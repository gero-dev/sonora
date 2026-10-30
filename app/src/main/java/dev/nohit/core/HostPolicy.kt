package dev.nohit.core

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Сопоставление имени хоста с шаблоном из [HostPolicy].
 *
 * Чистые функции без сети и Android — покрыты юнит-тестами.
 *
 * Поддерживаемые формы шаблона (регистр не важен, точка в конце отбрасывается):
 *  * `example.com` — сам хост и все его поддомены (`a.example.com`, `a.b.example.com`);
 *  * `*.example.com` — поддомены любого уровня **и** сам `example.com`;
 *  * `google*.com`, `a.b*` — glob, где `*` — любая последовательность символов (включая точки);
 *  * `?` — ровно один символ.
 *
 * Специально не поддерживаются регулярные выражения: список правил приходит
 * из настроек приложения, компилировать произвольный regex на каждое
 * соединение — лишний риск и стоимость.
 */
object HostMatcher {

    /** Кэш скомпилированных glob-шаблонов: правил мало, соединений много. */
    private const val MAX_CACHED = 256
    private val compiled = ConcurrentHashMap<String, Regex>()

    /** Приводит хост/шаблон к каноническому виду: без пробелов, нижний регистр, без точки в конце. */
    fun normalize(value: String): String = value.trim().lowercase().trimEnd('.')

    /** Совпадает ли [host] с одним [pattern] (см. правила в KDoc класса). */
    fun matches(host: String, pattern: String): Boolean {
        val h = normalize(host)
        val p = normalize(pattern)
        if (h.isEmpty() || p.isEmpty()) return false
        if (p == h) return true
        if (p.indexOf('*') < 0 && p.indexOf('?') < 0) {
            // Голый домен покрывает поддомены: "youtube.com" → "rr1.googlevideo..." нет,
            // но "youtube.com" → "www.youtube.com" да.
            return h.endsWith("." + p)
        }
        // "*.example.com" дополнительно покрывает apex-домен "example.com".
        if (p.startsWith("*.")) {
            val apex = p.substring(2)
            if (apex.isNotEmpty() && (h == apex || h.endsWith("." + apex))) return true
        }
        return globRegex(p).matches(h)
    }

    /** Совпадает ли [host] хотя бы с одним шаблоном из [patterns]. */
    fun matchesAny(host: String, patterns: List<String>): Boolean {
        if (patterns.isEmpty()) return false
        for (pattern in patterns) {
            if (matches(host, pattern)) return true
        }
        return false
    }

    private fun globRegex(pattern: String): Regex {
        compiled[pattern]?.let { return it }
        val builder = StringBuilder()
        var index = 0
        while (index < pattern.length) {
            when (val char = pattern[index]) {
                '*' -> {
                    builder.append(".*")
                    index++
                }

                '?' -> {
                    builder.append('.')
                    index++
                }

                else -> {
                    var end = index
                    while (end < pattern.length && pattern[end] != '*' && pattern[end] != '?') end++
                    builder.append(Regex.escape(pattern.substring(index, end)))
                    index = end
                }
            }
        }
        val regex = Regex(builder.toString())
        if (compiled.size >= MAX_CACHED) compiled.clear()
        compiled[pattern] = regex
        return regex
    }
}

/**
 * Правила по доменам (CORE-5): какие хосты **не** трогать фрагментацией.
 *
 * Решение принимается по имени хоста, которое знает [NohitDns] при резолве,
 * и запоминается для полученных IP (адрес нужен [FragSocketFactory] в `connect()`,
 * где URL уже недоступен — и не должен быть доступен: мы не логируем URL).
 *
 * Логика:
 *  1. [skipHosts] имеет приоритет: совпадение → фрагментация выключена;
 *  2. если [fragmentHosts] не пуст, это whitelist: фрагментируем только совпавшие;
 *  3. иначе фрагментируем всё (поведение по умолчанию, как до CORE-5).
 */
data class HostPolicy(
    val skipHosts: List<String> = emptyList(),
    val fragmentHosts: List<String> = emptyList()
) {

    /** Включены ли правила вообще. Если нет — [FragSocketFactory] ведёт себя как раньше. */
    val isEnabled: Boolean
        get() = skipHosts.isNotEmpty() || fragmentHosts.isNotEmpty()

    /** Нужно ли переупаковывать ClientHello для [host]. Пустой/неизвестный хост — да. */
    fun shouldFragment(host: String): Boolean {
        if (host.isBlank()) return true
        if (HostMatcher.matchesAny(host, skipHosts)) return false
        if (fragmentHosts.isNotEmpty()) return HostMatcher.matchesAny(host, fragmentHosts)
        return true
    }

    companion object {
        val DEFAULT = HostPolicy()
    }
}

/**
 * Таблица «IP → надо ли фрагментировать», заполняемая [NohitDns] при резолве.
 *
 * Это компромисс: `SocketFactory` не знает имя хоста (TLS ещё не начат),
 * поэтому решение переносится по адресу. У CDN один IP может обслуживать
 * разные домены; конфликт решается в пользу «не фрагментировать» (безопаснее
 * для банков/госуслуг). Записи стареют и чистятся при смене сети.
 */
internal object ResolvedHostTable {

    private const val MAX_ENTRIES = 4096
    private const val TTL_MS = 10 * 60 * 1000L

    private data class Decision(val shouldFragment: Boolean, val atMs: Long)

    private val decisions = ConcurrentHashMap<String, Decision>()

    fun record(addresses: List<InetAddress>, shouldFragment: Boolean) {
        if (addresses.isEmpty()) return
        val now = System.currentTimeMillis()
        for (address in addresses) {
            val key = keyOf(address) ?: continue
            decisions.compute(key) { _, previous ->
                val fresh = previous?.takeIf { now - it.atMs <= TTL_MS }
                val value = when {
                    fresh == null -> shouldFragment
                    // «не фрагментировать» сильнее: skip-правило не должно теряться.
                    else -> fresh.shouldFragment && shouldFragment
                }
                Decision(value, now)
            }
        }
        if (decisions.size > MAX_ENTRIES) evict(now)
    }

    /** `true` — фрагментировать, `false` — нет, `null` — хост нам неизвестен (по умолчанию фрагментируем). */
    fun shouldFragment(address: InetAddress): Boolean? {
        val key = keyOf(address) ?: return null
        val decision = decisions[key] ?: return null
        if (System.currentTimeMillis() - decision.atMs > TTL_MS) {
            decisions.remove(key)
            return null
        }
        return decision.shouldFragment
    }

    fun clear() {
        decisions.clear()
    }

    private fun evict(now: Long) {
        decisions.entries.removeIf { now - it.value.atMs > TTL_MS }
        if (decisions.size > MAX_ENTRIES) decisions.clear()
    }

    private fun keyOf(address: InetAddress): String? =
        address.hostAddress?.takeIf { it.isNotEmpty() }
}

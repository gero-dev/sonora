package dev.nohit.core

import java.security.MessageDigest

/**
 * Список UDP-серверов GeoHide с версией + разбор официального hosts-файла
 * `https://geohide.ru/hosts`.
 *
 * Всё здесь — чистые функции (без Android и сети), поэтому покрыто юнит-тестами:
 *  * [snapshot] — зашитый снимок, который всегда доступен офлайн;
 *  * [parseHostsFile] — вытаскивает блок «Эти IP-адреса принадлежат GeoHide DNS»;
 *  * [sanitize] — дедупликация, валидация IPv4, allowlist подсетей GeoHide
 *    ([ALLOWED_CIDRS], M-6), лимит количества;
 *  * [encode]/[decode] — компактный формат кэша в SharedPreferences с checksum.
 *
 * Телеметрии нет: список — это только адреса DNS-серверов, ничего о пользователе.
 */
object GeoHideList {

    /** Версия зашитого снимка (дата сверки), попадает в UI/логи как версия списка. */
    const val SNAPSHOT_VERSION: String = "2026-10-08"

    /** Официальный источник списка: hosts-файл GeoHide (RU). */
    const val SOURCE_URL: String = "https://geohide.ru/hosts"

    /**
     * Зеркало в GitHub (как запасной источник). Сейчас апстрим-репозиторий пуст,
     * поэтому используется только после основного; сбой любого источника
     * не ломает резолвер — остаётся last-good или [SNAPSHOT_SERVERS].
     */
    const val MIRROR_URL: String = "https://raw.githubusercontent.com/Internet-Helper/GeoHideDNS/main/hosts"

    /** Больше серверов не нужно: резолвер всё равно перебирает их по порядку. */
    const val MAX_SERVERS: Int = 32

    /** Формат кэша; менять при несовместимом изменении полей. */
    private const val FORMAT: String = "nohit-geohide/1"

    /** Снимок 08.10.2026: сверен со status.dns.geohide.ru, 7 рабочих серверов. */
    val SNAPSHOT_SERVERS: List<String> = listOf(
        "193.233.112.67",
        "193.233.112.68",
        "193.233.112.88",
        "37.230.192.51",
        "46.8.158.6",
        "159.194.200.33",
        "45.155.204.190"
    )

    /**
     * Allowlist подсетей GeoHide (M-6).
     *
     * Назначение: даже если источник/зеркало hosts-файла скомпрометированы
     * (или HTTPS перехвачен rogue-CA), в `udpServers` попадут только адреса
     * из этих подсетей. Дополнительно каждый адрес обязан быть публичным
     * ([AddressClassifier.isPublic]), т.е. приватные/loopback/link-local
     * заведомо отбрасываются — даже если подсеть формально совпала.
     *
     * ВАЖНО (происхождение списка): GeoHide не публикует официальный перечень
     * CIDR, поэтому блоки выведены из фактических адресов [SNAPSHOT_SERVERS]
     * (снимок [SNAPSHOT_VERSION]) с запасом до /24 провайдера:
     * ```
     * 193.233.112.67, .68, .88  -> 193.233.112.0/24
     * 37.230.192.51              -> 37.230.192.0/24
     * 46.8.158.6                 -> 46.8.158.0/24
     * 159.194.200.33             -> 159.194.200.0/24
     * 45.155.204.190             -> 45.155.204.0/24
     * ```
     * Следствия, которые нужно понимать:
     *  * новый сервер GeoHide в другой подсети будет **отклонён** обновлением —
     *    останется last-good/снимок, резолвер не сломается, но новый сервер
     *    не появится до обновления библиотеки (сознательный fail-closed выбор:
     *    аудит M-6 требует аутентичности, а не максимальной гибкости);
     *  * список версионируется вместе с библиотекой — при смене подсетей
     *    GeoHide его нужно расширить и выпустить релиз.
     */
    val ALLOWED_CIDRS: List<String> = listOf(
        "193.233.112.0/24",
        "37.230.192.0/24",
        "46.8.158.0/24",
        "159.194.200.0/24",
        "45.155.204.0/24"
    )

    /**
     * Годится ли [value] в качестве UDP-сервера GeoHide: корректный IPv4,
     * публичный и входящий в [ALLOWED_CIDRS]. Всё остальное — `false`
     * (fail-closed).
     */
    fun isAllowedServer(value: String): Boolean {
        val raw = ipv4Bytes(value) ?: return false
        if (!AddressClassifier.isPublic(raw)) return false
        return ALLOWED_CIDRS.any { cidr -> AddressClassifier.ipv4InCidr(raw, cidr) }
    }

    /**
     * Состояние списка: серверы, версия источника (дата hosts-файла или снимка),
     * checksum и время загрузки (для TTL обновления).
     */    data class Snapshot(
        val servers: List<String>,
        val version: String,
        val fetchedAtMs: Long = 0L
    ) {
        val checksum: String get() = checksum(servers)
    }

    /** Зашитый снимок как [Snapshot] (всегда валиден). */
    fun snapshot(): Snapshot = Snapshot(servers = SNAPSHOT_SERVERS, version = SNAPSHOT_VERSION)

    /** IPv4 в точечной нотации, 4 октета 0..255. */
    fun isValidIpv4(value: String): Boolean = ipv4Bytes(value) != null

    /** IPv4-строка -> 4 байта; `null`, если это не корректный IPv4. */
    private fun ipv4Bytes(value: String): ByteArray? {
        val parts = value.trim().split('.')
        if (parts.size != 4) return null
        val result = ByteArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3) return null
            if (!part.all { it in '0'..'9' }) return null
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            result[index] = octet.toByte()
        }
        return result
    }

    /**
     * Дедупликация с сохранением порядка; отбрасываются не-IPv4, приватные
     * адреса и всё, что не входит в [ALLOWED_CIDRS] (M-6); лимит [MAX_SERVERS].
     */
    fun sanitize(raw: List<String>): List<String> {
        val result = ArrayList<String>(minOf(raw.size, MAX_SERVERS))
        for (item in raw) {
            val candidate = item.trim()
            if (!isAllowedServer(candidate)) continue
            if (result.contains(candidate)) continue
            result.add(candidate)
            if (result.size >= MAX_SERVERS) break
        }
        return result
    }

    /**
     * Разбор hosts-файла GeoHide.
     *
     * Формат (сокращённо):
     * ```
     * ### geohide.ru: hosts file
     * # Последнее обновление: 20.10.2026 04:56:02
     * # Регион серверов: RU
     * # Эти IP-адреса принадлежат GeoHide DNS:
     * # 159.194.200.33
     * # 193.233.112.67
     * # Остальные IP-адреса принадлежат сторонним сервисам.
     * ...
     * 159.194.200.33 www.example.com
     * ```
     * Берём только комментарии-адреса сразу после маркера: остальные строки —
     * это уже подмена доменов, которая нам не нужна.
     *
     * Если маркер не найден, пробуем «простой список»: по одному IP в строке.
     * Битая/пустая загрузка → пустой список (вызывающий обязан оставить last-good).
     */
    fun parseHostsFile(text: String): List<String> {
        val lines = text.split('\n').map { it.trim() }
        val marker = lines.indexOfFirst { line ->
            line.startsWith("#") && run {
                val lower = line.lowercase()
                lower.contains("принадлежат") && lower.contains("geohide")
            }
        }
        val parsed = if (marker >= 0) parseMarkerBlock(lines, marker) else parsePlainList(lines)
        return sanitize(parsed)
    }

    /** Версия списка из строки `# Последнее обновление: ...` (для UI/логов). */
    fun parseVersion(text: String): String? {
        val marker = text.split('\n').firstOrNull { it.trim().startsWith("#") && it.contains("обновление") }
            ?: return null
        val value = marker.substringAfter(':', "").trim()
        return value.ifEmpty { null }
    }

    /** SHA-256 по каноническому представлению списка (порядок важен). */
    fun checksum(servers: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val payload = FORMAT + "\n" + servers.joinToString("\n")
        val hash = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { byte -> "%02x".format(byte) }
    }

    /** Сериализация кэша; формат однострочный по полям, серверы — через пробел. */
    fun encode(snapshot: Snapshot): String {
        val servers = sanitize(snapshot.servers)
        val version = snapshot.version.replace('\n', ' ').replace('\r', ' ').trim()
        return buildString {
            appendLine(FORMAT)
            appendLine("version=$version")
            appendLine("fetched=${snapshot.fetchedAtMs}")
            appendLine("checksum=${checksum(servers)}")
            append("servers=").append(servers.joinToString(" "))
        }
    }

    /**
     * Разбор кэша из SharedPreferences. Возвращает `null`, если строка битая,
     * пустая, без серверов или checksum не совпал — вызывающий берёт last-good
     * или зашитый снимок.
     */
    fun decode(raw: String?): Snapshot? {
        if (raw.isNullOrBlank()) return null
        val fields = HashMap<String, String>(4)
        var first = true
        for (line in raw.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (first) {
                first = false
                if (trimmed != FORMAT) return null
                continue
            }
            val separator = trimmed.indexOf('=')
            if (separator <= 0) return null
            fields[trimmed.substring(0, separator)] = trimmed.substring(separator + 1)
        }
        val servers = sanitize(fields["servers"]?.split(' ') ?: return null)
        if (servers.isEmpty()) return null
        val expected = fields["checksum"] ?: return null
        if (!expected.equals(checksum(servers), ignoreCase = true)) return null
        val fetchedAt = fields["fetched"]?.toLongOrNull() ?: 0L
        val version = fields["version"]?.takeIf { it.isNotBlank() } ?: SNAPSHOT_VERSION
        return Snapshot(servers = servers, version = version, fetchedAtMs = fetchedAt)
    }

    private fun parseMarkerBlock(lines: List<String>, marker: Int): List<String> {
        val result = ArrayList<String>()
        var index = marker + 1
        while (index < lines.size && result.size < MAX_SERVERS) {
            val line = lines[index]
            if (line.isEmpty() || !line.startsWith("#")) break
            val value = line.trimStart('#').trim()
            if (!isValidIpv4(value)) break
            result.add(value)
            index++
        }
        return result
    }

    private fun parsePlainList(lines: List<String>): List<String> {
        val result = ArrayList<String>()
        for (line in lines) {
            val value = line.trim()
            if (value.isEmpty() || value.startsWith("#")) continue
            // Строка hosts-файла вида "1.2.3.4 host" — это подмена домена, не сервер.
            val parts = value.split(' ', '\t')
            if (parts.size != 1 || !isValidIpv4(parts[0])) continue
            result.add(parts[0])
        }
        return result
    }
}

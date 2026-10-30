package dev.nohit.core

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Автообновление списка UDP-серверов GeoHide (CORE-4).
 *
 * Зачем: список IP — внешние данные, они меняются чаще, чем выходят релизы
 * библиотеки. Обновление позволяет не выпускать новую версию ради новых серверов.
 *
 * Как работает:
 *  1. при [NoHiT.init] и не чаще [REFRESH_INTERVAL_MS] в фоне скачивается hosts-файл
 *     GeoHide ([GeoHideList.SOURCE_URL], при сбое — зеркало [GeoHideList.MIRROR_URL]);
 *     только по HTTPS, без понижения схемы редиректом (M-6);
 *  2. из него берётся только блок «Эти IP-адреса принадлежат GeoHide DNS»,
 *     тело проверяется на размер/формат, а серверы — на публичность и allowlist
 *     подсетей GeoHide ([GeoHideList.ALLOWED_CIDRS], [GeoHideList.sanitize]);
 *  3. валидный результат кладётся в SharedPreferences вместе с checksum и версией
 *     (last-good); при чтении checksum проверяется;
 *  4. при любой ошибке/пустой загрузке остаётся прежний last-good, а если его нет —
 *     зашитый снимок [GeoHideList.SNAPSHOT_SERVERS]. Резолвер не ломается никогда.
 *
 * Приватность: запрос — обычный GET публичного hosts-файла, без параметров,
 * без идентификаторов, без логирования URL и без телеметрии. Обновление
 * запускается только если GeoHide действительно есть в конфиге (см. [NoHiT.init]).
 */
object GeoHideUpdater {

    /** Не чаще одного раза в сутки. */
    const val REFRESH_INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

    /** Место в SharedPreferences: отдельный файл, чтобы не трогать [ConfigStore]. */
    private const val PREFS = "nohit_geohide"
    private const val KEY = "servers_v1"

    /** Ограничение на размер загрузки: реальный файл ~140 КБ. */
    private const val MAX_BYTES = 2 * 1024 * 1024

    /** Меньше этого hosts-файл быть не может — почти наверняка заглушка/ошибка. */
    private const val MIN_BYTES = 32

    /** Источники по порядку: официальный hosts-файл, затем зеркало. */
    val SOURCES: List<String> = listOf(GeoHideList.SOURCE_URL, GeoHideList.MIRROR_URL)

    @Volatile
    private var memory: GeoHideList.Snapshot? = null

    private val refreshing = AtomicBoolean(false)

    /** Активный снимок: кэш (если прошёл проверку checksum), иначе зашитый. */
    fun activeSnapshot(context: Context?): GeoHideList.Snapshot {
        memory?.let { return it }
        val cached = context?.let { loadCached(it) }
        val resolved = cached ?: GeoHideList.snapshot()
        memory = resolved
        return resolved
    }

    /** Активный список серверов для [DnsUpstream.udpServers]. */
    fun activeServers(context: Context?): List<String> = activeSnapshot(context).servers

    /** `DnsUpstream` с актуальным (кэшированным) списком серверов. */
    fun activeUpstream(context: Context?): DnsUpstream = DnsUpstream.GEOHIDE.copy(udpServers = activeServers(context))

    /** Подменяет GeoHide-апстримы в конфиге на актуальные. Остальные записи не трогает. */
    fun applyTo(cfg: NoHiTConfig, context: Context?): NoHiTConfig = applyServers(cfg, activeServers(context))

    /** Подменяет GeoHide-апстримы в конфиге на [servers] (аддитивно: id и имя сохраняются). */
    fun applyServers(cfg: NoHiTConfig, servers: List<String>): NoHiTConfig {
        if (servers.isEmpty()) return cfg
        if (cfg.dns.none { it.id == DnsUpstream.GEOHIDE.id }) return cfg
        var changed = false
        val updated = cfg.dns.map { upstream ->
            if (upstream.id != DnsUpstream.GEOHIDE.id || upstream.udpServers == servers) {
                upstream
            } else {
                changed = true
                upstream.copy(udpServers = servers)
            }
        }
        return if (changed) cfg.copy(dns = updated) else cfg
    }

    /** Пора ли обновлять список (нет кэша / истёк TTL). */
    fun isRefreshDue(context: Context?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val snapshot = activeSnapshot(context)
        if (snapshot.fetchedAtMs <= 0L) return true
        return nowMs - snapshot.fetchedAtMs >= REFRESH_INTERVAL_MS
    }

    /**
     * Синхронная попытка обновления. Возвращает новый список серверов или `null`,
     * если все источники недоступны/битые (тогда ничего не меняется).
     *
     * Не блокирует UI только если вызывать из фонового потока — см. [refreshInBackground].
     */
    fun refresh(
        context: Context,
        sources: List<String> = SOURCES,
        timeoutMs: Int = 10_000
    ): List<String>? {
        if (!refreshing.compareAndSet(false, true)) return null
        try {
            val app = context.applicationContext
            for (source in sources) {
                // M-6: обновление только по HTTPS — http-источник даже не трогаем.
                if (!isHttps(source)) continue
                val text = fetch(source, timeoutMs) ?: continue
                if (!isValidPayload(text)) continue
                val servers = GeoHideList.parseHostsFile(text)
                if (servers.isEmpty()) continue
                val version = GeoHideList.parseVersion(text) ?: activeSnapshot(app).version
                val snapshot = GeoHideList.Snapshot(
                    servers = servers,
                    version = version,
                    fetchedAtMs = System.currentTimeMillis()
                )
                store(app, snapshot)
                memory = snapshot
                return servers
            }
            return null
        } catch (_: Throwable) {
            return null
        } finally {
            refreshing.set(false)
        }
    }

    /**
     * Фоновое обновление «при старте»: не чаще [REFRESH_INTERVAL_MS],
     * никогда не бросает, не держит процесс и не грузит главный поток.
     *
     * [onUpdated] вызывается только при успешном обновлении — фасад пересобирает
     * клиенты с новым списком.
     */
    fun refreshInBackground(context: Context, onUpdated: (List<String>) -> Unit = {}) {
        val app = context.applicationContext
        if (!isRefreshDue(app)) return
        if (refreshing.get()) return
        val thread = Thread({
            val servers = try {
                refresh(app)
            } catch (_: Throwable) {
                null
            }
            if (servers != null) {
                try {
                    onUpdated(servers)
                } catch (_: Throwable) {
                    // приложение не должно падать из-за нашего колбэка
                }
            }
        }, "nohit-geohide-updater")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
    }

    /** Сбрасывает кэш и память (диагностика/тесты). Возвращает зашитый снимок. */
    fun reset(context: Context) {
        memory = null
        try {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY).apply()
        } catch (_: Throwable) {
            // нет доступа к prefs — не критично
        }
    }

    private fun loadCached(context: Context): GeoHideList.Snapshot? = try {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        GeoHideList.decode(raw)
    } catch (_: Throwable) {
        null
    }

    private fun store(context: Context, snapshot: GeoHideList.Snapshot) {
        try {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, GeoHideList.encode(snapshot)).apply()
        } catch (_: Throwable) {
            // кэш — оптимизация; в памяти снимок всё равно актуален
        }
    }

    /**
     * M-6: обновление только по HTTPS. Проверяется и схема источника, и
     * фактический URL после редиректов (см. [fetch]); `http://` не загружается
     * никогда — иначе MITM/rogue-CA мог бы подменить список по открытому каналу.
     */
    internal fun isHttps(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.length > "https://".length && trimmed.startsWith("https://", ignoreCase = true)
    }

    /**
     * M-6: проверка размера/формата тела до парсинга.
     *
     * Отклоняем пустое/слишком короткое/слишком большое тело, NUL-байты,
     * управляющие символы (кроме `\n\r\t`) и явный HTML — страницу-заглушку
     * captive portal или ошибку прокси вместо hosts-файла. После этого тело
     * всё равно разбирается как hosts-файл и фильтруется по allowlist
     * ([GeoHideList.sanitize]); пустой результат = отказ и сохранение last-good.
     */
    internal fun isValidPayload(text: String): Boolean {
        if (text.length < MIN_BYTES || text.length > MAX_BYTES) return false
        for (ch in text) {
            if (ch == '\u0000') return false
            if (ch < ' ' && ch != '\n' && ch != '\r' && ch != '\t') return false
        }
        val lower = text.lowercase()
        if (lower.contains("<html") || lower.contains("<!doctype") || lower.contains("<script")) return false
        return true
    }

    /**
     * GET списка. Возвращает `null` при любой проблеме: сеть, HTTP-код,
     * слишком большой ответ. URL нигде не логируется и в исключение не попадает.
     *
     * M-6: только HTTPS. OK, что редиректы https→http не следуются
     * ([okhttp3.OkHttpClient.Builder.followSslRedirects] = false): понижение
     * схемы — это ровно тот вектор, от которого защищаемся.
     */
    private fun fetch(url: String, timeoutMs: Int): String? {
        if (!isHttps(url)) return null
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .followSslRedirects(false)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/plain, */*")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            // После same-scheme редиректов ещё раз убеждаемся, что канал HTTPS.
            if (!response.request.url.isHttps) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            val stream = body.byteStream()
            val buffer = ByteArrayOutputStream(16 * 1024)
            val chunk = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                total += read
                if (total > MAX_BYTES) return null
                buffer.write(chunk, 0, read)
            }
            return buffer.toString("UTF-8")
        }
    }
}

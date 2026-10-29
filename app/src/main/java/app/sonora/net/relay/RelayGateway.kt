package app.sonora.net.relay

import app.sonora.core.net.Http
import app.sonora.data.remote.ProbeResult
import app.sonora.data.remote.StreamProbe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Тип публичного зеркала YouTube. */
enum class RelayKind {
    /** Invidious: `/latest_version?id=…&itag=…&local=true` отдаёт медиа через себя. */
    INVIDIOUS,

    /** Piped: `/streams/<id>` возвращает аудио-URL, часто уже через piped-proxy. */
    PIPED,
}

/** Одно зеркало: базовый адрес без хвостового слэша. */
data class RelayInstance(
    val base: String,
    val kind: RelayKind,
) {
    val label: String
        get() = base.removePrefix("https://").removePrefix("http://").trimEnd('/')

    companion object {
        /**
         * Разбирает строку из настроек: полный URL или просто хост.
         * Тип определяется по имени хоста: `piped` → Piped, иначе Invidious.
         */
        fun parse(raw: String): RelayInstance? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isBlank()) return null
            val base = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
            if (!base.removePrefix("https://").removePrefix("http://").contains('.')) {
                return null
            }
            val kind = if (base.contains("piped", ignoreCase = true)) {
                RelayKind.PIPED
            } else {
                RelayKind.INVIDIOUS
            }
            return RelayInstance(base, kind)
        }
    }
}

/** Аудио-поток, полученный через зеркало. */
data class RelayStream(
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val headers: Map<String, String> = emptyMap(),
    /** Какое зеркало отдало поток: показываем в диагностике. */
    val instance: String,
    val expiresAtMs: Long,
)

/** Результат проверки зеркала для настроек. */
data class RelayHealth(
    val instance: RelayInstance,
    val ok: Boolean,
    val latencyMs: Long,
    val detail: String,
)

/**
 * Медиашлюз: резервный путь получения звука YouTube через публичные
 * Invidious/Piped-зеркала — без VPN и WARP на устройстве.
 *
 * Зачем: если провайдер или YouTube блокируют `googlevideo` по IP, прямой URL
 * не заиграет ни у InnerTube, ни у yt-dlp. Зеркало качает медиа своим
 * «выходом» и отдаёт нам готовый аудиопоток.
 *
 * Правила:
 *  - используется только когда прямой путь не сработал;
 *  - каждая ссылка проверяется настоящим Range-запросом до отдачи в плеер;
 *  - порядок зеркал: последнее рабочее → встроенный список → пользовательский;
 *  - ссылки кэшируются на 5 минут и не попадают в очередь.
 */
object RelayGateway {

    private const val TAG = "Relay"
    private const val CACHE_TTL_MS = 5 * 60_000L
    private const val RENEW_BEFORE_MS = 60_000L
    private const val TOTAL_TIMEOUT_MS = 14_000L
    private const val INSTANCE_TIMEOUT_MS = 9_000L
    private const val HEALTH_TIMEOUT_MS = 25_000L
    private const val MAX_PARALLEL = 6

    /** Сколько зеркал проверяем health-check'ом: список автопоиска длинный. */
    private const val HEALTH_LIMIT = 16

    /**
     * Встроенный список зеркал. Публичные инстансы живут недолго, поэтому
     * список дополняется автопоиском (`api.invidious.io/instances.json`,
     * доступен и из РФ) и проверяется health-check'ом в настройках.
     * Порядок: сначала зеркала с работающим медиа-прокси.
     */
    private val DEFAULT_INSTANCES: List<RelayInstance> = listOf(
        "https://yt.chocolatemoo53.com",
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://yewtu.be",
        "https://inv.tux.pizza",
        "https://invidious.materialio.us",
        "https://inv.us.projectsegfau.lt",
        "https://pipedapi.kavin.rocks",
        "https://api.piped.private.coffee",
        "https://pipedapi.ducks.party",
    ).mapNotNull { RelayInstance.parse(it) }

    private const val DISCOVERY_URL = "https://api.invidious.io/instances.json"
    private const val DISCOVERY_TTL_MS = 6 * 60 * 60 * 1000L
    private const val MAX_DISCOVERED = 40

    private data class Cached(
        val stream: RelayStream,
        val expiresAtMs: Long,
        val savedAtMs: Long,
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    @Volatile
    private var settings: app.sonora.core.settings.SettingsStore? = null

    @Volatile
    private var lastGoodLabel: String? = null

    @Volatile
    private var lastHealth: List<RelayHealth> = emptyList()

    /** Найденные автопоиском зеркала и время поиска (кэш в памяти процесса). */
    @Volatile
    private var discovered: List<RelayInstance> = emptyList()

    @Volatile
    private var discoveredAtMs: Long = 0L

    fun init(settings: app.sonora.core.settings.SettingsStore) {
        this.settings = settings
    }

    /** Пользователь не выключил шлюз в настройках (по умолчанию включён). */
    fun isEnabled(): Boolean = settings?.current?.relayEnabled ?: true

    /** Принудительно только шлюз: выбрано «Шлюз» в источнике потока. */
    fun isOnly(): Boolean =
        settings?.current?.streamSource == app.sonora.core.settings.StreamSource.RELAY

    /** Проверять ли ссылки Range-запросом перед отдачей в плеер. */
    fun probeBeforePlay(): Boolean = settings?.current?.probeBeforePlay ?: true

    fun lastUsed(): String? = lastGoodLabel

    fun healthSnapshot(): List<RelayHealth> = lastHealth

    fun defaults(): List<RelayInstance> = DEFAULT_INSTANCES

    /**
     * Итоговый список зеркал:
     *  1. свой список из настроек, если задан;
     *  2. найденные автопоиском + встроенные (свежие первыми);
     *  3. встроенный список.
     */
    fun instances(): List<RelayInstance> {
        val raw = settings?.current?.relayInstances.orEmpty()
        val custom = raw
            .split('\n', ',', ';', ' ')
            .mapNotNull { RelayInstance.parse(it) }
            .distinctBy { it.base }
        if (custom.isNotEmpty()) return custom
        val discoveredFresh = discovered.isNotEmpty() &&
            System.currentTimeMillis() - discoveredAtMs < DISCOVERY_TTL_MS
        val merged = if (discoveredFresh) {
            // Проверенные встроенные — первыми, автопоиск их дополняет.
            (DEFAULT_INSTANCES + discovered).distinctBy { it.base }
        } else {
            DEFAULT_INSTANCES
        }
        return merged.take(MAX_DISCOVERED)
    }

    /**
     * Автопоиск зеркал: официальный список `api.invidious.io/instances.json`
     * (доступен из РФ без VPN). Ошибка сети не критична — работаем на
     * встроенном списке.
     */
    suspend fun discover(): List<RelayInstance> {
        if (System.currentTimeMillis() - discoveredAtMs < DISCOVERY_TTL_MS && discovered.isNotEmpty()) {
            return discovered
        }
        val raw = runCatching { Http.get(DISCOVERY_URL) }.getOrNull() ?: return discovered
        val parsed = runCatching {
            val root = Http.parseJson(raw) as? kotlinx.serialization.json.JsonArray
                ?: return@runCatching emptyList()
            root.mapNotNull { entry ->
                val pair = entry as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
                val host = (pair.firstOrNull() as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: return@mapNotNull null
                val info = pair.getOrNull(1) as? kotlinx.serialization.json.JsonObject
                val type = (info?.get("type") as? kotlinx.serialization.json.JsonPrimitive)?.content
                if (type != null && type != "https") return@mapNotNull null
                RelayInstance.parse("https://$host")
            }.distinctBy { it.base }
        }.getOrDefault(emptyList())
        if (parsed.isNotEmpty()) {
            discovered = parsed
            discoveredAtMs = System.currentTimeMillis()
            android.util.Log.i(TAG, "автопоиск: ${parsed.size} зеркал")
        }
        return discovered.ifEmpty { DEFAULT_INSTANCES }
    }

    /**
     * Пытается получить аудио через зеркала. Возвращает первый поток, который
     * реально отвечает на Range-запрос. null — ни одно зеркало не справилось.
     */
    suspend fun resolve(videoId: String, targetKbps: Int?): RelayStream? {
        if (!isEnabled()) return null
        if (videoId.isBlank()) return null
        val cacheKey = "$videoId|${targetKbps ?: 0}"
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs - now > RENEW_BEFORE_MS) return cached.stream
        }

        val ordered = orderedInstances()
        if (ordered.isEmpty()) return null

        val found = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            val results = channelFlow<RelayStream?> {
                ordered.take(MAX_PARALLEL).forEach { instance ->
                    launch {
                        send(resolveOne(instance, videoId, targetKbps))
                    }
                }
            }
            results.firstOrNull { it != null }
        }

        if (found != null) {
            cache[cacheKey] = Cached(
                stream = found,
                expiresAtMs = minOf(found.expiresAtMs, System.currentTimeMillis() + CACHE_TTL_MS),
                savedAtMs = System.currentTimeMillis(),
            )
            lastGoodLabel = found.instance
            android.util.Log.i(TAG, "поток через ${found.instance}")
        }
        return found
    }

    /**
     * Проверяет зеркала (для настроек): сначала автопоиск, затем реальный
     * запрос аудио к каждому из первых [HEALTH_LIMIT]. Результат запоминается,
     * и при воспроизведении рабочие зеркала идут первыми.
     */
    suspend fun healthCheck(videoId: String = "dQw4w9WgXcQ"): List<RelayHealth> {
        discover()
        val all = instances().take(HEALTH_LIMIT)
        if (all.isEmpty()) {
            lastHealth = emptyList()
            return emptyList()
        }
        val result = kotlinx.coroutines.withTimeoutOrNull(HEALTH_TIMEOUT_MS) {
            kotlinx.coroutines.coroutineScope {
                all.map { instance ->
                    async { checkOne(instance, videoId) }
                }.awaitAll()
            }
        } ?: all.map { RelayHealth(it, false, 0, "таймаут") }
        lastHealth = result.sortedWith(
            compareByDescending<RelayHealth> { it.ok }.thenBy { it.latencyMs },
        )
        lastHealth.firstOrNull { it.ok }?.let { lastGoodLabel = it.instance.label }
        return lastHealth
    }

    fun clear() = cache.clear()

    // ------------------------------------------------------------------ inside

    private fun orderedInstances(): List<RelayInstance> {
        val list = instances()
        val working = lastHealth.filter { it.ok }.map { it.instance }
        val priority = LinkedHashMap<String, RelayInstance>()
        (listOfNotNull(lastGoodLabel?.let { label -> list.firstOrNull { it.label == label } }) +
            working +
            list).forEach { priority.putIfAbsent(it.base, it) }
        return priority.values.toList()
    }

    private suspend fun resolveOne(
        instance: RelayInstance,
        videoId: String,
        targetKbps: Int?,
    ): RelayStream? = kotlinx.coroutines.withTimeoutOrNull(INSTANCE_TIMEOUT_MS) {
        when (instance.kind) {
            RelayKind.INVIDIOUS -> resolveInvidious(instance, videoId, targetKbps)
            RelayKind.PIPED -> resolvePiped(instance, videoId, targetKbps)
        }
    }

    /**
     * Invidious: `/api/v1/videos/<id>?local=true` — зеркало само качает
     * googlevideo и отдаёт уже проксированные ссылки. Берём аудио-форматы,
     * предпочитая те, что ведут на сам инстанс, а не на googlevideo
     * (последний в РФ обычно заблокирован).
     */
    private suspend fun resolveInvidious(
        instance: RelayInstance,
        videoId: String,
        targetKbps: Int?,
    ): RelayStream? {
        val root = runCatching {
            Http.getJson(
                "${instance.base}/api/v1/videos/$videoId?local=true",
                mapOf("User-Agent" to INVIDIOUS_UA),
            )
        }.getOrNull() ?: return null
        val obj = root as? kotlinx.serialization.json.JsonObject ?: return null
        val array = obj["adaptiveFormats"] as? kotlinx.serialization.json.JsonArray ?: return null
        if (array.isEmpty()) return null

        data class Candidate(val url: String, val mime: String, val bitrate: Int, val proxied: Boolean)

        val candidates = array.mapNotNull { element ->
            val stream = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val type = (stream["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: return@mapNotNull null
            if (!type.startsWith("audio/", ignoreCase = true)) return@mapNotNull null
            val url = (stream["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val bitrate = (stream["bitrate"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toIntOrNull() ?: 0
            val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
            Candidate(
                url = url,
                mime = type.substringBefore(';').trim(),
                bitrate = bitrate,
                // Ссылка на самом инстансе — это его прокси, самый нужный вариант.
                proxied = !host.contains("googlevideo", ignoreCase = true),
            )
        }.sortedWith(
            compareByDescending<Candidate> { it.proxied }
                .thenBy { candidate ->
                    if (targetKbps == null) -candidate.bitrate
                    else kotlin.math.abs(candidate.bitrate - targetKbps * 1000)
                },
        ).take(4)

        for (candidate in candidates) {
            val probe = StreamProbe.probe(candidate.url, timeoutMs = 8_000L)
            if (probe.ok && probe.contentType.looksLikeAudio()) {
                return RelayStream(
                    url = candidate.url,
                    mimeType = candidate.mime,
                    bitrate = candidate.bitrate,
                    headers = mapOf("User-Agent" to INVIDIOUS_UA),
                    instance = instance.label,
                    expiresAtMs = System.currentTimeMillis() + CACHE_TTL_MS,
                )
            }
            android.util.Log.w(TAG, "${instance.label} itag=${candidate.bitrate}: ${probe.reason()}")
        }
        return null
    }

    /**
     * Piped: JSON `/streams/<id>` → `audioStreams[].url`. Часто уже через
     * piped-proxy; если ссылка ведёт на googlevideo напрямую — в РФ она
     * бесполезна, но проверка Range это и покажет.
     */
    private suspend fun resolvePiped(
        instance: RelayInstance,
        videoId: String,
        targetKbps: Int?,
    ): RelayStream? {
        val root = runCatching { Http.getJson("${instance.base}/streams/$videoId") }.getOrNull()
            ?: return null
        val obj = root as? kotlinx.serialization.json.JsonObject ?: return null
        val array = obj["audioStreams"] as? kotlinx.serialization.json.JsonArray ?: return null
        if (array.isEmpty()) return null

        data class Candidate(val url: String, val mime: String, val bitrate: Int)

        val candidates = array.mapNotNull { element ->
            val stream = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val url = (stream["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val mime = (stream["mimeType"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: "audio/mp4"
            val bitrate = (stream["bitrate"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toIntOrNull() ?: 0
            Candidate(url, mime, bitrate)
        }.sortedBy { candidate ->
            if (targetKbps == null) -candidate.bitrate
            else kotlin.math.abs(candidate.bitrate - targetKbps * 1000)
        }.take(3)

        for (candidate in candidates) {
            val probe: ProbeResult = StreamProbe.probe(candidate.url, timeoutMs = 8_000L)
            if (probe.ok && probe.contentType.looksLikeAudio()) {
                return RelayStream(
                    url = candidate.url,
                    mimeType = candidate.mime,
                    bitrate = candidate.bitrate,
                    headers = emptyMap(),
                    instance = instance.label,
                    expiresAtMs = System.currentTimeMillis() + CACHE_TTL_MS,
                )
            }
            android.util.Log.w(TAG, "${instance.label} piped: ${probe.reason()}")
        }
        return null
    }

    private suspend fun checkOne(instance: RelayInstance, videoId: String): RelayHealth {
        val started = System.currentTimeMillis()
        val stream = runCatching { resolveOne(instance, videoId, null) }.getOrNull()
        val elapsed = System.currentTimeMillis() - started
        return if (stream != null) {
            RelayHealth(instance, true, elapsed, "OK · ${stream.bitrate / 1000} кбит/с")
        } else {
            RelayHealth(instance, false, elapsed, "не отдало аудио")
        }
    }

    /**
     * Некоторые зеркала отвечают 206/200 на HTML-ошибку — контент-тип
     * защищает от ложного «OK». null (заголовка нет) считаем допустимым.
     */
    private fun String?.looksLikeAudio(): Boolean {
        val value = this?.lowercase() ?: return true
        return value.startsWith("audio/") ||
            value.startsWith("video/") ||
            value.contains("octet-stream") ||
            value.contains("mpegurl") ||
            value.contains("dash")
    }

    private const val INVIDIOUS_UA =
        "Mozilla/5.0 (Linux; Android 14; Sonora) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"
}

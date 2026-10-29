package app.sonora.data.remote.youtube.innertube

import app.sonora.core.settings.AudioQuality
import app.sonora.core.settings.StreamSource
import app.sonora.data.remote.StreamProbe
import app.sonora.data.remote.ytdlp.YtDlpBridge
import app.sonora.data.streaming.StreamingAutoPilot
import app.sonora.net.relay.RelayGateway
import com.metrolist.innertubex.extraction.AudioQuality as EngineQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Кэш прямых аудио-URL InnerTube.
 *
 * Ссылки googlevideo живут несколько часов, но перевыпускать их на каждый
 * `open()` источника всё равно дорого: ExoPlayer открывает поток при каждом
 * переходе и seek-е через границу чанка. Держим ссылку до истечения `expire`.
 *
 * Ключ кэша включает уровень качества, поэтому переключение «Низкое → Высокое»
 * не требует сброса кэша: для нового уровня просто резолвится своя ссылка.
 */
internal object InnerTubeStreams {

    private const val RENEW_BEFORE_MS = 60_000L

    /** Запас, ниже которого формат считается «попавшим» в целевой битрейт. */
    private const val BITRATE_TOLERANCE = 16_000

    /** Ссылка yt-dlp/шлюза живёт недолго: кэшируем её на 5 минут. */
    private const val RELAY_TTL_MS = 5 * 60_000L

    private val api = InnerTubeApi()
    private val cache = ConcurrentHashMap<String, StreamInfo>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun resolve(
        videoId: String,
        quality: AudioQuality = AudioQuality.AUTO,
    ): StreamInfo = cached("$videoId|${quality.name}") {
        resolveStream(videoId, quality)
    }

    /**
     * Цепочка источников. В РФ прямой `googlevideo` часто отдаёт 403 по IP,
     * поэтому каждая ссылка проверяется Range-запросом, а последним шагом
     * идёт медиашлюз без VPN/WARP:
     *
     *  1. InnerTubeX — быстрый и устойчивый путь, сам выбирает формат.
     *  2. Прямой `player`-ответ с целевым битрейтом (64/128/160 кбит/с).
     *  3. yt-dlp — обновлённый рантайм, перебор клиентов и IPv4/IPv6.
     *  4. Медиашлюз (Invidious/Piped) — если провайдер блокирует YouTube.
     *
     * Если пользователь выбрал «Шлюз» — сразу шаг 4; если «InnerTube» —
     * только шаги 1–2; если «yt-dlp» — сразу шаг 3.
     */
    private suspend fun resolveStream(videoId: String, level: AudioQuality): StreamInfo {
        val targetKbps = level.targetKbps
        val source = YtDlpBridge.source()

        if (source == StreamSource.RELAY) {
            return resolveViaRelay(videoId, targetKbps)
                ?: throw IllegalStateException(
                    "Медиашлюз не смог получить звук. Добавьте свои зеркала " +
                        "в Настройки → Поток и сеть или включите WARP.",
                )
        }
        if (source == StreamSource.YTDLP) {
            YtDlpBridge.resolve(videoId)?.let { return it.toStreamInfo(videoId) }
            throw IllegalStateException(
                "yt-dlp не получил играющую ссылку: YouTube блокирует медиа с вашего IP. " +
                    "Медиашлюз тоже не ответил — включите WARP или укажите прокси.",
            )
        }

        val failures = ArrayList<String>(4)

        // Автопилот: при необходимости поднимает WARP и задаёт порядок способов.
        StreamingAutoPilot.prepareForPlayback()
        if (StreamingAutoPilot.prefersRelayFirst()) {
            resolveViaRelay(videoId, targetKbps)?.let { return it }
        }
        if (StreamingAutoPilot.prefersYtDlpFirst() && source != StreamSource.INNERTUBE) {
            YtDlpBridge.resolve(videoId)?.let { return it.toStreamInfo(videoId) }
        }

        // 1. InnerTubeX — быстрый путь.
        var engineStream: StreamInfo? = null
        var engineFailure: Exception? = null
        try {
            engineStream = InnerTubeXEngine.resolve(videoId, level.engineQuality())
        } catch (error: Exception) {
            engineFailure = error
        }

        if (engineStream != null) {
            val playable = playableOrNull(engineStream)
            if (playable != null) {
                val tooHigh = targetKbps != null &&
                    playable.bitrate > targetKbps * 1000 + BITRATE_TOLERANCE
                if (!tooHigh) return playable
                // Нужен битрейт пониже — спросим прямой player-ответ.
                val direct = runCatching { api.stream(videoId, targetKbps) }.getOrNull()
                val directPlayable = direct?.let { playableOrNull(it) }
                return directPlayable ?: playable
            }
            failures += "InnerTubeX: ссылка не играет"
        } else {
            failures += "InnerTubeX: ${engineFailure?.message ?: "нет потока"}"
        }

        // 2. Прямой player-ответ InnerTube с целевым битрейтом.
        val direct = runCatching { api.stream(videoId, targetKbps) }.getOrNull()
        if (direct != null) {
            playableOrNull(direct)?.let { return it }
            failures += "InnerTube: ссылка не играет"
        } else {
            failures += "InnerTube: нет потока"
        }

        // 3–4. yt-dlp → медиашлюз без VPN/WARP.
        return resolveFallbacks(videoId, targetKbps, failures, source)
    }

    /** yt-dlp → медиашлюз; общая часть для AUTO-цепочки. */
    private suspend fun resolveFallbacks(
        videoId: String,
        targetKbps: Int?,
        failures: MutableList<String>,
        source: StreamSource,
    ): StreamInfo {
        // 3. yt-dlp: обновлённый рантайм сам перебирает клиентов и протоколы.
        if (source != StreamSource.INNERTUBE && YtDlpBridge.isEnabled()) {
            YtDlpBridge.resolve(videoId)?.let { return it.toStreamInfo(videoId) }
            failures += "yt-dlp: 403/нет ссылки"
        }

        // 4. Медиашлюз без VPN/WARP.
        if (source != StreamSource.INNERTUBE && RelayGateway.isEnabled()) {
            resolveViaRelay(videoId, targetKbps)?.let { return it }
            failures += "медиашлюз: зеркала не ответили"
        }

        throw IllegalStateException(
            "YouTube не отдал воспроизводимый поток (${failures.joinToString("; ")}). " +
                "Проверьте «Настройки → Поток и сеть → Проверить поток».",
        )
    }

    /** Проверка ссылки настоящим Range-запросом до отдачи в плеер. */
    private suspend fun playableOrNull(stream: StreamInfo): StreamInfo? {
        if (!RelayGateway.probeBeforePlay()) return stream
        // Bounded-range потоки нельзя проверять обычным Range — доверяем.
        if (stream.needsRangeRequests) return stream
        val probe = StreamProbe.probe(stream.url, stream.headers)
        return if (probe.ok) stream else null
    }

    private suspend fun resolveViaRelay(videoId: String, targetKbps: Int?): StreamInfo? {
        if (!RelayGateway.isEnabled()) return null
        val relay = RelayGateway.resolve(videoId, targetKbps) ?: return null
        return StreamInfo(
            videoId = videoId,
            url = relay.url,
            mimeType = relay.mimeType,
            bitrate = relay.bitrate,
            itag = 0,
            expiresAtMs = relay.expiresAtMs,
            headers = relay.headers,
        )
    }

    private fun app.sonora.data.remote.ytdlp.StreamHandle.toStreamInfo(
        videoId: String,
    ): StreamInfo = StreamInfo(
        videoId = videoId,
        url = url,
        mimeType = mimeType,
        bitrate = 0,
        itag = 0,
        expiresAtMs = System.currentTimeMillis() + RELAY_TTL_MS,
        headers = headers,
    )

    /**
     * Диагностика прямого пути: что именно отвечает InnerTubeX/player и играет
     * ли полученная ссылка. Возвращает короткий отчёт для «Проверить поток».
     */
    internal suspend fun diagnoseDirect(videoId: String): String {
        val lines = ArrayList<String>(2)
        val engine = runCatching { InnerTubeXEngine.resolve(videoId, EngineQuality.AUTO) }
        val engineStream = engine.getOrNull()
        if (engineStream != null) {
            val probe = StreamProbe.probe(engineStream.url, engineStream.headers)
            if (probe.ok) {
                return "InnerTubeX: OK · ${engineStream.bitrate / 1000} кбит/с · ${probe.elapsedMs} мс"
            }
            lines += "InnerTubeX: ${probe.reason()}"
        } else {
            lines += "InnerTubeX: ${engine.exceptionOrNull()?.message?.take(80) ?: "нет потока"}"
        }
        val direct = runCatching { api.stream(videoId) }
        val directStream = direct.getOrNull()
        if (directStream != null) {
            val probe = StreamProbe.probe(directStream.url, directStream.headers)
            if (probe.ok) {
                return "InnerTube: OK · ${directStream.bitrate / 1000} кбит/с · ${probe.elapsedMs} мс"
            }
            lines += "InnerTube: ${probe.reason()}"
        } else {
            lines += "InnerTube: ${direct.exceptionOrNull()?.message?.take(80) ?: "нет потока"}"
        }
        return lines.joinToString(" · ")
    }

    /** MP4-поток для скачивания: m4a лучше подходит для офлайн-файла. */
    suspend fun resolveDownload(videoId: String): StreamInfo =
        cached("$videoId|MP4") {
            runCatching { InnerTubeXEngine.resolve(videoId, EngineQuality.MP4) }
                .recoverCatching { api.stream(videoId) }
                .recoverCatching {
                    // Прямой CDN может быть заблокирован — шлюз отдаёт тот же файл.
                    resolveViaRelay(videoId, targetKbps = 128) ?: throw it
                }
                .getOrThrow()
        }

    /**
     * Резервный источник для скачивания: обычный `player`-ответ InnerTube без
     * InnerTubeX. Нужен, когда MP4-поток InnerTubeX отдаёт ссылку, которую
     * сервер затем отклоняет (403/410/416).
     */
    suspend fun resolveDownloadFallback(videoId: String): StreamInfo {
        val cacheKey = "$videoId|MP4"
        val resolved = api.stream(videoId)
        if (!resolved.mimeType.startsWith("audio/", ignoreCase = true)) {
            throw IllegalStateException(
                "YouTube отдал только видеопоток. Sonora играет только музыку (audio).",
            )
        }
        cache[cacheKey] = resolved
        return resolved
    }

    /**
     * Синхронный вариант для `ResolvingDataSource.Resolver`: Media3 зовёт его
     * на собственном loading-потоке, поэтому блокировать UI не страшно.
     */
    fun resolveBlocking(videoId: String, quality: AudioQuality): StreamInfo =
        runBlocking(Dispatchers.IO) { resolve(videoId, quality) }

    fun invalidate(videoId: String) {
        // Ключ кэша — «videoId|QUALITY», поэтому remove(videoId) не срабатывал.
        cache.keys.removeIf { it.startsWith("$videoId|") }
    }

    fun clear() = cache.clear()

    /** Видео-id из `innertube:<id>`; null, если схема чужая. */
    fun videoIdOf(rawUri: String): String? {
        if (!rawUri.startsWith("innertube:", ignoreCase = true)) return null
        val id = rawUri.removePrefix("innertube:").trimStart('/')
            .substringBefore('/').substringBefore('?')
        return id.takeIf { it.isNotBlank() }
    }

    /** Общий кэш с двойной проверкой под мьютексом и TTL. */
    private suspend fun cached(
        cacheKey: String,
        block: suspend () -> StreamInfo,
    ): StreamInfo {
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs - now > RENEW_BEFORE_MS) return cached
        }
        val lock = locks.getOrPut(cacheKey) { Mutex() }
        return lock.withLock {
            val latest = cache[cacheKey]
            val current = System.currentTimeMillis()
            if (latest != null && latest.expiresAtMs - current > RENEW_BEFORE_MS) {
                return@withLock latest
            }
            val resolved = block()
            // Sonora — музыкальный плеер: видеопотоки не воспроизводим и не качаем.
            if (!resolved.mimeType.startsWith("audio/", ignoreCase = true)) {
                throw IllegalStateException(
                    "YouTube отдал только видеопоток. Sonora играет только музыку (audio).",
                )
            }
            cache[cacheKey] = resolved
            resolved
        }
    }

    /** Соответствие уровней Sonora возможностям библиотеки InnerTubeX. */
    private fun AudioQuality.engineQuality(): EngineQuality = when (this) {
        AudioQuality.AUTO -> EngineQuality.AUTO
        AudioQuality.LOW, AudioQuality.NORMAL -> EngineQuality.LOW
        AudioQuality.HIGH, AudioQuality.VERY_HIGH -> EngineQuality.HIGH
    }
}

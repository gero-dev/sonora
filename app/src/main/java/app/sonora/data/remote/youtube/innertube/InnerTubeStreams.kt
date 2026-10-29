package app.sonora.data.remote.youtube.innertube

import app.sonora.core.settings.AudioQuality
import app.sonora.data.remote.ytdlp.YtDlpBridge
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

    /** Ссылка yt-dlp живёт недолго: кэшируем её на 15 минут. */
    private const val YTDLP_TTL_MS = 15 * 60_000L

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
     * Двухступенчатый резолв:
     * 1. InnerTubeX — быстрый и устойчивый путь, сам выбирает формат.
     * 2. Если нужен более низкий битрейт (Низкое/Обычное), чем отдал движок,
     *    пробуем прямой `player`-ответ с целевым битрейтом: там доступны
     *    отдельные форматы 64/128/160 кбит/с.
     *
     * Если дешёвый формат получить не удалось, играем то, что есть, — качество
     * не должно ломать воспроизведение.
     */
    private suspend fun resolveStream(videoId: String, level: AudioQuality): StreamInfo {
        val targetKbps = level.targetKbps
        var engineStream: StreamInfo? = null
        var engineFailure: Exception? = null
        try {
            engineStream = InnerTubeXEngine.resolve(videoId, level.engineQuality())
        } catch (error: Exception) {
            engineFailure = error
        }

        // Авто и «Очень высокое» — отдаём максимум, который нашёл движок.
        if (engineStream != null && targetKbps == null) return engineStream

        val engineTooHigh = engineStream != null &&
            engineStream.bitrate > targetKbps!! * 1000 + BITRATE_TOLERANCE
        if (engineStream == null || engineTooHigh) {
            val direct = try {
                api.stream(videoId, targetKbps)
            } catch (error: Exception) {
                null
            }
            if (direct != null) {
                // Для «Авто» сравниваем оба варианта и берём лучший.
                if (targetKbps == null && engineStream != null) {
                    return if (engineStream.bitrate >= direct.bitrate) engineStream else direct
                }
                return direct
            }
        }

        if (engineStream != null) return engineStream

        // Последний рубеж: yt-dlp. Он медленный, зато умеет видео, которые
        // InnerTube/InnerTubeX не отдают (возраст, регион, SABR-only).
        YtDlpBridge.resolve(videoId)?.let { handle ->
            return StreamInfo(
                videoId = videoId,
                url = handle.url,
                mimeType = handle.mimeType,
                bitrate = 0,
                itag = 0,
                expiresAtMs = System.currentTimeMillis() + YTDLP_TTL_MS,
                headers = handle.headers,
            )
        }

        throw (engineFailure ?: IllegalStateException("Не удалось получить поток YouTube"))
    }

    /** MP4-поток для скачивания: m4a лучше подходит для офлайн-файла. */
    suspend fun resolveDownload(videoId: String): StreamInfo =
        cached("$videoId|MP4") {
            runCatching { InnerTubeXEngine.resolve(videoId, EngineQuality.MP4) }
                .recoverCatching { api.stream(videoId) }
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

package app.sonora.data.remote.youtube.innertube

import com.metrolist.innertubex.extraction.AudioQuality
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
 */
internal object InnerTubeStreams {

    private const val RENEW_BEFORE_MS = 60_000L

    private val api = InnerTubeApi()
    private val cache = ConcurrentHashMap<String, StreamInfo>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun resolve(
        videoId: String,
        quality: AudioQuality = AudioQuality.HIGH,
    ): StreamInfo {
        val cacheKey = "$videoId|${quality.name}"
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
            val resolved = runCatching { InnerTubeXEngine.resolve(videoId, quality) }
                .recoverCatching { api.stream(videoId) }
                .getOrThrow()
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

    /** MP4-поток для скачивания: m4a лучше подходит для офлайн-файла. */
    suspend fun resolveDownload(videoId: String): StreamInfo =
        resolve(videoId, AudioQuality.MP4)

    /**
     * Резервный источник для скачивания: обычный `player`-ответ InnerTube без
     * InnerTubeX. Нужен, когда MP4-поток InnerTubeX отдаёт ссылку, которую
     * сервер затем отклоняет (403/410/416).
     */
    suspend fun resolveDownloadFallback(videoId: String): StreamInfo {
        val cacheKey = "$videoId|${AudioQuality.MP4.name}"
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
    fun resolveBlocking(videoId: String): StreamInfo =
        runBlocking(Dispatchers.IO) { resolve(videoId) }

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
}

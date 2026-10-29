package app.sonora.data.telemetry

import android.content.Context
import app.sonora.core.model.Track
import app.sonora.core.settings.SettingsStore
import app.sonora.data.remote.ytdlp.YtDlpResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Оркестрация телеметрии: кэш → скачивание 30-секундного фрагмента через
 * yt-dlp/ffmpeg → загрузка в ReccoBeats.
 *
 * Телеметрия полностью опциональна: любая ошибка возвращает null и никогда
 * не блокирует воспроизведение. Один трек анализируется один раз — параллельные
 * запросы переиспользуют `Deferred`.
 */
class AudioFeatureRepository(
    context: Context,
    private val ytDlp: YtDlpResolver,
    private val settings: SettingsStore,
) {

    private val cache = AudioFeatureCache(context)
    private val client = ReccoBeatsClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = HashMap<String, Deferred<AudioFeature?>>()
    private val lock = Any()

    val enabled: Boolean
        get() = settings.current.audioTelemetry

    /** Найти/посчитать фичи. Возвращает null, если телеметрия выключена или не удалась. */
    suspend fun featuresFor(track: Track): AudioFeature? {
        if (!enabled) return null
        val key = cacheKey(track)
        cache.get(key)?.let { return it }

        val deferred = synchronized(lock) {
            inFlight[key] ?: scope.async {
                try {
                    val videoId = videoIdOf(track) ?: return@async null
                    val snippet = ytDlp.downloadSnippet(videoId) ?: return@async null
                    val feature = try {
                        client.analyze(snippet)
                    } finally {
                        snippet.delete()
                    }
                    feature?.also { cache.put(key, it) }
                } finally {
                    synchronized(lock) { inFlight.remove(key) }
                }
            }.also { inFlight[key] = it }
        }
        return deferred.await()
    }

    /** Фоновый прогрев — вызывается при старте трека, результат не нужен. */
    fun prefetch(track: Track) {
        if (!enabled) return
        scope.launch { runCatching { featuresFor(track) } }
    }

    private fun cacheKey(track: Track): String {
        val videoId = videoIdOf(track)
        if (videoId != null) return "yt:$videoId"
        return "meta:${track.artist.trim().lowercase()}|${track.title.trim().lowercase()}"
    }

    private fun videoIdOf(track: Track): String? {
        track.extra["youtubeId"]?.takeIf { it.isNotBlank() }?.let { return it }
        track.id.takeIf { it.startsWith("youtube:") }?.removePrefix("youtube:")?.let { return it }
        track.id.takeIf { it.startsWith("ytdlp:") }?.removePrefix("ytdlp:")?.let { return it }
        val uri = track.uri.orEmpty()
        if (uri.startsWith("innertube:")) return uri.removePrefix("innertube:").substringBefore('?')
        if (uri.startsWith("ytdlp:")) return uri.removePrefix("ytdlp:").substringBefore('?')
        return track.deepLink
            ?.let { Regex("""[?&]v=([A-Za-z0-9_-]{11})""").find(it) }
            ?.groupValues
            ?.getOrNull(1)
    }
}

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Оркестрация телеметрии: кэш → скачивание 30-секундного фрагмента через
 * yt-dlp/ffmpeg → загрузка в ReccoBeats.
 *
 * Телеметрия полностью опциональна: любая ошибка возвращает null и никогда
 * не блокирует воспроизведение. Один трек анализируется один раз — параллельные
 * запросы переиспользуют `Deferred`.
 *
 * Ограничения фоновой нагрузки:
 *  - не больше [MAX_PARALLEL_ANALYSES] одновременных анализов (семафор);
 *  - между сетевыми запросами держится пауза [MIN_REQUEST_INTERVAL_MS]
 *    (бережём публичный API и мобильный трафик);
 *  - пакетный прогрев [prefetchAll] ограничен [MAX_PREFETCH] треками.
 */
class AudioFeatureRepository(
    context: Context,
    private val ytDlp: YtDlpResolver,
    private val settings: SettingsStore,
) : AudioFeatureProvider {

    private val cache = AudioFeatureCache(context)
    private val client = ReccoBeatsClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = HashMap<String, Deferred<AudioFeature?>>()
    private val lock = Any()

    private val analysisLimiter = Semaphore(MAX_PARALLEL_ANALYSES)
    private val rateLimitMutex = Mutex()

    @Volatile
    private var lastAnalysisAtMs = 0L

    val enabled: Boolean
        get() = settings.current.audioTelemetry

    override val available: Boolean
        get() = enabled

    /** Найти/посчитать фичи. Возвращает null, если телеметрия выключена или не удалась. */
    suspend fun featuresFor(track: Track): AudioFeature? {
        if (!enabled) return null
        val key = cacheKey(track)
        cache.get(key)?.let { return it }

        val deferred = synchronized(lock) {
            inFlight[key] ?: scope.async {
                try {
                    analyzeAndCache(key, track)
                } finally {
                    synchronized(lock) { inFlight.remove(key) }
                }
            }.also { inFlight[key] = it }
        }
        return deferred.await()
    }

    /**
     * Анализ одного трека: короткий сниппет + ReccoBeats. Вызывается только
     * через [featuresFor], поэтому не дублируется для одного ключа.
     */
    private suspend fun analyzeAndCache(key: String, track: Track): AudioFeature? =
        analysisLimiter.withPermit {
            awaitRateLimit()
            val videoId = videoIdOf(track)
            if (videoId == null) {
                null
            } else {
                val snippet = ytDlp.downloadSnippet(videoId)
                if (snippet == null) {
                    null
                } else {
                    val feature = try {
                        client.analyze(snippet)
                    } finally {
                        runCatching { snippet.delete() }
                    }
                    feature?.also { cache.put(key, it) }
                }
            }
        }

    /** Фоновый прогрев — вызывается при старте трека, результат не нужен. */
    fun prefetch(track: Track) {
        if (!enabled) return
        scope.launch { runCatching { featuresFor(track) } }
    }

    /**
     * Фоновый прогрев пачки (например, кандидатов поиска/распознавания):
     * последовательно, чтобы не плодить сетевые запросы и декодирование.
     */
    fun prefetchAll(tracks: List<Track>, limit: Int = MAX_PREFETCH) {
        if (!enabled || tracks.isEmpty()) return
        scope.launch {
            tracks.asSequence()
                .filter { !it.isVideoContent }
                .take(limit)
                .forEach { track -> runCatching { featuresFor(track) } }
        }
    }

    /**
     * Только кэш: «Моя волна» спрашивает фичи у всех кандидатов, и это должно
     * быть бесплатно (никаких загрузок). Ключ — id трека в модели.
     */
    override suspend fun cachedFeatures(tracks: List<Track>): Map<String, AudioFeature> {
        if (!enabled || tracks.isEmpty()) return emptyMap()
        val result = HashMap<String, AudioFeature>(tracks.size)
        for (track in tracks) {
            val feature = runCatching { cache.get(cacheKey(track)) }.getOrNull() ?: continue
            result[track.id] = feature
        }
        return result
    }

    /** Фичи конкретного трека только из кэша (без сети) — для быстрого UI. */
    suspend fun cachedFeature(track: Track): AudioFeature? {
        if (!enabled) return null
        return runCatching { cache.get(cacheKey(track)) }.getOrNull()
    }

    /** Посчитать фичи конкретного трека (сеть + кэш) для фонового обогащения. */
    override suspend fun fetchFeature(track: Track): AudioFeature? = featuresFor(track)

    /** Пауза между сетевыми анализами: не чаще одного запроса в интервал. */
    private suspend fun awaitRateLimit() {
        rateLimitMutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastAnalysisAtMs
            if (elapsed in 0 until MIN_REQUEST_INTERVAL_MS) {
                delay(MIN_REQUEST_INTERVAL_MS - elapsed)
            }
            lastAnalysisAtMs = System.currentTimeMillis()
        }
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

    private companion object {
        const val MAX_PARALLEL_ANALYSES = 2
        const val MIN_REQUEST_INTERVAL_MS = 1_000L
        const val MAX_PREFETCH = 3
    }
}

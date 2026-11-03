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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
     *
     * Порядок шагов важен для скорости. Раньше было «взять слот в лимите —
     * скачать сниппет — отправить запрос», и лимит в 1 запрос/сек превращался в
     * «1 запрос/сек ПЛЮС время скачивания»: пока один трек скачивался, право на
     * следующий запрос уже наступило, но никто им не пользовался. Теперь
     * сниппет скачивается ДО ожидания слота, и ожидание оплачивается только
     * оставшимся временем. Скачивание — это локальная работа yt-dlp, публичный
     * API она не нагружает, поэтому выносить её за лимит безопасно.
     */
    private suspend fun analyzeAndCache(key: String, track: Track): AudioFeature? {
        if (!enabled) return null
        val videoId = videoIdOf(track) ?: return null

        // Вне лимита: локальная подготовка, сеть ReccoBeats ещё не тронута.
        val snippet = analysisLimiter.withPermit {
            ytDlp.downloadSnippet(videoId)
        } ?: return null

        return try {
            awaitRateLimit()
            client.analyze(snippet)?.also { cache.put(key, it) }
        } finally {
            runCatching { snippet.delete() }
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
    /**
     * Фоновый прогрев пачки (например, кандидатов поиска/распознавания).
     *
     * Параллельно, а не по очереди. Последовательный обход складывал время
     * скачивания сниппетов со временем ожидания лимита, хотя это независимые
     * задержки: пока один трек ждёт свою секунду, остальные могут уже
     * скачиваться. Общий лимит запросов к API держится внутри
     * [awaitRateLimit] и от параллельности не нарушается, а число одновременных
     * задач ограничено семафором — на сотни кандидатов корутины не плодятся.
     *
     * Дубликаты убираются по ключу кэша: один и тот же трек в списке не должен
     * оплачиваться дважды.
     */
    fun prefetchAll(tracks: List<Track>, limit: Int = MAX_PREFETCH) {
        if (!enabled || tracks.isEmpty()) return
        val unique = tracks.asSequence()
            .filter { !it.isVideoContent }
            .distinctBy { cacheKey(it) }
            .take(limit)
            .toList()
        if (unique.isEmpty()) return
        scope.launch {
            coroutineScope {
                unique.map { track ->
                    async { runCatching { featuresFor(track) } }
                }.awaitAll()
            }
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
        /**
         * Одновременных скачиваний сниппетов. Запросы к API всё равно идут не
         * чаще [MIN_REQUEST_INTERVAL_MS], поэтому это ограничивает только
         * локальную работу yt-dlp.
         */
        const val MAX_PARALLEL_ANALYSES = 3

        /** Общий предел: чаще одного обращения к публичному API в секунду. */
        const val MIN_REQUEST_INTERVAL_MS = 1_000L

        /** Сколько треков прогревать за один вызов: 3 было слишком мало. */
        const val MAX_PREFETCH = 8
    }
}

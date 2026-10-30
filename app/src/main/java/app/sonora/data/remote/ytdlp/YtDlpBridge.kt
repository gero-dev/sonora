package app.sonora.data.remote.ytdlp

import android.content.Context
import app.sonora.core.model.Track
import app.sonora.core.settings.SettingsStore
import app.sonora.core.settings.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Мост между медиа-слоем и yt-dlp.
 *
 * Держит единственный резолвер и короткий кэш ссылок: прямые URL googlevideo
 * привязаны к IP и клиенту, поэтому живут в кэше не дольше [TTL_MS] и никогда
 * не попадают в очередь.
 *
 * yt-dlp ходит через локальный прокси NoHiT (см. [YtDlpResolver]); VPN и
 * публичных медиашлюзов в приложении нет.
 */
object YtDlpBridge {

    private const val TTL_MS = 5 * 60_000L

    @Volatile
    private var resolver: YtDlpResolver? = null

    @Volatile
    private var settings: SettingsStore? = null

    private data class Cached(val handle: StreamHandle, val atMs: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun init(resolver: YtDlpResolver, settings: SettingsStore) {
        this.resolver = resolver
        this.settings = settings
    }

    fun isInitialized(): Boolean = resolver != null

    /** Выбранный источник потока. */
    fun source(): StreamSource =
        settings?.current?.streamSource ?: StreamSource.AUTO

    /** Пользователь выбрал yt-dlp основным источником. */
    fun isPrimary(): Boolean = source() == StreamSource.YTDLP

    /** yt-dlp разрешён как источник или резерв. */
    fun isEnabled(): Boolean = source() != StreamSource.INNERTUBE

    /** Проверять ссылку Range-запросом перед отдачей в плеер. */
    fun probeBeforePlay(): Boolean = settings?.current?.probeBeforePlay ?: true

    /** Прогрев Python-рантайма: первый реальный вызов будет быстрее. */
    suspend fun warmUp() {
        resolver?.ensureInit()
    }

    /** Проверка/обновление yt-dlp (по кнопке в настройках). */
    suspend fun refreshRuntime(): String? = resolver?.ensureUpToDate(force = true)

    /** Текущая версия yt-dlp для настроек. */
    fun runtimeVersion(): String? = resolver?.versionLabel()

    /** Поиск через yt-dlp (`ytsearch`): нужен, когда InnerTube не отвечает через NoHiT. */
    suspend fun search(query: String, limit: Int = 10): List<Track> {
        val r = resolver ?: return emptyList()
        return runCatching { r.search(query, limit).map { it.toSonoraTrack() } }
            .getOrDefault(emptyList())
    }

    suspend fun resolve(videoId: String): StreamHandle? {
        val now = System.currentTimeMillis()
        cache[videoId]?.let { cached ->
            if (now - cached.atMs < TTL_MS) return cached.handle
        }

        val handle = resolver?.streamHandle(videoId)
        if (handle != null) {
            cache[videoId] = Cached(handle, now)
            return handle
        }
        return null
    }

    /** Скачать полный трек в кэш (надёжный путь для ExoPlayer в режиме NoHiT). */
    suspend fun downloadAudio(videoId: String): java.io.File? =
        resolver?.downloadTrack(videoId)

    fun downloadAudioBlocking(videoId: String): java.io.File? =
        runBlocking(Dispatchers.IO) { downloadAudio(videoId) }
    /** Синхронная версия для `ResolvingDataSource.Resolver`. */
    fun resolveBlocking(videoId: String): StreamHandle? =
        runBlocking(Dispatchers.IO) { resolve(videoId) }

    /** Сбросить ссылку (например, после 403). */
    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    fun clear() = cache.clear()
}

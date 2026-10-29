package app.sonora.data.remote.ytdlp

import android.content.Context
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

    /** Пользователь выбрал yt-dlp основным источником. */
    fun isPrimary(): Boolean =
        settings?.current?.streamSource == StreamSource.YTDLP

    /** yt-dlp разрешён как источник или резерв. */
    fun isEnabled(): Boolean =
        settings?.current?.streamSource != StreamSource.INNERTUBE

    /** Прогрев Python-рантайма: первый реальный вызов будет быстрее. */
    suspend fun warmUp() {
        resolver?.ensureInit()
    }

    suspend fun resolve(videoId: String): StreamHandle? {
        val active = resolver ?: return null
        val now = System.currentTimeMillis()
        cache[videoId]?.let { cached ->
            if (now - cached.atMs < TTL_MS) return cached.handle
        }
        val handle = active.streamHandle(videoId) ?: return null
        cache[videoId] = Cached(handle, now)
        return handle
    }

    /** Синхронная версия для `ResolvingDataSource.Resolver`. */
    fun resolveBlocking(videoId: String): StreamHandle? =
        runBlocking(Dispatchers.IO) { resolve(videoId) }

    /** Сбросить ссылку (например, после 403). */
    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    fun clear() = cache.clear()
}

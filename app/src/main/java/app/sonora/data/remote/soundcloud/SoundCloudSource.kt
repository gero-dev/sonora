package app.sonora.data.remote.soundcloud

import android.content.Context
import app.sonora.core.model.Playlist
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.net.HttpException
import app.sonora.core.security.SecureStore
import app.sonora.core.settings.SettingsStore
import app.sonora.data.remote.ConnectableSource

/**
 * SoundCloud: только каталог/метаданные через официальный api.soundcloud.com
 * с пользовательским client_id. Никакого OAuth-флоу: современные клиенты
 * SoundCloud confidential, client_secret нельзя хранить в APK, а API ToU
 * запрещает скачивание/офлайн и «агрегацию» с другими сервисами в одном плеере.
 *
 * Поэтому: uri = null, playable = false, downloadAllowed = false у всех треков.
 * Пользователь может искать по каталогу, импортировать публичный плейлист
 * (только список треков) и открыть трек в приложении SoundCloud.
 */
class SoundCloudSource(
    context: Context,
    private val settings: SettingsStore,
    private val secure: SecureStore,
) : ConnectableSource {

    private val appContext = context.applicationContext
    private val api = SoundCloudApi()

    /** Результаты последнего поиска/импорта — только в памяти сессии. */
    @Volatile
    private var cached: List<Track> = emptyList()

    /** Последняя ошибка для UI (null — ошибок нет). */
    @Volatile
    var lastError: String? = null

    override val source: Source = Source.SOUNDCLOUD

    private fun clientId(): String = settings.current.soundcloudClientId.trim()

    override fun isConfigured(): Boolean = clientId().isNotBlank()

    /**
     * «Подключён» здесь означает «есть client_id для официальных запросов».
     * OAuth-сессии у SoundCloud в приложении нет.
     */
    override fun isConnected(): Boolean = clientId().isNotBlank()

    override fun accountName(): String? = null

    /** OAuth не реализуется: нужен backend + Artist Pro, см. KDoc. */
    override fun authUrl(): String? = null

    override suspend fun handleCallback(uri: String): Boolean = false

    override suspend fun search(query: String, limit: Int): List<Track> {
        if (query.isBlank()) return emptyList()
        val id = clientId()
        if (id.isBlank()) {
            lastError = setupHint()
            return emptyList()
        }
        return runCatching { api.searchTracks(id, query, limit) }
            .onSuccess {
                cached = it
                lastError = null
            }
            .onFailure { error -> lastError = describe(error) }
            .getOrDefault(emptyList())
    }

    /**
     * Личной библиотеки нет: OAuth `/me` и лайки требуют confidential-клиента
     * и backend, а ToU запрещает превращать приложение в агрегатор сервисов.
     * Публичные плейлисты доступны через [importUrl].
     */
    override suspend fun libraryPlaylists(): List<Playlist> = emptyList()

    /**
     * Метаданные публичного плейлиста по id (для уже импортированных ссылок).
     * Треки не играются — только список и deep link.
     */
    override suspend fun playlistTracks(remotePlaylistId: String): List<Track> {
        val id = normalizeId(remotePlaylistId)
        if (id.isBlank()) return emptyList()
        val key = clientId()
        if (key.isBlank()) {
            lastError = setupHint()
            return emptyList()
        }
        return runCatching {
            val playlist = api.playlist(key, id)
                ?: throw IllegalStateException("Плейлист SoundCloud не найден.")
            api.tracksFromPlaylist(playlist)
        }
            .onSuccess { tracks ->
                if (tracks.isNotEmpty()) cached = tracks
                lastError = null
            }
            .onFailure { error -> lastError = describe(error) }
            .getOrDefault(emptyList())
    }

    /**
     * Импорт публичного трека/плейлиста по ссылке: только метаданные,
     * без stream_url/download_url и без client_id в медиа-ссылках.
     */
    suspend fun importUrl(url: String): List<Track> {
        val id = clientId()
        if (id.isBlank()) {
            lastError = setupHint()
            return emptyList()
        }
        if (url.isBlank()) {
            lastError = "Вставьте ссылку на трек или публичный плейлист SoundCloud."
            return emptyList()
        }
        return runCatching { api.importUrl(id, url) }
            .onSuccess { tracks ->
                if (tracks.isNotEmpty()) {
                    cached = tracks
                    lastError = null
                } else {
                    lastError = "SoundCloud не распознал ссылку: нужен трек или плейлист."
                }
            }
            .onFailure { error -> lastError = describe(error) }
            .getOrDefault(emptyList())
    }

    override fun disconnect() {
        // client_id — пользовательский ключ в настройках, а не сессия: не стираем.
        cached = emptyList()
        lastError = null
    }

    override fun setupHint(): String {
        val base = "SoundCloud API требует Artist Pro и серверный OAuth-клиент, " +
            "а его ToU запрещает скачивание и объединение с другими сервисами. " +
            "В Sonora доступны поиск по каталогу и переход в приложение SoundCloud."
        if (!isConfigured()) {
            return "$base Вставьте существующий client_id в настройках, " +
                "чтобы включить поиск по каталогу."
        }
        return base
    }

    override fun cachedTrackCount(): Int = cached.size

    // ---------------------------------------------------------------- внутреннее

    private fun normalizeId(raw: String): String {
        var value = raw.trim()
        if (value.startsWith("soundcloud:", ignoreCase = true)) {
            value = value.substring("soundcloud:".length)
        }
        return value.trim()
    }

    private fun describe(error: Throwable): String = when (error) {
        is HttpException -> when (error.code) {
            401, 403 -> "SoundCloud отклонил client_id (${error.code}). " + setupHint()
            404 -> "SoundCloud: не найдено (404) — проверьте ссылку."
            429 -> "SoundCloud: слишком много запросов — попробуйте позже."
            else -> "SoundCloud: ${error.message}"
        }
        else -> error.message ?: "Ошибка SoundCloud"
    }
}

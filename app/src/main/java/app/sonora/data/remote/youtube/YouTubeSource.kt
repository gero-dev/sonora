package app.sonora.data.remote.youtube

import app.sonora.core.model.ArtistRelease
import app.sonora.core.model.Playlist
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.net.HttpException
import app.sonora.data.remote.ConnectableSource
import app.sonora.data.remote.youtube.innertube.InnerTubeApi

/**
 * YouTube Music через InnerTube — тот же неофициальный, но открытый протокол,
 * на котором работает Metrolist.
 *
 * Треки сразу `playable = true`, а `uri = innertube:<videoId>` резолвится
 * в прямой аудио-URL на стороне ExoPlayer (см. `InnerTubeDataSourceFactory`).
 */
class YouTubeSource(
) : ConnectableSource {

    private val api = InnerTubeApi()

    /** Последняя удачная выдача: UI показывает её, пока идёт новый запрос. */
    @Volatile
    private var cached: List<Track> = emptyList()

    /** Последняя ошибка InnerTube (null — всё хорошо). */
    @Volatile
    var lastError: String? = null

    override val source: Source = Source.YOUTUBE

    /** InnerTube не требует настройки: работает анонимно «из коробки». */
    override fun isConfigured(): Boolean = true

    override fun isConnected(): Boolean = true

    override fun accountName(): String? {
        val sessionName = app.sonora.data.remote.youtube.innertube.InnerTubeSession.accountName
        if (!sessionName.isNullOrBlank()) return sessionName
        return if (isGoogleConnected()) "Google-аккаунт" else "InnerTube · без аккаунта"
    }

    /** Подключён ли Google-аккаунт (cookie SAPISID). */
    fun isGoogleConnected(): Boolean =
        app.sonora.data.remote.youtube.innertube.InnerTubeSession.isLoggedIn

    /** Имя/аватар аккаунта из InnerTube account_menu. */
    suspend fun accountInfo() =
        app.sonora.data.remote.youtube.innertube.InnerTubeSession
            .takeIf { it.isLoggedIn }
            ?.let { runCatching { api.accountInfo() }.getOrNull() }

    /** Плейлисты личной библиотеки; без входа — пусто. */
    override suspend fun libraryPlaylists(): List<Playlist> {
        if (!isGoogleConnected()) return emptyList()
        return runCatching { api.libraryPlaylists() }
            .onFailure { error -> lastError = describe(error) }
            .getOrDefault(emptyList())
    }

    /** «Понравившиеся» из аккаунта — для очереди сохранённых треков. */
    suspend fun likedSongs(limit: Int = 200): List<Track> {
        if (!isGoogleConnected()) return emptyList()
        return runCatching { api.likedSongs(limit).filterNot { it.isVideoContent } }
            .onFailure { error -> lastError = describe(error) }
            .getOrDefault(emptyList())
    }

    /** OAuth-вход не нужен, поэтому браузер не открываем. */
    override fun authUrl(): String? = null

    override suspend fun handleCallback(uri: String): Boolean = false

    override suspend fun search(query: String, limit: Int): List<Track> {
        if (query.isBlank()) return emptyList()
        return runCatching { api.search(query, limit).filterNot { it.isVideoContent } }
            .onSuccess {
                cached = it
                lastError = null
            }
            .onFailure { error -> lastError = describe(error) }
            .getOrDefault(emptyList())
    }

    override suspend fun playlistTracks(remotePlaylistId: String): List<Track> {
        return runCatching { api.playlist(remotePlaylistId).tracks.filterNot { it.isVideoContent } }
            .onSuccess {
                cached = it
                lastError = null
            }
            .onFailure { error -> lastError = describe(error) }
            .getOrDefault(emptyList())
    }

    /**
     * Импорт публичного плейлиста по ссылке (`?list=…`, `/playlist/…`) или id.
     * Возвращает треки и сохраняет их в кэш источника.
     */
    suspend fun importPlaylist(url: String): List<Track> {
        val playlistId = api.normalizePlaylistId(url)
        if (playlistId.isNullOrBlank()) {
            lastError = "Не удалось распознать ссылку на плейлист YouTube Music."
            return emptyList()
        }
        return runCatching { api.playlist(playlistId).tracks.filterNot { it.isVideoContent } }
            .onSuccess {
                cached = it
                lastError = null
            }
            .onFailure { error -> lastError = describe(error) }
            .getOrDefault(emptyList())
    }

    /**
     * Дискография исполнителя: альбомы, синглы и EP.
     * Нужна странице артиста — как блок «Альбомы» в Spotify.
     */
    suspend fun artistAlbums(artist: String, limit: Int = 70): List<ArtistRelease> =
        runCatching { api.artistAlbums(artist, limit) }
            .onFailure { error -> lastError = describe(error) }
            .getOrDefault(emptyList())

    /** Популярные треки исполнителя — верхний блок его страницы. */
    suspend fun artistTopTracks(artist: String, limit: Int = 25): List<Track> =
        runCatching { api.artistTopTracks(artist, limit).filterNot { it.isVideoContent } }
            .onFailure { error -> lastError = describe(error) }
            .getOrDefault(emptyList())

    /** Альбом по browseId (`MPREb…`): состав, год, обложка. */
    suspend fun album(browseId: String): app.sonora.data.remote.youtube.innertube.RemotePlaylist? =
        runCatching { api.album(browseId) }
            .onFailure { error -> lastError = describe(error) }
            .getOrNull()

    /** Метаданные плейлиста + треки: нужно для красивой карточки в медиатеке. */
    suspend fun importPlaylistDetails(url: String): app.sonora.data.remote.youtube.innertube.RemotePlaylist? {
        val playlistId = api.normalizePlaylistId(url) ?: return null
        return runCatching { api.playlist(playlistId) }
            .onSuccess {
                cached = it.tracks.filterNot { track -> track.isVideoContent }
            }
            .onFailure { error -> lastError = describe(error) }
            .getOrNull()
            ?.let { playlist ->
                // В деталях плейлиста оставляем только музыку.
                playlist.copy(tracks = playlist.tracks.filterNot { it.isVideoContent })
            }
    }

    /** Очередь «дальше»: используется для автоочереди YouTube-трека. */
    suspend fun related(videoId: String, limit: Int = 25): List<Track> =
        runCatching { api.related(videoId, limit).filterNot { it.isVideoContent } }
            .getOrDefault(emptyList())

    /** MP4-поток для сохранения трека на устройство (m4a, без SABR). */
    internal suspend fun downloadStream(videoId: String) =
        app.sonora.data.remote.youtube.innertube.InnerTubeStreams.resolveDownload(videoId)

    /** Резервный поток через обычный player-ответ, если InnerTubeX-ссылка не скачивается. */
    internal suspend fun downloadStreamFallback(videoId: String) =
        app.sonora.data.remote.youtube.innertube.InnerTubeStreams.resolveDownloadFallback(videoId)

    /** Сбрасывает закэшированную ссылку после 403/410/416. */
    internal fun invalidateStream(videoId: String) =
        app.sonora.data.remote.youtube.innertube.InnerTubeStreams.invalidate(videoId)

    /** Обложка в большем размере, чем превью 120×120 из поиска. */
    fun largeArtworkUrl(url: String?): String? = url
        ?.replace(Regex("""=w\d+-h\d+"""), "=w1080-h1080")
        ?.replace(Regex("""=s\d+"""), "=s1080")

    /** Текст песни из InnerTube (вкладка Lyrics), с кэшем на процесс. */
    suspend fun lyrics(videoId: String): String? = lyricsCache[videoId] ?: runCatching {
        api.lyrics(videoId)
    }.getOrNull()?.also { lyricsCache[videoId] = it }

    /** Синхронизированные авто-субтитры YouTube Music (LRC). */
    suspend fun transcript(videoId: String): String? {
        val key = "transcript:$videoId"
        return transcriptCache[key] ?: runCatching { api.transcript(videoId) }
            .getOrNull()?.also { transcriptCache[key] = it }
    }

    override fun disconnect() {
        cached = emptyList()
        lastError = null
    }

    override fun setupHint(): String =
        "InnerTube из Metrolist: без API-ключей и OAuth. Поиск, плейлисты, " +
            "очередь, тексты и аудиопоток идут через music.youtube.com."

    override fun cachedTrackCount(): Int = cached.size

    private fun describe(error: Throwable): String = when (error) {
        is HttpException -> when (error.code) {
            400 -> "InnerTube отклонил запрос (400)."
            403 -> "YouTube ограничил доступ (403): попробуйте позже."
            404 -> "Плейлист или трек не найден (404)."
            429 -> "Слишком много запросов к YouTube — подождите немного."
            else -> "InnerTube: ${error.message}"
        }

        else -> error.message ?: "Ошибка InnerTube"
    }

    private val lyricsCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val transcriptCache = java.util.concurrent.ConcurrentHashMap<String, String>()
}

package app.sonora.data.remote.spotify

import android.content.Context
import app.sonora.core.model.Playlist
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.net.HttpException
import app.sonora.core.security.SecureStore
import app.sonora.core.settings.SettingsStore
import app.sonora.data.remote.ConnectableSource
import java.util.concurrent.ConcurrentHashMap

/**
 * Spotify: каталог и библиотека через официальный Web API + OAuth PKCE.
 *
 * ЧТО МОЖНО: показать плейлисты и «Мне нравится», искать, играть 30-секундные
 * превью, если Spotify их выдал приложению (preview_url). Полных треков Web API
 * не отдаёт, скачивания нет — такие треки помечены playable = false и имеют
 * deepLink в приложение Spotify.
 *
 * ЧЕГО НЕТ И НЕ БУДЕТ: скрапинга, внутренних API веб-плеера, скачивания аудио,
 * DRM-обхода. downloadAllowed всегда false (см. SpotifyApi).
 */
class SpotifySource(
    context: Context,
    private val settings: SettingsStore,
    secure: SecureStore,
) : ConnectableSource {

    private val appContext = context.applicationContext
    private val auth = SpotifyAuth(settings, secure)
    private val api = SpotifyApi()

    /** Кэш треков по remote id плейлиста; живёт только в памяти процесса. */
    private val trackCache = ConcurrentHashMap<String, List<Track>>()

    /** Последняя ошибка для UI (null — ошибок нет). */
    @Volatile
    var lastError: String? = null

    override val source: Source = Source.SPOTIFY

    override fun isConfigured(): Boolean = auth.isConfigured()

    override fun isConnected(): Boolean = auth.isConnected()

    override fun accountName(): String? = auth.accountName()

    override fun authUrl(): String? {
        val url = auth.authUrl()
        lastError = if (url == null) auth.lastError else null
        return url
    }

    override suspend fun handleCallback(uri: String): Boolean {
        val ok = runCatching { auth.handleCallback(uri) }.getOrElse { error ->
            lastError = describe(error)
            false
        }
        if (ok) {
            trackCache.clear()
            lastError = null
        } else {
            lastError = auth.lastError ?: lastError ?: "Не удалось подключить Spotify."
        }
        return ok
    }

    override suspend fun search(query: String, limit: Int): List<Track> {
        if (query.isBlank()) return emptyList()
        if (!isConnected()) {
            lastError = "Spotify не подключён: войдите через «Подключить Spotify»."
            return emptyList()
        }
        return runCatching {
            auth.withToken { token -> api.search(token, query, limit) }
        }
            .onSuccess { lastError = null }
            .onFailure { error -> lastError = describe(error) }
            .getOrDefault(emptyList())
    }

    override suspend fun libraryPlaylists(): List<Playlist> {
        val liked = likedPlaylist()
        if (!isConnected()) return listOf(liked)
        return runCatching {
            val remote = auth.withToken { token -> api.playlists(token) }
            (listOf(liked) + remote).distinctBy { it.id }
        }
            .onSuccess { lastError = null }
            .onFailure { error -> lastError = describe(error) }
            .getOrDefault(listOf(liked))
    }

    override suspend fun playlistTracks(remotePlaylistId: String): List<Track> {
        val id = normalizePlaylistId(remotePlaylistId)
        if (id.isBlank()) return emptyList()
        trackCache[id]?.let { return it }
        if (!isConnected()) {
            lastError = "Spotify не подключён: войдите через «Подключить Spotify»."
            return emptyList()
        }
        val tracks = runCatching {
            auth.withToken { token ->
                if (id == LIKED_REMOTE_ID) {
                    api.likedTracks(token)
                } else {
                    api.playlistTracks(token, id)
                }
            }
        }
            .onSuccess { lastError = null }
            .onFailure { error -> lastError = describe(error) }
            .getOrDefault(emptyList())
        if (tracks.isNotEmpty()) {
            if (trackCache.size >= MAX_CACHED_PLAYLISTS) trackCache.clear()
            trackCache[id] = tracks
        }
        return tracks
    }

    override fun disconnect() {
        auth.disconnect()
        trackCache.clear()
        lastError = null
    }

    override fun setupHint(): String {
        if (!auth.isConfigured()) {
            return "Укажите Spotify Client ID в настройках (developer.spotify.com)."
        }
        return "Spotify играет только 30-секундные превью (если они выданы приложению) — " +
            "полные треки открываются в Spotify. Скачивание музыки Spotify не поддерживается."
    }

    override fun cachedTrackCount(): Int = trackCache.values.sumOf { it.size }

    // ---------------------------------------------------------------- внутреннее

    private fun likedPlaylist() = Playlist(
        id = LIKED_ID,
        name = "Мне нравится",
        source = Source.SPOTIFY,
        remoteId = LIKED_REMOTE_ID,
        editable = false,
        ownerName = accountName(),
    )

    /** Принимает "liked", "spotify:liked", "spotify:<playlistId>". */
    private fun normalizePlaylistId(raw: String): String {
        var value = raw.trim()
        if (value.startsWith("spotify:", ignoreCase = true)) {
            value = value.substring("spotify:".length)
        }
        if (value.startsWith("playlist:", ignoreCase = true)) {
            value = value.substring("playlist:".length)
        }
        return value.trim()
    }

    private fun describe(error: Throwable): String = when (error) {
        is HttpException -> when (error.code) {
            401 -> "Сессия Spotify истекла — подключите аккаунт заново."
            403 -> "Spotify ограничил доступ к этому плейлисту (403)."
            429 -> "Слишком много запросов к Spotify — попробуйте позже."
            else -> "Spotify: ${error.message}"
        }
        else -> error.message ?: "Ошибка Spotify"
    }

    companion object {
        /** Синтетический плейлист «Мне нравится» (Spotify saved tracks). */
        const val LIKED_ID = "spotify:liked"

        /** Значение Playlist.remoteId для «Мне нравится». */
        const val LIKED_REMOTE_ID = "liked"

        private const val MAX_CACHED_PLAYLISTS = 60
    }
}

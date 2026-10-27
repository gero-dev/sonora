package app.sonora.ui.nav

import android.net.Uri

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val DISCOVER = "discover"
    const val HISTORY = "history"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val CONNECTIONS = "connections"
    const val PLAYER = "player"
    const val QUEUE = "queue"
    /** Встроенный веб-плеер music.youtube.com. */
    const val YOUTUBE_WEB = "youtube_web"
    /** WebView-вход в Google для личной библиотеки YouTube Music. */
    const val GOOGLE_LOGIN = "google_login"

    const val PLAYLIST = "playlist/{playlistId}"
    const val ALBUM = "album/{albumName}/{artist}"
    const val ARTIST = "artist/{artistName}"
    /** Онлайн-альбом из дискографии исполнителя (browseId InnerTube). */
    const val REMOTE_ALBUM = "remote_album/{albumId}/{albumTitle}/{artist}"

    fun playlist(id: String): String = "playlist/${Uri.encode(id)}"
    fun album(name: String, artist: String): String = "album/${Uri.encode(name)}/${Uri.encode(artist)}"
    fun artist(name: String): String = "artist/${Uri.encode(name)}"
    fun remoteAlbum(id: String, title: String, artist: String): String =
        "remote_album/${Uri.encode(id)}/${Uri.encode(title)}/${Uri.encode(artist)}"

    val tabs = listOf(HOME, SEARCH, HISTORY, LIBRARY, SETTINGS)
}

package app.sonora.core.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Переходы в официальные приложения сервисов.
 * Это легальный способ дать пользователю «полный» функционал сервиса:
 * трек открывается там, где у платформы есть на него права.
 */
object AppLinks {

    fun open(context: Context, url: String?, fallbackMessage: String = "Ссылка недоступна") {
        if (url.isNullOrBlank()) {
            Toast.makeText(context, fallbackMessage, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Приложение не установлено", Toast.LENGTH_SHORT).show()
        }
    }

    fun spotifyTrack(trackId: String) = "https://open.spotify.com/track/$trackId"
    fun spotifyAlbum(albumId: String) = "https://open.spotify.com/album/$albumId"
    fun spotifyPlaylist(playlistId: String) = "https://open.spotify.com/playlist/$playlistId"
    fun spotifySearch(query: String) = "https://open.spotify.com/search/${Uri.encode(query)}"

    fun youtubeMusicTrack(videoId: String) = "https://music.youtube.com/watch?v=$videoId"
    fun youtubeMusicPlaylist(playlistId: String) = "https://music.youtube.com/playlist?list=$playlistId"
    fun youtubeMusicSearch(query: String) = "https://music.youtube.com/search?q=${Uri.encode(query)}"

    fun soundCloudTrack(url: String) = url.ifBlank { "https://soundcloud.com" }
    fun soundCloudSearch(query: String) = "https://soundcloud.com/search?q=${Uri.encode(query)}"
}

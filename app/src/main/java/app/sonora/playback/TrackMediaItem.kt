package app.sonora.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.sonora.core.model.Track

/**
 * Единое превращение [Track] в MediaItem для UI-контроллера и
 * [PlaybackService] (чтобы очередь, восстановленная виджетом, выглядела
 * точно так же, как очередь из приложения).
 */
internal fun trackToMediaItem(track: Track): MediaItem {
    val artwork = track.artworkUrl ?: track.artworkUri
    val metadata = MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artist)
        .apply {
            if (!track.album.isNullOrBlank()) setAlbumTitle(track.album)
            if (!artwork.isNullOrBlank()) setArtworkUri(Uri.parse(artwork))
        }
        .build()

    return MediaItem.Builder()
        .setMediaId(track.id)
        .setUri(track.uri)
        .setMediaMetadata(metadata)
        .build()
}

package app.sonora.data.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.data.metadata.ArtistNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Сканирование локальной фонотеки: MediaStore + папка импорта приложения.
 * Ничего не копирует и не изменяет — только читает метаданные.
 */
class MediaStoreScanner(private val context: Context) {

    private val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.YEAR,
    )

    suspend fun scan(excludeShort: Boolean = true): List<Track> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Track>()
        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            if (excludeShort) append(" AND ${MediaStore.Audio.Media.DURATION} > 30000")
        }
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val yearCol = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idCol)
                    val albumId = cursor.getLong(albumIdCol)
                    val title = cursor.getString(titleCol) ?: continue
                    val artist = cursor.getString(artistCol).orEmpty()
                    val album = cursor.getString(albumCol).orEmpty()
                    val duration = cursor.getLong(durationCol)
                    val trackNo = if (trackCol >= 0) cursor.getInt(trackCol) else 0
                    val year = if (yearCol >= 0) cursor.getInt(yearCol) else 0

                    result += Track(
                        id = "local:$mediaId",
                        title = title,
                        artist = ArtistNames.clean(artist).ifBlank { "Неизвестный исполнитель" },
                        album = album.takeIf { it.isNotBlank() },
                        durationMs = duration,
                        uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            mediaId,
                        ).toString(),
                        artworkUri = albumArtUri(albumId),
                        source = Source.LOCAL,
                        playable = true,
                        downloadAllowed = true,
                        extra = buildMap {
                            if (trackNo > 0) put("trackNo", trackNo.toString())
                            if (year > 0) put("year", year.toString())
                        },
                    )
                }
            }
        }
        result
    }

    private fun albumArtUri(albumId: Long): String? {
        if (albumId <= 0L) return null
        return android.content.ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            albumId,
        ).toString()
    }

    /**
     * Сканирует папку, которую MediaStore не индексирует (например,
     * `getExternalFilesDir(MUSIC)/Sonora`, куда попадают импортированные файлы).
     */
    suspend fun scanFolder(dir: File): List<Track> = withContext(Dispatchers.IO) {
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
        dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            ?.mapNotNull { file -> runCatching { readFile(file) }.getOrNull() }
            ?: emptyList()
    }

    private fun readFile(file: File): Track {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val title = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: file.nameWithoutExtension
            val artist = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?.let(ArtistNames::clean)
                ?: "Неизвестный исполнитель"
            val album = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L

            Track(
                id = "file:${file.absolutePath}",
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                uri = Uri.fromFile(file).toString(),
                source = Source.LOCAL,
                playable = true,
                downloadAllowed = true,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "opus", "ogg", "m4a", "aac", "wav", "webm", "mp4",
        )
    }
}

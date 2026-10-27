package app.sonora.data.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
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

    private val projection: Array<String> = buildList {
        add(MediaStore.Audio.Media._ID)
        add(MediaStore.Audio.Media.TITLE)
        add(MediaStore.Audio.Media.ARTIST)
        add(MediaStore.Audio.Media.ALBUM)
        add(MediaStore.Audio.Media.ALBUM_ID)
        add(MediaStore.Audio.Media.DURATION)
        add(MediaStore.Audio.Media.TRACK)
        add(MediaStore.Audio.Media.YEAR)
        add(MediaStore.MediaColumns.DISPLAY_NAME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(MediaStore.MediaColumns.RELATIVE_PATH)
        }
    }.toTypedArray()

    suspend fun scan(
        excludeShort: Boolean = true,
        readEmbedded: Boolean = true,
        readSidecar: Boolean = true,
    ): List<Track> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Track>()
        // Один и тот же альбом встречается десятки раз: проверяем URI обложки один раз.
        val albumArtCache = HashMap<Long, String>()
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
                val displayNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val relativePathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    -1
                }

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idCol)
                    val albumId = cursor.getLong(albumIdCol)
                    val title = cursor.getString(titleCol) ?: continue
                    val artist = cursor.getString(artistCol).orEmpty()
                    val album = cursor.getString(albumCol).orEmpty()
                    val duration = cursor.getLong(durationCol)
                    val trackNo = if (trackCol >= 0) cursor.getInt(trackCol) else 0
                    val year = if (yearCol >= 0) cursor.getInt(yearCol) else 0
                    val displayName = if (displayNameCol >= 0) cursor.getString(displayNameCol) else null
                    val relativePath = if (relativePathCol >= 0) cursor.getString(relativePathCol) else null
                    val albumArt = albumArtCache.getOrPut(albumId) { albumArtUri(albumId).orEmpty() }

                    var track = Track(
                        id = "local:$mediaId",
                        title = title,
                        artist = ArtistNames.clean(artist).ifBlank { "Неизвестный исполнитель" },
                        album = album.takeIf { it.isNotBlank() },
                        durationMs = duration,
                        uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            mediaId,
                        ).toString(),
                        artworkUri = albumArt.takeIf { it.isNotEmpty() },
                        source = Source.LOCAL,
                        playable = true,
                        downloadAllowed = true,
                        extra = buildMap {
                            if (trackNo > 0) put("trackNo", trackNo.toString())
                            if (year > 0) put("year", year.toString())
                        },
                    )

                    // Порядок обложек: встроенная → валидный MediaStore albumart →
                    // cover.* из папки → обложка из sidecar-файла.
                    if (readEmbedded) {
                        LocalArtwork.embedded(context, track.id, track.uri.orEmpty())
                            ?.let { embedded -> track = track.copy(artworkUri = embedded) }
                    }
                    if (readSidecar) {
                        // Sidecar ищем best-effort по пути из MediaStore: на Android 10+
                        // доступ к чужим папкам может быть закрыт — тогда просто пропускаем.
                        runCatching {
                            val file = mediaStoreFile(relativePath, displayName) ?: return@runCatching
                            if (track.artworkUri.isNullOrBlank()) {
                                file.parentFile?.let { parent ->
                                    LocalArtwork.folderCover(parent)
                                        ?.let { cover -> track = track.copy(artworkUri = cover) }
                                }
                            }
                            SidecarMetadata.readFor(file)?.let { info ->
                                track = SidecarMetadata.apply(track, info)
                            }
                        }
                    }
                    result += track
                }
            }
        }
        result
    }

    /**
     * URI обложки альбома из MediaStore. На Android 10+ провайдер часто отдаёт
     * `content://media/external/audio/albumart/<id>`, которого физически нет:
     * такой URI открывается с FileNotFoundException. Проверяем открытие и
     * возвращаем null, чтобы «битый» URI не блокировал настоящую обложку.
     */
    private fun albumArtUri(albumId: Long): String? {
        if (albumId <= 0L) return null
        val uri = android.content.ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            albumId,
        )
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { } ?: return@runCatching null
            uri.toString()
        }.getOrNull()
    }

    /**
     * Best-effort путь к файлу MediaStore из `RELATIVE_PATH` + `DISPLAY_NAME`.
     * На Android 10+ с scoped storage такой путь может быть недоступен — тогда
     * sidecar просто не найдётся (возвращаем null).
     */
    private fun mediaStoreFile(relativePath: String?, displayName: String?): File? {
        if (displayName.isNullOrBlank() || relativePath.isNullOrBlank()) return null
        return runCatching {
            val directory = relativePath.trimEnd('/') + "/"
            File(Environment.getExternalStorageDirectory(), directory + displayName)
        }.getOrNull()
    }

    /**
     * Сканирует папку, которую MediaStore не индексирует (например,
     * `getExternalFilesDir(MUSIC)/Sonora`, куда попадают импортированные файлы).
     */
    suspend fun scanFolder(
        dir: File,
        readEmbedded: Boolean = true,
        readSidecar: Boolean = true,
    ): List<Track> = withContext(Dispatchers.IO) {
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
        dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            ?.mapNotNull { file ->
                runCatching { readFileWithSidecars(file, readEmbedded, readSidecar) }.getOrNull()
                    // Видеофайлы не попадают в музыкальную фонотеку и плеер.
                    ?.takeUnless { it.isVideoContent }
            }
            ?: emptyList()
    }

    /** Файл + локальные обложки/метаданные: встроенная картинка, cover.*, sidecar. */
    private suspend fun readFileWithSidecars(
        file: File,
        readEmbedded: Boolean,
        readSidecar: Boolean,
    ): Track {
        var track = readFile(file)
        if (readEmbedded) {
            LocalArtwork.embedded(context, track.id, track.uri.orEmpty())
                ?.let { embedded -> track = track.copy(artworkUri = embedded) }
        }
        if (readSidecar) {
            // Ошибка в битом sidecar не должна выкидывать сам трек из фонотеки.
            runCatching {
                // cover.* в папке — fallback, если встроенной обложки нет.
                if (track.artworkUri.isNullOrBlank()) {
                    file.parentFile?.let { parent ->
                        LocalArtwork.folderCover(parent)
                            ?.let { cover -> track = track.copy(artworkUri = cover) }
                    }
                }
                SidecarMetadata.readFor(file)?.let { info ->
                    track = SidecarMetadata.apply(track, info)
                }
            }
        }
        return track
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
            val hasVideo = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                ?.equals("yes", ignoreCase = true) == true

            Track(
                id = "file:${file.absolutePath}",
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                uri = Uri.fromFile(file).toString(),
                source = Source.LOCAL,
                playable = !hasVideo,
                downloadAllowed = true,
                extra = if (hasVideo) mapOf("hasVideo" to "1") else emptyMap(),
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

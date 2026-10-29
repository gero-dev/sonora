package app.sonora.data.library

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.util.ScanDiff
import app.sonora.core.util.ScanSignature
import app.sonora.core.util.diffScanSignatures
import app.sonora.data.metadata.ArtistNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Сканирование локальной фонотеки: MediaStore + папка импорта приложения.
 * Ничего не копирует и не изменяет — только читает метаданные.
 *
 * Оптимизации (см. `LibraryPerf` в logcat):
 *  - инкрементальный кэш (`ScanCacheStore`): неизменённые файлы не перечитываются;
 *  - одна MediaStore-query с минимальной проекцией и фильтрами IS_MUSIC/длительности;
 *  - MediaMetadataRetriever/обложки/sidecar читаются ограниченным параллелизмом
 *    ([METADATA_PARALLELISM]) на Dispatchers.IO — главный поток не блокируется.
 */
class MediaStoreScanner(private val context: Context) {

    /** Персистентный кэш метаданных между сканами (filesDir/scan_cache.json). */
    private val cache = ScanCacheStore(context)

    /** IO-bound чтение тегов: 4–6 параллельных retriever'ов — предел пользы на телефоне. */
    private val metadataDispatcher = Dispatchers.IO.limitedParallelism(METADATA_PARALLELISM)

    /** URI обложек альбомов: один и тот же альбом встречается десятки раз. */
    private val albumArtCache = ConcurrentHashMap<Long, String>()

    /** Только нужные колонки. SIZE/DATE_MODIFIED — для инкрементального кэша. */
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
        add(MediaStore.MediaColumns.SIZE)
        add(MediaStore.MediaColumns.DATE_MODIFIED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(MediaStore.MediaColumns.RELATIVE_PATH)
        } else {
            // До Android 10 RELATIVE_PATH нет, а DATA даёт полный путь: нужен,
            // чтобы одинаковые имена файлов из разных папок не смешивались в кэше.
            add(MediaStore.MediaColumns.DATA)
        }
    }.toTypedArray()

    /**
     * Сканирует MediaStore.
     *
     * @param forceFull игнорировать кэш и перечитать метаданные всех файлов.
     * По умолчанию `false` — поведение для существующих вызовов не меняется.
     */
    suspend fun scan(
        excludeShort: Boolean = true,
        readEmbedded: Boolean = true,
        readSidecar: Boolean = true,
        forceFull: Boolean = false,
    ): List<Track> = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        val flags = cacheFlags(readEmbedded, readSidecar)
        // «Битый» albumart мог появиться в MediaStore позже — пробуем его снова.
        albumArtCache.entries.removeAll { entry -> entry.value.isEmpty() }
        val rows = queryMediaStore(excludeShort)
        // Пустой ответ при непустой библиотеке почти всегда означает сбой/отзыв
        // разрешения. Кэш в этом случае не трогаем — метаданные не теряются.
        if (rows.isEmpty()) return@withContext emptyList()

        val signatures = rows.map { it.signature }
        val previous = if (forceFull) {
            emptyMap()
        } else {
            cache.signaturesFor(flags) { folder -> folder.startsWith(MEDIASTORE_FOLDER_PREFIX) }
        }
        val diff = if (forceFull) {
            ScanDiff(changed = signatures.mapTo(LinkedHashSet()) { it.key })
        } else {
            diffScanSignatures(signatures, previous)
        }
        val cached = if (diff.unchanged.isEmpty()) {
            emptyMap()
        } else {
            cache.entriesFor(flags, diff.unchanged)
        }

        val changedRows = if (diff.changed.isEmpty()) {
            emptyList()
        } else {
            rows.filter { it.signature.key in diff.changed }
        }
        val extracted = extractMediaRows(changedRows, readEmbedded, readSidecar)

        val result = ArrayList<Track>(rows.size)
        val freshEntries = ArrayList<ScanCacheEntry>(rows.size)
        var reused = 0
        val now = System.currentTimeMillis()
        for (row in rows) {
            val key = row.signature.key
            val cachedEntry = cached[key]
            val track = if (cachedEntry != null) {
                reused++
                reuseMediaTrack(row, cachedEntry)
            } else {
                extracted.getValue(key)
            }
            result += track
            freshEntries += ScanCacheEntry(
                path = row.signature.path,
                size = row.signature.size,
                dateModified = row.signature.dateModified,
                folder = row.signature.folder,
                track = track,
                albumId = row.albumId,
                cachedAt = now,
            )
        }
        cache.commit(flags, diff.removed, freshEntries)
        Log.i(
            TAG,
            "scan MediaStore: ${elapsedMs(startedAt)} ms, ${result.size} tracks, " +
                "reused $reused, extracted ${extracted.size}, removed ${diff.removed.size}",
        )
        result
    }

    // ---------------------------------------------------------------- MediaStore query

    /**
     * Один курсор с минимальной проекцией. IS_MUSIC и минимальная длительность
     * фильтруются на стороне провайдера, а не в памяти. Сортировка прежняя.
     */
    private fun queryMediaStore(excludeShort: Boolean): List<MediaRow> {
        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            if (excludeShort) append(" AND ${MediaStore.Audio.Media.DURATION} > 30000")
        }
        val rows = ArrayList<MediaRow>()
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
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateModifiedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val relativePathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    -1
                }
                val dataCol = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                } else {
                    -1
                }

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: continue
                    val albumId = cursor.getLong(albumIdCol)
                    val relativePath = if (relativePathCol >= 0) {
                        cursor.getString(relativePathCol)
                    } else {
                        null
                    }
                    val displayName = if (displayNameCol >= 0) {
                        cursor.getString(displayNameCol)
                    } else {
                        null
                    }
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val folder: String
                    val path: String
                    if (relativePath != null) {
                        // Android 10+: путь файла не всегда доступен, но RELATIVE_PATH
                        // из MediaStore достаточен, чтобы различать папки.
                        folder = MEDIASTORE_FOLDER_PREFIX + relativePath
                        path = displayName?.takeIf { it.isNotBlank() } ?: "id:$mediaId"
                    } else if (!dataPath.isNullOrBlank()) {
                        folder = MEDIASTORE_FOLDER_PREFIX + dataPath.substringBeforeLast('/', "")
                        path = dataPath.substringAfterLast('/')
                    } else {
                        folder = MEDIASTORE_FOLDER_PREFIX
                        path = displayName?.takeIf { it.isNotBlank() } ?: "id:$mediaId"
                    }
                    rows += MediaRow(
                        mediaId = mediaId,
                        albumId = albumId,
                        title = title,
                        artist = cursor.getString(artistCol).orEmpty(),
                        album = cursor.getString(albumCol).orEmpty(),
                        duration = cursor.getLong(durationCol),
                        trackNo = if (trackCol >= 0) cursor.getInt(trackCol) else 0,
                        year = if (yearCol >= 0) cursor.getInt(yearCol) else 0,
                        relativePath = relativePath,
                        displayName = displayName,
                        signature = ScanSignature(
                            path = path,
                            size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                            dateModified = if (dateModifiedCol >= 0) {
                                cursor.getLong(dateModifiedCol)
                            } else {
                                0L
                            },
                            folder = folder,
                        ),
                    )
                }
            }
        }.onFailure { error -> Log.w(TAG, "MediaStore query failed", error) }
        return rows
    }

    /** Строка MediaStore, уже вычитанная из курсора: из неё строим Track и сигнатуру. */
    private class MediaRow(
        val mediaId: Long,
        val albumId: Long,
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val trackNo: Int,
        val year: Int,
        val relativePath: String?,
        val displayName: String?,
        val signature: ScanSignature,
    )

    // ---------------------------------------------------------------- метаданные MediaStore

    /**
     * Читает встроенную обложку и sidecar для новых/изменившихся файлов.
     * Параллельность ограничена [METADATA_PARALLELISM], порядок результата
     * восстанавливается по ключу, ошибка одного файла не роняет скан.
     */
    private suspend fun extractMediaRows(
        rows: List<MediaRow>,
        readEmbedded: Boolean,
        readSidecar: Boolean,
    ): Map<String, Track> {
        if (rows.isEmpty()) return emptyMap()
        return coroutineScope {
            rows.map { row ->
                async(metadataDispatcher) {
                    val track = runCatching { enrichMediaRow(row, readEmbedded, readSidecar) }
                        .getOrElse { error ->
                            Log.w(TAG, "metadata failed for ${row.signature.path}", error)
                            baseMediaTrack(row)
                        }
                    row.signature.key to track
                }
            }.awaitAll().toMap()
        }
    }

    private fun baseMediaTrack(row: MediaRow): Track = Track(
        id = "local:${row.mediaId}",
        title = row.title,
        artist = ArtistNames.clean(row.artist).ifBlank { UNKNOWN_ARTIST },
        album = row.album.takeIf { it.isNotBlank() },
        durationMs = row.duration,
        uri = mediaUri(row.mediaId),
        artworkUri = albumArtFor(row.albumId),
        source = Source.LOCAL,
        playable = true,
        downloadAllowed = true,
        extra = buildMap {
            if (row.trackNo > 0) put("trackNo", row.trackNo.toString())
            if (row.year > 0) put("year", row.year.toString())
        },
    )

    private suspend fun enrichMediaRow(
        row: MediaRow,
        readEmbedded: Boolean,
        readSidecar: Boolean,
    ): Track {
        var track = baseMediaTrack(row)
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
                val file = mediaStoreFile(row.relativePath, row.displayName) ?: return@runCatching
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

    /**
     * Переиспользует метаданные из кэша, но восстанавливает «идентичность» трека
     * из текущей строки MediaStore: id/URI зависят от mediaId, который теоретически
     * может измениться после переиндексации провайдера.
     */
    private fun reuseMediaTrack(row: MediaRow, cached: ScanCacheEntry): Track {
        var track = cached.track.copy(
            id = "local:${row.mediaId}",
            uri = mediaUri(row.mediaId),
        )
        // «Битый» albumart-URI зависит от albumId: если альбом переиндексирован,
        // пересчитываем его. Встроенные/папочные обложки от albumId не зависят.
        if (cached.albumId != row.albumId) {
            val artwork = track.artworkUri
            if (artwork != null && artwork.startsWith(ALBUMART_URI_PREFIX, ignoreCase = true)) {
                track = track.copy(artworkUri = albumArtFor(row.albumId))
            }
        }
        return track
    }

    private fun mediaUri(mediaId: Long): String =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId).toString()

    /**
     * URI обложки альбома из MediaStore. На Android 10+ провайдер часто отдаёт
     * `content://media/external/audio/albumart/<id>`, которого физически нет:
     * такой URI открывается с FileNotFoundException. Проверяем открытие и
     * возвращаем null, чтобы «битый» URI не блокировал настоящую обложку.
     */
    private fun albumArtFor(albumId: Long): String? {
        if (albumId <= 0L) return null
        albumArtCache[albumId]?.let { cachedValue ->
            return cachedValue.takeIf { it.isNotEmpty() }
        }
        val value = albumArtUri(albumId).orEmpty()
        val previous = albumArtCache.putIfAbsent(albumId, value)
        return (previous ?: value).takeIf { it.isNotEmpty() }
    }

    private fun albumArtUri(albumId: Long): String? {
        if (albumId <= 0L) return null
        val uri = ContentUris.withAppendedId(Uri.parse(ALBUMART_URI_PREFIX), albumId)
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

    // ---------------------------------------------------------------- папка импорта

    /**
     * Сканирует папку, которую MediaStore не индексирует (например,
     * `getExternalFilesDir(MUSIC)/Sonora`, куда попадают импортированные файлы).
     *
     * @param forceFull игнорировать кэш и перечитать метаданные всех файлов папки.
     */
    suspend fun scanFolder(
        dir: File,
        readEmbedded: Boolean = true,
        readSidecar: Boolean = true,
        forceFull: Boolean = false,
    ): List<Track> = withContext(Dispatchers.IO) {
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
        val files = dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            ?: return@withContext emptyList()
        if (files.isEmpty()) return@withContext emptyList()

        val startedAt = System.nanoTime()
        val flags = cacheFlags(readEmbedded, readSidecar)
        val folder = dir.absolutePath
        val signatures = files.map { file ->
            ScanSignature(
                path = file.absolutePath,
                size = file.length(),
                dateModified = file.lastModified(),
                folder = folder,
            )
        }
        val previous = if (forceFull) {
            emptyMap()
        } else {
            cache.signaturesFor(flags) { cachedFolder -> cachedFolder == folder }
        }
        val diff = if (forceFull) {
            ScanDiff(changed = signatures.mapTo(LinkedHashSet()) { it.key })
        } else {
            diffScanSignatures(signatures, previous)
        }
        val cached = if (diff.unchanged.isEmpty()) {
            emptyMap()
        } else {
            cache.entriesFor(flags, diff.unchanged)
        }
        val changedSignatures = signatures.filter { it.key in diff.changed }
        val read = readFolderFiles(changedSignatures, readEmbedded, readSidecar)

        val result = ArrayList<Track>(files.size)
        val freshEntries = ArrayList<ScanCacheEntry>(files.size)
        var reused = 0
        val now = System.currentTimeMillis()
        signatures.forEach { signature ->
            val key = signature.key
            val cachedEntry = cached[key]
            val track = if (cachedEntry != null) {
                reused++
                cachedEntry.track
            } else {
                read[key]
            }
            if (track != null) {
                freshEntries += ScanCacheEntry(
                    path = signature.path,
                    size = signature.size,
                    dateModified = signature.dateModified,
                    folder = signature.folder,
                    track = track,
                    cachedAt = now,
                )
                // Видеофайлы не попадают в музыкальную фонотеку и плеер, но
                // кэшируются — чтобы не перечитывать их retriever'ом каждый скан.
                if (!track.isVideoContent) result += track
            }
        }
        cache.commit(flags, diff.removed, freshEntries)
        Log.i(
            TAG,
            "scan folder '${dir.name}': ${elapsedMs(startedAt)} ms, ${result.size} tracks, " +
                "reused $reused, extracted ${read.size}, removed ${diff.removed.size}",
        )
        result
    }

    /** Читает изменённые/новые файлы папки параллельно, сохраняя порядок по ключу. */
    private suspend fun readFolderFiles(
        signatures: List<ScanSignature>,
        readEmbedded: Boolean,
        readSidecar: Boolean,
    ): Map<String, Track> {
        if (signatures.isEmpty()) return emptyMap()
        return coroutineScope {
            signatures.map { signature ->
                async(metadataDispatcher) {
                    val track = runCatching {
                        readFileWithSidecars(File(signature.path), readEmbedded, readSidecar)
                    }.getOrNull()
                    signature.key to track
                }
            }.awaitAll()
                .mapNotNull { (key, track) -> track?.let { key to it } }
                .toMap()
        }
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
                ?: UNKNOWN_ARTIST
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

    private fun elapsedMs(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000

    private companion object {
        /** Тег для logcat-фильтра `adb logcat -s LibraryPerf`. */
        const val TAG = "LibraryPerf"

        /** Сколько файлов читаем одновременно: 4–6 — оптимум для IO-bound чтения тегов. */
        const val METADATA_PARALLELISM = 4

        /** Префикс тега папки MediaStore в кэше; сам RELATIVE_PATH идёт следом. */
        const val MEDIASTORE_FOLDER_PREFIX = "@mediastore:"

        const val ALBUMART_URI_PREFIX = "content://media/external/audio/albumart"

        const val UNKNOWN_ARTIST = "Неизвестный исполнитель"

        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "opus", "ogg", "m4a", "aac", "wav", "webm", "mp4",
        )

        /** Флаги, меняющие состав метаданных: входят в заголовок кэша. */
        fun cacheFlags(readEmbedded: Boolean, readSidecar: Boolean): String =
            "embedded=$readEmbedded;sidecar=$readSidecar"
    }
}

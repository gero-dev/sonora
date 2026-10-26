package app.sonora.data.import

import android.content.Context
import android.net.Uri
import app.sonora.core.model.Playlist
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.util.AppLinks
import app.sonora.data.metadata.ArtistNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/**
 * Импорт библиотеки YouTube Music из **Google Takeout** (CSV или ZIP) без API-ключей.
 *
 * Из файла берутся название, исполнитель, альбом, длительность и videoId.
 * Строки с videoId получают `innertube:<videoId>` и играются через InnerTube;
 * без videoId остаётся только deep link в YouTube Music.
 *
 * Поддерживаются:
 *  - один CSV-файл (например `music-library-songs.csv`);
 *  - ZIP из Takeout: парсятся **все** вложенные `*.csv`, результаты объединяются.
 *
 * Ошибки не пробрасываются: любая проблема чтения превращается в warning в [Result].
 * Лимиты — [MAX_LINES] строк и [MAX_TRACKS] треков — защищают память и библиотеку.
 */
class TakeoutImporter(private val context: Context) {

    /** Результат импорта: треки/плейлисты для `cacheRemoteTracks` + предупреждения для UI. */
    data class Result(
        val tracks: List<Track> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
        val warnings: List<String> = emptyList(),
    )

    /**
     * Читает Takeout по SAF-ссылке. Всегда возвращает [Result] (даже при ошибках),
     * кроме отмены корутины — она пробрасывается как обычно.
     */
    suspend fun import(uri: Uri): Result = withContext(Dispatchers.IO) {
        val state = ImportState()
        try {
            parse(uri, state)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            state.readError = true
            state.warnings.add("Не удалось прочитать файл: ${describe(error)}")
        }
        buildResult(state)
    }

    // ---------------------------------------------------------------- открытие и ZIP

    private fun parse(uri: Uri, state: ImportState) {
        val raw: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("поток открылся пустым")
        raw.use { stream ->
            val buffered = BufferedInputStream(stream)
            if (isZip(buffered)) {
                parseZip(buffered, state)
            } else {
                parseCsv(InputStreamReader(buffered, StandardCharsets.UTF_8), state)
            }
        }
    }

    /** ZIP начинается с сигнатуры `PK` (0x50 0x4B). */
    private fun isZip(input: BufferedInputStream): Boolean {
        input.mark(2)
        return try {
            val first = input.read()
            val second = input.read()
            first == ZIP_SIGNATURE_1 && second == ZIP_SIGNATURE_2
        } finally {
            input.reset()
        }
    }

    private fun parseZip(input: BufferedInputStream, state: ImportState) {
        ZipInputStream(input, StandardCharsets.UTF_8).use { zip ->
            var csvCount = 0
            while (!state.limitReached()) {
                val entry = try {
                    zip.nextEntry
                } catch (error: Exception) {
                    state.warnings.add("Не удалось прочитать архив: ${describe(error)}")
                    return
                } ?: break
                try {
                    if (!entry.isDirectory && entry.name.endsWith(".csv", ignoreCase = true)) {
                        csvCount++
                        val reader = BufferedReader(
                            InputStreamReader(zip, StandardCharsets.UTF_8),
                        )
                        // Reader не закрываем: иначе закроется весь ZipInputStream.
                        parseCsv(reader, state)
                    }
                } catch (error: Exception) {
                    state.warnings.add("Не удалось прочитать файл ${entry.name}: ${describe(error)}")
                } finally {
                    runCatching { zip.closeEntry() }
                }
            }
            if (csvCount == 0) {
                state.warnings.add("В архиве не найдено CSV-файлов")
            }
        }
    }

    // ---------------------------------------------------------------- CSV

    /**
     * Разбирает один CSV: первая непустая запись — заголовок (если в ней есть
     * известные колонки), иначе сразу данные с позиционной раскладкой title=0,
     * artist=1.
     */
    private fun parseCsv(reader: Reader, state: ImportState) {
        val csv = CsvReader(reader, state.lineBudget)
        csv.skipByteOrderMark()

        var firstRecord: List<String>? = null
        while (true) {
            val record = csv.nextRecord() ?: break
            if (record.all { it.isBlank() }) continue
            firstRecord = record
            break
        }
        val first = firstRecord ?: return

        val columns = columnsFromHeader(first)
        if (columns != null) {
            if (columns.title < 0) {
                // В Takeout много служебных CSV (подписки, история): без названия это не треки.
                state.skippedFiles++
                return
            }
        } else {
            val positional = ColumnMap(title = 0, artist = 1)
            processRecord(first, positional, state)
        }

        val resolved = columns ?: ColumnMap(title = 0, artist = 1)
        while (!state.limitReached()) {
            val record = csv.nextRecord() ?: break
            if (record.all { it.isBlank() }) continue
            processRecord(record, resolved, state)
        }
    }

    /** Ищет известные колонки по заголовку. null — заголовок не распознан. */
    private fun columnsFromHeader(record: List<String>): ColumnMap? {
        var title = -1
        var artist = -1
        var album = -1
        var videoId = -1
        var playlist = -1
        var duration = -1
        record.forEachIndexed { index, cell ->
            val name = normalizeHeader(cell)
            if (name.isEmpty()) return@forEachIndexed
            when {
                title < 0 && name in TITLE_ALIASES -> title = index
                artist < 0 && name in ARTIST_ALIASES -> artist = index
                album < 0 && name in ALBUM_ALIASES -> album = index
                videoId < 0 && name in VIDEO_ID_ALIASES -> videoId = index
                playlist < 0 && name in PLAYLIST_ALIASES -> playlist = index
                duration < 0 && name in DURATION_ALIASES -> duration = index
            }
        }
        val known = title >= 0 || artist >= 0 || album >= 0 ||
            videoId >= 0 || playlist >= 0 || duration >= 0
        if (!known) return null
        return ColumnMap(
            title = title,
            artist = artist,
            album = album,
            videoId = videoId,
            playlist = playlist,
            duration = duration,
        )
    }

    /** Одна строка CSV -> [Track] (+ принадлежность плейлисту из колонки Playlist). */
    private fun processRecord(record: List<String>, columns: ColumnMap, state: ImportState) {
        if (state.tracksTruncated) return
        val title = record.cell(columns.title)?.clean().orEmpty()
        if (title.isBlank()) {
            state.skippedRows++
            return
        }
        val artist = record.cell(columns.artist)?.clean()?.let(ArtistNames::clean)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_ARTIST
        val album = record.cell(columns.album)?.clean()?.takeIf { it.isNotBlank() }
        val videoId = record.cell(columns.videoId)?.clean()
            ?.takeIf { VIDEO_ID_REGEX.matches(it) }
        val trackId = trackIdFor(videoId, title, artist)

        if (!state.tracks.containsKey(trackId)) {
            if (state.tracks.size >= MAX_TRACKS) {
                state.tracksTruncated = true
                return
            }
            state.tracks[trackId] = Track(
                id = trackId,
                title = title,
                artist = artist,
                album = album,
                durationMs = parseDurationMs(record.cell(columns.duration)),
                uri = videoId?.let { "innertube:$it" },
                source = Source.YOUTUBE,
                playable = videoId != null,
                downloadAllowed = videoId != null,
                deepLink = videoId?.let { AppLinks.youtubeMusicTrack(it) }
                    ?: AppLinks.youtubeMusicSearch("$artist $title"),
                extra = buildMap {
                    put(EXTRA_IMPORTED_FROM, TAKEOUT_MARKER)
                    if (videoId != null) put(EXTRA_VIDEO_ID, videoId)
                },
            )
        }

        // Привязка к плейлисту нужна даже для уже встреченного трека (другой плейлист).
        val playlistName = record.cell(columns.playlist)?.clean()?.takeIf { it.isNotBlank() }
        if (playlistName != null) {
            state.playlistMembers.getOrPut(playlistName) { LinkedHashSet() }.add(trackId)
        }
    }

    // ---------------------------------------------------------------- преобразования

    /** Стабильный id: `youtube:<videoId>` или синтетический `youtube:import:<hex>`. */
    private fun trackIdFor(videoId: String?, title: String, artist: String): String {
        if (videoId != null) return "$YOUTUBE_ID_PREFIX$videoId"
        val key = "$title|${artist.lowercase()}"
        return "$YOUTUBE_ID_PREFIX$IMPORT_ID_PREFIX${Integer.toHexString(key.hashCode())}"
    }

    /**
     * Длительность из секунд (`"245"`, `"245.83"`) или ISO-8601 (`"PT3M45S"`,
     * `"PT1H2M3S"`). Неизвестный формат — 0.
     */
    private fun parseDurationMs(raw: String?): Long {
        val value = raw?.clean().orEmpty()
        if (value.isBlank()) return 0L
        value.toLongOrNull()?.let { seconds -> return seconds * 1000L }
        value.toDoubleOrNull()?.let { seconds -> return (seconds * 1000.0).toLong() }
        val match = ISO_DURATION_REGEX.matchEntire(value) ?: return 0L
        val hours = match.groupValues[1].toDoubleOrNull() ?: 0.0
        val minutes = match.groupValues[2].toDoubleOrNull() ?: 0.0
        val seconds = match.groupValues[3].toDoubleOrNull() ?: 0.0
        return ((hours * 3600.0 + minutes * 60.0 + seconds) * 1000.0).toLong()
    }

    private fun buildResult(state: ImportState): Result {
        val warnings = state.warnings
        if (state.lineBudget.exceeded) warnings.add("Достигнут лимит 200 000 строк")
        if (state.tracksTruncated) warnings.add("Достигнут лимит 10 000 треков")
        if (state.skippedRows > 0) warnings.add("Пропущено строк: ${state.skippedRows}")
        if (state.skippedFiles > 0) {
            warnings.add("Пропущено файлов без колонки названия: ${state.skippedFiles}")
        }

        val playlistEntries = state.playlistMembers.entries.filter { it.value.isNotEmpty() }
        val playlists = playlistEntries
            .take(MAX_PLAYLISTS)
            .map { (name, trackIds) ->
                Playlist(
                    id = "$TAKEOUT_ID_PREFIX${slug(name)}",
                    name = name,
                    source = Source.YOUTUBE,
                    remoteId = null,
                    trackIds = trackIds.toList(),
                    editable = false,
                )
            }
        if (playlistEntries.size > MAX_PLAYLISTS) {
            warnings.add("Импортированы первые 200 плейлистов: достигнут лимит")
        }

        val tracks = state.tracks.values.toList()
        if (tracks.isEmpty() && playlists.isEmpty() && !state.readError) {
            warnings.add(
                0,
                "В файле не нашлось треков — выберите CSV или ZIP из Google Takeout " +
                    "(YouTube и YouTube Music)",
            )
        }
        return Result(
            tracks = tracks,
            playlists = playlists,
            warnings = warnings.toList(),
        )
    }

    // ---------------------------------------------------------------- утилиты

    private fun normalizeHeader(raw: String): String =
        raw.trim()
            .removePrefix(BOM_STRING)
            .lowercase()
            .filter { it.isLetterOrDigit() }

    private fun String.clean(): String = trim().removePrefix(BOM_STRING).trim()

    /** Slug для id плейлиста: читаемое имя + короткий хеш (защита от коллизий). */
    private fun slug(name: String): String {
        val readable = name.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .take(MAX_SLUG_LENGTH)
            .trim('-')
            .ifBlank { DEFAULT_PLAYLIST_SLUG }
        return "$readable-${Integer.toHexString(name.hashCode())}"
    }

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    // ---------------------------------------------------------------- внутренние типы

    private fun List<String>.cell(index: Int): String? =
        if (index in indices) this[index] else null

    private data class ColumnMap(
        val title: Int,
        val artist: Int = -1,
        val album: Int = -1,
        val videoId: Int = -1,
        val playlist: Int = -1,
        val duration: Int = -1,
    )

    private class ImportState {
        val tracks = LinkedHashMap<String, Track>()
        val playlistMembers = LinkedHashMap<String, LinkedHashSet<String>>()
        val warnings = mutableListOf<String>()
        val lineBudget = LineBudget(MAX_LINES)

        var skippedRows = 0
        var skippedFiles = 0
        var tracksTruncated = false
        var readError = false

        fun limitReached(): Boolean = tracksTruncated || lineBudget.exceeded
    }

    private class LineBudget(private val limit: Int) {
        var used = 0
            private set
        var exceeded = false
            private set

        fun countLine() {
            used++
            if (used > limit) exceeded = true
        }
    }

    /**
     * Минимальный RFC4180-парсер: кавычки, `""` внутри кавычек, запятые и
     * переводы строк внутри полей. Читает потоково, не грузя файл в память.
     */
    private class CsvReader(
        private val reader: Reader,
        private val budget: LineBudget,
    ) {
        private var pushback = NONE
        private var bomChecked = false

        fun skipByteOrderMark() {
            if (bomChecked) return
            bomChecked = true
            val first = reader.read()
            if (first >= 0 && first != BOM_CODE) pushback = first
        }

        fun nextRecord(): List<String>? {
            if (budget.exceeded) return null
            val fields = ArrayList<String>()
            val field = StringBuilder()
            var inQuotes = false
            var readAny = false

            while (true) {
                if (budget.exceeded) return null
                val code = readChar() ?: break
                readAny = true
                if (inQuotes) {
                    when (code) {
                        CODE_QUOTE -> {
                            val next = readChar()
                            if (next == CODE_QUOTE) {
                                field.append('"')
                            } else {
                                inQuotes = false
                                if (next != null) pushback = next
                            }
                        }
                        CODE_CR -> {
                            val next = readChar()
                            if (next == CODE_LF) {
                                field.append('\n')
                                budget.countLine()
                            } else if (next != null) {
                                pushback = next
                            }
                        }
                        CODE_LF -> {
                            field.append('\n')
                            budget.countLine()
                        }
                        else -> field.append(code.toChar())
                    }
                } else {
                    when (code) {
                        CODE_QUOTE -> {
                            if (field.isEmpty()) inQuotes = true else field.append('"')
                        }
                        CODE_COMMA -> {
                            fields.add(field.toString())
                            field.setLength(0)
                        }
                        CODE_CR -> Unit // часть CRLF, перевод строки придёт как LF
                        CODE_LF -> {
                            fields.add(field.toString())
                            budget.countLine()
                            return fields
                        }
                        else -> field.append(code.toChar())
                    }
                }
            }
            if (!readAny) return null
            fields.add(field.toString())
            return fields
        }

        private fun readChar(): Int? {
            if (pushback != NONE) {
                val value = pushback
                pushback = NONE
                return value
            }
            val value = reader.read()
            return if (value < 0) null else value
        }

        private companion object {
            const val NONE = -2
            const val BOM_CODE = 0xFEFF
            const val CODE_QUOTE = 0x22
            const val CODE_COMMA = 0x2C
            const val CODE_LF = 0x0A
            const val CODE_CR = 0x0D
        }
    }

    private companion object {
        const val MAX_LINES = 200_000
        const val MAX_TRACKS = 10_000
        const val MAX_PLAYLISTS = 200
        const val MAX_SLUG_LENGTH = 48

        const val DEFAULT_ARTIST = "YouTube"
        const val DEFAULT_PLAYLIST_SLUG = "playlist"

        const val TAKEOUT_MARKER = "takeout"
        const val TAKEOUT_ID_PREFIX = "takeout:"
        const val YOUTUBE_ID_PREFIX = "youtube:"
        const val IMPORT_ID_PREFIX = "import:"
        const val EXTRA_IMPORTED_FROM = "importedFrom"
        const val EXTRA_VIDEO_ID = "videoId"

        const val BOM_STRING = "\uFEFF"
        const val ZIP_SIGNATURE_1 = 0x50 // 'P'
        const val ZIP_SIGNATURE_2 = 0x4B // 'K'

        /** YouTube videoId — ровно 11 символов base64url. */
        val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

        /** ISO-8601 длительность вида `PT1H2M3.5S` (дни/недели для трека не нужны). */
        val ISO_DURATION_REGEX = Regex(
            """(?i)^P(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$""",
        )

        // Заголовки нормализуются: lowercase, без пробелов/подчёркиваний/кавычек.
        val TITLE_ALIASES = setOf(
            "songtitle", "title", "name", "track", "trackname", "song",
            "название", "композиция",
        )
        val ARTIST_ALIASES = setOf(
            "artist", "artists", "artistname", "artistname1", "artistname2",
            "videocreator", "channel", "channeltitle", "исполнитель", "артист",
        )
        val ALBUM_ALIASES = setOf("album", "albumtitle", "albumname", "альбом")
        val VIDEO_ID_ALIASES = setOf("videoid", "id")
        val PLAYLIST_ALIASES = setOf(
            "playlisttitle", "playlist", "playlistname", "плейлист",
        )
        val DURATION_ALIASES = setOf(
            "duration", "durationseconds", "длительность",
        )
    }
}

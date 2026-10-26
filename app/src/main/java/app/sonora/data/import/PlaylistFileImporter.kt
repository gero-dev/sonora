package app.sonora.data.import

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.FileNotFoundException

/** Один трек, прочитанный из файла другого сервиса. */
data class ImportedTrackEntry(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val videoId: String? = null,
    val url: String? = null,
)

data class PlaylistFileData(
    val fileName: String,
    val entries: List<ImportedTrackEntry>,
    val warnings: List<String> = emptyList(),
)

/**
 * Импорт списка треков из файлов Spotify/Apple Music/YouTube Music и обычных
 * списков: CSV/TSV, JSON, M3U/M3U8 и TXT («Артист — Название»).
 *
 * Это только список названий: аудио не скачивается. Совпадения ищутся в
 * локальной библиотеке и InnerTube, поэтому итоговый плейлист создаётся уже
 * в Sonora.
 */
class PlaylistFileImporter(private val context: Context) {

    suspend fun read(uri: Uri): PlaylistFileData = withContext(Dispatchers.IO) {
        val fileName = displayName(uri) ?: "playlist"
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(MAX_BYTES)
            var read = 0
            while (read < buffer.size) {
                val chunk = input.read(buffer, read, buffer.size - read)
                if (chunk <= 0) break
                read += chunk
            }
            buffer.copyOf(read)
        } ?: throw FileNotFoundException("Не удалось открыть файл")
        if (bytes.isEmpty()) throw IllegalArgumentException("Файл пустой")
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val entries = PlaylistFileParser.parse(fileName, text)
        if (entries.isEmpty()) {
            throw IllegalArgumentException(
                "В файле не найдено треков. Поддерживаются CSV, JSON, M3U/M3U8 и TXT.",
            )
        }
        PlaylistFileData(
            fileName = fileName,
            entries = entries.take(MAX_ENTRIES),
            warnings = if (entries.size > MAX_ENTRIES) {
                listOf("Взяты первые $MAX_ENTRIES треков из ${entries.size}.")
            } else {
                emptyList()
            },
        )
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
    }.getOrNull()

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_ENTRIES = 1_000
    }
}

object PlaylistFileParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(fileName: String, raw: String): List<ImportedTrackEntry> {
        val text = raw.removePrefix("\uFEFF").trim()
        if (text.isBlank()) return emptyList()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val parsed = when {
            extension == "json" || text.startsWith("[") || text.startsWith("{") ->
                parseJson(text).ifEmpty { parseText(text) }

            extension == "m3u" || extension == "m3u8" || text.contains("#EXTM3U") ->
                parseM3u(text).ifEmpty { parseText(text) }

            extension == "csv" || extension == "tsv" || looksLikeTable(text) ->
                parseTable(text).ifEmpty { parseText(text) }

            else -> parseText(text)
        }
        return parsed
            .filter { it.title.isNotBlank() }
            .distinctBy { entry ->
                "${entry.title.lowercase().trim()}|${entry.artist.orEmpty().lowercase().trim()}"
            }
            .take(MAX_ENTRIES)
    }

    // ---------------------------------------------------------------- JSON

    private fun parseJson(text: String): List<ImportedTrackEntry> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ImportedTrackEntry>()
        fun visit(element: JsonElement) {
            when (element) {
                is JsonArray -> element.forEach(::visit)
                is JsonObject -> {
                    // Spotify-обёртка плейлиста: {"track": {...}}.
                    val nestedTrack = element.obj("track")
                    if (nestedTrack != null && element.firstString("name") == null) {
                        visit(nestedTrack)
                    } else {
                        val entry = toEntry(element)
                        if (entry != null) {
                            out += entry
                        } else {
                            element.values.forEach { value ->
                                if (value !== nestedTrack) visit(value)
                            }
                        }
                    }
                }

                else -> Unit
            }
        }
        visit(root)
        return out
    }

    private fun toEntry(obj: JsonObject): ImportedTrackEntry? {
        // Плейлисты, альбомы и артисты тоже имеют name, но не являются треками.
        val kind = obj.firstString("type", "kind", "typeName")?.lowercase()
        if (kind in CONTAINER_KINDS) return null
        if (obj["album_type"] != null) return null
        if (obj.obj("tracks") != null && obj.obj("album") == null) return null
        val title = obj.firstString(
            "title", "trackName", "track_name", "songTitle", "song_title", "song", "name",
        )?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val artist = obj.stringList("artists")
            ?: obj.firstString("artist", "artistName", "artist_name", "albumArtist", "album_artist")
            ?: obj.obj("album")?.stringList("artists")
            ?: obj.obj("snippet")?.firstString("channelTitle")
        val album = obj.firstString("albumName", "album_name", "collectionName", "collection_name")
            ?: obj.obj("album")?.firstString("name", "title")
            ?: obj.stringValue("album")
            ?: obj.obj("snippet")?.firstString("album")
        val duration = obj.firstLong(
            "duration_ms", "durationMs", "durationInMillis", "duration_in_millis", "duration", "length",
        )?.let { if (it in 1..20_000) it * 1_000 else it }
        val videoId = obj.firstString("videoId", "video_id", "youtubeId", "youtube_id")
            ?: obj.obj("resourceId")?.firstString("videoId")
            ?: obj.obj("snippet")?.obj("resourceId")?.firstString("videoId")
        val url = obj.firstString("url", "link", "spotifyUrl", "spotify_url", "youtubeUrl", "youtube_url")
            ?: obj.obj("external_urls")?.values
                ?.filterIsInstance<JsonPrimitive>()
                ?.mapNotNull { it.contentOrNull }
                ?.firstOrNull { it.startsWith("http") }
            ?: obj.obj("external_urls")?.stringValue("spotify")
        // Плейлист/альбом без исполнителя и длительности — не трек.
        val trackLike = obj.obj("tracks") == null &&
            (artist != null || duration != null || videoId != null ||
                obj.firstString("type", "kind")?.contains("track", ignoreCase = true) == true)
        if (!trackLike) return null
        return ImportedTrackEntry(
            title = title,
            artist = artist?.trim()?.takeIf { it.isNotBlank() },
            album = album?.trim()?.takeIf { it.isNotBlank() },
            durationMs = duration?.takeIf { it > 0 },
            videoId = videoId?.trim()?.takeIf { it.isNotBlank() },
            url = url?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    private fun JsonObject.firstString(vararg keys: String): String? {
        for (key in keys) {
            val value = this[key] ?: continue
            stringValue(value)?.let { return it }
        }
        return null
    }

    private fun JsonObject.stringValue(key: String): String? = stringValue(this[key])

    private fun stringValue(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonObject -> element.firstString("name", "title", "text")
        else -> null
    }

    private fun JsonObject.stringList(key: String): String? {
        val array = this[key] as? JsonArray ?: return null
        val values = array.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonObject -> item.firstString("name", "title")
                else -> null
            }
        }.filter { it.isNotBlank() }
        return values.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun JsonObject.firstLong(vararg keys: String): Long? {
        for (key in keys) {
            val value = this[key] as? JsonPrimitive ?: continue
            val raw = value.contentOrNull ?: continue
            raw.toLongOrNull()?.let { return it }
            parseClockMs(raw)?.let { return it }
        }
        return null
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    // ---------------------------------------------------------------- CSV / TSV

    private fun looksLikeTable(text: String): Boolean {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        if (firstLine.count { it == '\t' } >= 1) return true
        if (firstLine.count { it == ';' } >= 1) return true
        if (firstLine.count { it == ',' } < 1) return false
        val normalized = firstLine.lowercase()
        return TITLE_HEADERS.any { it in normalized } || ARTIST_HEADERS.any { it in normalized }
    }

    private fun parseTable(text: String): List<ImportedTrackEntry> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        val delimiter = when {
            lines.first().count { it == '\t' } >= 1 -> '\t'
            lines.first().count { it == ';' } >= 1 -> ';'
            else -> ','
        }
        val rows = lines.map { parseDelimitedLine(it, delimiter) }
        val header = rows.firstOrNull().orEmpty().map { normalizeHeader(it) }
        val hasHeader = header.any { it in TITLE_HEADERS || it in ARTIST_HEADERS }
        val dataRows = if (hasHeader) rows.drop(1) else rows

        fun column(row: List<String>, headers: Set<String>, fallback: Int): String? {
            if (hasHeader) {
                val index = header.indexOfFirst { it in headers }
                return row.getOrNull(index)?.trim()?.takeIf { it.isNotBlank() }
            }
            return row.getOrNull(fallback)?.trim()?.takeIf { it.isNotBlank() }
        }

        return dataRows.mapNotNull { row ->
            val title = column(row, TITLE_HEADERS, 0) ?: return@mapNotNull null
            val artist = column(row, ARTIST_HEADERS, 1)
            val album = column(row, ALBUM_HEADERS, 2)
            val duration = column(row, DURATION_HEADERS, 3)?.let(::parseClockMs)
            val videoId = column(row, VIDEO_ID_HEADERS, -1)
                ?.takeIf { VIDEO_ID.matches(it) }
            val url = column(row, URL_HEADERS, -1)
            ImportedTrackEntry(
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                videoId = videoId,
                url = url,
            )
        }
    }

    private fun parseDelimitedLine(line: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }

                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> {
                    out += current.toString()
                    current.clear()
                }

                else -> current.append(char)
            }
            index++
        }
        out += current.toString()
        return out
    }

    // ---------------------------------------------------------------- M3U

    private fun parseM3u(text: String): List<ImportedTrackEntry> {
        val out = ArrayList<ImportedTrackEntry>()
        var pending: Pair<String?, Long?>? = null
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed == "#EXTM3U") continue
            if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                val body = trimmed.substringAfter(':')
                val durationSeconds = body.substringBefore(',').toLongOrNull()
                val title = body.substringAfter(',', "").trim().takeIf { it.isNotBlank() }
                pending = title to durationSeconds?.times(1_000)
                continue
            }
            if (trimmed.startsWith("#")) continue
            val (rawTitle, duration) = pending ?: continue
            val parsed = splitArtistTitle(rawTitle.orEmpty())
            out += ImportedTrackEntry(
                title = parsed.second,
                artist = parsed.first,
                durationMs = duration,
                videoId = youtubeId(trimmed),
                url = trimmed.takeIf { it.startsWith("http") },
            )
            pending = null
        }
        return out
    }

    // ---------------------------------------------------------------- TXT

    private fun parseText(text: String): List<ImportedTrackEntry> {
        return text.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                return@mapNotNull null
            }
            val (artist, title) = splitArtistTitle(trimmed)
            if (title.isBlank()) return@mapNotNull null
            ImportedTrackEntry(
                title = title,
                artist = artist,
                videoId = youtubeId(trimmed),
                url = trimmed.takeIf { it.startsWith("http") },
            )
        }
    }

    private fun splitArtistTitle(value: String): Pair<String?, String> {
        val trimmed = value.trim()
        val separators = listOf(" – ", " — ", " - ")
        for (separator in separators) {
            val index = trimmed.indexOf(separator)
            if (index > 0 && index < trimmed.length - separator.length) {
                val artist = trimmed.substring(0, index).trim().takeIf { it.isNotBlank() }
                val title = trimmed.substring(index + separator.length).trim()
                return artist to title
            }
        }
        return null to trimmed
    }

    private fun parseClockMs(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.all { it.isDigit() }) {
            val number = trimmed.toLongOrNull() ?: return null
            // В CSV «225» — это секунды, в JSON — обычно миллисекунды.
            return if (number in 1..20_000) number * 1_000 else number
        }
        val parts = trimmed.split(':').mapNotNull { it.toDoubleOrNull() }
        if (parts.isEmpty()) return null
        var seconds = 0.0
        parts.forEach { seconds = seconds * 60 + it }
        return (seconds * 1_000).toLong().takeIf { it > 0 }
    }

    private fun youtubeId(value: String): String? {
        val patterns = listOf(
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""music\.youtube\.com/watch\?v=([A-Za-z0-9_-]{11})"""),
        )
        for (pattern in patterns) {
            pattern.find(value)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private fun normalizeHeader(value: String): String =
        value.trim().lowercase().replace(Regex("[^a-zа-я0-9]+"), " ").trim()

    private val TITLE_HEADERS = setOf(
        "title", "name", "track", "track name", "song", "song title", "song name",
        "название", "трек", "песня",
    )
    private val ARTIST_HEADERS = setOf(
        "artist", "artists", "artist name", "artist name s", "artist s", "исполнитель",
        "album artist", "channel", "channel title",
    )
    private val ALBUM_HEADERS = setOf("album", "album name", "collection", "collection name", "альбом")
    private val DURATION_HEADERS = setOf("duration", "duration ms", "length", "time", "длительность")
    private val VIDEO_ID_HEADERS = setOf("video id", "videoid", "youtube id", "youtubeid")
    private val URL_HEADERS = setOf(
        "url", "link", "spotify url", "youtube url", "external url", "track uri", "spotify uri",
    )

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val CONTAINER_KINDS = setOf(
        "album", "playlist", "artist", "show", "podcast", "episode", "user", "collection",
    )
    private const val MAX_ENTRIES = 1_000
}

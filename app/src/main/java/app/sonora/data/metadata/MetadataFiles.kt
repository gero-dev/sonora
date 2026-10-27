package app.sonora.data.metadata

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * Одна строка «телеметрии» из файла, подготовленного пользователем
 * (JSON/CSV/NFO). Используется, чтобы уточнить альбом, год, обложку и текст
 * сохранённых на устройстве треков до похода в онлайн-базы.
 */
data class MetadataFileEntry(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val coverUrl: String? = null,
    val lyrics: String? = null,
)

/**
 * Чтение метаданных из файлов, которые пользователь выбирает вручную.
 *
 * Поддерживаются:
 *  - JSON: массив объектов или объект с массивом `tracks`;
 *    ключи `title/track/name`, `artist/author`, `album`, `year/date`,
 *    `cover/coverUrl/artwork`, `lyrics/text`.
 *  - CSV: первая строка — заголовки (artist,title,album,year,cover,lyrics),
 *    либо позиционно `artist,title,album,year`; разделитель `,` или `;`.
 *  - NFO: простые XML-теги `<title>`, `<artist>`, `<album>`, `<year>`,
 *    `<thumb>`, `<lyrics>` (одна песня или несколько блоков).
 */
object MetadataFiles {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Читает выбранный пользователем файл и возвращает найденные записи. */
    suspend fun parse(context: Context, uri: Uri): List<MetadataFileEntry> =
        withContext(Dispatchers.IO) {
            val name = (queryDisplayName(context, uri) ?: "").lowercase()
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            if (text.isBlank()) return@withContext emptyList()
            when {
                name.endsWith(".json") || text.trimStart().startsWith("{") ||
                    text.trimStart().startsWith("[") -> parseJson(text)
                name.endsWith(".nfo") || name.endsWith(".xml") -> parseNfo(text)
                name.endsWith(".csv") || name.endsWith(".tsv") || name.endsWith(".txt") ->
                    parseCsv(text)
                else -> parseJson(text) + parseNfo(text) + parseCsv(text)
            }
        }

    /** Читает sidecar-файл рядом с аудио (используется сканером). */
    fun parseFile(file: File): List<MetadataFileEntry> {
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        if (text.isBlank()) return emptyList()
        val name = file.name.lowercase()
        return when {
            name.endsWith(".json") -> parseJson(text)
            name.endsWith(".nfo") || name.endsWith(".xml") -> parseNfo(text)
            name.endsWith(".lrc") -> emptyList()
            name.endsWith(".csv") || name.endsWith(".tsv") -> parseCsv(text)
            else -> parseJson(text) + parseNfo(text)
        }
    }

    // ---------------------------------------------------------------- JSON

    private fun parseJson(text: String): List<MetadataFileEntry> = runCatching {
        val root: JsonElement = json.parseToJsonElement(text)
        val objects = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> {
                val nested = root["tracks"] ?: root["entries"] ?: root["items"]
                if (nested is JsonArray) {
                    nested.mapNotNull { it as? JsonObject }
                } else {
                    listOf(root)
                }
            }
            else -> emptyList()
        }
        objects.mapNotNull { obj -> fromJsonObject(obj) }
    }.getOrDefault(emptyList())

    private fun fromJsonObject(obj: JsonObject): MetadataFileEntry? {
        fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
        val title = str("title", "track", "name")
        val artist = str("artist", "author", "performer")
        val album = str("album", "albumTitle")
        val yearRaw = str("year", "date", "releaseDate")
        val cover = str("cover", "coverUrl", "artwork", "artworkUrl", "image")
        val lyrics = str("lyrics", "text", "plainLyrics")
        if (title == null && artist == null && album == null && cover == null &&
            lyrics == null
        ) {
            return null
        }
        return MetadataFileEntry(
            title = title,
            artist = artist,
            album = album,
            year = yearRaw?.take(4)?.toIntOrNull() ?: yearRaw?.toIntOrNull(),
            coverUrl = cover,
            lyrics = lyrics,
        )
    }

    // ---------------------------------------------------------------- NFO

    private fun parseNfo(text: String): List<MetadataFileEntry> = runCatching {
        val cleaned = text
            .replace(Regex("(?is)<\\?xml[^>]*>"), " ")
            .replace(Regex("(?is)<!DOCTYPE[^>]*>"), " ")
        // NFO-файл на альбом содержит несколько <track>; на один трек — один набор тегов.
        val blocks = Regex("(?is)<track[^>]*>(.*?)</track>").findAll(cleaned)
            .map { it.groupValues[1] }
            .toList()
            .ifEmpty { listOf(cleaned) }
        blocks.mapNotNull { block ->
            fun tag(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
                Regex("(?is)<$name[^>]*>(.*?)</$name>").find(block)
                    ?.groupValues?.getOrNull(1)
                    ?.replace(Regex("(?s)<[^>]+>"), " ")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
            val title = tag("title", "track")
            val artist = tag("artist", "performer")
            val album = tag("album")
            val year = tag("year")?.take(4)?.toIntOrNull()
            val cover = tag("thumb", "cover", "artwork")
            val lyrics = tag("lyrics", "text")
            if (title == null && artist == null && album == null && cover == null &&
                lyrics == null
            ) {
                null
            } else {
                MetadataFileEntry(
                    title = title,
                    artist = artist,
                    album = album,
                    year = year,
                    coverUrl = cover,
                    lyrics = lyrics,
                )
            }
        }
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- CSV

    private fun parseCsv(text: String): List<MetadataFileEntry> {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return emptyList()
        val separator = if (lines.first().count { it == ';' } > lines.first().count { it == ',' }) {
            ';'
        } else {
            ','
        }
        val header = splitCsvLine(lines.first(), separator)
            .map { it.lowercase().trim().trim('"') }
        val hasHeader = header.any { it in HEADER_HINTS }
        val indexes = if (hasHeader) {
            mapOf(
                "artist" to header.indexOfFirst { it in ARTIST_KEYS },
                "title" to header.indexOfFirst { it in TITLE_KEYS },
                "album" to header.indexOfFirst { it in ALBUM_KEYS },
                "year" to header.indexOfFirst { it in YEAR_KEYS },
                "cover" to header.indexOfFirst { it in COVER_KEYS },
                "lyrics" to header.indexOfFirst { it in LYRICS_KEYS },
            )
        } else {
            mapOf("artist" to 0, "title" to 1, "album" to 2, "year" to 3)
        }
        return lines.drop(if (hasHeader) 1 else 0).mapNotNull { line ->
            val cells = splitCsvLine(line, separator).map { it.trim().trim('"') }
            fun cell(key: String): String? = indexes[key]
                ?.takeIf { it >= 0 }
                ?.let { cells.getOrNull(it) }
                ?.takeIf { it.isNotBlank() }
            val title = cell("title")
            val artist = cell("artist")
            val album = cell("album")
            val cover = cell("cover")
            val lyrics = cell("lyrics")
            if (title == null && artist == null && album == null && cover == null &&
                lyrics == null
            ) {
                null
            } else {
                MetadataFileEntry(
                    title = title,
                    artist = artist,
                    album = album,
                    year = cell("year")?.take(4)?.toIntOrNull(),
                    coverUrl = cover,
                    lyrics = lyrics,
                )
            }
        }
    }

    private fun splitCsvLine(line: String, separator: Char): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' -> {
                    if (quoted && index + 1 < line.length && line[index + 1] == '"') {
                        current.append('"')
                        index++
                    } else {
                        quoted = !quoted
                    }
                }
                char == separator && !quoted -> {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        result += current.toString()
        return result
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index < 0) null else {
                cursor.moveToFirst()
                cursor.getString(index)
            }
        }
    }.getOrNull()

    private val HEADER_HINTS = setOf(
        "artist", "title", "track", "album", "year", "date", "cover", "lyrics", "author",
    )
    private val ARTIST_KEYS = setOf("artist", "author", "performer", "исполнитель")
    private val TITLE_KEYS = setOf("title", "track", "name", "название", "трек")
    private val ALBUM_KEYS = setOf("album", "альбом")
    private val YEAR_KEYS = setOf("year", "date", "год", "release_date")
    private val COVER_KEYS = setOf("cover", "coverurl", "artwork", "image", "обложка")
    private val LYRICS_KEYS = setOf("lyrics", "text", "текст")
}

package app.sonora.data.library

import android.net.Uri
import app.sonora.core.model.Track
import app.sonora.data.metadata.ArtistNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/** Метаданные из sidecar-файлов рядом с аудио (JSON/NFO/LRC/обложка). */
data class SidecarInfo(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    /** http(s)-обложка или file-URI (`cover.jpg` рядом с файлом). */
    val coverUrl: String? = null,
    val lyrics: String? = null,
    val syncedLyrics: String? = null,
)

/**
 * Чтение метаданных из файлов рядом с треком: `<имя>.json`, `<имя>.nfo`,
 * `<имя>.lrc`, `album.json` и `cover.*` в папке. Всё локально, без сети.
 *
 * Каждый формат читается изолированно: битый JSON/NFO/LRC просто игнорируется,
 * а остальные источники продолжают работать.
 */
object SidecarMetadata {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val BOM = "\uFEFF"
    private val YEAR_RANGE = 1000..2999

    /** Числовой префикс дорожки в имени файла («01 - Artist - Song»). */
    private val TRACK_PREFIX = Regex("""^\s*(?:\d{1,3}\s*[._-]\s*|\(\d{1,3}\)\s*)""")
    private val YEAR_REGEX = Regex("""\d{4}""")
    private val LRC_META_REGEX = Regex(
        """(?i)^\[(?:ar|ti|al|by|offset|length|re|ve|tool|encoding)\s*:.*]$""",
    )
    private val LRC_TIMESTAMP_REGEX = Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")

    private val PLACEHOLDER_ALBUMS = setOf(
        "music", "download", "downloads", "audio", "sounds", "ringtones",
        "podcasts", "notifications", "unknown", "unknown album", "<unknown>",
        "[unknown]", "без альбома", "неизвестный альбом", "альбом неизвестен",
        "разное", "various", "va",
    )

    /**
     * Ищет и объединяет sidecar-файлы для [file]. Приоритет: `cover.*` из папки
     * (как самая «папочная» обложка), затем `<имя>.json`, `<имя>.nfo`,
     * `<имя>.lrc` и в конце `album.json` (общие данные альбома).
     */
    fun readFor(file: File): SidecarInfo? {
        if (!file.isFile) return null
        val parent = file.parentFile ?: return null
        val stem = file.nameWithoutExtension
        var result: SidecarInfo? = null

        fun accept(next: SidecarInfo?) {
            if (next == null) return
            result = result?.withFallback(next) ?: next
        }

        // cover.* в папке приоритетнее обложки из JSON/NFO: порядок из задачи —
        // встроенная → валидный albumart → folderCover → sidecar cover.
        runCatching {
            LocalArtwork.folderCover(parent)?.let { cover -> accept(SidecarInfo(coverUrl = cover)) }
        }
        resolveSibling(parent, "$stem.json")?.let { sidecar ->
            runCatching { accept(readJson(sidecar, stem)) }
        }
        resolveSibling(parent, "$stem.nfo")?.let { sidecar ->
            runCatching { accept(readNfo(sidecar)) }
        }
        resolveSibling(parent, "$stem.lrc")?.let { sidecar ->
            runCatching { accept(readLrc(sidecar)) }
        }
        resolveSibling(parent, "album.json")?.let { sidecar ->
            runCatching { accept(readJson(sidecar, stem)) }
        }
        return result?.takeIf { !it.isBlank() }
    }

    /**
     * Совмещает sidecar с треком: непустые поля из файлов перекрывают только
     * пустые/заглушки (`Неизвестный исполнитель`, имя файла в title и т. п.).
     * `year` и `coverUrl` дополнительно сохраняются в `extra`.
     */
    fun apply(track: Track, info: SidecarInfo): Track {
        var updated = track
        val extra = track.extra.toMutableMap()

        if (!info.title.isNullOrBlank() && isPlaceholderTitle(track)) {
            updated = updated.copy(title = info.title.trim())
        }
        if (!info.artist.isNullOrBlank() && ArtistNames.isSuspicious(track.artist)) {
            updated = updated.copy(artist = ArtistNames.clean(info.artist))
        }
        if (!info.album.isNullOrBlank() && isPlaceholderAlbum(track.album)) {
            updated = updated.copy(album = info.album.trim())
        }
        val year = info.year
        val currentYear = track.year
        if (year != null && year > 0 && (currentYear == null || currentYear <= 0)) {
            extra["year"] = year.toString()
        }
        val cover = info.coverUrl?.trim().orEmpty()
        if (cover.isNotEmpty()) {
            if (extra["coverUrl"].isNullOrBlank()) extra["coverUrl"] = cover
            val hasArtwork = !track.artworkUri.isNullOrBlank() || !track.artworkUrl.isNullOrBlank()
            if (!hasArtwork) {
                updated = if (cover.startsWith("file:", ignoreCase = true)) {
                    updated.copy(artworkUri = cover)
                } else {
                    updated.copy(artworkUrl = cover)
                }
            }
        }
        if (!info.lyrics.isNullOrBlank() && track.lyrics.isNullOrBlank()) {
            updated = updated.copy(lyrics = info.lyrics.trim())
        }
        if (!info.syncedLyrics.isNullOrBlank() && track.syncedLyrics.isNullOrBlank()) {
            updated = updated.copy(syncedLyrics = info.syncedLyrics.trim())
        }
        if (extra != track.extra) updated = updated.copy(extra = extra)
        return updated
    }

    // ---------------------------------------------------------------- JSON

    private fun readJson(file: File, stem: String): SidecarInfo? {
        val text = file.readText().removePrefix(BOM).trim()
        if (text.isEmpty()) return null
        val root = json.parseToJsonElement(text)
        val obj = when (root) {
            is JsonObject -> root
            is JsonArray -> selectJsonEntry(root, stem) ?: return null
            else -> return null
        }
        return jsonInfo(obj, file.parentFile)
    }

    /** Для массива треков ищем запись по названию/исполнителю из имени файла. */
    private fun selectJsonEntry(array: JsonArray, stem: String): JsonObject? {
        val entries = array.mapNotNull { it as? JsonObject }
        if (entries.isEmpty()) return null
        val hints = filenameHints(stem)
        val match = entries.firstOrNull { obj ->
            val title = obj.text("title") ?: return@firstOrNull false
            if (normalize(title) != normalize(hints.title)) return@firstOrNull false
            val artist = obj.text("artist")
            hints.artist == null || artist == null ||
                artist.equals(hints.artist, ignoreCase = true) ||
                ArtistNames.matches(artist, hints.artist)
        }
        return match ?: entries.first()
    }

    private fun jsonInfo(obj: JsonObject, parent: File?): SidecarInfo {
        val coverRaw = obj.text("coverUrl") ?: obj.text("cover") ?: obj.text("artwork")
        return SidecarInfo(
            title = obj.text("title"),
            artist = obj.text("artist"),
            album = obj.text("album"),
            year = parseYear(obj["year"]),
            coverUrl = coverRaw?.let { resolveCover(it, parent) },
            lyrics = obj.text("lyrics"),
            syncedLyrics = obj.text("syncedLyrics") ?: obj.text("synced_lyrics"),
        )
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
            ?.removePrefix(BOM)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    private fun parseYear(element: JsonElement?): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        primitive.intOrNull?.let { year -> if (year in YEAR_RANGE) return year }
        val text = primitive.contentOrNull ?: return null
        return parseYearText(text)
    }

    private fun parseYearText(text: String): Int? =
        YEAR_REGEX.find(text)?.value?.toIntOrNull()?.takeIf { it in YEAR_RANGE }

    // ---------------------------------------------------------------- NFO

    private fun readNfo(file: File): SidecarInfo? {
        val text = file.readText().removePrefix(BOM)
        if (text.isBlank()) return null

        fun value(tag: String): String? =
            nfoTag(tag).find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::cleanXml)
                ?.takeIf { it.isNotBlank() }

        val info = SidecarInfo(
            title = value("title"),
            artist = value("artist"),
            album = value("album"),
            year = value("year")?.let(::parseYearText),
            coverUrl = value("thumb")?.let { resolveCover(it, file.parentFile) },
            lyrics = value("lyrics"),
        )
        return info.takeIf { !it.isBlank() }
    }

    private fun nfoTag(tag: String): Regex = Regex(
        """<$tag(?:\s[^>]*)?>(.*?)</$tag>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private fun cleanXml(raw: String): String {
        var value = raw.trim()
        if (value.startsWith("<![CDATA[") && value.endsWith("]]>")) {
            value = value.substring(9, value.length - 3)
        }
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .trim()
    }

    // ---------------------------------------------------------------- LRC

    /** `[mm:ss.xx]`-строки → syncedLyrics; текст без таймкодов → lyrics. */
    private fun readLrc(file: File): SidecarInfo? {
        val text = file.readText().removePrefix(BOM)
        if (text.isBlank()) return null
        val synced = StringBuilder()
        val plain = StringBuilder()
        var timed = false

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            if (LRC_META_REGEX.matches(line)) return@forEach
            val withoutTimestamps = LRC_TIMESTAMP_REGEX.replace(line, "").trim()
            val hadTimestamp = withoutTimestamps.length != line.length
            if (hadTimestamp) {
                timed = true
                synced.append(line).append('\n')
            }
            if (withoutTimestamps.isNotEmpty()) {
                plain.append(withoutTimestamps).append('\n')
            }
        }

        val plainText = plain.toString().trim().takeIf { it.isNotEmpty() }
        val syncedText = synced.toString().trim().takeIf { it.isNotEmpty() && timed }
        if (plainText == null && syncedText == null) return null
        return SidecarInfo(lyrics = plainText, syncedLyrics = syncedText)
    }

    // ---------------------------------------------------------------- файлы и обложки

    /** Ищет файл рядом с аудио: сначала точно, потом без учёта регистра. */
    private fun resolveSibling(parent: File, name: String): File? {
        val direct = File(parent, name)
        if (direct.isFile) return direct
        return runCatching {
            parent.listFiles()?.firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
        }.getOrNull()
    }

    private fun resolveCover(raw: String, parent: File?): String? {
        val value = raw.trim().removePrefix(BOM)
        if (value.isEmpty()) return null
        val lower = value.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file:")) {
            return value
        }
        val base = parent ?: return null
        return runCatching {
            val candidate = File(base, value.removePrefix("./"))
            if (candidate.isFile) Uri.fromFile(candidate).toString() else null
        }.getOrNull()
    }

    // ---------------------------------------------------------------- совмещение

    private fun SidecarInfo.withFallback(fallback: SidecarInfo): SidecarInfo = SidecarInfo(
        title = title ?: fallback.title,
        artist = artist ?: fallback.artist,
        album = album ?: fallback.album,
        year = year ?: fallback.year,
        coverUrl = coverUrl ?: fallback.coverUrl,
        lyrics = lyrics ?: fallback.lyrics,
        syncedLyrics = syncedLyrics ?: fallback.syncedLyrics,
    )

    private fun SidecarInfo.isBlank(): Boolean =
        title == null && artist == null && album == null && year == null &&
            coverUrl == null && lyrics == null && syncedLyrics == null

    private fun isPlaceholderTitle(track: Track): Boolean {
        val title = track.title.trim()
        if (title.isBlank()) return true
        if (title.equals("Без названия", ignoreCase = true)) return true
        val stem = track.uri?.let(::uriStem) ?: return false
        return title.equals(stem, ignoreCase = true)
    }

    private fun isPlaceholderAlbum(album: String?): Boolean {
        val value = album?.trim()?.lowercase() ?: return true
        return value.isBlank() || value in PLACEHOLDER_ALBUMS
    }

    /** Имя файла без пути/расширения — для сравнения с заглушкой в title. */
    private fun uriStem(uri: String): String? {
        val name = uri.substringAfterLast('/').substringBefore('?')
        if (name.isBlank()) return null
        val decoded = runCatching { Uri.decode(name) }.getOrDefault(name)
        return decoded.substringBeforeLast('.', decoded)
    }

    // ---------------------------------------------------------------- имя файла

    private data class NameHints(val artist: String?, val title: String)

    private fun filenameHints(stem: String): NameHints {
        val cleaned = stem.replace(TRACK_PREFIX, "").trim()
        val parts = cleaned.split(" - ", limit = 2)
        return if (parts.size == 2) {
            NameHints(
                artist = parts[0].trim().takeIf { it.isNotBlank() },
                title = parts[1].trim(),
            )
        } else {
            NameHints(artist = null, title = cleaned)
        }
    }

    private fun normalize(value: String): String = value.trim().lowercase()
}

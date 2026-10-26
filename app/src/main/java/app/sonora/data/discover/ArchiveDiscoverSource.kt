package app.sonora.data.discover

import android.content.Context
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Internet Archive (archive.org) — открытый каталог аудио без API-ключа.
 *
 * Работает с двумя публичными endpoint'ами:
 *  1. advancedsearch.php — поиск айтемов с mediatype:(audio);
 *  2. /metadata/<identifier> — список файлов айтема (там же формат и длина).
 *
 * Никакой авторизации и обхода нет: скачиваются только те файлы, которые
 * Archive отдаёт публично. Лицензия айтема приходит в `licenseurl`/`rights`
 * и пробрасывается в [Track.extra], решение о легальности принимает пользователь.
 *
 * Айтем Archive — это часто целый альбом, поэтому из одного айтема берётся
 * несколько аудиофайлов (до [MAX_TRACKS_PER_ITEM]), а не один.
 *
 * Вежливость к публичному API: не больше [MAX_CONCURRENT_METADATA] параллельных
 * запросов метаданных, не больше [MAX_METADATA_LOOKUPS] айтемов на один поиск
 * и кэш метаданных айтема на процесс.
 */
class ArchiveDiscoverSource(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : DiscoverSource {

    override val source: Source = Source.ARCHIVE

    /** Метаданные айтема тяжёлые: один identifier не должен запрашиваться дважды. */
    private val itemCache = ConcurrentHashMap<String, ArchiveItem>()

    /** Ограничиваем параллелизм запросов /metadata, чтобы не хаммерить archive.org. */
    private val metadataSemaphore = Semaphore(MAX_CONCURRENT_METADATA)

    override fun isAvailable(): Boolean = true

    /** Ключ не нужен: каталог открыт. */
    override fun setupHint(): String? = null

    override suspend fun search(query: String, limit: Int): List<Track> {
        if (query.isBlank() || limit <= 0) return emptyList()
        return runCatching {
            val target = limit.coerceIn(1, MAX_RESULTS)
            // Сначала ищем только в музыкальных коллекциях; если пусто — по всему аудио.
            var docs = searchDocuments(query.trim(), MAX_METADATA_LOOKUPS, musicOnly = true)
            if (docs.isEmpty()) {
                docs = searchDocuments(query.trim(), MAX_METADATA_LOOKUPS, musicOnly = false)
            }
            if (docs.isEmpty()) return emptyList()

            val result = ArrayList<Track>(target)
            // Обрабатываем пачками и останавливаемся, как только набрали limit:
            // часть айтемов окажется без аудиофайлов или закрытой.
            docs.chunked(MAX_CONCURRENT_METADATA * 2).forEach { chunk ->
                if (result.size < target) {
                    val loaded = coroutineScope {
                        chunk.map { doc ->
                            val identifier = doc.text("identifier")
                            async(Dispatchers.IO) {
                                if (identifier == null) {
                                    null
                                } else {
                                    metadataSemaphore.withPermit {
                                        // Один битый айтем не должен ронять весь поиск.
                                        runCatching { doc to loadItem(identifier) }.getOrNull()
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                    for (pair in loaded) {
                        if (result.size >= target) break
                        val (doc, item) = pair ?: continue
                        if (item == null || item.restricted) continue
                        result += toTracks(doc, item, target - result.size)
                    }
                }
            }
            result
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- поиск

    private suspend fun searchDocuments(
        query: String,
        limit: Int,
        musicOnly: Boolean,
    ): List<JsonObject> {
        val collections = if (musicOnly) {
            " AND (collection:(netlabels) OR collection:(audio_music) OR collection:(opensource_audio))"
        } else {
            ""
        }
        // q кодируем целиком, включая AND/mediatype: archive.org декодирует стандартно.
        val q = encode("$query AND mediatype:(audio)$collections")
        val url = "https://archive.org/advancedsearch.php" +
            "?q=$q" +
            "&fl%5B%5D=identifier" +
            "&fl%5B%5D=title" +
            "&fl%5B%5D=creator" +
            "&fl%5B%5D=year" +
            "&fl%5B%5D=licenseurl" +
            "&rows=$limit&page=1&output=json"
        val root = Http.getJson(url, ARCHIVE_HEADERS) as? JsonObject ?: return emptyList()
        val response = root["response"] as? JsonObject ?: return emptyList()
        val docs = response["docs"] as? JsonArray ?: return emptyList()
        return docs.mapNotNull { it as? JsonObject }
    }

    /** Метаданные айтема: поля item'а и список аудиофайлов. Кэш на процесс. */
    private suspend fun loadItem(identifier: String): ArchiveItem? {
        itemCache[identifier]?.let { return it }
        val url = "https://archive.org/metadata/${encode(identifier)}"
        val root = runCatching {
            Http.getJson(url, ARCHIVE_HEADERS) as? JsonObject
        }.getOrNull() ?: return null
        val metadata = root["metadata"] as? JsonObject
        val files = root["files"] as? JsonArray
        val item = ArchiveItem(
            identifier = identifier,
            title = metadata?.text("title"),
            creator = metadata?.text("creator"),
            year = metadata?.text("year") ?: metadata?.text("date")?.take(4),
            licenseUrl = metadata?.text("licenseurl"),
            rights = metadata?.text("rights"),
            restricted = metadata?.text("access-restricted-item")
                ?.equals("true", ignoreCase = true) == true,
            audioFiles = files?.let(::pickAudioFiles).orEmpty(),
        )
        itemCache[identifier] = item
        return item
    }

    private fun toTracks(doc: JsonObject, item: ArchiveItem, maxTracks: Int): List<Track> {
        if (item.audioFiles.isEmpty() || maxTracks <= 0) return emptyList()

        val docTitle = doc.text("title")
        val docCreator = doc.text("creator")
        val album = docTitle ?: item.title
        val creator = docCreator ?: item.creator
        val licenseUrl = doc.text("licenseurl") ?: item.licenseUrl
        val year = doc.text("year")?.take(4) ?: item.year

        val encodedIdentifier = encode(item.identifier)
        val detailsUrl = "https://archive.org/details/$encodedIdentifier"

        return item.audioFiles.take(maxTracks.coerceAtLeast(1)).map { file ->
            val title = file.title
                ?: docTitle
                ?: item.title
                ?: file.displayName
            val extras = buildMap {
                put("fileName", suggestedFileName(creator, title, file.name))
                put("license", shortLicense(licenseUrl ?: item.rights) ?: DEFAULT_LICENSE_HINT)
                put("licenseUrl", licenseUrl ?: detailsUrl)
                put("identifier", item.identifier)
                if (!year.isNullOrBlank()) put("year", year)
            }

            Track(
                id = "archive:${item.identifier}:${file.name}",
                title = title,
                artist = creator?.takeIf { it.isNotBlank() } ?: "Internet Archive",
                album = album?.takeIf { it.isNotBlank() },
                durationMs = parseDuration(file.length),
                uri = "https://archive.org/download/$encodedIdentifier/${encodePath(file.name)}",
                artworkUrl = "https://archive.org/services/img/$encodedIdentifier",
                source = Source.ARCHIVE,
                playable = true,
                downloadAllowed = true,
                deepLink = detailsUrl,
                extra = extras,
            )
        }
    }

    // ---------------------------------------------------------------- файлы

    /**
     * Все пригодные аудиофайлы айтема, лучший формат — первым.
     * `private: true` и служебные файлы отбрасываются.
     */
    private fun pickAudioFiles(files: JsonArray): List<ArchiveFile> {
        val usable = files.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.text("name") ?: return@mapNotNull null
            if (isExcluded(name)) return@mapNotNull null
            // `private: true` — файл закрыт настройками айтема (lending/restricted).
            if (obj.text("private")?.equals("true", ignoreCase = true) == true) {
                return@mapNotNull null
            }
            val format = obj.text("format")
            val extension = name.substringAfterLast('.', "").lowercase()
            val isAudio = formatRank(format) != Int.MAX_VALUE || extension in AUDIO_EXTENSIONS
            if (!isAudio) return@mapNotNull null
            ArchiveFile(
                name = name,
                title = obj.text("title"),
                format = format,
                length = obj.text("length"),
            )
        }
        return usable
            .distinctBy { it.name }
            .sortedBy { formatRank(it.format) }
            .take(MAX_TRACKS_PER_ITEM)
    }

    private fun formatRank(format: String?): Int {
        if (format.isNullOrBlank()) return Int.MAX_VALUE
        val index = PREFERRED_FORMATS.indexOfFirst { it.equals(format.trim(), ignoreCase = true) }
        return if (index >= 0) index else Int.MAX_VALUE
    }

    private fun isExcluded(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith("_files.xml") ||
            lower.endsWith("_meta.xml") ||
            lower.endsWith(".torrent") ||
            lower.endsWith(".png") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg")
    }

    // ---------------------------------------------------------------- форматирование

    /** `length` у Archive бывает секундами ("245.83") или таймкодом ("3:45", "1:02:03"). */
    private fun parseDuration(raw: String?): Long {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return 0L
        if (value.contains(':')) {
            val parts = value.split(':').mapNotNull { it.trim().toDoubleOrNull() }
            if (parts.isEmpty()) return 0L
            var seconds = 0.0
            parts.forEach { seconds = seconds * 60.0 + it }
            return (seconds * 1000.0).toLong().coerceAtLeast(0L)
        }
        return ((value.toDoubleOrNull() ?: 0.0) * 1000.0).toLong().coerceAtLeast(0L)
    }

    /** Короткое человекочитаемое имя лицензии из URL/текста. */
    private fun shortLicense(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        val lower = value.lowercase()
        if (lower.contains("publicdomain") ||
            lower.contains("public domain") ||
            lower.contains("/zero/1.0") ||
            lower.contains("mark/1.0")
        ) {
            return "Public Domain"
        }
        if (lower.contains("creativecommons.org")) {
            if (lower.contains("/licenses/")) {
                val parts = lower.substringAfter("/licenses/")
                    .split('/')
                    .filter { it.isNotBlank() }
                val code = parts.firstOrNull()?.uppercase() ?: return "Creative Commons"
                val version = parts.getOrNull(1).orEmpty()
                return listOf("CC", code, version)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            }
            return "Creative Commons"
        }
        return value.take(MAX_LICENSE_LENGTH)
    }

    private fun suggestedFileName(artist: String?, title: String, rawName: String): String {
        val extension = rawName.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in AUDIO_EXTENSIONS }
            ?: "mp3"
        val performer = artist?.takeIf { it.isNotBlank() } ?: "Internet Archive"
        return "${safePart(performer)} - ${safePart(title)}.$extension"
    }

    private fun safePart(value: String): String =
        value.replace(ILLEGAL_CHARS, "_")
            .trim()
            .trim('.')
            .take(MAX_FILE_PART)
            .ifBlank { "track" }

    // ---------------------------------------------------------------- утилиты

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Кодирует каждый сегмент пути отдельно, сохраняя разделители `/`. */
    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { encode(it) }

    /** Поле из JSON может быть строкой, числом или массивом (creator/licenseurl в Archive). */
    private fun JsonObject.text(key: String): String? = when (val element = this[key]) {
        is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        is JsonArray -> element.firstNotNullOfOrNull { child ->
            when (child) {
                is JsonPrimitive -> child.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                is JsonObject -> (child["name"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                else -> null
            }
        }
        is JsonObject -> (element["name"] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        else -> null
    }

    private data class ArchiveItem(
        val identifier: String,
        val title: String?,
        val creator: String?,
        val year: String?,
        val licenseUrl: String?,
        val rights: String?,
        val restricted: Boolean,
        val audioFiles: List<ArchiveFile>,
    )

    private data class ArchiveFile(
        val name: String,
        val title: String?,
        val format: String?,
        val length: String?,
    ) {
        /** Имя без каталога и расширения — запасной вариант заголовка. */
        val displayName: String
            get() = name.substringAfterLast('/').substringBeforeLast('.').ifBlank { name }
    }

    private companion object {
        const val MAX_RESULTS = 50
        const val MAX_METADATA_LOOKUPS = 24
        const val MAX_CONCURRENT_METADATA = 4
        const val MAX_TRACKS_PER_ITEM = 6
        const val MAX_LICENSE_LENGTH = 120
        const val MAX_FILE_PART = 60
        const val DEFAULT_LICENSE_HINT = "не указана"

        val ARCHIVE_HEADERS = mapOf("User-Agent" to Http.USER_AGENT)

        /** Порядок важен: первый подходящий формат считается лучшим. */
        val PREFERRED_FORMATS = listOf(
            "VBR MP3",
            "MP3",
            "192Kbps MP3",
            "128Kbps MP3",
            "MPEG-4 Audio",
            "Ogg Vorbis",
            "Flac",
            "24bit Flac",
            "64Kbps MP3",
            "32Kbps MP3",
            "AIFF",
            "Apple Lossless Audio",
            "WAVE",
        )

        val AUDIO_EXTENSIONS = setOf(
            "mp3", "ogg", "oga", "flac", "wav", "m4a", "aac", "opus", "wma", "aiff",
        )

        val ILLEGAL_CHARS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
    }
}

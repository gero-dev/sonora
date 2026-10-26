package app.sonora.data.discover

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.net.Http
import app.sonora.core.settings.SettingsStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/**
 * Jamendo API v3.0 — каталог Creative Commons, нужен пользовательский client_id.
 *
 * Endpoint: `GET https://api.jamendo.com/v3.0/tracks/` с `audioformat=mp32`.
 * API само отдаёт `audiodownload` для свободных треков: это официальная ссылка,
 * которую можно скачивать. Никаких обходов, скрейпинга и приватных endpoint'ов.
 *
 * Без client_id каталог недоступен ([isAvailable] == false), UI показывает
 * [setupHint]. Ошибки (401/403 из-за неверного ключа, сеть, битый JSON) не
 * пробрасываются: возвращается пустой список.
 */
class JamendoDiscoverSource(
    private val settings: SettingsStore,
) : DiscoverSource {

    override val source: Source = Source.JAMENDO

    private fun clientId(): String = settings.current.jamendoClientId.trim()

    override fun isAvailable(): Boolean = clientId().isNotBlank()

    override fun setupHint(): String? = if (isAvailable()) {
        null
    } else {
        "Вставьте Jamendo client_id в настройках: бесплатный ключ выдаётся " +
            "на devportal.jamendo.com (каталог Creative Commons)."
    }

    override suspend fun search(query: String, limit: Int): List<Track> {
        val key = clientId()
        if (key.isBlank() || query.isBlank() || limit <= 0) return emptyList()
        return runCatching {
            val max = limit.coerceIn(1, MAX_RESULTS)
            val url = "https://api.jamendo.com/v3.0/tracks/" +
                "?client_id=${encode(key)}" +
                "&format=json&limit=$max" +
                "&search=${encode(query.trim())}" +
                "&audioformat=mp32&include=musicinfo"
            val root = Http.getJson(url) as? JsonObject ?: return@runCatching emptyList()
            val results = root["results"] as? JsonArray ?: return@runCatching emptyList()
            results
                .mapNotNull { element -> (element as? JsonObject)?.let(::toTrack) }
                .distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    /**
     * Маппинг результата Jamendo в [Track]. Трек без https-аудио или с
     * запретом скачивания (`audiodownload_allowed = false`) пропускаем:
     * discover-источник отдаёт только то, что автор разрешил скачивать.
     */
    private fun toTrack(obj: JsonObject): Track? {
        val id = obj.text("id") ?: return null
        val title = obj.text("name") ?: return null
        val artist = obj.text("artist_name") ?: DEFAULT_ARTIST
        val downloadAllowed = (obj["audiodownload_allowed"] as? JsonPrimitive)?.booleanOrNull
        if (downloadAllowed == false) return null
        val audio = obj.text("audio")
        val download = obj.text("audiodownload")
        val uri = listOf(download, audio)
            .filterNotNull()
            .firstOrNull { it.startsWith("https://", ignoreCase = true) }
            ?: return null
        val album = obj.text("album_name")
        val licenseUrl = obj.text("license_ccurl") ?: JAMENDO_HOME
        val tags = tagsOf(obj)

        val extras = buildMap {
            put("fileName", "${safePart(artist)} - ${safePart(title)}.mp3")
            put("license", "Creative Commons")
            put("licenseUrl", licenseUrl)
            if (!audio.isNullOrBlank()) put("streamUrl", audio)
            if (!tags.isNullOrBlank()) put("tags", tags)
        }

        return Track(
            id = "jamendo:$id",
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs(obj),
            uri = uri,
            artworkUrl = obj.text("image") ?: obj.text("album_image"),
            source = Source.JAMENDO,
            playable = true,
            downloadAllowed = true,
            deepLink = "https://www.jamendo.com/track/${encode(id)}",
            extra = extras,
        )
    }

    /** `duration` в API — секунды; бывает числом или строкой. */
    private fun durationMs(obj: JsonObject): Long {
        val primitive = obj["duration"] as? JsonPrimitive ?: return 0L
        val seconds = primitive.longOrNull ?: primitive.doubleOrNull?.toLong() ?: 0L
        return (seconds * 1000L).coerceAtLeast(0L)
    }

    /** Жанры из `musicinfo.tags.genres` — приятный бонус для карточки трека. */
    private fun tagsOf(obj: JsonObject): String? {
        val musicInfo = obj["musicinfo"] as? JsonObject ?: return null
        val tags = musicInfo["tags"] as? JsonObject ?: return null
        val genres = tags["genres"] as? JsonArray ?: return null
        return genres
            .mapNotNull { element -> (element as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotBlank() }
            .take(MAX_TAGS)
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
    }

    private fun safePart(value: String): String =
        value.replace(ILLEGAL_CHARS, "_")
            .trim()
            .trim('.')
            .take(MAX_FILE_PART)
            .ifBlank { "track" }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun JsonObject.text(key: String): String? = when (val element = this[key]) {
        is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        is JsonArray -> element.firstNotNullOfOrNull { child ->
            (child as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        }
        else -> null
    }

    private companion object {
        const val MAX_RESULTS = 100
        const val MAX_TAGS = 5
        const val MAX_FILE_PART = 60
        const val DEFAULT_ARTIST = "Неизвестный исполнитель"
        const val JAMENDO_HOME = "https://www.jamendo.com/"

        val ILLEGAL_CHARS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
    }
}

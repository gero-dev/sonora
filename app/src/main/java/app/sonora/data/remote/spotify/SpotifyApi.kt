package app.sonora.data.remote.spotify

import app.sonora.core.model.Playlist
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.net.Http
import app.sonora.core.net.HttpException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/** Профиль Spotify-пользователя — только то, что нужно приложению. */
data class SpotifyUser(
    val name: String,
    val id: String,
    val email: String? = null,
)

/**
 * Официальный Spotify Web API (https://developer.spotify.com/documentation/web-api).
 *
 * ПРАВИЛА, ЗАЛОЖЕННЫЕ В КОД:
 *  - никакого скрапинга и внутренних (web-player) API — только api.spotify.com;
 *  - сторонним приложениям Spotify не отдаёт полные треки, поэтому единственный
 *    играбельный аудиофайл — 30-секундное превью `preview_url`;
 *  - если превью нет, трек помечается playable = false и открывается в Spotify
 *    по deep link;
 *  - скачивание звука из Spotify не поддерживается и не реализуется
 *    (downloadAllowed всегда false).
 */
class SpotifyApi {

    /**
     * Текущий пользователь. Бросает HttpException при ошибке сети/токена;
     * вызывающий код обязан обработать исключение.
     */
    suspend fun me(token: String): SpotifyUser {
        val root = Http.getJson(apiUrl("/me"), auth(token)).jsonObject
        val id = root.str("id").orEmpty()
        val name = root.str("display_name")?.takeIf { it.isNotBlank() }
            ?: id.takeIf { it.isNotBlank() }
            ?: "Spotify"
        return SpotifyUser(name = name, id = id, email = root.str("email"))
    }

    /**
     * Плейлисты пользователя. Страница 50 (как велит API), идём по `next`,
     * пока он есть; общий потолок — 300 плейлистов.
     */
    suspend fun playlists(token: String, limit: Int = 50): List<Playlist> {
        val pageSize = limit.coerceIn(1, MAX_PAGE)
        val cap = MAX_PLAYLISTS
        val out = LinkedHashMap<String, Playlist>()
        var offset = 0
        var pages = 0
        while (out.size < cap && pages < MAX_PAGES) {
            pages++
            val root = Http.getJson(
                apiUrl(
                    "/me/playlists",
                    mapOf("limit" to pageSize.toString(), "offset" to offset.toString()),
                ),
                auth(token),
            ).jsonObject
            val items = root.arr("items") ?: break
            for (element in items) {
                val obj = element as? JsonObject ?: continue
                mapPlaylist(obj)?.let { playlist -> out.getOrPut(playlist.id) { playlist } }
            }
            if (items.isEmpty() || root.str("next").isNullOrBlank()) break
            offset += pageSize
        }
        return out.values.toList()
    }

    /**
     * Треки плейлиста. Основной эндпоинт `/playlists/{id}/tracks` иногда отвечает
     * 403 на редакционные (algorithmic) плейлисты — тогда один раз пробуем
     * получить объект плейлиста и читать `tracks.items`. Если не вышло — пустой список.
     */
    suspend fun playlistTracks(
        token: String,
        playlistId: String,
        market: String = "from_token",
    ): List<Track> {
        return runCatching {
            tracksViaItemsEndpoint(token, playlistId, market)
        }.getOrElse { error ->
            if (error is HttpException && error.code == 403) {
                runCatching { tracksViaPlaylistObject(token, playlistId, market) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
        }
    }

    /** «Мне нравится»: /me/tracks, страницы по 50, потолок 500. */
    suspend fun likedTracks(token: String): List<Track> {
        val out = LinkedHashMap<String, Track>()
        var offset = 0
        var pages = 0
        while (out.size < MAX_LIKED && pages < MAX_PAGES) {
            pages++
            val root = Http.getJson(
                apiUrl("/me/tracks", mapOf("limit" to "50", "offset" to offset.toString())),
                auth(token),
            ).jsonObject
            val items = root.arr("items") ?: break
            for (element in items) {
                val item = element as? JsonObject ?: continue
                val trackElement = item["track"] as? JsonObject ?: continue
                mapTrack(trackElement)?.let { track -> out.getOrPut(track.id) { track } }
            }
            if (items.isEmpty() || root.str("next").isNullOrBlank()) break
            offset += 50
        }
        return out.values.toList()
    }

    /** Поиск треков. limit ограничивается официальным максимумом Spotify (50). */
    suspend fun search(token: String, query: String, limit: Int = 30): List<Track> {
        if (query.isBlank()) return emptyList()
        val root = Http.getJson(
            apiUrl(
                "/search",
                mapOf(
                    "q" to query,
                    "type" to "track",
                    "limit" to limit.coerceIn(1, MAX_PAGE).toString(),
                    "market" to "from_token",
                ),
            ),
            auth(token),
        ).jsonObject
        val items = root.obj("tracks")?.arr("items") ?: return emptyList()
        return items.mapNotNull { element -> (element as? JsonObject)?.let(::mapTrack) }
            .distinctBy { it.id }
    }

    // ---------------------------------------------------------------- внутреннее

    private suspend fun tracksViaItemsEndpoint(
        token: String,
        playlistId: String,
        market: String,
    ): List<Track> {
        val out = LinkedHashMap<String, Track>()
        var offset = 0
        var pages = 0
        while (out.size < MAX_PLAYLIST_TRACKS && pages < MAX_PAGES) {
            pages++
            val root = Http.getJson(
                apiUrl(
                    "/playlists/${pathSegment(playlistId)}/tracks",
                    mapOf(
                        "limit" to "100",
                        "offset" to offset.toString(),
                        "market" to market,
                    ),
                ),
                auth(token),
            ).jsonObject
            val items = root.arr("items") ?: break
            appendPlaylistItems(items, out)
            if (items.isEmpty() || root.str("next").isNullOrBlank()) break
            offset += 100
        }
        return out.values.toList()
    }

    private suspend fun tracksViaPlaylistObject(
        token: String,
        playlistId: String,
        market: String,
    ): List<Track> {
        val out = LinkedHashMap<String, Track>()
        var url = apiUrl("/playlists/${pathSegment(playlistId)}", mapOf("market" to market))
        var pages = 0
        while (out.size < MAX_PLAYLIST_TRACKS && pages < MAX_PAGES) {
            pages++
            val root = Http.getJson(url, auth(token)).jsonObject
            val tracks = root.obj("tracks") ?: break
            val items = tracks.arr("items") ?: break
            appendPlaylistItems(items, out)
            val next = tracks.str("next")
            if (items.isEmpty() || next.isNullOrBlank()) break
            // Spotify отдаёт next абсолютным URL; market там обычно уже есть.
            url = if (next.contains("market=")) next else "$next&market=$market"
        }
        return out.values.toList()
    }

    private fun appendPlaylistItems(items: JsonArray, out: MutableMap<String, Track>) {
        for (element in items) {
            val item = element as? JsonObject ?: continue
            val trackElement = item["track"] as? JsonObject ?: continue
            mapTrack(trackElement)?.let { track -> out.getOrPut(track.id) { track } }
        }
    }

    /** JSON-объект трека Spotify -> доменная модель. Nullable-поля безопасны. */
    private fun mapTrack(obj: JsonObject): Track? {
        val id = obj.str("id") ?: return null
        val album = obj.obj("album")
        val artists = obj.arr("artists")
            ?.mapNotNull { element -> (element as? JsonObject)?.str("name") }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val preview = obj.str("preview_url")?.takeIf { it.isNotBlank() }
        return Track(
            id = "spotify:$id",
            title = obj.str("name")?.takeIf { it.isNotBlank() } ?: "Без названия",
            artist = artists.joinToString(", ").ifBlank { "Неизвестный исполнитель" },
            album = album?.str("name"),
            durationMs = obj.long("duration_ms") ?: 0L,
            uri = preview,
            artworkUrl = album?.arr("images")?.firstOrNull().asObj()?.str("url"),
            source = Source.SPOTIFY,
            playable = preview != null,
            downloadAllowed = false,
            deepLink = "https://open.spotify.com/track/$id",
        )
    }

    private fun mapPlaylist(obj: JsonObject): Playlist? {
        val id = obj.str("id") ?: return null
        return Playlist(
            id = "spotify:$id",
            name = obj.str("name")?.takeIf { it.isNotBlank() } ?: "Плейлист Spotify",
            description = obj.str("description")?.takeIf { it.isNotBlank() },
            artworkUrl = obj.arr("images")?.firstOrNull().asObj()?.str("url"),
            source = Source.SPOTIFY,
            remoteId = id,
            editable = false,
            ownerName = obj.obj("owner")?.str("display_name"),
        )
    }

    private fun auth(token: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/json",
    )

    private fun apiUrl(path: String, params: Map<String, String> = emptyMap()): String {
        if (params.isEmpty()) return API_BASE + path
        val query = params.entries.joinToString("&") { (key, value) ->
            "${enc(key)}=${enc(value)}"
        }
        return "$API_BASE$path?$query"
    }

    private fun pathSegment(value: String): String = enc(value)

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

    private companion object {
        const val API_BASE = "https://api.spotify.com/v1"
        const val MAX_PAGE = 50
        const val MAX_PLAYLISTS = 300
        const val MAX_PLAYLIST_TRACKS = 2000
        const val MAX_LIKED = 500
        const val MAX_PAGES = 40
    }
}

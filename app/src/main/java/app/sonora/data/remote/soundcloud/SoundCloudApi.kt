package app.sonora.data.remote.soundcloud

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.net.Http
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/**
 * Официальный SoundCloud API (api.soundcloud.com) — ТОЛЬКО метаданные.
 *
 * По API Terms of Use (30.03.2024) приложению запрещено: сохранять файлы и
 * офлайн-кэшировать контент дольше сессии, обходить Go+, а также объединять
 * воспроизведение SoundCloud с контентом других сервисов в одном плеере.
 * Новые API-ключи требуют Artist Pro и confidential OAuth-клиента с
 * client_secret, который нельзя держать в APK.
 *
 * Поэтому источник честно «каталожный»:
 *  - uri всегда null, playable всегда false, downloadAllowed всегда false;
 *  - поля stream_url/download_url не читаются вообще, client_id к медиа-ссылкам
 *    не добавляется;
 *  - единственные действия — поиск, resolve ссылки, чтение публичных
 *    плейлистов и deep link в приложение SoundCloud (metadata + attribution).
 */
class SoundCloudApi {

    /** resolve ссылки трека/плейлиста. Бросает HttpException при ошибке API. */
    suspend fun resolve(clientId: String, url: String): JsonObject? {
        if (clientId.isBlank() || url.isBlank()) return null
        return Http.getJson(
            apiUrl("/resolve", mapOf("url" to url, "client_id" to clientId)),
        ) as? JsonObject
    }

    /** Публичный плейлист по id (для импорта по ссылке). */
    suspend fun playlist(clientId: String, playlistId: String): JsonObject? {
        if (clientId.isBlank() || playlistId.isBlank()) return null
        return Http.getJson(
            apiUrl(
                "/playlists/${pathSegment(playlistId)}",
                mapOf("client_id" to clientId),
            ),
        ) as? JsonObject
    }

    /** Поиск треков по каталогу. */
    suspend fun searchTracks(
        clientId: String,
        query: String,
        limit: Int = 30,
    ): List<Track> {
        if (clientId.isBlank() || query.isBlank()) return emptyList()
        val max = limit.coerceIn(1, MAX_SEARCH_RESULTS)
        val root = Http.getJson(
            apiUrl(
                "/search/tracks",
                mapOf(
                    "q" to query,
                    "limit" to max.toString(),
                    "client_id" to clientId,
                ),
            ),
        ) as? JsonObject ?: return emptyList()
        val collection = root.arr("collection") ?: return emptyList()
        return collection.mapNotNull { element -> (element as? JsonObject)?.let(::toTrack) }
            .distinctBy { it.id }
    }

    /**
     * resolve ссылки: трек -> одна модель, плейлист -> GET /playlists/{id} и его
     * треки. Неизвестный тип (пользователь, альбом) -> пустой список.
     */
    suspend fun importUrl(clientId: String, url: String): List<Track> {
        val resolved = resolve(clientId, url) ?: return emptyList()
        return when (resolved.str("kind")) {
            "track" -> listOfNotNull(toTrack(resolved))
            "playlist" -> {
                val playlistId = resolved.str("id") ?: return emptyList()
                val full = runCatching { playlist(clientId, playlistId) }.getOrNull() ?: resolved
                tracksFromPlaylist(full)
            }
            else -> {
                // На всякий случай: если kind не пришёл, но есть массив tracks.
                if (resolved.arr("tracks") != null) tracksFromPlaylist(resolved) else emptyList()
            }
        }
    }

    /** Треки из JSON-объекта плейлиста. Только метаданные, без uri. */
    fun tracksFromPlaylist(playlist: JsonObject): List<Track> {
        val tracks = playlist.arr("tracks") ?: return emptyList()
        return tracks.mapNotNull { element -> (element as? JsonObject)?.let(::toTrack) }
            .distinctBy { it.id }
    }

    /**
     * Маппинг записи трека SoundCloud -> Track. uri/playable/downloadAllowed
     * жёстко выключены: воспроизведение и скачивание через API запрещены ToU.
     */
    fun toTrack(obj: JsonObject): Track? {
        val kind = obj.str("kind")
        if (kind != null && kind != "track") return null
        val id = obj.str("id") ?: return null
        val user = obj.obj("user")
        val rawArtwork = obj.str("artwork_url") ?: user?.str("avatar_url")
        val artwork = rawArtwork?.replace("-large", "-t500x500")
        return Track(
            id = "soundcloud:$id",
            title = obj.str("title")?.takeIf { it.isNotBlank() } ?: "Без названия",
            artist = user?.str("username")?.takeIf { it.isNotBlank() }
                ?: "Неизвестный исполнитель",
            durationMs = obj.long("duration") ?: 0L,
            uri = null,
            artworkUrl = artwork,
            source = Source.SOUNDCLOUD,
            playable = false,
            downloadAllowed = false,
            deepLink = obj.str("permalink_url")?.takeIf { it.isNotBlank() },
        )
    }

    // ---------------------------------------------------------------- внутреннее

    private fun apiUrl(path: String, params: Map<String, String>): String {
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

    private companion object {
        const val API_BASE = "https://api.soundcloud.com"
        const val MAX_SEARCH_RESULTS = 50
    }
}

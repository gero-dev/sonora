package app.sonora.data.remote.youtube.innertube

import app.sonora.core.model.Playlist
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.data.metadata.ArtistNames
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/** Плейлист, разобранный из InnerTube. */
data class RemotePlaylist(
    val id: String,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val tracks: List<Track>,
)

/** Данные активного Google-аккаунта из account_menu. */
data class GoogleAccountInfo(
    val name: String,
    val email: String?,
    val channelHandle: String?,
    val avatarUrl: String?,
)

/** Прямая ссылка на аудиопоток YouTube. */
internal data class StreamInfo(
    val videoId: String,
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val itag: Int,
    val expiresAtMs: Long,
    val headers: Map<String, String>,
    /** Размер файла, если YouTube его сообщил. Нужен для bounded-range загрузки. */
    val contentLength: Long? = null,
    /** Некоторые клиенты отдают поток только кусками с ограниченным Range. */
    val requireBoundedRange: Boolean = false,
    val rangeChunkSizeBytes: Long = 0L,
    val useRangeChunks: Boolean = false,
) {
    val needsRangeRequests: Boolean
        get() = (requireBoundedRange || useRangeChunks) && rangeChunkSizeBytes > 0L
}

/**
 * Функциональность YouTube Music, которая раньше жила на официальном Data API v3,
 * переписанная на InnerTube (те же endpoint'ы и клиенты, что в Metrolist):
 * поиск, плейлисты, очередь «дальше», тексты и прямые аудиопотоки.
 */
internal class InnerTubeApi {

    // ------------------------------------------------------------------ поиск

    /**
     * Поиск песен. [params] — фильтр InnerTube; по умолчанию только песни,
     * чтобы в выдачу не попадали альбомы и исполнители без videoId.
     */
    suspend fun search(
        query: String,
        limit: Int = 30,
        params: String = FILTER_SONGS,
    ): List<Track> {
        if (query.isBlank()) return emptyList()
        val response = InnerTube.call(
            endpoint = "search",
            payload = buildJsonObject {
                put("query", query)
                if (params.isNotBlank()) put("params", params)
            },
        )
        val out = LinkedHashMap<String, Track>()
        for (renderer in response.deepObjects("musicResponsiveListItemRenderer")) {
            val track = parseTrackRenderer(renderer) ?: continue
            out.putIfAbsent(track.id, track)
            if (out.size >= limit) break
        }
        return out.values.toList()
    }

    /** Подсказки поиска: пригодятся для строки запроса. */
    suspend fun suggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val response = InnerTube.call(
            endpoint = "music/get_search_suggestions",
            payload = buildJsonObject { put("input", query) },
        )
        return response.deepObjects("searchSuggestionRenderer")
            .mapNotNull { it.obj("suggestion")?.text() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(10)
            .toList()
    }

    // -------------------------------------------------------------- плейлисты

    /**
     * Плейлист по id (`PL…`, `OLAK5uy_…`, `VL…`) или ссылке.
     * Подгружает продолжения, пока не наберётся [cap] треков.
     */
    suspend fun playlist(playlistId: String, cap: Int = 200): RemotePlaylist {
        val id = normalizePlaylistId(playlistId)
            ?: throw IllegalArgumentException("Не удалось распознать ссылку на плейлист YouTube.")
        val browseId = if (id.startsWith("VL")) id else "VL$id"

        var response = InnerTube.call(
            endpoint = "browse",
            payload = buildJsonObject { put("browseId", browseId) },
        )

        val header = response.deepObjects("musicResponsiveHeaderRenderer").firstOrNull()
            ?: response.deepObjects("musicDetailHeaderRenderer").firstOrNull()
        val title = header?.get("title").text()?.let(::decodeHtml)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Плейлист YouTube Music"
        val subtitle = header?.get("subtitle").text()?.let(::decodeHtml)?.trim()
        val artwork = header?.get("thumbnail").firstThumbnailUrl()

        val tracks = LinkedHashMap<String, Track>()
        var pages = 0
        while (pages < MAX_PAGES) {
            pages++
            for (renderer in response.deepObjects("musicResponsiveListItemRenderer")) {
                val track = parseTrackRenderer(renderer) ?: continue
                tracks.putIfAbsent(track.id, track)
                if (tracks.size >= cap) break
            }
            if (tracks.size >= cap) break
            val token = continuationToken(response) ?: break
            response = InnerTube.call(
                endpoint = "browse",
                payload = buildJsonObject { put("continuation", token) },
            )
        }

        return RemotePlaylist(
            id = id,
            title = title,
            subtitle = subtitle,
            artworkUrl = artwork,
            tracks = tracks.values.toList(),
        )
    }

    // ------------------------------------------------------- очередь и тексты

    /** «Далее» из watch-очереди YouTube Music — основа автоочереди. */
    suspend fun related(videoId: String, limit: Int = 25): List<Track> {
        if (videoId.isBlank()) return emptyList()
        val response = InnerTube.call(
            endpoint = "next",
            payload = buildJsonObject { put("videoId", videoId) },
        )
        val out = LinkedHashMap<String, Track>()
        collectQueueItems(response, videoId, limit, out)
        if (out.size < limit) {
            // YouTube Music отдаёт продолжение как automix-плейлист:
            // отдельный next по playlistId, а не список в первом ответе.
            val automix = response.deepObjects("automixPlaylistVideoRenderer")
                .firstOrNull()
                ?.obj("navigationEndpoint")
                ?.obj("watchPlaylistEndpoint")
            val playlistId = automix?.str("playlistId")
            val params = automix?.str("params")
            if (!playlistId.isNullOrBlank()) {
                val continued = InnerTube.call(
                    endpoint = "next",
                    payload = buildJsonObject {
                        put("playlistId", playlistId)
                        if (!params.isNullOrBlank()) put("params", params)
                    },
                )
                collectQueueItems(continued, videoId, limit, out)
            }
        }
        return out.values.toList()
    }

    private fun collectQueueItems(
        response: JsonObject,
        seedVideoId: String,
        limit: Int,
        out: LinkedHashMap<String, Track>,
    ) {
        for (panel in response.deepObjects("playlistPanelVideoRenderer")) {
            val id = panel.str("videoId") ?: continue
            if (id == seedVideoId) continue
            val title = decodeHtml(panel.get("title").text()).trim()
            if (title.isBlank() || title in PLACEHOLDER_TITLES) continue
            val byline = panel.get("longBylineText").text()
                ?: panel.get("shortBylineText").text()
            val artist = decodeHtml(byline).substringBefore("•").trim()
                .ifBlank { "YouTube Music" }
            val duration = parseClockDuration(panel.get("lengthText").text())
            val artwork = panel.get("thumbnail").firstThumbnailUrl()
            out.putIfAbsent(
                "youtube:$id",
                youtubeTrack(
                    videoId = id,
                    title = title,
                    artist = artist,
                    album = null,
                    durationMs = duration,
                    artworkUrl = artwork,
                ),
            )
            if (out.size >= limit) return
        }
    }

    /** Текст песни из вкладки «Lyrics» (InnerTube browse). */
    suspend fun lyrics(videoId: String): String? {
        if (videoId.isBlank()) return null
        val next = InnerTube.call(
            endpoint = "next",
            payload = buildJsonObject { put("videoId", videoId) },
        )
        val lyricsTab = next.deepObjects("tabRenderer").firstOrNull { tab ->
            val pageType = tab.obj("endpoint")?.obj("browseEndpoint")
                ?.obj("browseEndpointContextSupportedConfigs")
                ?.obj("browseEndpointContextMusicConfig")
                ?.str("pageType")
            pageType == "MUSIC_PAGE_TYPE_TRACK_LYRICS" ||
                tab.obj("title").text()?.contains("Lyrics", ignoreCase = true) == true
        } ?: return null
        val browse = lyricsTab.obj("endpoint")?.obj("browseEndpoint") ?: return null
        val browseId = browse.str("browseId") ?: return null
        val params = browse.str("params")
        val response = InnerTube.call(
            endpoint = "browse",
            payload = buildJsonObject {
                put("browseId", browseId)
                if (!params.isNullOrBlank()) put("params", params)
            },
        )
        val shelf = response.deepObjects("musicDescriptionShelfRenderer").firstOrNull() ?: return null
        return shelf.get("description").text()?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * Авто-субтитры YouTube Music → LRC с таймкодами. Качество ниже, чем у
     * LRCLIB, поэтому используется только как fallback для YouTube-треков.
     */
    suspend fun transcript(videoId: String): String? {
        if (videoId.isBlank()) return null
        val params = Base64.getEncoder().encodeToString(
            "\n\u000B$videoId".toByteArray(Charsets.UTF_8),
        )
        val response = InnerTube.call(
            endpoint = "get_transcript",
            apiKey = TRANSCRIPT_API_KEY,
            payload = buildJsonObject { put("params", params) },
        )
        val out = ArrayList<String>(256)
        var previous = ""
        for (cue in response.deepObjects("transcriptCueRenderer")) {
            val text = cue.obj("cue")?.str("simpleText")
                ?.trim()
                ?.trim('♪', ' ')
                ?.takeIf { it.isNotBlank() }
                ?: continue
            if (text == previous) continue
            previous = text
            val start = cue.long("startOffsetMs") ?: cue.int("startOffsetMs")?.toLong() ?: continue
            val minutes = start / 60_000
            val seconds = (start / 1_000) % 60
            val millis = start % 1_000
            out += String.format("[%02d:%02d.%03d]%s", minutes, seconds, millis, text)
            if (out.size >= MAX_TRANSCRIPT_LINES) break
        }
        return out.joinToString("\n").takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------- аккаунт

    /** Имя/аватар активного Google-аккаунта (InnerTube account_menu). */
    suspend fun accountInfo(): GoogleAccountInfo? {
        val response = InnerTube.call(
            endpoint = "account/account_menu",
            payload = buildJsonObject { },
        )
        val header = response.deepObjects("activeAccountHeaderRenderer").firstOrNull()
            ?: return null
        val name = header.get("accountName").text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return GoogleAccountInfo(
            name = decodeHtml(name),
            email = header.get("email").text()?.let(::decodeHtml),
            channelHandle = header.get("channelHandle").text()?.let(::decodeHtml),
            avatarUrl = header.get("accountPhoto").firstThumbnailUrl(),
        )
    }

    /** Плейлисты личной библиотеки (FEmusic_liked_playlists). */
    suspend fun libraryPlaylists(): List<Playlist> {
        val response = InnerTube.call(
            endpoint = "browse",
            payload = buildJsonObject { put("browseId", "FEmusic_liked_playlists") },
        )
        val out = LinkedHashMap<String, Playlist>()
        for (renderer in response.deepObjects("musicTwoRowItemRenderer")) {
            val browseId = renderer.obj("navigationEndpoint")
                ?.obj("browseEndpoint")
                ?.str("browseId")
                ?: continue
            if (!browseId.startsWith("VL")) continue
            val id = browseId.removePrefix("VL").ifBlank { continue }
            val title = decodeHtml(renderer.get("title").text()).trim()
            if (title.isBlank()) continue
            out.putIfAbsent(
                id,
                Playlist(
                    id = "youtube:playlist:$id",
                    name = title,
                    artworkUrl = renderer.get("thumbnail").firstThumbnailUrl(),
                    source = Source.YOUTUBE,
                    remoteId = id,
                    editable = false,
                ),
            )
        }
        for (renderer in response.deepObjects("musicResponsiveListItemRenderer")) {
            val browseId = renderer.obj("navigationEndpoint")
                ?.obj("browseEndpoint")
                ?.str("browseId")
                ?: continue
            if (!browseId.startsWith("VL")) continue
            val id = browseId.removePrefix("VL").ifBlank { continue }
            val title = decodeHtml(
                renderer.arr("flexColumns")
                    ?.firstOrNull()
                    ?.asObjectOrNull()
                    ?.obj("musicResponsiveListItemFlexColumnRenderer")
                    ?.get("text")
                    .runTexts()
                    .joinToString(""),
            ).trim()
            if (title.isBlank()) continue
            out.putIfAbsent(
                id,
                Playlist(
                    id = "youtube:playlist:$id",
                    name = title,
                    artworkUrl = renderer.get("thumbnail").firstThumbnailUrl(),
                    source = Source.YOUTUBE,
                    remoteId = id,
                    editable = false,
                ),
            )
        }
        return out.values.toList()
    }

    /** «Понравившиеся» (LM) — для очереди сохранённых треков. */
    suspend fun likedSongs(cap: Int = 200): List<Track> =
        playlist(PLAYLIST_LIKED_MUSIC, cap).tracks

    // ------------------------------------------------------------ аудиопоток

    /**
     * Прямой аудио-URL. Клиенты перебираются, потому что YouTube может
     * отдать одному клиенту только signatureCipher, а другому — готовый `url`.
     */
    suspend fun stream(videoId: String): StreamInfo {
        var lastError: Exception? = null
        for (profile in STREAM_CLIENTS) {
            try {
                val response = InnerTube.call(
                    endpoint = "player",
                    profile = profile,
                    payload = buildJsonObject {
                        put("videoId", videoId)
                        put("contentCheckOk", true)
                        put("racyCheckOk", true)
                        putJsonObject("playbackContext") {
                            putJsonObject("contentPlaybackContext") {
                                put("html5Preference", "HTML5_PREF_WANTS")
                            }
                        }
                    },
                )
                val status = response.obj("playabilityStatus")?.str("status")
                if (status != "OK") {
                    val reason = response.obj("playabilityStatus")?.str("reason")
                    throw IllegalStateException(
                        reason?.takeIf { it.isNotBlank() } ?: "Трек недоступен ($status)",
                    )
                }
                val formats = response.obj("streamingData")
                    ?.arr("adaptiveFormats")
                    ?.mapNotNull { it.asObjectOrNull() }
                    .orEmpty()
                val best = formats
                    .mapNotNull(::parseAudioFormat)
                    .maxByOrNull { formatScore(it) }
                    ?: throw IllegalStateException("YouTube не вернул аудиоформаты")
                val expire = EXPIRE_PARAM.find(best.url)?.groupValues?.getOrNull(1)
                    ?.toLongOrNull()
                val expiresAt = expire?.times(1000L)
                    ?: (System.currentTimeMillis() + DEFAULT_STREAM_TTL_MS)
                return StreamInfo(
                    videoId = videoId,
                    url = best.url,
                    mimeType = best.mimeType,
                    bitrate = best.bitrate,
                    itag = best.itag,
                    expiresAtMs = expiresAt,
                    headers = InnerTube.streamHeaders(profile),
                    contentLength = best.contentLength,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw IllegalStateException(
            lastError?.message ?: "Не удалось получить поток YouTube",
            lastError,
        )
    }

    // ------------------------------------------------------------- утилиты

    /** Достаёт id из ссылки (`watch?v=…`, `?list=…`, `/playlist/<id>`) или «голого» id. */
    fun extractPlaylistId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        if (trimmed in SPECIAL_PLAYLIST_IDS) return trimmed
        LIST_PARAM.find(trimmed)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val withoutQuery = trimmed.substringBefore('?').substringBefore('#').trimEnd('/')
        val lastSegment = withoutQuery.substringAfterLast('/')
        return when {
            lastSegment in SPECIAL_PLAYLIST_IDS -> lastSegment
            looksLikePlaylistId(lastSegment) -> lastSegment
            looksLikePlaylistId(trimmed) -> trimmed
            else -> null
        }
    }

    fun normalizePlaylistId(value: String): String? {
        val id = extractPlaylistId(value) ?: return null
        return id.removePrefix("VL").ifBlank { null }
    }

    private fun parseTrackRenderer(renderer: JsonObject): Track? {
        val videoId = renderer.obj("playlistItemData")?.str("videoId")
            ?: renderer.obj("overlay")
                ?.obj("musicItemThumbnailOverlayRenderer")
                ?.obj("content")
                ?.obj("musicPlayButtonRenderer")
                ?.obj("playNavigationEndpoint")
                ?.obj("watchEndpoint")
                ?.str("videoId")
            ?: renderer.obj("navigationEndpoint")?.obj("watchEndpoint")?.str("videoId")
            ?: return null
        if (!VIDEO_ID.matches(videoId)) return null

        val flex = renderer.arr("flexColumns")
            ?.mapNotNull { it.asObjectOrNull() }
            .orEmpty()
        fun columnRuns(index: Int): List<String> = flex.getOrNull(index)
            ?.obj("musicResponsiveListItemFlexColumnRenderer")
            ?.get("text")
            .runTexts()

        val title = decodeHtml(columnRuns(0).joinToString("")).trim()
        if (title.isBlank() || title in PLACEHOLDER_TITLES) return null

        val subtitle = columnRuns(1).joinToString("")
        val parts = subtitle.split("•").map { it.trim() }.filter { it.isNotEmpty() }
        val artist = decodeHtml(parts.firstOrNull().orEmpty()).ifBlank { "YouTube Music" }
        val cleanArtist = ArtistNames.clean(artist).ifBlank { "YouTube Music" }
        val album = parts.drop(1)
            .firstOrNull { !looksLikeDuration(it) && !it.endsWith("views", true) }
            ?.let(::decodeHtml)

        val fixedDuration = renderer.arr("fixedColumns")
            ?.firstOrNull()
            ?.asObjectOrNull()
            ?.obj("musicResponsiveListItemFixedColumnRenderer")
            ?.get("text")
            ?.text()
            ?.takeIf(::looksLikeDuration)
        val subtitleDuration = parts.lastOrNull()?.takeIf(::looksLikeDuration)
        val duration = parseClockDuration(fixedDuration ?: subtitleDuration)
        val artwork = renderer.get("thumbnail").firstThumbnailUrl()

        return youtubeTrack(
            videoId = videoId,
            title = title,
            artist = cleanArtist,
            album = album,
            durationMs = duration,
            artworkUrl = artwork,
        )
    }

    private fun parseAudioFormat(format: JsonObject): RawFormat? {
        val mime = format.str("mimeType").orEmpty()
        if (!mime.startsWith("audio/")) return null
        val url = format.str("url")
        if (url.isNullOrBlank()) return null
        return RawFormat(
            itag = format.int("itag") ?: 0,
            mimeType = mime,
            bitrate = format.int("bitrate") ?: 0,
            url = url,
            contentLength = format.str("contentLength")?.toLongOrNull()?.takeIf { it > 0L },
        )
    }

    private fun formatScore(format: RawFormat): Long = when (format.itag) {
        251 -> 5_000_000_000L + format.bitrate
        140 -> 4_000_000_000L + format.bitrate
        250 -> 3_000_000_000L + format.bitrate
        249 -> 2_000_000_000L + format.bitrate
        139 -> 1_000_000_000L + format.bitrate
        else -> format.bitrate.toLong()
    }

    private fun continuationToken(root: JsonObject): String? {
        for (shelf in root.deepObjects("musicPlaylistShelfRenderer")) {
            val continuations = shelf.arr("continuations") ?: continue
            for (element in continuations) {
                val continuation = element.asObjectOrNull() ?: continue
                continuation.obj("nextContinuationData")?.str("continuation")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
                continuation.obj("reloadContinuationData")?.str("continuation")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
        }
        return null
    }

    private fun looksLikePlaylistId(value: String): Boolean {
        if (value.length !in 12..80) return false
        if (!PLAYLIST_ID_CHARS.matches(value)) return false
        return PLAYLIST_PREFIXES.any { value.startsWith(it) }
    }

    private fun youtubeTrack(
        videoId: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
        artworkUrl: String?,
    ): Track = Track(
        id = "youtube:$videoId",
        title = title,
        artist = artist,
        album = album?.takeIf { it.isNotBlank() },
        durationMs = durationMs,
        uri = "$URI_SCHEME$videoId",
        artworkUrl = artworkUrl,
        source = Source.YOUTUBE,
        playable = true,
        downloadAllowed = true,
        deepLink = "https://music.youtube.com/watch?v=$videoId",
    )

    private data class RawFormat(
        val itag: Int,
        val mimeType: String,
        val bitrate: Int,
        val url: String,
        val contentLength: Long? = null,
    )

    private companion object {
        const val URI_SCHEME = "innertube:"
        const val FILTER_SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="
        const val MAX_PAGES = 10
        const val MAX_TRANSCRIPT_LINES = 500
        const val TRANSCRIPT_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
        const val PLAYLIST_LIKED_MUSIC = "LM"
        val SPECIAL_PLAYLIST_IDS = setOf("LM", "SE", "RDPN")
        const val DEFAULT_STREAM_TTL_MS = 30 * 60 * 1000L

        val STREAM_CLIENTS = listOf(
            InnerTube.ANDROID_VR,
            InnerTube.ANDROID_VR_LEGACY,
            InnerTube.IOS,
        )

        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        val PLAYLIST_PREFIXES = listOf("PL", "UU", "LL", "FL", "RD", "OLAK5uy_", "VL")
        val PLAYLIST_ID_CHARS = Regex("[A-Za-z0-9_-]+")
        val LIST_PARAM = Regex("[?&]list=([A-Za-z0-9_-]+)")
        val EXPIRE_PARAM = Regex("[?&]expire=(\\d+)")

        val PLACEHOLDER_TITLES = setOf(
            "Deleted video",
            "Private video",
            "[Deleted video]",
            "[Private video]",
        )
    }
}

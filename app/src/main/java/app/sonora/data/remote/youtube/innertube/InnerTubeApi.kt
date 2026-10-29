package app.sonora.data.remote.youtube.innertube

import app.sonora.core.model.ArtistRelease
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

    // ------------------------------------------------------------ исполнители

    /**
     * Дискография исполнителя: альбомы, синглы и EP.
     *
     * Идём через поиск с фильтром «Альбомы», а не через browse-страницу канала:
     * в библиотеке хранятся только имена артистов, без channelId, а поиск
     * отдаёт готовые карточки релизов с browseId — этого достаточно, чтобы
     * открыть альбом и показать его треки.
     */
    suspend fun artistAlbums(artist: String, limit: Int = 70): List<ArtistRelease> {
        val query = artist.trim()
        if (query.isBlank()) return emptyList()
        // Полная дискография живёт на странице канала: сначала находим channelId
        // поиском по исполнителям, затем читаем все полки «Альбомы»/«Синглы».
        val channelId = runCatching { artistChannelId(query) }.getOrNull()
        if (channelId != null) {
            val fromPage = runCatching { artistPageAlbums(channelId, limit) }
                .getOrDefault(emptyList())
            if (fromPage.isNotEmpty()) return fromPage
        }
        return searchArtistAlbums(query, limit)
    }

    /** Канал исполнителя: поиск с фильтром «Исполнители». */
    private suspend fun artistChannelId(artist: String): String? {
        val response = InnerTube.call(
            endpoint = "search",
            payload = buildJsonObject {
                put("query", artist)
                put("params", FILTER_ARTISTS)
            },
        )
        for (renderer in response.deepObjects("musicResponsiveListItemRenderer")) {
            val pageType = renderer.obj("navigationEndpoint")
                ?.obj("browseEndpoint")
                ?.obj("browseEndpointContextSupportedConfigs")
                ?.obj("browseEndpointContextMusicConfig")
                ?.str("pageType")
            if (pageType != null && pageType != ARTIST_PAGE_TYPE) continue
            val browseId = renderer.obj("navigationEndpoint")
                ?.obj("browseEndpoint")
                ?.str("browseId")
                ?: renderer.deepObjects("browseEndpoint").firstOrNull()?.str("browseId")
                ?: continue
            if (!browseId.startsWith(CHANNEL_ID_PREFIX)) continue
            val name = renderer.arr("flexColumns")
                ?.firstOrNull()
                ?.asObjectOrNull()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")
                ?.get("text")
                .text()
                ?: renderer.obj("title").text()
            if (name != null && ArtistNames.matches(name, artist)) return browseId
        }
        return null
    }

    /** Все релизы со страницы артиста, включая продолжения «Показать все». */
    private suspend fun artistPageAlbums(channelId: String, limit: Int): List<ArtistRelease> {
        var response = InnerTube.call(
            endpoint = "browse",
            payload = buildJsonObject { put("browseId", channelId) },
        )
        val out = LinkedHashMap<String, ArtistRelease>()
        var pages = 0
        while (pages < MAX_PAGES) {
            pages++
            val before = out.size
            for (renderer in response.deepObjects("musicTwoRowItemRenderer")) {
                val album = parseAlbumCard(renderer) ?: continue
                out.putIfAbsent(album.id, album)
                if (out.size >= limit) return out.values.toList()
            }
            // Продолжение без новых альбомов — это полка песен, а не релизов.
            if (out.size == before) break
            // На странице артиста полки листаются своей кнопкой «Ещё».
            val token = continuationToken(response) ?: break
            response = InnerTube.call(
                endpoint = "browse",
                payload = buildJsonObject { put("continuation", token) },
            )
        }
        return out.values.toList()
    }

    /** Резервный путь: поиск с фильтром «Альбомы», если канал не нашёлся. */
    private suspend fun searchArtistAlbums(artist: String, limit: Int): List<ArtistRelease> {
        val response = InnerTube.call(
            endpoint = "search",
            payload = buildJsonObject {
                put("query", artist)
                put("params", FILTER_ALBUMS)
            },
        )
        val out = LinkedHashMap<String, ArtistRelease>()
        for (renderer in response.deepObjects("musicTwoRowItemRenderer")) {
            val album = parseAlbumCard(renderer) ?: continue
            if (!belongsToArtist(album, artist)) continue
            out.putIfAbsent(album.id, album)
            if (out.size >= limit) break
        }
        if (out.size < limit) {
            for (renderer in response.deepObjects("musicResponsiveListItemRenderer")) {
                val album = parseAlbumCard(renderer) ?: continue
                if (!belongsToArtist(album, artist)) continue
                out.putIfAbsent(album.id, album)
                if (out.size >= limit) break
            }
        }
        return out.values.toList()
    }

    /** Популярные треки исполнителя — блок «Популярное» на его странице. */
    suspend fun artistTopTracks(artist: String, limit: Int = 25): List<Track> {
        val query = artist.trim()
        if (query.isBlank()) return emptyList()
        val response = InnerTube.call(
            endpoint = "search",
            payload = buildJsonObject {
                put("query", query)
                put("params", FILTER_SONGS)
            },
        )
        val out = LinkedHashMap<String, Track>()
        for (renderer in response.deepObjects("musicResponsiveListItemRenderer")) {
            val track = parseTrackRenderer(renderer) ?: continue
            if (!ArtistNames.matches(track.artist, query)) continue
            out.putIfAbsent(track.id, track)
            if (out.size >= limit) break
        }
        return out.values.toList()
    }

    /**
     * Альбом по browseId (`MPREb…`) — та же структура, что у плейлиста:
     * шапка с обложкой и списком треков, поэтому разбор общий.
     */
    suspend fun album(browseId: String, cap: Int = 200): RemotePlaylist {
        val id = browseId.trim().removePrefix("VL")
        require(id.isNotBlank()) { "Не удалось распознать альбом YouTube Music." }

        var response = InnerTube.call(
            endpoint = "browse",
            payload = buildJsonObject { put("browseId", id) },
        )

        val header = response.deepObjects("musicResponsiveHeaderRenderer").firstOrNull()
            ?: response.deepObjects("musicDetailHeaderRenderer").firstOrNull()
        val title = header?.get("title").text()?.let(::decodeHtml)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Альбом YouTube Music"
        val subtitle = header?.get("subtitle").text()?.let(::decodeHtml)?.trim()
        val artwork = header?.get("thumbnail").firstThumbnailUrl()

        val tracks = LinkedHashMap<String, Track>()
        var pages = 0
        while (pages < MAX_PAGES) {
            pages++
            for (renderer in response.deepObjects("musicResponsiveListItemRenderer")) {
                val parsed = parseTrackRenderer(renderer) ?: continue
                // На странице альбома название альбома берём из шапки:
                // в подписи строки его может не быть вовсе.
                tracks.putIfAbsent(parsed.id, parsed.copy(album = title))
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
     *
     * [targetKbps] — желаемый битрейт (64/128/160). Подбираем ближайший формат
     * не ниже целевого; если такого нет — самый близкий снизу. null — лучшее
     * доступное качество.
     */
    suspend fun stream(videoId: String, targetKbps: Int? = null): StreamInfo {
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
                val parsed = formats.mapNotNull(::parseAudioFormat)
                val best = pickAudioFormat(parsed, targetKbps)
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

    /**
     * Карточка релиза в выдаче «Альбомы»: `musicTwoRowItemRenderer` в сетке
     * и `musicResponsiveListItemRenderer` в списке. Обе несут browseId альбома.
     */
    private fun parseAlbumCard(renderer: JsonObject): ArtistRelease? {
        val endpoint = renderer.obj("navigationEndpoint")?.obj("browseEndpoint")
            ?: renderer.deepObjects("browseEndpoint").firstOrNull()
            ?: return null
        val browseId = endpoint.str("browseId")?.takeIf { it.isNotBlank() } ?: return null
        val pageType = endpoint
            .obj("browseEndpointContextSupportedConfigs")
            ?.obj("browseEndpointContextMusicConfig")
            ?.str("pageType")
        if (pageType != null && pageType != ALBUM_PAGE_TYPE) return null
        if (pageType == null && !browseId.startsWith(ALBUM_ID_PREFIX)) return null

        val title = decodeHtml(
            renderer.obj("title").text()
                ?: renderer.arr("flexColumns")
                    ?.firstOrNull()
                    ?.asObjectOrNull()
                    ?.obj("musicResponsiveListItemFlexColumnRenderer")
                    ?.get("text")
                    .text(),
        ).trim()
        if (title.isBlank() || title in PLACEHOLDER_TITLES) return null

        val subtitle = (
            renderer.obj("subtitle").text()
                ?: renderer.arr("flexColumns")
                    ?.getOrNull(1)
                    ?.asObjectOrNull()
                    ?.obj("musicResponsiveListItemFlexColumnRenderer")
                    ?.get("text")
                    .text()
            ).orEmpty()
        val parts = decodeHtml(subtitle)
            .split("•", "·")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val year = parts.firstOrNull { YEAR.matches(it) }?.toIntOrNull()
        val type = parts.firstOrNull { it.lowercase() in RELEASE_TYPES }
        val artistName = parts.firstOrNull {
            !YEAR.matches(it) && it.lowercase() !in RELEASE_TYPES
        }.orEmpty()

        val artwork = renderer.get("thumbnailRenderer").firstThumbnailUrl()
            ?: renderer.get("thumbnail").firstThumbnailUrl()

        return ArtistRelease(
            id = browseId,
            title = title,
            artist = ArtistNames.clean(artistName),
            year = year,
            artworkUrl = artwork,
            type = type,
            remote = true,
        )
    }

    /** Относится ли релиз к запрошенному исполнителю. */
    private fun belongsToArtist(album: ArtistRelease, artist: String): Boolean {
        if (album.artist.isNotBlank() && !ArtistNames.isPlaceholder(album.artist)) {
            return ArtistNames.matches(album.artist, artist)
        }
        val artistKey = ArtistNames.key(artist)
        val titleKey = ArtistNames.key(album.title)
        if (artistKey.isBlank()) return false
        return titleKey == artistKey || titleKey.startsWith("$artistKey ")
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

    /**
     * Выбор формата под желаемый битрейт.
     *
     * Сначала пробуем формат не ниже цели с минимальным превышением (чтобы
     * «Обычное» не превращалось в 160 кбит/с), затем — самый близкий снизу.
     * При равном битрейте предпочитаем Opus (251/250/249) и m4a (140/139).
     */
    private fun pickAudioFormat(formats: List<RawFormat>, targetKbps: Int?): RawFormat? {
        if (formats.isEmpty()) return null
        if (targetKbps == null) return formats.maxByOrNull { formatScore(it) }
        val target = targetKbps * 1000
        val atLeast = formats.filter { it.bitrate >= target }
            .minWithOrNull(
                compareBy<RawFormat> { it.bitrate - target }
                    .thenByDescending { formatScore(it) },
            )
        if (atLeast != null) return atLeast
        // Источник не отдаёт столько: берём самый высокий формат НИЖЕ цели,
        // а не максимальный вообще — иначе «Низкое» звучало бы как «Высокое».
        return formats.filter { it.bitrate > 0 && it.bitrate < target }
            .maxByOrNull { it.bitrate }
            ?: formats.minByOrNull { it.bitrate }
            ?: formats.maxByOrNull { formatScore(it) }
    }

    private fun continuationToken(root: JsonObject): String? {
        val shelves = sequence {
            yieldAll(root.deepObjects("musicPlaylistShelfRenderer"))
            yieldAll(root.deepObjects("musicShelfRenderer"))
        }
        for (shelf in shelves) {
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
        // Страницы артиста/альбома кладут continuation в другую секцию.
        root.deepObjects("nextContinuationData").forEach { data ->
            data.str("continuation")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        root.deepObjects("reloadContinuationData").forEach { data ->
            data.str("continuation")?.takeIf { it.isNotBlank() }?.let { return it }
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
        const val FILTER_ALBUMS = "EgWKAQIYAWoKEAkQChAFEAMQBA=="
        const val FILTER_ARTISTS = "EgWKAQIgAWoKEAkQChAFEAMQBA=="
        const val ALBUM_PAGE_TYPE = "MUSIC_PAGE_TYPE_ALBUM"
        const val ARTIST_PAGE_TYPE = "MUSIC_PAGE_TYPE_ARTIST"
        const val ALBUM_ID_PREFIX = "MPRE"
        const val CHANNEL_ID_PREFIX = "UC"
        val YEAR = Regex("""^(19|20)\d{2}$""")
        val RELEASE_TYPES = setOf(
            "album", "single", "ep", "compilation", "live", "soundtrack",
            "альбом", "сингл", "мини-альбом", "сборник",
        )
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

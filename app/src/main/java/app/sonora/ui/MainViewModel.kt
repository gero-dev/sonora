package app.sonora.ui

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.sonora.AppGraph
import app.sonora.core.model.ConnectionState
import app.sonora.core.model.Playlist
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.settings.Accent
import app.sonora.core.settings.AppSettings
import app.sonora.core.settings.ThemeMode
import app.sonora.core.util.AppLinks
import app.sonora.data.metadata.ArtistNames
import app.sonora.data.remote.youtube.innertube.StreamInfo
import app.sonora.net.warp.WarpConfigEntry
import app.sonora.net.warp.WarpConfigSummary
import app.sonora.net.warp.WarpStatus
import app.sonora.data.import.ImportedTrackEntry
import app.sonora.playback.PlayerUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

data class SearchUiState(
    val query: String = "",
    val local: List<Track> = emptyList(),
    val remote: List<Track> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class DiscoverUiState(
    val query: String = "",
    val source: Source = Source.ARCHIVE,
    val results: List<Track> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val downloadingIds: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(),
)

data class GoogleAccountUiState(
    val configured: Boolean = true,
    val connected: Boolean = false,
    val accountName: String? = null,
    val avatarUrl: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val playlistsImported: Int = 0,
)

data class TakeoutUiState(
    val importing: Boolean = false,
    val importedTracks: Int = 0,
    val importedPlaylists: Int = 0,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
)

/** Прогресс/результат импорта аудиофайлов с устройства (SAF, можно несколько сразу). */
data class FileImportUiState(
    val importing: Boolean = false,
    val imported: Int = 0,
    val failed: Int = 0,
    val done: Boolean = false,
)

/** Прогресс/результат импорта плейлиста из CSV/JSON/M3U/TXT. */
data class PlaylistImportUiState(
    val importing: Boolean = false,
    val total: Int = 0,
    val processed: Int = 0,
    val matched: Int = 0,
    val notFound: Int = 0,
    val playlistName: String? = null,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    val done: Boolean = false,
)

/** Форматы, которые можно сохранить на устройство. */
private val DOWNLOAD_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "webm", "flac", "wav")
private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")

/** Версия схемы метаданных: при повышении сбрасываются устаревшие отметки «проверено». */
private const val METADATA_SCHEMA_VERSION = 4

/** Сколько треков просить у InnerTube в поиске и в очереди «дальше». */
private const val REMOTE_SEARCH_LIMIT = 30
private const val RELATED_LIMIT = 25
private const val WAVE_LIMIT = 30
/** Сколько треков добавляет кнопка «Ещё» в бесконечной волне. */
private const val WAVE_LOAD_MORE_LIMIT = 20

/** Максимум треков за один импорт списка: каждый неизвестный ищется в InnerTube. */
private const val MAX_PLAYLIST_IMPORT = 150

// «Моя волна» — только музыка: границы длительности и скобочные пометки.
private const val WAVE_MIN_TRACK_MS = 45_000L
private const val WAVE_MAX_TRACK_MS = 12 * 60_000L
private const val WAVE_MIN_LOCAL_TRACK_MS = 20_000L
private val PARENS_REGEX_WAVE = Regex("""[\(\[][^\)\]]*[\)\]]""")
private val WHITESPACE_REGEX_WAVE = Regex("""\s+""")

/**
 * Единая ViewModel приложения. Экраны получают от неё состояние и действия,
 * чтобы UI не знал о репозиториях и сетевых клиентах.
 */
class MainViewModel(private val graph: AppGraph) : ViewModel() {

    // ---------- Состояния ----------
    val tracks: StateFlow<List<Track>> = graph.library.tracks
    val playlists: StateFlow<List<Playlist>> = graph.library.playlists
    val favorites: StateFlow<Set<String>> = graph.library.favorites
    val scanning: StateFlow<Boolean> = graph.library.scanning
    val player: StateFlow<PlayerUiState> = graph.playback.state
    val settings: StateFlow<AppSettings> = graph.settings.state
    val downloads = graph.downloads.items

    private val _connections = MutableStateFlow<List<ConnectionState>>(emptyList())
    val connections: StateFlow<List<ConnectionState>> = _connections.asStateFlow()

    private val _syncing = MutableStateFlow<Set<Source>>(emptySet())
    val syncing: StateFlow<Set<Source>> = _syncing.asStateFlow()

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            graph.library.initialize()
            // Миграция: старая версия могла пометить треки «проверенными» после сетевого
            // сбоя, и метаданные больше не запрашивались. Один раз сбрасываем отметки.
            if (graph.settings.current.metadataSchemaVersion < METADATA_SCHEMA_VERSION) {
                graph.library.resetMetadataChecked()
                graph.settings.update { it.copy(metadataSchemaVersion = METADATA_SCHEMA_VERSION) }
            }
            refreshConnections()
            refreshGoogleAccount()
            refreshMetadataCacheStats()
            // Автопоиск метаданных: если включён, обогащаем библиотеку сразу после старта.
            if (graph.settings.current.enrichMetadataOnScan &&
                graph.library.metadataCandidates().isNotEmpty()
            ) {
                enrichLibrary()
            }
            refreshWave()
            // WARP: автоподключение при старте, если пользователь включил автозапуск,
            // есть конфиги, разрешение VPN уже выдано и чужой VPN не активен.
            if (graph.settings.current.warpAutoConnect &&
                graph.warp.configs().isNotEmpty() &&
                !graph.warp.externalVpnActive() &&
                graph.warp.hasVpnPermission()
            ) {
                graph.warp.startIfNoExternalVpn()
            }
        }
        observeInfiniteQueue()
        observeCurrentYouTubeTrack()
        // Страховка для кнопки «вперёд»: если очередь кончилась, PlaybackController
        // сам попросит новую пачку и переключит трек (только при включённой бесконечной очереди).
        graph.playback.queueExtender = {
            if (!graph.settings.current.infiniteQueue) {
                emptyList()
            } else {
                val state = graph.playback.state.value
                buildRandomBatch(state.queue, state.current)
            }
        }
    }

    // ---------- Бесконечная очередь ----------

    private var extendingQueue = false

    /**
     * Подкидывает случайные треки, когда очередь подходит к концу.
     * Каждая пачка перемешивается заново, поэтому очередь никогда не повторяется
     * одинаково от запуска к запуску.
     */
    private fun observeInfiniteQueue() {
        viewModelScope.launch {
            graph.playback.state.collect { state -> maybeExtendQueue(state) }
        }
    }

    /** Подтягивает текст/метаданные для любого YouTube-трека из очереди. */
    private fun observeCurrentYouTubeTrack() {
        viewModelScope.launch {
            var lastId: String? = null
            graph.playback.state.collect { state ->
                val track = state.current ?: return@collect
                if (track.id != lastId) {
                    lastId = track.id
                    if (track.source == Source.YOUTUBE) cacheAndEnrichYouTube(track)
                }
            }
        }
    }

    private suspend fun maybeExtendQueue(state: PlayerUiState) {
        if (!graph.settings.current.infiniteQueue) return
        val queue = state.queue
        if (queue.size < 2) return
        if (state.index < queue.size - 2) return
        if (extendingQueue) return
        extendingQueue = true
        try {
            val batch = buildRandomBatch(queue, state.current)
            if (batch.isNotEmpty()) graph.playback.appendToQueue(batch)
        } finally {
            extendingQueue = false
        }
    }

    private fun buildRandomBatch(queue: List<Track>, current: Track?): List<Track> {
        val pool = graph.library.tracks.value.filter {
            (it.source == Source.LOCAL || it.source == Source.YOUTUBE) &&
                it.playable && !it.uri.isNullOrBlank()
        }
        if (pool.isEmpty()) return emptyList()
        val recentIds = queue.takeLast(8).map { it.id }.toSet()
        val sameArtist = if (current == null) {
            emptyList()
        } else {
            pool.filter { it.artist == current.artist && it.id !in recentIds }
        }
        val rest = pool.filter { it.id !in recentIds }
        // Микс: сначала пара треков того же исполнителя, затем локальные и
        // онлайн-треки вперемешку (без привязки к источнику).
        val unique = (sameArtist.shuffled().take(2) + rest.shuffled()).distinctBy { it.id }
        // Маленькая библиотека: разрешаем повторы, иначе очередь просто оборвётся.
        val batch = if (unique.isNotEmpty()) unique else pool.shuffled()
        return batch.take(12)
    }

    // ---------- Библиотека ----------

    fun scanLibrary() {
        viewModelScope.launch {
            graph.library.scanLocal()
            // Пересканирование сохраняет найденные метаданные; синхронизируем очередь,
            // чтобы открытый плеер и мини-плеер показывали актуальные теги/обложки.
            graph.playback.applyMetadata(
                graph.library.tracks.value.filter { it.source == Source.LOCAL },
            )
            if (graph.settings.current.enrichMetadataOnScan) enrichLibrary()
        }
    }

    fun albums() = graph.library.albums()

    fun artists() = graph.library.artists()

    fun playlistTracks(playlist: Playlist): List<Track> {
        val local = graph.library.tracksByIds(playlist.trackIds)
        if (local.isNotEmpty() || playlist.remoteId == null) return local
        return emptyList()
    }

    fun favoriteTracks(): List<Track> = graph.library.favoriteTracks()

    fun toggleFavorite(trackId: String) {
        viewModelScope.launch { graph.library.toggleFavorite(trackId) }
    }

    /**
     * Избранное для трека из поиска: результат InnerTube ещё не в библиотеке,
     * поэтому сначала кэшируем его, иначе сердечко не сохранится.
     */
    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            if (track.source != Source.LOCAL && graph.library.trackById(track.id) == null) {
                graph.library.cacheRemoteTracks(listOf(track))
            }
            graph.library.toggleFavorite(track.id)
        }
    }

    fun createPlaylist(name: String, onCreated: (Playlist) -> Unit = {}) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val created = graph.library.createPlaylist(name.trim())
            onCreated(created)
        }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch { graph.library.deletePlaylist(id) }
    }

    fun renamePlaylist(id: String, name: String) {
        viewModelScope.launch { graph.library.renamePlaylist(id, name) }
    }

    fun addToPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch { graph.library.addToPlaylist(playlistId, listOf(trackId)) }
    }

    fun addToPlaylist(playlistId: String, trackIds: List<String>) {
        viewModelScope.launch { graph.library.addToPlaylist(playlistId, trackIds) }
    }

    fun removeFromPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch { graph.library.removeFromPlaylist(playlistId, trackId) }
    }

    // ---------- Воспроизведение ----------

    /**
     * Играет трек, если платформа разрешает; иначе открывает официальное приложение.
     * Для локального трека при включённой автоочереди собирает очередь из похожих
     * треков (тот же альбом → тот же исполнитель → случайные из фонотеки).
     */
    fun playOrOpen(track: Track): Boolean {
        if (track.playable && !track.uri.isNullOrBlank()) {
            graph.playback.playQueue(buildQueueFor(track), 0)
            if (track.source == Source.YOUTUBE) {
                cacheAndEnrichYouTube(track)
                appendYouTubeRelated(track)
            }
            return true
        }
        AppLinks.open(
            graph.appContext,
            track.deepLink,
            "Этот трек нельзя включить в Sonora — платформа не разрешает стороннее воспроизведение.",
        )
        return false
    }

    /**
     * Игранный YouTube-трек попадает в библиотеку (вкладка «YouTube»), а его текст
     * подтягивается из InnerTube и сразу появляется в плеере.
     */
    private fun cacheAndEnrichYouTube(track: Track) {
        viewModelScope.launch {
            val videoId = track.id.removePrefix("youtube:")
            if (videoId.isBlank()) return@launch
            val known = graph.library.trackById(track.id)
            val base = known ?: track.copy(
                uri = if (track.uri.isNullOrBlank()) "innertube:$videoId" else track.uri,
                playable = true,
            )
            if (known == null) graph.library.cacheRemoteTracks(listOf(base))
            if (base.extra["lyricsChecked"] != "1") {
                val metadata = runCatching { graph.metadata.lookup(base) }.getOrNull()
                val innertubeLyrics = graph.youtube.lyrics(videoId)
                val synced = metadata?.syncedLyrics
                val updated = base.copy(
                    artist = if (ArtistNames.isSuspicious(base.artist)) {
                        ArtistNames.clean(metadata?.artist ?: base.artist)
                    } else {
                        ArtistNames.clean(base.artist)
                    },
                    album = base.album ?: metadata?.album,
                    artworkUrl = base.artworkUrl ?: metadata?.coverUrl,
                    lyrics = metadata?.lyrics?.takeIf { it.isNotBlank() }
                        ?: innertubeLyrics ?: base.lyrics,
                    syncedLyrics = synced ?: base.syncedLyrics,
                    extra = base.extra + buildMap {
                        put("lyricsChecked", "1")
                        metadata?.artistImageUrl?.let { put("artistImageUrl", it) }
                        metadata?.artistBio?.let { put("artistBio", it) }
                        metadata?.genre?.let { put("genre", it) }
                        metadata?.provider?.let { put("metadataProvider", it) }
                    },
                )
                graph.library.cacheRemoteTracks(listOf(updated))
                graph.playback.applyMetadata(listOf(updated))
            }
        }
    }

    // ---------- Скачивание из InnerTube ----------

    private val _youtubeDownloads = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Состояние скачивания YouTube-трека: track.id -> «Скачивание»/«Готово»/ошибка. */
    val youtubeDownloads: StateFlow<Map<String, String>> = _youtubeDownloads.asStateFlow()

    /** Есть ли уже локальная копия этого трека (YouTube или прямого источника). */
    fun isTrackDownloaded(track: Track): Boolean {
        if (track.source == Source.LOCAL) return true
        val videoId = youtubeVideoId(track)
        return graph.library.tracks.value.any { local ->
            if (local.source != Source.LOCAL) return@any false
            local.extra["downloadedTrackId"] == track.id ||
                (videoId != null && local.extra["youtubeId"] == videoId)
        }
    }

    /** Есть ли уже локальная копия этого YouTube-трека. */
    fun isYouTubeDownloaded(track: Track): Boolean = isTrackDownloaded(track)

    /**
     * Скачивает аудио через InnerTube/InnerTubeX, сохраняет обложку рядом с
     * файлом и регистрирует трек как локальный с альбомом/артистом/текстом —
     * после этого он виден во вкладках «Треки», «Альбомы», «Исполнители»
     * и «Избранное».
     *
     * Ссылки InnerTubeX иногда отдаются только bounded-range кусками. Если
     * первый поток отклонён (403/410/416) или сервер не принял Range, берём
     * резервный player-поток и повторяем — как делает Metrolist.
     */
    fun downloadYouTube(track: Track) {
        val videoId = youtubeVideoId(track)
        if (videoId == null) {
            _youtubeDownloads.update { it + (track.id to "Ошибка: нет videoId") }
            _trackDownloads.update { it + (track.id to "Ошибка: нет videoId") }
            return
        }
        if (isYouTubeDownloaded(track)) {
            _youtubeDownloads.update { it + (track.id to "Уже скачано") }
            _trackDownloads.update { it + (track.id to "Уже скачано") }
            return
        }
        if (_trackDownloads.value[track.id] == "Скачивание") return
        _youtubeDownloads.update { it + (track.id to "Скачивание") }
        _trackDownloads.update { it + (track.id to "Скачивание") }
        viewModelScope.launch {
            runCatching {
                val (_, file) = downloadInnerTubeFile(track, videoId)
                val metadata = runCatching { graph.metadata.lookup(track) }.getOrNull()
                val innertubeLyrics = runCatching { graph.youtube.lyrics(videoId) }.getOrNull()
                val synced = metadata?.syncedLyrics
                val cover = graph.youtube.largeArtworkUrl(
                    metadata?.coverUrl ?: track.artworkUrl,
                )?.let { url -> graph.downloads.downloadArtwork(url, videoId) }
                val artistImage = metadata?.artistImageUrl?.let { url ->
                    graph.downloads.downloadArtwork(url, "artist_${ArtistNames.key(track.artist)}")
                }
                val local = track.copy(
                    id = "file:${file.absolutePath}",
                    uri = file.toURI().toString(),
                    artist = if (ArtistNames.isSuspicious(track.artist)) {
                        ArtistNames.clean(metadata?.artist ?: track.artist)
                    } else {
                        ArtistNames.clean(track.artist)
                    },
                    album = track.album ?: metadata?.album,
                    artworkUri = cover?.toURI()?.toString(),
                    artworkUrl = graph.youtube.largeArtworkUrl(track.artworkUrl)
                        ?: metadata?.coverUrl,
                    lyrics = metadata?.lyrics?.takeIf { it.isNotBlank() }
                        ?: innertubeLyrics ?: track.lyrics,
                    syncedLyrics = synced ?: track.syncedLyrics,
                    source = Source.LOCAL,
                    playable = true,
                    downloadAllowed = true,
                    deepLink = null,
                    extra = track.extra + buildMap {
                        put("youtubeId", videoId)
                        put("downloadedTrackId", track.id)
                        put("downloadedAt", System.currentTimeMillis().toString())
                        metadata?.artistImageUrl?.let { put("artistImageUrl", it) }
                        artistImage?.let { put("artistImageUri", it.toURI().toString()) }
                        metadata?.artistBio?.let { put("artistBio", it) }
                        metadata?.genre?.let { put("genre", it) }
                    },
                )
                graph.library.upsertLocalTracks(listOf(local))
                graph.library.scanLocal()
                _youtubeDownloads.update { it + (track.id to "Готово") }
                _trackDownloads.update { it + (track.id to "Готово") }
            }.onFailure { error ->
                val message = downloadErrorMessage(error)
                _youtubeDownloads.update { it + (track.id to "Ошибка: $message") }
                _trackDownloads.update { it + (track.id to "Ошибка: $message") }
            }
        }
    }

    /**
     * Пробует MP4-поток InnerTubeX, затем резервный player-поток InnerTube.
     * Скачивает файл и возвращает поток + готовый файл.
     */
    private suspend fun downloadInnerTubeFile(track: Track, videoId: String): Pair<StreamInfo, java.io.File> {
        var lastError: Throwable? = null
        val plans = listOf(
            "InnerTubeX MP4" to suspend { graph.youtube.downloadStream(videoId) },
            "InnerTube fallback" to suspend {
                graph.youtube.invalidateStream(videoId)
                graph.youtube.downloadStreamFallback(videoId)
            },
            "InnerTubeX retry" to suspend {
                graph.youtube.invalidateStream(videoId)
                graph.youtube.downloadStream(videoId)
            },
        )
        for ((_, resolve) in plans) {
            val stream = try {
                resolve()
            } catch (error: Throwable) {
                lastError = error
                continue
            }
            val extension = when {
                stream.mimeType.contains("mp4", true) -> ".m4a"
                stream.mimeType.contains("webm", true) -> ".webm"
                stream.mimeType.contains("ogg", true) -> ".ogg"
                else -> ".m4a"
            }
            val baseName = "${sanitizeFileName(ArtistNames.clean(track.artist))} - " +
                sanitizeFileName(track.title)
            val result = graph.downloads.download(
                url = stream.url,
                suggestedFileName = baseName + extension,
                headers = stream.headers,
                mime = stream.mimeType,
                contentLengthHint = stream.contentLength,
                rangeChunkSizeBytes = stream.rangeChunkSizeBytes,
                requireBoundedRange = stream.requireBoundedRange,
            )
            if (result.isSuccess) return stream to result.getOrThrow()
            lastError = result.exceptionOrNull()
            Log.w(
                "SonoraDownload",
                "InnerTube download attempt failed: video=$videoId mime=${stream.mimeType} " +
                    "range=${stream.requireBoundedRange}/${stream.rangeChunkSizeBytes} " +
                    "error=${lastError?.message}",
                lastError,
            )
            if (!isRetryableDownloadError(lastError)) break
        }
        throw lastError ?: IllegalStateException("Не удалось скачать поток InnerTube")
    }

    private fun isRetryableDownloadError(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            when (current) {
                is app.sonora.core.net.HttpException ->
                    return current.code in setOf(403, 408, 410, 416, 429) ||
                        current.code in 500..599

                is java.io.IOException -> return true
            }
            current = current.cause
        }
        return false
    }

    private fun downloadErrorMessage(error: Throwable): String {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message?.trim()
            if (!message.isNullOrBlank() && message != "null") return message
            current = current.cause
        }
        return error::class.java.simpleName.ifBlank { "неизвестная ошибка" }
    }

    /** videoId из id, innertube-URI или deep link — на случай нестандартных треков. */
    private fun youtubeVideoId(track: Track): String? {
        val fromId = track.id.removePrefix("youtube:")
        if (fromId != track.id && YOUTUBE_ID.matches(fromId)) return fromId
        val fromUri = track.uri?.removePrefix("innertube:")?.trimStart('/')
            ?.substringBefore('/')?.substringBefore('?')
        if (fromUri != null && YOUTUBE_ID.matches(fromUri)) return fromUri
        val link = track.deepLink ?: return null
        val patterns = listOf(
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        )
        for (pattern in patterns) {
            pattern.find(link)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    /**
     * YouTube-трек играет сразу, а «дальше» (watch-очередь InnerTube) подгружается
     * фоном и дописывается в очередь — как в Metrolist.
     */
    private fun appendYouTubeRelated(track: Track) {
        if (!graph.settings.current.autoQueue) return
        val videoId = track.id.removePrefix("youtube:")
        if (videoId.isBlank()) return
        viewModelScope.launch {
            val related = graph.youtube.related(videoId, RELATED_LIMIT)
            if (related.isNotEmpty()) graph.playback.appendToQueue(related)
        }
    }

    /** Автоматическая очередь: трек + похожие из фонотеки. */
    private fun buildQueueFor(seed: Track): List<Track> {
        if (!graph.settings.current.autoQueue) {
            return listOf(seed)
        }
        val pool = graph.library.tracks.value.filter {
            (it.source == Source.LOCAL || it.source == Source.YOUTUBE) &&
                it.playable && !it.uri.isNullOrBlank() && it.id != seed.id
        }
        if (pool.isEmpty()) return listOf(seed)

        val sameAlbum = pool.filter {
            !seed.album.isNullOrBlank() && it.album == seed.album
        }
        val sameArtist = pool.filter {
            it.artist == seed.artist && sameAlbum.none { album -> album.id == it.id }
        }
        val rest = pool.filter { candidate ->
            sameAlbum.none { it.id == candidate.id } &&
                sameArtist.none { it.id == candidate.id }
        }.shuffled()

        return (listOf(seed) + sameAlbum.take(2) + (sameArtist + rest).shuffled())
            .distinctBy { it.id }
            .take(graph.settings.current.autoQueueSize.coerceIn(5, 100))
    }

    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        graph.playback.playQueue(tracks, startIndex)
    }

    fun playAlbum(albumName: String, artist: String) {
        val list = graph.library.tracks.value.filter { it.album == albumName && it.artist == artist }
        if (list.isNotEmpty()) graph.playback.playQueue(list, 0)
    }

    fun togglePlayPause() = graph.playback.togglePlayPause()
    fun next() = graph.playback.next()
    fun previous() = graph.playback.previous()
    fun seekTo(ms: Long) = graph.playback.seekTo(ms)
    fun seekToIndex(index: Int) = graph.playback.seekToIndex(index)
    fun toggleShuffle() = graph.playback.setShuffle(!graph.playback.state.value.shuffle)
    fun cycleRepeat() = graph.playback.cycleRepeatMode()
    fun setSpeed(speed: Float) = graph.playback.setSpeed(speed)
    fun addToQueue(track: Track) = graph.playback.addToQueue(track)
    fun playNext(track: Track) = graph.playback.playNext(track)
    fun removeFromQueue(index: Int) = graph.playback.removeAt(index)
    fun moveInQueue(from: Int, to: Int) = graph.playback.move(from, to)
    fun clearQueue() = graph.playback.clearQueue()
    fun startSleepTimer(minutes: Int) = graph.playback.startSleepTimer(minutes)
    fun cancelSleepTimer() = graph.playback.cancelSleepTimer()

    fun setSpeedPreset(speed: Float) {
        graph.playback.setSpeed(speed)
        graph.settings.update { it.copy(playbackSpeed = speed) }
    }

    // ---------- Поиск ----------

    fun onSearchQueryChanged(query: String) {
        _search.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _search.update { SearchUiState() }
            return
        }
        searchJob = viewModelScope.launch {
            _search.update { it.copy(loading = true, error = null) }
            delay(250)
            val local = graph.library.searchLocal(query)
            // YouTube Music теперь на InnerTube: поиск идёт без ключа и сразу
            // возвращает играбельные треки.
            val remote = runCatching { graph.youtube.search(query, REMOTE_SEARCH_LIMIT) }
            _search.update {
                it.copy(
                    local = local,
                    remote = remote.getOrDefault(emptyList()),
                    loading = false,
                    error = remote.exceptionOrNull()?.let { error ->
                        graph.youtube.lastError ?: error.message
                    },
                )
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = SearchUiState()
    }

    // ---------- Моя волна ----------

    private val _wave = MutableStateFlow(WaveUiState())
    val wave: StateFlow<WaveUiState> = _wave.asStateFlow()
    private var waveJob: Job? = null

    fun setWaveSource(mode: WaveSourceMode) {
        _wave.update { it.copy(filters = it.filters.copy(source = mode)) }
        refreshWave()
    }

    fun setWaveMood(mood: WaveMood) {
        _wave.update { it.copy(filters = it.filters.copy(mood = mood)) }
        refreshWave()
    }

    fun setWaveSort(sort: WaveSort) {
        _wave.update { it.copy(sort = sort) }
        // Сортировка не требует сети: переставляем уже собранную волну.
        _wave.update { state -> state.copy(tracks = sortWave(state.tracks, state.sort)) }
    }

    fun refreshWave() {
        waveJob?.cancel()
        waveSeenIds.clear()
        waveSeenKeys.clear()
        _wave.update { it.copy(loading = true, loadingMore = false, error = null) }
        waveJob = viewModelScope.launch {
            delay(250)
            val state = _wave.value
            val result = runCatching { buildWave(state) }
            _wave.update { current ->
                val data = result.getOrNull()
                current.copy(
                    tracks = data?.first ?: current.tracks,
                    seedLabel = data?.second ?: current.seedLabel,
                    loading = false,
                    error = result.exceptionOrNull()?.message
                        ?: if (data == null || data.first.isEmpty()) "Не удалось собрать волну" else null,
                    generatedAt = System.currentTimeMillis(),
                )
            }
            result.getOrNull()?.first?.let { markWaveSeen(it) }
        }
    }

    /**
     * «Моя волна» бесконечная: докидывает следующую порцию рекомендаций.
     * Уже показанные треки исключаются (и по id, и по artist|title), поэтому
     * повторяться подборка не будет.
     */
    fun loadMoreWave() {
        val current = _wave.value
        if (current.loading || current.loadingMore) return
        _wave.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val state = _wave.value
            val result = runCatching {
                buildWave(
                    state = state,
                    excludeIds = waveSeenIds.toSet(),
                    excludeKeys = waveSeenKeys.toSet(),
                    limit = WAVE_LOAD_MORE_LIMIT,
                )
            }
            val more = result.getOrNull()?.first ?: emptyList()
            _wave.update { latest ->
                latest.copy(
                    tracks = latest.tracks + more,
                    loadingMore = false,
                    error = result.exceptionOrNull()?.message,
                )
            }
            markWaveSeen(more)
        }
    }

    private val waveSeenIds = mutableSetOf<String>()
    private val waveSeenKeys = mutableSetOf<String>()
    private var waveQueryCursor = 0

    private fun markWaveSeen(tracks: List<Track>) {
        tracks.forEach { track ->
            waveSeenIds += track.id
            waveSeenKeys += waveKey(track)
        }
    }

    fun playWave() {
        val tracks = _wave.value.tracks
        if (tracks.isEmpty()) return
        graph.playback.playQueue(tracks, 0)
        tracks.firstOrNull { it.source == Source.YOUTUBE }?.let(::appendYouTubeRelated)
    }

    fun enqueueWave() {
        val tracks = _wave.value.tracks
        if (tracks.isEmpty()) return
        graph.playback.appendToQueue(tracks)
    }

    private suspend fun buildWave(
        state: WaveUiState,
        excludeIds: Set<String> = emptySet(),
        excludeKeys: Set<String> = emptySet(),
        limit: Int = WAVE_LIMIT,
    ): Pair<List<Track>, String> {
        val sourceMode = state.filters.source
        val allLocal = graph.library.tracks.value.filter {
            (it.source == Source.LOCAL || it.source == Source.YOUTUBE) &&
                it.playable && !it.uri.isNullOrBlank()
        }.filter { isWaveMusic(it, strict = false) }

        val favorites = if (state.filters.includeFavorites) favoriteTracks() else emptyList()
        val favoriteIds = favorites.map { it.id }.toSet()
        val favoriteArtists = favorites.map { it.artist.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

        val artistPopularity = graph.library.tracks.value
            .map { it.artist.trim().lowercase() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()

        // Вес: любимые треки/исполнители и часто слушаемые артисты получают
        // больше шансов, локальные файлы — небольшой бонус перед стримингом.
        fun weightOf(track: Track): Double {
            var weight = 1.0
            val artist = track.artist.trim().lowercase()
            if (track.id in favoriteIds) weight += 2.5
            if (artist in favoriteArtists) weight += 1.8
            weight += minOf(artistPopularity[artist] ?: 0, 6) * 0.15
            if (track.source == Source.LOCAL) weight += 0.3
            if (track.artist.isBlank()) weight -= 0.2
            return weight.coerceAtLeast(0.1)
        }

        val localPool = if (sourceMode == WaveSourceMode.ONLINE) {
            emptyList()
        } else {
            weightedShuffle(allLocal, ::weightOf)
        }

        val online = if (sourceMode == WaveSourceMode.LOCAL) {
            emptyList()
        } else {
            // Каждая порция — новый запрос: любимые артисты, частые артисты, общие миксы.
            val queries = buildList {
                if (state.filters.mood != WaveMood.ANY && state.filters.mood.query.isNotBlank()) {
                    add(state.filters.mood.query)
                }
                favoriteArtists.take(3).forEach { add("$it mix") }
                artistPopularity.entries
                    .sortedByDescending { it.value }
                    .take(4)
                    .forEach { add("${it.key} mix") }
                add("new music mix")
                add("chill mix")
            }
            val query = queries[waveQueryCursor++ % queries.size]
            graph.youtube.search(query, 30)
                .filter { it.playable }
                .filter { isWaveMusic(it, strict = true) }
                .let { weightedShuffle(it, ::weightOf) }
        }

        val merged = mergeWave(localPool, online, limit * 2)
        // Исключаем всё, что уже показано: и по id, и по «исполнитель|название».
        val filtered = dedupeWave(merged).filter { track ->
            track.id !in excludeIds && waveKey(track) !in excludeKeys
        }
        val diversified = diversifyWave(filtered, limit)
        val sorted = sortWave(diversified, state.sort)
        val label = when {
            state.filters.mood != WaveMood.ANY ->
                "Настроение: ${state.filters.mood.label}"
            favoriteArtists.isNotEmpty() ->
                "На основе ${favoriteArtists.size} любимых исполнителей"
            else -> "Только музыка: без видео и концертов"
        }
        return sorted to label
    }

    private fun sortWave(tracks: List<Track>, sort: WaveSort): List<Track> = when (sort) {
        WaveSort.FOR_YOU -> tracks
        WaveSort.TITLE -> tracks.sortedBy { it.title.lowercase() }
        WaveSort.ARTIST -> tracks.sortedBy { it.artist.lowercase() }
        WaveSort.DURATION -> tracks.sortedBy { it.durationMs }
        WaveSort.SHUFFLE -> tracks.shuffled()
    }

    // ---------- Моя волна: только музыка ----------

    /** Маркеры видеоконтента в названии: клипы, концерты, интервью, стримы. */
    private val waveVideoMarkers = listOf(
        "official video", "official music video", "music video", "video clip",
        "официальный клип", "клип", "lyric video", "lyrics video", "visualizer",
        "full album", "полный альбом", "live concert", "концерт", "live stream",
        "live performance", "interview", "интервью", "podcast", "подкаст",
        "shorts", "#shorts", "making of", "backstage", "reaction", "trailer", "тизер",
    )

    /**
     * Пропускает только музыку: отсекает клипы/концерты/интервью по названию,
     * слишком короткие ролики и длинные записи (концерты, «full album», миксы).
     * Для онлайн-результатов фильтр строже, локальные файлы не режем по верхней границе.
     */
    private fun isWaveMusic(track: Track, strict: Boolean): Boolean {
        val duration = track.durationMs
        if (strict) {
            if (duration in 1 until WAVE_MIN_TRACK_MS) return false
            if (duration > WAVE_MAX_TRACK_MS) return false
        } else if (duration in 1 until WAVE_MIN_LOCAL_TRACK_MS) {
            return false
        }
        val title = track.title.lowercase()
        return waveVideoMarkers.none { title.contains(it) }
    }

    /** Ключ для дедупликации: исполнитель + название без скобочных пометок. */
    private fun waveKey(track: Track): String {
        val title = track.title.lowercase()
            .replace(PARENS_REGEX_WAVE, " ")
            .replace(WHITESPACE_REGEX_WAVE, " ")
            .trim()
        return "${track.artist.lowercase().trim()}|$title"
    }

    private fun dedupeWave(tracks: List<Track>): List<Track> {
        val ids = HashSet<String>()
        val keys = HashSet<String>()
        return tracks.filter { track ->
            if (!ids.add(track.id)) return@filter false
            keys.add(waveKey(track))
        }
    }

    /** Взвешенный случайный порядок: без повторов, вес из [weightOf]. */
    private fun weightedShuffle(
        tracks: List<Track>,
        weightOf: (Track) -> Double,
    ): List<Track> {
        val remaining = tracks
            .map { it to weightOf(it).coerceAtLeast(0.1) }
            .toMutableList()
        val result = ArrayList<Track>(remaining.size)
        while (remaining.isNotEmpty()) {
            val total = remaining.sumOf { it.second }
            var roll = Random.nextDouble(total)
            var index = 0
            for (i in remaining.indices) {
                roll -= remaining[i].second
                if (roll <= 0.0) {
                    index = i
                    break
                }
            }
            result += remaining.removeAt(index).first
        }
        return result
    }

    /** Смешивает локальную фонотеку и онлайн в пропорции ~60/40. */
    private fun mergeWave(local: List<Track>, online: List<Track>, limit: Int): List<Track> {
        val out = ArrayList<Track>(limit)
        var localIndex = 0
        var onlineIndex = 0
        while (out.size < limit && (localIndex < local.size || onlineIndex < online.size)) {
            val takeLocal = when {
                localIndex >= local.size -> false
                onlineIndex >= online.size -> true
                else -> out.size % 5 < 3
            }
            if (takeLocal) {
                out += local[localIndex++]
            } else {
                out += online[onlineIndex++]
            }
        }
        return out
    }

    /**
     * Не больше двух треков одного исполнителя в начале волны — иначе подборка
     * превращается в один альбом. Остальные треки артиста уходят в конец.
     */
    private fun diversifyWave(tracks: List<Track>, limit: Int): List<Track> {
        val out = ArrayList<Track>(limit)
        val deferred = ArrayList<Track>()
        val perArtist = HashMap<String, Int>()
        for (track in tracks) {
            if (out.size >= limit) break
            val artist = track.artist.lowercase().trim()
            val count = perArtist[artist] ?: 0
            if (count >= 2) {
                deferred += track
                continue
            }
            out += track
            perArtist[artist] = count + 1
        }
        var index = 0
        while (out.size < limit && index < deferred.size) {
            out += deferred[index++]
        }
        return out
    }

    private fun interleave(local: List<Track>, online: List<Track>, limit: Int): List<Track> {
        val out = ArrayList<Track>(limit)
        var localIndex = 0
        var onlineIndex = 0
        while (out.size < limit && (localIndex < local.size || onlineIndex < online.size)) {
            if (localIndex < local.size && (out.size % 2 == 0 || onlineIndex >= online.size)) {
                out += local[localIndex++]
            } else if (onlineIndex < online.size) {
                out += online[onlineIndex++]
            }
        }
        return out
    }

    // ---------- Подключения ----------

    fun refreshConnections() {
        _connections.value = graph.sources.sources.map { source ->
            ConnectionState(
                source = source.source,
                connected = source.isConnected(),
                accountName = source.accountName(),
                available = source.isConfigured(),
                hint = source.setupHint(),
            )
        }
    }

    /** Открывает браузер для OAuth-входа. Возвращает false, если источник не настроен. */
    fun connect(source: Source): Boolean {
        val src = graph.sources.bySource(source) ?: return false
        val url = src.authUrl() ?: return false
        AppLinks.open(graph.appContext, url)
        return true
    }

    fun disconnect(source: Source) {
        graph.sources.bySource(source)?.disconnect()
        refreshConnections()
    }

    /** Подтянуть плейлисты и треки аккаунта в локальный кэш. */
    fun syncSource(source: Source) {
        val src = graph.sources.bySource(source) ?: return
        if (!src.isConnected()) return
        _syncing.update { it + source }
        viewModelScope.launch {
            runCatching {
                val remotePlaylists = src.libraryPlaylists()
                graph.library.cacheRemotePlaylists(remotePlaylists)
                val collected = mutableListOf<Track>()
                val tracksByPlaylist = mutableMapOf<String, List<Track>>()
                var imported = 0
                remotePlaylists.take(25).forEach { playlist ->
                    val remoteId = playlist.remoteId ?: return@forEach
                    val tracks = runCatching { src.playlistTracks(remoteId) }
                        .getOrNull()
                        .orEmpty()
                    if (tracks.isNotEmpty()) {
                        tracksByPlaylist[playlist.id] = tracks
                        collected += tracks
                        imported++
                    }
                }
                graph.library.cacheRemoteTracks(collected)
                // Привязываем треки именно к своему плейлисту, а не все подряд.
                tracksByPlaylist.forEach { (playlistId, tracks) ->
                    graph.library.addToPlaylist(playlistId, tracks.map { it.id })
                }
                if (source == Source.YOUTUBE) {
                    _google.update { it.copy(playlistsImported = imported) }
                }
            }
            refreshConnections()
            _syncing.update { it - source }
        }
    }

    // ---------- Google-аккаунт (InnerTube cookie из WebView) ----------

    private val _google = MutableStateFlow(GoogleAccountUiState())
    val googleAccount: StateFlow<GoogleAccountUiState> = _google.asStateFlow()

    fun refreshGoogleAccount() {
        _google.update {
            it.copy(
                configured = true,
                connected = graph.youtube.isGoogleConnected(),
                accountName = graph.youtube.accountName(),
                avatarUrl = app.sonora.data.remote.youtube.innertube.InnerTubeSession.accountAvatarUrl,
                error = null,
            )
        }
    }

    /**
     * Завершает вход из WebView: сохраняет cookie/visitorData/dataSyncId и
     * подтягивает плейлисты аккаунта. Cookie никогда не покидает устройство.
     */
    fun completeGoogleLogin(
        cookie: String,
        visitorData: String?,
        dataSyncId: String?,
        authUser: String,
    ) {
        if (cookie.isBlank() ||
            (!cookie.contains("SAPISID") && !cookie.contains("PAPISID"))
        ) {
            _google.update {
                it.copy(error = "В cookie нет SAPISID — вход не завершён. Попробуйте ещё раз.")
            }
            return
        }
        app.sonora.data.remote.youtube.innertube.InnerTubeSession.save(
            secure = graph.secure,
            cookie = cookie,
            visitorData = visitorData,
            dataSyncId = dataSyncId,
            authUser = authUser,
            accountName = null,
            accountAvatarUrl = null,
        )
        refreshGoogleAccount()
        _google.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val info = runCatching { graph.youtube.accountInfo() }.getOrNull()
            app.sonora.data.remote.youtube.innertube.InnerTubeSession.updateProfile(
                graph.secure,
                info?.name ?: "Google-аккаунт",
                info?.avatarUrl,
            )
            refreshGoogleAccount()
            _google.update { it.copy(busy = false) }
            syncSource(Source.YOUTUBE)
        }
    }

    fun logoutGoogle() {
        app.sonora.data.remote.youtube.innertube.InnerTubeSession.clear(graph.secure)
        runCatching {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        }
        _google.value = GoogleAccountUiState()
        refreshConnections()
    }

    fun syncGoogleAccount() = syncSource(Source.YOUTUBE)

    /** Добавить избранное устройства в конец очереди. */
    fun enqueueFavorites() {
        val tracks = favoriteTracks()
        if (tracks.isNotEmpty()) graph.playback.appendToQueue(tracks)
    }

    /** Добавить YouTube-плейлист (включая сохранённые) в очередь. */
    fun enqueuePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            var tracks = playlistTracks(playlist)
            if (tracks.isEmpty() && playlist.remoteId != null) {
                tracks = runCatching { graph.youtube.playlistTracks(playlist.remoteId) }
                    .getOrDefault(emptyList())
            }
            if (tracks.isNotEmpty()) {
                graph.library.cacheRemoteTracks(tracks)
                graph.library.addToPlaylist(playlist.id, tracks.map { it.id })
                graph.playback.appendToQueue(tracks)
            }
        }
    }

    /** Добавить «Понравившиеся» YouTube в очередь (как Saved Songs в Spotify). */
    fun enqueueLikedSongs() {
        viewModelScope.launch {
            val tracks = graph.youtube.likedSongs()
                .filter { it.playable && !it.uri.isNullOrBlank() }
            if (tracks.isNotEmpty()) {
                graph.library.cacheRemoteTracks(tracks)
                graph.playback.appendToQueue(tracks)
            }
        }
    }

    // ---------- Импорт плейлиста YouTube по ссылке (InnerTube) ----------

    private val _playlistImport = MutableStateFlow<String?>(null)

    /** Результат импорта плейлиста: текст ошибки или null. */
    val playlistImportError: StateFlow<String?> = _playlistImport.asStateFlow()

    /**
     * Поиск/плейлисты берутся анонимно, поэтому импорт работает по публичной ссылке:
     * `music.youtube.com/playlist?list=…`, `youtube.com/watch?v=…&list=…` или голый id.
     */
    fun importYouTubePlaylist(url: String) {
        if (url.isBlank()) return
        _playlistImport.value = null
        viewModelScope.launch {
            val details = runCatching { graph.youtube.importPlaylistDetails(url) }.getOrNull()
            if (details == null || details.tracks.isEmpty()) {
                _playlistImport.value = graph.youtube.lastError
                    ?: "В плейлисте не нашлось треков."
                return@launch
            }
            graph.library.cacheRemoteTracks(details.tracks)
            graph.library.cacheRemotePlaylists(
                listOf(
                    Playlist(
                        id = "youtube:playlist:${details.id}",
                        name = details.title,
                        description = details.subtitle,
                        artworkUrl = details.artworkUrl,
                        source = Source.YOUTUBE,
                        remoteId = details.id,
                        trackIds = details.tracks.map { it.id },
                        editable = false,
                    ),
                ),
            )
        }
    }

    // ---------- Импорт списка треков из файла (Spotify/Apple/YouTube) ----------

    private val _playlistFileImport = MutableStateFlow(PlaylistImportUiState())
    val playlistFileImport: StateFlow<PlaylistImportUiState> = _playlistFileImport.asStateFlow()

    fun dismissPlaylistImport() {
        if (!_playlistFileImport.value.importing) {
            _playlistFileImport.value = PlaylistImportUiState()
        }
    }

    /**
     * Читает CSV/JSON/M3U/TXT со списком треков, находит совпадения в библиотеке
     * и InnerTube и создаёт из них обычный плейлист Sonora.
     */
    fun importPlaylistFile(uri: Uri) {
        if (_playlistFileImport.value.importing) return
        _playlistFileImport.value = PlaylistImportUiState(importing = true)
        viewModelScope.launch {
            val data = try {
                graph.playlistImporter.read(uri)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _playlistFileImport.value = PlaylistImportUiState(
                    error = error.message ?: "Не удалось прочитать файл",
                    done = true,
                )
                return@launch
            }
            val entries = data.entries.take(MAX_PLAYLIST_IMPORT)
            if (entries.isEmpty()) {
                _playlistFileImport.value = PlaylistImportUiState(
                    error = "В файле не нашлось треков",
                    done = true,
                )
                return@launch
            }
            _playlistFileImport.value = PlaylistImportUiState(
                importing = true,
                total = entries.size,
            )
            val found = LinkedHashMap<String, Track>()
            var notFound = 0
            entries.forEachIndexed { index, entry ->
                val track = resolveImportedEntry(entry)
                if (track != null) {
                    found[track.id] = track
                } else {
                    notFound++
                }
                _playlistFileImport.value = _playlistFileImport.value.copy(
                    processed = index + 1,
                    matched = found.size,
                    notFound = notFound,
                )
            }
            if (found.isEmpty()) {
                _playlistFileImport.value = PlaylistImportUiState(
                    notFound = notFound,
                    warnings = data.warnings,
                    error = "Не удалось найти ни один трек. Проверьте названия и исполнителей.",
                    done = true,
                )
                return@launch
            }
            val remote = found.values.filter { it.source != Source.LOCAL }
            if (remote.isNotEmpty()) {
                graph.library.cacheRemoteTracks(remote)
            }
            val baseName = data.fileName.substringBeforeLast('.').ifBlank { "плейлист" }
            val playlist = graph.library.createPlaylist(
                name = "Импорт: $baseName".take(80),
                trackIds = found.keys.toList(),
            )
            _playlistFileImport.value = PlaylistImportUiState(
                processed = entries.size,
                total = entries.size,
                matched = found.size,
                notFound = notFound,
                playlistName = playlist.name,
                warnings = data.warnings,
                done = true,
            )
        }
    }

    private suspend fun resolveImportedEntry(entry: ImportedTrackEntry): Track? {
        val local = graph.library.tracks.value
            .filter { it.playable }
            .maxByOrNull { matchScore(it, entry) }
            ?.takeIf { matchScore(it, entry) >= 3 }
        if (local != null) return local

        val videoId = entry.videoId?.takeIf { YOUTUBE_ID.matches(it) }
            ?: entry.url?.let(::youtubeIdFromUrl)
        if (videoId != null) {
            return trackFromEntry(videoId, entry)
        }

        val query = listOfNotNull(entry.artist, entry.title)
            .joinToString(" ")
            .ifBlank { entry.title }
        val results = runCatching { graph.youtube.search(query, 5) }
            .getOrDefault(emptyList())
            .filter { it.playable }
        return results
            .maxByOrNull { matchScore(it, entry) }
            ?.takeIf { matchScore(it, entry) >= 3 }
    }

    private fun matchScore(track: Track, entry: ImportedTrackEntry): Int {
        val title = normalizeMatch(track.title)
        val wantedTitle = normalizeMatch(entry.title)
        if (title.isBlank() || wantedTitle.isBlank()) return 0
        var score = when {
            title == wantedTitle -> 3
            title.contains(wantedTitle) || wantedTitle.contains(title) -> 1
            else -> 0
        }
        val artist = normalizeMatch(track.artist)
        val wantedArtist = normalizeMatch(entry.artist)
        if (wantedArtist.isNotBlank() && artist.isNotBlank()) {
            score += when {
                artist == wantedArtist -> 2
                artist.contains(wantedArtist) || wantedArtist.contains(artist) -> 1
                else -> 0
            }
        }
        val duration = entry.durationMs
        if (duration != null && duration > 0 && track.durationMs > 0 &&
            kotlin.math.abs(duration - track.durationMs) <= 10_000
        ) {
            score++
        }
        return score
    }

    private fun normalizeMatch(value: String?): String =
        value.orEmpty()
            .lowercase()
            .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
            .replace(Regex("""[^a-zа-я0-9]+"""), " ")
            .trim()

    private fun trackFromEntry(videoId: String, entry: ImportedTrackEntry): Track = Track(
        id = "youtube:$videoId",
        title = entry.title,
        artist = entry.artist ?: "YouTube Music",
        album = entry.album,
        durationMs = entry.durationMs ?: 0L,
        uri = "innertube:$videoId",
        source = Source.YOUTUBE,
        playable = true,
        downloadAllowed = true,
        deepLink = "https://music.youtube.com/watch?v=$videoId",
    )

    private fun youtubeIdFromUrl(url: String): String? {
        val patterns = listOf(
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        )
        for (pattern in patterns) {
            pattern.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    // ---------- Импорт библиотеки из Google Takeout ----------
    private val _takeout = MutableStateFlow(TakeoutUiState())
    val takeout: StateFlow<TakeoutUiState> = _takeout.asStateFlow()

    /** Импорт CSV/ZIP из Google Takeout: только метаданные, воспроизведение — в YouTube Music. */
    fun importTakeout(uri: Uri) {
        if (_takeout.value.importing) return
        _takeout.value = TakeoutUiState(importing = true)
        viewModelScope.launch {
            val result = graph.takeout.import(uri)
            if (result.tracks.isNotEmpty()) {
                graph.library.cacheRemoteTracks(result.tracks)
            }
            if (result.playlists.isNotEmpty()) {
                graph.library.cacheRemotePlaylists(result.playlists)
            }
            _takeout.value = TakeoutUiState(
                importing = false,
                importedTracks = result.tracks.size,
                importedPlaylists = result.playlists.size,
                warnings = result.warnings,
                error = if (result.tracks.isEmpty() && result.playlists.isEmpty()) {
                    result.warnings.firstOrNull() ?: "В файле не нашлось треков"
                } else {
                    null
                },
            )
        }
    }

    // ---------- Файлы ----------

    private val _fileImport = MutableStateFlow(FileImportUiState())
    val fileImport: StateFlow<FileImportUiState> = _fileImport.asStateFlow()

    fun dismissFileImport() {
        _fileImport.value = FileImportUiState()
    }

    fun importFile(uri: Uri) = importMusicFiles(listOf(uri))

    /**
     * Импорт музыки с устройства: выбранные файлы копируются в папку приложения,
     * затем библиотека пересканируется и (если включено) подтягиваются метаданные.
     * Аналог «Local files» в Spotify, но файлы хранятся внутри приложения.
     */
    fun importMusicFiles(uris: List<Uri>) {
        if (uris.isEmpty() || _fileImport.value.importing) return
        _fileImport.value = FileImportUiState(importing = true)
        viewModelScope.launch {
            var imported = 0
            var failed = 0
            uris.forEach { uri ->
                val result = runCatching { graph.downloads.importFromUri(uri) }.getOrNull()
                if (result?.isSuccess == true) imported++ else failed++
            }
            if (imported > 0) {
                graph.library.scanLocal()
                if (graph.settings.current.enrichMetadataOnScan) enrichLibrary()
            }
            _fileImport.value = FileImportUiState(
                imported = imported,
                failed = failed,
                done = true,
            )
        }
    }

    fun deleteDownload(fileName: String) {
        viewModelScope.launch { graph.downloads.delete(fileName) }
    }

    // ---------- Настройки ----------

    fun updateSettings(transform: (AppSettings) -> AppSettings) = graph.settings.update(transform)

    fun setThemeMode(mode: ThemeMode) = updateSettings { it.copy(themeMode = mode) }
    fun setAccent(accent: Accent) = updateSettings { it.copy(accent = accent) }
    fun setDynamicColor(enabled: Boolean) = updateSettings { it.copy(dynamicColor = enabled) }
    fun setGapless(enabled: Boolean) = updateSettings { it.copy(gapless = enabled) }
    fun setSkipSilence(enabled: Boolean) = updateSettings { it.copy(skipSilence = enabled) }
    fun setScanOnStart(enabled: Boolean) = updateSettings { it.copy(scanOnStart = enabled) }
    fun setDownloadWifiOnly(enabled: Boolean) = updateSettings { it.copy(downloadWifiOnly = enabled) }

    fun setEqEnabled(enabled: Boolean) {
        updateSettings { it.copy(eqEnabled = enabled) }
        app.sonora.playback.AudioEffectBus.enabled.value = enabled
    }

    fun setEqBand(index: Int, gain: Float) {
        val bands = graph.settings.current.eqBands.toMutableList()
        if (index !in bands.indices) return
        bands[index] = gain
        updateSettings { it.copy(eqBands = bands) }
        app.sonora.playback.AudioEffectBus.gains.value = bands
    }

    fun setEqBands(bands: List<Float>) {
        updateSettings { it.copy(eqBands = bands) }
        app.sonora.playback.AudioEffectBus.gains.value = bands
    }

    fun setSpotifyClientId(value: String) {
        updateSettings { it.copy(spotifyClientId = value.trim()) }
        refreshConnections()
    }

    fun setSoundCloudClientId(value: String) {
        updateSettings { it.copy(soundcloudClientId = value.trim()) }
        refreshConnections()
    }

    fun setJamendoClientId(value: String) {
        updateSettings { it.copy(jamendoClientId = value.trim()) }
    }

    fun setEnrichOnScan(enabled: Boolean) {
        updateSettings { it.copy(enrichMetadataOnScan = enabled) }
    }

    fun setAutoQueue(enabled: Boolean) {
        updateSettings { it.copy(autoQueue = enabled) }
    }

    fun setInfiniteQueue(enabled: Boolean) {
        updateSettings { it.copy(infiniteQueue = enabled) }
    }

    // ---------- Открытые каталоги: поиск и скачивание ----------

    private val _discover = MutableStateFlow(DiscoverUiState(source = Source.ARCHIVE))
    val discover: StateFlow<DiscoverUiState> = _discover.asStateFlow()

    private var discoverJob: Job? = null

    /** Все каталоги-участники (Archive.org, Jamendo). */
    fun discoverSources(): List<Source> = graph.discover.chips().map { it.source }

    fun discoverHint(source: Source): String? = graph.discover.bySource(source)?.setupHint()

    fun setDiscoverSource(source: Source) {
        if (_discover.value.source == source) return
        _discover.update { it.copy(source = source, results = emptyList(), error = null) }
        if (_discover.value.query.isNotBlank()) {
            onDiscoverQueryChanged(_discover.value.query)
        }
    }

    fun onDiscoverQueryChanged(query: String) {
        _discover.update { it.copy(query = query) }
        discoverJob?.cancel()
        if (query.isBlank()) {
            _discover.update { it.copy(results = emptyList(), loading = false, error = null) }
            return
        }
        discoverJob = viewModelScope.launch {
            _discover.update { it.copy(loading = true, error = null) }
            delay(300)
            val source = _discover.value.source
            val results = graph.discover.search(source, query)
            _discover.update {
                it.copy(
                    results = results,
                    loading = false,
                    error = if (results.isEmpty()) "Ничего не найдено в ${source.title}" else null,
                )
            }
        }
    }

    /**
     * Скачивает трек из открытого каталога в папку приложения и сразу добавляет
     * его в фонотеку (пересканирование папки загрузок).
     */
    fun downloadDiscoverTrack(track: Track) {
        val url = track.uri ?: return
        if (_discover.value.downloadingIds.contains(track.id)) return
        _discover.update { it.copy(downloadingIds = it.downloadingIds + track.id) }
        viewModelScope.launch {
            val fileName = track.extra["fileName"]
                ?: "${sanitizeFileName(track.artist)} - ${sanitizeFileName(track.title)}.mp3"
            val result = graph.downloads.download(url, fileName)
            result.onSuccess { file ->
                val cover = track.artworkUrl?.let { artwork ->
                    graph.downloads.downloadArtwork(artwork, track.id)
                }
                val local = track.copy(
                    id = "file:${file.absolutePath}",
                    uri = file.toURI().toString(),
                    artworkUri = cover?.toURI()?.toString(),
                    artworkUrl = track.artworkUrl,
                    source = Source.LOCAL,
                    playable = true,
                    downloadAllowed = true,
                    deepLink = null,
                    extra = track.extra + mapOf(
                        "downloadedFrom" to track.source.name,
                        "downloadedTrackId" to track.id,
                        "downloadedAt" to System.currentTimeMillis().toString(),
                    ),
                )
                graph.library.upsertLocalTracks(listOf(local))
                graph.library.scanLocal()
            }
            _discover.update {
                it.copy(
                    downloadingIds = it.downloadingIds - track.id,
                    downloadedIds = if (result.isSuccess) it.downloadedIds + track.id else it.downloadedIds,
                    error = result.exceptionOrNull()?.let { error -> "Не удалось скачать: ${error.message}" }
                        ?: it.error,
                )
            }
        }
    }

    fun openDownloadFolderHint(): String = graph.downloads.downloadDirectory().absolutePath

    private fun sanitizeFileName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60).ifBlank { "track" }

    // ---------- Онлайн-метаданные и текст песни ----------

    private val _enriching = MutableStateFlow(false)
    val enriching: StateFlow<Boolean> = _enriching.asStateFlow()

    /** null — обогащение не запущено; (готово, всего). */
    private val _enrichProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val enrichProgress: StateFlow<Pair<Int, Int>?> = _enrichProgress.asStateFlow()

    /**
     * Ищет в MusicBrainz/Cover Art Archive/LRCLIB альбом, год, обложку и текст
     * для локальных треков, у которых этих данных нет. Ничего не скачивает в файлы.
     */
    fun enrichLibrary() {
        if (_enriching.value) return
        viewModelScope.launch {
            val candidates = graph.library.metadataCandidates()
            if (candidates.isEmpty()) {
                _enrichProgress.value = 0 to 0
                refreshMetadataCacheStats()
                return@launch
            }
            _enriching.value = true
            try {
                var done = 0
                _enrichProgress.value = 0 to candidates.size
                val candidateIds = candidates.map { it.id }.toSet()
                for (track in candidates) {
                    val metadata = runCatching { graph.metadata.lookup(track) }.getOrNull()
                    if (metadata != null && !metadata.isEmpty) {
                        graph.library.updateTrackMetadata(track.id, metadata)
                    }
                    done++
                    _enrichProgress.value = done to candidates.size
                }
                // Отмечаем все попытки, чтобы неудачный сетевой запрос не
                // повторялся при каждом запуске. Повтор — через сутки.
                graph.library.markMetadataChecked(candidateIds)
                val refreshed = graph.library.tracks.value.filter { it.id in candidateIds }
                graph.playback.applyMetadata(refreshed)
                _enrichProgress.value = done to candidates.size
            } finally {
                _enriching.value = false
                refreshMetadataCacheStats()
            }
        }
    }

    /** Обогатить один трек (например, из карточки «Сейчас играет»). */
    fun enrichTrack(track: Track) {
        viewModelScope.launch {
            val metadata = runCatching { graph.metadata.lookup(track) }.getOrNull()
            if (metadata != null && !metadata.isEmpty) {
                graph.library.updateTrackMetadata(track.id, metadata)
            }
            graph.library.markMetadataChecked(listOf(track.id))
            graph.library.trackById(track.id)?.let { graph.playback.applyMetadata(listOf(it)) }
            refreshMetadataCacheStats()
        }
    }

    // ---------- Кэш метаданных ----------

    private val _metadataCacheSize = MutableStateFlow(0)
    val metadataCacheSize: StateFlow<Int> = _metadataCacheSize.asStateFlow()

    fun refreshMetadataCacheStats() {
        viewModelScope.launch {
            _metadataCacheSize.value = runCatching { graph.metadataCache.size() }.getOrDefault(0)
        }
    }

    fun clearMetadataCache() {
        viewModelScope.launch {
            runCatching { graph.metadataCache.clear() }
            graph.library.resetMetadataChecked()
            _metadataCacheSize.value = 0
        }
    }

    fun currentLyrics(): String? = graph.playback.state.value.current?.lyrics

    // ---------- Обучение новичков ----------

    private val _showOnboarding = MutableStateFlow(!graph.settings.current.onboardingDone)
    val showOnboarding: StateFlow<Boolean> = _showOnboarding.asStateFlow()

    fun finishOnboarding() {
        _showOnboarding.value = false
        graph.settings.update { it.copy(onboardingDone = true) }
    }

    /** Открыть руководство заново из настроек. */
    fun replayOnboarding() {
        _showOnboarding.value = true
        graph.settings.update { it.copy(onboardingDone = false) }
    }

    // ---------- Скачивание треков из открытых каталогов ----------

    private val _trackDownloads = MutableStateFlow<Map<String, String>>(emptyMap())
    val trackDownloads: StateFlow<Map<String, String>> = _trackDownloads.asStateFlow()

    /**
     * Сохраняет любой играемый трек в приватную папку и добавляет его в
     * локальную фонотеку. Для InnerTube используется MP4-поток InnerTubeX,
     * для открытых каталогов и превью — прямой URL.
     */
    fun downloadTrack(track: Track) {
        if (track.source == Source.LOCAL) {
            _trackDownloads.update { it + (track.id to "Уже на устройстве") }
            return
        }
        if (track.source == Source.YOUTUBE || track.uri?.startsWith("innertube:") == true) {
            downloadYouTube(track)
            return
        }
        if (!track.playable || track.uri.isNullOrBlank() || !track.uri.startsWith("http")) {
            _trackDownloads.update { it + (track.id to "Недоступно") }
            return
        }
        if (isTrackDownloaded(track)) {
            _trackDownloads.update { it + (track.id to "Уже скачано") }
            return
        }
        if (_trackDownloads.value[track.id] == "Скачивание") return
        _trackDownloads.update { it + (track.id to "Скачивание") }
        viewModelScope.launch {
            val url = track.uri ?: return@launch
            val fileName = downloadFileName(track)
            graph.downloads.download(
                url = url,
                suggestedFileName = fileName,
                mime = track.extra["mime"],
            )
                .onSuccess { file ->
                    val metadata = runCatching { graph.metadata.lookup(track) }.getOrNull()
                    val coverUrl = metadata?.coverUrl ?: track.artworkUrl
                    val cover = coverUrl?.let { graph.downloads.downloadArtwork(it, track.id) }
                    val artistImage = metadata?.artistImageUrl?.let { url ->
                        graph.downloads.downloadArtwork(
                            url,
                            "artist_${ArtistNames.key(metadata.artist ?: track.artist)}",
                        )
                    }
                    val local = track.copy(
                        id = "file:${file.absolutePath}",
                        uri = file.toURI().toString(),
                        artist = if (ArtistNames.isSuspicious(track.artist)) {
                            ArtistNames.clean(metadata?.artist ?: track.artist)
                        } else {
                            ArtistNames.clean(track.artist)
                        },
                        album = track.album ?: metadata?.album,
                        artworkUri = cover?.toURI()?.toString(),
                        artworkUrl = coverUrl,
                        source = Source.LOCAL,
                        playable = true,
                        downloadAllowed = true,
                        deepLink = null,
                        extra = track.extra + buildMap {
                            put("downloadedFrom", track.source.name)
                            put("downloadedTrackId", track.id)
                            put("downloadedAt", System.currentTimeMillis().toString())
                            metadata?.artistImageUrl?.let { put("artistImageUrl", it) }
                            artistImage?.let { put("artistImageUri", it.toURI().toString()) }
                            metadata?.artistBio?.let { put("artistBio", it) }
                            metadata?.genre?.let { put("genre", it) }
                            metadata?.provider?.let { put("metadataProvider", it) }
                        },
                    )
                    graph.library.upsertLocalTracks(listOf(local))
                    graph.library.scanLocal()
                    _trackDownloads.update { it + (track.id to "Готово") }
                }
                .onFailure { error ->
                    _trackDownloads.update {
                        it + (track.id to "Ошибка: ${downloadErrorMessage(error)}")
                    }
                }
        }
    }

    private fun downloadFileName(track: Track): String {
        track.extra["fileName"]?.takeIf { it.isNotBlank() }?.let { return it }
        val extension = track.uri
            ?.substringBefore('?')
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it in DOWNLOAD_EXTENSIONS }
            ?: when {
                track.extra["mime"]?.contains("mp4", true) == true -> "m4a"
                track.extra["mime"]?.contains("webm", true) == true -> "webm"
                track.extra["mime"]?.contains("ogg", true) == true -> "ogg"
                else -> "mp3"
            }
        return "${sanitizeFileName(ArtistNames.clean(track.artist))} - " +
            "${sanitizeFileName(track.title)}.$extension"
    }

    // ---------- WARP-туннель ----------

    /** Состояние встроенного per-app WireGuard-туннеля (WARP). */
    val warpStatus: StateFlow<WarpStatus> = graph.warp.status

    private val _warpConfigs = MutableStateFlow(graph.warp.configs())
    val warpConfigs: StateFlow<List<WarpConfigEntry>> = _warpConfigs.asStateFlow()

    private val _warpAutoRotate = MutableStateFlow(graph.warp.isAutoRotate())
    val warpAutoRotate: StateFlow<Boolean> = _warpAutoRotate.asStateFlow()

    private val _warpExternalVpn = MutableStateFlow(graph.warp.externalVpnActive())
    val warpExternalVpn: StateFlow<Boolean> = _warpExternalVpn.asStateFlow()

    private val _warpError = MutableStateFlow<String?>(null)
    val warpError: StateFlow<String?> = _warpError.asStateFlow()

    fun clearWarpError() {
        _warpError.value = null
    }

    /** Перечитать список конфигов/настройки (после импорта, выбора, удаления). */
    fun refreshWarp() {
        _warpConfigs.value = graph.warp.configs()
        _warpAutoRotate.value = graph.warp.isAutoRotate()
        _warpExternalVpn.value = graph.warp.externalVpnActive()
    }

    /** Intent системного запроса разрешения VPN; null, если разрешение уже выдано. */
    fun warpVpnPermissionIntent(): Intent? = graph.warp.vpnPermissionIntent()

    fun hasWarpConfig(): Boolean = graph.warp.configs().isNotEmpty()

    fun warpConfigSummary(): WarpConfigSummary? = graph.warp.configSummary()

    fun importWarpConfig(uri: Uri) = importWarpConfigs(listOf(uri))

    fun importWarpConfigs(uris: List<Uri>) {
        viewModelScope.launch {
            val results = graph.warp.importConfigs(uris)
            val failure = results.firstOrNull { it.isFailure }?.exceptionOrNull()
            _warpError.value = failure?.let {
                "Не удалось импортировать конфигурацию: " +
                    (it.message ?: it.javaClass.simpleName)
            }
            refreshWarp()
        }
    }

    fun selectWarpConfig(id: String) {
        runCatching { graph.warp.selectConfig(id) }
        refreshWarp()
    }

    fun removeWarpConfig(id: String) {
        graph.warp.removeConfig(id)
        refreshWarp()
    }

    fun setWarpAutoRotate(enabled: Boolean) {
        graph.warp.setAutoRotate(enabled)
        _warpAutoRotate.value = enabled
    }

    fun setWarpAutoConnect(enabled: Boolean) {
        graph.settings.update { it.copy(warpAutoConnect = enabled) }
    }

    /**
     * Включает туннель, только если на устройстве нет чужого VPN/WARP.
     * Разрешение VPN должно быть уже выдано (иначе использовать [warpVpnPermissionIntent]).
     */
    fun startWarp(): Boolean {
        val result = graph.warp.startIfNoExternalVpn()
        if (result.isFailure) {
            _warpError.value = result.exceptionOrNull()?.message
        } else {
            _warpError.value = null
        }
        refreshWarp()
        return result.isSuccess
    }

    fun stopWarp() {
        graph.warp.stop()
        refreshWarp()
    }

    fun clearWarpConfig() {
        graph.warp.clearConfig()
        refreshWarp()
    }

    override fun onCleared() {
        searchJob?.cancel()
        discoverJob?.cancel()
        super.onCleared()
    }
}

class MainViewModelFactory(private val graph: AppGraph) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MainViewModel::class.java)) {
            "Unknown ViewModel: ${modelClass.name}"
        }
        return MainViewModel(graph) as T
    }
}

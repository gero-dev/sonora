package app.sonora.ui

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.sonora.AppGraph
import app.sonora.core.model.ArtistRelease
import app.sonora.core.model.ConnectionState
import app.sonora.core.model.Playlist
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.model.TrackMetadata
import app.sonora.core.settings.Accent
import app.sonora.core.settings.AppSettings
import app.sonora.core.settings.PlaybackMode
import app.sonora.core.settings.ThemeMode
import app.sonora.core.settings.WaveSettings
import app.sonora.core.util.AppLinks
import app.sonora.data.download.SaveStatus
import app.sonora.data.download.TrackSaveState
import app.sonora.data.metadata.ArtistNames
import app.sonora.data.metadata.MetadataFileEntry
import app.sonora.data.metadata.MetadataFiles
import app.sonora.data.library.LibraryRepository
import app.sonora.data.remote.youtube.innertube.StreamInfo
import app.sonora.data.wave.WaveEngine
import app.sonora.data.wave.WaveHistoryEntry
import app.sonora.net.warp.WarpConfigEntry
import app.sonora.net.warp.WarpConfigSummary
import app.sonora.net.warp.WarpState
import app.sonora.net.warp.WarpStatus
import app.sonora.data.import.ImportedTrackEntry
import app.sonora.playback.PlayerUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/**
 * Страница исполнителя: дискография (альбомы, синглы, EP) и популярные треки.
 * Локальная библиотека и InnerTube дополняют друг друга: если сети нет,
 * остаются альбомы из файлов устройства.
 */
data class ArtistUiState(
    val name: String = "",
    val loading: Boolean = false,
    val albums: List<ArtistRelease> = emptyList(),
    val topTracks: List<Track> = emptyList(),
    val error: String? = null,
)

/** Онлайн-альбом, открытый из дискографии исполнителя. */
data class RemoteAlbumUiState(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val year: Int? = null,
    val artworkUrl: String? = null,
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Прогресс сохранения плейлиста целиком: сколько треков предстоит сохранить,
 * сколько готово и сколько качается прямо сейчас.
 */
data class PlaylistSaveProgress(
    val total: Int = 0,
    val done: Int = 0,
    val active: Int = 0,
) {
    val pending: Int get() = (total - done - active).coerceAtLeast(0)
    val allDone: Boolean get() = total > 0 && done >= total
}

/** Год релиза в подписи альбома (`1991`, `2004`). */
private val YEAR_TEXT = Regex("""^(19|20)\d{2}$""")

/** Служебные слова в подписи релиза: их нельзя принимать за имя исполнителя. */
internal val RELEASE_TYPE_TOKENS = setOf(
    "album", "single", "ep", "compilation", "live", "soundtrack",
    "альбом", "сингл", "мини-альбом", "сборник",
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

/** Прогресс поиска обложек для локальных треков, у которых их нет. */
data class CoverSearchUiState(
    val searching: Boolean = false,
    val total: Int = 0,
    val processed: Int = 0,
    val found: Int = 0,
    val failed: Int = 0,
    val message: String? = null,
)

/** Результат импорта метаданных («телеметрии») из выбранных файлов. */
data class MetadataImportUiState(
    val importing: Boolean = false,
    val files: Int = 0,
    val matched: Int = 0,
    val updated: Int = 0,
    val failed: Int = 0,
    val message: String? = null,
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

/** Через сколько секунд прослушивания трек считается «дослушанным» для истории. */
private const val HISTORY_MIN_LISTEN_MS = 20_000L
/** Максимальная прибавка к прослушанному за один тик: перемотку не учитываем. */
private const val MAX_HISTORY_TICK_MS = 2_000L
/** Сколько треков проверять за один запуск «Найти пропущенные обложки». */
private const val MAX_COVER_SEARCH = 40

private val MATCH_PARENS_REGEX = Regex("""[\(\[][^\)\]]*[\)\]]""")
private val MATCH_FEAT_REGEX = Regex("""\b(feat\.?|ft\.?|featuring)\b.*$""")
private val MATCH_WHITESPACE_REGEX = Regex("""\s+""")

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
            // Свойства ViewModel объявлены ниже init-блока. Без приостановки
            // корутина на Main.immediate успевает выполниться до их инициализации
            // и падает с NPE (_google/_discover == null). delay(1) гарантирует,
            // что работа начнётся после завершения конструктора.
            delay(1)
            graph.library.initialize()
            repairSavedTrackTitles()
            // Миграция: старая версия могла пометить треки «проверенными» после сетевого
            // сбоя, и метаданные больше не запрашивались. Один раз сбрасываем отметки.
            if (graph.settings.current.metadataSchemaVersion < METADATA_SCHEMA_VERSION) {
                graph.library.resetMetadataChecked()
                graph.settings.update { it.copy(metadataSchemaVersion = METADATA_SCHEMA_VERSION) }
            }
            refreshConnections()
            refreshGoogleAccount()
            refreshMetadataCacheStats()
            // История прослушиваний нужна и «Моей волне», и экрану настроек.
            graph.playHistory.load()
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
        observePlaybackHistory()
        observeWarpKeepAlive()
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

    /**
     * История прослушиваний для «Моей волны». Трек считается дослушанным, если
     * звучал ≥20 секунд или половину длительности; иначе это пропуск — он снижает
     * вес артиста в рекомендациях.
     */
    private fun observePlaybackHistory() {
        viewModelScope.launch {
            var lastTrack: Track? = null
            var listenedMs = 0L
            var lastPositionMs = 0L
            graph.playback.state.collect { state ->
                val track = state.current
                if (track?.id != lastTrack?.id) {
                    val previous = lastTrack
                    if (previous != null && listenedMs > 0L &&
                        graph.settings.current.saveHistory
                    ) {
                        val completed = listenedMs >= maxOf(
                            HISTORY_MIN_LISTEN_MS,
                            (previous.durationMs * 0.5).toLong(),
                        )
                        val duration = previous.durationMs
                            .takeIf { it > 0L }
                            ?: state.durationMs
                        graph.playHistory.record(
                            track = previous,
                            completed = completed,
                            listenedMs = listenedMs,
                            durationMs = duration,
                        )
                    }
                    lastTrack = track
                    listenedMs = 0L
                    lastPositionMs = state.positionMs
                    // Текущий трек появляется в истории сразу, не дожидаясь
                    // переключения: так список «Недавние» ведёт себя как Spotify.
                    if (track != null && graph.settings.current.saveHistory &&
                        graph.playHistory.entries.value.lastOrNull()?.trackId != track.id
                    ) {
                        graph.playHistory.record(track, completed = false, listenedMs = 0L)
                    }
                } else if (track != null) {
                    val position = state.positionMs
                    if (state.isPlaying && position > lastPositionMs) {
                        // Перемотку вперёд не считаем прослушиванием: берём не больше 2 секунд за тик.
                        listenedMs += minOf(position - lastPositionMs, MAX_HISTORY_TICK_MS)
                    }
                    lastPositionMs = position
                }
            }
        }
    }

    // ---------- Режим проигрывания ----------

    /** Режим разрешает стриминг вообще (без учёта выключателя очереди). */
    private fun modeAllowsStreaming(): Boolean =
        graph.settings.current.playbackMode == PlaybackMode.STREAMING

    /** Стриминг разрешён и в очереди: режим «Онлайн + устройство» и включённый выключатель. */
    private fun streamingAllowed(): Boolean =
        modeAllowsStreaming() && graph.settings.current.streamingInQueue

    /** Трек играется без сети: это файл на устройстве. */
    fun offlinePlayable(track: Track): Boolean =
        track.source == Source.LOCAL && !track.uri.isNullOrBlank()

    /** Фильтр для пополнения очереди: учитывает режим, стриминг и запрет видео. */
    private fun queueable(tracks: List<Track>): List<Track> {
        val playable = tracks.filter {
            it.playable && !it.uri.isNullOrBlank() && !it.isVideoContent
        }
        return if (streamingAllowed()) playable else playable.filter(::offlinePlayable)
    }

    /**
     * Фильтр для прямого воспроизведения выбранного списка: выключатель «Стриминг
     * в очереди» намеренно не действует — он про автодополнение, а не про прямой запуск.
     */
    private fun playableNow(tracks: List<Track>): List<Track> {
        val playable = tracks.filter {
            it.playable && !it.uri.isNullOrBlank() && !it.isVideoContent
        }
        return if (modeAllowsStreaming()) playable else playable.filter(::offlinePlayable)
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(graph.appContext, message, Toast.LENGTH_SHORT).show() }
    }

    /** Sonora — музыкальный плеер: видео/клипы/подкасты не запускаем. */
    private fun blockedVideo(track: Track): Boolean {
        if (!track.isVideoContent) return false
        toast("Видео отключено: Sonora играет только музыку.")
        return true
    }

    /** Если включён режим «Только загруженное», стриминговый трек не запускаем. */
    private fun blockedByMode(track: Track): Boolean {
        if (track.source == Source.LOCAL || modeAllowsStreaming()) return false
        toast("Стриминг выключен. Включите его в плеере — долгое нажатие на кнопку перемешивания.")
        return true
    }

    /** Убирает из очереди стриминговые треки (текущий не трогаем). */
    private fun pruneStreamingQueue(showEmptyMessage: Boolean) {
        val removed = graph.playback.removeWhere { track -> offlinePlayable(track) }
        when {
            removed > 0 -> toast("Стриминг убран из очереди: $removed")
            showEmptyMessage -> {
                val current = graph.playback.state.value.current
                val onlyCurrent = current != null && !offlinePlayable(current)
                toast(
                    if (onlyCurrent) {
                        "Текущий стриминговый трек доигрывает, остальных в очереди нет"
                    } else {
                        "В очереди нет стриминговых треков"
                    },
                )
            }
        }
    }

    private suspend fun maybeExtendQueue(state: PlayerUiState) {
        if (!graph.settings.current.infiniteQueue) return
        val queue = state.queue
        if (queue.isEmpty()) return
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
        // Режим «Только загруженное»: в пул попадают лишь локальные файлы.
        val pool = queueable(graph.library.tracks.value)
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

    /**
     * Одноразовый ремонт названий сохранённых из стриминга треков: в старых
     * версиях сканер брал имя файла «Исполнитель - Название» (иногда ещё и
     * percent-encoded) вместо метаданных стриминга. Возвращаем красивое название.
     */
    private suspend fun repairSavedTrackTitles() {
        val all = graph.library.tracks.value
        if (all.isEmpty()) return
        val byId = all.associateBy { it.id }
        val fixes = all.mapNotNull { local ->
            if (local.source != Source.LOCAL) return@mapNotNull null
            val remoteId = local.extra["downloadedTrackId"] ?: return@mapNotNull null
            val remote = byId[remoteId] ?: return@mapNotNull null
            val fileStem = "${remote.artist} - ${remote.title}"
            if (!local.title.equals(fileStem, ignoreCase = true)) return@mapNotNull null
            local.copy(
                title = remote.title.ifBlank { local.title },
                artist = remote.artist.ifBlank { local.artist },
                album = local.album ?: remote.album,
            )
        }
        if (fixes.isNotEmpty()) {
            graph.library.upsertLocalTracks(fixes)
            graph.playback.applyMetadata(fixes)
        }
        // Заодно переносим старые ссылки избранного/плейлистов на локальные копии.
        all.forEach { local ->
            if (local.source != Source.LOCAL) return@forEach
            val remoteId = local.extra["downloadedTrackId"] ?: return@forEach
            graph.library.transferTrackReferences(remoteId, local.id)
        }
    }

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

    // ---------- Страница исполнителя и онлайн-альбомы ----------

    private val _artist = MutableStateFlow(ArtistUiState())
    val artist: StateFlow<ArtistUiState> = _artist.asStateFlow()
    private val artistCache = mutableMapOf<String, ArtistUiState>()
    private var artistJob: Job? = null

    private val _remoteAlbum = MutableStateFlow(RemoteAlbumUiState())
    val remoteAlbum: StateFlow<RemoteAlbumUiState> = _remoteAlbum.asStateFlow()
    private val remoteAlbumCache = mutableMapOf<String, RemoteAlbumUiState>()
    private var remoteAlbumJob: Job? = null

    /**
     * Загружает дискографию артиста: альбомы/синглы/EP из InnerTube плюс
     * альбомы, собранные из файлов устройства. Результат кэшируется на сессию,
     * поэтому возврат с альбома не дёргает сеть повторно.
     */
    fun openArtist(artistName: String) {
        val name = artistName.trim()
        if (name.isBlank()) return
        val cached = artistCache[name]
        if (cached != null) {
            _artist.value = cached
            return
        }
        if (artistJob?.isActive == true && _artist.value.name == name) return
        artistJob?.cancel()
        _artist.value = ArtistUiState(name = name, loading = true)
        artistJob = viewModelScope.launch {
            val remoteAlbums = runCatching { graph.youtube.artistAlbums(name) }
                .getOrDefault(emptyList())
            val topTracks = runCatching { graph.youtube.artistTopTracks(name) }
                .getOrDefault(emptyList())
            val state = ArtistUiState(
                name = name,
                loading = false,
                albums = mergeArtistAlbums(name, remoteAlbums),
                topTracks = withoutSavedCopies(topTracks),
                error = graph.youtube.lastError,
            )
            artistCache[name] = state
            _artist.value = state
        }
    }

    /** Повторить загрузку дискографии (например, после ошибки сети). */
    fun reloadArtist() {
        val name = _artist.value.name
        if (name.isBlank()) return
        artistCache.remove(name)
        artistJob?.cancel()
        _artist.value = ArtistUiState()
        openArtist(name)
    }

    private fun mergeArtistAlbums(
        name: String,
        remote: List<ArtistRelease>,
    ): List<ArtistRelease> {
        val out = LinkedHashMap<String, ArtistRelease>()
        for (album in remote) out.putIfAbsent(ArtistNames.key(album.title), album)
        graph.library.albums()
            .filter { ArtistNames.matches(it.artist, name) }
            .forEach { album ->
                val key = ArtistNames.key(album.name)
                if (out.containsKey(key)) return@forEach
                out[key] = ArtistRelease(
                    id = "local:${album.name}|${album.artist}",
                    title = album.name,
                    artist = album.artist,
                    year = album.year,
                    artworkUrl = album.artworkUrl,
                    artworkUri = album.artworkUri,
                    trackCount = album.trackIds.size,
                    remote = false,
                )
            }
        return out.values.sortedWith(
            compareByDescending<ArtistRelease> { it.year ?: 0 }
                .thenBy { it.title.lowercase() },
        )
    }

    /** Онлайн-альбом из дискографии: состав, год, обложка. */
    fun openRemoteAlbum(browseId: String, title: String, artist: String) {
        val id = browseId.trim()
        if (id.isBlank()) return
        remoteAlbumCache[id]?.let { cached ->
            _remoteAlbum.value = cached
            return
        }
        if (remoteAlbumJob?.isActive == true && _remoteAlbum.value.id == id) return
        remoteAlbumJob?.cancel()
        _remoteAlbum.value = RemoteAlbumUiState(
            id = id,
            title = title,
            artist = artist,
            loading = true,
        )
        remoteAlbumJob = viewModelScope.launch {
            val album = runCatching { graph.youtube.album(id) }.getOrNull()
            val state = if (album == null || album.tracks.isEmpty()) {
                RemoteAlbumUiState(
                    id = id,
                    title = title,
                    artist = artist,
                    loading = false,
                    error = graph.youtube.lastError
                        ?: "Не удалось загрузить альбом. Проверьте подключение.",
                )
            } else {
                val parts = album.subtitle.orEmpty()
                    .split("•", "·")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val year = parts.firstOrNull { YEAR_TEXT.matches(it) }?.toIntOrNull()
                // В подписи шапки первым может идти тип релиза («Album», «Single»),
                // а не исполнитель — такие слова пропускаем.
                val headerArtist = parts
                    .firstOrNull { part ->
                        part.toIntOrNull() == null && part.lowercase() !in RELEASE_TYPE_TOKENS
                    }
                    ?.let { ArtistNames.clean(it) }
                    ?.takeIf { it.isNotBlank() && !ArtistNames.isPlaceholder(it) }
                val resolvedArtist = headerArtist ?: artist
                val tracks = album.tracks
                    .filterNot { it.isVideoContent }
                    .map { track ->
                        val badArtist = track.artist.isBlank() ||
                            ArtistNames.isPlaceholder(track.artist)
                        track.copy(
                            artist = if (badArtist) resolvedArtist else track.artist,
                            artworkUrl = track.artworkUrl ?: album.artworkUrl,
                        )
                    }
                graph.library.cacheRemoteTracks(tracks)
                RemoteAlbumUiState(
                    id = id,
                    title = album.title,
                    artist = resolvedArtist,
                    year = year,
                    artworkUrl = album.artworkUrl
                        ?: tracks.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl,
                    tracks = tracks,
                    loading = false,
                )
            }
            remoteAlbumCache[id] = state
            _remoteAlbum.value = state
        }
    }

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
        if (blockedVideo(track)) return false
        // Если онлайн-трек уже сохранён — играем локальную копию, а не стрим.
        val playableTrack = localCopyOf(track) ?: track
        if (playableTrack.playable && !playableTrack.uri.isNullOrBlank()) {
            if (blockedByMode(playableTrack)) return false
            graph.playback.playQueue(buildQueueFor(playableTrack), 0)
            if (playableTrack.source == Source.YOUTUBE) {
                cacheAndEnrichYouTube(playableTrack)
                appendYouTubeRelated(playableTrack)
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

    // ---------- Сохранение треков из стриминга ----------

    private val _saveStates = MutableStateFlow<Map<String, TrackSaveState>>(emptyMap())

    /** Прогресс сохранения: track.id -> состояние (очередь, проценты, готово, ошибка). */
    val saveStates: StateFlow<Map<String, TrackSaveState>> = _saveStates.asStateFlow()

    /** Очередь сохранений: треки скачиваются по одному, без конкуренции за сеть. */
    private val saveQueue = ArrayDeque<Track>()
    private val saveQueuedIds = mutableSetOf<String>()
    private var saveWorker: Job? = null

    /**
     * Разрешение на мобильный трафик, выданное пользователем в этой сессии.
     * Оно глобальное для всего приложения: после первого подтверждения
     * плейлисты целиком и одиночные треки качаются без повторных вопросов.
     */
    private var mobileDataApproved = false

    /**
     * Треки, ожидающие разрешения на скачивание через мобильную сеть.
     * UI показывает один диалог на всю пачку (трек, альбом или плейлист).
     */
    private val _pendingMobileDownloads = MutableStateFlow<List<Track>>(emptyList())
    val pendingMobileDownloads: StateFlow<List<Track>> = _pendingMobileDownloads.asStateFlow()

    /** Пользователь разрешил мобильную загрузку — сразу для всей пачки. */
    fun confirmMobileDownloads(always: Boolean) {
        val pending = _pendingMobileDownloads.value
        _pendingMobileDownloads.value = emptyList()
        // Разрешение действует на всё приложение до конца сессии.
        mobileDataApproved = true
        if (always) updateSettings { it.copy(askMobileDownload = false) }
        pending.forEach { track -> enqueueSave(track) }
    }

    fun cancelMobileDownloads() {
        _pendingMobileDownloads.value = emptyList()
    }

    /** Выдано ли разрешение на мобильную сеть в текущей сессии. */
    fun isMobileDownloadApproved(): Boolean = mobileDataApproved

    /** Состояние сохранения конкретного трека (null — ещё не сохраняли). */
    fun saveState(trackId: String): TrackSaveState? = _saveStates.value[trackId]

    /** Сколько треков сейчас ждёт или сохраняется — для бейджа/подсказки. */
    fun saveQueueSize(): Int = saveQueue.size + if (saveWorker?.isActive == true) 1 else 0

    /** Локальная копия онлайн-трека, если он уже сохранён на устройство. */
    fun localCopyOf(track: Track): Track? {
        if (track.source == Source.LOCAL) return track
        val videoId = youtubeVideoId(track)
        return graph.library.tracks.value.firstOrNull { local ->
            if (local.source != Source.LOCAL) return@firstOrNull false
            local.extra["downloadedTrackId"] == track.id ||
                (videoId != null && local.extra["youtubeId"] == videoId)
        }
    }

    /** Есть ли уже локальная копия этого трека (YouTube или прямого источника). */
    fun isTrackDownloaded(track: Track): Boolean = localCopyOf(track) != null

    /**
     * Убирает из списка онлайн-версии треков, у которых уже есть локальная копия:
     * иначе сохранённый трек показывался бы дважды — в поиске, у артиста и в YouTube.
     */
    fun withoutSavedCopies(tracks: List<Track>): List<Track> {
        val savedRemoteIds = graph.library.tracks.value.mapNotNull { local ->
            if (local.source == Source.LOCAL) local.extra["downloadedTrackId"] else null
        }.toSet()
        if (savedRemoteIds.isEmpty()) return tracks
        return tracks.filterNot { it.source != Source.LOCAL && it.id in savedRemoteIds }
    }

    /** Есть ли уже локальная копия этого YouTube-трека. */
    fun isYouTubeDownloaded(track: Track): Boolean = isTrackDownloaded(track)

    /**
     * Ставит трек из стриминга в очередь сохранения. Файл, обложка и метаданные
     * попадают в общую библиотеку — трек сразу виден во вкладках «Треки», «Альбомы»,
     * «Исполнители» и «Избранное», рядом с файлами устройства.
     */
    fun downloadTrack(track: Track) = requestSave(track, allowMobile = false)

    private fun requestSave(track: Track, allowMobile: Boolean) {
        if (track.source == Source.LOCAL) {
            _saveStates.update { it + (track.id to TrackSaveState.done()) }
            return
        }
        if (isTrackDownloaded(track)) {
            _saveStates.update { it + (track.id to TrackSaveState.done()) }
            toast("Уже сохранено на устройстве")
            return
        }
        val state = _saveStates.value[track.id]
        if (state?.isActive == true || track.id in saveQueuedIds) return
        if (!canSave(track)) {
            _saveStates.update {
                it + (track.id to TrackSaveState.unavailable("Недоступно для сохранения"))
            }
            return
        }
        // На мобильной сети показываем разрешение, а не молча тратим трафик.
        // После первого «Скачать» разрешение глобальное: очередь плейлиста
        // и следующие треки идут без повторных вопросов.
        if (!allowMobile &&
            graph.settings.current.askMobileDownload &&
            !mobileDataApproved &&
            graph.downloads.isOnMobileData()
        ) {
            _pendingMobileDownloads.update { pending ->
                if (pending.any { it.id == track.id }) pending else pending + track
            }
            return
        }
        enqueueSave(track)
    }

    private fun enqueueSave(track: Track) {
        if (track.id in saveQueuedIds || _saveStates.value[track.id]?.isActive == true) return
        _saveStates.update { it + (track.id to TrackSaveState.queued()) }
        saveQueuedIds += track.id
        saveQueue.addLast(track)
        startSaveWorker()
    }

    /** Совместимый вход для кнопок, которые исторически звали downloadYouTube. */
    fun downloadYouTube(track: Track) = downloadTrack(track)

    fun retrySave(track: Track) {
        _saveStates.update { it - track.id }
        downloadTrack(track)
    }

    /**
     * Сохраняет плейлист целиком — как кнопка «Скачать» в Spotify.
     *
     * Локальные и уже сохранённые треки пропускаются. Если состав удалённого
     * плейлиста ещё не синхронизирован, сначала подгружаем его; при мобильной
     * сети разрешение спрашивается один раз на всю пачку.
     */
    fun downloadPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            var tracks = playlistTracks(playlist)
            if (tracks.isEmpty() && playlist.remoteId != null) {
                tracks = runCatching { graph.youtube.playlistTracks(playlist.remoteId) }
                    .getOrDefault(emptyList())
                if (tracks.isNotEmpty()) {
                    graph.library.cacheRemoteTracks(tracks)
                    graph.library.addToPlaylist(playlist.id, tracks.map { it.id })
                }
            }
            if (tracks.isEmpty()) {
                toast("В этом плейлисте нет треков")
                return@launch
            }
            downloadTracks(tracks)
        }
    }

    /**
     * Сохраняет набор треков (альбом, подборку) одной пачкой: одна очередь,
     * одно разрешение мобильной сети на всех.
     */
    fun downloadTracks(tracks: List<Track>) {
        val candidates = tracks.filter { track ->
            track.source != Source.LOCAL && track.playable && !isTrackDownloaded(track)
        }
        if (candidates.isEmpty()) {
            toast("Всё уже сохранено на устройстве")
            return
        }
        toast("Скачиваем треков: ${candidates.size}")
        candidates.forEach { track -> downloadTrack(track) }
    }

    /** Сколько треков плейлиста уже сохранено и сколько качается сейчас. */
    fun playlistSaveProgress(playlist: Playlist): PlaylistSaveProgress {
        val tracks = playlistTracks(playlist).filter { it.source != Source.LOCAL }
        if (tracks.isEmpty()) return PlaylistSaveProgress()
        val done = tracks.count { saveState(it.id)?.status == SaveStatus.DONE || isTrackDownloaded(it) }
        val active = tracks.count { saveState(it.id)?.isActive == true }
        return PlaylistSaveProgress(total = tracks.size, done = done, active = active)
    }

    private fun canSave(track: Track): Boolean {
        val youtube = track.source == Source.YOUTUBE || track.uri?.startsWith("innertube:") == true
        val direct = track.uri?.startsWith("http", ignoreCase = true) == true
        return track.playable && (youtube || direct)
    }

    private fun startSaveWorker() {
        if (saveWorker?.isActive == true) return
        saveWorker = viewModelScope.launch {
            try {
            while (saveQueue.isNotEmpty()) {
                val track = saveQueue.removeFirst()
                saveQueuedIds -= track.id
                val videoId = youtubeVideoId(track)
                // Сразу показываем «Сохранение…», не дожидаясь онлайн-метаданных:
                // поиск обложки/альбома может занять несколько секунд.
                reportSave(track.id, TrackSaveState.downloading())
                val metadata = runCatching { graph.metadata.lookup(track) }.getOrNull()
                val innertubeLyrics = if (videoId != null) {
                    runCatching { graph.youtube.lyrics(videoId) }.getOrNull()
                } else {
                    null
                }
                runCatching {
                    val file = when {
                        videoId != null -> {
                            downloadInnerTubeFile(track, videoId) { written, total ->
                                reportProgress(track.id, written, total)
                            }.second
                        }

                        track.uri?.startsWith("http", ignoreCase = true) == true -> {
                            // Прямые ссылки каталогов иногда отдают 5xx/обрыв;
                            // один повтор заметно повышает шанс сохранения.
                            var downloaded: java.io.File? = null
                            var lastError: Throwable? = null
                            for (attempt in 0..1) {
                                val result = graph.downloads.download(
                                    url = track.uri,
                                    suggestedFileName = downloadFileName(track),
                                    mime = track.extra["mime"],
                                    tag = track.id,
                                    onProgress = { written, total ->
                                        reportProgress(track.id, written, total)
                                    },
                                )
                                if (result.isSuccess) {
                                    downloaded = result.getOrThrow()
                                    break
                                }
                                lastError = result.exceptionOrNull()
                                if (attempt == 0) delay(700)
                            }
                            downloaded ?: throw (lastError
                                ?: IllegalStateException("Не удалось скачать файл"))
                        }

                        else -> throw IllegalStateException("Нет доступного потока")
                    }
                    ensureAudioFile(file)
                    val coverUrl = graph.youtube.largeArtworkUrl(
                        metadata?.coverUrl ?: track.artworkUrl,
                    ) ?: track.artworkUrl
                    val cover = coverUrl?.let { url ->
                        graph.downloads.downloadArtwork(url, videoId ?: track.id)
                    }
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
                        lyrics = metadata?.lyrics?.takeIf { it.isNotBlank() }
                            ?: innertubeLyrics ?: track.lyrics,
                        syncedLyrics = metadata?.syncedLyrics ?: track.syncedLyrics,
                        source = Source.LOCAL,
                        playable = true,
                        downloadAllowed = true,
                        deepLink = null,
                        extra = track.extra + buildMap {
                            videoId?.let { put("youtubeId", it) }
                            put("downloadedTrackId", track.id)
                            put("downloadedFrom", track.source.name)
                            put("downloadedAt", System.currentTimeMillis().toString())
                            metadata?.artistImageUrl?.let { put("artistImageUrl", it) }
                            artistImage?.let { put("artistImageUri", it.toURI().toString()) }
                            metadata?.artistBio?.let { put("artistBio", it) }
                            metadata?.genre?.let { put("genre", it) }
                            metadata?.provider?.let { put("metadataProvider", it) }
                        },
                    )
                    // Сначала регистрируем трек в библиотеке, затем пересканируем папку:
                    // так он появляется в списке мгновенно и не дублируется после скана.
                    graph.library.upsertLocalTracks(listOf(local))
                    // Избранное и плейлисты переводим на локальную копию.
                    graph.library.transferTrackReferences(track.id, local.id)
                    graph.playback.applyMetadata(listOf(local))
                    // Пересканирование — вспомогательное: файл уже в библиотеке,
                    // и сбой сканера не должен превращать успех в «не удалось».
                    runCatching { graph.library.scanLocal() }
                    reportSave(track.id, TrackSaveState.done())
                    toast("«${track.title}» сохранён на устройство")
                }.onFailure { error ->
                    reportSave(track.id, TrackSaveState.error(downloadErrorMessage(error)))
                }
            }
            } finally {
                saveWorker = null
                // Если воркер упал/отменён, не оставляем вечное «Сохранение…».
                val stuck = _saveStates.value.filterValues { it.isActive }.keys
                if (stuck.isNotEmpty()) {
                    _saveStates.update { current ->
                        current.mapValues { (id, state) ->
                            if (id in stuck) {
                                TrackSaveState.error("Загрузка прервана — попробуйте ещё раз")
                            } else {
                                state
                            }
                        }
                    }
                }
                saveQueuedIds.clear()
                if (saveQueue.isNotEmpty()) startSaveWorker()
            }
        }
    }

    private fun reportProgress(trackId: String, written: Long, total: Long) {
        // 100 % оставляем для состояния DONE: пока идёт финальная обработка
        // (обложка, метаданные), показываем не больше 99 %.
        val percent = if (total > 0L) {
            ((written.toDouble() / total) * 100.0).toInt().coerceIn(0, 99)
        } else {
            -1
        }
        _saveStates.update { current ->
            val state = current[trackId]
            if (state?.status == SaveStatus.DOWNLOADING && state.percent == percent) {
                current
            } else {
                current + (trackId to TrackSaveState.downloading(percent))
            }
        }
    }

    private fun reportSave(trackId: String, state: TrackSaveState) {
        _saveStates.update { it + (trackId to state) }
    }

    /**
     * Пробует MP4-поток InnerTubeX, затем резервный player-поток InnerTube.
     * Скачивает файл и возвращает поток + готовый файл.
     */
    private suspend fun downloadInnerTubeFile(
        track: Track,
        videoId: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Pair<StreamInfo, java.io.File> {
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
            // Сначала штатный режим потока (range/bounded), затем — обычный GET:
            // часть CDN отвечает 200 на Range и ломает пошаговую загрузку.
            val attempts = buildList {
                add(Triple(stream.rangeChunkSizeBytes, stream.requireBoundedRange, "range"))
                if (stream.rangeChunkSizeBytes > 0L || stream.requireBoundedRange) {
                    add(Triple(0L, false, "full"))
                }
            }
            for ((chunkSize, requireRange, mode) in attempts) {
                val result = graph.downloads.download(
                    url = stream.url,
                    suggestedFileName = baseName + extension,
                    headers = stream.headers,
                    mime = stream.mimeType,
                    contentLengthHint = stream.contentLength,
                    rangeChunkSizeBytes = chunkSize,
                    requireBoundedRange = requireRange,
                    tag = track.id,
                    onProgress = onProgress,
                )
                if (result.isSuccess) return stream to result.getOrThrow()
                lastError = result.exceptionOrNull()
                Log.w(
                    "SonoraDownload",
                    "InnerTube download attempt failed: video=$videoId mime=${stream.mimeType} " +
                        "mode=$mode range=$requireRange/$chunkSize " +
                        "error=${lastError?.message}",
                    lastError,
                )
                if (!isRetryableDownloadError(lastError)) break
            }
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

    /**
     * Проверяет, что сохранённый файл действительно аудио и не пустой.
     * Если сервер отдал muxed-видео, файл удаляется: Sonora хранит только музыку.
     */
    private suspend fun ensureAudioFile(file: java.io.File) {
        if (file.length() < 1024L) {
            runCatching { graph.downloads.delete(file.name) }
            throw IllegalStateException("Сервер вернул пустой или слишком короткий файл")
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                ?.equals("yes", ignoreCase = true) == true
            if (hasVideo) {
                runCatching { graph.downloads.delete(file.name) }
                throw IllegalStateException(
                    "Сервер вернул видеофайл — Sonora сохраняет только музыку",
                )
            }
        } catch (error: IllegalStateException) {
            throw error
        } catch (_: Exception) {
            // Некоторые контейнеры (webm/opus) MediaMetadataRetriever не читает,
            // но ExoPlayer их играет: не считаем это ошибкой сохранения.
        } finally {
            runCatching { retriever.release() }
        }
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
        // В офлайн-режиме «похожие» из стриминга не нужны.
        if (!streamingAllowed()) return
        val videoId = track.id.removePrefix("youtube:")
        if (videoId.isBlank()) return
        viewModelScope.launch {
            val related = graph.youtube.related(videoId, RELATED_LIMIT)
                .let(::queueable)
            if (related.isNotEmpty()) graph.playback.appendToQueue(related)
        }
    }

    /** Автоматическая очередь: трек + похожие из фонотеки. */
    private fun buildQueueFor(seed: Track): List<Track> {
        if (!graph.settings.current.autoQueue) {
            return listOf(seed)
        }
        val pool = queueable(graph.library.tracks.value)
            .filter { it.id != seed.id }
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
        // Прямой запуск списка: выключатель «Стриминг в очереди» здесь не мешает.
        val allowed = playableNow(tracks)
        if (allowed.isEmpty()) {
            toast("Режим «Только загруженное»: здесь нет треков с устройства.")
            return
        }
        val startId = tracks.getOrNull(startIndex)?.id
        val index = allowed.indexOfFirst { it.id == startId }.coerceAtLeast(0)
        graph.playback.playQueue(allowed, index)
    }

    fun playAlbum(albumName: String, artist: String) {
        val list = graph.library.tracks.value.filter { it.album == albumName && it.artist == artist }
        playQueue(list)
    }

    fun togglePlayPause() = graph.playback.togglePlayPause()
    fun next() = graph.playback.next()
    fun previous() = graph.playback.previous()
    fun seekTo(ms: Long) = graph.playback.seekTo(ms)
    fun seekToIndex(index: Int) = graph.playback.seekToIndex(index)
    fun toggleShuffle() = graph.playback.setShuffle(!graph.playback.state.value.shuffle)
    fun cycleRepeat() = graph.playback.cycleRepeatMode()
    fun setSpeed(speed: Float) = graph.playback.setSpeed(speed)
    fun addToQueue(track: Track) {
        if (blockedVideo(track)) return
        if (blockedByMode(track)) return
        graph.playback.addToQueue(track)
    }

    fun playNext(track: Track) {
        if (blockedVideo(track)) return
        if (blockedByMode(track)) return
        graph.playback.playNext(track)
    }
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

    /** Номер поколения поиска: ответ старого запроса не перетирает новый. */
    private var searchGeneration = 0

    fun onSearchQueryChanged(query: String) {
        _search.update { it.copy(query = query) }
        searchJob?.cancel()
        val normalized = query.trim()
        if (normalized.isBlank()) {
            searchGeneration++
            _search.value = SearchUiState(query = query)
            return
        }
        val generation = ++searchGeneration
        searchJob = viewModelScope.launch {
            _search.update { it.copy(loading = true, error = null) }
            delay(280)
            if (generation != searchGeneration) return@launch
            val local = graph.library.searchLocal(normalized)
            val remoteResult = try {
                Result.success(graph.youtube.search(normalized, REMOTE_SEARCH_LIMIT))
            } catch (cancelled: CancellationException) {
                // Отмена — не ошибка: новый запрос уже в пути.
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (generation != searchGeneration) return@launch
            val remote = remoteResult.getOrDefault(emptyList())
                .filterNot { it.isVideoContent }
                .filter { matchesSearchQuery(it, normalized) }
                .distinctBy { it.id }
            _search.update {
                it.copy(
                    local = local,
                    remote = remote,
                    loading = false,
                    error = when {
                        remoteResult.isFailure && local.isEmpty() && remote.isEmpty() ->
                            graph.youtube.lastError
                                ?: remoteResult.exceptionOrNull()?.message
                                ?: "Поиск временно недоступен"
                        else -> null
                    },
                )
            }
        }
    }

    /**
     * YouTube иногда возвращает далёкие «похожие» результаты на опечатку.
     * Оставляем только треки, где есть хотя бы одно слово запроса (от 3 букв).
     */
    private fun matchesSearchQuery(track: Track, query: String): Boolean {
        val tokens = query.lowercase()
            .replace('ё', 'е')
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
        if (tokens.isEmpty()) return true
        val haystack = buildString {
            append(track.title).append(' ')
            append(track.artist).append(' ')
            append(track.album.orEmpty())
        }.lowercase().replace('ё', 'е')
        return tokens.any { haystack.contains(it) }
    }

    fun clearSearch() {
        searchGeneration++
        searchJob?.cancel()
        _search.value = SearchUiState()
    }

    // ---------- Моя волна ----------

    // loading=true с первого кадра: до окончания сканирования библиотеки нельзя
    // показывать «0 треков, добавьте музыку» — это выглядит как потеря фонотеки.
    private val _wave = MutableStateFlow(WaveUiState(loading = true))
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

    private var waveGeneration = 0
    private var loadMoreJob: Job? = null

    fun refreshWave() {
        waveJob?.cancel()
        loadMoreJob?.cancel()
        loadMoreJob = null
        waveGeneration++
        waveSeenIds.clear()
        waveSeenKeys.clear()
        _wave.update { it.copy(loading = true, loadingMore = false, error = null) }
        waveJob = viewModelScope.launch {
            delay(250)
            val generation = waveGeneration
            val state = _wave.value
            val result = try {
                Result.success(buildWave(state))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            // Пока волна собиралась, пользователь мог нажать «Обновить» ещё раз:
            // устаревший результат не должен перезаписывать новую подборку.
            if (generation != waveGeneration) return@launch
            _wave.update { current ->
                val data = result.getOrNull()
                current.copy(
                    tracks = data?.first?.distinctBy { it.id } ?: current.tracks,
                    seedLabel = data?.second ?: current.seedLabel,
                    loading = false,
                    error = result.exceptionOrNull()?.message
                        ?: if (data == null || data.first.isEmpty()) emptyWaveHint() else null,
                    generatedAt = System.currentTimeMillis(),
                )
            }
            result.getOrNull()?.first?.let { markWaveSeen(it) }
        }
    }

    /** Почему волна может быть пустой — подсказка вместо общей ошибки. */
    private fun emptyWaveHint(): String = when {
        !modeAllowsStreaming() ->
            "Стриминг выключен: включите его в плеере или добавьте музыку на устройство."
        !streamingAllowed() ->
            "Стриминг в очереди выключен — волна собирается только из музыки на устройстве."
        else -> "Не удалось собрать волну"
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
        loadMoreJob?.cancel()
        val generation = waveGeneration
        loadMoreJob = viewModelScope.launch {
            val state = _wave.value
            val result = try {
                Result.success(
                    buildWave(
                        state = state,
                        excludeIds = waveSeenIds.toSet(),
                        excludeKeys = waveSeenKeys.toSet(),
                        limit = WAVE_LOAD_MORE_LIMIT,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (generation != waveGeneration) return@launch
            val more = result.getOrNull()?.first.orEmpty()
            _wave.update { latest ->
                latest.copy(
                    tracks = (latest.tracks + more).distinctBy { it.id },
                    loadingMore = false,
                    error = result.exceptionOrNull()?.message,
                )
            }
            markWaveSeen(more)
            if (more.isEmpty() && result.exceptionOrNull() == null) {
                toast("Больше рекомендаций пока нет — обновите волну.")
            }
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
        val tracks = playableNow(_wave.value.tracks)
        if (tracks.isEmpty()) {
            toast("Волна пуста: добавьте музыку на устройство или включите стриминг.")
            return
        }
        graph.playback.playQueue(tracks, 0)
        // «Похожие» подтягиваем для того трека, который реально заиграл.
        tracks.firstOrNull()?.takeIf { it.source == Source.YOUTUBE }?.let(::appendYouTubeRelated)
    }

    fun enqueueWave() {
        val tracks = queueable(_wave.value.tracks)
        if (tracks.isEmpty()) return
        graph.playback.appendToQueue(tracks)
    }

    /** Сколько прослушиваний учтено в рекомендациях (показывается в настройках волны). */
    val waveHistorySize: StateFlow<Int> = graph.playHistory.size

    /** Сырые записи истории — экран «История» пересобирает из них списки. */
    val historyEntries: StateFlow<List<WaveHistoryEntry>> = graph.playHistory.entries

    /** Сохраняет настройки «Моей волны» и сразу пересобирает подборку. */
    fun updateWaveSettings(transform: (WaveSettings) -> WaveSettings) {
        graph.settings.updateWave(transform)
        refreshWave()
    }

    fun clearWaveHistory() {
        viewModelScope.launch {
            graph.playHistory.clear()
            refreshWave()
        }
    }

    // ---------- История прослушиваний ----------

    /** Один трек в истории: последняя запись + сколько раз он играл. */
    data class HistoryTrackEntry(
        val track: Track,
        val playedAtMs: Long,
        val completed: Boolean,
        val playCount: Int,
        val listenedMs: Long,
    )

    /** Исполнитель в истории: сколько раз играл и когда в последний раз. */
    data class HistoryArtistEntry(
        val name: String,
        val artworkUrl: String?,
        val artworkUri: String?,
        val playCount: Int,
        val lastPlayedMs: Long,
        val trackIds: List<String>,
    )

    /**
     * История треков, новые сверху. Повторы одного трека свёрнуты в одну
     * строку (как «Недавно игравшее» в Spotify), но счётчик прослушиваний
     * сохраняется для статистики.
     */
    fun historyTracks(limit: Int = 150): List<HistoryTrackEntry> {
        val entries = graph.playHistory.entries.value
        if (entries.isEmpty()) return emptyList()
        val allTracks = graph.library.tracks.value
        val library = allTracks.associateBy { it.id }
        // Если стриминговый трек уже сохранён, в истории показываем локальную
        // копию: она играет офлайн и лежит рядом с остальными файлами.
        val savedCopies = allTracks
            .filter { it.source == Source.LOCAL && it.extra["downloadedTrackId"] != null }
            .associateBy { it.extra["downloadedTrackId"] }
        val counts = entries.groupingBy { it.trackId }.eachCount()
        return entries.asReversed()
            .distinctBy { it.trackId }
            .take(limit)
            .map { entry ->
                val track = savedCopies[entry.trackId]
                    ?: library[entry.trackId]
                    ?: Track(
                        id = entry.trackId,
                        title = entry.title,
                        artist = entry.artist,
                        source = Source.fromKey(entry.source),
                        playable = false,
                    )
                HistoryTrackEntry(
                    track = track,
                    playedAtMs = entry.playedAtMs,
                    completed = entry.completed,
                    playCount = counts[entry.trackId] ?: 1,
                    listenedMs = entry.listenedMs,
                )
            }
            .filterNot { it.track.isVideoContent }
    }

    /** Топ исполнителей по числу прослушиваний за всё время. */
    fun historyArtists(limit: Int = 60): List<HistoryArtistEntry> {
        val entries = graph.playHistory.entries.value
        if (entries.isEmpty()) return emptyList()
        val libraryTracks = graph.library.tracks.value
        return entries
            .groupBy { it.artist.trim().ifBlank { "Неизвестный исполнитель" } }
            .map { (name, plays) ->
                val tracks = libraryTracks.filter { it.artist.equals(name, ignoreCase = true) }
                val imageUrl = tracks.firstNotNullOfOrNull {
                    it.extra["artistImageUrl"]?.takeIf { url -> url.isNotBlank() }
                } ?: tracks.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl
                val imageUri = tracks.firstNotNullOfOrNull {
                    it.extra["artistImageUri"]?.takeIf { uri -> uri.isNotBlank() }
                } ?: tracks.firstOrNull { !it.artworkUri.isNullOrBlank() }?.artworkUri
                HistoryArtistEntry(
                    name = name,
                    artworkUrl = imageUrl,
                    artworkUri = imageUri,
                    playCount = plays.size,
                    lastPlayedMs = plays.maxOf { it.playedAtMs },
                    trackIds = plays.map { it.trackId }.distinct(),
                )
            }
            .sortedByDescending { it.playCount }
            .take(limit)
    }

    /** Суммарное прослушанное время по истории (для шапки экрана). */
    fun historyListenedMs(): Long = graph.playHistory.entries.value.sumOf { it.listenedMs }

    fun clearPlayHistory() {
        viewModelScope.launch {
            graph.playHistory.clear()
            refreshWave()
            toast("История прослушиваний очищена")
        }
    }

    fun setSaveHistory(enabled: Boolean) {
        updateSettings { it.copy(saveHistory = enabled) }
        toast(if (enabled) "История включена" else "История больше не записывается")
    }

    /**
     * Собирает «Мою волну»: локальные треки + онлайн (если режим проигрывания разрешает
     * стриминг), только музыка, с учётом истории прослушиваний и настроек волны.
     */
    private suspend fun buildWave(
        state: WaveUiState,
        excludeIds: Set<String> = emptySet(),
        excludeKeys: Set<String> = emptySet(),
        limit: Int = WAVE_LIMIT,
    ): Pair<List<Track>, String> = withContext(Dispatchers.Default) {
        val tuning = graph.settings.current.wave
        val sourceMode = state.filters.source
        val history = graph.playHistory.entries.value
        val now = System.currentTimeMillis()
        val useHistory = tuning.useHistory
        val recent = if (useHistory) WaveEngine.recentKeys(history, now) else emptySet()
        val affinity = if (useHistory) WaveEngine.artistAffinity(history, now) else emptyMap()

        val allTracks = graph.library.tracks.value
        val localCandidates = allTracks.filter { track ->
            track.source == Source.LOCAL && track.playable && !track.uri.isNullOrBlank() &&
                WaveEngine.isMusic(track, tuning)
        }
        val favorites = if (tuning.includeFavorites) favoriteTracks() else emptyList()
        val favoriteIds = favorites.map { it.id }.toSet()
        val favoriteArtists = favorites.map { it.artist.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .toSet()
        val artistCounts = allTracks
            .map { it.artist.trim().lowercase() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()

        val localPool = if (sourceMode == WaveSourceMode.ONLINE) {
            emptyList()
        } else {
            WaveEngine.rank(
                candidates = localCandidates,
                favoriteIds = favoriteIds,
                favoriteArtists = favoriteArtists,
                artistCounts = artistCounts,
                history = history,
                tuning = tuning,
                localBias = true,
                nowMs = now,
            )
        }

        val onlineAllowed = sourceMode != WaveSourceMode.LOCAL && streamingAllowed()
        val online = if (!onlineAllowed) {
            emptyList()
        } else {
            // Каждая порция — новый запрос: любимые артисты, частые артисты, миксы.
            val queries = WaveEngine.queries(
                moodQuery = state.filters.mood.takeIf { it != WaveMood.ANY }?.query,
                favoriteArtists = favoriteArtists.toList(),
                popularArtists = artistCounts.entries
                    .sortedByDescending { it.value }
                    .map { it.key }
                    .take(6),
                affinity = affinity,
                tuning = tuning,
            )
            val query = queries.getOrNull(waveQueryCursor++ % queries.size.coerceAtLeast(1))
            if (query.isNullOrBlank()) {
                emptyList()
            } else {
                graph.youtube.search(query, REMOTE_SEARCH_LIMIT)
                    .filter { it.playable && !it.uri.isNullOrBlank() }
                    .filter { WaveEngine.isMusic(it, tuning) }
                    .let { candidates ->
                        WaveEngine.rank(
                            candidates = candidates,
                            favoriteIds = favoriteIds,
                            favoriteArtists = favoriteArtists,
                            artistCounts = artistCounts,
                            history = history,
                            tuning = tuning,
                            localBias = false,
                            nowMs = now,
                        )
                    }
            }
        }

        val merged = WaveEngine.mix(localPool, online, tuning, limit * 2)
        // Исключаем показанное и недавно игравшее: и по id, и по «исполнитель|название».
        val filtered = WaveEngine.dedupe(merged).filter { track ->
            val key = WaveEngine.waveKey(track)
            track.id !in excludeIds && key !in excludeKeys && key !in recent
        }
        val diversified = WaveEngine.diversify(filtered, limit, tuning.artistDiversity.coerceIn(1, 5))
        val sorted = sortWave(diversified, state.sort)
        val label = when {
            state.filters.mood != WaveMood.ANY -> "Настроение: ${state.filters.mood.label}"
            !streamingAllowed() -> "Только устройство: стриминг выключен"
            sourceMode == WaveSourceMode.LOCAL -> "Только музыка с устройства"
            sourceMode == WaveSourceMode.ONLINE -> "Только музыка онлайн"
            useHistory && history.isNotEmpty() -> "На основе ${history.size} прослушиваний"
            favoriteArtists.isNotEmpty() -> "На основе ${favoriteArtists.size} любимых исполнителей"
            else -> "Только музыка: без видео и концертов"
        }
        return@withContext sorted to label
    }

    private fun sortWave(tracks: List<Track>, sort: WaveSort): List<Track> = when (sort) {
        WaveSort.FOR_YOU -> tracks
        WaveSort.TITLE -> tracks.sortedBy { it.title.lowercase() }
        WaveSort.ARTIST -> tracks.sortedBy { it.artist.lowercase() }
        WaveSort.DURATION -> tracks.sortedBy { it.durationMs }
        WaveSort.SHUFFLE -> tracks.shuffled()
    }

    // ---------- Моя волна: служебное ----------

    /** Ключ для дедупликации: исполнитель + название без скобочных пометок. */
    private fun waveKey(track: Track): String = WaveEngine.waveKey(track)

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
        val tracks = queueable(favoriteTracks())
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
                val allowed = queueable(tracks)
                if (allowed.isNotEmpty()) graph.playback.appendToQueue(allowed)
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
                val allowed = queueable(tracks)
                if (allowed.isNotEmpty()) graph.playback.appendToQueue(allowed)
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

    // ---------- Обложки и метаданные из файлов ----------

    private val _coverSearch = MutableStateFlow(CoverSearchUiState())
    val coverSearch: StateFlow<CoverSearchUiState> = _coverSearch.asStateFlow()
    private var coverSearchJob: Job? = null

    /**
     * Ищет обложки для локальных треков, у которых их нет. Запросы идут
     * последовательно и с лимитом за запуск: каталоги (iTunes/Deezer/
     * MusicBrainz) не любят пачки параллельных запросов.
     */
    fun findMissingCovers() {
        if (_coverSearch.value.searching) return
        coverSearchJob?.cancel()
        val targets = graph.library.tracks.value
            .filter { track ->
                if (track.source != Source.LOCAL) return@filter false
                val noRealCover = track.artworkUrl.isNullOrBlank() &&
                    (track.artworkUri.isNullOrBlank() || isMediaStoreAlbumArt(track.artworkUri))
                val lowResCover = !track.artworkUrl.isNullOrBlank() &&
                    LibraryRepository.isLowResolutionArtwork(track.artworkUrl.orEmpty())
                noRealCover || lowResCover
            }
            .take(MAX_COVER_SEARCH)
        if (targets.isEmpty()) {
            _coverSearch.value = CoverSearchUiState(
                message = "У всех локальных треков уже есть обложки",
            )
            toast("У всех локальных треков уже есть обложки")
            return
        }
        _coverSearch.value = CoverSearchUiState(searching = true, total = targets.size)
        coverSearchJob = viewModelScope.launch {
            var found = 0
            var failed = 0
            var processed = 0
            for (track in targets) {
                if (!isActive) break
                val metadata = runCatching { graph.metadata.lookup(track) }.getOrNull()
                val cover = metadata?.coverUrl
                if (metadata == null || cover.isNullOrBlank()) {
                    failed++
                } else {
                    runCatching { graph.library.updateTrackMetadata(track.id, metadata) }
                    found++
                }
                processed++
                _coverSearch.update { state ->
                    state.copy(processed = processed, found = found, failed = failed)
                }
            }
            val message = "Готово: найдено обложек — $found"
            _coverSearch.update { it.copy(searching = false, message = message) }
            toast(message)
        }
    }

    fun cancelCoverSearch() {
        coverSearchJob?.cancel()
        _coverSearch.update { it.copy(searching = false) }
    }

    private val _metadataImport = MutableStateFlow(MetadataImportUiState())
    val metadataImport: StateFlow<MetadataImportUiState> = _metadataImport.asStateFlow()

    /**
     * Импорт «телеметрии» из файлов пользователя (JSON/CSV/NFO): альбом, год,
     * обложка и текст для уже сохранённых локальных треков. Совпадение — по
     * нормализованным «исполнитель + название», при отсутствии — по названию.
     */
    fun importMetadataFiles(uris: List<Uri>) {
        if (uris.isEmpty() || _metadataImport.value.importing) return
        _metadataImport.value = MetadataImportUiState(importing = true, files = uris.size)
        viewModelScope.launch {
            val entries = mutableListOf<MetadataFileEntry>()
            var failed = 0
            uris.forEach { uri ->
                val parsed = runCatching { MetadataFiles.parse(graph.appContext, uri) }
                    .getOrDefault(emptyList())
                if (parsed.isEmpty()) failed++ else entries += parsed
            }
            val localTracks = graph.library.tracks.value.filter { it.source == Source.LOCAL }
            val byKey = localTracks.groupBy { metadataMatchKey(it.artist, it.title) }
            var matched = 0
            var updated = 0
            entries.forEach { entry ->
                val track = findTrackForEntry(entry, byKey, localTracks) ?: return@forEach
                matched++
                val hasData = !entry.album.isNullOrBlank() || (entry.year ?: 0) > 0 ||
                    !entry.coverUrl.isNullOrBlank() || !entry.lyrics.isNullOrBlank()
                if (!hasData) return@forEach
                runCatching {
                    graph.library.applyImportedMetadata(
                        trackId = track.id,
                        album = entry.album,
                        year = entry.year,
                        coverUrl = entry.coverUrl,
                        lyrics = entry.lyrics,
                    )
                }
                updated++
            }
            _metadataImport.value = MetadataImportUiState(
                files = uris.size,
                matched = matched,
                updated = updated,
                failed = failed,
                message = "Совпало: $matched, обновлено: $updated",
            )
            toast("Метаданные: совпало $matched, обновлено $updated")
        }
    }

    private fun findTrackForEntry(
        entry: MetadataFileEntry,
        byKey: Map<String, List<Track>>,
        tracks: List<Track>,
    ): Track? {
        val entryTitle = entry.title?.takeIf { it.isNotBlank() } ?: return null
        if (!entry.artist.isNullOrBlank()) {
            byKey[metadataMatchKey(entry.artist, entryTitle)]?.firstOrNull()?.let { return it }
        }
        val titleKey = normalizeForMatch(entryTitle)
        return tracks.firstOrNull { normalizeForMatch(it.title) == titleKey }
            ?: tracks.firstOrNull {
                titleKey.length >= 4 && normalizeForMatch(it.title).contains(titleKey)
            }
    }

    private fun metadataMatchKey(artist: String?, title: String?): String =
        "${normalizeForMatch(artist.orEmpty())}|${normalizeForMatch(title.orEmpty())}"

    /** «Битая» обложка MediaStore: такой URI часто не открывается и блокирует онлайн-обложку. */
    private fun isMediaStoreAlbumArt(uri: String?): Boolean =
        !uri.isNullOrBlank() && uri.startsWith("content://media/external/audio/albumart")

    /** Нормализация для сопоставления: без регистра, скобок, feat. и лишних пробелов. */
    private fun normalizeForMatch(value: String): String = value
        .lowercase()
        .replace(MATCH_PARENS_REGEX, " ")
        .replace(MATCH_FEAT_REGEX, " ")
        .replace(MATCH_WHITESPACE_REGEX, " ")
        .trim()

    // ---------- Настройки ----------

    fun updateSettings(transform: (AppSettings) -> AppSettings) = graph.settings.update(transform)

    fun setThemeMode(mode: ThemeMode) = updateSettings { it.copy(themeMode = mode) }
    fun setAccent(accent: Accent) = updateSettings { it.copy(accent = accent) }
    fun setDynamicColor(enabled: Boolean) = updateSettings { it.copy(dynamicColor = enabled) }
    fun setGapless(enabled: Boolean) = updateSettings { it.copy(gapless = enabled) }
    fun setSkipSilence(enabled: Boolean) = updateSettings { it.copy(skipSilence = enabled) }
    fun setScanOnStart(enabled: Boolean) = updateSettings { it.copy(scanOnStart = enabled) }

    fun setReadSidecarMetadata(enabled: Boolean) =
        updateSettings { it.copy(readSidecarMetadata = enabled) }

    fun setUseEmbeddedArtwork(enabled: Boolean) {
        updateSettings { it.copy(useEmbeddedArtwork = enabled) }
        if (enabled) toast("Обложки из файлов подхватятся при следующем сканировании")
    }

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

    fun setAskMobileDownload(enabled: Boolean) {
        updateSettings { it.copy(askMobileDownload = enabled) }
    }

    // ---------- Режим проигрывания (онлайн / только устройство) ----------

    fun setPlaybackMode(mode: PlaybackMode) {
        updateSettings { it.copy(playbackMode = mode) }
        if (mode == PlaybackMode.LOCAL_ONLY) pruneStreamingQueue(showEmptyMessage = false)
        refreshWave()
    }

    fun setStreamingInQueue(enabled: Boolean) {
        updateSettings { it.copy(streamingInQueue = enabled) }
        if (!enabled) pruneStreamingQueue(showEmptyMessage = false)
        refreshWave()
    }

    /**
     * Главный переключатель стриминга из плеера (кнопка перемешивания).
     * Выключение — это офлайн-режим Spotify: играем только сохранённое.
     */
    fun setStreamingEnabled(enabled: Boolean) {
        val mode = if (enabled) PlaybackMode.STREAMING else PlaybackMode.LOCAL_ONLY
        // Одно обновление настроек, чтобы не пересобирать волну дважды.
        updateSettings { it.copy(playbackMode = mode, streamingInQueue = enabled) }
        if (!enabled) pruneStreamingQueue(showEmptyMessage = false)
        refreshWave()
        toast(
            if (enabled) {
                "Стриминг включён: онлайн-треки снова в очереди"
            } else {
                "Офлайн-режим: только музыка на устройстве"
            },
        )
    }


    fun removeStreamingFromQueue() {
        pruneStreamingQueue(showEmptyMessage = true)
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
     * Сохраняет трек из открытого каталога через общую очередь сохранений:
     * прогресс, обложка, метаданные и появление в фонотеке — как у стриминга.
     */
    fun downloadDiscoverTrack(track: Track) {
        // Обычный путь сохранения: прогресс, очередь, разрешение мобильной сети.
        // Если пользователь отменит мобильную загрузку, состояния не будет —
        // интерфейс «Обзора» не зависнет в бесконечном спиннере.
        downloadTrack(track)
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

    private val _warpKeepAlive = MutableStateFlow(graph.settings.current.warpKeepAlive)
    val warpKeepAlive: StateFlow<Boolean> = _warpKeepAlive.asStateFlow()

    /** Пользователь включал WARP: держим туннель включённым, в том числе после
     *  перезапуска процесса (но только пока не нажал «Выключить»). */
    private val _warpDesired = MutableStateFlow(
        graph.settings.current.warpAutoConnect || graph.settings.current.warpUserEnabled,
    )

    /**
     * Автовосстановление WARP. Если пользователь выключил свой VPN/WARP
     * во время активной сессии, Sonora поднимает собственный туннель, как
     * только внешний VPN исчезает. Явное «Выключить» в Sonora снимает флаг.
     */
    private fun observeWarpKeepAlive() {
        viewModelScope.launch {
            var lastAttemptAt = 0L
            while (isActive) {
                delay(3_000)
                _warpExternalVpn.value = graph.warp.externalVpnActive()
                if (!_warpKeepAlive.value || !_warpDesired.value) continue
                val status = graph.warp.status.value
                if (status.state == WarpState.CONNECTED ||
                    status.state == WarpState.CONNECTING ||
                    status.state == WarpState.DISCONNECTING
                ) {
                    continue
                }
                if (graph.warp.externalVpnActive()) continue
                if (_warpConfigs.value.isEmpty()) continue
                if (!graph.warp.hasVpnPermission()) continue
                val now = System.currentTimeMillis()
                // После ошибки не долбим сервер каждые 3 секунды.
                if (status.state == WarpState.ERROR && now - lastAttemptAt < 15_000L) continue
                lastAttemptAt = now
                val result = graph.warp.startIfNoExternalVpn()
                if (result.isFailure) {
                    _warpError.value = result.exceptionOrNull()?.message
                } else {
                    _warpError.value = null
                }
                refreshWarp()
            }
        }
    }

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
        // Запоминаем намерение: если сейчас активен чужой VPN, автоподхват
        // включит Sonora WARP, как только пользователь его выключит.
        _warpDesired.value = true
        graph.settings.update { it.copy(warpUserEnabled = true) }
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
        // Явное выключение в Sonora отключает автоподхват до следующего включения.
        _warpDesired.value = false
        graph.settings.update { it.copy(warpUserEnabled = false) }
        graph.warp.stop()
        refreshWarp()
    }

    fun setWarpKeepAlive(enabled: Boolean) {
        _warpKeepAlive.value = enabled
        graph.settings.update { it.copy(warpKeepAlive = enabled) }
    }

    fun clearWarpConfig() {
        graph.warp.clearConfig()
        refreshWarp()
    }

    /** Возвращает в список встроенные WARP-конфиги, если пользователь их удалил. */
    fun restoreBundledWarpConfigs() {
        val added = runCatching { graph.warp.restoreBundled() }.getOrDefault(0)
        refreshWarp()
        toast(
            if (added > 0) {
                "Добавлено встроенных конфигов: $added"
            } else {
                "Встроенные конфиги уже в списке"
            },
        )
    }

    override fun onCleared() {
        searchJob?.cancel()
        discoverJob?.cancel()
        waveJob?.cancel()
        loadMoreJob?.cancel()
        // Колбэк держит ссылку на ViewModel, а PlaybackController живёт в AppGraph —
        // без сброса после пересоздания Activity остался бы старый экземпляр.
        graph.playback.queueExtender = null
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

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
import app.sonora.core.settings.AudioQuality
import app.sonora.core.settings.PlaybackMode
import app.sonora.core.settings.StreamSource
import app.sonora.core.settings.ThemeMode
import app.sonora.core.settings.WaveSettings
import app.sonora.core.settings.YoutubeIpMode
import app.sonora.core.util.AppLinks
import app.sonora.data.download.SaveStatus
import app.sonora.data.download.TrackSaveState
import app.sonora.data.metadata.ArtistNames
import app.sonora.data.metadata.MetadataFileEntry
import app.sonora.data.telemetry.AudioFeature
import app.sonora.data.metadata.MetadataFiles
import app.sonora.data.library.LibraryRepository
import app.sonora.data.remote.StreamProbe
import app.sonora.data.remote.youtube.innertube.StreamInfo
import app.sonora.data.streaming.StreamingAudit
import app.sonora.data.streaming.StreamingAutoPilot
import app.sonora.data.streaming.TransportKind
import app.sonora.data.update.UpdateInfo
import app.sonora.data.update.UpdateManager
import app.sonora.data.wave.WaveEngine
import app.sonora.data.wave.WaveHistoryEntry
import app.sonora.net.relay.RelayGateway
import app.sonora.net.relay.RelayHealth
import app.sonora.net.warp.WarpConfigEntry
import app.sonora.net.warp.WarpConfigSummary
import app.sonora.net.warp.WarpState
import app.sonora.net.warp.WarpStatus
import app.sonora.data.import.ImportedTrackEntry
import app.sonora.playback.PlayerUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

/** Тестовый ролик для диагностики потока, когда ничего не играет. */
private const val TEST_VIDEO_ID = "dQw4w9WgXcQ"

private const val RELATED_LIMIT = 25
private const val WAVE_LIMIT = 30
/** Сколько треков добавляет кнопка «Ещё» в бесконечной волне. */
private const val WAVE_LOAD_MORE_LIMIT = 20

/** Радио: сколько похожих треков брать сразу и какими порциями добирать. */
private const val RADIO_LIMIT = 40
private const val RADIO_BATCH = 20
/** Порог остатка очереди, после которого радио догружает следующую пачку. */
private const val RADIO_REFILL_THRESHOLD = 6

/** Сколько локальных треков должно совпасть с настроением, чтобы фильтровать. */
private const val MIN_MOOD_MATCHES = 6

/** Подсказки настроения для локальной фонотеки (жанр/название/альбом). */
private val MOOD_KEYWORDS: Map<WaveMood, List<String>> = mapOf(
    WaveMood.CALM to listOf(
        "chill", "acoustic", "ambient", "piano", "lofi", "lo-fi", "soft", "calm",
        "sleep", "relax", "спокой", "акуст", "эмбиент", "пианино",
    ),
    WaveMood.FOCUS to listOf(
        "instrumental", "focus", "study", "ambient", "piano", "beat", "concentration",
        "инструмент", "фокус", "концентрац",
    ),
    WaveMood.ENERGY to listOf(
        "dance", "workout", "rock", "metal", "drum", "bass", "edm", "party", "energy",
        "танц", "энергич", "рок", "метал",
    ),
    WaveMood.NIGHT to listOf(
        "night", "drive", "synth", "dark", "midnight", "noir",
        "ноч", "драйв", "синт",
    ),
    WaveMood.HAPPY to listOf(
        "happy", "pop", "upbeat", "sunny", "funk", "disco", "smile",
        "весел", "поп", "солнечн",
    ),
)

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
    /**
     * Состояние плеера без позиции: экранам, которым нужен только трек/статус,
     * не надо перерисовываться каждые полсекунды из-за обновления позиции.
     */
    val playerChrome: StateFlow<PlayerUiState> = graph.playback.state
        .map { state -> state.copy(positionMs = 0L, positionSampledAtMs = 0L) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, graph.playback.state.value)
    /** Грубый прогресс для мини-плеера: меняется не чаще, чем на 1 %. */
    val playerProgress: StateFlow<Float> = graph.playback.state
        .map { it.progress }
        .distinctUntilChanged { old, new -> kotlin.math.abs(old - new) < 0.01f }
        .stateIn(viewModelScope, SharingStarted.Eagerly, graph.playback.state.value.progress)
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
            // WARP не включается автоматически: только кнопкой в интерфейсе.

        }
        observeInfiniteQueue()
        observeCurrentYouTubeTrack()
        observePlaybackHistory()
        observeRadio()
        observeAudioFeatures()
        // observeWarpKeepAlive() — WARP не поднимается сам, только кнопкой.
        // Автопилот: при ошибке источника подбираем другой способ и переоткрываем трек.
        graph.playback.onStreamError = { error -> onPlaybackStreamError(error) }
        // Фоновая проверка способов стриминга: не блокирует старт приложения.
        if (graph.settings.current.autoTransport) {
            viewModelScope.launch(Dispatchers.IO) {
                delay(3_000)
                runCatching { StreamingAutoPilot.ensureAudited() }
                _autoPilotAudit.value = StreamingAutoPilot.lastAudit()
                _autoPilotMessage.value = StreamingAutoPilot.lastAudit()?.summaryLine()
            }
            // Периодическая перепроверка (сеть могла смениться, WARP — отвалиться).
            viewModelScope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(10 * 60_000L)
                    if (!graph.settings.current.autoTransport) continue
                    runCatching { StreamingAutoPilot.ensureAudited() }
                    _autoPilotAudit.value = StreamingAutoPilot.lastAudit()
                    _autoPilotMessage.value = StreamingAutoPilot.lastAudit()?.summaryLine()
                }
            }
        }
        // Проверка обновлений: раз в сутки в фоне, ничего не устанавливаем сами.
        viewModelScope.launch(Dispatchers.IO) {
            delay(5_000)
            val available = runCatching { UpdateManager.checkIfDue() }.getOrNull()
            if (available != null) {
                withContext(Dispatchers.Main) {
                    toast("Доступна Sonora ${available.versionName} — Настройки → Обновления")
                }
            }
            while (isActive) {
                delay(6 * 60 * 60_000L)
                runCatching { UpdateManager.checkIfDue() }
            }
        }
        // Прогреваем Python/yt-dlp в фоне: первый реальный вызов будет быстрее,
        // а старт приложения не ждёт тяжёлый рантайм.
        viewModelScope.launch(Dispatchers.IO) {
            delay(1_500)
            runCatching { app.sonora.data.remote.ytdlp.YtDlpBridge.warmUp() }
        }
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

    /** Треки по id в исходном порядке — для альбомов и исполнителей в медиатеке. */
    fun tracksByIds(ids: List<String>): List<Track> = graph.library.tracksByIds(ids)

    /** Вставить набор треков сразу после текущего (альбом/исполнитель). */
    fun playTracksNext(tracks: List<Track>) {
        val allowed = queueable(tracks)
        if (allowed.isEmpty()) return
        graph.playback.playNext(allowed)
        toast("Следующими: ${allowed.size} трек(ов)")
    }

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

    /** Перестановка треков внутри плейлиста (как перетаскивание в Spotify). */
    fun movePlaylistTrack(playlistId: String, from: Int, to: Int) {
        viewModelScope.launch { graph.library.moveInPlaylist(playlistId, from, to) }
    }

    /** Сортировка плейлиста по названию/исполнителю/альбому. */
    fun sortPlaylist(playlist: Playlist, comparator: Comparator<Track>) {
        viewModelScope.launch {
            val ordered = playlistTracks(playlist).sortedWith(comparator).map { it.id }
            if (ordered.isNotEmpty()) graph.library.setPlaylistTracks(playlist.id, ordered)
        }
    }

    /** Играет плейлист целиком (как кнопка Play в строке Spotify). */
    fun playPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            val tracks = resolvePlaylistTracks(playlist)
            if (tracks.isEmpty()) {
                toast("В этом плейлисте нет треков")
                return@launch
            }
            playQueue(tracks)
        }
    }

    /**
     * Ставит весь плейлист сразу после текущего трека — «играть следующим»,
     * как в Spotify. Состав подгружается, если удалённый плейлист ещё не синхронизирован.
     */
    fun playPlaylistNext(playlist: Playlist) {
        viewModelScope.launch {
            val tracks = resolvePlaylistTracks(playlist)
            if (tracks.isEmpty()) {
                toast("В этом плейлисте нет треков")
                return@launch
            }
            val allowed = queueable(tracks)
            if (allowed.isEmpty()) return@launch
            graph.playback.playNext(allowed)
            toast("Следующими: ${allowed.size} трек(ов)")
        }
    }

    /** Единая точка получения состава плейлиста: локальный кэш или InnerTube. */
    private suspend fun resolvePlaylistTracks(playlist: Playlist): List<Track> {
        val local = playlistTracks(playlist)
        if (local.isNotEmpty() || playlist.remoteId == null) return local
        val remote = runCatching { graph.youtube.playlistTracks(playlist.remoteId) }
            .getOrDefault(emptyList())
        if (remote.isNotEmpty()) {
            graph.library.cacheRemoteTracks(remote)
            graph.library.addToPlaylist(playlist.id, remote.map { it.id })
        }
        return remote.filterNot { it.isVideoContent }
    }

    // ---------- Радио (по треку и по плейлисту) ----------

    /**
     * Активно ли радио. Пока оно включено, очередь автоматически пополняется
     * похожими треками, а обычное воспроизведение его выключает.
     */
    private var radioActive = false
    private var radioRefilling = false

    /** Радио по треку — как «Перейти в радио песни» в Spotify. */
    fun startTrackRadio(track: Track) {
        if (blockedVideo(track)) return
        startRadio(localCopyOf(track) ?: track, "Радио по треку: ${track.title}")
    }

    /** Радио по плейлисту: сид — случайный трек, дальше похожее. */
    fun startPlaylistRadio(playlist: Playlist) {
        viewModelScope.launch {
            val tracks = resolvePlaylistTracks(playlist)
            if (tracks.isEmpty()) {
                toast("В этом плейлисте нет треков")
                return@launch
            }
            val seed = tracks.random()
            startRadio(seed, "Радио по плейлисту «${playlist.name}»")
        }
    }

    /** Выключает режим радио (вызывается при обычном запуске очереди). */
    private fun stopRadio() {
        radioActive = false
        radioRefilling = false
    }

    private fun startRadio(seed: Track, label: String) {
        stopRadio()
        radioActive = true
        viewModelScope.launch {
            val related = radioCandidates(seed, RADIO_LIMIT)
            val queue = playableNow(listOf(seed) + related).distinctBy { it.id }
            if (queue.isEmpty()) {
                radioActive = false
                toast("Не удалось собрать радио для этого трека")
                return@launch
            }
            graph.playback.playQueue(queue, 0)
            toast(label)
        }
    }

    /**
     * Следит за очередью и подгружает следующую пачку, пока радио активно.
     * Порог небольшой, чтобы «вперёд» никогда не упирался в конец.
     */
    private fun observeRadio() {
        viewModelScope.launch {
            graph.playback.state.collect { state ->
                if (!radioActive || radioRefilling) return@collect
                if (state.queue.isEmpty()) return@collect
                val remaining = state.queue.size - state.index
                if (remaining > RADIO_REFILL_THRESHOLD) return@collect
                val last = state.queue.lastOrNull() ?: return@collect
                radioRefilling = true
                try {
                    val batch = radioCandidates(last, RADIO_BATCH)
                    val known = state.queue.mapTo(HashSet()) { it.id }
                    val fresh = queueable(batch).filterNot { it.id in known }
                    if (fresh.isNotEmpty()) graph.playback.appendToQueue(fresh)
                } finally {
                    radioRefilling = false
                }
            }
        }
    }

    /**
     * Кандидаты для радио: очередь «дальше» из YouTube Music плюс локальные
     * похожие (тот же альбом/артист). Онлайн-часть работает только когда
     * стриминг разрешён — иначе радио остаётся в пределах устройства.
     */
    private suspend fun radioCandidates(seed: Track, limit: Int): List<Track> {
        val videoId = youtubeVideoId(seed)
        val remote = if (videoId != null && streamingAllowed()) {
            runCatching { graph.youtube.related(videoId, limit) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val local = buildQueueFor(seed).filterNot { it.id == seed.id }
        return WaveEngine.dedupe(remote + local)
            .filter { !it.isVideoContent && it.playable && !it.uri.isNullOrBlank() }
            .filterNot { WaveEngine.isSelfReference(it) }
            .take(limit)
    }

    // ---------- Аудио-фичи (ReccoBeats) ----------

    private val _audioFeatures = MutableStateFlow<Map<String, AudioFeature>>(emptyMap())

    /** Фичи играющих треков: track.id → BPM/energy/…; пусто, если телеметрия выключена. */
    val audioFeatures: StateFlow<Map<String, AudioFeature>> = _audioFeatures.asStateFlow()

    fun audioFeature(trackId: String): AudioFeature? = _audioFeatures.value[trackId]

    /**
     * Считает фичи для каждого нового трека в фоне. Ошибки/таймауты полностью
     * проглатываются: воспроизведение не зависит от ReccoBeats.
     */
    private fun observeAudioFeatures() {
        viewModelScope.launch {
            val requested = HashSet<String>()
            graph.playback.state.collect { state ->
                val track = state.current ?: return@collect
                if (!graph.settings.current.audioTelemetry) return@collect
                if (track.id in requested) return@collect
                if (_audioFeatures.value.containsKey(track.id)) return@collect
                requested += track.id
                launch(Dispatchers.IO) {
                    val feature = runCatching {
                        graph.audioFeatures.featuresFor(track)
                    }.getOrNull() ?: return@launch
                    _audioFeatures.update { it + (track.id to feature) }
                }
            }
        }
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
            stopRadio()
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
            "yt-dlp" to suspend {
                graph.youtube.ytDlpDownloadStream(videoId)
                    ?: error("yt-dlp не вернул поток")
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
        stopRadio()
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

    /**
     * «Слушать всю медиатеку» одной кнопкой: вся фонотека вперемешку, как
     * «Понравившиеся» в Spotify. Shuffle включается сам, очередь стартует с
     * первого случайного трека.
     */
    fun playLibraryShuffled() {
        val all = graph.library.tracks.value
            .filter { it.playable && !it.uri.isNullOrBlank() }
            .filterNot { blockedVideo(it) }
            .filterNot { blockedByMode(it) }
        if (all.isEmpty()) {
            toast("В медиатеке пока нет треков")
            return
        }
        graph.playback.setShuffle(true)
        graph.playback.playQueue(all.shuffled(), 0)
        toast("Вся медиатека: ${all.size} треков · вперемешку")
    }
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
            var remote = remoteResult.getOrDefault(emptyList())
                .filterNot { it.isVideoContent }
                .filter { matchesSearchQuery(it, normalized) }
                .distinctBy { it.id }
            // InnerTube пуст или упал — пробуем yt-dlp (если он не отключён).
            if (remote.isEmpty() &&
                graph.settings.current.streamSource != StreamSource.INNERTUBE
            ) {
                val fromYtDlp = runCatching {
                    graph.ytDlp.search(normalized, limit = 10)
                }.getOrDefault(emptyList())
                if (generation != searchGeneration) return@launch
                remote = fromYtDlp
                    .map { it.toSonoraTrack() }
                    .distinctBy { it.id }
            }
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
        resetWaveCursor()
        refreshWave()
    }

    fun setWaveMood(mood: WaveMood) {
        _wave.update { it.copy(filters = it.filters.copy(mood = mood)) }
        resetWaveCursor()
        refreshWave()
    }

    /** Только избранное: локальная часть подборки остаётся в любимых треках. */
    fun setWaveFavoritesOnly(enabled: Boolean) {
        _wave.update { it.copy(filters = it.filters.copy(favoritesOnly = enabled)) }
        resetWaveCursor()
        refreshWave()
    }

    /** Смена фильтра начинает подборку с первого запроса, а не с середины. */
    private fun resetWaveCursor() {
        waveQueryCursor = 0
        waveSeenIds.clear()
        waveSeenKeys.clear()
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
        stopRadio()
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

    /** Треки, отмеченные «не интересно» в волне. */
    val waveDisliked: StateFlow<Set<String>> = graph.playHistory.disliked

    /**
     * «Не интересно»: трек убирается из текущей волны и больше не предлагается.
     * Обратное действие — [undoWaveDislike].
     */
    fun dislikeWaveTrack(track: Track) {
        viewModelScope.launch {
            graph.playHistory.dislike(track)
            markWaveSeen(listOf(track))
            _wave.update { st -> st.copy(tracks = st.tracks.filterNot { it.id == track.id }) }
            toast("Больше не будем предлагать этот трек")
        }
    }

    fun undoWaveDislike(track: Track) {
        viewModelScope.launch {
            graph.playHistory.undislike(track)
            refreshWave()
        }
    }

    /** Вернуть в рекомендации все скрытые треки. */
    fun clearWaveDislikes() {
        viewModelScope.launch {
            graph.playHistory.clearDislikes()
            toast("Скрытые треки снова в рекомендациях")
            refreshWave()
        }
    }

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
        val disliked = graph.playHistory.disliked.value

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
            // Фильтры должны влиять на локальную часть, а не только на онлайн:
            // настроение — по жанру/названию, «только избранное» — по id.
            val scoped = if (state.filters.favoritesOnly) {
                val onlyFavorites = localCandidates.filter { it.id in favoriteIds }
                // Пустое избранное не должно давать пустую волну.
                onlyFavorites.ifEmpty { localCandidates }
            } else {
                localCandidates
            }
            val moodScoped = if (state.filters.mood == WaveMood.ANY) {
                scoped
            } else {
                val matched = scoped.filter { matchesMood(it, state.filters.mood) }
                // Если совпадений мало, не обрезаем фонотеку до нуля.
                if (matched.size >= MIN_MOOD_MATCHES) matched else scoped
            }
            WaveEngine.rank(
                candidates = moodScoped,
                favoriteIds = favoriteIds,
                favoriteArtists = favoriteArtists,
                artistCounts = artistCounts,
                history = history,
                tuning = tuning,
                localBias = true,
                nowMs = now,
                dislikedKeys = disliked,
                recentTrackKeys = recent,
            )
        }

        val onlineAllowed = sourceMode != WaveSourceMode.LOCAL && streamingAllowed()
        val online = if (!onlineAllowed) {
            emptyList()
        } else {
            // Берём сразу несколько запросов: один даёт узкую выдачу и слишком
            // часто возвращает уже знакомые треки. Любимые артисты, частые
            // артисты и настроение идут параллельно.
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
            val picks = if (queries.isEmpty()) {
                emptyList()
            } else {
                val start = waveQueryCursor
                waveQueryCursor = (waveQueryCursor + 3) % queries.size
                (0 until minOf(3, queries.size))
                    .map { queries[(start + it) % queries.size] }
                    .distinct()
            }
            val responses = if (picks.isEmpty()) {
                emptyList()
            } else {
                coroutineScope {
                    picks.map { query ->
                        async {
                            runCatching { graph.youtube.search(query, REMOTE_SEARCH_LIMIT) }
                                .getOrDefault(emptyList())
                        }
                    }.awaitAll()
                }
            }
            val candidates = responses.flatten()
                .filter { it.playable && !it.uri.isNullOrBlank() }
                .filter { WaveEngine.isMusic(it, tuning) }
            WaveEngine.rank(
                candidates = candidates,
                favoriteIds = favoriteIds,
                favoriteArtists = favoriteArtists,
                artistCounts = artistCounts,
                history = history,
                tuning = tuning,
                localBias = false,
                nowMs = now,
                dislikedKeys = disliked,
                recentTrackKeys = recent,
            )
        }

        val merged = WaveEngine.mix(localPool, online, tuning, limit * 2)
        // Исключаем только показанное в прошлых порциях и явные дизлайки.
        // Недавно игравшее больше не вырезается подчистую — за него отвечает
        // штраф в rank, иначе у небольшой фонотеки волна остаётся пустой.
        val filtered = WaveEngine.dedupe(merged).filter { track ->
            val key = WaveEngine.waveKey(track)
            track.id !in excludeIds &&
                key !in excludeKeys &&
                !WaveEngine.isDisliked(track, disliked)
        }
        val diversified = WaveEngine.diversify(filtered, limit, tuning.artistDiversity.coerceIn(1, 5))
        val sorted = sortWave(diversified, state.sort)
        // Подписи — про подборку, а не про «ваши N прослушиваний»: волна
        // должна ощущаться как открытие нового, а не как отчёт о себе.
        val label = when {
            state.filters.mood != WaveMood.ANY -> "Настроение: ${state.filters.mood.label}"
            !streamingAllowed() -> "Только устройство: стриминг выключен"
            sourceMode == WaveSourceMode.LOCAL -> "Только музыка с устройства"
            sourceMode == WaveSourceMode.ONLINE -> "Новые исполнители и знакомые треки"
            tuning.novelty >= 65 -> "Больше новых исполнителей"
            else -> "Новая подборка каждый раз"
        }
        return@withContext sorted to label
    }

    /**
     * Совпадает ли трек с настроением. Жанр берём из `extra["genre"]` (его
     * заполняют метаданные), плюс подсказки в названии и альбоме. Для треков
     * без жанра настроение работает мягко: если совпадений мало, волна не
     * обрезается (см. [MIN_MOOD_MATCHES]).
     */
    private fun matchesMood(track: Track, mood: WaveMood): Boolean {
        val keywords = MOOD_KEYWORDS[mood] ?: return true
        val haystack = buildString {
            append(track.title).append(' ')
            append(track.album.orEmpty()).append(' ')
            append(track.extra["genre"].orEmpty())
        }.lowercase().replace('ё', 'е')
        return keywords.any { haystack.contains(it) }
    }

    private fun sortWave(tracks: List<Track>, sort: WaveSort): List<Track> = when (sort) {        WaveSort.FOR_YOU -> tracks
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

    /**
     * Смена качества онлайн-аудио. Сбрасываем кэш ссылок текущего трека и
     * переоткрываем поток, чтобы новое качество применилось сразу, а не со
     * следующего трека.
     */
    fun setAudioQuality(quality: AudioQuality) {
        updateSettings { it.copy(audioQuality = quality) }
        val current = graph.playback.state.value.current ?: return
        val videoId = youtubeVideoId(current) ?: return
        graph.youtube.invalidateStream(videoId)
        graph.playback.reloadCurrent()
        toast("Качество: ${quality.label}")
    }

    /** Градация качества для сотовой сети (действует при основном «Авто»). */
    fun setMobileAudioQuality(quality: AudioQuality) {
        updateSettings { it.copy(mobileAudioQuality = quality) }
        if (graph.settings.current.audioQuality != AudioQuality.AUTO) {
            toast("Мобильная сеть: ${quality.label}")
            return
        }
        if (!graph.downloads.isOnMobileData()) {
            toast("Мобильная сеть: ${quality.label}")
            return
        }
        val current = graph.playback.state.value.current
        val videoId = current?.let { youtubeVideoId(it) }
        if (videoId != null) {
            graph.youtube.invalidateStream(videoId)
            graph.playback.reloadCurrent()
        }
        toast("Мобильная сеть: ${quality.label}")
    }

    /** Источник потока: Авто / yt-dlp / только InnerTube. */
    fun setStreamSource(source: StreamSource) {
        updateSettings { it.copy(streamSource = source) }
        toast("Поток: ${source.label}")
    }

    /** HTTP/SOCKS-прокси только для yt-dlp (пусто — напрямую). */
    fun setYtDlpProxy(proxy: String) {
        updateSettings { it.copy(ytDlpProxy = proxy.trim()) }
        toast(if (proxy.isBlank()) "Прокси yt-dlp выключен" else "Прокси yt-dlp сохранён")
    }

    /** Считать аудио-фичи ReccoBeats для играющего трека. */
    fun setAudioTelemetry(enabled: Boolean) {
        updateSettings { it.copy(audioTelemetry = enabled) }
    }

    private val _streamCheck = MutableStateFlow<String?>(null)
    val streamCheck: StateFlow<String?> = _streamCheck.asStateFlow()

    private val _streamCheckDetails = MutableStateFlow<String?>(null)
    val streamCheckDetails: StateFlow<String?> = _streamCheckDetails.asStateFlow()

    private val _relayCheck = MutableStateFlow<String?>(null)
    val relayCheck: StateFlow<String?> = _relayCheck.asStateFlow()

    private val _relayHealth = MutableStateFlow<List<RelayHealth>>(emptyList())
    val relayHealth: StateFlow<List<RelayHealth>> = _relayHealth.asStateFlow()

    private val _ytDlpStatus = MutableStateFlow<String?>(null)
    val ytDlpStatus: StateFlow<String?> = _ytDlpStatus.asStateFlow()

    fun dismissStreamCheckDetails() {
        _streamCheckDetails.value = null
    }

    /** Медиашлюз без VPN и WARP: резервный путь через публичные зеркала. */
    fun setRelayEnabled(enabled: Boolean) {
        updateSettings { it.copy(relayEnabled = enabled) }
        RelayGateway.clear()
        toast(if (enabled) "Медиашлюз без VPN включён" else "Медиашлюз выключен")
    }

    /** Свой список зеркал: по одному в строке, пусто — встроенный список. */
    fun setRelayInstances(value: String) {
        updateSettings { it.copy(relayInstances = value.trim()) }
        RelayGateway.clear()
        _relayCheck.value = null
        toast(if (value.isBlank()) "Зеркала: встроенный список" else "Список зеркал сохранён")
    }

    /** Предпочтительный IP-протокол YouTube (IPv6 иногда работает без WARP). */
    fun setYoutubeIpMode(mode: YoutubeIpMode) {
        updateSettings { it.copy(youtubeIpMode = mode) }
        toast("YouTube: ${mode.label}")
    }

    /** Проверять ссылку Range-запросом перед отдачей в плеер. */
    fun setProbeBeforePlay(enabled: Boolean) {
        updateSettings { it.copy(probeBeforePlay = enabled) }
    }

    // ---------- Автопилот стриминга ----------

    private val _autoPilotAudit = MutableStateFlow<StreamingAudit?>(null)
    val autoPilotAudit: StateFlow<StreamingAudit?> = _autoPilotAudit.asStateFlow()

    private val _autoPilotRunning = MutableStateFlow(false)
    val autoPilotRunning: StateFlow<Boolean> = _autoPilotRunning.asStateFlow()

    private val _autoPilotMessage = MutableStateFlow<String?>(null)
    val autoPilotMessage: StateFlow<String?> = _autoPilotMessage.asStateFlow()

    private var recovering = false
    private var recoveryTrackId: String? = null
    private var recoveryAttempts = 0

    /** Автопилот: сам проверяет способы стриминга и выбирает рабочий. */
    fun setAutoTransport(enabled: Boolean) {
        updateSettings { it.copy(autoTransport = enabled) }
        toast(if (enabled) "Автопилот включён" else "Автопилот выключен")
        if (enabled) runStreamingAudit(deep = false)
    }

    /** Поднять WARP автоматически, если прямой путь не работает. */
    fun setAutoWarpFallback(enabled: Boolean) {
        updateSettings { it.copy(autoWarpFallback = enabled) }
        toast(if (enabled) "WARP-автопилот включён" else "WARP-автопилот выключен")
    }

    /** Самостоятельная регистрация свежего WARP-профиля. */
    fun setWarpAutoRegister(enabled: Boolean) {        updateSettings { it.copy(warpAutoRegister = enabled) }
        toast(if (enabled) "Авто-регистрация WARP включена" else "Авто-регистрация WARP выключена")
    }

    /** Получить свежий WARP-профиль по кнопке (старые конфиги устаревают). */
    fun autoRegisterWarp() {
        viewModelScope.launch(Dispatchers.IO) {
            toast("Регистрируем свежий WARP-профиль…")
            val ok = runCatching { StreamingAutoPilot.registerFreshWarp() }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                toast(
                    if (ok) {
                        "WARP-профиль обновлён — подключаемся"
                    } else {
                        "Не удалось зарегистрировать WARP-профиль (нет сети?)"
                    },
                )
            }
            if (ok) {
                runCatching { StreamingAutoPilot.ensureWarp() }
                runStreamingAudit(deep = false)
            }
        }
    }

    /** Переключать источник при 403/ошибке воспроизведения. */
    fun setAutoFailover(enabled: Boolean) {
        updateSettings { it.copy(autoFailover = enabled) }
        toast(if (enabled) "Автопереключение потока включено" else "Автопереключение выключено")
    }

    /**
     * Проверить все способы стриминга. deep=true — включая WARP-конфиги
     * (медленно, зато показывает реальный результат через туннель).
     */
    fun runStreamingAudit(deep: Boolean = true) {
        if (_autoPilotRunning.value) return
        _autoPilotRunning.value = true
        _autoPilotMessage.value = if (deep) "Проверяем все способы…" else "Проверяем поток…"
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { StreamingAutoPilot.runAudit(deep = deep) }.getOrNull()
            _autoPilotAudit.value = result ?: StreamingAutoPilot.lastAudit()
            _autoPilotMessage.value = result?.summaryLine() ?: "Проверка не удалась"
            _autoPilotRunning.value = false
            withContext(Dispatchers.Main) { toast(_autoPilotMessage.value!!) }
        }
    }

    /** Нужно ли разрешение Android на VPN для WARP-автопилота. */
    fun autoPilotNeedsVpnPermission(): Boolean = StreamingAutoPilot.needsVpnPermission()

    /** Intent системного диалога разрешения VPN; null — уже выдано. */
    fun autoPilotVpnPermissionIntent(): Intent? = graph.warp.vpnPermissionIntent()

    // ---------- DPI-обход NoHiT (вместо WARP) ----------

    private val _nohitTuning = MutableStateFlow(false)
    val nohitTuning: StateFlow<Boolean> = _nohitTuning.asStateFlow()

    private val _nohitMessage = MutableStateFlow<String?>(null)
    val nohitMessage: StateFlow<String?> = _nohitMessage.asStateFlow()

    /** Текущая стратегия NoHiT для подписи в настройках. */
    fun nohitConfigLabel(): String {
        val cfg = runCatching { dev.nohit.core.NoHiT.currentConfig() }.getOrNull()
            ?: return "не инициализирован"
        val split = cfg.split
        val splitText = if (split.chunks.isEmpty()) {
            "без фрагментации"
        } else {
            split.chunks.joinToString("-") +
                if (split.delayMs > 0) " +${split.delayMs} мс" else ""
        }
        return "${cfg.dns.firstOrNull()?.name ?: "системный DNS"} · $splitText"
    }

    /** Включить/выключить DPI-обход NoHiT (замена WARP). */
    fun setNohitEnabled(enabled: Boolean) {
        updateSettings { it.copy(nohitEnabled = enabled) }
        if (enabled) {
            runCatching { dev.nohit.core.NoHiT.init(graph.appContext) }
            app.sonora.net.nohit.NohitProxy.start()
            toast("DPI-обход NoHiT включён")
        } else {
            app.sonora.net.nohit.NohitProxy.stop()
            toast("DPI-обход NoHiT выключен")
        }
    }

    /** Автоподбор стратегии фрагментации и DNS (может занять до минуты). */
    fun tuneNohit() {
        if (_nohitTuning.value) return
        _nohitTuning.value = true
        _nohitMessage.value = "Подбираем стратегию…"
        viewModelScope.launch(Dispatchers.IO) {
            val report = runCatching {
                dev.nohit.core.AutoTuner.tune(graph.appContext) { line ->
                    _nohitMessage.value = line
                }
            }.getOrNull()
            _nohitMessage.value = when {
                report == null -> "Подбор не удался"
                report.ok -> "OK · ${report.dns.name} · ${report.latencyMs} мс"
                else -> "Нет рабочей комбинации на этой сети"
            }
            _nohitTuning.value = false
            updateSettings { it.copy(nohitTunedAtMs = System.currentTimeMillis()) }
            withContext(Dispatchers.Main) { toast(_nohitMessage.value!!) }
        }
    }

    // ---------- Обновления из GitHub ----------

    val updateAvailable: StateFlow<UpdateInfo?> = UpdateManager.available
    val updateChecking: StateFlow<Boolean> = UpdateManager.checking
    val updateDownloading: StateFlow<Boolean> = UpdateManager.downloading
    val updateProgress: StateFlow<Float> = UpdateManager.progress
    val updateError: StateFlow<String?> = UpdateManager.error
    val updateReadyToInstall: StateFlow<Boolean> = UpdateManager.readyToInstall

    fun appVersionLabel(): String = "Sonora ${UpdateManager.currentVersion()}"

    /** Автоматическая проверка обновлений (раз в сутки). */
    fun setUpdateAutoCheck(enabled: Boolean) {
        updateSettings { it.copy(updateAutoCheck = enabled) }
        UpdateManager.setAutoCheck(enabled)
        toast(if (enabled) "Автопроверка обновлений включена" else "Автопроверка обновлений выключена")
        if (enabled) checkForUpdates()
    }

    /** Проверить обновления по кнопке. */
    fun checkForUpdates() {
        viewModelScope.launch(Dispatchers.IO) {
            val info = runCatching { UpdateManager.checkForUpdates(force = true) }.getOrNull()
            withContext(Dispatchers.Main) {
                toast(
                    when {
                        info != null -> "Доступна Sonora ${info.versionName}"
                        UpdateManager.error.value != null -> UpdateManager.error.value!!
                        else -> "У вас последняя версия"
                    },
                )
            }
        }
    }

    /** Добровольное скачивание обновления и запуск системного установщика. */
    fun downloadUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            toast("Скачиваем обновление…")
            // GitHub доступен напрямую: туннель WARP нужен для YouTube, а
            // большое APK через него качается медленно и рвётся. Пауза туннеля
            // ускоряет загрузку, после — возвращаем WARP, если он был включён.
            val warpWasConnected = graph.warp.status.value.state == WarpState.CONNECTED
            if (warpWasConnected) {
                runCatching { graph.warp.stop() }
                // Даём Android убрать VPN-интерфейс и вернуть обычную сеть:
                // иначе первые запросы уходят в уже мёртвый туннель.
                val deadline = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < deadline &&
                    graph.warp.status.value.state != WarpState.DISCONNECTED &&
                    graph.warp.status.value.state != WarpState.NOT_CONFIGURED
                ) {
                    delay(150)
                }
                delay(400)
            }
            try {
                var ok = runCatching { UpdateManager.downloadAndInstall() }.getOrDefault(false)
                if (!ok && warpWasConnected) {
                    // Резерв: если без туннеля скачать не вышло — возвращаем WARP
                    // и пробуем ещё раз (медленнее, но работает).
                    runCatching { StreamingAutoPilot.ensureWarp() }
                    ok = runCatching { UpdateManager.downloadAndInstall() }.getOrDefault(false)
                }
                if (!ok) {
                    withContext(Dispatchers.Main) {
                        toast(UpdateManager.error.value ?: "Не удалось скачать обновление")
                    }
                }
            } finally {
                if (warpWasConnected) runCatching { StreamingAutoPilot.ensureWarp() }
            }
        }
    }

    fun cancelUpdateDownload() = UpdateManager.cancelDownload()

    fun dismissUpdate() = UpdateManager.dismiss()

    fun updatePermissionIntent(): Intent? = UpdateManager.installPermissionIntent()

    /** Открыть страницу релизов в браузере (если хочется скачать вручную). */
    fun openReleasePage() {
        runCatching {
            graph.appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(UpdateManager.releasePageUrl()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Плеер не смог открыть поток (403/сеть). Сбрасываем кэши и просим
     * автопилот подобрать другой способ; затем переоткрываем текущий трек.
     */
    private fun onPlaybackStreamError(error: Throwable?) {
        if (!graph.settings.current.autoFailover) return
        val current = graph.playback.state.value.current ?: return
        val videoId = youtubeVideoId(current) ?: return
        if (recoveryTrackId != current.id) {
            recoveryTrackId = current.id
            recoveryAttempts = 0
        }
        if (recovering || recoveryAttempts >= 2) return
        recoveryAttempts++
        recovering = true
        Log.i("AutoPilot", "stream error: ${error?.message?.take(120)}; recover $videoId")
        viewModelScope.launch(Dispatchers.IO) {
            val label = runCatching { StreamingAutoPilot.recover(videoId) }.getOrNull()
            _autoPilotAudit.value = StreamingAutoPilot.lastAudit()
            _autoPilotMessage.value = StreamingAutoPilot.lastAudit()?.summaryLine()
            withContext(Dispatchers.Main) {
                recovering = false
                if (label != null) {
                    toast("Поток переключён: $label")
                    graph.playback.reloadCurrent()
                } else {
                    toast("Не удалось подобрать обход. Проверьте «Поток и сеть».")
                }
            }
        }
    }

    /** Автообновление рантайма yt-dlp из GitHub. */
    fun setYtDlpAutoUpdate(enabled: Boolean) {
        updateSettings { it.copy(ytDlpAutoUpdate = enabled) }
        if (enabled) updateYtDlp()
    }

    /** Обновление yt-dlp по кнопке: встроенная сборка быстро устаревает. */
    fun updateYtDlp() {
        viewModelScope.launch(Dispatchers.IO) {
            _ytDlpStatus.value = "Обновляем yt-dlp…"
            val version = runCatching { graph.ytDlp.ensureUpToDate(force = true) }.getOrNull()
            _ytDlpStatus.value = if (version != null) {
                "yt-dlp $version"
            } else {
                "yt-dlp: обновить не удалось (нет сети?)"
            }
            toast(_ytDlpStatus.value!!)
        }
    }

    /** Показать текущую версию yt-dlp в настройках. */
    fun refreshYtDlpStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graph.ytDlp.ensureInit() }
            val version = graph.ytDlp.versionLabel()
            _ytDlpStatus.value = version?.let { "yt-dlp $it" } ?: "yt-dlp: версия неизвестна"
        }
    }

    /** Проверка зеркал медиашлюза: какие реально отдают звук. */
    fun checkRelayHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            _relayCheck.value = "Проверяем зеркала…"
            val list = runCatching { RelayGateway.healthCheck() }.getOrDefault(emptyList())
            _relayHealth.value = list
            val working = list.count { it.ok }
            _relayCheck.value = when {
                list.isEmpty() -> "Зеркала не настроены"
                working == 0 -> "Ни одно из ${list.size} зеркал не ответило"
                else -> "Работают $working из ${list.size}"
            }
            toast(_relayCheck.value!!)
        }
    }

    /**
     * Диагностика потока: прямой YouTube, yt-dlp и медиашлюз по очереди.
     * Показывает, что именно блокируется и какой путь работает без VPN/WARP.
     */
    fun checkStreamAccess() {
        viewModelScope.launch(Dispatchers.IO) {
            _streamCheck.value = "Проверяем поток…"
            val current = graph.playback.state.value.current
            val videoId = current?.let { youtubeVideoId(it) } ?: TEST_VIDEO_ID
            val lines = ArrayList<String>()
            lines += "Видео: $videoId"

            val direct = runCatching { graph.youtube.diagnoseDirectStream(videoId) }
                .getOrElse { "ошибка: ${it.message?.take(80)}" }
            lines += "Прямой YouTube → $direct"
            val directOk = direct.contains("OK")

            val ytDlpVersion = runCatching { graph.ytDlp.ensureInit(); graph.ytDlp.versionLabel() }
                .getOrNull()
            val ytDlpHandle = runCatching { graph.ytDlp.streamHandle(videoId) }.getOrNull()
            lines += if (ytDlpHandle != null) {
                "yt-dlp ${ytDlpVersion ?: ""} → OK (${ytDlpHandle.mimeType})"
            } else {
                "yt-dlp ${ytDlpVersion ?: ""} → нет играющей ссылки"
            }

            val relay = runCatching { RelayGateway.resolve(videoId, null) }.getOrNull()
            lines += if (relay != null) {
                "Медиашлюз → OK · ${relay.instance} · ${relay.bitrate / 1000} кбит/с"
            } else {
                "Медиашлюз → ни одно зеркало не ответило"
            }

            val summary = when {
                directOk -> "OK: прямой поток доступен"
                ytDlpHandle != null -> "OK: yt-dlp получил играющую ссылку"
                relay != null -> "OK: играет через медиашлюз, без VPN и WARP"
                else -> "Поток недоступен: добавьте зеркало или включите WARP"
            }
            _streamCheck.value = summary
            _streamCheckDetails.value = buildString {
                appendLine(summary)
                appendLine()
                appendLine(lines.joinToString("\n"))
                appendLine()
                when {
                    directOk -> append("Прямой доступ работает: VPN и WARP не нужны.")
                    relay != null -> append(
                        "Прямой YouTube блокируется с вашего IP, но медиашлюз отдаёт звук — " +
                            "стриминг работает без VPN и WARP.",
                    )
                    else -> append(
                        "Ни один путь не дал звук. Проверьте интернет, добавьте своё зеркало " +
                            "в «Серверы шлюза» или включите WARP.",
                    )
                }
            }
            toast(summary)
        }
    }

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
        // Чтение JSON-индекса и перебор сетевых интерфейсов — не на главном потоке.
        viewModelScope.launch(Dispatchers.IO) {
            _warpConfigs.value = graph.warp.configs()
            _warpAutoRotate.value = graph.warp.isAutoRotate()
            _warpExternalVpn.value = graph.warp.externalVpnActive()
        }
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

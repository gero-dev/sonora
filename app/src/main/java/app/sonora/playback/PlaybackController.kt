package app.sonora.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.widget.SonoraWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val TAG = "SonoraPlayback"

/**
 * UI-состояние плеера. Источник истины — очередь MediaController; [queue] и
 * [queueSections] собираются из реальных медиаэлементов плеера, поэтому UI
 * никогда не расходится с тем, что играет.
 *
 * Новые поля добавлены с значениями по умолчанию, чтобы остальные экраны
 * (PlayerScreen/HomeScreen/…) продолжали компилироваться без изменений.
 */
data class PlayerUiState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    /** elapsedRealtime() в момент сэмпла: лирика интерполирует позицию между тиками. */
    val positionSampledAtMs: Long = 0L,
    val durationMs: Long = 0L,
    val index: Int = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val queue: List<Track> = emptyList(),
    /** Секция каждого элемента [queue] (индекс в индекс). */
    val queueSections: List<QueueSection> = emptyList(),
    /** Подпись контекста для «Далее из: …»; null — контекст неизвестен. */
    val contextTitle: String? = null,
    val sleepEndsAt: Long? = null,
) {
    val current: Track? get() = queue.getOrNull(index)

    val hasNext: Boolean
        get() = if (repeatMode == Player.REPEAT_MODE_ALL) {
            queue.isNotEmpty()
        } else {
            index < queue.lastIndex
        }

    val hasPrevious: Boolean get() = index > 0

    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Глобальные индексы секции «Далее в очереди» (добавлено вручную). */
    val userQueueIndices: List<Int>
        get() = (index + 1 until queue.size)
            .filter { queueSections.getOrNull(it) == QueueSection.USER }

    /** Глобальные индексы секции «Далее из: контекст». */
    val contextQueueIndices: List<Int>
        get() = (index + 1 until queue.size)
            .filter { queueSections.getOrNull(it) == QueueSection.CONTEXT }

    val userQueue: List<Track> get() = userQueueIndices.map { queue[it] }
    val contextQueue: List<Track> get() = contextQueueIndices.map { queue[it] }
}

/**
 * Обёртка над MediaController. Все методы вызываются с главного потока.
 *
 * Очередь живёт в одном месте — в плейлисте MediaController. [entries] лишь
 * повторяет его порядок и добавляет секции; при любом расхождении очередь
 * пересобирается из плеера (mediaId), а не наоборот. Благодаря этому «вперёд»,
 * выбор трека в очереди и восстановление после перезапуска не могут уехать
 * на соседний индекс.
 */
class PlaybackController(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controller: MediaController? = null
    private var connecting = false
    private var sleepJob: Job? = null
    private var lastPersistAt = 0L
    private var lastPersistTrackId: String? = null
    private var lastPersistPlaying: Boolean? = null
    private var lastPersistRepeat: Int? = null
    private var lastPersistShuffle: Boolean? = null
    private var restoredSession = false

    /** Пока идёт пакетное изменение очереди, снимок состояния не пересчитываем. */
    private var suppressingRefresh = false

    /** Защита от рекурсии, пока авто-переключение в конце очереди двигает плеер. */
    private var autoAdvancing = false

    /** Метка времени последнего «в начало трека»: второе нажатие «назад» уже листает. */
    private var lastRestartAtMs = 0L

    /** Не даём двойному срабатыванию конца очереди прокрутить два трека. */
    private var lastEndAdvanceAtMs = 0L

    /** Когда media3 сам переключил трек (для диагностики ручной гонки). */
    private var lastAutoTransitionAtMs = 0L

    private var lastLoggedIndex = -1

    /**
     * Позиция, на которую пользователь только что перемотал, но плеер ещё не
     * применил её.
     *
     * Нужна потому, что `seekTo` в media3 асинхронный: после вызова
     * `controller.seekTo(x)` свойство `currentPosition` ещё некоторое время
     * возвращает СТАРОЕ значение. Пока оно не обновилось, фоновый цикл
     * прогресса и `refresh()` успевали записать старое значение в состояние, и
     * ползунок отскакивал назад — та самая «некорректная перемотка вперёд»,
     * которая воспроизводилась тем чаще, чем ближе к моменту перемотки
     * приходился очередной тик цикла.
     *
     * Пока значение не `null`, позиция из плеера игнорируется в пользу
     * [pendingSeekTargetMs]. Снимается по `onPositionDiscontinuity` с причиной
     * `SEEK` — это авторитетный момент, когда плеер применил перемотку, — либо
     * по таймауту, если событие потерялось.
     */
    private var pendingSeekTargetMs: Long? = null
    private var pendingSeekIssuedAtMs = 0L

    /** Регистр треков: mediaId → Track. Метаданные для state.queue берём отсюда. */
    private val trackRegistry = HashMap<String, Track>()

    /**
     * Очередь в фактическом порядке плеера. [QueueEntry] не хранит Track целиком,
     * чтобы планировщик очереди оставался чистым и тестируемым без Android.
     */
    private var entries: List<QueueEntry> = emptyList()

    /** UID контекстных треков в исходном порядке — чтобы выключить shuffle. */
    private var originalContextOrder: List<Long> = emptyList()

    private var nextUid = 1L
    private var contextTitle: String? = null

    /**
     * Размер очереди, для которого посчитан [contextTitle]. Нужен, чтобы
     * пересчитывать подпись «Далее из: …» при изменении состава очереди и не
     * делать это на каждом обновлении позиции.
     */
    private var contextTitleSize = -1

    // Отложенные действия, если контроллер ещё подключается (холодный старт).
    private var pendingNext = false
    private var pendingToggle = false
    private var pendingPlay: PendingPlay? = null
    private val pendingQueueOps = ArrayDeque<() -> Unit>()

    private data class PendingPlay(val tracks: List<Track>, val startIndex: Int)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /**
     * Ошибка источника (403/сеть/протухшая ссылка). MainViewModel по этому
     * сигналу запускает автопилот и переоткрывает трек через другой способ.
     */
    var onStreamError: ((Throwable?) -> Unit)? = null

    /** Колбэк «дай ещё треков»: выставляет MainViewModel для бесконечной очереди. */
    var queueExtender: (() -> List<Track>)? = null

    /** true, когда есть живой MediaController (нужно сервису для media-кнопок). */
    internal val isConnected: Boolean get() = controller != null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            ) {
                lastAutoTransitionAtMs = elapsed()
            }
            val c = controller
            if (c != null) {
                android.util.Log.i(
                    TAG,
                    "transition reason=${reasonName(reason)} from=$lastLoggedIndex " +
                        "to=${c.currentMediaItemIndex} count=${c.mediaItemCount} " +
                        "repeat=${repeatName(c.repeatMode)} shuffle=${_state.value.shuffle} " +
                        "id=${mediaItem?.mediaId ?: "-"}",
                )
            }
            refresh()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Конец очереди: докупаем пачку и стартуем следующий трек.
            if (playbackState == Player.STATE_ENDED) onQueueEnded()
            refresh()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                onSeekApplied(newPosition.positionMs)
            }
            refresh()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            android.util.Log.w(TAG, "player error: ${error.errorCodeName} ${error.message?.take(120)}")
            onStreamError?.invoke(error)
            refresh()
        }
    }

    init {
        instance = this
        connect()
        startProgressLoop()
    }

    private fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val sessionToken = SessionToken(
            appContext,
            ComponentName(appContext, PlaybackService::class.java),
        )
        val future = MediaController.Builder(appContext, sessionToken).buildAsync()
        future.addListener(
            {
                connecting = false
                val c = runCatching { future.get() }.getOrNull()
                if (c == null) {
                    android.util.Log.w(TAG, "controller connect failed")
                    return@addListener
                }
                controller = c
                c.addListener(playerListener)
                seedRegistryFromSnapshot()
                _state.update { it.copy(connected = true) }
                restoreLastSession(c)
                refresh()
                if (pendingNext) {
                    pendingNext = false
                    next()
                }
                if (pendingToggle) {
                    pendingToggle = false
                    togglePlayPause()
                }
                pendingPlay?.let { pending ->
                    pendingPlay = null
                    playQueueNow(c, pending.tracks, pending.startIndex)
                }
                while (pendingQueueOps.isNotEmpty()) {
                    runCatching { pendingQueueOps.removeFirst().invoke() }
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private fun startProgressLoop() {
        scope.launch {
            while (isActive) {
                val c = controller
                if (c != null && c.isPlaying) {
                    // Через effectivePosition: пока перемотка не применена,
                    // currentPosition возвращает старое значение и ползунок
                    // отскакивал назад.
                    val position = effectivePosition(c)
                    val sampledAt = elapsed()
                    _state.update {
                        it.copy(positionMs = position, positionSampledAtMs = sampledAt)
                    }
                    persistState()
                }
                delay(500)
            }
        }
    }

    // ---------------- Синхронизация состояния с плеером ----------------

    private fun refresh() {
        if (suppressingRefresh) return
        val c = controller ?: return
        // Порядок проигрывания задаёт наша очередь; media3-shuffle не используем,
        // иначе пользовательская очередь перемешалась бы с контекстом.
        if (c.shuffleModeEnabled) {
            android.util.Log.w(TAG, "player shuffle detected: switch to order-based shuffle")
            runCatching { c.shuffleModeEnabled = false }
        }
        reconcileQueueFromPlayer(c)
        // Подпись «Далее из: …» выведена из состава очереди, поэтому её нужно
        // пересчитывать при каждом изменении размера. Иначе после удаления или
        // дописывания треков она сообщает прежнее число («Очередь · 20 треков»
        // при десяти в очереди).
        if (entries.size != contextTitleSize) {
            contextTitle = QueueModel.inferContextTitle(entries.map { trackOf(it.trackId) })
            contextTitleSize = entries.size
        }
        val duration = runCatching { c.duration }.getOrDefault(0L)
        val position = effectivePosition(c)
        val index = runCatching { c.currentMediaItemIndex }.getOrDefault(0).coerceAtLeast(0)
        lastLoggedIndex = index
        _state.update { st ->
            st.copy(
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                positionMs = position,
                positionSampledAtMs = elapsed(),
                durationMs = if (duration > 0) duration else st.current?.durationMs ?: 0L,
                index = index,
                repeatMode = c.repeatMode,
                speed = c.playbackParameters.speed,
                queue = entries.map { trackOf(it.trackId) },
                queueSections = entries.map { it.section },
                contextTitle = contextTitle,
            )
        }
        persistState()
    }

    /**
     * Приводит [entries] в соответствие с реальными медиаэлементами плеера.
     * Если плеер и UI разошлись (сервис пересоздал очередь, пакетное удаление,
     * восстановление), побеждает плеер — UI не может «указать» на чужой индекс.
     */
    private fun reconcileQueueFromPlayer(c: MediaController) {
        val count = c.mediaItemCount
        if (count == 0) {
            if (entries.isNotEmpty()) {
                android.util.Log.w(TAG, "queue drift: player is empty, ui=${entries.size} -> cleared")
                entries = emptyList()
            }
            return
        }
        val old = entries
        var same = count == old.size
        if (same) {
            for (i in 0 until count) {
                val id = runCatching { c.getMediaItemAt(i).mediaId }.getOrNull()
                if (id == null || old[i].trackId != id) {
                    same = false
                    break
                }
            }
        }
        if (same) return
        val pools = HashMap<String, ArrayDeque<QueueEntry>>()
        old.forEach { pools.getOrPut(it.trackId) { ArrayDeque() }.addLast(it) }
        val rebuilt = ArrayList<QueueEntry>(count)
        for (i in 0 until count) {
            val item = runCatching { c.getMediaItemAt(i) }.getOrNull() ?: continue
            val id = item.mediaId
            val previous = pools[id]?.removeFirstOrNull()
            if (trackRegistry[id] == null) {
                trackRegistry[id] = mediaItemToTrack(item)
            }
            rebuilt += previous?.copy(trackId = id)
                ?: QueueEntry(id, QueueSection.CONTEXT, nextUid++)
        }
        if (originalContextOrder.isEmpty()) {
            originalContextOrder = rebuilt
                .filter { it.section == QueueSection.CONTEXT }
                .map { it.uid }
        }
        android.util.Log.w(
            TAG,
            "queue drift: ui=${old.size} player=$count -> rebuilt=${rebuilt.size}",
        )
        entries = rebuilt
    }

    // ---------------- Восстановление ----------------

    private fun seedRegistryFromSnapshot() {
        PlaybackSnapshotStore.load(appContext).queue.forEach { trackRegistry.putIfAbsent(it.id, it) }
    }

    /**
     * Восстановление последнего трека/очереди после запуска приложения.
     * Играть автоматически не начинаем: пользователь сам нажмёт Play.
     *
     * Живую очередь сервиса НИКОГДА не перезаписываем снимком — именно этот
     * сценарий давал «подмену трека на resume при неизменном id».
     */
    private fun restoreLastSession(c: MediaController) {
        if (restoredSession) return
        restoredSession = true
        val snapshot = PlaybackSnapshotStore.load(appContext)
        // Старые версии держали перемешивание в самом плеере; теперь порядок
        // уже сохранён в очереди, media3-shuffle всегда выключен.
        if (c.shuffleModeEnabled) runCatching { c.shuffleModeEnabled = false }
        when (QueueModel.restorePlan(snapshot.queue.size, c.mediaItemCount)) {
            RestorePlan.USE_PLAYER -> {
                adoptPlayerQueue(c, snapshot)
                contextTitle = snapshot.contextTitle
                _state.update { it.copy(shuffle = snapshot.shuffle) }
                android.util.Log.i(
                    TAG,
                    "restore action=use_player count=${c.mediaItemCount} " +
                        "snapshot=${snapshot.queue.size} " +
                        "repeat=${repeatName(runCatching { c.repeatMode }.getOrNull())} " +
                        "shuffle=${snapshot.shuffle}",
                )
            }
            RestorePlan.LOAD_SNAPSHOT -> {
                entries = QueueModel.entriesFromSnapshot(
                    snapshot.queue,
                    snapshot.queueSections,
                    nextUid,
                )
                nextUid += snapshot.queue.size
                originalContextOrder = QueueModel.matchOriginalOrder(
                    entries,
                    snapshot.contextOrderIds,
                ).ifEmpty {
                    entries.filter { it.section == QueueSection.CONTEXT }.map { it.uid }
                }
                contextTitle = snapshot.contextTitle
                val index = snapshot.index.coerceIn(0, snapshot.queue.lastIndex)
                runCatching {
                    c.setMediaItems(
                        snapshot.queue.map(::toMediaItem),
                        index,
                        snapshot.positionMs.coerceAtLeast(0L),
                    )
                    c.prepare()
                    c.pause()
                    // Режимы применяем после prepare: некоторые состояния плеера
                    // до готовности очереди игнорируют setRepeatMode.
                    c.repeatMode = snapshot.repeatMode
                    if (c.repeatMode != snapshot.repeatMode) {
                        c.repeatMode = snapshot.repeatMode
                    }
                }.onFailure { android.util.Log.w(TAG, "restore failed: ${it.message}") }
                _state.update {
                    it.copy(
                        queue = entries.map { entry -> trackOf(entry.trackId) },
                        queueSections = entries.map { it.section },
                        contextTitle = contextTitle,
                        shuffle = snapshot.shuffle,
                        repeatMode = runCatching { c.repeatMode }.getOrDefault(snapshot.repeatMode),
                        index = index,
                        positionMs = snapshot.positionMs,
                        isPlaying = false,
                    )
                }
                android.util.Log.i(
                    TAG,
                    "restore action=load_snapshot count=${snapshot.queue.size} index=$index " +
                        "repeat=${repeatName(runCatching { c.repeatMode }.getOrDefault(snapshot.repeatMode))} " +
                        "shuffle=${snapshot.shuffle}",
                )
            }
            RestorePlan.NONE -> android.util.Log.i(TAG, "restore action=none")
        }
    }

    /**
     * Сервис уже держит очередь (например, приложение перезапустили во время
     * воспроизведения): порядок берём из плеера, метаданные — из снимка по mediaId.
     */
    private fun adoptPlayerQueue(c: MediaController, snapshot: PlaybackSnapshot) {
        val count = c.mediaItemCount
        val items = ArrayList<MediaItem>(count)
        for (i in 0 until count) {
            runCatching { c.getMediaItemAt(i) }.getOrNull()?.let { items += it }
        }
        val playerIds = items.map { it.mediaId }
        if (playerIds == snapshot.queue.map { it.id }) {
            entries = QueueModel.entriesFromSnapshot(snapshot.queue, snapshot.queueSections, 1L)
            nextUid = 1L + entries.size
            originalContextOrder = QueueModel.matchOriginalOrder(entries, snapshot.contextOrderIds)
                .ifEmpty { entries.filter { it.section == QueueSection.CONTEXT }.map { it.uid } }
            snapshot.queue.forEach { trackRegistry.putIfAbsent(it.id, it) }
            return
        }
        // Расхождение размера/порядка: очередь из плеера, треки — из снимка.
        val trackPools = HashMap<String, ArrayDeque<Track>>()
        snapshot.queue.forEach { trackPools.getOrPut(it.id) { ArrayDeque() }.addLast(it) }
        val sectionPools = HashMap<String, ArrayDeque<QueueSection>>()
        snapshot.queue.forEachIndexed { index, track ->
            sectionPools.getOrPut(track.id) { ArrayDeque() }.addLast(snapshot.sectionAt(index))
        }
        val rebuilt = ArrayList<QueueEntry>(items.size)
        items.forEach { item ->
            val id = item.mediaId
            val track = trackPools[id]?.removeFirstOrNull() ?: mediaItemToTrack(item)
            trackRegistry[id] = track
            val section = sectionPools[id]?.removeFirstOrNull() ?: QueueSection.CONTEXT
            rebuilt += QueueEntry(id, section, nextUid++)
        }
        entries = rebuilt
        originalContextOrder = rebuilt.filter { it.section == QueueSection.CONTEXT }.map { it.uid }
        android.util.Log.w(
            TAG,
            "queue drift on restore: snapshot=${snapshot.queue.size} player=$count -> player wins",
        )
    }

    /**
     * Сохраняет очередь/трек/позицию для виджета и следующего запуска.
     * Троттлинг: принудительно — при смене трека/статуса, иначе раз в 2 с.
     */
    private fun persistState(force: Boolean = false) {
        val state = _state.value
        if (state.queue.isEmpty()) {
            PlaybackSnapshotStore.clear(appContext)
            lastPersistTrackId = null
            lastPersistPlaying = null
            lastPersistRepeat = null
            lastPersistShuffle = null
            return
        }
        val trackId = state.current?.id
        // Режимы повтора/перемешивания тоже должны попадать в снимок сразу:
        // раньше троттлинг мог пропустить переключение repeat, и после
        // kill/relaunch приложение поднимало уже устаревший OFF.
        val important = force ||
            trackId != lastPersistTrackId ||
            state.isPlaying != lastPersistPlaying ||
            state.repeatMode != lastPersistRepeat ||
            state.shuffle != lastPersistShuffle
        val now = elapsed()
        if (!important && now - lastPersistAt < PERSIST_INTERVAL_MS) return
        lastPersistAt = now
        lastPersistTrackId = trackId
        lastPersistPlaying = state.isPlaying
        lastPersistRepeat = state.repeatMode
        lastPersistShuffle = state.shuffle
        PlaybackSnapshotStore.save(
            appContext,
            PlaybackSnapshot(
                version = PlaybackSnapshot.CURRENT_VERSION,
                queue = entries.map { trackOf(it.trackId) },
                queueSections = QueueModel.sectionsSnapshot(entries),
                contextTitle = contextTitle,
                contextOrderIds = contextOrderIds(),
                index = state.index,
                positionMs = state.positionMs,
                shuffle = state.shuffle,
                repeatMode = state.repeatMode,
                isPlaying = state.isPlaying,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        SonoraWidget.updateAll(appContext)
    }

    /** mediaId контекстных треков в исходном порядке (для выключения shuffle). */
    private fun contextOrderIds(): List<String> {
        val rank = HashMap<Long, Int>(originalContextOrder.size * 2)
        originalContextOrder.forEachIndexed { index, uid -> rank[uid] = index }
        return entries.filter { it.section == QueueSection.CONTEXT }
            .sortedWith(
                compareBy<QueueEntry>(
                    { rank[it.uid] ?: Int.MAX_VALUE },
                    { it.uid },
                ),
            )
            .map { it.trackId }
    }

    // ---------------- Воспроизведение ----------------

    /** Запускает список треков с позиции [startIndex]. Неиграемые треки отбрасываются. */
    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        val c = controller
        if (c == null) {
            pendingPlay = PendingPlay(tracks, startIndex)
            connect()
            return
        }
        playQueueNow(c, tracks, startIndex)
    }

    private fun playQueueNow(c: MediaController, tracks: List<Track>, startIndex: Int) {
        val playable = tracks.filter { it.playable && !it.uri.isNullOrBlank() }
        if (playable.isEmpty()) {
            android.util.Log.w(TAG, "playQueue: no playable tracks")
            return
        }
        val startTrack = tracks.getOrNull(startIndex)
        val startAt = playable.indexOfFirst { it.id == startTrack?.id }
        val shuffle = _state.value.shuffle
        val ordered: List<Track>
        val index: Int
        if (shuffle && playable.size > 1) {
            val seedIndex = if (startAt >= 0) startAt else 0
            val seed = playable[seedIndex]
            ordered = listOf(seed) + playable.filterIndexed { i, _ -> i != seedIndex }
                .shuffled(Random.Default)
            index = 0
        } else {
            ordered = playable
            index = startAt.coerceAtLeast(0)
        }
        registerTracks(ordered)
        entries = ordered.map { QueueEntry(it.id, QueueSection.CONTEXT, nextUid++) }
        originalContextOrder = entries.map { it.uid }
        contextTitle = QueueModel.inferContextTitle(ordered)
        runCatching {
            c.setMediaItems(ordered.map(::toMediaItem), index, 0L)
            c.prepare()
            c.play()
        }.onFailure { android.util.Log.w(TAG, "playQueue failed: ${it.message}") }
        android.util.Log.i(
            TAG,
            "playQueue count=${ordered.size} start=$index shuffle=$shuffle " +
                "repeat=${repeatName(c.repeatMode)}",
        )
        refresh()
    }

    fun playSingle(track: Track) = playQueue(listOf(track), 0)

    fun togglePlayPause() {
        val c = controller
        if (c == null) {
            pendingToggle = true
            connect()
            return
        }
        when {
            c.isPlaying -> c.pause()
            c.playbackState == Player.STATE_ENDED -> {
                ensureQueueLoaded(c)
                if (c.mediaItemCount > 0) {
                    c.seekTo(c.currentMediaItemIndex.coerceAtLeast(0), 0L)
                }
                c.play()
            }
            c.playbackState == Player.STATE_IDLE -> {
                ensureQueueLoaded(c)
                c.prepare()
                c.play()
            }
            else -> c.play()
        }
    }

    /**
     * Следующий трек. Работает и в REPEAT_ONE (переходит на реального соседа),
     * не теряет нажатие до подключения контроллера и не прокручивает два
     * трека, если очередь кончилась и пачку пришлось докупать.
     */
    fun next() {
        val c = controller
        if (c == null) {
            pendingNext = true
            connect()
            return
        }
        if (c.mediaItemCount == 0) ensureQueueLoaded(c)
        advance(manual = true)
    }

    private fun advance(manual: Boolean) {
        val c = controller ?: return
        val count = c.mediaItemCount
        if (count == 0) {
            android.util.Log.i(TAG, "advance reason=${advanceReason(manual)} skipped=empty_queue")
            return
        }
        val from = c.currentMediaItemIndex
        val repeat = c.repeatMode
        var target = QueueModel.nextIndex(from, count, repeat)
        if (target == QueueModel.NONE) {
            val batch = requestQueueExtension()
            if (batch.isNotEmpty()) {
                appendToQueueInternal(batch)
                target = QueueModel.nextIndex(from, c.mediaItemCount, repeat)
            }
        }
        if (target == QueueModel.NONE) {
            android.util.Log.i(
                TAG,
                "advance reason=${advanceReason(manual)} from=$from to=-1 " +
                    "count=$count repeat=${repeatName(repeat)} shuffle=${_state.value.shuffle} " +
                    "result=end_of_queue",
            )
            return
        }
        val wasPlaying = c.isPlaying || c.playbackState == Player.STATE_ENDED
        c.seekTo(target, 0L)
        if (wasPlaying) c.play()
        android.util.Log.i(
            TAG,
            "advance reason=${advanceReason(manual)} from=$from to=$target " +
                "count=${c.mediaItemCount} repeat=${repeatName(repeat)} " +
                "shuffle=${_state.value.shuffle} " +
                "id=${runCatching { c.getMediaItemAt(target).mediaId }.getOrNull() ?: "-"}",
        )
    }

    /**
     * «Назад»: далеко от начала — в начало текущего трека, иначе на реальный
     * предыдущий. Работает в REPEAT_ONE так же, как ручной «вперёд».
     */
    fun previous() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            ensureQueueLoaded(c)
            if (c.mediaItemCount == 0) return
        }
        val current = c.currentMediaItemIndex
        val now = elapsed()
        val position = runCatching { c.currentPosition }.getOrDefault(0L)
        val justRestarted = lastRestartAtMs != 0L && now - lastRestartAtMs <= PREVIOUS_TAP_WINDOW_MS
        if (position > PREVIOUS_RESTART_MS && !justRestarted) {
            lastRestartAtMs = now
            c.seekTo(current, 0L)
            android.util.Log.i(TAG, "previous reason=manual from=$current to=$current result=restart")
            return
        }
        val target = QueueModel.previousIndex(current, c.mediaItemCount, c.repeatMode)
        lastRestartAtMs = 0L
        if (target == current || target !in 0 until c.mediaItemCount) {
            c.seekTo(current.coerceAtLeast(0), 0L)
            android.util.Log.i(TAG, "previous reason=manual from=$current to=$current result=restart")
            return
        }
        val wasPlaying = c.isPlaying || c.playbackState == Player.STATE_ENDED
        c.seekTo(target, 0L)
        if (wasPlaying) c.play()
        android.util.Log.i(
            TAG,
            "previous reason=manual from=$current to=$target count=${c.mediaItemCount} " +
                "repeat=${repeatName(c.repeatMode)} shuffle=${_state.value.shuffle}",
        )
    }

    /**
     * Авто-переход в конце очереди: докупает пачку треков через [queueExtender]
     * и явно запускает следующий. Один вызов = максимум один трек, даже если
     * STATE_ENDED придёт повторно (раньше это давало двойной пропуск).
     */
    private fun onQueueEnded() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        if (autoAdvancing) return
        val now = elapsed()
        if (now - lastEndAdvanceAtMs < END_ADVANCE_COOLDOWN_MS) {
            android.util.Log.i(TAG, "advance reason=end ignored=cooldown")
            return
        }
        autoAdvancing = true
        lastEndAdvanceAtMs = now
        try {
            advance(manual = false)
        } finally {
            autoAdvancing = false
        }
    }

    private fun requestQueueExtension(): List<Track> =
        runCatching { queueExtender?.invoke() ?: emptyList() }
            .getOrDefault(emptyList())

    /**
     * Страховка на случай расхождения снимка и MediaController (сервис
     * перезапускался/очередь восстановлена): возвращаем items в плеер.
     */
    private fun ensureQueueLoaded(c: MediaController) {
        if (c.mediaItemCount > 0) return
        val snapshot = PlaybackSnapshotStore.load(appContext)
        if (snapshot.queue.isEmpty()) return
        registerTracks(snapshot.queue)
        entries = QueueModel.entriesFromSnapshot(snapshot.queue, snapshot.queueSections, nextUid)
        nextUid += snapshot.queue.size
        originalContextOrder = QueueModel.matchOriginalOrder(entries, snapshot.contextOrderIds)
            .ifEmpty { entries.filter { it.section == QueueSection.CONTEXT }.map { it.uid } }
        contextTitle = snapshot.contextTitle
        runCatching {
            c.setMediaItems(
                snapshot.queue.map(::toMediaItem),
                snapshot.index.coerceIn(0, snapshot.queue.lastIndex),
                snapshot.positionMs.coerceAtLeast(0L),
            )
            c.prepare()
            c.repeatMode = snapshot.repeatMode
        }
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        val c = controller
        if (c == null) {
            _state.update { it.copy(positionMs = target, positionSampledAtMs = elapsed()) }
            return
        }
        // Зажигаем «замороженную» позицию ДО вызова seekTo: иначе цикл прогресса
        // успевает записать старое значение между вызовом и этим присваиванием.
        pendingSeekTargetMs = target
        pendingSeekIssuedAtMs = elapsed()
        runCatching { c.seekTo(target) }
        _state.update {
            it.copy(
                positionMs = target,
                positionSampledAtMs = elapsed(),
            )
        }
    }

    /**
     * Позиция для показа: цель незавершённой перемотки, пока плеер её не
     * применил, иначе фактическая позиция плеера.
     */
    private fun effectivePosition(c: Player): Long {
        val pending = pendingSeekTargetMs
        if (pending != null) {
            val stillFresh = elapsed() - pendingSeekIssuedAtMs <= SEEK_SETTLE_TIMEOUT_MS
            if (stillFresh) return pending
            // Событие о перемотке потерялось — снимаем заморозку, иначе
            // ползунок навсегда застынет на цели.
            pendingSeekTargetMs = null
        }
        return runCatching { c.currentPosition }.getOrDefault(_state.value.positionMs)
    }

    /** Плеер сообщил о разрыве позиции — если это наша перемотка, снимаем заморозку. */
    private fun onSeekApplied(positionMs: Long) {
        val pending = pendingSeekTargetMs ?: return
        pendingSeekTargetMs = null
        // Округляем до 50 мс: плеер встаёт рядом с целью, но не точно в неё
        // (например, на границу кадра или ключевого кадра).
        val drift = kotlin.math.abs(positionMs - pending)
        if (drift > SEEK_DRIFT_LOG_MS) {
            android.util.Log.i(
                TAG,
                "seek drift: requested=$pending actual=$positionMs drift=$drift",
            )
        }
    }

    fun seekToIndex(index: Int) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) ensureQueueLoaded(c)
        reconcileQueueFromPlayer(c)
        if (index !in 0 until c.mediaItemCount) return
        c.seekTo(index, 0L)
        c.play()
        android.util.Log.i(
            TAG,
            "seek_to_index target=$index count=${c.mediaItemCount} " +
                "id=${runCatching { c.getMediaItemAt(index).mediaId }.getOrNull() ?: "-"}",
        )
    }

    fun setShuffle(enabled: Boolean) {
        if (_state.value.shuffle == enabled) return
        _state.update { it.copy(shuffle = enabled) }
        val c = controller ?: run {
            persistState(force = true)
            return
        }
        reconcileQueueFromPlayer(c)
        if (entries.isEmpty()) {
            persistState(force = true)
            return
        }
        val current = c.currentMediaItemIndex.coerceIn(0, entries.lastIndex)
        val target = if (enabled) {
            QueueModel.shuffledUpcoming(current, entries, Random.Default)
        } else {
            QueueModel.unshuffledUpcoming(current, entries, originalContextOrder)
        }
        applyReorder(c, QueueModel.permutationMoves(0, entries, target))
        android.util.Log.i(
            TAG,
            "shuffle enabled=$enabled current=$current count=${entries.size}",
        )
        refresh()
        persistState(force = true)
    }

    fun cycleRepeatMode() {
        val c = controller ?: return
        val next = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        c.repeatMode = next
        _state.update { it.copy(repeatMode = next) }
        // Сразу в снимок: иначе при kill в течение 2 с после переключения
        // после перезапуска восстанавливался старый режим.
        persistState(force = true)
        android.util.Log.i(TAG, "repeat mode=${repeatName(next)} persisted=true")
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    // ---------------- Очередь ----------------

    /**
     * «В очередь» (Add to queue): в конец пользовательской секции, то есть
     * сразу после других треков, добавленных вручную, но до контекста.
     */
    fun addToQueue(track: Track) {
        if (!track.playable || track.uri.isNullOrBlank()) return
        val c = controller
        if (c == null) {
            pendingQueueOps.addLast { addToQueue(track) }
            connect()
            return
        }
        reconcileQueueFromPlayer(c)
        val current = c.currentMediaItemIndex
        val at = QueueModel.addToQueueInsertIndex(current, entries)
            .coerceIn(0, c.mediaItemCount)
        registerTracks(listOf(track))
        suppressRefresh {
            c.addMediaItem(at, toMediaItem(track))
            entries = entries.insertEntry(at, QueueEntry(track.id, QueueSection.USER, nextUid++))
        }
        android.util.Log.i(TAG, "add_to_queue at=$at current=$current total=${entries.size}")
        refresh()
    }

    /** «Играть следующим»: в начало пользовательской секции (сразу после текущего). */
    fun playNext(track: Track) {
        if (!track.playable || track.uri.isNullOrBlank()) return
        val c = controller
        if (c == null) {
            pendingQueueOps.addLast { playNext(track) }
            connect()
            return
        }
        reconcileQueueFromPlayer(c)
        val current = c.currentMediaItemIndex
        val at = QueueModel.playNextInsertIndex(current, entries).coerceIn(0, c.mediaItemCount)
        registerTracks(listOf(track))
        suppressRefresh {
            c.addMediaItem(at, toMediaItem(track))
            entries = entries.insertEntry(at, QueueEntry(track.id, QueueSection.USER, nextUid++))
        }
        android.util.Log.i(TAG, "play_next at=$at current=$current total=${entries.size}")
        refresh()
    }

    /** «Играть следующими» для набора: порядок сохраняется, вставка после текущего. */
    fun playNext(tracks: List<Track>) {
        val c = controller
        if (c == null) {
            pendingQueueOps.addLast { playNext(tracks) }
            connect()
            return
        }
        val playable = tracks.filter { it.playable && !it.uri.isNullOrBlank() }
        if (playable.isEmpty()) return
        reconcileQueueFromPlayer(c)
        val current = c.currentMediaItemIndex
        var at = QueueModel.playNextInsertIndex(current, entries).coerceIn(0, c.mediaItemCount)
        registerTracks(playable)
        val added = ArrayDeque<QueueEntry>()
        suppressRefresh {
            playable.forEach { track ->
                c.addMediaItem(at, toMediaItem(track))
                added.addLast(QueueEntry(track.id, QueueSection.USER, nextUid++))
                at++
            }
            entries = entries.toMutableList().apply {
                addAll((at - playable.size).coerceIn(0, size), added)
            }
        }
        android.util.Log.i(
            TAG,
            "play_next_batch count=${playable.size} current=$current total=${entries.size}",
        )
        refresh()
    }

    /**
     * Подменяет метаданные уже стоящих в очереди треков (после онлайн-обогащения).
     * Текущий трек не пересоздаём, чтобы не сбить позицию/буферизацию.
     * Замена идёт строго по индексу элемента плеера, а не «по позиции в UI-списке».
     */
    fun applyMetadata(updated: List<Track>) {
        val c = controller ?: return
        if (updated.isEmpty()) return
        val byId = updated.associateBy { it.id }
        val currentIndex = runCatching { c.currentMediaItemIndex }.getOrDefault(-1)
        var changed = false
        entries = entries.mapIndexed { index, entry ->
            val better = byId[entry.trackId] ?: return@mapIndexed entry
            val current = trackRegistry[entry.trackId]
            if (current != null && current == better) return@mapIndexed entry
            trackRegistry[better.id] = better
            changed = true
            if (index != currentIndex && index < c.mediaItemCount) {
                runCatching { c.replaceMediaItem(index, toMediaItem(better)) }
            }
            entry
        }
        if (changed) refresh()
    }

    /**
     * Добавляет пачку треков в конец контекста (бесконечная очередь).
     * Не путать с «В очередь»: пользовательская секция не меняется.
     */
    fun appendToQueue(tracks: List<Track>) {
        val c = controller
        if (c == null) {
            pendingQueueOps.addLast { appendToQueue(tracks) }
            connect()
            return
        }
        appendToQueueInternal(tracks)
    }

    private fun appendToQueueInternal(tracks: List<Track>) {
        val c = controller ?: return
        val playable = tracks.filter { it.playable && !it.uri.isNullOrBlank() }
        if (playable.isEmpty()) return
        registerTracks(playable)
        suppressRefresh {
            val added = playable.map { track ->
                c.addMediaItem(toMediaItem(track))
                QueueEntry(track.id, QueueSection.CONTEXT, nextUid++)
            }
            entries = entries + added
            originalContextOrder = originalContextOrder + added.map { it.uid }
        }
        android.util.Log.i(
            TAG,
            "append_context count=${playable.size} total=${entries.size}",
        )
        refresh()
    }

    /**
     * Пересоздаёт текущий MediaItem: поток открывается заново и подхватывает
     * новое качество из настроек. Позиция и состояние play/pause сохраняются.
     */
    fun reloadCurrent() {
        val c = controller ?: return
        val index = runCatching { c.currentMediaItemIndex }.getOrDefault(-1)
        if (index < 0) return
        val track = trackRegistry[entries.getOrNull(index)?.trackId ?: return] ?: return
        val position = runCatching { c.currentPosition }.getOrDefault(0L)
        val wasPlaying = c.isPlaying
        runCatching {
            c.replaceMediaItem(index, toMediaItem(track))
            c.seekTo(index, position)
            if (wasPlaying) c.play()
        }
        refresh()
    }

    /** Удаляет элемент пользовательской секции после текущего. */
    fun removeAt(index: Int) {
        val c = controller ?: return
        reconcileQueueFromPlayer(c)
        if (index !in entries.indices) return
        val current = c.currentMediaItemIndex
        if (index == current) return
        if (entries[index].section != QueueSection.USER || index < current) {
            android.util.Log.i(
                TAG,
                "remove_at ignored index=$index current=$current section=${entries[index].section}",
            )
            return
        }
        suppressRefresh {
            runCatching { c.removeMediaItem(index) }
            entries = entries.filterIndexed { i, _ -> i != index }
        }
        android.util.Log.i(TAG, "remove_at index=$index current=$current total=${entries.size}")
        refresh()
    }

    /**
     * Удаляет из очереди треки, для которых [keep] вернул false. Текущий трек
     * остаётся на месте, чтобы не прерывать воспроизведение. Возвращает число удалённых.
     */
    fun removeWhere(keep: (Track) -> Boolean): Int {
        val c = controller ?: return 0
        reconcileQueueFromPlayer(c)
        val working = entries.toMutableList()
        val currentIndex = runCatching { c.currentMediaItemIndex }.getOrDefault(-1)
        var removed = 0
        suppressRefresh {
            for (index in working.indices.reversed()) {
                if (index == currentIndex) continue
                val track = trackOf(working[index].trackId)
                if (keep(track)) continue
                if (index >= c.mediaItemCount) continue
                val success = runCatching { c.removeMediaItem(index) }.isSuccess
                if (success) {
                    working.removeAt(index)
                    removed++
                }
            }
        }
        if (removed > 0) {
            entries = working
            refresh()
        }
        return removed
    }

    /**
     * Что стало с очередью после пересборки под новый фильтр.
     *
     * @param added сколько треков дописано в конец;
     * @param removed сколько убрано;
     * @param keptCurrent текущий трек не подошёл под фильтр, но доигрывает
     *   (менять его на ходу — значит оборвать музыку на середине).
     */
    data class RebuildResult(
        val added: Int,
        val removed: Int,
        val keptCurrent: Boolean,
    ) {
        val changed: Boolean get() = added > 0 || removed > 0 || keptCurrent
    }

    /**
     * Пересобирает очередь под новый фильтр: убирает неподходящие треки и
     * дописывает подходящие, которых в очереди не было.
     *
     * Нужна при переключении стриминга. Раньше включение стриминга только
     * пересобирало подборку волны, а очередь плеера не трогало: онлайн-треки
     * не появлялись, пока пользователь не запускал волну заново. Симметрично,
     * выключение стриминга вычищало онлайн-треки из очереди, поэтому включение
     * обратно уже не могло их вернуть — их там просто не оставалось.
     *
     * Текущий трек намеренно не трогаем: если он не проходит фильтр, он
     * доигрывает, а очередь вокруг него уже перестроена. Порядок уже стоящих
     * треков сохраняется — пересборка не должна тасовать то, что пользователь
     * слушает сейчас.
     *
     * Вся арифметика вынесена в [QueueModel.rebuildPlan] и покрыта юнит-тестами;
     * здесь остаются только вызовы MediaController.
     *
     * @param candidates треки, которыми можно дополнить очередь;
     * @param keep какие треки допустимы в очереди после смены режима.
     */
    fun rebuildQueue(candidates: List<Track>, keep: (Track) -> Boolean): RebuildResult {
        val c = controller ?: return RebuildResult(0, 0, false)
        reconcileQueueFromPlayer(c)
        val currentIndex = runCatching { c.currentMediaItemIndex }.getOrDefault(QueueModel.NONE)
        val queue = entries.map { trackOf(it.trackId) }
        val playableCandidates = candidates.filter { it.playable && !it.uri.isNullOrBlank() }
        val plan = QueueModel.rebuildPlan(queue, currentIndex, playableCandidates, keep)

        // 1. Убираем всё, что больше не подходит. Индексы приходят в убывающем
        //    порядке, поэтому удаление не сдвигает ещё не обработанные позиции.
        var removed = 0
        suppressRefresh {
            val working = entries.toMutableList()
            for (index in plan.removeIndices) {
                if (index >= c.mediaItemCount || index !in working.indices) continue
                if (runCatching { c.removeMediaItem(index) }.isSuccess) {
                    working.removeAt(index)
                    removed++
                }
            }
            if (removed > 0) entries = working
        }

        // 2. Дописываем подходящее. [plan.append] уже исключает то, что было в
        //    очереди до удаления; после удаления проверяем ещё раз, чтобы
        //    случайно не продублировать оставшийся трек.
        val present = entries.mapTo(HashSet()) { it.trackId }
        val fresh = plan.append.filter { it.id !in present }
        if (fresh.isEmpty()) {
            if (removed > 0) refresh()
            return RebuildResult(0, removed, plan.keptCurrent)
        }
        appendToQueueInternal(fresh)
        android.util.Log.i(
            TAG,
            "rebuild_queue added=${fresh.size} removed=$removed keptCurrent=${plan.keptCurrent}",
        )
        return RebuildResult(fresh.size, removed, plan.keptCurrent)
    }

    /** Перестановка внутри пользовательской секции после текущего трека. */
    fun move(from: Int, to: Int) {
        val c = controller ?: return
        reconcileQueueFromPlayer(c)
        if (from !in entries.indices || to !in entries.indices) return
        val current = c.currentMediaItemIndex
        if (from <= current || to <= current) return
        if (entries[from].section != QueueSection.USER ||
            entries[to].section != QueueSection.USER
        ) {
            return
        }
        suppressRefresh {
            runCatching { c.moveMediaItem(from, to) }
            entries = entries.toMutableList().apply { add(to, removeAt(from)) }
        }
        android.util.Log.i(TAG, "move from=$from to=$to current=$current")
        refresh()
    }

    fun clearQueue() {
        runCatching { controller?.clearMediaItems() }
        entries = emptyList()
        originalContextOrder = emptyList()
        contextTitle = null
        _state.update {
            it.copy(
                queue = emptyList(),
                queueSections = emptyList(),
                contextTitle = null,
                index = 0,
                positionMs = 0L,
            )
        }
        PlaybackSnapshotStore.clear(appContext)
        android.util.Log.i(TAG, "clear_queue")
    }

    // ---------------- Таймер сна ----------------

    fun startSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        val millis = minutes.coerceAtLeast(1) * 60_000L
        _state.update { it.copy(sleepEndsAt = System.currentTimeMillis() + millis) }
        sleepJob = scope.launch {
            delay(millis)
            controller?.pause()
            _state.update { it.copy(sleepEndsAt = null) }
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _state.update { it.copy(sleepEndsAt = null) }
    }

    // ---------------- Служебное ----------------

    fun release() {
        sleepJob?.cancel()
        scope.cancel()
        controller?.let { c ->
            runCatching { c.removeListener(playerListener) }
            runCatching { c.release() }
        }
        controller = null
        if (instance === this) instance = null
    }

    private inline fun suppressRefresh(block: () -> Unit) {
        suppressingRefresh = true
        try {
            block()
        } finally {
            suppressingRefresh = false
        }
    }

    private fun List<QueueEntry>.insertEntry(at: Int, entry: QueueEntry): List<QueueEntry> {
        val mutable = toMutableList()
        mutable.add(at.coerceIn(0, mutable.size), entry)
        return mutable
    }

    private fun applyReorder(c: MediaController, moves: List<QueueMove>) {
        if (moves.isEmpty()) return
        suppressRefresh {
            val working = entries.toMutableList()
            moves.forEach { move ->
                if (move.from !in working.indices || move.to !in working.indices) return@forEach
                val moved = runCatching { c.moveMediaItem(move.from, move.to) }.isSuccess
                if (!moved) return@forEach
                working.add(move.to, working.removeAt(move.from))
            }
            entries = working
        }
    }

    private fun registerTracks(tracks: List<Track>) {
        tracks.forEach { trackRegistry[it.id] = it }
    }

    private fun trackOf(id: String): Track =
        trackRegistry[id] ?: Track(id = id, title = id, artist = "", playable = false)

    /** Резервное восстановление Track из MediaItem (снимок недоступен). */
    private fun mediaItemToTrack(item: MediaItem): Track {
        val uri = runCatching { item.requestMetadata.mediaUri?.toString() }.getOrNull()
        val metadata = item.mediaMetadata
        val source = if (uri != null &&
            (uri.startsWith("content://") || uri.startsWith("file://"))
        ) {
            Source.LOCAL
        } else {
            Source.YOUTUBE
        }
        return Track(
            id = item.mediaId,
            title = metadata.title?.toString().orEmpty().ifBlank { item.mediaId },
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString(),
            uri = uri,
            artworkUri = metadata.artworkUri?.toString(),
            source = source,
            playable = uri != null,
        )
    }

    private fun toMediaItem(track: Track): MediaItem = trackToMediaItem(track)

    private fun elapsed(): Long = android.os.SystemClock.elapsedRealtime()

    private fun repeatName(mode: Int?): String = when (mode) {
        Player.REPEAT_MODE_ONE -> "ONE"
        Player.REPEAT_MODE_ALL -> "ALL"
        else -> "OFF"
    }

    private fun reasonName(reason: Int): String = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "playlist"
        else -> "other"
    }

    private fun advanceReason(manual: Boolean): String = if (manual) "manual" else "end"

    /** Константы общие с [PlaybackService]: виджет использует то же «назад», что и UI. */
    internal companion object {
        /** Позицию для восстановления/виджета сохраняем не чаще раза в 2 с. */
        const val PERSIST_INTERVAL_MS = 2_000L

        /**
         * Порог Spotify-поведения «назад»: дальше него первый клик только
         * возвращает текущий трек в начало.
         */
        const val PREVIOUS_RESTART_MS = 3_000L

        /** Окно, в котором повторный клик «назад» уже листает на предыдущий трек. */
        const val PREVIOUS_TAP_WINDOW_MS = 2_000L

        /** Защита от повторного авто-перехода в конце очереди. */
        const val END_ADVANCE_COOLDOWN_MS = 1_500L

        /**
         * Сколько держать «замороженную» позицию перемотки, если плеер так и не
         * прислал `onPositionDiscontinuity`. Страховка от застывшего ползунка.
         */
        const val SEEK_SETTLE_TIMEOUT_MS = 2_000L

        /** Расхождение цели и фактической позиции, о котором стоит писать в лог. */
        const val SEEK_DRIFT_LOG_MS = 750L

        /**
         * Живой контроллер UI (если приложение открыто). Сервис использует его,
         * чтобы media-кнопки/уведомление шли через ту же логику очереди.
         */
        @Volatile
        internal var instance: PlaybackController? = null
    }
}

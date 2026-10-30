package app.sonora.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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

/**
 * UI-состояние плеера. Источник истины — MediaController, но для отрисовки
 * мы держим снимок в StateFlow, чтобы Compose не зависел от классов плеера.
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
    val sleepEndsAt: Long? = null,
) {
    val current: Track? get() = queue.getOrNull(index)
    val hasNext: Boolean get() = index < queue.lastIndex
    val hasPrevious: Boolean get() = index > 0
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * Обёртка над MediaController. Все методы вызываются с главного потока.
 */
class PlaybackController(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controller: MediaController? = null
    private var sleepJob: Job? = null
    private var lastWidgetUpdateAt = 0L
    private var lastPersistAt = 0L
    private var lastPersistTrackId: String? = null
    private var lastPersistPlaying: Boolean? = null
    private var restoredSession = false
    /** Пока идёт пакетное удаление из очереди, снимок состояния не пересчитываем. */
    private var suppressingRefresh = false
    /** Защита от рекурсии, пока авто-переключение в конце очереди двигает плеер. */
    private var autoAdvancing = false
    /** Метка времени последнего «в начало трека»: второе нажатие «назад» уже листает. */
    private var lastRestartAtMs = 0L
    /** Версия очереди: защищает отложенное пополнение от гонки с новым запуском. */
    private var queueVersion = 0

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /**
     * Ошибка источника (403/сеть/протухшая ссылка). MainViewModel по этому
     * сигналу запускает автопилот и переоткрывает трек через другой способ.
     */
    var onStreamError: ((Throwable?) -> Unit)? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refresh()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Конец очереди. Раньше здесь ничего не происходило: если в очереди
            // оставался один item (типичный случай для трека из «Моей волны»,
            // запущенного кликом, а не кнопкой «Играть волну»), ExoPlayer
            // оставался в STATE_ENDED — трек заканчивался в тишину, а «вперёд»
            // не работал. Теперь докупаем пачку и стартуем следующий трек.
            if (playbackState == Player.STATE_ENDED) {
                onQueueEnded()
            }
            refresh()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            onStreamError?.invoke(error)
            refresh()
        }
    }

    init {
        connect()
        startProgressLoop()
    }

    private fun connect() {
        val sessionToken = SessionToken(
            appContext,
            ComponentName(appContext, PlaybackService::class.java),
        )
        val future = MediaController.Builder(appContext, sessionToken).buildAsync()
        future.addListener(
            {
                runCatching {
                    val c = future.get()
                    controller = c
                    c.addListener(playerListener)
                    _state.update { it.copy(connected = true) }
                    restoreLastSession(c)
                    refresh()
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
                    val position = runCatching { c.currentPosition }.getOrDefault(0L)
                    val sampledAt = android.os.SystemClock.elapsedRealtime()
                    _state.update {
                        it.copy(positionMs = position, positionSampledAtMs = sampledAt)
                    }
                    persistState()
                }
                delay(500)
            }
        }
    }

    private fun refresh() {
        if (suppressingRefresh) return
        val c = controller ?: return
        _state.update { st ->
            val duration = runCatching { c.duration }.getOrDefault(0L)
            val position = runCatching { c.currentPosition }.getOrDefault(st.positionMs)
            st.copy(
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                positionMs = position,
                positionSampledAtMs = android.os.SystemClock.elapsedRealtime(),
                durationMs = if (duration > 0) duration else st.current?.durationMs ?: 0L,
                index = runCatching { c.currentMediaItemIndex }.getOrDefault(0).coerceAtLeast(0),
                shuffle = c.shuffleModeEnabled,
                repeatMode = c.repeatMode,
                speed = c.playbackParameters.speed,
            )
        }
        // Снимок последнего трека + виджет: обновляем только по значимым
        // изменениям (трек/play-pause) или раз в две секунды, чтобы прогресс
        // не дёргал RemoteViews и не подтормаживал UI.
        persistState()
    }

    /**
     * Восстановление последнего трека/очереди после запуска приложения.
     * Играть автоматически не начинаем: пользователь сам нажмёт Play.
     */
    private fun restoreLastSession(c: MediaController) {
        if (restoredSession) return
        restoredSession = true
        if (_state.value.queue.isNotEmpty()) return
        val snapshot = PlaybackSnapshotStore.load(appContext)
        if (snapshot.queue.isEmpty()) return
        runCatching {
            // Если сервис уже играет (приложение перезапустили), не трогаем
            // его очередь — просто подхватываем метаданные для UI.
            if (c.mediaItemCount > 0) {
                if (snapshot.queue.size == c.mediaItemCount) {
                    _state.update {
                        it.copy(
                            queue = snapshot.queue,
                            index = c.currentMediaItemIndex.coerceIn(0, snapshot.queue.lastIndex),
                            positionMs = c.currentPosition.coerceAtLeast(0L),
                            positionSampledAtMs = android.os.SystemClock.elapsedRealtime(),
                            isPlaying = c.isPlaying,
                            shuffle = c.shuffleModeEnabled,
                            repeatMode = c.repeatMode,
                        )
                    }
                }
                return@runCatching
            }
            val index = snapshot.index.coerceIn(0, snapshot.queue.lastIndex)
            c.setMediaItems(snapshot.queue.map { trackToMediaItem(it) }, index, snapshot.positionMs)
            c.shuffleModeEnabled = snapshot.shuffle
            c.repeatMode = snapshot.repeatMode
            c.prepare()
            c.pause()
            _state.update {
                it.copy(
                    queue = snapshot.queue,
                    index = index,
                    positionMs = snapshot.positionMs,
                    positionSampledAtMs = android.os.SystemClock.elapsedRealtime(),
                    shuffle = snapshot.shuffle,
                    repeatMode = snapshot.repeatMode,
                    isPlaying = false,
                )
            }
        }.onFailure { android.util.Log.w("Playback", "restore failed: ${it.message}") }
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
            return
        }
        val trackId = state.current?.id
        val important = force ||
            trackId != lastPersistTrackId ||
            state.isPlaying != lastPersistPlaying
        val now = android.os.SystemClock.elapsedRealtime()
        if (!important && now - lastPersistAt < PERSIST_INTERVAL_MS) return
        lastPersistAt = now
        lastPersistTrackId = trackId
        lastPersistPlaying = state.isPlaying
        PlaybackSnapshotStore.save(
            appContext,
            PlaybackSnapshot(
                queue = state.queue,
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

    // ---------------- Воспроизведение ----------------

    /** Запускает список треков с позиции [startIndex]. Неиграемые треки отбрасываются. */
    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        val c = controller ?: return
        val startTrack = tracks.getOrNull(startIndex)
        val playable = tracks.filter { it.playable && !it.uri.isNullOrBlank() }
        if (playable.isEmpty()) {
            _state.update { it.copy(queue = emptyList(), index = 0) }
            return
        }
        val index = playable.indexOfFirst { it.id == startTrack?.id }.coerceAtLeast(0)
        val items = playable.map { toMediaItem(it) }
        queueVersion++
        c.setMediaItems(items, index, 0L)
        c.prepare()
        c.play()
        _state.update {
            it.copy(
                queue = playable,
                index = index,
                positionMs = 0L,
                positionSampledAtMs = android.os.SystemClock.elapsedRealtime(),
            )
        }
        refresh()
        // Короткая очередь (в частности, одиночный трек) сама себя не продолжит:
        // просим автоматическую очередь сразу, а не после конца трека.
        if (playable.size <= SHORT_QUEUE_MAX) extendShortQueue()
    }

    fun playSingle(track: Track) = playQueue(listOf(track), 0)

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    /**
     * Следующий трек. Устойчиво к трём случаям, из-за которых кнопка «вперёд»
     * раньше казалась «залипшей»:
     *  - очередь кончилась, но расширятель ещё не успел;
     *  - Media3 ещё не пересчитал timeline (hasNextMediaItem=false сразу
     *    после setMediaItems/appendToQueue);
     *  - плеер был на паузе/буферизации — после seek нужно вернуть play().
     */
    fun next() {
        val mediaController = controller ?: run {
            connect()
            return
        }
        ensureControllerHasQueue(mediaController)
        val wasPlaying = mediaController.isPlaying ||
            mediaController.playbackState == Player.STATE_ENDED
        val beforeIndex = mediaController.currentMediaItemIndex

        if (mediaController.hasNextMediaItem()) {
            mediaController.seekToNextMediaItem()
            if (wasPlaying) mediaController.play()
            return
        }
        // Очередь кончилась: сначала докупаем треки, потом переключаем.
        val batch = requestQueueExtension()
        if (batch.isNotEmpty()) appendToQueue(batch)
        if (mediaController.hasNextMediaItem()) {
            mediaController.seekToNextMediaItem()
            if (wasPlaying) mediaController.play()
            return
        }
        // Последний рубеж: идём по абсолютному индексу, не полагаясь на timeline.
        val count = mediaController.mediaItemCount
        if (beforeIndex in 0 until count - 1) {
            mediaController.seekTo(beforeIndex + 1, 0L)
            if (wasPlaying) mediaController.play()
        }
    }

    /**
     * Колбэк «дай ещё треков»: выставляет MainViewModel, чтобы очередь не обрывалась,
     * даже если фоновое расширение по StateFlow не успело сработать.
     */
    var queueExtender: (() -> List<Track>)? = null

    fun previous() {
        val c = controller ?: return
        ensureControllerHasQueue(c)
        val now = android.os.SystemClock.elapsedRealtime()
        val position = runCatching { c.currentPosition }.getOrDefault(0L)
        // «Как в Spotify»: далеко от начала — только в начало текущего трека.
        // Повторное нажатие (позиция уже около нуля) листает на предыдущий.
        // Окно PREVIOUS_TAP_WINDOW_MS страхует от гонки, когда seek ещё не
        // применился на MediaController.
        val justRestarted = lastRestartAtMs != 0L && now - lastRestartAtMs <= PREVIOUS_TAP_WINDOW_MS
        when {
            position > PREVIOUS_RESTART_MS && !justRestarted -> {
                lastRestartAtMs = now
                c.seekTo(0L)
                _state.update { it.copy(positionMs = 0L, positionSampledAtMs = now) }
            }
            c.hasPreviousMediaItem() -> {
                lastRestartAtMs = 0L
                c.seekToPreviousMediaItem()
            }
            else -> {
                // Предыдущего нет: остаёмся «в начале» текущего трека.
                lastRestartAtMs = 0L
                c.seekTo(0L)
                _state.update { it.copy(positionMs = 0L, positionSampledAtMs = now) }
            }
        }
    }

    /**
     * Авто-переход в конце очереди: докупает пачку треков через [queueExtender]
     * и явно запускает следующий. Нужен для коротких очередей («Моя волна»
     * по клику на отдельный трек, одиночный трек) — без этого ExoPlayer
     * замирал в STATE_ENDED и «вперёд» ничего не делал.
     */
    private fun onQueueEnded() {
        val c = controller ?: return
        if (autoAdvancing) return
        autoAdvancing = true
        try {
            ensureControllerHasQueue(c)
            if (c.hasNextMediaItem()) {
                c.seekToNextMediaItem()
                c.play()
                return
            }
            val batch = requestQueueExtension()
            if (batch.isNotEmpty()) appendToQueue(batch)
            if (c.hasNextMediaItem()) {
                c.seekToNextMediaItem()
                c.play()
            }
        } finally {
            autoAdvancing = false
        }
    }

    private fun requestQueueExtension(): List<Track> =
        runCatching { queueExtender?.invoke() ?: emptyList() }
            .getOrDefault(emptyList())

    /**
     * Страховка на случай расхождения снимка и MediaController (сервис
     * перезапускался/очередь восстановлена): возвращаем items в плеер, чтобы
     * next()/previous()/авто-переход работали по реальной очереди.
     */
    private fun ensureControllerHasQueue(c: MediaController) {
        if (c.mediaItemCount > 0) return
        val state = _state.value
        if (state.queue.isEmpty()) return
        val index = state.index.coerceIn(0, state.queue.lastIndex)
        c.setMediaItems(state.queue.map { toMediaItem(it) }, index, state.positionMs.coerceAtLeast(0L))
        c.prepare()
        if (state.isPlaying) c.play()
    }

    /**
     * Просит ещё треков сразу после старта короткой очереди: трек из «Моей
     * волны», запущенный кликом, иначе остаётся единственным в плеере
     * (автоматическая очередь для него собралась пустой) и после конца
     * замолкает.
     */
    private fun extendShortQueue() {
        if (queueExtender == null) return
        val version = queueVersion
        scope.launch {
            // Даём MainViewModel дописать состояние (playQueue обновляет его
            // синхронно, но листенер плеера может прийти раньше).
            delay(0)
            if (version != queueVersion) return@launch
            if (_state.value.queue.size > SHORT_QUEUE_MAX) return@launch
            val batch = requestQueueExtension()
            if (batch.isEmpty() || version != queueVersion) return@launch
            appendToQueue(batch)
            // Если трек успел закончиться, пока мы собирали пачку — вытаскиваем
            // плеер из STATE_ENDED.
            val c = controller ?: return@launch
            if (c.playbackState == Player.STATE_ENDED) onQueueEnded()
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
        _state.update {
            it.copy(
                positionMs = positionMs,
                positionSampledAtMs = android.os.SystemClock.elapsedRealtime(),
            )
        }
    }

    fun seekToIndex(index: Int) {
        controller?.let { c ->
            if (index in 0 until c.mediaItemCount) {
                c.seekTo(index, 0L)
                c.play()
            }
        }
    }

    fun setShuffle(enabled: Boolean) {
        controller?.shuffleModeEnabled = enabled
        _state.update { it.copy(shuffle = enabled) }
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
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    // ---------------- Очередь ----------------

    fun addToQueue(track: Track) {
        if (!track.playable || track.uri.isNullOrBlank()) return
        controller?.addMediaItem(toMediaItem(track))
        _state.update { st -> st.copy(queue = st.queue + track) }
    }

    /**
     * Подменяет метаданные уже стоящих в очереди треков (после онлайн-обогащения).
     * Текущий трек не пересоздаём, чтобы не сбить позицию/буферизацию, — его карточка
     * обновится из [state]; уведомление обновится при следующем переключении.
     */
    fun applyMetadata(updated: List<Track>) {
        val mediaController = controller ?: return
        if (updated.isEmpty()) return
        val byId = updated.associateBy { it.id }
        val queue = _state.value.queue
        if (queue.isEmpty()) return
        val currentIndex = runCatching { mediaController.currentMediaItemIndex }.getOrDefault(-1)
        queue.forEachIndexed { index, track ->
            val better = byId[track.id] ?: return@forEachIndexed
            if (better == track) return@forEachIndexed
            if (index == currentIndex) return@forEachIndexed
            runCatching { mediaController.replaceMediaItem(index, toMediaItem(better)) }
        }
        _state.update { st ->
            st.copy(queue = st.queue.map { byId[it.id] ?: it })
        }
    }

    /**
     * Добавляет пачку треков в конец очереди (для бесконечной очереди).
     * Один вызов = одна пачка, чтобы не дёргать листенер на каждый трек.
     */
    fun appendToQueue(tracks: List<Track>) {
        val controller = controller ?: return
        val playable = tracks.filter { it.playable && !it.uri.isNullOrBlank() }
        if (playable.isEmpty()) return
        playable.forEach { controller.addMediaItem(toMediaItem(it)) }
        _state.update { st -> st.copy(queue = st.queue + playable) }
    }

    fun playNext(track: Track) {
        if (!track.playable || track.uri.isNullOrBlank()) return
        val c = controller ?: return
        val insertAt = (c.currentMediaItemIndex + 1).coerceAtLeast(0)
        c.addMediaItem(insertAt, toMediaItem(track))
        _state.update { st ->
            val mutable = st.queue.toMutableList()
            mutable.add(insertAt.coerceIn(0, mutable.size), track)
            st.copy(queue = mutable)
        }
    }

    /**
     * Вставляет пачку треков сразу после текущего — «играть следующим» для
     * плейлиста или альбома. Порядок сохраняется.
     */
    fun playNext(tracks: List<Track>) {
        val c = controller ?: return
        val playable = tracks.filter { it.playable && !it.uri.isNullOrBlank() }
        if (playable.isEmpty()) return
        var insertAt = (c.currentMediaItemIndex + 1).coerceAtLeast(0)
        playable.forEach { track ->
            c.addMediaItem(insertAt, toMediaItem(track))
            insertAt++
        }
        _state.update { st ->
            val mutable = st.queue.toMutableList()
            val at = insertAt.coerceIn(0, mutable.size)
            val start = (at - playable.size).coerceAtLeast(0)
            mutable.addAll(start, playable)
            st.copy(queue = mutable)
        }
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
        val track = _state.value.queue.getOrNull(index) ?: return
        val position = runCatching { c.currentPosition }.getOrDefault(0L)
        val wasPlaying = c.isPlaying
        runCatching {
            c.replaceMediaItem(index, toMediaItem(track))
            c.seekTo(index, position)
            if (wasPlaying) c.play()
        }
        refresh()
    }

    fun removeAt(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.removeMediaItem(index)
        _state.update { st ->
            st.copy(queue = st.queue.filterIndexed { i, _ -> i != index })
        }
    }

    /**
     * Удаляет из очереди треки, для которых [keep] вернул false. Текущий трек
     * остаётся на месте, чтобы не прерывать воспроизведение. Возвращает число удалённых.
     */
    fun removeWhere(keep: (Track) -> Boolean): Int {
        val c = controller ?: return 0
        val queue = _state.value.queue.toMutableList()
        val currentIndex = runCatching { c.currentMediaItemIndex }.getOrDefault(-1)
        var removed = 0
        // На время пакетного удаления не пересчитываем снимок: иначе onEvents
        // обновит index/queue по промежуточному состоянию и «текущий» трек
        // на мгновение станет чужим.
        suppressingRefresh = true
        try {
            for (index in queue.indices.reversed()) {
                if (index == currentIndex) continue
                val track = queue.getOrNull(index) ?: continue
                if (keep(track)) continue
                if (index >= c.mediaItemCount) continue
                val success = runCatching { c.removeMediaItem(index) }.isSuccess
                if (success) {
                    queue.removeAt(index)
                    removed++
                }
            }
        } finally {
            suppressingRefresh = false
        }
        if (removed > 0) {
            _state.update { st -> st.copy(queue = queue) }
            refresh()
        }
        return removed
    }

    fun move(from: Int, to: Int) {
        val c = controller ?: return
        if (from !in 0 until c.mediaItemCount || to !in 0 until c.mediaItemCount) return
        c.moveMediaItem(from, to)
        _state.update { st ->
            val mutable = st.queue.toMutableList()
            if (from in mutable.indices && to in mutable.indices) {
                val item = mutable.removeAt(from)
                mutable.add(to, item)
            }
            st.copy(queue = mutable)
        }
    }

    fun clearQueue() {
        queueVersion++
        controller?.clearMediaItems()
        _state.update { it.copy(queue = emptyList(), index = 0, positionMs = 0L) }
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
    }

    private fun toMediaItem(track: Track): MediaItem = trackToMediaItem(track)

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

        /**
         * Очередь такого размера считаем «короткой» и сразу просим
         * автоматическую очередь (одиночный трек сам не продолжится).
         */
        const val SHORT_QUEUE_MAX = 1
    }
}

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
    /** Пока идёт пакетное удаление из очереди, снимок состояния не пересчитываем. */
    private var suppressingRefresh = false

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
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
        // Виджет рабочего стола живёт в этом же процессе — обновляем его без опроса,
        // но не чаще раза в секунду: прогресс-бар в виджете не требует 2 Гц.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWidgetUpdateAt >= WIDGET_UPDATE_INTERVAL_MS) {
            lastWidgetUpdateAt = now
            runCatching { SonoraWidget.updateAll(appContext, _state.value) }
        }
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
    }

    fun playSingle(track: Track) = playQueue(listOf(track), 0)

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() {
        val mediaController = controller ?: return
        if (mediaController.hasNextMediaItem()) {
            mediaController.seekToNextMediaItem()
            return
        }
        // Очередь кончилась: подкидываем ещё и только потом переключаем.
        // Так кнопка «вперёд» не «залипает» после нескольких переключений.
        val extender = queueExtender ?: return
        val batch = runCatching { extender() }.getOrDefault(emptyList())
        if (batch.isNotEmpty()) {
            appendToQueue(batch)
            if (mediaController.hasNextMediaItem()) mediaController.seekToNextMediaItem()
        }
    }

    /**
     * Колбэк «дай ещё треков»: выставляет MainViewModel, чтобы очередь не обрывалась,
     * даже если фоновое расширение по StateFlow не успело сработать.
     */
    var queueExtender: (() -> List<Track>)? = null

    fun previous() {
        controller?.let {
            if (it.currentPosition > 3_000L) it.seekTo(0L)
            else if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem()
            else it.seekTo(0L)
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

    private fun toMediaItem(track: Track): MediaItem {
        val artwork = track.artworkUrl ?: track.artworkUri
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .apply {
                if (!track.album.isNullOrBlank()) setAlbumTitle(track.album)
                if (!artwork.isNullOrBlank()) setArtworkUri(Uri.parse(artwork))
            }
            .build()

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(track.uri)
            .setMediaMetadata(metadata)
            .build()
    }

    private companion object {
        /** Виджет не нужно перерисовывать чаще раза в секунду. */
        const val WIDGET_UPDATE_INTERVAL_MS = 1_000L
    }
}

package app.sonora.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.media.audiofx.Equalizer
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import app.sonora.AppGraph
import app.sonora.R
import app.sonora.core.settings.SettingsStore
import app.sonora.core.settings.effectiveAudioQuality
import app.sonora.widget.SonoraWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val TAG = "SonoraPlayback"

/**
 * Фоновое воспроизведение: ExoPlayer + MediaSession.
 * Благодаря MediaSessionService работают уведомление, экран блокировки,
 * кнопки на Bluetooth-гарнитуре и Android Auto.
 *
 * Кнопки media-сессии перехватываются [mediaSessionCallback]: «вперёд»/«назад»
 * должны вести себя так же, как в приложении (реальный соседний трек даже в
 * REPEAT_ONE), а не как дефолтный media3-seek.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var equalizer: Equalizer? = null
    /** true, пока мы сами держим foreground-уведомление до старта Media3. */
    private var bootForegroundActive = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val settings by lazy { SettingsStore(this) }

    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            command: Int,
        ): Int {
            when (command) {
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                -> {
                    val handled = handleNextCommand()
                    return if (handled) SessionResult.RESULT_INFO_SKIPPED else SessionResult.RESULT_SUCCESS
                }
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                -> {
                    val handled = handlePreviousCommand()
                    return if (handled) SessionResult.RESULT_INFO_SKIPPED else SessionResult.RESULT_SUCCESS
                }
            }
            // RESULT_SUCCESS — команду выполняет сессия штатно.
            return SessionResult.RESULT_SUCCESS
        }
    }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(
                InnerTubeDataSourceFactory(this) {
                    // Читаем актуальные настройки приложения (не кэш сервиса):
                    // смена качества в UI применяется к следующему открытию потока.
                    val graph = AppGraph.get(this@PlaybackService)
                    graph.settings.current.effectiveAudioQuality(
                        graph.downloads.isOnMobileData(),
                    )
                },
            )

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    ensureEqualizer()
                    // Media3 уже показал своё уведомление и держит foreground —
                    // временное «запускаем…» больше не нужно.
                    cancelBootForeground()
                }
                persistSnapshot()
                SonoraWidget.updateAll(this@PlaybackService)
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                android.util.Log.i(
                    TAG,
                    "service_transition reason=$reason to=${player.currentMediaItemIndex} " +
                        "count=${player.mediaItemCount} id=${mediaItem?.mediaId ?: "-"}",
                )
                persistSnapshot()
                SonoraWidget.updateAll(this@PlaybackService)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                persistSnapshot()
            }
        })

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(mediaSessionCallback)
            .build()

        scope.launch {
            settings.state.collect { s ->
                runCatching {
                    player.skipSilenceEnabled = s.skipSilence
                }
            }
        }

        scope.launch {
            combine(
                AudioEffectBus.enabled,
                AudioEffectBus.gains,
                AudioEffectBus.bassBoost,
            ) { enabled, gains, _ -> enabled to gains }
                .collect { (enabled, gains) -> applyEqualizer(enabled, gains) }
        }
    }

    private fun ensureEqualizer() {
        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        if (equalizer == null) {
            runCatching { equalizer = Equalizer(0, sessionId) }
        }
        applyEqualizer(AudioEffectBus.enabled.value, AudioEffectBus.gains.value)
    }

    private fun applyEqualizer(enabled: Boolean, gains: List<Float>) {
        val eq = equalizer ?: return
        runCatching {
            eq.enabled = enabled
            if (enabled) {
                val range = eq.bandLevelRange
                val bands = eq.numberOfBands.toInt().coerceAtMost(gains.size)
                for (band in 0 until bands) {
                    val millibels = (gains[band] * 100).toInt()
                        .coerceIn(range[0].toInt(), range[1].toInt())
                    eq.setBandLevel(band.toShort(), millibels.toShort())
                }
            }
        }
    }

    /**
     * Кнопки виджета приходят сюда сервисным интентом: так музыка запускается
     * даже когда приложение убито, без создания AppGraph/активити.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != null && action.startsWith(ACTION_PREFIX)) {
            // startForegroundService из виджета обязывает вызвать startForeground()
            // за ~5 секунд, иначе Android убивает процесс с ANR. Media3 сделает
            // это сам только когда плеер начнёт играть, а онлайн-трек резолвится
            // дольше — поэтому держим временное уведомление сразу.
            ensureBootForeground()
            val restart = super.onStartCommand(intent, flags, startId)
            runCatching {
                when (action) {
                    ACTION_PLAY_LAST -> {
                        if (player.mediaItemCount == 0) playFromSnapshot() else player.play()
                    }
                    ACTION_PLAY_PAUSE -> {
                        if (player.mediaItemCount == 0) {
                            playFromSnapshot()
                        } else if (player.isPlaying) {
                            player.pause()
                        } else {
                            player.play()
                        }
                    }
                    ACTION_NEXT -> {
                        if (player.mediaItemCount == 0) {
                            playFromSnapshot()
                        } else {
                            handleNextCommand()
                        }
                        Unit
                    }
                    ACTION_PREVIOUS -> {
                        if (player.mediaItemCount == 0) {
                            playFromSnapshot()
                        } else {
                            handlePreviousCommand()
                        }
                        Unit
                    }
                    else -> Unit
                }
            }.onFailure { android.util.Log.w(TAG, "widget action failed: ${it.message}") }
            persistSnapshot()
            SonoraWidget.updateAll(this)
            // Нечего играть (нет сохранённой очереди) — не висим foreground-сервисом.
            if (player.mediaItemCount == 0) {
                cancelBootForeground()
                stopSelf()
                return START_NOT_STICKY
            }
            return restart
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * «Вперёд» с media-кнопок/уведомления. Если приложение открыто, идём через
     * живой `PlaybackController` (там же докупается бесконечная очередь), иначе
     * считаем соседний индекс локально — так REPEAT_ONE больше не «залипает».
     */
    private fun handleNextCommand(): Boolean {
        val live = PlaybackController.instance
        if (live != null && live.isConnected) {
            live.next()
            return true
        }
        return advancePlayer()
    }

    private fun handlePreviousCommand(): Boolean {
        val live = PlaybackController.instance
        if (live != null && live.isConnected) {
            live.previous()
            return true
        }
        return restartOrPreviousPlayer()
    }

    private fun advancePlayer(): Boolean {
        val count = player.mediaItemCount
        if (count == 0) return false
        val from = player.currentMediaItemIndex
        val target = QueueModel.nextIndex(from, count, player.repeatMode)
        if (target == QueueModel.NONE) {
            android.util.Log.i(
                TAG,
                "advance reason=media_key from=$from to=-1 count=$count " +
                    "repeat=${repeatName(player.repeatMode)} result=end_of_queue",
            )
            return false
        }
        val wasPlaying = player.isPlaying || player.playbackState == Player.STATE_ENDED
        player.seekTo(target, 0L)
        if (wasPlaying) player.play()
        android.util.Log.i(
            TAG,
            "advance reason=media_key from=$from to=$target count=$count " +
                "repeat=${repeatName(player.repeatMode)}",
        )
        return true
    }

    private fun restartOrPreviousPlayer(): Boolean {
        val count = player.mediaItemCount
        if (count == 0) return false
        val current = player.currentMediaItemIndex
        val position = runCatching { player.currentPosition }.getOrDefault(0L)
        if (position > PlaybackController.PREVIOUS_RESTART_MS) {
            player.seekTo(current, 0L)
            android.util.Log.i(TAG, "previous reason=media_key from=$current result=restart")
            return true
        }
        val target = QueueModel.previousIndex(current, count, player.repeatMode)
        val wasPlaying = player.isPlaying || player.playbackState == Player.STATE_ENDED
        player.seekTo(target, 0L)
        if (wasPlaying) player.play()
        android.util.Log.i(
            TAG,
            "previous reason=media_key from=$current to=$target count=$count " +
                "repeat=${repeatName(player.repeatMode)}",
        )
        return true
    }

    private fun repeatName(mode: Int): String = when (mode) {
        Player.REPEAT_MODE_ONE -> "ONE"
        Player.REPEAT_MODE_ALL -> "ALL"
        else -> "OFF"
    }

    /**
     * Временное foreground-уведомление на время старта из виджета: без него
     * startForegroundService + медленный резолв онлайн-трека даёт ANR.
     * Скрываем, как только Media3 показал своё уведомление, или через 12 секунд.
     */
    private fun ensureBootForeground() {
        if (bootForegroundActive) return
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    BOOT_CHANNEL_ID,
                    "Запуск воспроизведения",
                    NotificationManager.IMPORTANCE_LOW,
                )
                manager.createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(this, BOOT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Sonora")
                .setContentText("Запускаем воспроизведение…")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            startForeground(BOOT_NOTIFICATION_ID, notification)
            bootForegroundActive = true
            scope.launch {
                delay(BOOT_NOTIFICATION_TIMEOUT_MS)
                cancelBootForeground()
            }
        }.onFailure { android.util.Log.w(TAG, "boot foreground failed: ${it.message}") }
    }

    private fun cancelBootForeground() {
        if (!bootForegroundActive) return
        bootForegroundActive = false
        runCatching {
            getSystemService(NotificationManager::class.java).cancel(BOOT_NOTIFICATION_ID)
        }
    }

    /** Ставит в плеер последнюю сохранённую очередь и сразу играет. */
    private fun playFromSnapshot(): Boolean {
        val snapshot = PlaybackSnapshotStore.load(this)
        if (snapshot.queue.isEmpty()) return false
        val items = snapshot.queue.map { trackToMediaItem(it) }
        val index = snapshot.index.coerceIn(0, items.lastIndex)
        player.setMediaItems(items, index, snapshot.positionMs)
        // Порядок уже сохранён в самой очереди: media3-shuffle не включаем,
        // иначе пользовательская очередь («Далее в очереди») перемешается.
        player.shuffleModeEnabled = false
        player.prepare()
        // Режим повтора применяем после prepare, чтобы он не потерялся на
        // холодном старте сервиса (виджет/кнопка Play при убитом процессе).
        player.repeatMode = snapshot.repeatMode
        player.play()
        android.util.Log.i(
            TAG,
            "play_from_snapshot count=${items.size} index=$index " +
                "repeat=${repeatName(player.repeatMode)}",
        )
        return true
    }

    /**
     * Сохраняет позицию/статус. Очередь и индекс принадлежат
     * `PlaybackController`; здесь только точечное обновление и только если
     * плеер играет ровно сохранённую очередь (mediaId + размер).
     */
    private fun persistSnapshot() {
        if (player.mediaItemCount == 0) return
        val currentId = runCatching { player.currentMediaItem?.mediaId }.getOrNull()
        PlaybackSnapshotStore.updatePlayback(
            context = this,
            itemCount = player.mediaItemCount,
            index = player.currentMediaItemIndex,
            currentMediaId = currentId,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            isPlaying = player.isPlaying,
            repeatMode = player.repeatMode,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistSnapshot()
        val player = mediaSession?.player
        if (player == null ||
            (!player.playWhenReady && player.playbackState == Player.STATE_IDLE) ||
            player.mediaItemCount == 0
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        persistSnapshot()
        scope.cancel()
        runCatching { equalizer?.release() }
        equalizer = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_PREFIX = "app.sonora.widget."
        const val ACTION_PLAY_LAST = "${ACTION_PREFIX}PLAY_LAST"
        const val ACTION_PLAY_PAUSE = "${ACTION_PREFIX}PLAY_PAUSE"
        const val ACTION_NEXT = "${ACTION_PREFIX}NEXT"
        const val ACTION_PREVIOUS = "${ACTION_PREFIX}PREVIOUS"

        private const val BOOT_CHANNEL_ID = "sonora_boot"
        private const val BOOT_NOTIFICATION_ID = 101
        private const val BOOT_NOTIFICATION_TIMEOUT_MS = 12_000L

        fun widgetAction(context: android.content.Context, action: String): Intent =
            Intent(context, PlaybackService::class.java).setAction(action)
    }
}

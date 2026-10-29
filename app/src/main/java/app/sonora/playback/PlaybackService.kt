package app.sonora.playback

import android.content.Intent
import android.media.audiofx.Equalizer
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.sonora.AppGraph
import app.sonora.core.settings.SettingsStore
import app.sonora.core.settings.effectiveAudioQuality
import app.sonora.widget.SonoraWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Фоновое воспроизведение: ExoPlayer + MediaSession.
 * Благодаря MediaSessionService работают уведомление, экран блокировки,
 * кнопки на Bluetooth-гарнитуре и Android Auto.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var equalizer: Equalizer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val settings by lazy { SettingsStore(this) }

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
                if (isPlaying) ensureEqualizer()
                persistSnapshot()
                SonoraWidget.updateAll(this@PlaybackService)
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                persistSnapshot()
                SonoraWidget.updateAll(this@PlaybackService)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                persistSnapshot()
            }
        })

        mediaSession = MediaSession.Builder(this, player).build()

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
                            if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                            player.play()
                        }
                    }
                    ACTION_PREVIOUS -> {
                        if (player.mediaItemCount == 0) {
                            playFromSnapshot()
                        } else {
                            if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
                            player.play()
                        }
                    }
                }
            }.onFailure { android.util.Log.w("Playback", "widget action failed: ${it.message}") }
            persistSnapshot()
            SonoraWidget.updateAll(this)
            return restart
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** Ставит в плеер последнюю сохранённую очередь и сразу играет. */
    private fun playFromSnapshot(): Boolean {
        val snapshot = PlaybackSnapshotStore.load(this)
        if (snapshot.queue.isEmpty()) return false
        val items = snapshot.queue.map { trackToMediaItem(it) }
        val index = snapshot.index.coerceIn(0, items.lastIndex)
        player.setMediaItems(items, index, snapshot.positionMs)
        player.shuffleModeEnabled = snapshot.shuffle
        player.repeatMode = snapshot.repeatMode
        player.prepare()
        player.play()
        return true
    }

    /** Сохраняет текущую позицию/статус в снимок для виджета и следующего старта. */
    private fun persistSnapshot() {
        if (player.mediaItemCount == 0) return
        val stored = PlaybackSnapshotStore.load(this)
        if (stored.queue.isEmpty()) return
        val index = player.currentMediaItemIndex.coerceIn(0, stored.queue.lastIndex)
        PlaybackSnapshotStore.save(
            this,
            stored.copy(
                index = index,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                isPlaying = player.isPlaying,
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                updatedAtMs = System.currentTimeMillis(),
            ),
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

        fun widgetAction(context: android.content.Context, action: String): Intent =
            Intent(context, PlaybackService::class.java).setAction(action)
    }
}

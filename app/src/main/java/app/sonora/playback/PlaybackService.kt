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
import app.sonora.core.settings.SettingsStore
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
            .setDataSourceFactory(InnerTubeDataSourceFactory(this))

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) ensureEqualizer()
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null ||
            (!player.playWhenReady && player.playbackState == Player.STATE_IDLE) ||
            player.mediaItemCount == 0
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
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
}

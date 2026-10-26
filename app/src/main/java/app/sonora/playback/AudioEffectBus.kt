package app.sonora.playback

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Мост между UI и сервисом воспроизведения для звуковых эффектов.
 *
 * Эквалайзер привязывается к audio session ExoPlayer и должен жить в том же процессе,
 * что и плеер, поэтому UI общается с ним через этот объект (один процесс на приложение).
 */
object AudioEffectBus {
    val enabled = MutableStateFlow(false)
    val gains = MutableStateFlow(List(5) { 0f })
    val bassBoost = MutableStateFlow(0)
}

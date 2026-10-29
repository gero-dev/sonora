package app.sonora.ui.nav

import androidx.compose.runtime.staticCompositionLocalOf
import app.sonora.core.model.Track

/**
 * Переход к исполнителю из любого места, где есть трек: строки списка,
 * мини-плеер, полный плеер, лист действий. Как в Spotify/Metrolist:
 * нажатие на имя артиста открывает его страницу, а не запускает трек.
 *
 * Провайдер — `MainScreen`; значение по умолчанию ничего не делает,
 * чтобы компонент можно было использовать в превью/тестах.
 */
val LocalArtistNavigator = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Запуск радио по треку (Song Radio) из любого списка — как в Spotify.
 * null означает, что провайдера нет: пункт меню просто не показывается.
 */
val LocalTrackRadio = staticCompositionLocalOf<((Track) -> Unit)?> { null }


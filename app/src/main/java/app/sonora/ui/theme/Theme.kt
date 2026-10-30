package app.sonora.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.sonora.core.settings.AppSettings
import app.sonora.core.settings.ThemeMode

/**
 * Дополнительные цвета, которых нет в MaterialTheme.
 *
 * [gradient] — раньше это был яркий двухцветный градиент; теперь это плоский
 * акцент (обе точки совпадают), поэтому любые Brush.linearGradient в приложении
 * автоматически превращаются в ровную заливку primary. Градиенты убраны
 * осознанно: Pixel Experience — это про чистые плоскости.
 * [header] — сплошной surface-тон «шапки» экрана: шапки плоские, без цветного
 * ореола, как в Spotify.
 */
data class SonoraColors(
    val gradient: List<Color>,
    val header: List<Color>,
)

val LocalSonoraColors = staticCompositionLocalOf {
    SonoraColors(
        gradient = listOf(VioletPalette.primaryDark, VioletPalette.primaryDark),
        header = listOf(DarkSurface, DarkSurface, DarkSurface),
    )
}

/**
 * Тема приложения: 12 акцентных палитр + системный динамический цвет (Android 12+,
 * по умолчанию выключен). Базовая тема — чистая тёмная схема Spotify-dark с
 * фирменным зелёным акцентом; светлая — такой же чистый нейтральный вариант.
 *
 * Дизайн: Pixel Experience × Spotify — крупная жирная типографика, мягкие
 * скругления, плоские поверхности, акцент только на действиях.
 */
@Composable
fun SonoraTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }
    val palette = paletteFor(settings.accent)
    val context = LocalContext.current
    val dynamicAvailable = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val scheme = when {
        dynamicAvailable -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> palette.darkScheme()
        else -> palette.lightScheme()
    }

    // Плоский акцент вместо градиента: обе точки совпадают, Brush остаётся Brush,
    // но рисует ровный primary. Ноль лишних аллокаций на кадр.
    val gradient = remember(scheme.primary) {
        listOf(scheme.primary, scheme.primary)
    }
    // Плоская шапка: сплошной surface-тон без цветного ореола.
    val header = remember(scheme.surface) {
        listOf(scheme.surface, scheme.surface, scheme.surface)
    }

    CompositionLocalProvider(
        LocalSonoraColors provides SonoraColors(gradient = gradient, header = header),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = SonoraTypography,
            shapes = SonoraShapes,
            content = content,
        )
    }
}

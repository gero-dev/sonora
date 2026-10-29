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
 * [gradient] — акцентный градиент палитры (кнопки Play, прогресс, hero-карточки).
 * [header] — тот же акцент, растворённый в фоне: мягкая «шапка» экрана в духе
 * Spotify, которая работает и в тёмной, и в светлой теме, и с динамическим цветом.
 */
data class SonoraColors(
    val gradient: List<Color>,
    val header: List<Color>,
)

val LocalSonoraColors = staticCompositionLocalOf {
    SonoraColors(
        gradient = listOf(VioletPalette.gradientStart, VioletPalette.gradientEnd),
        header = listOf(
            VioletPalette.gradientStart.copy(alpha = 0.30f),
            VioletPalette.gradientStart.copy(alpha = 0.10f),
            DarkBackground.copy(alpha = 0f),
        ),
    )
}

/**
 * Тема приложения: 12 акцентных палитр + системный динамический цвет (Android 12+),
 * плотный типографический масштаб и скругления. В динамическом режиме акцентный
 * градиент тоже подстраивается под обои — из primary/tertiary системной схемы.
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

    // В динамическом режиме стараемся не спорить с цветом обоев: градиент берём
    // из системной палитры, иначе — из выбранного акцента.
    val gradient = remember(scheme, dynamicAvailable, dark) {
        if (dynamicAvailable) {
            listOf(scheme.primary, scheme.tertiary)
        } else {
            listOf(palette.gradientStart, palette.gradientEnd)
        }
    }
    // Шапка экрана: акцент, плавно растворяющийся в фоне. Тот же приём, что в
    // Spotify, но на своём акценте (и на цвете обоев при dynamicColor).
    val header = remember(scheme.background, gradient) {
        listOf(
            gradient.first().copy(alpha = 0.30f),
            gradient.first().copy(alpha = 0.10f),
            scheme.background.copy(alpha = 0f),
        )
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

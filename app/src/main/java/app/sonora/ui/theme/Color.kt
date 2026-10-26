package app.sonora.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.sonora.core.settings.Accent

/**
 * Акцентная палитра приложения (в духе Metrolist: тёмный фон + яркий градиент).
 * Палитра задаёт только акцентные роли; нейтральные поверхности общие для всех
 * шести акцентов и описаны ниже — так интерфейс остаётся цельным при смене цвета.
 */
data class AccentPalette(
    val primaryDark: Color,
    val onPrimaryDark: Color,
    val primaryContainerDark: Color,
    val onPrimaryContainerDark: Color,
    val primaryLight: Color,
    val onPrimaryLight: Color,
    val primaryContainerLight: Color,
    val onPrimaryContainerLight: Color,
    val gradientStart: Color,
    val gradientEnd: Color,
    val secondary: Color,
    val tertiary: Color,
)

val VioletPalette = AccentPalette(
    primaryDark = Color(0xFFC9B6FF),
    onPrimaryDark = Color(0xFF2A1259),
    primaryContainerDark = Color(0xFF41307A),
    onPrimaryContainerDark = Color(0xFFE9DDFF),
    primaryLight = Color(0xFF6742C8),
    onPrimaryLight = Color(0xFFFFFFFF),
    primaryContainerLight = Color(0xFFE9DDFF),
    onPrimaryContainerLight = Color(0xFF21005D),
    gradientStart = Color(0xFF7C4DFF),
    gradientEnd = Color(0xFFFF5FA2),
    secondary = Color(0xFFB9C3FF),
    tertiary = Color(0xFFFFB0C8),
)

val BluePalette = AccentPalette(
    primaryDark = Color(0xFFA8C7FF),
    onPrimaryDark = Color(0xFF002F65),
    primaryContainerDark = Color(0xFF15457F),
    onPrimaryContainerDark = Color(0xFFD6E3FF),
    primaryLight = Color(0xFF2E5EA8),
    onPrimaryLight = Color(0xFFFFFFFF),
    primaryContainerLight = Color(0xFFD6E3FF),
    onPrimaryContainerLight = Color(0xFF001B3D),
    gradientStart = Color(0xFF2D7DFF),
    gradientEnd = Color(0xFF00D4C8),
    secondary = Color(0xFFB7C7E9),
    tertiary = Color(0xFF8BD5FF),
)

val GreenPalette = AccentPalette(
    primaryDark = Color(0xFF9FE3B0),
    onPrimaryDark = Color(0xFF00391D),
    primaryContainerDark = Color(0xFF11512F),
    onPrimaryContainerDark = Color(0xFFD3F7DC),
    primaryLight = Color(0xFF2C6B44),
    onPrimaryLight = Color(0xFFFFFFFF),
    primaryContainerLight = Color(0xFFD3F7DC),
    onPrimaryContainerLight = Color(0xFF00210F),
    gradientStart = Color(0xFF1DB954),
    gradientEnd = Color(0xFF9BE15D),
    secondary = Color(0xFFBCCBBC),
    tertiary = Color(0xFFA5D6C0),
)

val OrangePalette = AccentPalette(
    primaryDark = Color(0xFFFFB68C),
    onPrimaryDark = Color(0xFF552000),
    primaryContainerDark = Color(0xFF7A3200),
    onPrimaryContainerDark = Color(0xFFFFDBCC),
    primaryLight = Color(0xFFA64B12),
    onPrimaryLight = Color(0xFFFFFFFF),
    primaryContainerLight = Color(0xFFFFDBCC),
    onPrimaryContainerLight = Color(0xFF351000),
    gradientStart = Color(0xFFFF8A3D),
    gradientEnd = Color(0xFFFFC93D),
    secondary = Color(0xFFE9C0AE),
    tertiary = Color(0xFFFFD3A6),
)

val PinkPalette = AccentPalette(
    primaryDark = Color(0xFFFFB0C8),
    onPrimaryDark = Color(0xFF5A0F31),
    primaryContainerDark = Color(0xFF7C2A4A),
    onPrimaryContainerDark = Color(0xFFFFD9E4),
    primaryLight = Color(0xFFB03A64),
    onPrimaryLight = Color(0xFFFFFFFF),
    primaryContainerLight = Color(0xFFFFD9E4),
    onPrimaryContainerLight = Color(0xFF3E001D),
    gradientStart = Color(0xFFFF4D8D),
    gradientEnd = Color(0xFFB84DFF),
    secondary = Color(0xFFFFB0C8),
    tertiary = Color(0xFFFFD7A6),
)

val MonoPalette = AccentPalette(
    primaryDark = Color(0xFFE6E1E9),
    onPrimaryDark = Color(0xFF1B1B1F),
    primaryContainerDark = Color(0xFF2E2E33),
    onPrimaryContainerDark = Color(0xFFE6E1E9),
    primaryLight = Color(0xFF3A3A40),
    onPrimaryLight = Color(0xFFFFFFFF),
    primaryContainerLight = Color(0xFFE3E2E6),
    onPrimaryContainerLight = Color(0xFF1B1B1F),
    gradientStart = Color(0xFF6E6E78),
    gradientEnd = Color(0xFFB9B9C3),
    secondary = Color(0xFFC9C5CE),
    tertiary = Color(0xFFD6D3DB),
)

fun paletteFor(accent: Accent): AccentPalette = when (accent) {
    Accent.VIOLET -> VioletPalette
    Accent.BLUE -> BluePalette
    Accent.GREEN -> GreenPalette
    Accent.ORANGE -> OrangePalette
    Accent.PINK -> PinkPalette
    Accent.MONO -> MonoPalette
}

// ---------------------------------------------------------------------------
// Нейтральные роли. Dark-first: мягкий «графитовый» фон и заметно более
// светлые контейнеры — карточки отделяются тоном, а не тенью. Светлая тема
// повторяет ту же иерархию в обратную сторону.
// ---------------------------------------------------------------------------

val DarkBackground = Color(0xFF0A0A0E)
val DarkSurface = Color(0xFF0F0F14)
val DarkSurfaceLowest = Color(0xFF08080C)
val DarkSurfaceLow = Color(0xFF121218)
val DarkSurfaceContainer = Color(0xFF14141B)
val DarkSurfaceHigh = Color(0xFF1A1A22)
val DarkSurfaceHighest = Color(0xFF22222C)
val DarkSurfaceVariant = Color(0xFF1C1C25)
val DarkSurfaceBright = Color(0xFF2B2B37)
val DarkSurfaceDim = Color(0xFF0A0A0E)
val DarkOnSurface = Color(0xFFE9E6F0)
val DarkOnSurfaceVariant = Color(0xFFA9A3B5)
val DarkOutline = Color(0xFF4B4658)
val DarkOutlineVariant = Color(0xFF2E2A38)
val DarkInverseSurface = Color(0xFFE9E6F0)
val DarkInverseOnSurface = Color(0xFF1B1B23)
val DarkScrim = Color(0xFF000000)

val LightBackground = Color(0xFFF9F7FC)
val LightSurface = Color(0xFFF3F0F8)
val LightSurfaceLowest = Color(0xFFFFFFFF)
val LightSurfaceLow = Color(0xFFF6F3FB)
val LightSurfaceContainer = Color(0xFFF0ECF5)
val LightSurfaceHigh = Color(0xFFEAE5F1)
val LightSurfaceHighest = Color(0xFFE2DCEB)
val LightSurfaceVariant = Color(0xFFE7E2EE)
val LightSurfaceBright = Color(0xFFFFFFFF)
val LightSurfaceDim = Color(0xFFDDD8E4)
val LightOnSurface = Color(0xFF1B1922)
val LightOnSurfaceVariant = Color(0xFF4D4759)
val LightOutline = Color(0xFF8A8495)
val LightOutlineVariant = Color(0xFFD8D2E2)
val LightInverseSurface = Color(0xFF312F38)
val LightInverseOnSurface = Color(0xFFF4EFF8)
val LightScrim = Color(0xFF000000)

fun AccentPalette.darkScheme() = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    inversePrimary = primaryLight,
    secondary = secondary,
    onSecondary = Color(0xFF16151C),
    secondaryContainer = Color(0xFF232230),
    onSecondaryContainer = Color(0xFFE7E1F0),
    tertiary = tertiary,
    onTertiary = Color(0xFF16151C),
    tertiaryContainer = Color(0xFF3A2430),
    onTertiaryContainer = Color(0xFFFFD9E4),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = primaryDark,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = DarkScrim,
    surfaceBright = DarkSurfaceBright,
    surfaceDim = DarkSurfaceDim,
    surfaceContainerLowest = DarkSurfaceLowest,
    surfaceContainerLow = DarkSurfaceLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = DarkSurfaceHighest,
)

fun AccentPalette.lightScheme() = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    inversePrimary = primaryDark,
    secondary = secondary,
    onSecondary = Color(0xFF16151C),
    secondaryContainer = Color(0xFFE7E1F0),
    onSecondaryContainer = Color(0xFF1C1B22),
    tertiary = tertiary,
    onTertiary = Color(0xFF16151C),
    tertiaryContainer = Color(0xFFF5DDE7),
    onTertiaryContainer = Color(0xFF33111F),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = primaryLight,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = LightScrim,
    surfaceBright = LightSurfaceBright,
    surfaceDim = LightSurfaceDim,
    surfaceContainerLowest = LightSurfaceLowest,
    surfaceContainerLow = LightSurfaceLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceHigh,
    surfaceContainerHighest = LightSurfaceHighest,
)

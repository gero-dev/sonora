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

/**
 * Основной акцент Sonora — фирменный «Spotify green»: один чистый зелёный без
 * градиентной растяжки. Контейнерные роли затемнены, чтобы текст на них читался
 * в обеих темах.
 */
val GreenPalette = AccentPalette(
    primaryDark = Color(0xFF1ED760),
    onPrimaryDark = Color(0xFF06210F),
    primaryContainerDark = Color(0xFF0F3D24),
    onPrimaryContainerDark = Color(0xFFB8F5C9),
    primaryLight = Color(0xFF0E7A35),
    onPrimaryLight = Color(0xFFFFFFFF),
    primaryContainerLight = Color(0xFFC8F2D6),
    onPrimaryContainerLight = Color(0xFF05210F),
    gradientStart = Color(0xFF1ED760),
    gradientEnd = Color(0xFF1ED760),
    secondary = Color(0xFF8FD6A5),
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

val RedPalette = AccentPalette(
    primaryDark = Color(0xFFFFB4AB),
    onPrimaryDark = Color(0xFF690005),
    primaryContainerDark = Color(0xFF93000A),
    onPrimaryContainerDark = Color(0xFFFFDAD6),
    primaryLight = Color(0xFFB3261E),
    onPrimaryLight = Color(0xFFFFFFFF),
    primaryContainerLight = Color(0xFFFFDAD6),
    onPrimaryContainerLight = Color(0xFF410002),
    gradientStart = Color(0xFFFF5252),
    gradientEnd = Color(0xFFFF9800),
    secondary = Color(0xFFFFB4AB),
    tertiary = Color(0xFFFFD7A6),
)

val TealPalette = AccentPalette(
    primaryDark = Color(0xFF80DEEA),
    onPrimaryDark = Color(0xFF00363A),
    primaryContainerDark = Color(0xFF00565C),
    onPrimaryContainerDark = Color(0xFFA6F1F5),
    primaryLight = Color(0xFF00696E),
    onPrimaryLight = Color(0xFFFFFFFF),
    primaryContainerLight = Color(0xFFA6F1F5),
    onPrimaryContainerLight = Color(0xFF002022),
    gradientStart = Color(0xFF00BFA5),
    gradientEnd = Color(0xFF00E5FF),
    secondary = Color(0xFF80DEEA),
    tertiary = Color(0xFFA5D6C0),
)

val YellowPalette = AccentPalette(
    primaryDark = Color(0xFFFFE082),
    onPrimaryDark = Color(0xFF3F2E00),
    primaryContainerDark = Color(0xFF5C4300),
    onPrimaryContainerDark = Color(0xFFFFE8A3),
    primaryLight = Color(0xFF7A5900),
    onPrimaryLight = Color(0xFFFFFFFF),
    primaryContainerLight = Color(0xFFFFE8A3),
    onPrimaryContainerLight = Color(0xFF261A00),
    gradientStart = Color(0xFFFFC107),
    gradientEnd = Color(0xFFFFEB3B),
    secondary = Color(0xFFFFE082),
    tertiary = Color(0xFFFFD3A6),
)

val IndigoPalette = AccentPalette(
    primaryDark = Color(0xFFB3B9FF),
    onPrimaryDark = Color(0xFF1A237E),
    primaryContainerDark = Color(0xFF303F9F),
    onPrimaryContainerDark = Color(0xFFE0E3FF),
    primaryLight = Color(0xFF3F51B5),
    onPrimaryLight = Color(0xFFFFFFFF),
    primaryContainerLight = Color(0xFFE0E3FF),
    onPrimaryContainerLight = Color(0xFF060C46),
    gradientStart = Color(0xFF5C6BC0),
    gradientEnd = Color(0xFF7C4DFF),
    secondary = Color(0xFFB3B9FF),
    tertiary = Color(0xFF8BD5FF),
)

val CyanPalette = AccentPalette(
    primaryDark = Color(0xFF80D8FF),
    onPrimaryDark = Color(0xFF00344A),
    primaryContainerDark = Color(0xFF00556F),
    onPrimaryContainerDark = Color(0xFFBEE9FF),
    primaryLight = Color(0xFF00638A),
    onPrimaryLight = Color(0xFFFFFFFF),
    primaryContainerLight = Color(0xFFBEE9FF),
    onPrimaryContainerLight = Color(0xFF001E2C),
    gradientStart = Color(0xFF00B0FF),
    gradientEnd = Color(0xFF00E5FF),
    secondary = Color(0xFF80D8FF),
    tertiary = Color(0xFF8BD5FF),
)

val BrownPalette = AccentPalette(
    primaryDark = Color(0xFFFFCCBC),
    onPrimaryDark = Color(0xFF4E2600),
    primaryContainerDark = Color(0xFF7A3E1D),
    onPrimaryContainerDark = Color(0xFFFFDBCC),
    primaryLight = Color(0xFF8D4E2A),
    onPrimaryLight = Color(0xFFFFFFFF),
    primaryContainerLight = Color(0xFFFFDBCC),
    onPrimaryContainerLight = Color(0xFF2E1500),
    gradientStart = Color(0xFFD2691E),
    gradientEnd = Color(0xFFFFB74D),
    secondary = Color(0xFFFFCCBC),
    tertiary = Color(0xFFFFD7A6),
)

fun paletteFor(accent: Accent): AccentPalette = when (accent) {
    Accent.VIOLET -> VioletPalette
    Accent.BLUE -> BluePalette
    Accent.GREEN -> GreenPalette
    Accent.ORANGE -> OrangePalette
    Accent.PINK -> PinkPalette
    Accent.MONO -> MonoPalette
    Accent.RED -> RedPalette
    Accent.TEAL -> TealPalette
    Accent.YELLOW -> YellowPalette
    Accent.INDIGO -> IndigoPalette
    Accent.CYAN -> CyanPalette
    Accent.BROWN -> BrownPalette
}

// ---------------------------------------------------------------------------
// Нейтральные роли — Pixel Experience × Spotify: почти чёрный «чернильный» фон,
// на пару тонов более светлые контейнеры и тонкие outline. Карточки отделяются
// тоном, а не тенью; никаких цветных подмесов в серых поверхностях.
// Светлая тема — та же иерархия в обратную сторону, без сиреневого оттенка.
// ---------------------------------------------------------------------------

val DarkBackground = Color(0xFF0B0B0C)
val DarkSurface = Color(0xFF121214)
val DarkSurfaceLowest = Color(0xFF080809)
val DarkSurfaceLow = Color(0xFF0F0F11)
val DarkSurfaceContainer = Color(0xFF17171A)
val DarkSurfaceHigh = Color(0xFF1E1E22)
val DarkSurfaceHighest = Color(0xFF242428)
val DarkSurfaceVariant = Color(0xFF17171A)
val DarkSurfaceBright = Color(0xFF2A2A2F)
val DarkSurfaceDim = Color(0xFF0B0B0C)
val DarkOnSurface = Color(0xFFF5F5F7)
val DarkOnSurfaceVariant = Color(0xFFA8A8AE)
val DarkOutline = Color(0xFF2C2C31)
val DarkOutlineVariant = Color(0xFF232327)
val DarkInverseSurface = Color(0xFFF5F5F7)
val DarkInverseOnSurface = Color(0xFF121214)
val DarkScrim = Color(0xFF000000)

val DarkError = Color(0xFFFF6B6B)
val DarkOnError = Color(0xFF3A0000)
val DarkErrorContainer = Color(0xFF4A1010)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val LightBackground = Color(0xFFFAFAFA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceLowest = Color(0xFFFFFFFF)
val LightSurfaceLow = Color(0xFFF7F7FA)
val LightSurfaceContainer = Color(0xFFF4F4F7)
val LightSurfaceHigh = Color(0xFFEBEBEF)
val LightSurfaceHighest = Color(0xFFE3E3E9)
val LightSurfaceVariant = Color(0xFFF2F2F5)
val LightSurfaceBright = Color(0xFFFFFFFF)
val LightSurfaceDim = Color(0xFFECECF1)
val LightOnSurface = Color(0xFF141416)
val LightOnSurfaceVariant = Color(0xFF5E5E66)
val LightOutline = Color(0xFFDADAE0)
val LightOutlineVariant = Color(0xFFE6E6EC)
val LightInverseSurface = Color(0xFF1F1F24)
val LightInverseOnSurface = Color(0xFFF5F5F7)
val LightScrim = Color(0xFF000000)

val LightError = Color(0xFFB3261E)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

fun AccentPalette.darkScheme() = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    inversePrimary = primaryLight,
    secondary = secondary,
    onSecondary = Color(0xFF101012),
    secondaryContainer = DarkSurfaceHigh,
    onSecondaryContainer = DarkOnSurface,
    tertiary = tertiary,
    onTertiary = Color(0xFF101012),
    tertiaryContainer = DarkSurfaceHighest,
    onTertiaryContainer = DarkOnSurface,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = primaryDark,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
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
    onSecondary = Color(0xFF101012),
    secondaryContainer = LightSurfaceHigh,
    onSecondaryContainer = LightOnSurface,
    tertiary = tertiary,
    onTertiary = Color(0xFF101012),
    tertiaryContainer = LightSurfaceHighest,
    onTertiaryContainer = LightOnSurface,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = primaryLight,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
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

package app.sonora.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Формы Sonora: крупные, мягкие, «пиксельные» скругления.
 *
 * Шкала: 8 / 12 / 18 / 24 / 30 dp. Карточки и hero-блоки берут large (24dp),
 * плотные строки и чипы — medium (18dp), мелкие элементы — small (12dp).
 * Кнопки Material3 в актуальной версии уже полностью скруглены (pill), поэтому
 * отдельная форма для них не нужна; нижняя навигация использует собственный
 * pill-индикатор.
 */
val SonoraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

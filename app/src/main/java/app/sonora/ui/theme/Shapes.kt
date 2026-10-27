package app.sonora.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Формы Sonora: мягкие карточки 20–24dp, чипы 12dp, нижние листы 28dp.
 * Material3 берёт отсюда формы кнопок (small), карточек (medium/large) и
 * текстовых полей (extraSmall); нижние листы по умолчанию скругляются на 28dp.
 */
val SonoraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

package app.sonora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Цвет иконки/текста поверх акцентной заливки. Берём onPrimary из темы: он
 * гарантированно контрастен к primary в обеих темах (для Spotify-зелёного это
 * почти чёрный, а не белый). Раньше здесь был эвристический luminance-выбор.
 */
@Composable
fun accentContentColor(): Color = MaterialTheme.colorScheme.onPrimary

/**
 * Крупная круглая кнопка Play в духе Spotify: ровная акцентная заливка
 * (primary темы), без градиента и без тени — плоская, как в Pixel.
 * Ставится поверх обложек плейлистов, альбомов и исполнителей.
 */
@Composable
fun CircularPlayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    contentDescription: String = "Играть",
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = contentDescription,
            tint = accentContentColor(),
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** «12 треков», «1 трек» — тот же счётчик, что в Spotify, но по-русски. */
fun tracksLabel(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val word = when {
        mod100 in 11..14 -> "треков"
        mod10 == 1 -> "трек"
        mod10 in 2..4 -> "трека"
        else -> "треков"
    }
    return "$count $word"
}

package app.sonora.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sonora.playback.PlayerUiState

private val IslandShape = RoundedCornerShape(22.dp)
private val IslandBackground = Color(0xFF08080B)

/**
 * Мини-плеер с раскрывающейся панелью — теперь **внизу экрана**, над
 * нижней навигацией (как в версии до 0.9.9).
 *
 * Почему снизу: на Samsung/One UI верхний Scaffold `topBar` с системными
 * inset'ами мог уезжать под шторку/вырез, из-за чего панель не появлялась.
 * Нижняя позиция не зависит от статус-бара и работает на всех оболочках:
 * LayoutDirection, вырезы и жестовая/кнопочная навигация обрабатываются
 * обычным `NavigationBar` ниже.
 *
 * Функциональность сохранена: обложка, название, play/pause, следующий трек,
 * прогресс, раскрытие с предыдущим/следующим и кнопкой «Открыть плеер».
 */
@Composable
fun DynamicIsland(
    state: PlayerUiState,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.current ?: return
    var expanded by remember(track.id) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Раскрытая панель растёт вверх, поэтому идёт первой в колонке.
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(180)) + fadeIn(tween(140)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(100)),
        ) {
            Column(
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(IslandBackground)
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.07f),
                        RoundedCornerShape(20.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoverArt(
                        url = track.artworkUrl ?: track.artworkUri,
                        size = 46.dp,
                        cornerRadius = 12.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.62f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = "Предыдущий трек",
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = onTogglePlay, modifier = Modifier.size(40.dp)) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (state.isPlaying) {
                                    Icons.Rounded.Pause
                                } else {
                                    Icons.Rounded.PlayArrow
                                },
                                contentDescription = if (state.isPlaying) "Пауза" else "Играть",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = "Следующий трек",
                            tint = Color.White,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (state.durationMs > 0L) {
                    ProgressLine(
                        progress = state.progress,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.White.copy(alpha = 0.16f),
                        height = 3.dp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpen)
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Открыть плеер",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clip(IslandShape)
                .background(IslandBackground)
                .border(1.dp, Color.White.copy(alpha = 0.07f), IslandShape)
                .clickable(onClick = onOpen)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverArt(
                    url = track.artworkUrl ?: track.artworkUri,
                    size = 40.dp,
                    cornerRadius = 12.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.62f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.isPlaying) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = "Играет",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                }
                IconButton(onClick = onTogglePlay, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = if (state.isPlaying) {
                            Icons.Rounded.Pause
                        } else {
                            Icons.Rounded.PlayArrow
                        },
                        contentDescription = if (state.isPlaying) "Пауза" else "Играть",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Следующий трек",
                        tint = Color.White.copy(alpha = 0.86f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Rounded.KeyboardArrowDown
                        } else {
                            Icons.Rounded.KeyboardArrowUp
                        },
                        contentDescription = if (expanded) "Свернуть" else "Развернуть",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            if (state.durationMs > 0L) {
                ProgressLine(
                    progress = state.progress,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.14f),
                    height = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun ProgressLine(
    progress: Float,
    color: Color,
    trackColor: Color,
    height: androidx.compose.ui.unit.Dp,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                .width(maxWidth * progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(color),
        )
    }
}

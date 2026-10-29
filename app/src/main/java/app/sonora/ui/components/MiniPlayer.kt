package app.sonora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sonora.playback.PlayerUiState
import app.sonora.ui.theme.LocalSonoraColors
import kotlinx.coroutines.flow.StateFlow

/**
 * Мини-плеер в духе Spotify: плавающая скруглённая панель над навигацией,
 * тонкая акцентная полоса прогресса, обложка 48dp, крупное название и
 * заметные play/pause. Поведение прежнее: тап открывает полный плеер,
 * свайп влево/вправо переключает трек, свайп вверх открывает плеер.
 */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    progress: StateFlow<Float>,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit = {},
    onArtistClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val track = state.current ?: return
    // Грубый прогресс приходит отдельным потоком (шаг ~1 %) и обновляет
    // только сам мини-плеер, не заставляя перерисовываться весь экран.
    val currentProgress by progress.collectAsState()
    val gradient = LocalSonoraColors.current.gradient
    val progressBrush = remember(gradient) { Brush.linearGradient(gradient) }
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 56.dp.toPx() }
    var horizontalDrag by remember { mutableStateOf(0f) }
    var verticalDrag by remember { mutableStateOf(0f) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = shape,
            )
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { horizontalDrag = 0f },
                    onHorizontalDrag = { change, amount ->
                        horizontalDrag += amount
                        change.consume()
                    },
                    onDragEnd = {
                        when {
                            horizontalDrag <= -swipeThresholdPx -> onNext()
                            horizontalDrag >= swipeThresholdPx -> onPrevious()
                        }
                        horizontalDrag = 0f
                    },
                    onDragCancel = { horizontalDrag = 0f },
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { verticalDrag = 0f },
                    onVerticalDrag = { change, amount ->
                        verticalDrag += amount
                        change.consume()
                    },
                    onDragEnd = {
                        if (verticalDrag <= -swipeThresholdPx) onOpen()
                        verticalDrag = 0f
                    },
                    onDragCancel = { verticalDrag = 0f },
                )
            }
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // Тонкая полоска прогресса вместо полноценного слайдера.
        // Ширину считаем от maxWidth, а не через fillMaxWidth(fraction):
        // при позиции 0 fillMaxWidth(0f) недопустим.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                modifier = Modifier
                    .width(maxWidth * currentProgress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(progressBrush),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverArt(
                url = track.artworkUrl ?: track.artworkUri,
                size = 48.dp,
                cornerRadius = 10.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (onArtistClick != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable(enabled = onArtistClick != null) {
                            onArtistClick?.invoke()
                        }
                        .padding(vertical = 2.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            // Крупная акцентная play/pause — как главная кнопка Spotify.
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(progressBrush)
                    .clickable(onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.isPlaying) "Пауза" else "Играть",
                    tint = accentContentColor(),
                    modifier = Modifier.size(22.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onNext),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "Следующий трек",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

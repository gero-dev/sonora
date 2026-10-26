package app.sonora.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Строка синхронизированного текста: таймкод и подпись. */
private data class LyricLine(val timeMs: Long, val text: String)

/** Сколько держится пауза авто-прокрутки после ручного скролла. */
private const val AUTO_SCROLL_RESUME_MS = 8_000L

/** Подпись для строки с таймкодом, но без слов (инструментальная пауза). */
private const val INSTRUMENTAL_LINE = "♪"

/**
 * `[mm:ss]`, `[mm:ss.xx]` с несколькими таймкодами на строку.
 * Дробная часть может отделяться точкой или двоеточием (популярный вариант LRC).
 */
private val LRC_TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

/** Служебные теги LRC, которые не являются текстом. */
private val LRC_META_TAG = Regex(
    """^\[(ar|ti|al|by|offset|re|ve|length|tool):.*]$""",
    RegexOption.IGNORE_CASE,
)

/** Смещение из `[offset:+250]` в LRC, если оно есть. */
private val LRC_OFFSET = Regex("""^\[offset:\s*([+-]?\d+)]$""", RegexOption.IGNORE_CASE)

/**
 * Нижний лист с текстом песни.
 *
 * Синхронизированный режим приближен к Metrolist: крупная типографика,
 * плавная подсветка, интерполяция позиции между тиками плеера, затухание
 * краёв, автоскролл с ручной паузой и кнопкой возврата. Без таймкодов
 * показывается обычный текст и честная пометка «Без таймкодов».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(
    title: String,
    lyrics: String?,
    syncedLyrics: String?,
    positionMs: Long,
    isPlaying: Boolean = false,
    speed: Float = 1f,
    positionSampledAtMs: Long = 0L,
    onSeek: ((Long) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    // Разбор LRC кэшируем: он не зависит от позиции плеера.
    val syncedLines = remember(syncedLyrics) { parseLrc(syncedLyrics) }
    // Запасной вариант — обычный текст; если его нет, чистим таймкоды у synced-версии.
    val plainText = remember(lyrics, syncedLyrics) {
        lyrics?.takeIf { it.isNotBlank() } ?: plainTextFromSynced(syncedLyrics)
    }
    if (syncedLines == null && plainText == null) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (syncedLines != null) {
                    "Синхронизировано · InnerTube / LRCLIB"
                } else {
                    "Без таймкодов · обычный текст"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // Мягкие края, как в Metrolist: текст не обрывается в линию.
                // Рисуем их под контентом, чтобы кнопка автоскролла оставалась видимой.
                EdgeFade(
                    modifier = Modifier.align(Alignment.TopCenter),
                    fromTop = true,
                )
                EdgeFade(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    fromTop = false,
                )
                if (syncedLines != null) {
                    SyncedLyrics(
                        lines = syncedLines,
                        positionMs = positionMs,
                        isPlaying = isPlaying,
                        speed = speed,
                        positionSampledAtMs = positionSampledAtMs,
                        // Без обработчика перемотки лист работает как статичный текст
                        // (например, когда открыт прямо из строки списка).
                        onSeek = onSeek ?: { _ -> },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    PlainLyrics(
                        text = plainText.orEmpty(),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun EdgeFade(modifier: Modifier = Modifier, fromTop: Boolean) {
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val colors = if (fromTop) {
        listOf(surface, surface.copy(alpha = 0.72f), Color.Transparent)
    } else {
        listOf(Color.Transparent, surface.copy(alpha = 0.72f), surface)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (fromTop) 64.dp else 96.dp)
            .background(Brush.verticalGradient(colors)),
    )
}

/**
 * Караоке-режим: строки по таймкодам, подсветка активной и авто-прокрутка.
 *
 * Позиция интерполируется между тиками PlaybackController (500 мс), поэтому
 * подсветка не отстаёт от звука. Ручной скролл отключает авто-прокрутку на
 * [AUTO_SCROLL_RESUME_MS] и показывает кнопку возврата.
 */
@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    positionMs: Long,
    isPlaying: Boolean,
    speed: Float,
    positionSampledAtMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var activeIndex by remember(lines) {
        mutableIntStateOf(activeIndexAt(lines, positionMs))
    }
    var autoScrollEnabled by remember { mutableStateOf(true) }
    var resumeTick by remember { mutableIntStateOf(0) }
    var userScrollAt by remember { mutableStateOf(0L) }

    // 60-fps цикл только для активной строки: сам список не рекомпозируется
    // на каждый кадр, меняется только activeIndex.
    LaunchedEffect(positionMs, positionSampledAtMs, isPlaying, speed, lines) {
        if (!isPlaying) {
            activeIndex = activeIndexAt(lines, positionMs)
            return@LaunchedEffect
        }
        val anchor = positionMs
        val anchorAt = SystemClock.elapsedRealtime()
        while (isActive) {
            withFrameNanos {
                val elapsed = (SystemClock.elapsedRealtime() - anchorAt).coerceAtLeast(0L)
                val interpolated = anchor + (elapsed * speed).toLong()
                val next = activeIndexAt(lines, interpolated)
                if (next != activeIndex) activeIndex = next
            }
        }
    }

    LaunchedEffect(listState) {
        var resumeJob: Job? = null
        launch {
            listState.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start) {
                    resumeJob?.cancel()
                    autoScrollEnabled = false
                    userScrollAt = SystemClock.elapsedRealtime()
                }
            }
        }
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && !autoScrollEnabled) {
                resumeJob?.cancel()
                resumeJob = launch {
                    delay(AUTO_SCROLL_RESUME_MS)
                    autoScrollEnabled = true
                    resumeTick++
                }
            }
        }
    }

    LaunchedEffect(activeIndex, autoScrollEnabled, resumeTick, lines) {
        if (!autoScrollEnabled || activeIndex < 0) return@LaunchedEffect
        val target = activeIndex.coerceIn(0, lines.lastIndex)
        val viewportHeight = snapshotFlow { listState.layoutInfo.viewportSize.height }
            .first { it > 0 }
        // Активная строка встаёт примерно на треть высоты списка от верха —
        // как в Metrolist.
        listState.animateScrollToItem(target, scrollOffset = -(viewportHeight / 3))
    }

    Box(modifier = modifier.navigationBarsPadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = 48.dp,
                bottom = 220.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(lines) { index, line ->
                LyricRow(
                    text = line.text,
                    isActive = index == activeIndex,
                    distance = abs(index - activeIndex),
                    beforeFirst = activeIndex < 0,
                    onClick = { onSeek(line.timeMs) },
                )
            }
        }
        AnimatedVisibility(
            visible = !autoScrollEnabled,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    autoScrollEnabled = true
                    resumeTick++
                },
            ) {
                Text("К текущей строке")
            }
        }
    }
}

@Composable
private fun LyricRow(
    text: String,
    isActive: Boolean,
    distance: Int,
    beforeFirst: Boolean,
    onClick: () -> Unit,
) {
    val targetAlpha = when {
        isActive -> 1f
        beforeFirst -> 0.55f
        distance <= 1 -> 0.34f
        distance == 2 -> 0.22f
        distance == 3 -> 0.14f
        else -> 0.08f
    }
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 240),
        label = "lyric-alpha",
    )
    val color by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 240),
        label = "lyric-color",
    )
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        color = color.copy(alpha = alpha),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.headlineMedium.copy(
            fontSize = if (isActive) 32.sp else 27.sp,
            lineHeight = 40.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = (-0.5).sp,
        ),
    )
}

/** Обычный текст без таймкодов: прокрутка, без подсветки и перемотки. */
@Composable
private fun PlainLyrics(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 120.dp),
    ) {
        text.lines().forEach { line ->
            if (line.isBlank()) {
                // Пустая строка — пауза между куплетами.
                Spacer(Modifier.height(12.dp))
            } else {
                Text(
                    text = line.trimEnd(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 20.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

private fun activeIndexAt(lines: List<LyricLine>, positionMs: Long): Int =
    lines.indexOfLast { it.timeMs <= positionMs }

/**
 * Разбирает LRC. Возвращает `null`, если таймкодов нет вовсе (тогда UI покажет
 * обычный текст), иначе — отсортированный по времени список строк.
 */
private fun parseLrc(raw: String?): List<LyricLine>? {
    val source = raw?.takeIf { it.isNotBlank() } ?: return null
    var offsetMs = 0L
    for (line in source.lines()) {
        LRC_OFFSET.find(line.trim())?.groupValues?.getOrNull(1)?.toLongOrNull()?.let {
            offsetMs = it
        }
    }
    val result = ArrayList<LyricLine>()
    for (line in source.lines()) {
        val stamps = LRC_TIMESTAMP.findAll(line).toList()
        if (stamps.isEmpty()) continue
        // Всё, что после последнего таймкода, — слова этой строки.
        val text = line.substring(stamps.last().range.last + 1).trim()
            .ifEmpty { INSTRUMENTAL_LINE }
        for (stamp in stamps) {
            val minutes = stamp.groupValues[1].toLongOrNull() ?: continue
            val seconds = stamp.groupValues[2].toLongOrNull() ?: continue
            val millis = fractionToMillis(stamp.groupValues[3])
            result += LyricLine(
                timeMs = (minutes * 60_000L + seconds * 1_000L + millis + offsetMs)
                    .coerceAtLeast(0L),
                text = text,
            )
        }
    }
    return result.sortedBy { it.timeMs }.takeIf { it.isNotEmpty() }
}

/** «34» → 340 мс, «345» → 345 мс, «5» → 500 мс. */
private fun fractionToMillis(fraction: String): Long {
    if (fraction.isEmpty()) return 0L
    return fraction.take(3).padEnd(3, '0').toLongOrNull() ?: 0L
}

/** Текст из LRC без таймкодов и служебных тегов — на случай битого synced-варианта. */
private fun plainTextFromSynced(raw: String?): String? {
    val source = raw?.takeIf { it.isNotBlank() } ?: return null
    return source.lines()
        .map { line -> line.replace(LRC_TIMESTAMP, "").trim() }
        .filter { line -> line.isNotEmpty() && !LRC_META_TAG.matches(line) }
        .joinToString("\n")
        .ifBlank { null }
}

package app.sonora.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import app.sonora.core.model.Source
import app.sonora.core.settings.AudioQuality
import app.sonora.core.settings.PlaybackMode
import app.sonora.core.util.Formatters
import app.sonora.data.download.SaveStatus
import app.sonora.data.download.TrackSaveState
import app.sonora.ui.MainViewModel
import app.sonora.ui.components.CoverArtBox
import app.sonora.ui.components.EmptyState
import app.sonora.ui.components.LyricsSheet
import app.sonora.ui.nav.Routes
import app.sonora.ui.theme.LocalSonoraColors
import coil.compose.AsyncImage
import kotlin.math.abs
import kotlinx.coroutines.delay

private const val SPEED_MENU_MIN = 0.75f

private val SPEED_OPTIONS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** Цвета иконок в панели действий на затемнённом фоне плеера. */
private val ActionTint = Color.White.copy(alpha = 0.82f)
private val ActionTintMuted = Color.White.copy(alpha = 0.48f)

/** Сколько живёт всплывающая подсказка на экране плеера. */
private const val HINT_DURATION_MS = 2_600L

/** Сколько ждём текст песни, прежде чем честно сказать, что его нет. */
private const val LYRICS_WAIT_MS = 8_000L

/**
 * Полноэкранный плеер: размытая обложка на фоне, крупный арт, кастомный слайдер,
 * транспорт и одна строка иконочных действий (избранное, таймер, очередь, текст, скачивание).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(vm: MainViewModel, onBack: () -> Unit, navigate: (String) -> Unit) {
    val player by vm.player.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val saveStates by vm.saveStates.collectAsState()
    val settings by vm.settings.collectAsState()

    var menuExpanded by remember { mutableStateOf(false) }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var sleepDialogVisible by remember { mutableStateOf(false) }
    var qualityDialogVisible by remember { mutableStateOf(false) }
    var lyricsVisible by remember { mutableStateOf(false) }
    var lyricsRequestedFor by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    val streamingEnabled = settings.playbackMode == PlaybackMode.STREAMING

    // Жесты: горизонтальный свайп — трек, вниз — закрыть, вверх — очередь.
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val swipeThreshold = with(LocalDensity.current) { 88.dp.toPx() }

    val track = player.current
    val artwork = track?.artworkUrl ?: track?.artworkUri
    val gradient = LocalSonoraColors.current.gradient
    // На затемнённом фоне плеера текст белый; на пустом состоянии — обычный цвет темы.
    val foreground = if (track != null) Color.White else MaterialTheme.colorScheme.onSurface

    // Подсказка живёт пару секунд и гаснет сама.
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(HINT_DURATION_MS)
            hint = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragOffset = Offset.Zero },
                    onDragEnd = {
                        val dx = dragOffset.x
                        val dy = dragOffset.y
                        dragOffset = Offset.Zero
                        when {
                            abs(dx) > swipeThreshold && abs(dx) > abs(dy) ->
                                if (dx < 0) vm.next() else vm.previous()

                            dy > swipeThreshold -> onBack()
                            dy < -swipeThreshold -> navigate(Routes.QUEUE)
                        }
                    },
                    onDragCancel = { dragOffset = Offset.Zero },
                    onDrag = { change, amount ->
                        change.consume()
                        dragOffset += amount
                    },
                )
            },
    ) {
        // Фон: размытая обложка или градиент, поверх — затемняющая шторка.
        if (track != null) {
            if (!artwork.isNullOrBlank()) {
                AsyncImage(
                    model = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0.45f },
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(gradient)),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.55f),
                                Color.Black.copy(alpha = 0.92f),
                            ),
                        ),
                    ),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            // Верхняя строка: свернуть, заголовок, меню.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Свернуть",
                        tint = foreground,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Text(
                    text = "Сейчас играет",
                    style = MaterialTheme.typography.labelLarge,
                    color = foreground.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Ещё",
                            tint = foreground,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        track?.artist?.takeIf { it.isNotBlank() }?.let { artist ->
                            DropdownMenuItem(
                                text = { Text("Перейти к исполнителю") },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Person, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    navigate(Routes.artist(artist))
                                },
                            )
                            HorizontalDivider()
                        }
                        DropdownMenuItem(
                            text = { Text("Очередь") },
                            onClick = {
                                menuExpanded = false
                                navigate(Routes.QUEUE)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Таймер сна") },
                            onClick = {
                                menuExpanded = false
                                sleepDialogVisible = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Качество · ${settings.audioQuality.label}") },
                            leadingIcon = {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                qualityDialogVisible = true
                            },
                        )
                        HorizontalDivider()
                        SPEED_OPTIONS.forEach { speed ->
                            DropdownMenuItem(
                                text = { Text("Скорость ${speedMenuLabel(speed)}") },
                                onClick = {
                                    menuExpanded = false
                                    vm.setSpeedPreset(speed)
                                },
                            )
                        }
                    }
                }
            }

            if (track == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Rounded.MusicOff,
                        title = "Ничего не играет",
                        subtitle = "Выберите трек в медиатеке — и он появится здесь.",
                    )
                }
                TextButton(
                    onClick = { navigate(Routes.LIBRARY) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text("Открыть медиатеку", color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(24.dp))
            } else {
                val current = track
                val isFavorite = current.id in favorites
                val hasLyrics = !current.lyrics.isNullOrBlank() ||
                    !current.syncedLyrics.isNullOrBlank()
                val saveState = saveStates[current.id]
                    ?: if (vm.isTrackDownloaded(current)) TrackSaveState.done() else null
                val sleepEndsAt = player.sleepEndsAt
                val sleepDescription = if (sleepEndsAt != null) {
                    "Таймер сна, осталось ${remainingLabel(sleepEndsAt)}"
                } else {
                    "Таймер сна"
                }

                // Текст уже есть — открываем сразу, иначе просим ViewModel его найти.
                val onLyricsClick: () -> Unit = {
                    if (hasLyrics) {
                        lyricsVisible = true
                    } else {
                        vm.enrichTrack(current)
                        lyricsRequestedFor = current.id
                        hint = "Ищу текст…"
                    }
                }

                // Как только обогащение принесло текст — открываем лист;
                // если текста так и нет, честно сообщаем об этом.
                LaunchedEffect(
                    lyricsRequestedFor,
                    current.id,
                    current.lyrics,
                    current.syncedLyrics,
                ) {
                    if (lyricsRequestedFor != current.id) return@LaunchedEffect
                    if (hasLyrics) {
                        lyricsVisible = true
                        lyricsRequestedFor = null
                    } else {
                        delay(LYRICS_WAIT_MS)
                        if (lyricsRequestedFor == current.id) {
                            hint = "Текст не найден"
                            lyricsRequestedFor = null
                        }
                    }
                }

                // Статус сохранения: «Готово»/«Ошибка» показываем только на переходе.
                var previousSaveStatus by remember(current.id) {
                    mutableStateOf(saveState?.status)
                }
                LaunchedEffect(current.id, saveState?.status) {
                    val status = saveState?.status
                    if (previousSaveStatus == SaveStatus.DOWNLOADING ||
                        previousSaveStatus == SaveStatus.QUEUED
                    ) {
                        when (status) {
                            SaveStatus.DONE -> hint = "Сохранено на устройство"
                            SaveStatus.ERROR -> hint = saveState?.message
                                ?.let { "Не удалось сохранить: $it" }
                                ?: "Не удалось сохранить трек"
                            else -> Unit
                        }
                    }
                    previousSaveStatus = status
                }

                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                ) {
                    // Обложка: подстраивается под свободное место, но не больше 320dp.
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        val artSize = minOf(maxWidth * 0.92f, maxHeight * 0.94f, 320.dp)
                        CoverArtBox(
                            url = current.artworkUrl ?: current.artworkUri,
                            cornerRadius = 24.dp,
                            modifier = Modifier
                                .size(artSize)
                                .graphicsLayer {
                                    translationX = dragOffset.x
                                    translationY = dragOffset.y * 0.35f
                                    rotationZ = (dragOffset.x / 60f).coerceIn(-12f, 12f)
                                }
                                .pointerInput(Unit) {
                                    detectTapGestures(onDoubleTap = { vm.togglePlayPause() })
                                }
                                .shadow(20.dp, RoundedCornerShape(24.dp)),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    // Как в Spotify/Metrolist: тап по исполнителю открывает его страницу.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = current.artist.isNotBlank()) {
                                navigate(Routes.artist(current.artist))
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = current.displaySubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (current.artist.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White.copy(alpha = 0.7f)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (current.artist.isNotBlank()) {
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = "Перейти к исполнителю",
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    if (saveState?.isActive == true) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (saveState.percent in 0..100) {
                                LinearProgressIndicator(
                                    progress = saveState.percent / 100f,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(4.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = Color.White.copy(alpha = 0.18f),
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(4.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = Color.White.copy(alpha = 0.18f),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = saveState.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.78f),
                            )
                        }
                    }
                    if (!current.playable) {
                        Spacer(Modifier.height(12.dp))
                        NotPlayableBanner(onOpen = { vm.playOrOpen(current) })
                    }
                    Spacer(Modifier.height(20.dp))

                    // Слайдер: локальное состояние, чтобы позиция не «дралась» с плеером.
                    var dragging by remember { mutableStateOf(false) }
                    var dragValue by remember { mutableStateOf(0f) }
                    val durationMs = player.durationMs
                    val progress = when {
                        dragging -> dragValue
                        durationMs > 0L -> player.positionMs.toFloat() / durationMs
                        else -> 0f
                    }
                    SeekBar(
                        progress = progress,
                        enabled = durationMs > 0L,
                        onScrub = {
                            dragging = true
                            dragValue = it
                        },
                        onScrubFinished = {
                            if (durationMs > 0L && dragging) {
                                vm.seekTo((dragValue * durationMs).toLong())
                            }
                            dragging = false
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = Formatters.duration(
                                if (dragging) (dragValue * durationMs).toLong() else player.positionMs,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        Text(
                            text = Formatters.duration(durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    // Транспорт: 56dp play, остальные — компактные иконки.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Перемешивание + переключатель стриминга, как в Spotify:
                        // обычный тап — shuffle, долгое нажатие — источник воспроизведения.
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        onClick = { vm.toggleShuffle() },
                                        onLongClick = { sourceMenuExpanded = true },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Shuffle,
                                    contentDescription = if (streamingEnabled) {
                                        "Перемешать. Долгое нажатие — стриминг"
                                    } else {
                                        "Перемешать. Стриминг выключен"
                                    },
                                    tint = if (player.shuffle) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        ActionTint
                                    },
                                    modifier = Modifier.size(24.dp),
                                )
                                Icon(
                                    imageVector = if (streamingEnabled) {
                                        Icons.Rounded.Cloud
                                    } else {
                                        Icons.Rounded.CloudOff
                                    },
                                    contentDescription = null,
                                    tint = if (streamingEnabled) {
                                        ActionTint
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 8.dp, bottom = 7.dp)
                                        .size(11.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = sourceMenuExpanded,
                                onDismissRequest = { sourceMenuExpanded = false },
                            ) {
                                Text(
                                    text = "Источник воспроизведения",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                                DropdownMenuItem(
                                    text = { Text("Онлайн + устройство") },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Cloud, contentDescription = null)
                                    },
                                    trailingIcon = {
                                        if (streamingEnabled) {
                                            Icon(Icons.Rounded.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        sourceMenuExpanded = false
                                        vm.setStreamingEnabled(true)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Только на устройстве") },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.CloudOff, contentDescription = null)
                                    },
                                    trailingIcon = {
                                        if (!streamingEnabled) {
                                            Icon(Icons.Rounded.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        sourceMenuExpanded = false
                                        vm.setStreamingEnabled(false)
                                    },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (player.shuffle) "Перемешивание включено"
                                            else "Перемешивание выключено",
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Shuffle, contentDescription = null)
                                    },
                                    trailingIcon = {
                                        if (player.shuffle) {
                                            Icon(Icons.Rounded.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        sourceMenuExpanded = false
                                        vm.toggleShuffle()
                                    },
                                )
                            }
                        }
                        IconButton(onClick = vm::previous) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = "Предыдущий трек",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                        IconButton(
                            onClick = vm::togglePlayPause,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(gradient)),
                        ) {
                            if (player.isBuffering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.5.dp,
                                    color = Color.White,
                                )
                            } else {
                                Icon(
                                    imageVector = if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (player.isPlaying) "Пауза" else "Играть",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                        IconButton(onClick = vm::next) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = "Следующий трек",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                        IconButton(onClick = vm::cycleRepeat) {
                            Icon(
                                imageVector = if (player.repeatMode == Player.REPEAT_MODE_ONE) {
                                    Icons.Rounded.RepeatOne
                                } else {
                                    Icons.Rounded.Repeat
                                },
                                contentDescription = "Повтор",
                                tint = if (player.repeatMode != Player.REPEAT_MODE_OFF) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    ActionTint
                                },
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))

                    // Одна строка иконочных действий без подписей: равные слоты, центр.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ActionSlot {
                            IconButton(onClick = { vm.toggleFavorite(current) }) {
                                Icon(
                                    imageVector = if (isFavorite) {
                                        Icons.Rounded.Favorite
                                    } else {
                                        Icons.Rounded.FavoriteBorder
                                    },
                                    contentDescription = if (isFavorite) {
                                        "Убрать из избранного"
                                    } else {
                                        "В избранное"
                                    },
                                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else ActionTint,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        ActionSlot {
                            IconButton(onClick = { sleepDialogVisible = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.Bedtime,
                                    contentDescription = sleepDescription,
                                    tint = if (sleepEndsAt != null) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        ActionTint
                                    },
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        ActionSlot {
                            IconButton(onClick = { navigate(Routes.QUEUE) }) {
                                Icon(
                                    imageVector = Icons.Rounded.QueueMusic,
                                    contentDescription = "Очередь",
                                    tint = ActionTint,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        ActionSlot {
                            IconButton(onClick = onLyricsClick) {
                                Icon(
                                    imageVector = Icons.Rounded.Lyrics,
                                    contentDescription = "Текст песни",
                                    tint = if (hasLyrics) ActionTint else ActionTintMuted,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        // Сохранение — только для треков, которые ещё не на устройстве.
                        if (current.playable && current.source != Source.LOCAL &&
                            saveState?.status != SaveStatus.UNAVAILABLE
                        ) {
                            ActionSlot {
                                when (saveState?.status) {
                                    SaveStatus.QUEUED, SaveStatus.DOWNLOADING -> Box(
                                        modifier = Modifier.size(44.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (saveState.percent in 0..99) {
                                            CircularProgressIndicator(
                                                progress = saveState.percent / 100f,
                                                modifier = Modifier.size(22.dp),
                                                strokeWidth = 2.5.dp,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        } else {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = ActionTint,
                                            )
                                        }
                                    }

                                    SaveStatus.DONE -> IconButton(
                                        onClick = { hint = "Уже сохранено на устройство" },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = "Сохранено",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }

                                    SaveStatus.ERROR -> IconButton(
                                        onClick = { vm.retrySave(current) },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Download,
                                            contentDescription = "Повторить сохранение",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }

                                    else -> IconButton(onClick = { vm.downloadTrack(current) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Download,
                                            contentDescription = "Сохранить на устройство",
                                            tint = ActionTint,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Долгое нажатие на ⤨ — стриминг · " +
                            if (streamingEnabled) "онлайн включён" else "только устройство",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (streamingEnabled) {
                            Color.White.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Подсказка поверх контента: «Ищу текст…», «Скачано в библиотеку» и т.п.
        hint?.let { text ->
            PlayerHintPill(
                text = text,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(bottom = 88.dp),
            )
        }
    }

    if (sleepDialogVisible) {
        AlertDialog(
            onDismissRequest = { sleepDialogVisible = false },
            title = { Text("Таймер сна") },
            text = {
                Column {
                    Text(
                        text = "Остановить воспроизведение через:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    listOf(15, 30, 45, 60).forEach { minutes ->
                        TextButton(
                            onClick = {
                                vm.startSleepTimer(minutes)
                                sleepDialogVisible = false
                            },
                        ) {
                            Text("$minutes мин")
                        }
                    }
                    val endsAt = player.sleepEndsAt
                    if (endsAt != null) {
                        Text(
                            text = "Осталось: ${remainingLabel(endsAt)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                if (player.sleepEndsAt != null) {
                    TextButton(
                        onClick = {
                            vm.cancelSleepTimer()
                            sleepDialogVisible = false
                        },
                    ) { Text("Отменить таймер") }
                }
            },
            dismissButton = {
                TextButton(onClick = { sleepDialogVisible = false }) { Text("Закрыть") }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    if (qualityDialogVisible) {
        AlertDialog(
            onDismissRequest = { qualityDialogVisible = false },
            title = { Text("Качество аудио") },
            text = {
                Column {
                    Text(
                        text = "Для онлайн-воспроизведения. Сохранённые файлы играются " +
                            "в исходном качестве.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    AudioQuality.values().forEach { quality ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setAudioQuality(quality)
                                    qualityDialogVisible = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.audioQuality == quality,
                                onClick = {
                                    vm.setAudioQuality(quality)
                                    qualityDialogVisible = false
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = quality.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = quality.hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { qualityDialogVisible = false }) { Text("Закрыть") }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    // Лист с текстом песни: открывается только если текст реально есть.
    // Позиция берётся из снимка плеера, тап по строке перематывает трек.
    if (lyricsVisible && track != null) {
        LyricsSheet(
            title = track.title,
            lyrics = track.lyrics,
            syncedLyrics = track.syncedLyrics,
            positionMs = player.positionMs,
            isPlaying = player.isPlaying,
            speed = player.speed,
            positionSampledAtMs = player.positionSampledAtMs,
            onSeek = { vm.seekTo(it) },
            onDismiss = { lyricsVisible = false },
        )
    }
}

/** Один равный слот в строке иконочных действий плеера. */
@Composable
private fun RowScope.ActionSlot(content: @Composable () -> Unit) {
    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
        content()
    }
}

/**
 * Кастомный слайдер позиции: толще стандартного Material-слайдера,
 * скруглённые концы, акцентная заливка прогресса и крупный круглый ползунок.
 */
@Composable
private fun SeekBar(
    progress: Float,
    enabled: Boolean,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gradient = LocalSonoraColors.current.gradient
    var widthPx by remember { mutableStateOf(1f) }
    val clamped = progress.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
                setProgress { target ->
                    onScrub(target.coerceIn(0f, 1f))
                    onScrubFinished()
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { position ->
                    onScrub((position.x / widthPx).coerceIn(0f, 1f))
                    onScrubFinished()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { position ->
                        onScrub((position.x / widthPx).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onScrubFinished() },
                    onDragCancel = { onScrubFinished() },
                ) { change, _ ->
                    change.consume()
                    onScrub((change.position.x / widthPx).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
        ) {
            val trackHeight = 6.dp.toPx()
            val thumbRadius = 8.dp.toPx()
            val centerY = size.height / 2f
            val corner = CornerRadius(trackHeight / 2f, trackHeight / 2f)
            val activeWidth = size.width * clamped

            drawRoundRect(
                color = Color.White.copy(alpha = 0.22f),
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(size.width, trackHeight),
                cornerRadius = corner,
            )
            if (clamped > 0f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(gradient),
                    topLeft = Offset(0f, centerY - trackHeight / 2f),
                    size = Size(activeWidth, trackHeight),
                    cornerRadius = corner,
                )
            }
            val maxThumbX = size.width - thumbRadius
            val thumbX = if (maxThumbX <= thumbRadius) {
                size.width / 2f
            } else {
                activeWidth.coerceIn(thumbRadius, maxThumbX)
            }
            drawCircle(
                color = Color.White,
                radius = thumbRadius,
                center = Offset(thumbX, centerY),
            )
        }
    }
}

/** Всплывающая подсказка плеера (текст песни, скачивание). */
@Composable
private fun PlayerHintPill(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

/** Плашка для трека, который нельзя включить в Sonora. */
@Composable
private fun NotPlayableBanner(onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.OpenInNew,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Воспроизведение недоступно в приложении",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpen) {
            Text("Открыть в сервисе", color = Color.White)
        }
    }
}

private fun speedMenuLabel(speed: Float): String = when (speed) {
    SPEED_MENU_MIN -> "0.75×"
    1f -> "1×"
    1.25f -> "1.25×"
    1.5f -> "1.5×"
    else -> "2×"
}

private fun remainingLabel(endsAt: Long): String {
    val seconds = ((endsAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
    val minutes = seconds / 60
    val rest = seconds % 60
    return "${minutes}:${rest.toString().padStart(2, '0')}"
}

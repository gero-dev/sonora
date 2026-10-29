package app.sonora.ui.components

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sonora.core.model.Playlist
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.data.download.SaveStatus
import app.sonora.data.download.TrackSaveState
import app.sonora.ui.nav.LocalArtistNavigator
import app.sonora.ui.nav.LocalTrackRadio
import app.sonora.ui.theme.LocalSonoraColors
import coil.compose.AsyncImage

/**
 * Базовые переиспользуемые элементы интерфейса Sonora.
 * Здесь же — лист действий над треком и диалог создания плейлиста,
 * чтобы одинаково вести себя на Главной, в Поиске и в Медиатеке.
 */

/** Акцентный градиент текущей палитры (для hero-карточек и активных состояний). */
@Composable
fun accentGradient(): Brush = Brush.linearGradient(LocalSonoraColors.current.gradient)

/** Цвет сервиса-источника: используется в чипах и бейджах. */
fun sourceColor(source: Source): Color = when (source) {
    Source.LOCAL -> Color(0xFF7C4DFF)
    Source.SPOTIFY -> Color(0xFF1DB954)
    Source.YOUTUBE -> Color(0xFFFF0033)
    Source.SOUNDCLOUD -> Color(0xFFFF5500)
    Source.ARCHIVE -> Color(0xFF7BD389)
    Source.JAMENDO -> Color(0xFFFFB454)
}

/**
 * Обложка произвольного размера: рисует картинку, если она есть,
 * иначе — градиентную заглушку с нотой.
 */
@Composable
fun CoverArtBox(
    url: String?,
    contentDescription: String? = null,
    cornerRadius: Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            val gradient = LocalSonoraColors.current.gradient
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Brush.linearGradient(gradient)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.fillMaxSize(0.32f),
                )
            }
        }
    }
}

/** Квадратная обложка фиксированного размера. */
@Composable
fun CoverArt(
    url: String?,
    size: Dp,
    cornerRadius: Dp = 12.dp,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    CoverArtBox(
        url = url,
        contentDescription = contentDescription,
        cornerRadius = cornerRadius,
        modifier = modifier.size(size),
    )
}

/** Градиентная заглушка обложки (когда картинки нет вообще). */
@Composable
fun ArtworkFallback(
    size: Dp,
    cornerRadius: Dp = 12.dp,
    modifier: Modifier = Modifier,
) {
    CoverArtBox(
        url = null,
        contentDescription = null,
        cornerRadius = cornerRadius,
        modifier = modifier.size(size),
    )
}

/** Кнопка «назад»: если onBack не передан — используется системный диспетчер. */
@Composable
fun BackIconButton(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val owner = LocalOnBackPressedDispatcherOwner.current
    IconButton(onClick = {
        if (onClick != null) onClick.invoke() else owner?.onBackPressedDispatcher?.onBackPressed()
    }, modifier = modifier) {
        Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
    }
}

/** Заголовок секции с необязательным действием справа. */
@Composable
fun SectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Пустое состояние: иконка, заголовок и подсказка. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Небольшой цветной чип источника (Spotify / YouTube Music / SoundCloud). */
@Composable
fun SourceChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/**
 * Строка трека: обложка, название, исполнитель, сердечко, необязательный trailing-слот
 * и компактное меню «Ещё» (текст песни, скачивание — только если оно разрешено).
 * Долгое нажатие открывает лист действий (см. [TrackActionSheet]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    isFavorite: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onFavorite: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    saveState: TrackSaveState? = null,
    modifier: Modifier = Modifier,
) {
    val hasLyrics = !track.lyrics.isNullOrBlank() || !track.syncedLyrics.isNullOrBlank()
    val openArtist = LocalArtistNavigator.current
    val trackRadio = LocalTrackRadio.current
    val canDownload = onDownload != null && track.streamingOnly &&
        saveState?.status != SaveStatus.UNAVAILABLE
    val saving = saveState?.status == SaveStatus.QUEUED ||
        saveState?.status == SaveStatus.DOWNLOADING
    var menuOpen by remember { mutableStateOf(false) }
    var lyricsVisible by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isPlaying) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            url = track.artworkUrl ?: track.artworkUri,
            size = 48.dp,
            cornerRadius = 10.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val saveFailed = saveState?.status == SaveStatus.ERROR
            Text(
                text = when {
                    saving -> saveState?.label ?: track.displaySubtitle
                    saveFailed -> "Ошибка сохранения — нажмите ↻"
                    else -> track.displaySubtitle
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    saving -> MaterialTheme.colorScheme.primary
                    saveFailed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(
                    enabled = !saving && !saveFailed && track.artist.isNotBlank(),
                ) {
                    // Как в Spotify/Metrolist: имя артиста ведёт на его страницу.
                    openArtist(track.artist)
                },
            )
        }
        if (saving) {
            if (saveState?.percent in 0..99) {
                CircularProgressIndicator(
                    progress = (saveState?.percent ?: 0) / 100f,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        } else if (saveState?.status == SaveStatus.ERROR && onDownload != null) {
            IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = "Повторить сохранение",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else if (track.savedFromStreaming || saveState?.status == SaveStatus.DONE) {
            Icon(
                imageVector = Icons.Rounded.DownloadDone,
                contentDescription = "Сохранено на устройстве",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = 2.dp),
            )
        } else if (!track.playable) {
            Icon(
                imageVector = Icons.Rounded.OpenInNew,
                contentDescription = "Откроется в сервисе",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = 2.dp),
            )
        }
        IconButton(onClick = onFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (isFavorite) "Убрать из избранного" else "В избранное",
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailing != null) {
            trailing()
        }
        // Меню показываем только когда в нём есть хотя бы одно действие.
        if (hasLyrics || canDownload || trackRadio != null) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "Ещё",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    if (trackRadio != null) {
                        DropdownMenuItem(
                            text = { Text("Радио по треку") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Radio,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                trackRadio.invoke(track)
                            },
                        )
                    }
                    if (hasLyrics) {
                        DropdownMenuItem(
                            text = { Text("Текст песни") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Lyrics,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                lyricsVisible = true
                            },
                        )
                    }
                    if (canDownload) {
                        val inProgress = saveState?.status == SaveStatus.QUEUED ||
                            saveState?.status == SaveStatus.DOWNLOADING
                        val done = saveState?.status == SaveStatus.DONE
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when {
                                        inProgress -> saveState?.label ?: "Сохранение…"
                                        done -> "Сохранено на устройстве"
                                        saveState?.status == SaveStatus.ERROR ->
                                            "Повторить сохранение"
                                        else -> "Сохранить на устройство"
                                    },
                                )
                            },
                            leadingIcon = {
                                if (inProgress && saveState?.percent in 0..99) {
                                    CircularProgressIndicator(
                                        progress = (saveState?.percent ?: 0) / 100f,
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (done) {
                                            Icons.Rounded.CheckCircle
                                        } else {
                                            Icons.Rounded.Download
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                            enabled = !inProgress && !done,
                            onClick = {
                                menuOpen = false
                                onDownload?.invoke()
                            },
                        )
                    }
                }
            }
        }
    }

    // Текст песни прямо из списка: статичный лист, без перемотки.
    if (lyricsVisible) {
        LyricsSheet(
            title = track.title,
            lyrics = track.lyrics,
            syncedLyrics = track.syncedLyrics,
            positionMs = 0L,
            onDismiss = { lyricsVisible = false },
        )
    }
}

/**
 * Лист действий над треком: играть, в очередь, следующим, в плейлист, в избранное.
 * Переиспользуется на всех экранах, где есть список треков.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionSheet(
    track: Track,
    isFavorite: Boolean,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToPlaylist: (Playlist) -> Unit,
    onCreatePlaylist: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenArtist: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    downloadLabel: String = "Скачать",
) {
    val artistNavigator = LocalArtistNavigator.current
    val artistAction = onOpenArtist ?: if (track.artist.isNotBlank()) {
        { artistNavigator(track.artist) }
    } else {
        null
    }
    val startRadio = LocalTrackRadio.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.displaySubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            SheetAction(Icons.Rounded.PlayArrow, "Играть", onPlay)
            SheetAction(Icons.Rounded.QueueMusic, "В очередь", onAddToQueue)
            SheetAction(Icons.Rounded.PlaylistPlay, "Играть следующим", onPlayNext)
            if (startRadio != null) {
                SheetAction(
                    icon = Icons.Rounded.Radio,
                    label = "Радио по треку",
                    onClick = { startRadio.invoke(track) },
                )
            }
            if (artistAction != null) {
                SheetAction(Icons.Rounded.Person, "Перейти к исполнителю", artistAction)
            }
            SheetAction(
                icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                label = if (isFavorite) "Убрать из избранного" else "В избранное",
                onClick = onToggleFavorite,
            )
            if (onDownload != null) {
                SheetAction(
                    icon = Icons.Rounded.Download,
                    label = downloadLabel,
                    onClick = onDownload,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                text = "Добавить в плейлист",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            val editable = playlists.filter { it.editable }
            if (editable.isEmpty()) {
                Text(
                    text = "Своих плейлистов пока нет",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                editable.forEach { playlist ->
                    SheetAction(
                        icon = Icons.Rounded.PlaylistAdd,
                        label = playlist.name,
                        onClick = { onAddToPlaylist(playlist) },
                    )
                }
            }
            SheetAction(Icons.Rounded.Add, "Создать плейлист", onCreatePlaylist)
        }
    }
}

/** Диалог создания плейлиста с названием. */
@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    title: String = "Новый плейлист",
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Название") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Создать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** Строка действия в нижнем листе. */
@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

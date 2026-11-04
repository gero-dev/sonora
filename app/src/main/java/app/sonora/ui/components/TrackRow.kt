package app.sonora.ui.components

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.Delete
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
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
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
import coil.request.ImageRequest

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
 * иначе — заглушку с нотой.
 *
 * Размер для декодирования берётся из фактически занятого места, а не из
 * исходного файла. Без этой подсказки Coil декодировал обложку в её полном
 * разрешении: картинка 1000×1000 в строке высотой 56dp занимала около 4 МБ
 * памяти вместо 12 КБ. В списке из десятков строк это десятки мегабайт, и на
 * слабом устройстве сборщик мусора начинает работать во время прокрутки —
 * отсюда рывки.
 *
 * Ширина измеряется через `onSizeChanged`: до первого прохода компоновки её
 * ещё нет, а подставить на этот момент «оригинал» и значит получить
 * полноразмерный декод. До измерения рисуется заглушка — она бесплатна.
 */
@Composable
fun CoverArtBox(
    url: String?,
    contentDescription: String? = null,
    cornerRadius: Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(cornerRadius)
    // Ширина в пикселях после первой компоновки. 0 — ещё не измерено.
    var targetPx by remember { mutableIntStateOf(0) }
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .onSizeChanged { size ->
                val measured = size.width.coerceIn(1, MAX_ARTWORK_PX)
                if (measured != targetPx) targetPx = measured
            },
    ) {
        if (!url.isNullOrBlank() && targetPx > 0) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    // Не грузим оригинал: просим ровно тот размер, что видно.
                    // Кроссфейд задан один раз в общем Coil-загрузчике
                    // (SonoraApp), дублировать его на каждый запрос незачем.
                    .size(targetPx)
                    .build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            // Плоская заглушка: акцент только в ноте, без градиента на весь экран.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxSize(0.32f),
                )
            }
        }
    }
}

/**
 * Потолок декодирования обложки. Полноэкранный арт плеера — это ~340dp, на
 * экране 3x это около 1020px; 1024 покрывает и его, и любую плитку каталога,
 * но не даёт декодировать картинку 4000px из тега файла.
 */
private const val MAX_ARTWORK_PX = 1024

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

/**
 * Заголовок секции в духе Spotify: крупный жирный текст, приглушённый
 * подзаголовок и компактное действие справа («Все» / «Показать все»).
 */
@Composable
fun SectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Пустое состояние: акцентная иконка в круге, заголовок и подсказка. */
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
            .padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Небольшой цветной чип источника (Spotify / YouTube Music / SoundCloud).
 *
 * В строках списков больше не применяется — там источник показывает [SourceDot].
 * Оставлен для мест, где строк мало и ширина не в дефиците: надпись вроде
 * «Internet Archive» занимает около сотни dp, и в списке из сотен треков эта
 * ширина отнималась у названия и исполнителя.
 */
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
 * Точка-индикатор источника трека: цвет сервиса, 8dp.
 *
 * Заменяет [SourceChip] в строке списка. Чип с надписью полезен там, где строк
 * мало и ширина не в дефиците, а в списке из сотен треков он отнимал у названия
 * и исполнителя больше сотни dp. Цвет несёт ту же информацию, а название
 * сервиса доступно через `contentDescription` — оно же озвучивается
 * программой чтения с экрана.
 */
@Composable
fun SourceDot(source: Source, modifier: Modifier = Modifier) {
    val color = sourceColor(source)
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
            .semantics { contentDescription = "Источник: ${source.title}" },
    )
}

/**
 * Строка трека: обложка, название, исполнитель, сердечко, необязательный trailing-слот
 * и компактное меню «Ещё» (текст песни, скачивание — только если оно разрешено).
 * Долгое нажатие открывает лист действий (см. [TrackActionSheet]).
 *
 * Оценки:
 *  * [rating] `null` — экран не подключил оценки, строка выглядит как раньше;
 *  * `0` / `1` / `-1` — показать 👍/👎, подсветив активную оценку;
 *  * [onLike]/[onDislike] — что вызвать по тапу (и в меню «Ещё»).
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
    /**
     * Показывать источник трека точкой перед исполнителем.
     *
     * Раньше источник рисовался чипом с надписью в конце строки (`trailing`),
     * и надпись вроде «Internet Archive» съедала около сотни dp — ровно ту
     * ширину, которой не хватало названию и исполнителю. Точка цвета сервиса
     * занимает 8dp и не мешает тексту занять всю строку.
     */
    showSource: Boolean = false,
    onDownload: (() -> Unit)? = null,
    saveState: TrackSaveState? = null,
    rating: Int? = null,
    onLike: (() -> Unit)? = null,
    onDislike: (() -> Unit)? = null,
    /**
     * Удаление трека прямо из строки, через меню «три точки».
     *
     * Строка не знает, как именно удалять: файл с устройства и онлайн-трек
     * удаляются по-разному, и решает это экран. Здесь только пункт меню.
     */
    onDelete: (() -> Unit)? = null,
    /** Подпись пункта удаления: «Удалить с устройства» или «Удалить из медиатеки». */
    deleteLabel: String = "Удалить",
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
    // Мягкая подсветка играющего трека — 200 мс, без перерисовок на каждый тик.
    val rowBackground by animateColorAsState(
        targetValue = if (isPlaying) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 200),
        label = "track-row-background",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(rowBackground)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Плотная строка Spotify: обложка 56dp, крупное название, тишина вокруг.
        CoverArt(
            url = track.artworkUrl ?: track.artworkUri,
            size = 56.dp,
            cornerRadius = 8.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                // Ровно две строки и ВСЯ ширина строки: раньше чип источника стоял
                // справа и отнимал у названия и исполнителя до сотни dp, из-за чего
                // длинные названия обрезались, хотя места в строке было достаточно.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val saveFailed = saveState?.status == SaveStatus.ERROR
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Источник — маленькая точка перед исполнителем, а не чип справа.
                // Точка занимает 12dp вместо 100dp у чипа с надписью, поэтому
                // исполнитель и альбом укладываются в полную строку.
                if (showSource) {
                    SourceDot(source = track.source, modifier = Modifier.padding(end = 6.dp))
                }
                Text(
                    text = when {
                        saving -> saveState?.label ?: track.displaySubtitle
                        saveFailed -> "Ошибка сохранения — нажмите ↻"
                        else -> track.displaySubtitle
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        saving -> MaterialTheme.colorScheme.primary
                        saveFailed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            enabled = !saving && !saveFailed && track.artist.isNotBlank(),
                        ) {
                            // Как в Spotify/Metrolist: имя артиста ведёт на его страницу.
                            openArtist(track.artist)
                        },
                )
            }
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
        if (trailing != null) {
            trailing()
        }
        // Оценки, избранное, скачивание, удаление и текст — всё в «трёх точках».
        //
        // Раньше 👍, 👎 и ❤️ стояли прямо в строке: три круглые кнопки по 30–48dp
        // отнимали около половины ширины, название с исполнителем ужимались, а
        // сама строка вырастала. Оценки и избранное — действия редкие, им место
        // в меню. В строке остаётся то, что человек читает: обложка, название,
        // исполнитель, статус сохранения и одна кнопка меню.
        val hasMenuItems = hasLyrics || canDownload || trackRadio != null ||
            onDelete != null ||
            (rating != null && (onLike != null || onDislike != null))
        if (hasMenuItems) {
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
                    if (rating != null && onLike != null) {
                        DropdownMenuItem(
                            text = {
                                Text(if (rating > 0) "Убрать «нравится»" else "Нравится")
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.ThumbUp,
                                    contentDescription = null,
                                    tint = if (rating > 0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onLike?.invoke()
                            },
                        )
                    }
                    if (rating != null && onDislike != null) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (rating < 0) "Убрать «не нравится»" else "Не нравится",
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.ThumbDown,
                                    contentDescription = null,
                                    tint = if (rating < 0) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onDislike?.invoke()
                            },
                        )
                    }
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
                    // Избранное переехало из строки в меню: сердечко занимало
                    // 48dp постоянной ширины ради действия, которое нажимают
                    // редко, и отнимало место у названия.
                    DropdownMenuItem(
                        text = {
                            Text(if (isFavorite) "Убрать из избранного" else "В избранное")
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isFavorite) {
                                    Icons.Rounded.Favorite
                                } else {
                                    Icons.Rounded.FavoriteBorder
                                },
                                contentDescription = null,
                                tint = if (isFavorite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onFavorite()
                        },
                    )
                    if (onDelete != null) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onDelete.invoke()
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
    /** Оценка трека: null — экран не подключил 👍/👎, пункты не показываются. */
    rating: Int? = null,
    onLike: (() -> Unit)? = null,
    onDislike: (() -> Unit)? = null,
    /**
     * Удаление трека. `null` — экран не умеет удалять, пункт не показывается.
     *
     * Раньше удалять можно было только скачанное из стриминга
     * (`deleteDownload(fileName)`), а собственные mp3 с устройства — нет.
     */
    onDelete: (() -> Unit)? = null,
    /** Пояснение под пунктом удаления: что именно исчезнет. */
    deleteLabel: String = "Удалить",
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
                maxLines = 3,
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
            if (rating != null && onLike != null) {
                SheetAction(
                    icon = Icons.Rounded.ThumbUp,
                    label = if (rating > 0) "Убрать «нравится»" else "Нравится",
                    tint = if (rating > 0) MaterialTheme.colorScheme.primary else null,
                    onClick = {
                        onLike?.invoke()
                        onDismiss()
                    },
                )
            }
            if (rating != null && onDislike != null) {
                SheetAction(
                    icon = Icons.Rounded.ThumbDown,
                    label = if (rating < 0) "Убрать «не нравится»" else "Не нравится",
                    tint = if (rating < 0) MaterialTheme.colorScheme.error else null,
                    onClick = {
                        onDislike?.invoke()
                        onDismiss()
                    },
                )
            }
            if (onDownload != null) {
                SheetAction(
                    icon = Icons.Rounded.Download,
                    label = downloadLabel,
                    onClick = onDownload,
                )
            }
            if (onDelete != null) {
                // Красным и в самом низу: удаление файла необратимо, поэтому его
                // не ставят рядом с повседневными действиями вроде «В очередь».
                SheetAction(
                    icon = Icons.Rounded.Delete,
                    label = deleteLabel,
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
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

/** Строка действия в нижнем листе. [tint] — цвет активной иконки (оценки). */
@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color? = null,
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
            tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
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

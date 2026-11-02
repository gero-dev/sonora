package app.sonora.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sonora.core.model.Album
import app.sonora.core.model.Artist
import app.sonora.core.model.Playlist
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import coil.compose.AsyncImage

/**
 * Карточки медиатеки в духе Spotify: обложка 1:1, круглый Play поверх неё,
 * жирное название и приглушённый счётчик треков. Все карточки прозрачные —
 * их «фоном» служит плоская поверхность экрана.
 */

/**
 * Обложка плейлиста: коллаж из обложек первых треков (до 4), один постер
 * или акцентная градиентная заглушка с нотой, если обложек нет вообще.
 */
@Composable
fun PlaylistCover(
    coverUrls: List<String>,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
) {
    // Коллаж зависит только от списка обложек — фильтруем один раз на изменение.
    val urls = remember(coverUrls) {
        coverUrls.filter { it.isNotBlank() }.distinct().take(4)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        when (urls.size) {
            0 -> GradientCoverPlaceholder(Modifier.matchParentSize())
            1 -> CoverCell(urls[0], Modifier.matchParentSize())
            2 -> Row(Modifier.matchParentSize()) {
                CoverCell(urls[0], Modifier.weight(1f).fillMaxHeight())
                CoverCell(urls[1], Modifier.weight(1f).fillMaxHeight())
            }
            3 -> Row(Modifier.matchParentSize()) {
                CoverCell(urls[0], Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    CoverCell(urls[1], Modifier.weight(1f).fillMaxWidth())
                    CoverCell(urls[2], Modifier.weight(1f).fillMaxWidth())
                }
            }
            else -> Column(Modifier.matchParentSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    CoverCell(urls[0], Modifier.weight(1f).fillMaxHeight())
                    CoverCell(urls[1], Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    CoverCell(urls[2], Modifier.weight(1f).fillMaxHeight())
                    CoverCell(urls[3], Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun CoverCell(url: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

@Composable
private fun GradientCoverPlaceholder(modifier: Modifier = Modifier) {
    // Плоская заглушка вместо градиента: акцент только в ноте.
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = primary.copy(alpha = 0.85f),
            modifier = Modifier.fillMaxSize(0.30f),
        )
    }
}

/** Карточка плейлиста: обложка-коллаж, круглый Play и «N треков». */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistCard(
    playlist: Playlist,
    coverUrls: List<String>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subtitle = buildString {
        append(tracksLabel(playlist.trackIds.size))
        if (playlist.source != Source.LOCAL) {
            append(" · ")
            append(playlist.source.title)
        }
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
    ) {
        Box {
            PlaylistCover(
                coverUrls = coverUrls,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            CircularPlayButton(
                onClick = onPlay,
                contentDescription = "Играть плейлист",
                size = 48.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** Карточка альбома: квадратная обложка, Play и «исполнитель · год». */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
    ) {
        Box {
            CoverArtBox(
                url = album.artworkUrl ?: album.artworkUri,
                cornerRadius = 12.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            CircularPlayButton(
                onClick = onPlay,
                contentDescription = "Играть альбом",
                size = 48.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = album.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = listOfNotNull(album.artist.takeIf { it.isNotBlank() }, album.year?.toString())
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** Карточка исполнителя: круглый аватар, Play и число треков. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistCard(
    artist: Artist,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth()) {
            CoverArtBox(
                url = artist.artworkUrl ?: artist.artworkUri,
                cornerRadius = 500.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            CircularPlayButton(
                onClick = onPlay,
                contentDescription = "Играть исполнителя",
                size = 44.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = tracksLabel(artist.trackIds.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** Плитка «Создать плейлист»: та же сетка, что у карточек, с плюсом в круге. */
@Composable
fun CreatePlaylistTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Создать плейлист",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Новый список",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Компактная плитка быстрого доступа для «закреплённого» ряда медиатеки:
 * акцентная иконка в квадрате, название и подпись. Как плитки Liked Songs /
 * Downloads в верхней части библиотеки Spotify.
 */
@Composable
fun QuickAccessTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Строка плейлиста в режиме списка: коллаж, название, счётчик и меню «Ещё». */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistListRow(
    playlist: Playlist,
    coverUrls: List<String>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subtitle = buildString {
        append(tracksLabel(playlist.trackIds.size))
        if (playlist.source != Source.LOCAL) {
            append(" · ")
            append(playlist.source.title)
        }
        playlist.ownerName?.takeIf { it.isNotBlank() }?.let {
            append(" · ")
            append(it)
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistCover(
            coverUrls = coverUrls,
            cornerRadius = 8.dp,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OverflowButton(onClick = onMoreClick, description = "Действия с плейлистом")
    }
}

/** Строка альбома в режиме списка: обложка, название, исполнитель и год. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumListRow(
    album: Album,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArtBox(
            url = album.artworkUrl ?: album.artworkUri,
            cornerRadius = 8.dp,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = album.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    album.artist.takeIf { it.isNotBlank() },
                    album.year?.toString(),
                    tracksLabel(album.trackIds.size),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OverflowButton(onClick = onMoreClick, description = "Действия с альбомом")
    }
}

/** Строка исполнителя в режиме списка: круглый аватар, имя и число треков. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistListRow(
    artist: Artist,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArtBox(
            url = artist.artworkUrl ?: artist.artworkUri,
            cornerRadius = 500.dp,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = artist.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tracksLabel(artist.trackIds.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OverflowButton(onClick = onMoreClick, description = "Действия с исполнителем")
    }
}

/** Кнопка «Ещё» для строк списка — те же действия, что по долгому нажатию. */
@Composable
private fun OverflowButton(
    onClick: () -> Unit,
    description: String,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = Icons.Rounded.MoreVert,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Карточка трека для горизонтальных полок «Недавно прослушано» и
 * «Загруженные»: квадратная обложка, крупное название и исполнитель.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentTrackCard(
    track: Track,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
    ) {
        CoverArtBox(
            url = track.artworkUrl ?: track.artworkUri,
            cornerRadius = 10.dp,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Карточка «Любимые треки» — аналог Liked Songs в Spotify: акцентный
 * квадрат с сердечком, счётчик и круглая кнопка Play справа.
 */
@Composable
fun LikedSongsCard(
    count: Int,
    onPlay: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Любимые треки",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tracksLabel(count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CircularPlayButton(
            onClick = onPlay,
            size = 44.dp,
            contentDescription = "Играть любимые треки",
        )
    }
}

package app.sonora.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sonora.core.model.Playlist
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.util.Formatters
import app.sonora.data.metadata.ArtistNames
import app.sonora.playback.PlayerUiState
import app.sonora.ui.MainViewModel
import app.sonora.ui.components.BackIconButton
import app.sonora.ui.components.CoverArtBox
import app.sonora.ui.components.CreatePlaylistDialog
import app.sonora.ui.components.EmptyState
import app.sonora.ui.components.TrackActionSheet
import app.sonora.ui.components.TrackRow
import app.sonora.ui.nav.Routes

/**
 * Детальные экраны: плейлист, альбом и исполнитель.
 * Все три собираются из общих частей (шапка + список треков + лист действий).
 */
@Composable
fun PlaylistDetailScreen(vm: MainViewModel, playlistId: String, navigate: (String) -> Unit) {
    val playlists by vm.playlists.collectAsState()
    val tracks by vm.tracks.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val player by vm.player.collectAsState()

    val actions = remember { DetailActions() }
    val playlist = playlists.firstOrNull { it.id == playlistId }
    val playlistTracks = remember(playlist, tracks) { playlist?.let { vm.playlistTracks(it) } ?: emptyList() }
    val totalMs = remember(playlistTracks) { playlistTracks.sumOf { it.durationMs } }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        DetailBackRow()
        if (playlist == null) {
            EmptyState(
                icon = Icons.Rounded.PlaylistPlay,
                title = "Плейлист не найден",
                subtitle = "Возможно, он был удалён или ещё не синхронизирован с сервисом.",
            )
        } else {
            DetailTracks(
                favorites = favorites,
                player = player,
                tracks = playlistTracks,
                emptyTitle = "Плейлист пуст",
                emptySubtitle = "Добавляйте треки долгим нажатием в медиатеке или поиске.",
                onClick = { vm.playOrOpen(it) },
                onLongClick = { actions.track = it },
                onFavorite = { vm.toggleFavorite(it.id) },
                trailing = { track ->
                    if (playlist.editable) {
                        IconButton(onClick = { vm.removeFromPlaylist(playlist.id, track.id) }) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = "Убрать из плейлиста",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
            ) {
                PlaylistHeader(
                    playlist = playlist,
                    trackCount = playlistTracks.size,
                    totalMs = totalMs,
                    actionsEnabled = playlistTracks.isNotEmpty(),
                    onPlay = { vm.playQueue(playlistTracks) },
                    onShuffle = {
                        if (!player.shuffle) vm.toggleShuffle()
                        if (playlistTracks.isNotEmpty()) vm.playQueue(playlistTracks.shuffled())
                    },
                )
                if (playlist.source != Source.LOCAL && playlist.trackIds.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    SyncHintCard { navigate(Routes.CONNECTIONS) }
                }
            }
        }
    }
    DetailActionHost(vm = vm, actions = actions, playlists = playlists, favorites = favorites)
}

@Composable
fun AlbumDetailScreen(
    vm: MainViewModel,
    albumName: String,
    artist: String,
    navigate: (String) -> Unit,
) {
    val tracks by vm.tracks.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val player by vm.player.collectAsState()

    val actions = remember { DetailActions() }
    val albumTracks = remember(tracks, albumName, artist) {
        tracks.filter { it.album == albumName && ArtistNames.matches(it.artist, artist) }
    }
    val artwork = remember(albumTracks) {
        albumTracks.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl
            ?: albumTracks.firstOrNull { !it.artworkUri.isNullOrBlank() }?.artworkUri
    }
    val totalMs = remember(albumTracks) { albumTracks.sumOf { it.durationMs } }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        DetailBackRow()
        DetailTracks(
            favorites = favorites,
            player = player,
            tracks = albumTracks,
            emptyTitle = "Альбом пуст",
            emptySubtitle = "В этом альбоме нет доступных треков.",
            onClick = { vm.playOrOpen(it) },
            onLongClick = { actions.track = it },
            onFavorite = { vm.toggleFavorite(it.id) },
        ) {
            DetailCoverHeader(
                artwork = artwork,
                title = albumName,
                subtitle = artist,
                meta = if (albumTracks.isEmpty()) null else Formatters.tracksAndTime(albumTracks.size, totalMs),
                actionsEnabled = albumTracks.isNotEmpty(),
                onSubtitleClick = { navigate(Routes.artist(artist)) },
                onPlay = { vm.playQueue(albumTracks) },
                onShuffle = {
                    if (!player.shuffle) vm.toggleShuffle()
                    if (albumTracks.isNotEmpty()) vm.playQueue(albumTracks.shuffled())
                },
            )
        }
    }
    DetailActionHost(vm = vm, actions = actions, playlists = playlists, favorites = favorites)
}

@Composable
fun ArtistDetailScreen(vm: MainViewModel, artistName: String, navigate: (String) -> Unit) {
    val tracks by vm.tracks.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val player by vm.player.collectAsState()

    val actions = remember { DetailActions() }
    val artistTracks = remember(tracks, artistName) {
        tracks.filter { ArtistNames.matches(it.artist, artistName) }
    }
    val artwork = remember(artistTracks) {
        artistTracks.firstNotNullOfOrNull { it.extra["artistImageUri"]?.takeIf(String::isNotBlank) }
            ?: artistTracks.firstNotNullOfOrNull { it.extra["artistImageUrl"]?.takeIf(String::isNotBlank) }
            ?: artistTracks.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl
            ?: artistTracks.firstOrNull { !it.artworkUri.isNullOrBlank() }?.artworkUri
    }
    val bio = remember(artistTracks) {
        artistTracks.firstNotNullOfOrNull { it.extra["artistBio"]?.takeIf(String::isNotBlank) }
    }
    val genre = remember(artistTracks) {
        artistTracks.firstNotNullOfOrNull { it.extra["genre"]?.takeIf(String::isNotBlank) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        DetailBackRow()
        DetailTracks(
            favorites = favorites,
            player = player,
            tracks = artistTracks,
            emptyTitle = "Нет треков",
            emptySubtitle = "У этого исполнителя пока нет треков в библиотеке.",
            onClick = { vm.playOrOpen(it) },
            onLongClick = { actions.track = it },
            onFavorite = { vm.toggleFavorite(it.id) },
        ) {
            DetailCoverHeader(
                artwork = artwork,
                title = artistName,
                subtitle = "Исполнитель",
                meta = buildString {
                    append("Треков: ${artistTracks.size}")
                    if (!genre.isNullOrBlank()) append(" · $genre")
                },
                actionsEnabled = artistTracks.isNotEmpty(),
                round = true,
                onPlay = { vm.playQueue(artistTracks) },
                onShuffle = {
                    if (!player.shuffle) vm.toggleShuffle()
                    if (artistTracks.isNotEmpty()) vm.playQueue(artistTracks.shuffled())
                },
            )
            if (!bio.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    DetailActionHost(vm = vm, actions = actions, playlists = playlists, favorites = favorites)
}

/** Состояние листа действий над треком для детальных экранов. */
private class DetailActions {
    var track by mutableStateOf<Track?>(null)
    var createFor by mutableStateOf<Track?>(null)
}

/** Лист действий и диалог создания плейлиста, общие для трёх экранов. */
@Composable
private fun DetailActionHost(
    vm: MainViewModel,
    actions: DetailActions,
    playlists: List<Playlist>,
    favorites: Set<String>,
) {
    actions.track?.let { track ->
        TrackActionSheet(
            track = track,
            isFavorite = track.id in favorites,
            playlists = playlists,
            onDismiss = { actions.track = null },
            onPlay = {
                actions.track = null
                vm.playOrOpen(track)
            },
            onAddToQueue = {
                actions.track = null
                vm.addToQueue(track)
            },
            onPlayNext = {
                actions.track = null
                vm.playNext(track)
            },
            onAddToPlaylist = { playlist ->
                actions.track = null
                vm.addToPlaylist(playlist.id, track.id)
            },
            onCreatePlaylist = {
                actions.track = null
                actions.createFor = track
            },
            onToggleFavorite = {
                actions.track = null
                vm.toggleFavorite(track)
            },
            onDownload = if (track.playable && track.source != Source.LOCAL && !vm.isTrackDownloaded(track)) {
                {
                    actions.track = null
                    vm.downloadTrack(track)
                }
            } else {
                null
            },
            downloadLabel = "Скачать",
        )
    }
    actions.createFor?.let { track ->
        CreatePlaylistDialog(
            title = "Новый плейлист с треком",
            onDismiss = { actions.createFor = null },
            onCreate = { name ->
                actions.createFor = null
                vm.createPlaylist(name) { created -> vm.addToPlaylist(created.id, track.id) }
            },
        )
    }
}

/** Строка «назад» в шапке детального экрана. */
@Composable
private fun DetailBackRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackIconButton()
        Spacer(Modifier.width(4.dp))
        Text(
            text = "Назад",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Шапка плейлиста: обложка, название, кнопки. */
@Composable
private fun PlaylistHeader(
    playlist: Playlist,
    trackCount: Int,
    totalMs: Long,
    actionsEnabled: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CoverArtBox(
                url = playlist.artworkUrl,
                cornerRadius = 28.dp,
                modifier = Modifier
                    .width(180.dp)
                    .aspectRatio(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.headlineLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = playlist.ownerName
                ?: if (trackCount == 0) "Плейлист пуст" else Formatters.tracksAndTime(trackCount, totalMs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        HeaderActions(
            enabled = actionsEnabled,
            onPlay = onPlay,
            onShuffle = onShuffle,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** Шапка альбома / исполнителя. */
@Composable
private fun DetailCoverHeader(
    artwork: String?,
    title: String,
    subtitle: String,
    meta: String?,
    actionsEnabled: Boolean,
    onSubtitleClick: (() -> Unit)? = null,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    round: Boolean = false,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CoverArtBox(
                url = artwork,
                cornerRadius = if (round) 90.dp else 24.dp,
                modifier = Modifier
                    .width(if (round) 176.dp else 180.dp)
                    .aspectRatio(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (onSubtitleClick != null) {
                Modifier.clickable(onClick = onSubtitleClick)
            } else {
                Modifier
            },
        )
        if (meta != null) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        HeaderActions(
            enabled = actionsEnabled,
            onPlay = onPlay,
            onShuffle = onShuffle,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** Кнопки «Играть» и «Перемешать». */
@Composable
private fun HeaderActions(enabled: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onPlay, enabled = enabled) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Играть")
        }
        OutlinedButton(onClick = onShuffle, enabled = enabled) {
            Icon(
                imageVector = Icons.Rounded.Shuffle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Перемешать")
        }
    }
}

/** Подсказка синхронизировать удалённый плейлист. */
@Composable
private fun SyncHintCard(onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Sync,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Треки не синхронизированы", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Синхронизируйте аккаунт в разделе «Подключения», чтобы увидеть состав.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Список треков детального экрана с шапкой.
 * Пустой результат показывает [EmptyState].
 */
@Composable
private fun DetailTracks(
    favorites: Set<String>,
    player: PlayerUiState,
    tracks: List<Track>,
    emptyTitle: String,
    emptySubtitle: String,
    onClick: (Track) -> Unit,
    onLongClick: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (Track) -> Unit = {},
    header: @Composable () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 4.dp,
            bottom = 32.dp,
        ),
    ) {
        item { header() }
        if (tracks.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Album,
                    title = emptyTitle,
                    subtitle = emptySubtitle,
                )
            }
        } else {
            items(tracks, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    isFavorite = track.id in favorites,
                    isPlaying = player.current?.id == track.id && player.isPlaying,
                    onClick = { onClick(track) },
                    onLongClick = { onLongClick(track) },
                    onFavorite = { onFavorite(track) },
                    trailing = { trailing(track) },
                )
            }
        }
    }
}

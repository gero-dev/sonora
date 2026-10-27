package app.sonora.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sonora.core.model.Source
import app.sonora.core.util.Formatters
import app.sonora.ui.MainViewModel
import app.sonora.ui.components.CoverArtBox
import app.sonora.ui.components.EmptyState
import app.sonora.ui.components.TrackRow
import app.sonora.ui.nav.Routes

/**
 * Онлайн-альбом из дискографии: обложка, исполнитель, год и полный состав.
 * Треки сразу попадают в кэш библиотеки, поэтому их можно играть, сохранять
 * и добавлять в плейлисты как обычные композиции.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteAlbumScreen(
    vm: MainViewModel,
    albumId: String,
    albumTitle: String,
    artist: String,
    navigate: (String) -> Unit,
) {
    val state by vm.remoteAlbum.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val player by vm.player.collectAsState()
    val saveStates by vm.saveStates.collectAsState()

    LaunchedEffect(albumId) { vm.openRemoteAlbum(albumId, albumTitle, artist) }

    val actions = remember { DetailActions() }
    val applicable = state.id == albumId
    val title = if (applicable && state.title.isNotBlank()) state.title else albumTitle
    val artistName = if (applicable && state.artist.isNotBlank()) state.artist else artist
    val tracks = if (applicable) state.tracks else emptyList()
    val loading = !applicable || state.loading
    val error = if (applicable) state.error else null
    val artwork = if (applicable) {
        state.artworkUrl ?: tracks.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl
    } else {
        null
    }
    val totalMs = remember(tracks) { tracks.sumOf { it.durationMs } }
    val streamingTracks = remember(tracks) {
        tracks.filter { it.source != Source.LOCAL && it.playable }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { DetailBackRow() }

        item {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CoverArtBox(
                        url = artwork,
                        cornerRadius = 24.dp,
                        modifier = Modifier
                            .width(180.dp)
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
                Text(
                    text = artistName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable(enabled = artistName.isNotBlank()) {
                            navigate(Routes.artist(artistName))
                        }
                        .padding(vertical = 2.dp),
                )
                val meta = buildString {
                    if (applicable && state.year != null) append("${state.year} · ")
                    append("Альбом")
                    if (tracks.isNotEmpty()) {
                        append(" · ")
                        append(Formatters.tracksAndTime(tracks.size, totalMs))
                    }
                }
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { vm.playQueue(tracks) },
                        enabled = tracks.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Играть")
                    }
                    OutlinedButton(
                        onClick = {
                            if (!player.shuffle) vm.toggleShuffle()
                            if (tracks.isNotEmpty()) vm.playQueue(tracks.shuffled())
                        },
                        enabled = tracks.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Перемешать")
                    }
                    OutlinedButton(
                        onClick = { vm.downloadTracks(streamingTracks) },
                        enabled = streamingTracks.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Скачать")
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        if (loading) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }

        if (!loading && tracks.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.PlayArrow,
                    title = "Альбом недоступен",
                    subtitle = error ?: "Не удалось получить состав альбома.",
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { vm.openRemoteAlbum(albumId, albumTitle, artist) }) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Повторить")
                    }
                }
            }
        }

        items(tracks, key = { it.id }) { track ->
            val canDownload = track.source != Source.LOCAL &&
                track.playable &&
                !vm.isTrackDownloaded(track)
            TrackRow(
                track = track,
                isFavorite = track.id in favorites,
                isPlaying = player.current?.id == track.id && player.isPlaying,
                onClick = { vm.playOrOpen(track) },
                onLongClick = { actions.track = track },
                onFavorite = { vm.toggleFavorite(track) },
                saveState = saveStates[track.id],
                onDownload = if (canDownload) ({ vm.downloadTrack(track) }) else null,
            )
        }
    }

    DetailActionHost(
        vm = vm,
        actions = actions,
        playlists = playlists,
        favorites = favorites,
        navigate = navigate,
    )
}

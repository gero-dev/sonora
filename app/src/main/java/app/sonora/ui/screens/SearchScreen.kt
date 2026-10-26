package app.sonora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.ui.MainViewModel
import app.sonora.ui.components.CreatePlaylistDialog
import app.sonora.ui.components.EmptyState
import app.sonora.ui.components.SectionHeader
import app.sonora.ui.components.SourceChip
import app.sonora.ui.components.TrackActionSheet
import app.sonora.ui.components.TrackRow
import app.sonora.ui.components.sourceColor

/** Подписи фильтров: порядок совпадает с индексами в фильтре. */
private val SEARCH_FILTERS = listOf("Все", "Устройство", "YouTube Music")

private const val FILTER_ALL = 0
private const val FILTER_LOCAL = 1
private const val FILTER_YOUTUBE = 2

private val PagePadding = 16.dp

/**
 * Поиск по локальной фонотеке и YouTube Music.
 *
 * YouTube-часть работает через InnerTube: результаты сразу играбельны,
 * поэтому вместо «откроется в сервисе» показывается обычная кнопка play.
 * Логика и колбэки не менялись — только отступы, поле поиска и типографика.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: MainViewModel, padding: PaddingValues, navigate: (String) -> Unit) {
    val search by vm.search.collectAsState()
    val tracks by vm.tracks.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val player by vm.player.collectAsState()

    var filterIndex by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var actionTrack by remember { mutableStateOf<Track?>(null) }
    var playlistForNewTrack by remember { mutableStateOf<Track?>(null) }

    val localTracks = remember(tracks) { tracks.filter { it.source == Source.LOCAL } }
    val recentPreview = remember(localTracks) { localTracks.take(8) }

    val queryBlank = search.query.isBlank()
    val showLocal = filterIndex == FILTER_ALL || filterIndex == FILTER_LOCAL
    val showYouTube = filterIndex == FILTER_ALL || filterIndex == FILTER_YOUTUBE
    val localResults = if (showLocal) search.local else emptyList()
    val remoteResults = if (showYouTube) search.remote else emptyList()
    val nothingFound = !queryBlank && !search.loading &&
        localResults.isEmpty() && remoteResults.isEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = padding.calculateTopPadding()),
    ) {
        Column(Modifier.padding(horizontal = PagePadding)) {
            Spacer(Modifier.height(8.dp))
            Text("Поиск", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = search.query,
                onValueChange = vm::onSearchQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = { Text("Треки, исполнители, альбомы") },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (search.query.isNotEmpty()) {
                        IconButton(onClick = { vm.clearSearch() }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Очистить")
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = PagePadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SEARCH_FILTERS.size) { index ->
                FilterChip(
                    selected = filterIndex == index,
                    onClick = { filterIndex = index },
                    label = { Text(SEARCH_FILTERS[index]) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = PagePadding,
                end = PagePadding,
                top = 2.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (search.loading) {
                item { SearchLoadingRow() }
            }

            val error = search.error
            if (!error.isNullOrBlank() && !queryBlank) {
                item {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            if (queryBlank) {
                item { SectionHeader(title = "На устройстве") }
                if (recentPreview.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.MusicNote,
                            title = "Библиотека пуста",
                            subtitle = "Просканируйте устройство на Главной — и треки появятся здесь.",
                        )
                    }
                } else {
                    items(recentPreview, key = { "preview-${it.id}" }) { track ->
                        SearchTrackRow(
                            track = track,
                            isFavorite = track.id in favorites,
                            isPlaying = player.current?.id == track.id && player.isPlaying,
                            onClick = { vm.playOrOpen(track) },
                            onLongClick = { actionTrack = track },
                            onFavorite = { vm.toggleFavorite(track.id) },
                        )
                    }
                }
            } else {
                if (nothingFound) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.Search,
                            title = "Ничего не нашлось",
                            subtitle = "Попробуйте другой запрос или снимите фильтр.",
                        )
                    }
                }
                if (localResults.isNotEmpty()) {
                    item { SectionHeader(title = "На устройстве · ${localResults.size}") }
                    items(localResults, key = { "local-${it.id}" }) { track ->
                        SearchTrackRow(
                            track = track,
                            isFavorite = track.id in favorites,
                            isPlaying = player.current?.id == track.id && player.isPlaying,
                            onClick = { vm.playOrOpen(track) },
                            onLongClick = { actionTrack = track },
                            onFavorite = { vm.toggleFavorite(track.id) },
                        )
                    }
                }
                if (remoteResults.isNotEmpty()) {
                    item { SectionHeader(title = "YouTube Music · InnerTube · ${remoteResults.size}") }
                    items(remoteResults, key = { "remote-${it.id}" }) { track ->
                        SearchTrackRow(
                            track = track,
                            isFavorite = track.id in favorites,
                            isPlaying = player.current?.id == track.id && player.isPlaying,
                            onClick = { vm.playOrOpen(track) },
                            onLongClick = { actionTrack = track },
                            onFavorite = { vm.toggleFavorite(track.id) },
                            showSource = true,
                        )
                    }
                }
            }
        }
    }

    actionTrack?.let { track ->
        TrackActionSheet(
            track = track,
            isFavorite = track.id in favorites,
            playlists = playlists,
            onDismiss = { actionTrack = null },
            onPlay = {
                actionTrack = null
                vm.playOrOpen(track)
            },
            onAddToQueue = {
                actionTrack = null
                vm.addToQueue(track)
            },
            onPlayNext = {
                actionTrack = null
                vm.playNext(track)
            },
            onAddToPlaylist = { playlist ->
                actionTrack = null
                vm.addToPlaylist(playlist.id, track.id)
            },
            onCreatePlaylist = {
                actionTrack = null
                playlistForNewTrack = track
            },
            onToggleFavorite = {
                actionTrack = null
                vm.toggleFavorite(track)
            },
            onDownload = if (track.playable && track.source != Source.LOCAL && !vm.isTrackDownloaded(track)) {
                {
                    actionTrack = null
                    vm.downloadTrack(track)
                }
            } else {
                null
            },
            downloadLabel = "Скачать",
        )
    }

    playlistForNewTrack?.let { track ->
        CreatePlaylistDialog(
            title = "Новый плейлист с треком",
            onDismiss = { playlistForNewTrack = null },
            onCreate = { name ->
                playlistForNewTrack = null
                vm.createPlaylist(name) { created -> vm.addToPlaylist(created.id, track.id) }
            },
        )
    }
}

/** Строка результата поиска с чипом источника. */
@Composable
private fun SearchTrackRow(
    track: Track,
    isFavorite: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavorite: () -> Unit,
    showSource: Boolean = false,
) {
    TrackRow(
        track = track,
        isFavorite = isFavorite,
        isPlaying = isPlaying,
        onClick = onClick,
        onLongClick = onLongClick,
        onFavorite = onFavorite,
        trailing = {
            if (showSource) {
                SourceChip(
                    label = track.source.title,
                    color = sourceColor(track.source),
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        },
    )
}

/** Индикатор поиска. */
@Composable
private fun SearchLoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Ищем на устройстве и в YouTube Music…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

package app.sonora.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sonora.core.model.ArtistRelease
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.data.download.TrackSaveState
import app.sonora.data.metadata.ArtistNames
import app.sonora.ui.MainViewModel
import app.sonora.ui.components.CoverArtBox
import app.sonora.ui.components.EmptyState
import app.sonora.ui.components.SectionHeader
import app.sonora.ui.components.TrackRow
import app.sonora.ui.nav.Routes

/**
 * Страница исполнителя как в Spotify/Metrolist: фото, биография, популярные
 * треки и вся дискография — альбомы, синглы и EP.
 *
 * Онлайн-часть приходит из InnerTube (поиск с фильтром «Альбомы»), локальная —
 * из файлов устройства. Если сети нет, страница остаётся рабочей: показываются
 * альбомы и треки из медиатеки.
 */
@Composable
fun ArtistDetailScreen(vm: MainViewModel, artistName: String, navigate: (String) -> Unit) {
    val tracks by vm.tracks.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val player by vm.player.collectAsState()
    val artistState by vm.artist.collectAsState()
    val saveStates by vm.saveStates.collectAsState()

    LaunchedEffect(artistName) { vm.openArtist(artistName) }

    val actions = remember { DetailActions() }
    val visible = remember(tracks) { vm.withoutSavedCopies(tracks) }
    val localTracks = remember(visible, artistName) {
        visible.filter { ArtistNames.matches(it.artist, artistName) }
    }
    val stateMatchesArtist = artistState.name == artistName
    val remoteTop = remember(artistState, stateMatchesArtist) {
        if (stateMatchesArtist) artistState.topTracks else emptyList()
    }
    val albums = remember(artistState, stateMatchesArtist) {
        if (stateMatchesArtist) artistState.albums else emptyList()
    }

    // Всё, что знаем об исполнителе: сначала локальные файлы, затем онлайн-топ.
    val allTracks = remember(localTracks, remoteTop) {
        val merged = LinkedHashMap<String, Track>()
        localTracks.forEach { merged[it.id] = it }
        remoteTop.forEach { merged.putIfAbsent(it.id, it) }
        merged.values.toList()
    }
    val topTracks = remember(remoteTop, localTracks) {
        remoteTop.ifEmpty { localTracks }.take(TOP_TRACKS_LIMIT)
    }
    val topIds = remember(topTracks) { topTracks.map { it.id }.toSet() }
    val restTracks = remember(allTracks, topIds) {
        allTracks.filterNot { it.id in topIds }
    }

    val artwork = remember(localTracks, albums) {
        localTracks.firstNotNullOfOrNull { it.extra["artistImageUri"]?.takeIf(String::isNotBlank) }
            ?: localTracks.firstNotNullOfOrNull { it.extra["artistImageUrl"]?.takeIf(String::isNotBlank) }
            ?: albums.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl
            ?: albums.firstOrNull { !it.artworkUri.isNullOrBlank() }?.artworkUri
            ?: localTracks.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl
            ?: localTracks.firstOrNull { !it.artworkUri.isNullOrBlank() }?.artworkUri
    }
    val bio = remember(localTracks) {
        localTracks.firstNotNullOfOrNull { it.extra["artistBio"]?.takeIf(String::isNotBlank) }
    }
    val genre = remember(localTracks) {
        localTracks.firstNotNullOfOrNull { it.extra["genre"]?.takeIf(String::isNotBlank) }
    }

    val loading = stateMatchesArtist && artistState.loading
    val error = if (stateMatchesArtist) artistState.error else null
    val playable = allTracks.filter { it.playable }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { DetailBackRow() }

        item {
            ArtistHeader(
                name = artistName,
                artwork = artwork,
                subtitle = "Исполнитель",
                meta = buildString {
                    append("Треков: ${allTracks.size}")
                    if (albums.isNotEmpty()) append(" · релизов: ${albums.size}")
                    if (!genre.isNullOrBlank()) append(" · $genre")
                },
                enabled = playable.isNotEmpty(),
                onPlay = { vm.playQueue(playable) },
                onShuffle = {
                    if (!player.shuffle) vm.toggleShuffle()
                    if (playable.isNotEmpty()) vm.playQueue(playable.shuffled())
                },
            )
        }

        if (!bio.isNullOrBlank()) {
            item {
                Text(
                    text = bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
            }
        }

        if (loading) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Загружаем дискографию…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (topTracks.isNotEmpty()) {
            item { SectionHeader(title = "Популярные треки") }
            items(topTracks, key = { "top:${it.id}" }) { track ->
                ArtistTrackRow(
                    vm = vm,
                    track = track,
                    favorites = favorites,
                    player = player,
                    saveStates = saveStates,
                    onClick = { vm.playOrOpen(track) },
                    onLongClick = { actions.track = track },
                    onFavorite = { vm.toggleFavorite(it) },
                )
            }
        }

        if (albums.isNotEmpty()) {
            item {
                SectionHeader(title = "Дискография · ${albums.size}")
            }
            items(albums.chunked(2), key = { row -> "row:${row.joinToString("|") { it.id }}" }) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { release ->
                        ReleaseCard(
                            release = release,
                            modifier = Modifier.weight(1f),
                            onClick = { openRelease(release, artistName, navigate) },
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (restTracks.isNotEmpty()) {
            item { SectionHeader(title = "Все треки") }
            items(restTracks, key = { "all:${it.id}" }) { track ->
                ArtistTrackRow(
                    vm = vm,
                    track = track,
                    favorites = favorites,
                    player = player,
                    saveStates = saveStates,
                    onClick = { vm.playOrOpen(track) },
                    onLongClick = { actions.track = track },
                    onFavorite = { vm.toggleFavorite(it) },
                )
            }
        }

        if (!loading && allTracks.isEmpty() && albums.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.PlayArrow,
                    title = "Ничего не найдено",
                    subtitle = if (error.isNullOrBlank()) {
                        "У этого исполнителя пока нет треков в библиотеке."
                    } else {
                        "Не удалось загрузить дискографию: $error"
                    },
                )
                if (!error.isNullOrBlank()) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { vm.reloadArtist() }) {
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

/** Открывает релиз: онлайн — сетевой экран, локальный — альбом из медиатеки. */
private fun openRelease(release: ArtistRelease, artistName: String, navigate: (String) -> Unit) {
    if (release.remote && release.id.isNotBlank()) {
        // В карточках со страницы артиста исполнитель иногда не указан —
        // подставляем имя со страницы, иначе маршрут останется с пустым сегментом.
        val artist = release.artist.ifBlank { artistName }
        navigate(Routes.remoteAlbum(release.id, release.title, artist))
    } else {
        navigate(Routes.album(release.title, release.artist.ifBlank { artistName }))
    }
}

@Composable
private fun ArtistHeader(
    name: String,
    artwork: String?,
    subtitle: String,
    meta: String,
    enabled: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CoverArtBox(
                url = artwork,
                cornerRadius = 90.dp,
                modifier = Modifier
                    .width(176.dp)
                    .aspectRatio(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.headlineLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = meta,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
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
        Spacer(Modifier.height(8.dp))
    }
}

/** Карточка релиза в сетке дискографии. */
@Composable
private fun ReleaseCard(
    release: ArtistRelease,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(bottom = 6.dp),
    ) {
        CoverArtBox(
            url = release.artworkUrl ?: release.artworkUri,
            cornerRadius = 16.dp,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = release.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val details = listOfNotNull(
            release.year?.toString(),
            release.type?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (details.isNotBlank()) {
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Строка трека на странице артиста с сохранением и прогрессом загрузки. */
@Composable
private fun ArtistTrackRow(
    vm: MainViewModel,
    track: Track,
    favorites: Set<String>,
    player: app.sonora.playback.PlayerUiState,
    saveStates: Map<String, TrackSaveState>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavorite: (Track) -> Unit,
) {
    val canDownload = track.source != Source.LOCAL && track.playable && !vm.isTrackDownloaded(track)
    TrackRow(
        track = track,
        isFavorite = track.id in favorites,
        isPlaying = player.current?.id == track.id && player.isPlaying,
        onClick = onClick,
        onLongClick = onLongClick,
        onFavorite = { onFavorite(track) },
        saveState = saveStates[track.id],
        onDownload = if (canDownload) ({ vm.downloadTrack(track) }) else null,
    )
}

private const val TOP_TRACKS_LIMIT = 5

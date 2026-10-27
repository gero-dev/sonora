package app.sonora.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sonora.core.model.Album
import app.sonora.core.model.Artist
import app.sonora.core.model.Playlist
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.data.download.TrackSaveState
import app.sonora.playback.PlayerUiState
import app.sonora.ui.MainViewModel
import app.sonora.ui.components.CoverArt
import app.sonora.ui.components.CoverArtBox
import app.sonora.ui.components.CreatePlaylistDialog
import app.sonora.ui.components.EmptyState
import app.sonora.ui.components.SourceChip
import app.sonora.ui.components.TrackActionSheet
import app.sonora.ui.components.TrackRow
import app.sonora.ui.components.sourceColor
import app.sonora.ui.nav.Routes
import kotlin.math.roundToInt

private val LIBRARY_TABS =
    listOf("Треки", "Альбомы", "Артисты", "Плейлисты", "Избранное", "YouTube")

/**
 * Медиатека: треки, альбомы, исполнители, плейлисты и избранное.
 * Долгое нажатие на трек открывает общий лист действий.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: MainViewModel, padding: PaddingValues, navigate: (String) -> Unit) {
    val tracks by vm.tracks.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val player by vm.player.collectAsState()
    val scanning by vm.scanning.collectAsState()

    var tabIndex by rememberSaveable { mutableStateOf(0) }
    var sortByArtist by rememberSaveable { mutableStateOf(false) }
    var onlySaved by rememberSaveable { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showYouTubeImport by remember { mutableStateOf(false) }
    var actionTrack by remember { mutableStateOf<Track?>(null) }
    var playlistForNewTrack by remember { mutableStateOf<Track?>(null) }

    val localTracks = remember(tracks) { tracks.filter { it.source == Source.LOCAL } }
    val savedFromStreaming = remember(localTracks) { localTracks.count { it.savedFromStreaming } }
    val sortedTracks = remember(localTracks, sortByArtist, onlySaved) {
        val filtered = if (onlySaved) localTracks.filter { it.savedFromStreaming } else localTracks
        if (sortByArtist) {
            filtered.sortedBy { it.artist.lowercase() }
        } else {
            filtered.sortedBy { it.title.lowercase() }
        }
    }
    val albums = remember(localTracks) { vm.albums() }
    val artists = remember(localTracks) { vm.artists() }
    val favoriteTracks = remember(tracks, favorites) { tracks.filter { it.id in favorites } }
    val importedTracks = remember(tracks) {
        vm.withoutSavedCopies(
            tracks.filter { it.source == Source.YOUTUBE && !it.isVideoContent },
        )
    }
    val bottomPadding = padding.calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Медиатека", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        text = buildString {
                            append("На устройстве: ${localTracks.size}")
                            if (savedFromStreaming > 0) {
                                append(" · сохранено из стриминга: $savedFromStreaming")
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LibraryTabBar(
                tabs = LIBRARY_TABS,
                selectedIndex = tabIndex,
                onSelect = { tabIndex = it },
            )
            Spacer(Modifier.height(6.dp))
            when (tabIndex) {
                0 -> TracksTab(
                    tracks = sortedTracks,
                    favorites = favorites,
                    player = player,
                    sortByArtist = sortByArtist,
                    onlySaved = onlySaved,
                    scanning = scanning,
                    onToggleSort = { sortByArtist = !sortByArtist },
                    onToggleSaved = { onlySaved = !onlySaved },
                    onScan = { vm.scanLibrary() },
                    onClick = { vm.playOrOpen(it) },
                    onLongClick = { actionTrack = it },
                    onFavorite = { vm.toggleFavorite(it.id) },
                    bottomPadding = bottomPadding,
                )
                1 -> AlbumsTab(albums = albums, navigate = navigate, bottomPadding = bottomPadding)
                2 -> ArtistsTab(artists = artists, navigate = navigate, bottomPadding = bottomPadding)
                3 -> PlaylistsTab(
                    playlists = playlists,
                    navigate = navigate,
                    bottomPadding = bottomPadding,
                    onCreatePlaylist = { showCreateDialog = true },
                )
                5 -> YouTubeTab(
                    tracks = importedTracks,
                    favorites = favorites,
                    player = player,
                    onClick = { vm.playOrOpen(it) },
                    onLongClick = { actionTrack = it },
                    onFavorite = { vm.toggleFavorite(it.id) },
                    onImport = { navigate(Routes.SETTINGS) },
                    onImportLink = { showYouTubeImport = true },
                    importError = vm.playlistImportError.collectAsState().value,
                    saveStates = vm.saveStates.collectAsState().value,
                    bottomPadding = bottomPadding,
                )
                else -> FavoritesTab(
                    tracks = favoriteTracks,
                    favorites = favorites,
                    player = player,
                    onPlayAll = { vm.playQueue(favoriteTracks) },
                    onClick = { vm.playOrOpen(it) },
                    onLongClick = { actionTrack = it },
                    onFavorite = { vm.toggleFavorite(it.id) },
                    bottomPadding = bottomPadding,
                )
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
                downloadLabel = "Сохранить на устройство",
            )
        }

        if (showCreateDialog) {
            CreatePlaylistDialog(
                onDismiss = { showCreateDialog = false },
                onCreate = { name ->
                    showCreateDialog = false
                    vm.createPlaylist(name) { created -> navigate(Routes.playlist(created.id)) }
                },
            )
        }

        if (showYouTubeImport) {
            YouTubePlaylistDialog(
                onDismiss = { showYouTubeImport = false },
                onImport = { url ->
                    showYouTubeImport = false
                    vm.importYouTubePlaylist(url)
                },
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
}

/** Вкладка «Треки»: счётчик, сортировка, пересканирование и список. */
@Composable
private fun TracksTab(
    tracks: List<Track>,
    favorites: Set<String>,
    player: PlayerUiState,
    sortByArtist: Boolean,
    onlySaved: Boolean,
    scanning: Boolean,
    onToggleSort: () -> Unit,
    onToggleSaved: () -> Unit,
    onScan: () -> Unit,
    onClick: (Track) -> Unit,
    onLongClick: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    bottomPadding: Dp,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = bottomPadding + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${tracks.size} трек(ов)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggleSaved) {
                    Icon(
                        imageVector = Icons.Rounded.DownloadDone,
                        contentDescription = if (onlySaved) {
                            "Показать все треки"
                        } else {
                            "Только сохранённые из стриминга"
                        },
                        tint = if (onlySaved) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                FilterChip(
                    selected = sortByArtist,
                    onClick = onToggleSort,
                    label = { Text(if (sortByArtist) "По исполнителю" else "По названию") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (sortByArtist) Icons.Rounded.Person else Icons.Rounded.Sort,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
                IconButton(onClick = onScan) {
                    if (scanning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Сканировать фонотеку")
                    }
                }
            }
        }
        if (tracks.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.LibraryMusic,
                    title = if (onlySaved) "Нет сохранённых треков" else "Треков нет",
                    subtitle = if (onlySaved) {
                        "Нажмите кнопку загрузки у онлайн-трека — он сохранится сюда, " +
                            "рядом с музыкой на устройстве."
                    } else {
                        "Нажмите «Сканировать» — найдём аудиофайлы на устройстве."
                    },
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
                )
            }
        }
    }
}

/** Вкладка «Альбомы»: сетка обложек. */
@Composable
private fun AlbumsTab(albums: List<Album>, navigate: (String) -> Unit, bottomPadding: Dp) {
    if (albums.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.Album,
            title = "Альбомов нет",
            subtitle = "Альбомы собираются из тегов локальных файлов.",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = bottomPadding + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        gridItems(albums, key = { "${it.name}|${it.artist}" }) { album ->
            Column(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { navigate(Routes.album(album.name, album.artist)) }
                    .padding(4.dp),
            ) {
                CoverArtBox(
                    url = album.artworkUrl ?: album.artworkUri,
                    cornerRadius = 14.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                Text(
                    text = album.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** Вкладка «Исполнители»: круглые аватары. */
@Composable
private fun ArtistsTab(artists: List<Artist>, navigate: (String) -> Unit, bottomPadding: Dp) {
    if (artists.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.Person,
            title = "Исполнителей нет",
            subtitle = "Исполнители берутся из тегов локальных файлов.",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = bottomPadding + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(artists, key = { it.name }) { artist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { navigate(Routes.artist(artist.name)) }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverArt(
                    url = artist.artworkUrl ?: artist.artworkUri,
                    size = 52.dp,
                    cornerRadius = 26.dp,
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
                        text = "${artist.trackIds.size} трек(ов)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Вкладка «Плейлисты»: свои и синхронизированные из сервисов. */
@Composable
private fun PlaylistsTab(
    playlists: List<Playlist>,
    navigate: (String) -> Unit,
    bottomPadding: Dp,
    onCreatePlaylist: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = bottomPadding + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Button(
                onClick = onCreatePlaylist,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Создать плейлист")
            }
        }
        if (playlists.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.PlaylistPlay,
                    title = "Плейлистов нет",
                    subtitle = "Создайте свой или синхронизируйте аккаунт в разделе «Подключения».",
                )
            }
        } else {
            items(playlists, key = { it.id }) { playlist ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { navigate(Routes.playlist(playlist.id)) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverArt(
                        url = playlist.artworkUrl,
                        size = 52.dp,
                        cornerRadius = 14.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${playlist.trackIds.size} трек(ов)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (playlist.source != Source.LOCAL) {
                                Spacer(Modifier.width(8.dp))
                                SourceChip(
                                    label = playlist.source.title,
                                    color = sourceColor(playlist.source),
                                )
                            }
                        }
                    }
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Вкладка «Избранное»: список сердечком отмеченных треков. */
@Composable
private fun FavoritesTab(
    tracks: List<Track>,
    favorites: Set<String>,
    player: PlayerUiState,
    onPlayAll: () -> Unit,
    onClick: (Track) -> Unit,
    onLongClick: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    bottomPadding: Dp,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = bottomPadding + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${tracks.size} трек(ов)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onPlayAll, enabled = tracks.isNotEmpty()) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Играть все")
                }
            }
        }
        if (tracks.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.FavoriteBorder,
                    title = "Пока пусто",
                    subtitle = "Отмечайте треки сердечком — они появятся здесь.",
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
                )
            }
        }
    }
}

/** Вкладка YouTube Music: треки из InnerTube/Takeout играются внутри приложения. */
@Composable
private fun YouTubeTab(
    tracks: List<Track>,
    favorites: Set<String>,
    player: PlayerUiState,
    onClick: (Track) -> Unit,
    onLongClick: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    onImport: () -> Unit,
    onImportLink: () -> Unit,
    importError: String?,
    saveStates: Map<String, TrackSaveState>,
    bottomPadding: Dp,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = bottomPadding + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${tracks.size} трек(ов)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onImportLink) {
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("По ссылке")
                }
                TextButton(onClick = onImport) { Text("Takeout") }
            }
            if (!importError.isNullOrBlank()) {
                Text(
                    text = importError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        if (tracks.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.PlayCircle,
                    title = "Библиотека YouTube Music пуста",
                    subtitle = "Найдите треки в поиске (InnerTube), импортируйте плейлист по ссылке " +
                        "или CSV/ZIP из Google Takeout. Все треки играются внутри Sonora.",
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
                    saveState = saveStates[track.id],
                )
            }
        }
    }
}

private val TabHeight = 36.dp

/**
 * Сегментированные вкладки медиатеки: скроллируемая капсула с анимированным
 * индикатором, который «переезжает» под выбранную вкладку. Набор вкладок и
 * индексы те же, что были у ScrollableTabRow, поэтому вся логика экрана цела.
 */
@Composable
private fun LibraryTabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    // Позиции вкладок запоминаем после первого layout: индикатор должен ехать и
    // тогда, когда выбранная вкладка меняется без повторного layout.
    val tabBounds = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }
    val selectedBounds = tabBounds[selectedIndex]

    val indicatorLeft by animateDpAsState(
        targetValue = with(density) { (selectedBounds?.first ?: 0).toDp() },
        animationSpec = tween(durationMillis = 220),
    )
    val indicatorWidth by animateDpAsState(
        targetValue = with(density) { (selectedBounds?.second ?: 0).toDp() },
        animationSpec = tween(durationMillis = 220),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
    ) {
        Box(modifier = Modifier.horizontalScroll(scrollState)) {
            Box {
                // Индикатор рисуется первым — подписи вкладок лежат поверх него.
                Box(
                    modifier = Modifier
                        .offset(x = indicatorLeft)
                        .width(indicatorWidth)
                        .height(TabHeight)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                )
                Row {
                    tabs.forEachIndexed { index, title ->
                        val selected = index == selectedIndex
                        Box(
                            modifier = Modifier
                                .onGloballyPositioned { coordinates ->
                                    val bounds = coordinates.positionInParent().x.roundToInt() to
                                        coordinates.size.width
                                    if (tabBounds[index] != bounds) {
                                        tabBounds[index] = bounds
                                    }
                                }
                                .height(TabHeight)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(index) }
                                .padding(horizontal = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                ),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Диалог импорта публичного плейлиста YouTube Music по ссылке или id. */
@Composable
private fun YouTubePlaylistDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Плейлист YouTube Music") },
        text = {
            Column {
                Text(
                    text = "Вставьте ссылку на публичный плейлист (music.youtube.com/playlist?list=…) " +
                        "или его id: PL…, OLAK5uy_….",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("Ссылка или id") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (url.isNotBlank()) onImport(url.trim()) },
                enabled = url.isNotBlank(),
            ) { Text("Импортировать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

package app.sonora.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.sonora.ui.components.AlbumCard
import app.sonora.ui.components.ArtistCard
import app.sonora.ui.components.CircularPlayButton
import app.sonora.ui.components.CreatePlaylistDialog
import app.sonora.ui.components.CreatePlaylistTile
import app.sonora.ui.components.EmptyState
import app.sonora.ui.components.PlaylistCard
import app.sonora.ui.components.SectionHeader
import app.sonora.ui.components.TrackActionSheet
import app.sonora.ui.components.TrackRow
import app.sonora.ui.components.tracksLabel
import app.sonora.ui.nav.Routes
import kotlinx.coroutines.flow.StateFlow

/**
 * Разделы медиатеки. Порядок чипов — как в Spotify: «Все», затем плейлисты,
 * альбомы, исполнители, треки, избранное и YouTube.
 *
 * [tabIndex] сохраняет прежнюю нумерацию вкладок экрана (0 — Треки, 1 — Альбомы,
 * 2 — Исполнители, 3 — Плейлисты, 4 — Избранное, 5 — YouTube), поэтому вся
 * логика и навигация остались теми же — изменилась только подача.
 */
private enum class LibrarySection(val label: String, val tabIndex: Int) {
    ALL("Все", 0),
    PLAYLISTS("Плейлисты", 3),
    ALBUMS("Альбомы", 1),
    ARTISTS("Исполнители", 2),
    TRACKS("Треки", 0),
    FAVORITES("Избранное", 4),
    YOUTUBE("YouTube", 5),
}

/** Сортировка треков в медиатеке — как в Spotify: заголовок, артист, альбом и т. д. */
private enum class LibrarySort(val label: String) {
    TITLE("По названию"),
    ARTIST("По исполнителю"),
    ALBUM("По альбому"),
    YEAR("По году"),
    DURATION("По длительности"),
    ADDED("По дате добавления"),
}

/** Быстрый фильтр состава вкладки «Треки». */
private enum class LibraryFilter(val label: String) {
    ALL("Все"),
    STREAMING("Из стриминга"),
    DEVICE("Файлы устройства"),
}

/**
 * Медиатека в духе Spotify, но со своими акцентами и темами Sonora:
 * крупный заголовок на мягком градиенте, горизонтальные фильтры-чипы,
 * карточки-плейлисты с коллажем обложек и круглым Play, плотные строки треков.
 *
 * Вся прежняя функциональность на месте: сортировка/фильтры треков,
 * сканирование, переходы в плейлист/альбом/исполнителя, долгое нажатие
 * открывает те же листы действий, YouTube-импорт и создание плейлистов.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: MainViewModel, padding: PaddingValues, navigate: (String) -> Unit) {
    val tracks by vm.tracks.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState()
    // Медиатеке нужен только текущий трек/статус — позиция не показывается.
    val player by vm.playerChrome.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val importError by vm.playlistImportError.collectAsState()

    var sectionName by rememberSaveable { mutableStateOf(LibrarySection.ALL.name) }
    var sortName by rememberSaveable { mutableStateOf(LibrarySort.TITLE.name) }
    var sortDescending by rememberSaveable { mutableStateOf(false) }
    var filterName by rememberSaveable { mutableStateOf(LibraryFilter.ALL.name) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showYouTubeImport by remember { mutableStateOf(false) }
    var actionTrack by remember { mutableStateOf<Track?>(null) }
    var playlistForNewTrack by remember { mutableStateOf<Track?>(null) }
    var playlistMenu by remember { mutableStateOf<Playlist?>(null) }
    var albumMenu by remember { mutableStateOf<Album?>(null) }
    var artistMenu by remember { mutableStateOf<Artist?>(null) }
    var playlistPickerIds by remember { mutableStateOf<List<String>?>(null) }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }

    val section = LibrarySection.valueOf(sectionName)
    val sort = LibrarySort.valueOf(sortName)
    val filter = LibraryFilter.valueOf(filterName)

    val localTracks = remember(tracks) { tracks.filter { it.source == Source.LOCAL } }
    val savedFromStreaming = remember(localTracks) { localTracks.count { it.savedFromStreaming } }
    val sortedTracks = remember(localTracks, sort, sortDescending, filter) {
        val filtered = when (filter) {
            LibraryFilter.ALL -> localTracks
            LibraryFilter.STREAMING -> localTracks.filter { it.savedFromStreaming }
            LibraryFilter.DEVICE -> localTracks.filterNot { it.savedFromStreaming }
        }
        val comparator: Comparator<Track> = when (sort) {
            LibrarySort.TITLE -> compareBy<Track> { it.title.lowercase() }
            LibrarySort.ARTIST -> compareBy(
                { it.artist.lowercase() },
                { it.title.lowercase() },
            )
            LibrarySort.ALBUM -> compareBy(
                { it.album.orEmpty().lowercase() },
                { it.extra["trackNo"]?.toIntOrNull() ?: 0 },
                { it.title.lowercase() },
            )
            LibrarySort.YEAR -> compareBy<Track> { it.year ?: 0 }
            LibrarySort.DURATION -> compareBy<Track> { it.durationMs }
            // Сохранённые из стриминга несут метку downloadedAt; у файлов
            // устройства её нет — они остаются в конце списка.
            LibrarySort.ADDED -> compareBy<Track> {
                it.extra["downloadedAt"]?.toLongOrNull() ?: 0L
            }
        }
        filtered.sortedWith(comparator).let { if (sortDescending) it.asReversed() else it }
    }
    val albums = remember(localTracks) { vm.albums() }
    val artists = remember(localTracks) { vm.artists() }
    val favoriteTracks = remember(tracks, favorites) { tracks.filter { it.id in favorites } }
    // Не пересчитываем «хвосты» списков в теле композиции на каждой перерисовке.
    val recentTracks = remember(localTracks) { localTracks.take(8) }
    val editablePlaylists = remember(playlists) { playlists.filter { it.editable } }
    val importedTracks = remember(tracks) {
        vm.withoutSavedCopies(
            tracks.filter { it.source == Source.YOUTUBE && !it.isVideoContent },
        )
    }
    // Обложки плейлистов для коллажей: постер плейлиста или до 4 обложек треков.
    // Индекс id→трек строим один раз: vm.playlistTracks() пересобирал бы всю
    // таблицу для каждого плейлиста (O(плейлисты × треки) на главном потоке).
    val playlistCovers = remember(playlists, tracks) {
        val trackById = tracks.associateBy { it.id }
        playlists.associate { playlist ->
            val own = playlist.artworkUrl?.takeIf { it.isNotBlank() }
            val covers = if (own != null) {
                listOf(own)
            } else {
                playlist.trackIds.asSequence()
                    .mapNotNull { trackById[it] }
                    .mapNotNull { it.artworkUrl ?: it.artworkUri }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(4)
                    .toList()
            }
            playlist.id to covers
        }
    }
    val bottomPadding = padding.calculateBottomPadding()
    val playlistPlay: (Playlist) -> Unit = { vm.playPlaylist(it) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            // Плоская шапка: без цветного ореола, как в Spotify.
            LibraryHeader(
                localCount = localTracks.size,
                savedFromStreaming = savedFromStreaming,
                playlistCount = playlists.size,
                scanning = scanning,
                onScan = { vm.scanLibrary() },
                onAvatarClick = { navigate(Routes.SETTINGS) },
            )
            Spacer(Modifier.height(6.dp))
            LibraryChips(
                selected = section,
                onSelect = { sectionName = it.name },
            )
            Spacer(Modifier.height(6.dp))
            LibraryPlayAllRow(
                trackCount = localTracks.size,
                onPlayAll = { vm.playLibraryShuffled() },
            )
            Spacer(Modifier.height(8.dp))
            when (section) {
                LibrarySection.ALL -> AllTab(
                    playlists = playlists,
                    playlistCovers = playlistCovers,
                    albums = albums,
                    artists = artists,
                    recentTracks = recentTracks,
                    favorites = favorites,
                    player = player,
                    bottomPadding = bottomPadding,
                    onSelectSection = { sectionName = it.name },
                    onCreatePlaylist = { showCreateDialog = true },
                    navigate = navigate,
                    onPlaylistLongClick = { playlistMenu = it },
                    onPlayPlaylist = playlistPlay,
                    onAlbumLongClick = { albumMenu = it },
                    onPlayAlbum = { vm.playQueue(vm.tracksByIds(it.trackIds)) },
                    onArtistLongClick = { artistMenu = it },
                    onPlayArtist = { vm.playQueue(vm.tracksByIds(it.trackIds)) },
                    onClickTrack = { vm.playOrOpen(it) },
                    onLongClickTrack = { actionTrack = it },
                    onFavoriteTrack = { vm.toggleFavorite(it.id) },
                )
                LibrarySection.PLAYLISTS -> PlaylistsTab(
                    playlists = playlists,
                    playlistCovers = playlistCovers,
                    bottomPadding = bottomPadding,
                    onCreatePlaylist = { showCreateDialog = true },
                    onLongClick = { playlistMenu = it },
                    onPlay = playlistPlay,
                    navigate = navigate,
                )
                LibrarySection.ALBUMS -> AlbumsTab(
                    albums = albums,
                    navigate = navigate,
                    bottomPadding = bottomPadding,
                    onPlay = { vm.playQueue(vm.tracksByIds(it.trackIds)) },
                    onLongClick = { albumMenu = it },
                )
                LibrarySection.ARTISTS -> ArtistsTab(
                    artists = artists,
                    navigate = navigate,
                    bottomPadding = bottomPadding,
                    onPlay = { vm.playQueue(vm.tracksByIds(it.trackIds)) },
                    onLongClick = { artistMenu = it },
                )
                LibrarySection.TRACKS -> TracksTab(
                    tracks = sortedTracks,
                    favorites = favorites,
                    player = player,
                    sort = sort,
                    sortDescending = sortDescending,
                    filter = filter,
                    scanning = scanning,
                    onSortChange = { sortName = it.name },
                    onToggleDirection = { sortDescending = !sortDescending },
                    onFilterChange = { filterName = it.name },
                    onScan = { vm.scanLibrary() },
                    onClick = { vm.playOrOpen(it) },
                    onLongClick = { actionTrack = it },
                    onFavorite = { vm.toggleFavorite(it.id) },
                    bottomPadding = bottomPadding,
                )
                LibrarySection.FAVORITES -> FavoritesTab(
                    tracks = favoriteTracks,
                    favorites = favorites,
                    player = player,
                    onPlayAll = { vm.playQueue(favoriteTracks) },
                    onClick = { vm.playOrOpen(it) },
                    onLongClick = { actionTrack = it },
                    onFavorite = { vm.toggleFavorite(it.id) },
                    bottomPadding = bottomPadding,
                )
                LibrarySection.YOUTUBE -> YouTubeTab(
                    tracks = importedTracks,
                    favorites = favorites,
                    player = player,
                    onClick = { vm.playOrOpen(it) },
                    onLongClick = { actionTrack = it },
                    onFavorite = { vm.toggleFavorite(it.id) },
                    onImport = { navigate(Routes.SETTINGS) },
                    onImportLink = { showYouTubeImport = true },
                    importError = importError,
                    saveStatesFlow = vm.saveStates,
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
                onOpenArtist = if (track.artist.isNotBlank()) {
                    {
                        actionTrack = null
                        navigate(Routes.artist(track.artist))
                    }
                } else {
                    null
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

        playlistMenu?.let { playlist ->
            PlaylistActionsSheet(
                playlist = playlist,
                onDismiss = { playlistMenu = null },
                onPlay = {
                    playlistMenu = null
                    val list = vm.playlistTracks(playlist)
                    if (list.isNotEmpty()) vm.playQueue(list)
                },
                onShuffle = {
                    playlistMenu = null
                    val list = vm.playlistTracks(playlist)
                    if (list.isNotEmpty()) {
                        if (!player.shuffle) vm.toggleShuffle()
                        vm.playQueue(list.shuffled())
                    }
                },
                onDownload = {
                    playlistMenu = null
                    vm.downloadPlaylist(playlist)
                },
                onPlayNext = {
                    playlistMenu = null
                    vm.playPlaylistNext(playlist)
                },
                onSortByTitle = {
                    playlistMenu = null
                    vm.sortPlaylist(playlist, compareBy { it.title.lowercase() })
                },
                onSortByArtist = {
                    playlistMenu = null
                    vm.sortPlaylist(
                        playlist,
                        compareBy({ it.artist.lowercase() }, { it.title.lowercase() }),
                    )
                },
                onRename = {
                    playlistMenu = null
                    renameTarget = playlist
                },
                onDelete = if (playlist.editable) {
                    {
                        playlistMenu = null
                        deleteTarget = playlist
                    }
                } else {
                    null
                },
            )
        }

        albumMenu?.let { album ->
            LibraryItemSheet(
                title = album.name,
                subtitle = listOfNotNull(album.artist, album.year?.toString())
                    .joinToString(" · "),
                onDismiss = { albumMenu = null },
                onPlay = {
                    albumMenu = null
                    vm.playQueue(vm.tracksByIds(album.trackIds))
                },
                onPlayNext = {
                    albumMenu = null
                    vm.playTracksNext(vm.tracksByIds(album.trackIds))
                },
                onAddToPlaylist = {
                    albumMenu = null
                    playlistPickerIds = album.trackIds
                },
                onDownload = {
                    albumMenu = null
                    vm.downloadTracks(vm.tracksByIds(album.trackIds))
                },
            )
        }

        artistMenu?.let { artist ->
            LibraryItemSheet(
                title = artist.name,
                subtitle = "Исполнитель · ${tracksLabel(artist.trackIds.size)}",
                onDismiss = { artistMenu = null },
                onPlay = {
                    artistMenu = null
                    vm.playQueue(vm.tracksByIds(artist.trackIds))
                },
                onPlayNext = {
                    artistMenu = null
                    vm.playTracksNext(vm.tracksByIds(artist.trackIds))
                },
                onAddToPlaylist = {
                    artistMenu = null
                    playlistPickerIds = artist.trackIds
                },
                onDownload = null,
            )
        }

        playlistPickerIds?.let { ids ->
            PlaylistPickerDialog(
                playlists = editablePlaylists,
                onDismiss = { playlistPickerIds = null },
                onPick = { target ->
                    playlistPickerIds = null
                    vm.addToPlaylist(target.id, ids)
                },
            )
        }

        renameTarget?.let { playlist ->
            RenamePlaylistDialog(
                currentName = playlist.name,
                onDismiss = { renameTarget = null },
                onRename = { name ->
                    renameTarget = null
                    vm.renamePlaylist(playlist.id, name)
                },
            )
        }

        deleteTarget?.let { playlist ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("Удалить плейлист?") },
                text = { Text("«${playlist.name}» будет удалён. Файлы на устройстве останутся.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deleteTarget = null
                            vm.deletePlaylist(playlist.id)
                        },
                    ) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text("Отмена") }
                },
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

/**
 * Шапка медиатеки: огромное «Медиатека», строка-сводка и компактные действия
 * (сканирование фонотеки + аватар-«профиль», ведущий в настройки).
 */
@Composable
private fun LibraryHeader(
    localCount: Int,
    savedFromStreaming: Int,
    playlistCount: Int,
    scanning: Boolean,
    onScan: () -> Unit,
    onAvatarClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Медиатека",
                style = MaterialTheme.typography.headlineLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    append(tracksLabel(localCount))
                    append(" на устройстве")
                    if (savedFromStreaming > 0) append(" · из стриминга: $savedFromStreaming")
                    if (playlistCount > 0) append(" · плейлистов: $playlistCount")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onScan) {
            if (scanning) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Сканировать фонотеку",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onAvatarClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = "Настройки профиля",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * «Слушать всю медиатеку» — одна большая кнопка, как Play у «Понравившихся»
 * в Spotify: вся фонотека вперемешку, shuffle включается автоматически.
 */
@Composable
private fun LibraryPlayAllRow(
    trackCount: Int,
    onPlayAll: () -> Unit,
) {
    val enabled = trackCount > 0
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            .clickable(enabled = enabled, onClick = onPlayAll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Shuffle,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Слушать всю медиатеку",
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (enabled) {
                    "${tracksLabel(trackCount)} · вперемешку, как плейлист"
                } else {
                    "Пока нет треков"
                },
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.82f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (enabled) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Слушать всю медиатеку",
                tint = contentColor,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/** Горизонтальные фильтры-чипы в духе Spotify; выбранный залит акцентом. */
@Composable
private fun LibraryChips(    selected: LibrarySection,
    onSelect: (LibrarySection) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibrarySection.entries.forEach { section ->
            val isSelected = section == selected
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    )
                    .clickable { onSelect(section) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = section.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * «Все»: сводка медиатеки рельсами, как стартовая страница Spotify.
 * Плейлисты идут первыми и запускаются верхней кнопкой Play.
 */
@Composable
private fun AllTab(
    playlists: List<Playlist>,
    playlistCovers: Map<String, List<String>>,
    albums: List<Album>,
    artists: List<Artist>,
    recentTracks: List<Track>,
    favorites: Set<String>,
    player: PlayerUiState,
    bottomPadding: Dp,
    onSelectSection: (LibrarySection) -> Unit,
    onCreatePlaylist: () -> Unit,
    navigate: (String) -> Unit,
    onPlaylistLongClick: (Playlist) -> Unit,
    onPlayPlaylist: (Playlist) -> Unit,
    onAlbumLongClick: (Album) -> Unit,
    onPlayAlbum: (Album) -> Unit,
    onArtistLongClick: (Artist) -> Unit,
    onPlayArtist: (Artist) -> Unit,
    onClickTrack: (Track) -> Unit,
    onLongClickTrack: (Track) -> Unit,
    onFavoriteTrack: (Track) -> Unit,
) {
    // Срезы для рельсов считаем один раз на список, а не на каждую перерисовку.
    val featuredPlaylists = remember(playlists) { playlists.take(8) }
    val featuredAlbums = remember(albums) { albums.take(10) }
    val featuredArtists = remember(artists) { artists.take(10) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 2.dp,
            bottom = bottomPadding + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "all-playlists-header") {
            SectionHeader(
                title = "Плейлисты",
                subtitle = if (playlists.isEmpty()) {
                    "Создайте свой первый плейлист"
                } else {
                    tracksLabel(playlists.size) + " · запуск одним нажатием"
                },
                actionLabel = if (playlists.isNotEmpty()) "Все" else null,
                onAction = { onSelectSection(LibrarySection.PLAYLISTS) },
            )
        }
        item(key = "all-playlists-rail") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (playlists.isEmpty()) {
                    item(key = "all-create-playlist") {
                        CreatePlaylistTile(
                            onClick = onCreatePlaylist,
                            modifier = Modifier.width(156.dp),
                        )
                    }
                } else {
                    items(featuredPlaylists, key = { "all-pl-${it.id}" }) { playlist ->
                        PlaylistCard(
                            playlist = playlist,
                            coverUrls = playlistCovers[playlist.id].orEmpty(),
                            onClick = { navigate(Routes.playlist(playlist.id)) },
                            onLongClick = { onPlaylistLongClick(playlist) },
                            onPlay = { onPlayPlaylist(playlist) },
                            modifier = Modifier.width(156.dp),
                        )
                    }
                    item(key = "all-create-playlist") {
                        CreatePlaylistTile(
                            onClick = onCreatePlaylist,
                            modifier = Modifier.width(156.dp),
                        )
                    }
                }
            }
        }
        if (albums.isEmpty() && artists.isEmpty() && recentTracks.isEmpty()) {
            item(key = "all-empty") {
                EmptyState(
                    icon = Icons.Rounded.LibraryMusic,
                    title = "Фонотека пуста",
                    subtitle = "Нажмите «Сканировать» в шапке — найдём аудиофайлы на устройстве.",
                )
            }
        }
        if (albums.isNotEmpty()) {
            item(key = "all-albums-header") {
                SectionHeader(
                    title = "Альбомы",
                    subtitle = tracksLabel(albums.size) + " в фонотеке",
                    actionLabel = "Все",
                    onAction = { onSelectSection(LibrarySection.ALBUMS) },
                )
            }
            item(key = "all-albums-rail") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(featuredAlbums, key = { "all-alb-${it.name}|${it.artist}" }) { album ->
                        AlbumCard(
                            album = album,
                            onClick = { navigate(Routes.album(album.name, album.artist)) },
                            onLongClick = { onAlbumLongClick(album) },
                            onPlay = { onPlayAlbum(album) },
                            modifier = Modifier.width(150.dp),
                        )
                    }
                }
            }
        }
        if (artists.isNotEmpty()) {
            item(key = "all-artists-header") {
                SectionHeader(
                    title = "Исполнители",
                    subtitle = tracksLabel(artists.size) + " в фонотеке",
                    actionLabel = "Все",
                    onAction = { onSelectSection(LibrarySection.ARTISTS) },
                )
            }
            item(key = "all-artists-rail") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(featuredArtists, key = { "all-art-${it.name}" }) { artist ->
                        ArtistCard(
                            artist = artist,
                            onClick = { navigate(Routes.artist(artist.name)) },
                            onLongClick = { onArtistLongClick(artist) },
                            onPlay = { onPlayArtist(artist) },
                            modifier = Modifier.width(140.dp),
                        )
                    }
                }
            }
        }
        if (recentTracks.isNotEmpty()) {
            item(key = "all-recent-header") {
                SectionHeader(
                    title = "Недавно добавленные",
                    actionLabel = "Все треки",
                    onAction = { onSelectSection(LibrarySection.TRACKS) },
                )
            }
            items(recentTracks, key = { "all-track-${it.id}" }, contentType = { "track" }) { track ->
                TrackRow(
                    track = track,
                    isFavorite = track.id in favorites,
                    isPlaying = player.current?.id == track.id && player.isPlaying,
                    onClick = { onClickTrack(track) },
                    onLongClick = { onLongClickTrack(track) },
                    onFavorite = { onFavoriteTrack(track) },
                )
            }
        }
    }
}

/** Вкладка «Треки»: счётчики, сортировка, фильтры и плотный список. */
@Composable
private fun TracksTab(
    tracks: List<Track>,
    favorites: Set<String>,
    player: PlayerUiState,
    sort: LibrarySort,
    sortDescending: Boolean,
    filter: LibraryFilter,
    scanning: Boolean,
    onSortChange: (LibrarySort) -> Unit,
    onToggleDirection: () -> Unit,
    onFilterChange: (LibraryFilter) -> Unit,
    onScan: () -> Unit,
    onClick: (Track) -> Unit,
    onLongClick: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    bottomPadding: Dp,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = bottomPadding + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "tracks-controls") {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = tracksLabel(tracks.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        FilterChip(
                            selected = false,
                            onClick = { sortMenuOpen = true },
                            label = { Text(sort.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Sort,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            LibrarySort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    leadingIcon = {
                                        if (option == sort) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    },
                                    onClick = {
                                        sortMenuOpen = false
                                        onSortChange(option)
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = onToggleDirection) {
                        Icon(
                            imageVector = if (sortDescending) {
                                Icons.Rounded.ArrowDownward
                            } else {
                                Icons.Rounded.ArrowUpward
                            },
                            contentDescription = if (sortDescending) {
                                "Сначала больше"
                            } else {
                                "Сначала меньше"
                            },
                        )
                    }
                    IconButton(onClick = onScan) {
                        if (scanning) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Сканировать фонотеку")
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LibraryFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { onFilterChange(option) },
                            label = { Text(option.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
            }
        }
        if (tracks.isEmpty()) {
            item(key = "tracks-empty") {
                EmptyState(
                    icon = Icons.Rounded.LibraryMusic,
                    title = if (filter == LibraryFilter.STREAMING) {
                        "Нет сохранённых треков"
                    } else {
                        "Треков нет"
                    },
                    subtitle = when (filter) {
                        LibraryFilter.STREAMING ->
                            "Нажмите кнопку загрузки у онлайн-трека — он сохранится сюда, " +
                                "рядом с музыкой на устройстве."
                        LibraryFilter.DEVICE ->
                            "Файлов устройства пока нет: нажмите «Сканировать», чтобы найти музыку."
                        LibraryFilter.ALL ->
                            "Нажмите «Сканировать» — найдём аудиофайлы на устройстве."
                    },
                )
            }
        } else {
            items(tracks, key = { it.id }, contentType = { "track" }) { track ->
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

/** Вкладка «Альбомы»: сетка обложек с круглым Play и подписью. */
@Composable
private fun AlbumsTab(
    albums: List<Album>,
    navigate: (String) -> Unit,
    bottomPadding: Dp,
    onPlay: (Album) -> Unit,
    onLongClick: (Album) -> Unit,
) {
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
            top = 8.dp,
            bottom = bottomPadding + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        gridItems(albums, key = { "${it.name}|${it.artist}" }, contentType = { "album" }) { album ->
            AlbumCard(
                album = album,
                onClick = { navigate(Routes.album(album.name, album.artist)) },
                onLongClick = { onLongClick(album) },
                onPlay = { onPlay(album) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Вкладка «Исполнители»: сетка круглых аватаров, Play поверх обложки. */
@Composable
private fun ArtistsTab(
    artists: List<Artist>,
    navigate: (String) -> Unit,
    bottomPadding: Dp,
    onPlay: (Artist) -> Unit,
    onLongClick: (Artist) -> Unit,
) {
    if (artists.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.Person,
            title = "Исполнителей нет",
            subtitle = "Исполнители берутся из тегов локальных файлов.",
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
            top = 8.dp,
            bottom = bottomPadding + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        gridItems(artists, key = { it.name }, contentType = { "artist" }) { artist ->
            ArtistCard(
                artist = artist,
                onClick = { navigate(Routes.artist(artist.name)) },
                onLongClick = { onLongClick(artist) },
                onPlay = { onPlay(artist) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Вкладка «Плейлисты»: 2 колонки карточек с коллажем обложек и круглым Play,
 * который сразу запускает весь плейлист. Долгое нажатие — прежний лист действий.
 */
@Composable
private fun PlaylistsTab(
    playlists: List<Playlist>,
    playlistCovers: Map<String, List<String>>,
    navigate: (String) -> Unit,
    bottomPadding: Dp,
    onCreatePlaylist: () -> Unit,
    onLongClick: (Playlist) -> Unit,
    onPlay: (Playlist) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = bottomPadding + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "create-playlist") {
            CreatePlaylistTile(
                onClick = onCreatePlaylist,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (playlists.isEmpty()) {
            item(
                key = "playlists-empty",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                EmptyState(
                    icon = Icons.Rounded.PlaylistPlay,
                    title = "Плейлистов нет",
                    subtitle = "Создайте свой или синхронизируйте аккаунт в разделе «Подключения».",
                )
            }
        } else {
            gridItems(playlists, key = { it.id }, contentType = { "playlist" }) { playlist ->
                PlaylistCard(
                    playlist = playlist,
                    coverUrls = playlistCovers[playlist.id].orEmpty(),
                    onClick = { navigate(Routes.playlist(playlist.id)) },
                    onLongClick = { onLongClick(playlist) },
                    onPlay = { onPlay(playlist) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Вкладка «Избранное»: крупный Play и тот же плотный ритм строк. */
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
            top = 8.dp,
            bottom = bottomPadding + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "favorites-header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tracks.isNotEmpty()) {
                    CircularPlayButton(
                        onClick = onPlayAll,
                        size = 52.dp,
                        contentDescription = "Играть все избранные",
                    )
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = tracksLabel(tracks.size),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Всё, что отмечено сердечком",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (tracks.isEmpty()) {
            item(key = "favorites-empty") {
                EmptyState(
                    icon = Icons.Rounded.FavoriteBorder,
                    title = "Пока пусто",
                    subtitle = "Отмечайте треки сердечком — они появятся здесь.",
                )
            }
        } else {
            items(tracks, key = { it.id }, contentType = { "track" }) { track ->
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
    saveStatesFlow: StateFlow<Map<String, TrackSaveState>>,
    bottomPadding: Dp,
) {
    // Прогресс скачиваний обновляется часто: подписку держим внутри вкладки,
    // чтобы проценты сохранения не перерисовывали всю Медиатеку.
    val saveStates by saveStatesFlow.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = bottomPadding + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "youtube-header") {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = tracksLabel(tracks.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
        }
        if (tracks.isEmpty()) {
            item(key = "youtube-empty") {
                EmptyState(
                    icon = Icons.Rounded.PlayCircle,
                    title = "Библиотека YouTube Music пуста",
                    subtitle = "Найдите треки в поиске (InnerTube), импортируйте плейлист по ссылке " +
                        "или CSV/ZIP из Google Takeout. Все треки играются внутри Sonora.",
                )
            }
        } else {
            items(tracks, key = { it.id }, contentType = { "track" }) { track ->
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

/** Действия над плейлистом прямо из списка — по долгому нажатию. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistActionsSheet(
    playlist: Playlist,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDownload: () -> Unit,
    onPlayNext: () -> Unit,
    onSortByTitle: () -> Unit,
    onSortByArtist: () -> Unit,
    onRename: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                text = tracksLabel(playlist.trackIds.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(8.dp))
            PlaylistSheetAction(Icons.Rounded.PlayArrow, "Играть", onPlay)
            PlaylistSheetAction(Icons.Rounded.Shuffle, "Перемешать", onShuffle)
            PlaylistSheetAction(
                Icons.Rounded.Download,
                "Скачать весь плейлист",
                onDownload,
            )
            PlaylistSheetAction(
                Icons.Rounded.QueueMusic,
                "Играть следующими",
                onPlayNext,
            )
            PlaylistSheetAction(
                Icons.Rounded.Sort,
                "Сортировать по названию",
                onSortByTitle,
            )
            PlaylistSheetAction(
                Icons.Rounded.Sort,
                "Сортировать по исполнителю",
                onSortByArtist,
            )
            if (playlist.editable) {
                PlaylistSheetAction(Icons.Rounded.Edit, "Переименовать", onRename)
            }
            if (onDelete != null) {
                PlaylistSheetAction(
                    icon = Icons.Rounded.Delete,
                    label = "Удалить",
                    onClick = onDelete,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PlaylistSheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = tint,
        )
    }
}

/** Переименование плейлиста. */
@Composable
private fun RenamePlaylistDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переименовать плейлист") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Название") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** Действия над альбомом или исполнителем: играть, следующими, в плейлист. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryItemSheet(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: (() -> Unit)?,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            PlaylistSheetAction(Icons.Rounded.PlayArrow, "Играть", onPlay)
            PlaylistSheetAction(Icons.Rounded.QueueMusic, "Играть следующими", onPlayNext)
            PlaylistSheetAction(Icons.Rounded.PlaylistAdd, "Добавить в плейлист", onAddToPlaylist)
            if (onDownload != null) {
                PlaylistSheetAction(Icons.Rounded.Download, "Скачать", onDownload)
            }
        }
    }
}

/** Выбор плейлиста для добавления набора треков. */
@Composable
private fun PlaylistPickerDialog(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPick: (Playlist) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить в плейлист") },
        text = {
            if (playlists.isEmpty()) {
                Text(
                    text = "Своих плейлистов пока нет — создайте его в разделе «Плейлисты».",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(playlist) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlaylistAdd,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = tracksLabel(playlist.trackIds.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

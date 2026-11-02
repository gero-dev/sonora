package app.sonora.ui.screens

import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.ViewList
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sonora.core.model.Album
import app.sonora.core.model.Artist
import app.sonora.core.model.Playlist
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.data.download.SaveStatus
import app.sonora.data.download.TrackSaveState
import app.sonora.data.wave.TrackRatingIndex
import app.sonora.playback.PlayerUiState
import app.sonora.ui.MainViewModel
import app.sonora.ui.components.AlbumCard
import app.sonora.ui.components.AlbumListRow
import app.sonora.ui.components.ArtistCard
import app.sonora.ui.components.ArtistListRow
import app.sonora.ui.components.CircularPlayButton
import app.sonora.ui.components.CreatePlaylistDialog
import app.sonora.ui.components.CreatePlaylistTile
import app.sonora.ui.components.EmptyState
import app.sonora.ui.components.LikedSongsCard
import app.sonora.ui.components.PlaylistCard
import app.sonora.ui.components.PlaylistListRow
import app.sonora.ui.components.QuickAccessTile
import app.sonora.ui.components.RecentTrackCard
import app.sonora.ui.components.SectionHeader
import app.sonora.ui.components.TrackActionSheet
import app.sonora.ui.components.TrackRow
import app.sonora.ui.components.tracksLabel
import app.sonora.ui.nav.Routes
import kotlinx.coroutines.flow.StateFlow

/**
 * Разделы медиатеки — порядок чипов как в Spotify: сверху «Все» (сводка),
 * затем плейлисты, исполнители, альбомы, треки, загрузки и любимые треки.
 * YouTube-импорт Sonora остаётся отдельным разделом, потому что это
 * не тип библиотеки Spotify, а источник.
 */
private enum class LibrarySection(val label: String) {
    ALL("Все"),
    PLAYLISTS("Плейлисты"),
    ARTISTS("Исполнители"),
    ALBUMS("Альбомы"),
    TRACKS("Треки"),
    DOWNLOADS("Загрузки"),
    FAVORITES("Любимые"),
    YOUTUBE("YouTube"),
}

/**
 * Меню сортировки в духе Spotify. Первые пункты — из запроса пользователя
 * («Недавние», «По алфавиту», «По артисту», «По дате добавления»), дальше
 * сохранены прежние варианты, к которым привыкли в Sonora.
 */
private enum class LibrarySort(val label: String, val defaultDescending: Boolean) {
    RECENT("Недавние", true),
    TITLE("По алфавиту", false),
    ARTIST("По артисту", false),
    ADDED("По дате добавления", true),
    ALBUM("По альбому", false),
    YEAR("По году", true),
    DURATION("По длительности", false),
}

/** Быстрый фильтр состава раздела «Треки». */
private enum class LibraryFilter(val label: String) {
    ALL("Все"),
    STREAMING("Из стриминга"),
    DEVICE("Файлы устройства"),
}

/** Вид раздела: сетка обложек или компактный список строк. */
private enum class LibraryView {
    GRID,
    LIST,
}

/**
 * UI-предпочтения медиатеки. Хранятся отдельным SharedPreferences-файлом,
 * а не в SettingsStore: это состояние экрана (чип, сортировка, вид), а не
 * настройка приложения, и оно не должно требовать правок слоя данных.
 */
private class LibraryUiPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun section(): LibrarySection = readEnum(prefs.getString(KEY_SECTION, null), LibrarySection.ALL)
    fun sort(): LibrarySort = readEnum(prefs.getString(KEY_SORT, null), LibrarySort.RECENT)
    fun descending(): Boolean = prefs.getBoolean(KEY_DESCENDING, true)
    fun view(): LibraryView = readEnum(prefs.getString(KEY_VIEW, null), LibraryView.GRID)
    fun filter(): LibraryFilter = readEnum(prefs.getString(KEY_FILTER, null), LibraryFilter.ALL)

    fun setSection(value: LibrarySection) {
        prefs.edit().putString(KEY_SECTION, value.name).apply()
    }

    fun setSort(value: LibrarySort) {
        prefs.edit().putString(KEY_SORT, value.name).apply()
    }

    fun setDescending(value: Boolean) {
        prefs.edit().putBoolean(KEY_DESCENDING, value).apply()
    }

    fun setView(value: LibraryView) {
        prefs.edit().putString(KEY_VIEW, value.name).apply()
    }

    fun setFilter(value: LibraryFilter) {
        prefs.edit().putString(KEY_FILTER, value.name).apply()
    }

    private inline fun <reified T : Enum<T>> readEnum(name: String?, fallback: T): T =
        name?.let { stored -> runCatching { enumValueOf<T>(stored) }.getOrNull() } ?: fallback

    private companion object {
        const val PREFS_NAME = "sonora_library_ui"
        const val KEY_SECTION = "section"
        const val KEY_SORT = "sort"
        const val KEY_DESCENDING = "descending"
        const val KEY_VIEW = "view"
        const val KEY_FILTER = "filter"
    }
}

/** Сортировка списка с учётом выбранного направления (без копии при инверсии). */
private fun <T> List<T>.sortedFor(comparator: Comparator<T>, descending: Boolean): List<T> =
    sortedWith(comparator).let { if (descending) it.asReversed() else it }

/** Поиск внутри медиатеки: название, исполнитель, альбом. Регистр не важен. */
private fun Playlist.matchesQuery(query: String): Boolean =
    name.lowercase().contains(query) ||
        description?.lowercase()?.contains(query) == true ||
        ownerName?.lowercase()?.contains(query) == true

private fun Album.matchesQuery(query: String): Boolean =
    name.lowercase().contains(query) || artist.lowercase().contains(query)

private fun Artist.matchesQuery(query: String): Boolean = name.lowercase().contains(query)

private fun Track.matchesQuery(query: String): Boolean =
    title.lowercase().contains(query) ||
        artist.lowercase().contains(query) ||
        album?.lowercase()?.contains(query) == true

/**
 * Дата добавления трека. У сохранённых из стриминга есть downloadedAt,
 * у файлов устройства — addedAt/scannedAt; если меток нет, трек остаётся
 * в исходном порядке (stable sort).
 */
private fun Track.addedAtMs(): Long =
    extra["addedAt"]?.toLongOrNull()
        ?: extra["downloadedAt"]?.toLongOrNull()
        ?: extra["scannedAt"]?.toLongOrNull()
        ?: 0L

private fun trackComparator(sort: LibrarySort): Comparator<Track> = when (sort) {
    LibrarySort.RECENT, LibrarySort.ADDED -> compareBy<Track> { it.addedAtMs() }
    LibrarySort.TITLE -> compareBy<Track> { it.title.lowercase() }
    LibrarySort.ARTIST -> compareBy<Track>({ it.artist.lowercase() }, { it.title.lowercase() })
    LibrarySort.ALBUM -> compareBy<Track>(
        { it.album.orEmpty().lowercase() },
        { it.extra["trackNo"]?.toIntOrNull() ?: 0 },
        { it.title.lowercase() },
    )
    LibrarySort.YEAR -> compareBy<Track> { it.year ?: 0 }
    LibrarySort.DURATION -> compareBy<Track> { it.durationMs }
}

private fun playlistComparator(sort: LibrarySort): Comparator<Playlist> = when (sort) {
    LibrarySort.RECENT, LibrarySort.ADDED, LibrarySort.YEAR ->
        compareBy<Playlist>({ it.createdAt }, { it.name.lowercase() })
    LibrarySort.TITLE, LibrarySort.ARTIST -> compareBy<Playlist> { it.name.lowercase() }
    LibrarySort.ALBUM -> compareBy<Playlist> { it.name.lowercase() }
    LibrarySort.DURATION -> compareBy<Playlist> { it.trackIds.size }
}

private fun albumComparator(sort: LibrarySort): Comparator<Album> = when (sort) {
    LibrarySort.RECENT, LibrarySort.ADDED, LibrarySort.YEAR ->
        compareBy<Album>({ it.year ?: 0 }, { it.name.lowercase() })
    LibrarySort.TITLE, LibrarySort.ALBUM -> compareBy<Album> { it.name.lowercase() }
    LibrarySort.ARTIST -> compareBy<Album>({ it.artist.lowercase() }, { it.name.lowercase() })
    LibrarySort.DURATION -> compareBy<Album> { it.trackIds.size }
}

private fun artistComparator(sort: LibrarySort): Comparator<Artist> = when (sort) {
    LibrarySort.DURATION -> compareBy<Artist> { it.trackIds.size }
    else -> compareBy<Artist> { it.name.lowercase() }
}

/**
 * Медиатека в структуре Spotify:
 * - крупный заголовок и встроенный поиск по библиотеке;
 * - горизонтальные фильтры-чипы со счётчиками;
 * - панель сортировки и переключатель «список/сетка»;
 * - «Все» — сводка с закреплённым рядом, «Недавно прослушано», «Любимыми
 *   треками», «Загруженными», «Моими плейлистами» и полками альбомов/исполнителей.
 *
 * Вся прежняя функциональность сохранена: воспроизведение, сканирование,
 * импорт YouTube (по ссылке и Takeout), создание/переименование/удаление
 * плейлистов, скачивание, сортировка плейлистов и долгое нажатие с листом
 * действий. Выбранный чип, сортировка, направление и вид переживают запуск.
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
    val historyEntries by vm.historyEntries.collectAsState()
    // Оценки «нравится / не нравится» для рекомендаций — общий индекс волны.
    val ratingIndex by vm.trackRatingIndex.collectAsState()

    val context = LocalContext.current
    val uiPrefs = remember(context) { LibraryUiPrefs(context) }

    var sectionName by rememberSaveable { mutableStateOf(uiPrefs.section().name) }
    var sortName by rememberSaveable { mutableStateOf(uiPrefs.sort().name) }
    var sortDescending by rememberSaveable { mutableStateOf(uiPrefs.descending()) }
    var viewName by rememberSaveable { mutableStateOf(uiPrefs.view().name) }
    var filterName by rememberSaveable { mutableStateOf(uiPrefs.filter().name) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
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

    val section = remember(sectionName) {
        runCatching { LibrarySection.valueOf(sectionName) }.getOrDefault(LibrarySection.ALL)
    }
    val sort = remember(sortName) {
        runCatching { LibrarySort.valueOf(sortName) }.getOrDefault(LibrarySort.RECENT)
    }
    val filter = remember(filterName) {
        runCatching { LibraryFilter.valueOf(filterName) }.getOrDefault(LibraryFilter.ALL)
    }
    val view = remember(viewName) {
        runCatching { LibraryView.valueOf(viewName) }.getOrDefault(LibraryView.GRID)
    }

    // ---------- Данные ----------

    val localTracks = remember(tracks) { tracks.filter { it.source == Source.LOCAL } }
    val downloadedTracks = remember(localTracks) {
        localTracks.filter { it.savedFromStreaming }
            .sortedByDescending { it.extra["downloadedAt"]?.toLongOrNull() ?: 0L }
    }
    val favoriteTracks = remember(tracks, favorites) { tracks.filter { it.id in favorites } }
    val albums = remember(localTracks) { vm.albums() }
    val artists = remember(localTracks) { vm.artists() }
    val editablePlaylists = remember(playlists) { playlists.filter { it.editable } }
    val importedTracks = remember(tracks) {
        vm.withoutSavedCopies(
            tracks.filter { it.source == Source.YOUTUBE && !it.isVideoContent },
        )
    }
    // «Недавно прослушано» — та же история, что на экране «История».
    val recentlyPlayed = remember(historyEntries, tracks) { vm.historyTracks(16) }
    // Обложки плейлистов для коллажей: индекс id→трек строим один раз,
    // иначе получаем O(плейлисты × треки) на каждой перерисовке.
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

    // ---------- Поиск и сортировка ----------

    val normalizedQuery = query.trim()
    val queryKey = normalizedQuery.lowercase()
    val searching = normalizedQuery.isNotEmpty()

    val visiblePlaylists = remember(playlists, queryKey, sort, sortDescending) {
        playlists
            .filter { queryKey.isEmpty() || it.matchesQuery(queryKey) }
            .sortedFor(playlistComparator(sort), sortDescending)
    }
    val visibleAlbums = remember(albums, queryKey, sort, sortDescending) {
        albums
            .filter { queryKey.isEmpty() || it.matchesQuery(queryKey) }
            .sortedFor(albumComparator(sort), sortDescending)
    }
    val visibleArtists = remember(artists, queryKey, sort, sortDescending) {
        artists
            .filter { queryKey.isEmpty() || it.matchesQuery(queryKey) }
            .sortedFor(artistComparator(sort), sortDescending)
    }
    val visibleTracks = remember(localTracks, queryKey, sort, sortDescending, filter) {
        localTracks
            .filter { track ->
                (queryKey.isEmpty() || track.matchesQuery(queryKey)) && when (filter) {
                    LibraryFilter.ALL -> true
                    LibraryFilter.STREAMING -> track.savedFromStreaming
                    LibraryFilter.DEVICE -> !track.savedFromStreaming
                }
            }
            .sortedFor(trackComparator(sort), sortDescending)
    }
    val visibleFavorites = remember(favoriteTracks, queryKey, sort, sortDescending) {
        favoriteTracks
            .filter { queryKey.isEmpty() || it.matchesQuery(queryKey) }
            .sortedFor(trackComparator(sort), sortDescending)
    }
    val visibleDownloads = remember(downloadedTracks, queryKey, sort, sortDescending) {
        downloadedTracks
            .filter { queryKey.isEmpty() || it.matchesQuery(queryKey) }
            .sortedFor(trackComparator(sort), sortDescending)
    }
    val visibleYouTube = remember(importedTracks, queryKey, sort, sortDescending) {
        importedTracks
            .filter { queryKey.isEmpty() || it.matchesQuery(queryKey) }
            .sortedFor(trackComparator(sort), sortDescending)
    }
    // Короткий срез для сводки: не держим в композиции весь список лишний раз.
    val tracksPreview = remember(visibleTracks) { visibleTracks.take(12) }

    val chipCounts = remember(
        playlists, albums, artists, localTracks, downloadedTracks, favoriteTracks, importedTracks,
    ) {
        mapOf(
            LibrarySection.ALL to
                (playlists.size + albums.size + artists.size + localTracks.size + importedTracks.size),
            LibrarySection.PLAYLISTS to playlists.size,
            LibrarySection.ARTISTS to artists.size,
            LibrarySection.ALBUMS to albums.size,
            LibrarySection.TRACKS to localTracks.size,
            LibrarySection.DOWNLOADS to downloadedTracks.size,
            LibrarySection.FAVORITES to favoriteTracks.size,
            LibrarySection.YOUTUBE to importedTracks.size,
        )
    }

    // ---------- Действия ----------

    val selectSection: (LibrarySection) -> Unit = { next ->
        sectionName = next.name
        uiPrefs.setSection(next)
    }
    val changeSort: (LibrarySort) -> Unit = { next ->
        sortName = next.name
        sortDescending = next.defaultDescending
        uiPrefs.setSort(next)
        uiPrefs.setDescending(next.defaultDescending)
    }
    val toggleDirection: () -> Unit = {
        sortDescending = !sortDescending
        uiPrefs.setDescending(sortDescending)
    }
    val changeView: (LibraryView) -> Unit = { next ->
        viewName = next.name
        uiPrefs.setView(next)
    }
    val changeFilter: (LibraryFilter) -> Unit = { next ->
        filterName = next.name
        uiPrefs.setFilter(next)
    }
    val closeSearch: () -> Unit = {
        searchOpen = false
        query = ""
    }
    val playTracks: (List<Track>) -> Unit = { list -> if (list.isNotEmpty()) vm.playQueue(list) }
    val onLikeTrack: (Track) -> Unit = { vm.toggleTrackLike(it) }
    val onDislikeTrack: (Track) -> Unit = { vm.toggleTrackDislike(it) }

    val bottomPadding = padding.calculateBottomPadding()
    val gridSections = section == LibrarySection.PLAYLISTS ||
        section == LibrarySection.ALBUMS ||
        section == LibrarySection.ARTISTS

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            LibraryHeader(
                trackCount = localTracks.size,
                downloadedCount = downloadedTracks.size,
                playlistCount = playlists.size,
                favoriteCount = favoriteTracks.size,
                scanning = scanning,
                searchOpen = searchOpen,
                onSearchClick = {
                    if (searchOpen) {
                        closeSearch()
                    } else {
                        searchOpen = true
                    }
                },
                onScan = { vm.scanLibrary() },
                onAvatarClick = { navigate(Routes.SETTINGS) },
            )
            if (searchOpen) {
                LibrarySearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onClose = closeSearch,
                )
            }
            Spacer(Modifier.height(6.dp))
            LibraryChips(
                selected = section,
                counts = chipCounts,
                onSelect = selectSection,
            )
            Spacer(Modifier.height(4.dp))
            LibraryToolbar(
                sort = sort,
                descending = sortDescending,
                view = view,
                showViewToggle = gridSections,
                onSortChange = changeSort,
                onToggleDirection = toggleDirection,
                onViewChange = changeView,
            )
            Spacer(Modifier.height(4.dp))

            when {
                section == LibrarySection.ALL && searching -> LibrarySearchResults(
                    query = normalizedQuery,
                    playlists = visiblePlaylists,
                    playlistCovers = playlistCovers,
                    albums = visibleAlbums,
                    artists = visibleArtists,
                    tracks = visibleTracks,
                    favorites = favorites,
                    player = player,
                    navigate = navigate,
                    bottomPadding = bottomPadding,
                    ratingIndex = ratingIndex,
                    onLikeTrack = onLikeTrack,
                    onDislikeTrack = onDislikeTrack,
                    onPlaylistLongClick = { playlistMenu = it },
                    onAlbumLongClick = { albumMenu = it },
                    onArtistLongClick = { artistMenu = it },
                    onClickTrack = { vm.playOrOpen(it) },
                    onLongClickTrack = { actionTrack = it },
                    onFavoriteTrack = { vm.toggleFavorite(it.id) },
                )
                section == LibrarySection.ALL -> AllTab(
                    playlists = visiblePlaylists,
                    playlistCovers = playlistCovers,
                    albums = visibleAlbums,
                    artists = visibleArtists,
                    recentlyPlayed = recentlyPlayed,
                    favoriteTracks = visibleFavorites,
                    downloadedTracks = visibleDownloads,
                    tracksPreview = tracksPreview,
                    favorites = favorites,
                    player = player,
                    scanning = scanning,
                    bottomPadding = bottomPadding,
                    ratingIndex = ratingIndex,
                    onLikeTrack = onLikeTrack,
                    onDislikeTrack = onDislikeTrack,
                    onSelectSection = selectSection,
                    onOpenHistory = { navigate(Routes.HISTORY) },
                    onPlayAllLibrary = { vm.playLibraryShuffled() },
                    onCreatePlaylist = { showCreateDialog = true },
                    navigate = navigate,
                    onPlaylistLongClick = { playlistMenu = it },
                    onPlayPlaylist = { vm.playPlaylist(it) },
                    onAlbumLongClick = { albumMenu = it },
                    onPlayAlbum = { playTracks(vm.tracksByIds(it.trackIds)) },
                    onArtistLongClick = { artistMenu = it },
                    onPlayArtist = { playTracks(vm.tracksByIds(it.trackIds)) },
                    onClickTrack = { vm.playOrOpen(it) },
                    onLongClickTrack = { actionTrack = it },
                    onFavoriteTrack = { vm.toggleFavorite(it.id) },
                    onPlayTracks = playTracks,
                )
                section == LibrarySection.PLAYLISTS -> PlaylistsTab(
                    playlists = visiblePlaylists,
                    playlistCovers = playlistCovers,
                    view = view,
                    bottomPadding = bottomPadding,
                    onCreatePlaylist = { showCreateDialog = true },
                    onLongClick = { playlistMenu = it },
                    onPlay = { vm.playPlaylist(it) },
                    navigate = navigate,
                )
                section == LibrarySection.ALBUMS -> AlbumsTab(
                    albums = visibleAlbums,
                    view = view,
                    navigate = navigate,
                    bottomPadding = bottomPadding,
                    onPlay = { playTracks(vm.tracksByIds(it.trackIds)) },
                    onLongClick = { albumMenu = it },
                )
                section == LibrarySection.ARTISTS -> ArtistsTab(
                    artists = visibleArtists,
                    view = view,
                    navigate = navigate,
                    bottomPadding = bottomPadding,
                    onPlay = { playTracks(vm.tracksByIds(it.trackIds)) },
                    onLongClick = { artistMenu = it },
                )
                section == LibrarySection.TRACKS -> TracksTab(
                    tracks = visibleTracks,
                    favorites = favorites,
                    player = player,
                    filter = filter,
                    scanning = scanning,
                    ratingIndex = ratingIndex,
                    onLikeTrack = onLikeTrack,
                    onDislikeTrack = onDislikeTrack,
                    onFilterChange = changeFilter,
                    onClick = { vm.playOrOpen(it) },
                    onLongClick = { actionTrack = it },
                    onFavorite = { vm.toggleFavorite(it.id) },
                    bottomPadding = bottomPadding,
                )
                section == LibrarySection.DOWNLOADS -> DownloadsTab(
                    tracks = visibleDownloads,
                    favorites = favorites,
                    player = player,
                    saveStatesFlow = vm.saveStates,
                    ratingIndex = ratingIndex,
                    onLikeTrack = onLikeTrack,
                    onDislikeTrack = onDislikeTrack,
                    onPlayAll = { playTracks(downloadedTracks) },
                    onClick = { vm.playOrOpen(it) },
                    onLongClick = { actionTrack = it },
                    onFavorite = { vm.toggleFavorite(it.id) },
                    bottomPadding = bottomPadding,
                )
                section == LibrarySection.FAVORITES -> FavoritesTab(
                    tracks = visibleFavorites,
                    favorites = favorites,
                    player = player,
                    ratingIndex = ratingIndex,
                    onLikeTrack = onLikeTrack,
                    onDislikeTrack = onDislikeTrack,
                    onPlayAll = { playTracks(favoriteTracks) },
                    onClick = { vm.playOrOpen(it) },
                    onLongClick = { actionTrack = it },
                    onFavorite = { vm.toggleFavorite(it.id) },
                    bottomPadding = bottomPadding,
                )
                section == LibrarySection.YOUTUBE -> YouTubeTab(
                    tracks = visibleYouTube,
                    favorites = favorites,
                    player = player,
                    ratingIndex = ratingIndex,
                    onLikeTrack = onLikeTrack,
                    onDislikeTrack = onDislikeTrack,
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
                rating = ratingIndex.ratingOf(track),
                onLike = {
                    actionTrack = null
                    vm.toggleTrackLike(track)
                },
                onDislike = {
                    actionTrack = null
                    vm.toggleTrackDislike(track)
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
                    playTracks(vm.tracksByIds(album.trackIds))
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
                    playTracks(vm.tracksByIds(artist.trackIds))
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
 * Шапка медиатеки: огромное «Медиатека», строка-сводка и компактные действия —
 * поиск по библиотеке, сканирование фонотеки и аватар-«профиль» (настройки).
 */
@Composable
private fun LibraryHeader(
    trackCount: Int,
    downloadedCount: Int,
    playlistCount: Int,
    favoriteCount: Int,
    scanning: Boolean,
    searchOpen: Boolean,
    onSearchClick: () -> Unit,
    onScan: () -> Unit,
    onAvatarClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 6.dp, top = 10.dp, bottom = 2.dp),
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
                    append(tracksLabel(trackCount))
                    append(" на устройстве")
                    if (downloadedCount > 0) append(" · загружено: $downloadedCount")
                    if (playlistCount > 0) append(" · плейлистов: $playlistCount")
                    if (favoriteCount > 0) append(" · любимых: $favoriteCount")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onSearchClick) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = if (searchOpen) "Закрыть поиск по медиатеке" else "Поиск по медиатеке",
                tint = if (searchOpen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
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

/** Встроенное поле поиска по медиатеке — раскрывается иконкой в шапке. */
@Composable
private fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        placeholder = { Text("Поиск в медиатеке") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = {
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Закрыть поиск",
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
    )
}

/** Горизонтальные фильтры-чипы в духе Spotify; у выбранного — акцент и счётчик. */
@Composable
private fun LibraryChips(
    selected: LibrarySection,
    counts: Map<LibrarySection, Int>,
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
            Row(
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
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
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
                Spacer(Modifier.width(6.dp))
                Text(
                    text = (counts[section] ?: 0).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Панель инструментов: сортировка с направлением и переключатель вида
 * «список/сетка» (для разделов с обложками). Состояние сохраняется в prefs.
 */
@Composable
private fun LibraryToolbar(
    sort: LibrarySort,
    descending: Boolean,
    view: LibraryView,
    showViewToggle: Boolean,
    onSortChange: (LibrarySort) -> Unit,
    onToggleDirection: () -> Unit,
    onViewChange: (LibraryView) -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        IconButton(onClick = onToggleDirection, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (descending) {
                    Icons.Rounded.ArrowDownward
                } else {
                    Icons.Rounded.ArrowUpward
                },
                contentDescription = if (descending) {
                    "Сначала новые и большие"
                } else {
                    "Сначала старые и меньшие"
                },
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (showViewToggle) {
            ViewModeButton(
                icon = Icons.Rounded.GridView,
                selected = view == LibraryView.GRID,
                description = "Показать сеткой",
                onClick = { onViewChange(LibraryView.GRID) },
            )
            ViewModeButton(
                icon = Icons.Rounded.ViewList,
                selected = view == LibraryView.LIST,
                description = "Показать списком",
                onClick = { onViewChange(LibraryView.LIST) },
            )
        }
    }
}

@Composable
private fun ViewModeButton(
    icon: ImageVector,
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * «Все»: сводка медиатеки рельсами, как домашняя страница библиотеки Spotify.
 * Сверху — закреплённый ряд (любимые, загруженные, история), затем полки
 * «Недавно прослушано», «Загруженные», «Мои плейлисты», альбомы и исполнители.
 */
@Composable
private fun AllTab(
    playlists: List<Playlist>,
    playlistCovers: Map<String, List<String>>,
    albums: List<Album>,
    artists: List<Artist>,
    recentlyPlayed: List<MainViewModel.HistoryTrackEntry>,
    favoriteTracks: List<Track>,
    downloadedTracks: List<Track>,
    tracksPreview: List<Track>,
    favorites: Set<String>,
    player: PlayerUiState,
    scanning: Boolean,
    bottomPadding: Dp,
    onSelectSection: (LibrarySection) -> Unit,
    onOpenHistory: () -> Unit,
    onPlayAllLibrary: () -> Unit,
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
    onPlayTracks: (List<Track>) -> Unit,
    ratingIndex: TrackRatingIndex,
    onLikeTrack: (Track) -> Unit,
    onDislikeTrack: (Track) -> Unit,
) {
    // Срезы для рельсов считаем один раз на список, а не на каждую перерисовку.
    val featuredPlaylists = remember(playlists) { playlists.take(10) }
    val featuredAlbums = remember(albums) { albums.take(10) }
    val featuredArtists = remember(artists) { artists.take(10) }
    val featuredRecent = remember(recentlyPlayed) { recentlyPlayed.take(14) }
    val featuredDownloads = remember(downloadedTracks) { downloadedTracks.take(10) }
    val everythingEmpty = playlists.isEmpty() && albums.isEmpty() && artists.isEmpty() &&
        recentlyPlayed.isEmpty() && favoriteTracks.isEmpty() && downloadedTracks.isEmpty() &&
        tracksPreview.isEmpty()

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
        item(key = "all-pinned") {
            PinnedRail(
                favoriteCount = favoriteTracks.size,
                downloadedCount = downloadedTracks.size,
                onSelectSection = onSelectSection,
                onOpenHistory = onOpenHistory,
                onPlayAllLibrary = onPlayAllLibrary,
            )
        }
        if (scanning && everythingEmpty) {
            item(key = "all-loading") { LoadingRow("Сканируем фонотеку…") }
        }
        if (everythingEmpty && !scanning) {
            item(key = "all-empty") {
                EmptyState(
                    icon = Icons.Rounded.LibraryMusic,
                    title = "Фонотека пуста",
                    subtitle = "Нажмите «Сканировать» в шапке — найдём аудиофайлы на устройстве. " +
                        "Или создайте первый плейлист.",
                )
            }
        }

        // «Недавно прослушано» — как Recently played в Spotify.
        item(key = "all-recent-header") {
            SectionHeader(
                title = "Недавно прослушано",
                subtitle = if (featuredRecent.isEmpty()) "Здесь появятся прослушанные треки" else null,
                actionLabel = if (featuredRecent.isNotEmpty()) "История" else null,
                onAction = onOpenHistory,
            )
        }
        if (featuredRecent.isNotEmpty()) {
            item(key = "all-recent-rail") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(featuredRecent, key = { "all-recent-${it.track.id}" }) { entry ->
                        RecentTrackCard(
                            track = entry.track,
                            onClick = { onClickTrack(entry.track) },
                            onLongClick = { onLongClickTrack(entry.track) },
                            modifier = Modifier.width(136.dp),
                        )
                    }
                }
            }
        }

        // «Любимые треки» — аналог Liked Songs, ведёт в раздел «Любимые».
        item(key = "all-liked") {
            LikedSongsCard(
                count = favoriteTracks.size,
                onPlay = { onPlayTracks(favoriteTracks) },
                onClick = { onSelectSection(LibrarySection.FAVORITES) },
            )
        }

        // «Загруженные» — локальные копии, сохранённые из стриминга.
        item(key = "all-downloads-header") {
            SectionHeader(
                title = "Загруженные",
                subtitle = if (featuredDownloads.isEmpty()) {
                    "Скачанные треки играют без интернета"
                } else {
                    tracksLabel(downloadedTracks.size) + " · доступны офлайн"
                },
                actionLabel = if (featuredDownloads.isNotEmpty()) "Все" else null,
                onAction = { onSelectSection(LibrarySection.DOWNLOADS) },
            )
        }
        if (featuredDownloads.isNotEmpty()) {
            item(key = "all-downloads-rail") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(featuredDownloads, key = { "all-dl-${it.id}" }) { track ->
                        RecentTrackCard(
                            track = track,
                            onClick = { onClickTrack(track) },
                            onLongClick = { onLongClickTrack(track) },
                            modifier = Modifier.width(136.dp),
                        )
                    }
                }
            }
        }

        // «Мои плейлисты» — главная полка библиотеки.
        item(key = "all-playlists-header") {
            SectionHeader(
                title = "Мои плейлисты",
                subtitle = if (playlists.isEmpty()) {
                    "Создайте свой первый плейлист"
                } else {
                    tracksLabel(playlists.size)
                },
                actionLabel = if (playlists.isNotEmpty()) "Все" else null,
                onAction = { onSelectSection(LibrarySection.PLAYLISTS) },
            )
        }
        item(key = "all-playlists-rail") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (featuredPlaylists.isEmpty()) {
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

        if (featuredAlbums.isNotEmpty()) {
            item(key = "all-albums-header") {
                SectionHeader(
                    title = "Альбомы",
                    subtitle = tracksLabel(albums.size),
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
        if (featuredArtists.isNotEmpty()) {
            item(key = "all-artists-header") {
                SectionHeader(
                    title = "Исполнители",
                    subtitle = tracksLabel(artists.size),
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

        if (tracksPreview.isNotEmpty()) {
            item(key = "all-tracks-header") {
                SectionHeader(
                    title = "Треки",
                    subtitle = "На устройстве",
                    actionLabel = "Все треки",
                    onAction = { onSelectSection(LibrarySection.TRACKS) },
                )
            }
            items(
                items = tracksPreview,
                key = { "all-track-${it.id}" },
                contentType = { "track" },
            ) { track ->
                TrackRow(
                    track = track,
                    isFavorite = track.id in favorites,
                    isPlaying = player.current?.id == track.id && player.isPlaying,
                    onClick = { onClickTrack(track) },
                    onLongClick = { onLongClickTrack(track) },
                    onFavorite = { onFavoriteTrack(track) },
                    rating = ratingIndex.ratingOf(track),
                    onLike = { onLikeTrack(track) },
                    onDislike = { onDislikeTrack(track) },
                )
            }
        }
    }
}

/** Закреплённый ряд: быстрый доступ к любимым, загрузкам и истории прослушиваний. */
@Composable
private fun PinnedRail(
    favoriteCount: Int,
    downloadedCount: Int,
    onSelectSection: (LibrarySection) -> Unit,
    onOpenHistory: () -> Unit,
    onPlayAllLibrary: () -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "pinned-play-all") {
            QuickAccessTile(
                icon = Icons.Rounded.Shuffle,
                title = "Слушать всё",
                subtitle = "Вся медиатека вперемешку",
                onClick = onPlayAllLibrary,
                modifier = Modifier.width(224.dp),
            )
        }
        item(key = "pinned-liked") {
            QuickAccessTile(
                icon = Icons.Rounded.Favorite,
                title = "Любимые треки",
                subtitle = tracksLabel(favoriteCount),
                onClick = { onSelectSection(LibrarySection.FAVORITES) },
                modifier = Modifier.width(224.dp),
            )
        }
        item(key = "pinned-downloads") {
            QuickAccessTile(
                icon = Icons.Rounded.Download,
                title = "Загруженные",
                subtitle = tracksLabel(downloadedCount),
                onClick = { onSelectSection(LibrarySection.DOWNLOADS) },
                modifier = Modifier.width(224.dp),
            )
        }
        item(key = "pinned-history") {
            QuickAccessTile(
                icon = Icons.Rounded.History,
                title = "Недавно прослушано",
                subtitle = "История и статистика",
                onClick = onOpenHistory,
                modifier = Modifier.width(224.dp),
            )
        }
    }
}

/** Строка загрузки/пустого состояния с индикатором прогресса. */
@Composable
private fun LoadingRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Строка «Создать плейлист» для спискового режима раздела «Плейлисты». */
@Composable
private fun CreatePlaylistRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.PlaylistAdd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Создать плейлист", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Новый список",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Плейлисты: сетка карточек или компактный список строк, как в Spotify. */
@Composable
private fun PlaylistsTab(
    playlists: List<Playlist>,
    playlistCovers: Map<String, List<String>>,
    view: LibraryView,
    navigate: (String) -> Unit,
    bottomPadding: Dp,
    onCreatePlaylist: () -> Unit,
    onLongClick: (Playlist) -> Unit,
    onPlay: (Playlist) -> Unit,
) {
    if (view == LibraryView.LIST) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 8.dp,
                end = 8.dp,
                top = 4.dp,
                bottom = bottomPadding + 24.dp,
            ),
        ) {
            item(key = "create-playlist-row") { CreatePlaylistRow(onCreatePlaylist) }
            if (playlists.isEmpty()) {
                item(key = "playlists-empty") {
                    EmptyState(
                        icon = Icons.Rounded.PlaylistPlay,
                        title = "Плейлистов нет",
                        subtitle = "Создайте свой или синхронизируйте аккаунт в разделе «Подключения».",
                    )
                }
            } else {
                items(playlists, key = { it.id }, contentType = { "playlist" }) { playlist ->
                    PlaylistListRow(
                        playlist = playlist,
                        coverUrls = playlistCovers[playlist.id].orEmpty(),
                        onClick = { navigate(Routes.playlist(playlist.id)) },
                        onLongClick = { onLongClick(playlist) },
                        onMoreClick = { onLongClick(playlist) },
                    )
                }
            }
        }
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

/** Альбомы: сетка обложек или строки с overflow-меню. */
@Composable
private fun AlbumsTab(
    albums: List<Album>,
    view: LibraryView,
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
    if (view == LibraryView.LIST) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 8.dp,
                end = 8.dp,
                top = 4.dp,
                bottom = bottomPadding + 24.dp,
            ),
        ) {
            items(albums, key = { "${it.name}|${it.artist}" }, contentType = { "album" }) { album ->
                AlbumListRow(
                    album = album,
                    onClick = { navigate(Routes.album(album.name, album.artist)) },
                    onLongClick = { onLongClick(album) },
                    onMoreClick = { onLongClick(album) },
                )
            }
        }
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

/** Исполнители: сетка круглых аватаров или строки с overflow-меню. */
@Composable
private fun ArtistsTab(
    artists: List<Artist>,
    view: LibraryView,
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
    if (view == LibraryView.LIST) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 8.dp,
                end = 8.dp,
                top = 4.dp,
                bottom = bottomPadding + 24.dp,
            ),
        ) {
            items(artists, key = { it.name }, contentType = { "artist" }) { artist ->
                ArtistListRow(
                    artist = artist,
                    onClick = { navigate(Routes.artist(artist.name)) },
                    onLongClick = { onLongClick(artist) },
                    onMoreClick = { onLongClick(artist) },
                )
            }
        }
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

/** Треки на устройстве: фильтры состава, счётчик и плотный список. */
@Composable
private fun TracksTab(
    tracks: List<Track>,
    favorites: Set<String>,
    player: PlayerUiState,
    filter: LibraryFilter,
    scanning: Boolean,
    onFilterChange: (LibraryFilter) -> Unit,
    onClick: (Track) -> Unit,
    onLongClick: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    bottomPadding: Dp,
    ratingIndex: TrackRatingIndex,
    onLikeTrack: (Track) -> Unit,
    onDislikeTrack: (Track) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = bottomPadding + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "tracks-controls") {
            Column {
                Text(
                    text = tracksLabel(tracks.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
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
        when {
            scanning && tracks.isEmpty() -> item(key = "tracks-loading") {
                LoadingRow("Сканируем фонотеку…")
            }
            tracks.isEmpty() -> item(key = "tracks-empty") {
                EmptyState(
                    icon = Icons.Rounded.LibraryMusic,
                    title = when (filter) {
                        LibraryFilter.STREAMING -> "Нет сохранённых треков"
                        LibraryFilter.DEVICE -> "Файлов устройства нет"
                        LibraryFilter.ALL -> "Треков нет"
                    },
                    subtitle = when (filter) {
                        LibraryFilter.STREAMING ->
                            "Нажмите кнопку загрузки у онлайн-трека — он сохранится сюда, " +
                                "рядом с музыкой на устройстве."
                        LibraryFilter.DEVICE ->
                            "Нажмите «Сканировать» в шапке, чтобы найти музыку на устройстве."
                        LibraryFilter.ALL ->
                            "Нажмите «Сканировать» в шапке — найдём аудиофайлы на устройстве."
                    },
                )
            }
            else -> items(tracks, key = { it.id }, contentType = { "track" }) { track ->
                TrackRow(
                    track = track,
                    isFavorite = track.id in favorites,
                    isPlaying = player.current?.id == track.id && player.isPlaying,
                    onClick = { onClick(track) },
                    onLongClick = { onLongClick(track) },
                    onFavorite = { onFavorite(track) },
                    rating = ratingIndex.ratingOf(track),
                    onLike = { onLikeTrack(track) },
                    onDislike = { onDislikeTrack(track) },
                )
            }
        }
    }
}

/** «Любимые» (liked songs): крупный Play и тот же плотный ритм строк. */
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
    ratingIndex: TrackRatingIndex,
    onLikeTrack: (Track) -> Unit,
    onDislikeTrack: (Track) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
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
                        contentDescription = "Играть все любимые треки",
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
                    rating = ratingIndex.ratingOf(track),
                    onLike = { onLikeTrack(track) },
                    onDislike = { onDislikeTrack(track) },
                )
            }
        }
    }
}

/**
 * «Загрузки»: треки, сохранённые из стриминга для офлайна. Прогресс активных
 * сохранений читается только внутри этого раздела, чтобы проценты загрузок
 * не перерисовывали всю медиатеку.
 */
@Composable
private fun DownloadsTab(
    tracks: List<Track>,
    favorites: Set<String>,
    player: PlayerUiState,
    saveStatesFlow: StateFlow<Map<String, TrackSaveState>>,
    onPlayAll: () -> Unit,
    onClick: (Track) -> Unit,
    onLongClick: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    bottomPadding: Dp,
    ratingIndex: TrackRatingIndex,
    onLikeTrack: (Track) -> Unit,
    onDislikeTrack: (Track) -> Unit,
) {
    val saveStates by saveStatesFlow.collectAsState()
    val activeSaves = remember(saveStates) {
        saveStates.values.count { it.status == SaveStatus.QUEUED || it.status == SaveStatus.DOWNLOADING }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = bottomPadding + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "downloads-header") {
            Column(Modifier.padding(bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (tracks.isNotEmpty()) {
                        CircularPlayButton(
                            onClick = onPlayAll,
                            size = 52.dp,
                            contentDescription = "Играть все загруженные треки",
                        )
                        Spacer(Modifier.width(14.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = tracksLabel(tracks.size),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (activeSaves > 0) {
                                "Сохраняем сейчас: $activeSaves · доступны без интернета"
                            } else {
                                "Сохранено из стриминга · доступны без интернета"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (tracks.isEmpty()) {
            item(key = "downloads-empty") {
                EmptyState(
                    icon = Icons.Rounded.Download,
                    title = "Загрузок нет",
                    subtitle = "Откройте онлайн-трек, нажмите «Сохранить на устройство» — " +
                        "он появится здесь и будет играть офлайн.",
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
                    rating = ratingIndex.ratingOf(track),
                    onLike = { onLikeTrack(track) },
                    onDislike = { onDislikeTrack(track) },
                )
            }
        }
    }
}

/**
 * Результаты встроенного поиска в разделе «Все»: плейлисты, альбомы,
 * исполнители и треки, сгруппированные по типу — как поиск Spotify.
 */
@Composable
private fun LibrarySearchResults(
    query: String,
    playlists: List<Playlist>,
    playlistCovers: Map<String, List<String>>,
    albums: List<Album>,
    artists: List<Artist>,
    tracks: List<Track>,
    favorites: Set<String>,
    player: PlayerUiState,
    navigate: (String) -> Unit,
    bottomPadding: Dp,
    onPlaylistLongClick: (Playlist) -> Unit,
    onAlbumLongClick: (Album) -> Unit,
    onArtistLongClick: (Artist) -> Unit,
    onClickTrack: (Track) -> Unit,
    onLongClickTrack: (Track) -> Unit,
    onFavoriteTrack: (Track) -> Unit,
    ratingIndex: TrackRatingIndex,
    onLikeTrack: (Track) -> Unit,
    onDislikeTrack: (Track) -> Unit,
) {
    val hasResults = playlists.isNotEmpty() || albums.isNotEmpty() ||
        artists.isNotEmpty() || tracks.isNotEmpty()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 4.dp,
            bottom = bottomPadding + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "search-summary") {
            Text(
                text = "Результаты по запросу «$query»",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        if (!hasResults) {
            item(key = "search-empty") {
                EmptyState(
                    icon = Icons.Rounded.Search,
                    title = "Ничего не найдено",
                    subtitle = "Поиск идёт по названиям, исполнителям и альбомам. Попробуйте другой запрос.",
                )
            }
        }
        if (playlists.isNotEmpty()) {
            item(key = "search-playlists-header") {
                SectionHeader(title = "Плейлисты", subtitle = tracksLabel(playlists.size))
            }
            items(playlists, key = { "search-pl-${it.id}" }, contentType = { "playlist" }) { playlist ->
                PlaylistListRow(
                    playlist = playlist,
                    coverUrls = playlistCovers[playlist.id].orEmpty(),
                    onClick = { navigate(Routes.playlist(playlist.id)) },
                    onLongClick = { onPlaylistLongClick(playlist) },
                    onMoreClick = { onPlaylistLongClick(playlist) },
                )
            }
        }
        if (albums.isNotEmpty()) {
            item(key = "search-albums-header") {
                SectionHeader(title = "Альбомы", subtitle = tracksLabel(albums.size))
            }
            items(albums, key = { "search-alb-${it.name}|${it.artist}" }, contentType = { "album" }) { album ->
                AlbumListRow(
                    album = album,
                    onClick = { navigate(Routes.album(album.name, album.artist)) },
                    onLongClick = { onAlbumLongClick(album) },
                    onMoreClick = { onAlbumLongClick(album) },
                )
            }
        }
        if (artists.isNotEmpty()) {
            item(key = "search-artists-header") {
                SectionHeader(title = "Исполнители", subtitle = tracksLabel(artists.size))
            }
            items(artists, key = { "search-art-${it.name}" }, contentType = { "artist" }) { artist ->
                ArtistListRow(
                    artist = artist,
                    onClick = { navigate(Routes.artist(artist.name)) },
                    onLongClick = { onArtistLongClick(artist) },
                    onMoreClick = { onArtistLongClick(artist) },
                )
            }
        }
        if (tracks.isNotEmpty()) {
            item(key = "search-tracks-header") {
                SectionHeader(title = "Треки", subtitle = tracksLabel(tracks.size))
            }
            items(tracks, key = { "search-track-${it.id}" }, contentType = { "track" }) { track ->
                TrackRow(
                    track = track,
                    isFavorite = track.id in favorites,
                    isPlaying = player.current?.id == track.id && player.isPlaying,
                    onClick = { onClickTrack(track) },
                    onLongClick = { onLongClickTrack(track) },
                    onFavorite = { onFavoriteTrack(track) },
                    rating = ratingIndex.ratingOf(track),
                    onLike = { onLikeTrack(track) },
                    onDislike = { onDislikeTrack(track) },
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
    ratingIndex: TrackRatingIndex,
    onLikeTrack: (Track) -> Unit,
    onDislikeTrack: (Track) -> Unit,
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
                    rating = ratingIndex.ratingOf(track),
                    onLike = { onLikeTrack(track) },
                    onDislike = { onDislikeTrack(track) },
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

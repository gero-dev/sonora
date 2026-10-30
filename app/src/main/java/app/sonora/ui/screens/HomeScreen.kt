package app.sonora.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonora.core.model.Playlist
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.ui.MainViewModel
import app.sonora.ui.WaveMood
import app.sonora.ui.WaveSort
import app.sonora.ui.WaveSourceMode
import app.sonora.ui.WaveUiState
import app.sonora.ui.WaveStatus
import app.sonora.ui.components.CoverArt
import app.sonora.ui.components.CoverArtBox
import app.sonora.ui.components.CreatePlaylistDialog
import app.sonora.ui.components.EmptyState
import app.sonora.ui.components.TrackActionSheet
import app.sonora.ui.components.TrackRow
import app.sonora.ui.nav.Routes
import app.sonora.ui.theme.LocalSonoraColors
import java.time.LocalTime

/** Единый шаг страницы: 16dp по краям и 12dp между блоками. */
private val PagePadding = 16.dp
private val SectionGap = 12.dp
private val RailSpacing = 12.dp
private val CardRadius = 16.dp
private val TileWidth = 152.dp
private val ArtworkRadius = 12.dp

/**
 * Главный экран: приветствие, «Моя волна», быстрые карточки действий,
 * недавние треки, плейлисты и избранное. Вся логика — в [MainViewModel],
 * экран только раскладывает состояние и вызывает те же действия, что и раньше.
 */
@Composable
fun HomeScreen(vm: MainViewModel, padding: PaddingValues, navigate: (String) -> Unit) {
    val tracks by vm.tracks.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState()
    // Позиция здесь не показывается: берём состояние без positionMs, чтобы
    // не пересобирать Главную на каждый тик плеера (500 мс).
    val player by vm.playerChrome.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val wave by vm.wave.collectAsState()
    val fileImport by vm.fileImport.collectAsState()
    val settings by vm.settings.collectAsState()
    val waveHistorySize by vm.waveHistorySize.collectAsState()
    val waveDisliked by vm.waveDisliked.collectAsState()

    var showImportGuide by remember { mutableStateOf(false) }
    var showWaveSettings by remember { mutableStateOf(false) }
    val musicImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) vm.importMusicFiles(uris)
    }

    val localTracks = remember(tracks) { tracks.filter { it.source == Source.LOCAL } }
    val recent = remember(localTracks) { localTracks.take(15) }
    val favoriteTracks = remember(tracks, favorites) { tracks.filter { it.id in favorites } }
    val favoritePreview = remember(favoriteTracks) { favoriteTracks.take(10) }
    val ownPlaylists = remember(playlists) { playlists.filter { it.editable } }

    var showCreateDialog by remember { mutableStateOf(false) }
    var actionTrack by remember { mutableStateOf<Track?>(null) }
    var playlistForNewTrack by remember { mutableStateOf<Track?>(null) }

    val favoriteAction: (() -> Unit)? = if (favoriteTracks.isEmpty()) {
        null
    } else {
        { vm.playQueue(favoriteTracks) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = PagePadding,
                end = PagePadding,
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { HomeGreeting(localCount = localTracks.size, onAccount = { navigate(Routes.GOOGLE_LOGIN) }) }
            item {
                WaveCard(
                    state = wave,
                    onPlay = { vm.playWave() },
                    onEnqueue = { vm.enqueueWave() },
                    onRefresh = { vm.refreshWave() },
                    onSource = vm::setWaveSource,
                    onToggleFavorites = { vm.setWaveFavoritesOnly(!wave.filters.favoritesOnly) },
                    onMood = vm::setWaveMood,
                    onSort = vm::setWaveSort,
                    onResetFilters = { vm.resetWaveFilters() },
                    onTrackClick = { track ->
                        // Клик по плитке играет волну с этого трека: очередь
                        // собирается из всех волновых треков, поэтому next и
                        // автопереход работают даже в стриминговой библиотеке.
                        val index = wave.tracks.indexOfFirst { it.id == track.id }
                        if (index >= 0) vm.playQueue(wave.tracks, index) else vm.playOrOpen(track)
                    },
                    onLoadMore = { vm.loadMoreWave() },
                    onSettings = { showWaveSettings = true },
                    onDislike = { vm.dislikeWaveTrack(it) },
                    onRadio = { vm.startTrackRadio(it) },
                )
            }

            if (localTracks.isEmpty()) {
                item { ScanLibraryCard(scanning = scanning, onScan = { vm.scanLibrary() }) }
            }
            item {
                QuickAccessRow(
                    onCatalog = { navigate(Routes.DISCOVER) },
                    onSearch = { navigate(Routes.SEARCH) },
                )
            }

            item {
                ImportMusicCard(
                    importing = fileImport.importing,
                    imported = fileImport.imported,
                    failed = fileImport.failed,
                    done = fileImport.done,
                    onPickFiles = { musicImportLauncher.launch(arrayOf("*/*")) },
                    onShowGuide = { showImportGuide = true },
                )
            }

            if (recent.isNotEmpty()) {
                item { HomeSectionHeader(title = "Недавно добавленные") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(RailSpacing)) {
                        items(recent, key = { "recent-${it.id}" }) { track ->
                            TrackTile(track = track, onClick = { vm.playOrOpen(track) })
                        }
                    }
                }
            }

            item { HomeSectionHeader(title = "Плейлисты") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(RailSpacing)) {
                    item { CreatePlaylistTile(onClick = { showCreateDialog = true }) }
                    items(ownPlaylists, key = { "pl-${it.id}" }) { playlist ->
                        PlaylistTile(
                            playlist = playlist,
                            onClick = { navigate(Routes.playlist(playlist.id)) },
                        )
                    }
                }
            }

            item {
                HomeSectionHeader(
                    title = "Избранное",
                    actionLabel = if (favoriteAction != null) "Играть все" else null,
                    onAction = favoriteAction,
                )
            }
            if (favoriteTracks.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.FavoriteBorder,
                        title = "Пока пусто",
                        subtitle = "Отмечайте треки сердечком — они появятся здесь.",
                    )
                }
            } else {
                items(favoritePreview, key = { "fav-${it.id}" }) { track ->
                    TrackRow(
                        track = track,
                        isFavorite = track.id in favorites,
                        isPlaying = player.current?.id == track.id && player.isPlaying,
                        onClick = { vm.playOrOpen(track) },
                        onLongClick = { actionTrack = track },
                        onFavorite = { vm.toggleFavorite(track.id) },
                    )
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

        if (showCreateDialog) {
            CreatePlaylistDialog(
                onDismiss = { showCreateDialog = false },
                onCreate = { name ->
                    showCreateDialog = false
                    vm.createPlaylist(name) { created -> navigate(Routes.playlist(created.id)) }
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

        if (showWaveSettings) {
            app.sonora.ui.screens.WaveSettingsSheet(
                settings = settings.wave,
                historySize = waveHistorySize,
                dislikedCount = waveDisliked.size,
                onDismiss = { showWaveSettings = false },
                onApply = { draft ->
                    vm.updateWaveSettings { _ -> draft }
                    showWaveSettings = false
                },
                onClearHistory = { vm.clearWaveHistory() },
                onClearDislikes = { vm.clearWaveDislikes() },
            )
        }

        if (showImportGuide) {
            ImportGuideDialog(
                onDismiss = { showImportGuide = false },
                onPickFiles = {
                    showImportGuide = false
                    musicImportLauncher.launch(arrayOf("*/*"))
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Заголовки и приветствие
// ---------------------------------------------------------------------------

/** Заголовок секции: 20sp semibold, отступ 12dp сверху, действие справа. */
@Composable
private fun HomeSectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = SectionGap, bottom = 2.dp),
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
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Приветствие по времени суток + краткая сводка по фонотеке. */
@Composable
private fun HomeGreeting(localCount: Int, onAccount: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "SONORA",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = greetingForNow(),
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
            // Вход в Google-аккаунт всегда под рукой — в правом верхнем углу.
            IconButton(onClick = onAccount) {
                Icon(
                    imageVector = Icons.Rounded.AccountCircle,
                    contentDescription = "Google-аккаунт",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (localCount == 0) {
                "Фонотека пока пуста — начнём со сканирования устройства."
            } else {
                "Треков на устройстве: $localCount · всё ваше звучит здесь"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun tracksLabel(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val word = when {
        mod100 in 11..14 -> "треков"
        mod10 == 1 -> "трек"
        mod10 in 2..4 -> "трека"
        else -> "треков"
    }
    return "$count $word"
}

private fun greetingForNow(): String {
    val hour = LocalTime.now().hour
    return when (hour) {
        in 5..11 -> "Доброе утро"
        in 12..17 -> "Добрый день"
        in 18..22 -> "Добрый вечер"
        else -> "Доброй ночи"
    }
}

// ---------------------------------------------------------------------------
// Карточки действий
// ---------------------------------------------------------------------------

/** Большая градиентная карточка первичного действия — сканирование фонотеки. */
@Composable
private fun ScanLibraryCard(scanning: Boolean, onScan: () -> Unit) {
    val gradient = LocalSonoraColors.current.gradient
    val brush = remember(gradient) { Brush.linearGradient(gradient) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(brush)
            .padding(20.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Своя музыка — в один тап",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Найдём аудиофайлы на устройстве: MP3, FLAC, Opus и другие форматы.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.88f),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onScan,
            enabled = !scanning,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color(0xFF1B1B1F),
            ),
        ) {
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF1B1B1F),
                )
                Spacer(Modifier.width(8.dp))
                Text("Сканируем…")
            } else {
                Text("Сканировать фонотеку")
            }
        }
    }
}

/**
 * Две компактные карточки вместо больших промо-блоков: «Обзор» открытых
 * каталогов и поиск по стримингу. Меньше визуального шума на Главной.
 */
@Composable
private fun QuickAccessRow(onCatalog: () -> Unit, onSearch: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QuickAccessTile(
            title = "Обзор",
            subtitle = "Свободная музыка",
            icon = Icons.Rounded.Explore,
            onClick = onCatalog,
            modifier = Modifier.weight(1f),
        )
        QuickAccessTile(
            title = "Стриминг",
            subtitle = "YouTube Music",
            icon = Icons.Rounded.PlayCircle,
            onClick = onSearch,
            modifier = Modifier.weight(1f),
            accent = true,
        )
    }
}

@Composable
private fun QuickAccessTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (accent) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (accent) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (accent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// «Моя волна»
// ---------------------------------------------------------------------------

@Composable
private fun WaveCard(
    state: WaveUiState,
    onPlay: () -> Unit,
    onEnqueue: () -> Unit,
    onRefresh: () -> Unit,
    onSource: (WaveSourceMode) -> Unit,
    onToggleFavorites: () -> Unit,
    onMood: (WaveMood) -> Unit,
    onSort: (WaveSort) -> Unit,
    onResetFilters: () -> Unit,
    onTrackClick: (Track) -> Unit,
    onLoadMore: () -> Unit,
    onSettings: () -> Unit,
    onDislike: (Track) -> Unit,
    onRadio: (Track) -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    // Градиент пересоздаём только при смене палитры, а не на каждой перерисовке.
    val waveStart = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
    val waveEnd = MaterialTheme.colorScheme.surfaceContainerHigh
    val waveBrush = remember(waveStart, waveEnd) {
        Brush.linearGradient(listOf(waveStart, waveEnd))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(waveBrush)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Моя волна", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = state.seedLabel.ifBlank { "Новая подборка каждый раз" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = "Настройки волны",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Обновить волну",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(WaveSourceMode.entries, key = { it.name }) { mode ->
                FilterChip(
                    selected = state.filters.source == mode,
                    onClick = { onSource(mode) },
                    label = { Text(mode.label) },
                )
            }
            item {
                FilterChip(
                    selected = state.filters.favoritesOnly,
                    onClick = onToggleFavorites,
                    label = { Text("Избранное") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(WaveMood.entries, key = { it.name }) { mood ->
                FilterChip(
                    selected = state.filters.mood == mood,
                    onClick = { onMood(mood) },
                    label = { Text(mood.label) },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(
                    onClick = { sortMenu = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sort,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Сортировка: ${state.sort.label}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    WaveSort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort.label) },
                            onClick = {
                                sortMenu = false
                                onSort(sort)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (state.loading) "Собираем…" else tracksLabel(state.tracks.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Активные фильтры и вклад ReccoBeats — одной строкой, без лишних перерисовок.
        val filterLine = remember(
            state.filterSummary,
            state.featuresUsed,
            state.filters.hasActiveFilter,
        ) {
            buildList {
                if (state.filters.hasActiveFilter && state.filterSummary.isNotBlank()) {
                    add(state.filterSummary)
                }
                if (state.featuresUsed > 0) add("ReccoBeats: ${state.featuresUsed}")
            }.joinToString(" · ")
        }
        if (filterLine.isNotBlank()) {
            Text(
                text = filterLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Пустая волна обязана объяснить причину: фильтр не «снимается» молча.
        val reason = state.error?.takeIf { it.isNotBlank() }
            ?: state.hint?.takeIf { state.showsEmptyReason }
        if (!reason.isNullOrBlank() && state.tracks.isEmpty() && !state.loading) {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            if (state.filters.hasActiveFilter && state.status != WaveStatus.ERROR) {
                TextButton(
                    onClick = onResetFilters,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
                ) {
                    Text("Сбросить фильтры")
                }
            }
        }

        if (state.tracks.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(RailSpacing)) {
                items(state.tracks, key = { "wave-${it.id}" }) { track ->
                    TrackTile(
                        track = track,
                        onClick = { onTrackClick(track) },
                        onDislike = { onDislike(track) },
                        onRadio = { onRadio(track) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onLoadMore,
                enabled = !state.loadingMore && !state.loading,
            ) {
                if (state.loadingMore) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text("Ещё рекомендации")
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Играть волну")
                }
                OutlinedButton(onClick = onEnqueue) {
                    Icon(
                        imageVector = Icons.Rounded.QueueMusic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("В очередь")
                }
            }
        } else if (!state.loading) {
            Text(
                text = "Добавьте треки в библиотеку или включите онлайн-источник.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Плитки горизонтальных подборок
// ---------------------------------------------------------------------------

/**
 * Карточка трека для подборок: 160dp, обложка 12dp, тональный фон без тени.
 * Текст живёт на том же тональном контейнере, поэтому тёмная тема выглядит ровно.
 * Долгое нажатие в «Моей волне» открывает «Радио по треку» и «Не интересно».
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackTile(
    track: Track,
    onClick: () -> Unit,
    onDislike: (() -> Unit)? = null,
    onRadio: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onDislike != null || onRadio != null
    Box {
        Column(
            modifier = Modifier
                .width(TileWidth)
                .clip(RoundedCornerShape(CardRadius))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
                )
                .padding(8.dp),
        ) {
            CoverArtBox(
                url = track.artworkUrl ?: track.artworkUri,
                cornerRadius = ArtworkRadius,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            Spacer(Modifier.height(10.dp))
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            Spacer(Modifier.height(6.dp))
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            if (onRadio != null) {
                DropdownMenuItem(
                    text = { Text("Радио по треку") },
                    leadingIcon = {
                        Icon(Icons.Rounded.Radio, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onRadio()
                    },
                )
            }
            if (onDislike != null) {
                DropdownMenuItem(
                    text = { Text("Не интересно") },
                    leadingIcon = {
                        Icon(Icons.Rounded.ThumbDown, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onDislike()
                    },
                )
            }
        }
    }
}

/** Плитка плейлиста в том же ритме, что и карточки треков. */
@Composable
private fun PlaylistTile(playlist: Playlist, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(TileWidth)
            .clip(RoundedCornerShape(CardRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        CoverArtBox(
            url = playlist.artworkUrl,
            cornerRadius = ArtworkRadius,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${playlist.trackIds.size} трек(ов)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Spacer(Modifier.height(6.dp))
    }
}

/** Плитка «создать плейлист» — тональная карточка с тонкой обводкой. */
@Composable
private fun CreatePlaylistTile(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(TileWidth)
            .clip(RoundedCornerShape(CardRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(CardRadius),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("Создать", style = MaterialTheme.typography.labelLarge)
        }
    }
}
// ---------------------------------------------------------------------------
// Импорт музыки на главном экране
// ---------------------------------------------------------------------------


/** Импорт своей музыки файлами + короткая инструкция. */
@Composable
private fun ImportMusicCard(
    importing: Boolean,
    imported: Int,
    failed: Int,
    done: Boolean,
    onPickFiles: () -> Unit,
    onShowGuide: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.UploadFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (done) {
                    "Импортировано: $imported" + if (failed > 0) " · ошибок: $failed" else ""
                } else {
                    "Своя музыка файлами"
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (done) {
                    "Файлы уже в медиатеке"
                } else {
                    "MP3, FLAC, M4A, OGG, OPUS"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onShowGuide) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = "Как импортировать музыку",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        TextButton(onClick = onPickFiles, enabled = !importing) {
            if (importing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text("Выбрать")
            }
        }
    }
}

/** Инструкция по импорту: что подготовить и как назвать файлы. */
@Composable
private fun ImportGuideDialog(onDismiss: () -> Unit, onPickFiles: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Как импортировать свою музыку") },
        text = {
            Column {
                Text("1. Подготовьте файлы", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Подойдут MP3, FLAC, M4A/AAC, OGG, OPUS, WAV. Файлы без DRM-защиты " +
                        "(скачанные из стриминговых сервисов не подойдут).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text("2. Назовите и заполните теги", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Идеально: «Исполнитель - Название.mp3» и теги title/artist/album " +
                        "через Mp3tag, foobar2000 или MusicBrainz Picard. Обложку можно " +
                        "вложить в тег — приложение покажет её без интернета.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text("3. Пример папки", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Sonora Music/\n" +
                        "  Queen - Bohemian Rhapsody.mp3\n" +
                        "  Michael Jackson - Billie Jean.flac\n" +
                        "  Daft Punk - Get Lucky.m4a",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("4. Импорт", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Нажмите «Выбрать файлы», отметьте одну или несколько песен. " +
                        "Они скопируются в папку приложения, появятся в Медиатеке, " +
                        "а обложки и тексты подтянутся из онлайн-каталогов.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onPickFiles) { Text("Выбрать файлы") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

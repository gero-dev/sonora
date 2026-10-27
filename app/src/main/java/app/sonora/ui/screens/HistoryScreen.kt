package app.sonora.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.data.download.SaveStatus
import app.sonora.data.download.TrackSaveState
import app.sonora.ui.MainViewModel
import app.sonora.ui.components.CoverArt
import app.sonora.ui.components.CreatePlaylistDialog
import app.sonora.ui.components.EmptyState
import app.sonora.ui.components.TrackActionSheet
import app.sonora.ui.nav.Routes
import app.sonora.ui.theme.LocalSonoraColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HistoryCardRadius = 14.dp
private val RussianLocale = Locale("ru", "RU")
private val MonthDayFormat = DateTimeFormatter.ofPattern("d MMMM", RussianLocale)
private val TodayFormat = DateTimeFormatter.ofPattern("HH:mm", RussianLocale)

/**
 * «История» — вкладка вместо «Обзора», как Recently played в Spotify.
 *
 * Показывает недавние треки (сгруппированные по дням), топ исполнителей и
 * статистику прослушиваний. Данные лежат только на устройстве
 * (`filesDir/play_history.json`); их можно полностью очистить.
 */
@Composable
fun HistoryScreen(vm: MainViewModel, padding: PaddingValues, navigate: (String) -> Unit) {
    val entries by vm.historyEntries.collectAsState()
    val tracks by vm.tracks.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val player by vm.player.collectAsState()
    val saveStates by vm.saveStates.collectAsState()

    val trackEntries = remember(entries, tracks) { vm.historyTracks() }
    val artists = remember(entries, tracks) { vm.historyArtists() }
    val listenedMs = remember(entries) { entries.sumOf { it.listenedMs } }
    val dayGroups = remember(trackEntries) { groupByDay(trackEntries) }

    var tab by rememberSaveable { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var actionTrack by remember { mutableStateOf<Track?>(null) }
    var playlistForNewTrack by remember { mutableStateOf<Track?>(null) }

    val bottomPadding = padding.calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = bottomPadding + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "header") {
                HistoryHeader(
                    plays = entries.size,
                    artists = artists.size,
                    listenedMs = listenedMs,
                    canClear = entries.isNotEmpty(),
                    onClear = { confirmClear = true },
                )
            }

            item(key = "tabs") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        label = { Text("Треки") },
                    )
                    FilterChip(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        label = { Text("Исполнители") },
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            if (entries.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.History,
                        title = "История пока пуста",
                        subtitle = "Включите любой трек — здесь появятся прослушанные " +
                            "треки, исполнители и статистика. Всё хранится только на устройстве.",
                    )
                }
            } else if (tab == 0) {
                if (artists.isNotEmpty()) {
                    item(key = "recent-artists-title") {
                        SectionTitle("Недавно игравшие")
                    }
                    item(key = "recent-artists") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(artists.take(14), key = { "artist-${it.name}" }) { artist ->
                                ArtistCircle(
                                    name = artist.name,
                                    artworkUrl = artist.artworkUrl,
                                    artworkUri = artist.artworkUri,
                                    onClick = { navigate(Routes.artist(artist.name)) },
                                )
                            }
                        }
                    }
                }

                dayGroups.forEach { (label, rows) ->
                    item(key = "day-$label") {
                        SectionTitle(label, topPadding = 12.dp)
                    }
                    items(rows, key = { "history-${it.track.id}-${it.playedAtMs}" }) { row ->
                        HistoryTrackRow(
                            row = row,
                            isFavorite = row.track.id in favorites,
                            isPlaying = player.current?.id == row.track.id && player.isPlaying,
                            saveState = saveStates[row.track.id],
                            onPlay = { vm.playOrOpen(row.track) },
                            onLongClick = { actionTrack = row.track },
                            onDownload = { vm.downloadTrack(row.track) },
                        )
                    }
                }
            } else {
                item(key = "artists-title") {
                    SectionTitle("Топ исполнителей")
                }
                items(artists, key = { "top-${it.name}" }) { artist ->
                    ArtistHistoryRow(
                        artist = artist,
                        onOpen = { navigate(Routes.artist(artist.name)) },
                        onPlay = {
                            val artistTracks = tracks.filter {
                                it.artist.equals(artist.name, ignoreCase = true)
                            }
                            if (artistTracks.isNotEmpty()) vm.playQueue(artistTracks)
                        },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить историю?") },
            text = {
                Text(
                    "Треки, исполнители и статистика прослушиваний будут удалены. " +
                        "Файлы и плейлисты останутся на месте.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        vm.clearPlayHistory()
                    },
                ) { Text("Очистить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Отмена") }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
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
            onDownload = if (track.playable && track.streamingOnly && !vm.isTrackDownloaded(track)) {
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

@Composable
private fun HistoryHeader(
    plays: Int,
    artists: Int,
    listenedMs: Long,
    canClear: Boolean,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("История", style = MaterialTheme.typography.headlineLarge)
                Text(
                    text = "Только на этом устройстве",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClear, enabled = canClear) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Очистить историю",
                    tint = if (canClear) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    },
                )
            }
        }
        if (plays > 0) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(HistoryCardRadius))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat("Прослушиваний", formatCount(plays))
                Stat("Исполнителей", formatCount(artists))
                Stat("Время", formatListened(listenedMs))
            }
        }
    }
}

@Composable
private fun Stat(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String, topPadding: androidx.compose.ui.unit.Dp = 4.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = topPadding, bottom = 2.dp),
    )
}

@Composable
private fun ArtistCircle(
    name: String,
    artworkUrl: String?,
    artworkUri: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverArt(
            url = artworkUrl ?: artworkUri,
            size = 70.dp,
            cornerRadius = 35.dp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HistoryTrackRow(
    row: MainViewModel.HistoryTrackEntry,
    isFavorite: Boolean,
    isPlaying: Boolean,
    saveState: TrackSaveState?,
    onPlay: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
) {
    val track = row.track
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isPlaying) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onPlay)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            url = track.artworkUrl ?: track.artworkUri,
            size = 48.dp,
            cornerRadius = 10.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isPlaying) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(track.artist)
                    if (row.playCount > 1) append(" · прослушиваний: ${row.playCount}")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = timeLabel(row.playedAtMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                saveState?.isActive == true -> {
                    Spacer(Modifier.height(4.dp))
                    if (saveState.percent in 0..99) {
                        CircularProgressIndicator(
                            progress = saveState.percent / 100f,
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.5.dp,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                        )
                    }
                }

                saveState?.status == SaveStatus.DONE -> Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Сохранено на устройстве",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (track.streamingOnly && saveState?.status != SaveStatus.DONE) {
            IconButton(
                onClick = onDownload,
                enabled = saveState?.isActive != true &&
                    saveState?.status != SaveStatus.UNAVAILABLE,
            ) {
                if (saveState?.isActive == true) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "Сохранить на устройство",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistHistoryRow(
    artist: MainViewModel.HistoryArtistEntry,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            url = artist.artworkUrl ?: artist.artworkUri,
            size = 50.dp,
            cornerRadius = 25.dp,
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
                text = "${playsLabel(artist.playCount)} · ${relativeDate(artist.lastPlayedMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onPlay) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Играть исполнителя",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ---------------------------------------------------------------- helpers

private fun groupByDay(
    rows: List<MainViewModel.HistoryTrackEntry>,
): List<Pair<String, List<MainViewModel.HistoryTrackEntry>>> {
    val groups = LinkedHashMap<String, MutableList<MainViewModel.HistoryTrackEntry>>()
    rows.forEach { row ->
        val label = dayLabel(row.playedAtMs)
        groups.getOrPut(label) { mutableListOf() } += row
    }
    return groups.map { (label, list) -> label to list }
}

private fun dayLabel(atMs: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (date) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> date.format(MonthDayFormat)
    }
}

private fun timeLabel(atMs: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    if (date == today) {
        return Instant.ofEpochMilli(atMs).atZone(zone).format(TodayFormat)
    }
    return if (date == today.minusDays(1)) "вчера" else date.format(MonthDayFormat)
}

private fun relativeDate(atMs: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (date) {
        today -> "сегодня"
        today.minusDays(1) -> "вчера"
        else -> date.format(MonthDayFormat)
    }
}

private fun formatCount(value: Int): String = value.toString()

private fun playsLabel(value: Int): String {
    val mod100 = value % 100
    val mod10 = value % 10
    val word = when {
        mod100 in 11..14 -> "прослушиваний"
        mod10 == 1 -> "прослушивание"
        mod10 in 2..4 -> "прослушивания"
        else -> "прослушиваний"
    }
    return "$value $word"
}

private fun formatListened(ms: Long): String {
    if (ms <= 0L) return "—"
    val minutes = ms / 60_000L
    return when {
        minutes < 60 -> "$minutes мин"
        else -> {
            val hours = minutes / 60
            val rest = minutes % 60
            if (rest == 0L) "$hours ч" else "$hours ч $rest мин"
        }
    }
}

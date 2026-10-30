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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import app.sonora.ui.nav.Routes
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import app.sonora.data.recognition.RecognitionController
import app.sonora.data.recognition.RecognitionState
import coil.compose.AsyncImage

private val PagePadding = 16.dp

/**
 * Поиск по локальной фонотеке и онлайн-источникам.
 *
 * Результаты показываются одним списком без разделения на категории: у каждой
 * строки есть маркер источника («Устройство», «YouTube Music», …), поэтому
 * видно, откуда пришёл трек, но глазами не нужно переключать вкладки.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: MainViewModel, padding: PaddingValues, navigate: (String) -> Unit) {
    val search by vm.search.collectAsState()
    val tracks by vm.tracks.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState()
    // Поиску позиция не нужна: только текущий трек и признак игры.
    val player by vm.playerChrome.collectAsState()
    val saveStates by vm.saveStates.collectAsState()

    var actionTrack by remember { mutableStateOf<Track?>(null) }
    var playlistForNewTrack by remember { mutableStateOf<Track?>(null) }

    val localTracks = remember(tracks) { tracks.filter { it.source == Source.LOCAL } }
    val recentPreview = remember(localTracks) { localTracks.take(8) }

    val queryBlank = search.query.isBlank()
    // Один общий список: сначала совпадения на устройстве (они мгновенные),
    // затем онлайн. Дубли сохранённых треков не показываем дважды.
    val results = remember(search.local, search.remote, tracks) {
        val remote = vm.withoutSavedCopies(search.remote.filterNot { it.isVideoContent })
        (search.local + remote).distinctBy { it.id }
    }
    val nothingFound = !queryBlank && !search.loading && results.isEmpty()
    // vm.isTrackDownloaded() просматривает всю фонотеку. Считаем это один раз
    // на список результатов, а не в композиции каждой строки при каждой перерисовке.
    val downloadedIds = remember(results, tracks) {
        results.asSequence()
            .filter { vm.isTrackDownloaded(it) }
            .map { it.id }
            .toSet()
    }

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
            FragmentSearchPanel(onSearch = vm::onSearchQueryChanged)
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
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
                item { SectionHeader(title = "Недавнее на устройстве") }
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
                            showSource = true,
                        )
                    }
                }
            } else {
                if (nothingFound) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.Search,
                            title = "Ничего не нашлось",
                            subtitle = "Попробуйте другой запрос или проверьте написание.",
                        )
                    }
                }
                if (results.isNotEmpty()) {
                    item { SectionHeader(title = "Найдено · ${results.size}") }
                    items(results, key = { "result-${it.id}" }) { track ->
                        SearchTrackRow(
                            track = track,
                            isFavorite = track.id in favorites,
                            isPlaying = player.current?.id == track.id && player.isPlaying,
                            onClick = { vm.playOrOpen(track) },
                            onLongClick = { actionTrack = track },
                            onFavorite = { vm.toggleFavorite(track.id) },
                            showSource = true,
                            saveState = saveStates[track.id]
                                ?: if (track.id in downloadedIds) {
                                    app.sonora.data.download.TrackSaveState.done()
                                } else {
                                    null
                                },
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
    saveState: app.sonora.data.download.TrackSaveState? = null,
) {
    TrackRow(
        track = track,
        isFavorite = isFavorite,
        isPlaying = isPlaying,
        onClick = onClick,
        onLongClick = onLongClick,
        onFavorite = onFavorite,
        saveState = saveState,
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
            text = "Ищем на устройстве и в онлайн-источниках…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * «Найти по фрагменту» — компактная точка входа в распознавание песни.
 *
 * Панель не требует правок MainViewModel: она читает StateFlow из
 * RecognitionController и вызывает только его публичные методы.
 * Запись и распознавание идут в фоне (Dispatchers.IO), воспроизведение не трогаем.
 */
@Composable
private fun FragmentSearchPanel(onSearch: (String) -> Unit) {
    val context = LocalContext.current
    val state by RecognitionController.state.collectAsState()
    var showTokenDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) RecognitionController.start(context) else RecognitionController.permissionDenied()
    }
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            RecognitionController.startWithFile(context, uri)
        }
    }

    // Разрешение запрашиваем только когда контроллер явно его просит.
    LaunchedEffect(state) {
        if (state is RecognitionState.PermissionRequired) {
            if (RecognitionController.hasPermission(context)) {
                RecognitionController.start(context)
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        when (val current = state) {
            RecognitionState.Idle -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        if (RecognitionController.hasPermission(context)) {
                            RecognitionController.start(context)
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Найти по фрагменту")
                }
                TextButton(onClick = { fileLauncher.launch(arrayOf("audio/*")) }) {
                    Text("Из файла", style = MaterialTheme.typography.labelMedium)
                }
            }

            RecognitionState.PermissionRequired -> Text(
                text = "Нужен доступ к микрофону, чтобы записать фрагмент песни.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is RecognitionState.Recording -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Слушаем фрагмент… ${current.elapsedMs / 1000} с из ${current.totalMs / 1000} с",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Поднесите телефон ближе к музыке",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { RecognitionController.cancel() }) { Text("Отмена") }
            }

            is RecognitionState.Identifying -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = current.stage,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { RecognitionController.cancel() }) { Text("Отмена") }
            }

            is RecognitionState.Result -> FragmentResultCard(
                result = current,
                onSearch = onSearch,
                onOpenLink = { link -> openExternalLink(context, link) },
            )

            is RecognitionState.Error -> Column(Modifier.fillMaxWidth()) {
                Text(
                    text = current.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (current.needsAuddToken) {
                        TextButton(onClick = { showTokenDialog = true }) { Text("Указать ключ AudD") }
                    }
                    TextButton(onClick = { RecognitionController.reset() }) { Text("Закрыть") }
                }
            }
        }
    }

    if (showTokenDialog) {
        AudDTokenDialog(context = context, onDismiss = { showTokenDialog = false })
    }
}

/** Карточка распознанного трека + результаты фонового поиска метаданных. */
@Composable
private fun FragmentResultCard(
    result: RecognitionState.Result,
    onSearch: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val track = result.track
    val enrichment = result.enrichment
    val artwork = enrichment.coverUrl ?: track.artworkUrl
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!artwork.isNullOrBlank()) {
                    AsyncImage(
                        model = artwork,
                        contentDescription = null,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = track.displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = listOfNotNull(
                        enrichment.album ?: track.album,
                        enrichment.year?.toString(),
                        track.provider,
                    ).joinToString(" · ")
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!enrichment.done) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Ищем информацию по названию и автору…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (enrichment.candidates.isNotEmpty()) {
                Text(
                    text = "Найдено в источниках",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                enrichment.candidates.take(3).forEach { candidate ->
                    Text(
                        text = candidate.displayTitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSearch(candidate.displayTitle) }
                            .padding(vertical = 2.dp),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onSearch(track.searchQuery) }) { Text("Искать в поиске") }
                val link = track.songLink
                if (!link.isNullOrBlank()) {
                    TextButton(onClick = { onOpenLink(link) }) { Text("Открыть страницу") }
                }
                TextButton(onClick = { RecognitionController.reset() }) { Text("Закрыть") }
            }

            if (result.fromLocalLibrary) {
                val percent = ((track.score ?: 0.0) * 100).toInt()
                Text(
                    text = "Совпадение с вашей фонотекой · $percent%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Ввод ключа AudD: ключ хранится в настройках модуля, не в SettingsStore. */
@Composable
private fun AudDTokenDialog(context: Context, onDismiss: () -> Unit) {
    var token by remember { mutableStateOf(RecognitionController.apiToken(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ключ AudD") },
        text = {
            Column {
                Text(
                    text = "Ключ нужен, чтобы распознавать незнакомые песни. " +
                        "Получите его на dashboard.audd.io: 300 запросов бесплатно, карта не нужна.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    singleLine = true,
                    label = { Text("API-ключ") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Без ключа поиск работает только по вашей фонотеке (офлайн).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                RecognitionController.setApiToken(context, token)
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

/** Открыть ссылку на песню во внешнем браузере (ошибку молча игнорируем). */
private fun openExternalLink(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

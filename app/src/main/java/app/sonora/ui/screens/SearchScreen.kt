package app.sonora.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.util.SearchText
import app.sonora.data.recognition.RecognitionController
import app.sonora.data.recognition.RecognitionMode
import app.sonora.data.recognition.RecognitionState
import app.sonora.data.recognition.RecognizedTrack
import app.sonora.data.telemetry.AudioFeature
import app.sonora.data.telemetry.SearchRankItem
import app.sonora.data.telemetry.SearchRanker
import app.sonora.ui.MainViewModel
import app.sonora.ui.components.CreatePlaylistDialog
import app.sonora.ui.components.EmptyState
import app.sonora.ui.components.SectionHeader
import app.sonora.ui.components.TrackActionSheet
import app.sonora.ui.components.TrackRow
import app.sonora.ui.nav.Routes
import coil.compose.AsyncImage

private val PagePadding = 16.dp

/**
 * Поиск по локальной фонотеке и онлайн-источникам.
 *
 * Результаты показываются одним списком без разделения на категории: у каждой
 * строки есть маркер источника («Устройство», «YouTube Music», …), поэтому
 * видно, откуда пришёл трек, но глазами не нужно переключать вкладки.
 *
 * Вверху — «поиск по мелодии»: два режима, как в поиске Google по треку:
 *  - «Слушать вокруг» — Shazam-подобная запись того, что играет рядом;
 *  - «Напеть мелодию» — hum-to-search по вашей фонотеке.
 *
 * Результаты переранжируются (совпадение текста + локальная доступность +
 * уверенность распознавания + аудио-фичи), поэтому после распознавания нужный
 * трек оказывается наверху, а не теряется среди похожих.
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
    val audioFeatures by vm.audioFeatures.collectAsState()
    val recognitionState by RecognitionController.state.collectAsState()

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
    // vm.isTrackDownloaded() просматривает всю фонотеку. Считаем это один раз
    // на список результатов, а не в композиции каждой строки при каждой перерисовке.
    val downloadedIds = remember(results, tracks) {
        results.asSequence()
            .filter { vm.isTrackDownloaded(it) }
            .map { it.id }
            .toSet()
    }
    // После распознавания/напевки поднимаем наверх совпавшие результаты.
    val recognizedTracks = remember(recognitionState) { recognitionTracks(recognitionState) }
    val rankedResults = remember(results, search.query, audioFeatures, downloadedIds, recognizedTracks) {
        rankSearchResults(search.query, results, downloadedIds, audioFeatures, recognizedTracks)
    }
    val nothingFound = !queryBlank && !search.loading && results.isEmpty()

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
            FragmentSearchPanel(
                state = recognitionState,
                onSearch = vm::onSearchQueryChanged,
                onPlayLocal = { trackId ->
                    tracks.firstOrNull { it.id == trackId }?.let { vm.playOrOpen(it) }
                },
            )
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
                if (rankedResults.isNotEmpty()) {
                    item { SectionHeader(title = "Найдено · ${rankedResults.size}") }
                    items(rankedResults, key = { "result-${it.id}" }) { track ->
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
        // Источник ушёл из конца строки в точку перед исполнителем: чип с
        // надписью отнимал у названия около сотни dp.
        showSource = showSource,
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
 * «Поиск по мелодии» — компактная точка входа в распознавание песни/напева.
 *
 * Панель не требует правок MainViewModel: она читает StateFlow из
 * RecognitionController и вызывает только его публичные методы.
 * Запись и распознавание идут в фоне (Dispatchers.IO), воспроизведение не трогаем.
 */
@Composable
private fun FragmentSearchPanel(
    state: RecognitionState,
    onSearch: (String) -> Unit,
    onPlayLocal: (String) -> Unit,
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(RecognitionMode.LISTEN) }
    var showTokenDialog by remember { mutableStateOf(false) }
    val running = state is RecognitionState.Recording || state is RecognitionState.Identifying

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            RecognitionController.permissionDenied()
        } else if (RecognitionController.hasPermission(context)) {
            if (mode == RecognitionMode.HUM) {
                RecognitionController.startHumming(context)
            } else {
                RecognitionController.start(context)
            }
        }
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
                if (mode == RecognitionMode.HUM) {
                    RecognitionController.startHumming(context)
                } else {
                    RecognitionController.start(context)
                }
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val startRecognition: () -> Unit = {
        if (RecognitionController.hasPermission(context)) {
            if (mode == RecognitionMode.HUM) {
                RecognitionController.startHumming(context)
            } else {
                RecognitionController.start(context)
            }
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = mode == RecognitionMode.LISTEN,
                onClick = { if (!running) mode = RecognitionMode.LISTEN },
                enabled = !running,
                label = { Text("Слушать вокруг") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
            FilterChip(
                selected = mode == RecognitionMode.HUM,
                onClick = { if (!running) mode = RecognitionMode.HUM },
                enabled = !running,
                label = { Text("Напеть мелодию") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        Text(
            text = if (mode == RecognitionMode.HUM) {
                "Напойте или просвистите мелодию — Sonora сравнит её с треками на устройстве."
            } else {
                "Запишите 8 секунд того, что играет рядом: AudD (если задан ключ) или ваша фонотека."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )

        when (val current = state) {
            RecognitionState.Idle -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = startRecognition,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = if (mode == RecognitionMode.HUM) {
                            Icons.Rounded.MusicNote
                        } else {
                            Icons.Rounded.Mic
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (mode == RecognitionMode.HUM) "Напеть мелодию" else "Найти по фрагменту")
                }
                if (mode == RecognitionMode.LISTEN) {
                    TextButton(onClick = { fileLauncher.launch(arrayOf("audio/*")) }) {
                        Text("Из файла", style = MaterialTheme.typography.labelMedium)
                    }
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
                    imageVector = if (current.mode == RecognitionMode.HUM) {
                        Icons.Rounded.MusicNote
                    } else {
                        Icons.Rounded.Mic
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    val action = if (current.mode == RecognitionMode.HUM) {
                        "Записываем мелодию"
                    } else {
                        "Слушаем фрагмент"
                    }
                    Text(
                        text = "$action… ${current.elapsedMs / 1000} с из ${current.totalMs / 1000} с",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = if (current.mode == RecognitionMode.HUM) {
                            "Напойте или просвистите мелодию ровно, без слов"
                        } else {
                            "Поднесите телефон ближе к музыке"
                        },
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
                Column(Modifier.weight(1f)) {
                    Text(
                        text = current.phase.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = current.stage,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = { RecognitionController.cancel() }) { Text("Отмена") }
            }

            is RecognitionState.Result -> FragmentResultCard(
                result = current,
                onSearch = onSearch,
                onPlayLocal = onPlayLocal,
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

/** Карточка распознанного трека + результаты фонового поиска метаданных и проверки. */
@Composable
private fun FragmentResultCard(
    result: RecognitionState.Result,
    onSearch: (String) -> Unit,
    onPlayLocal: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val track = result.track
    val enrichment = result.enrichment
    val verification = result.verification
    val artwork = enrichment.coverUrl ?: track.artworkUrl
    val localTrackId = result.localTrackId
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
                    val percent = track.confidencePercent
                    if (percent != null) {
                        Text(
                            text = if (result.fromLocalLibrary) {
                                "Совпадение с вашей фонотекой · $percent%"
                            } else {
                                "Уверенность · $percent%"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (result.fromCache) {
                Text(
                    text = "Результат из кэша — сеть не использовалась",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            // Top-N вариантов из локальной сверки/мелодии: можно сразу слушать.
            if (result.alternatives.isNotEmpty()) {
                Text(
                    text = "Другие варианты",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                result.alternatives.take(4).forEach { alternative ->
                    val alternativeLocalId = alternative.localTrackId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (alternativeLocalId != null) {
                                    onPlayLocal(alternativeLocalId)
                                } else {
                                    onSearch(alternative.displayTitle)
                                }
                            }
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = alternative.displayTitle,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = listOfNotNull(alternative.album, alternative.provider)
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        alternative.confidencePercent?.let { percentValue ->
                            Text(
                                text = "$percentValue%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
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
                enrichment.candidates
                    .sortedByDescending { it.confidence ?: 0.0 }
                    .take(4)
                    .forEach { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSearch(candidate.displayTitle) }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = candidate.displayTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val subtitle = listOfNotNull(
                                    candidate.sourceLabel,
                                    candidate.audioFeatureLabel,
                                    candidate.subtitle,
                                ).joinToString(" · ")
                                if (subtitle.isNotBlank()) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            candidate.confidencePercent?.let { percentValue ->
                                Text(
                                    text = "$percentValue%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
            }

            // Фоновая проверка: локальная копия, сверка короткого сниппета, фичи.
            when {
                result.verifying -> Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Проверяем совпадение в фоне — можно продолжать слушать музыку…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                verification != null -> Column(Modifier.padding(top = 6.dp)) {
                    val summary = buildList {
                        if (verification.localAvailable) {
                            val score = verification.localScore
                            add(
                                if (score != null) {
                                    "Есть на устройстве · ${(score * 100).toInt()}%"
                                } else {
                                    "Есть на устройстве"
                                },
                            )
                        }
                        if (verification.snippetChecked && verification.snippetScore != null) {
                            add("аудио кандидата ${(verification.snippetScore * 100).toInt()}%")
                        }
                        verification.featuresSummary?.let { add(it) }
                    }
                    if (summary.isNotEmpty()) {
                        Text(
                            text = summary.joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    verification.note?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (localTrackId != null) {
                    TextButton(onClick = { onPlayLocal(localTrackId) }) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Слушать")
                    }
                }
                TextButton(onClick = { onSearch(track.searchQuery) }) { Text("Искать в поиске") }
                val link = track.songLink
                if (!link.isNullOrBlank()) {
                    TextButton(onClick = { onOpenLink(link) }) { Text("Открыть страницу") }
                }
                TextButton(onClick = { RecognitionController.reset() }) { Text("Закрыть") }
            }

            if (result.mode == RecognitionMode.HUM) {
                Text(
                    text = "Напетый поиск сравнивает мелодию только с треками на устройстве.",
                    style = MaterialTheme.typography.labelSmall,
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

/** Треки из последнего результата распознавания — для переранжирования поиска. */
private fun recognitionTracks(state: RecognitionState): List<RecognizedTrack> {
    val result = state as? RecognitionState.Result ?: return emptyList()
    return buildList {
        add(result.track)
        addAll(result.alternatives)
    }
}

/** Переранжирование результатов поиска: текст, локальность, распознавание, фичи. */
private fun rankSearchResults(
    query: String,
    results: List<Track>,
    downloadedIds: Set<String>,
    audioFeatures: Map<String, AudioFeature>,
    recognized: List<RecognizedTrack>,
): List<Track> {
    if (results.isEmpty()) return results
    val items = results.map { track ->
        SearchRankItem(
            track = track,
            audioFeature = audioFeatures[track.id],
            localAvailable = track.source == Source.LOCAL || track.id in downloadedIds,
            recognitionScore = recognitionScoreFor(track, recognized),
        )
    }
    return SearchRanker.rank(query, items).map { it.track }
}

/** Уверенность распознавания для конкретной строки поиска (или null). */
private fun recognitionScoreFor(track: Track, recognized: List<RecognizedTrack>): Double? {
    if (recognized.isEmpty()) return null
    val title = SearchText.normalize(track.title)
    val artist = SearchText.normalize(track.artist)
    var best: Double? = null
    for (item in recognized) {
        val score = item.score ?: continue
        val itemLocalId = item.localTrackId
        if (itemLocalId != null && itemLocalId == track.id) {
            best = maxOf(best ?: 0.0, score)
            continue
        }
        val itemTitle = SearchText.normalize(item.title)
        val itemArtist = SearchText.normalize(item.artist)
        if (itemTitle.isBlank()) continue
        val titleMatch = title.isNotBlank() &&
            (title.contains(itemTitle) || itemTitle.contains(title))
        val artistMatch = artist.isNotBlank() && itemArtist.isNotBlank() &&
            (artist.contains(itemArtist) || itemArtist.contains(artist))
        val candidate = when {
            titleMatch && artistMatch -> score
            titleMatch -> score * 0.85
            artistMatch && itemTitle.length >= 4 && title.contains(itemTitle.take(4)) -> score * 0.6
            else -> null
        }
        if (candidate != null) best = maxOf(best ?: 0.0, candidate)
    }
    return best
}

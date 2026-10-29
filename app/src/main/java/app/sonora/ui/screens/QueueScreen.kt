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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sonora.core.model.Playlist
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.settings.PlaybackMode
import app.sonora.ui.MainViewModel
import app.sonora.ui.components.BackIconButton
import app.sonora.ui.components.CoverArt
import app.sonora.ui.components.EmptyState
import app.sonora.ui.nav.LocalArtistNavigator
import app.sonora.ui.nav.Routes

/**
 * Очередь воспроизведения: подсветка текущего трека,
 * перемещение стрелками и удаление.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(vm: MainViewModel, navigate: (String) -> Unit) {
    // Очереди не нужна позиция воспроизведения — только состав и текущий индекс.
    val player by vm.playerChrome.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val google by vm.googleAccount.collectAsState()
    val settings by vm.settings.collectAsState()
    val queue = player.queue
    // Сколько треков в очереди требуют сети — для строки «Онлайн-треков в очереди».
    val onlineInQueue = remember(queue) { queue.count { !vm.offlinePlayable(it) } }
    // Ключ строки = id трека + номер вхождения: в очереди бывают дубликаты,
    // а id-ключ помогает Compose переиспользовать строки при перестановке/удалении.
    val queueKeys = remember(queue) {
        val seen = HashMap<String, Int>(queue.size)
        queue.map { track ->
            val occurrence = seen.getOrDefault(track.id, 0)
            seen[track.id] = occurrence + 1
            "${track.id}#$occurrence"
        }
    }
    var showSaved by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackIconButton()
            Column(Modifier.weight(1f)) {
                Text("Очередь", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "Треков в очереди: ${queue.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { showSaved = true },
            ) {
                Text("Добавить")
            }
            TextButton(
                onClick = { vm.clearQueue() },
                enabled = queue.isNotEmpty(),
            ) {
                Text("Очистить")
            }
        }

        // Карточка режима проигрывания: что попадает в очередь и как убрать стриминг.
        QueuePlaybackModeCard(
            mode = settings.playbackMode,
            streamingInQueue = settings.streamingInQueue,
            onlineCount = onlineInQueue,
            onModeChange = vm::setPlaybackMode,
            onStreamingChange = vm::setStreamingInQueue,
            onRemoveStreaming = vm::removeStreamingFromQueue,
        )

        if (queue.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(
                        icon = Icons.Rounded.QueueMusic,
                        title = "Очередь пуста",
                        subtitle = "Включите трек или добавьте его в очередь из меню.",
                    )
                    TextButton(onClick = { navigate(Routes.LIBRARY) }) {
                        Text("Открыть медиатеку")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(
                    items = queue,
                    key = { index, _ -> queueKeys[index] },
                ) { index, track ->
                    QueueRow(
                        track = track,
                        index = index,
                        isCurrent = index == player.index,
                        canMoveUp = index > 0,
                        canMoveDown = index < queue.lastIndex,
                        onClick = { vm.seekToIndex(index) },
                        onMoveUp = { vm.moveInQueue(index, index - 1) },
                        onMoveDown = { vm.moveInQueue(index, index + 1) },
                        onRemove = { vm.removeFromQueue(index) },
                    )
                }
            }
        }
    }

    if (showSaved) {
        ModalBottomSheet(
            onDismissRequest = { showSaved = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            // Список опций прокручивается внутри листа: при большом числе плейлистов
            // нижние пункты остаются достижимыми, а сам лист не растягивается на весь экран.
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = "Добавить сохранённое в очередь",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "Избранное, плейлисты и «Понравившиеся» аккаунта",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                SavedQueueOption(
                    title = "Избранное на устройстве",
                    subtitle = "${vm.favoriteTracks().size} треков",
                    onClick = {
                        showSaved = false
                        vm.enqueueFavorites()
                    },
                )
                if (google.connected) {
                    SavedQueueOption(
                        title = "Понравившиеся YouTube",
                        subtitle = "Из личной библиотеки аккаунта",
                        onClick = {
                            showSaved = false
                            vm.enqueueLikedSongs()
                        },
                    )
                } else {
                    Text(
                        text = "Войдите в Google в «Подключениях», чтобы добавлять " +
                            "«Понравившиеся» и плейлисты аккаунта.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                val youtubePlaylists = playlists.filter { it.source == Source.YOUTUBE }
                if (youtubePlaylists.isNotEmpty()) {
                    Text(
                        text = "Плейлисты YouTube",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                    youtubePlaylists.take(12).forEach { playlist ->
                        SavedQueueOption(
                            title = playlist.name,
                            subtitle = "YouTube Music",
                            onClick = {
                                showSaved = false
                                vm.enqueuePlaylist(playlist)
                            },
                        )
                    }
                }
                val ownPlaylists = playlists.filter { it.editable }.take(12)
                if (ownPlaylists.isNotEmpty()) {
                    Text(
                        text = "Мои плейлисты",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                    ownPlaylists.forEach { playlist ->
                        SavedQueueOption(
                            title = playlist.name,
                            subtitle = "${playlist.trackIds.size} треков",
                            onClick = {
                                showSaved = false
                                vm.enqueuePlaylist(playlist)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Карточка «Режим проигрывания»: онлайн-режим, выключатель стриминга в очереди
 * и быстрая чистка онлайн-треков.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueuePlaybackModeCard(
    mode: PlaybackMode,
    streamingInQueue: Boolean,
    onlineCount: Int,
    onModeChange: (PlaybackMode) -> Unit,
    onStreamingChange: (Boolean) -> Unit,
    onRemoveStreaming: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Режим проигрывания", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            // Два режима всегда помещаются в строку; скролл — страховка для узких экранов.
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaybackMode.entries.forEach { option ->
                    FilterChip(
                        selected = mode == option,
                        onClick = { onModeChange(option) },
                        label = { Text(option.label) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // В локальном режиме подсказка режима и есть статус очереди.
            Text(
                text = if (mode == PlaybackMode.LOCAL_ONLY) {
                    "Играем только то, что сохранено на устройстве"
                } else {
                    mode.hint
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (mode == PlaybackMode.LOCAL_ONLY) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Стриминг в очереди", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Разрешать онлайн-треки в очереди и автодополнении",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = streamingInQueue && mode == PlaybackMode.STREAMING,
                    onCheckedChange = onStreamingChange,
                    // В режиме «только загруженное» стриминг выключен по определению.
                    enabled = mode == PlaybackMode.STREAMING,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Онлайн-треков в очереди: $onlineCount",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onRemoveStreaming,
                    enabled = onlineCount > 0,
                ) {
                    Text("Убрать стриминг")
                }
            }
        }
    }
}

@Composable
private fun SavedQueueOption(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.PlaylistAdd,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Одна строка очереди. */
@Composable
private fun QueueRow(
    track: Track,
    index: Int,
    isCurrent: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val openArtist = LocalArtistNavigator.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp),
        )
        CoverArt(
            url = track.artworkUrl ?: track.artworkUri,
            size = 48.dp,
            cornerRadius = 12.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = track.artist.isNotBlank()) {
                    openArtist(track.artist)
                },
            )
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Rounded.ArrowUpward,
                contentDescription = "Выше",
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Rounded.ArrowDownward,
                contentDescription = "Ниже",
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = "Убрать из очереди",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

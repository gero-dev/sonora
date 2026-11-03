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
import androidx.compose.material3.FilterChipDefaults
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
 * Очередь воспроизведения в модели Spotify:
 *  - «Сейчас играет»;
 *  - «Далее в очереди» — треки, добавленные вручную («Играть следующим» /
 *    «В очередь»): их можно переставлять и удалять;
 *  - «Далее из: <контекст>» — альбом/плейлист/волна, которые продолжатся
 *    после пользовательской очереди; их можно только выбрать тапом.
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
    val userIndices = player.userQueueIndices
    val contextIndices = player.contextQueueIndices
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
                    text = if (queue.isEmpty()) {
                        "Очередь пуста"
                    } else {
                        "Треков: ${queue.size} · вручную: ${userIndices.size}"
                    },
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
                item(key = "header-now") {
                    QueueSectionHeader("Сейчас играет")
                }
                player.current?.let { current ->
                    item(key = "track-current") {
                        QueueRow(
                            track = current,
                            label = "${player.index + 1}",
                            isCurrent = true,
                            editable = false,
                            canMoveUp = false,
                            canMoveDown = false,
                            onClick = {},
                            onMoveUp = {},
                            onMoveDown = {},
                            onRemove = {},
                        )
                    }
                }

                item(key = "header-user") {
                    QueueSectionHeader(
                        title = "Далее в очереди",
                        subtitle = if (userIndices.isEmpty()) {
                            "Добавленные вручную треки играют раньше контекста"
                        } else {
                            "Порядок можно менять"
                        },
                        action = if (userIndices.isNotEmpty()) "Очистить" else null,
                        onAction = {
                            // Убираем только пользовательскую очередь, контекст не трогаем.
                            userIndices.reversed().forEach { index -> vm.removeFromQueue(index) }
                        },
                    )
                }
                if (userIndices.isEmpty()) {
                    item(key = "user-empty") {
                        Text(
                            text = "Пусто. В меню трека выберите «Играть следующим» или «В очередь».",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                } else {
                    itemsIndexed(
                        items = userIndices,
                        key = { _, globalIndex -> "user-${queueKeys[globalIndex]}" },
                        contentType = { _, _ -> "queue-user" },
                    ) { position, globalIndex ->
                        val track = queue[globalIndex]
                        QueueRow(
                            track = track,
                            label = "${position + 1}",
                            isCurrent = false,
                            editable = true,
                            canMoveUp = position > 0,
                            canMoveDown = position < userIndices.lastIndex,
                            onClick = { vm.seekToIndex(globalIndex) },
                            onMoveUp = { vm.moveInQueue(globalIndex, userIndices[position - 1]) },
                            onMoveDown = { vm.moveInQueue(globalIndex, userIndices[position + 1]) },
                            onRemove = { vm.removeFromQueue(globalIndex) },
                        )
                    }
                }

                if (contextIndices.isNotEmpty()) {
                    item(key = "header-context") {
                        QueueSectionHeader(
                            title = "Далее из: ${player.contextTitle ?: "контекст"}",
                            subtitle = "${contextIndices.size} треков",
                        )
                    }
                    itemsIndexed(
                        items = contextIndices,
                        key = { _, globalIndex -> "ctx-${queueKeys[globalIndex]}" },
                        contentType = { _, _ -> "queue-context" },
                    ) { _, globalIndex ->
                        QueueRow(
                            track = queue[globalIndex],
                            label = "${globalIndex + 1}",
                            isCurrent = false,
                            editable = false,
                            canMoveUp = false,
                            canMoveDown = false,
                            onClick = { vm.seekToIndex(globalIndex) },
                            onMoveUp = {},
                            onMoveDown = {},
                            onRemove = {},
                        )
                    }
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

/** Заголовок секции очереди с необязательным действием (например, «Очистить»). */
@Composable
private fun QueueSectionHeader(
    title: String,
    subtitle: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
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
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
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

/** Одна строка очереди. Контекстные треки показываем без кнопок правки. */
@Composable
private fun QueueRow(
    track: Track,
    label: String,
    isCurrent: Boolean,
    editable: Boolean,
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
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            )
            .clickable(enabled = !isCurrent, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isCurrent) "▶" else label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp),
        )
        CoverArt(
            url = track.artworkUrl ?: track.artworkUri,
            size = 44.dp,
            cornerRadius = 8.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                // Две строки: в очереди название тоже обрезалось, хотя строка
                // растёт по высоте и места под ним достаточно.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = track.artist.isNotBlank()) {
                    openArtist(track.artist)
                },
            )
        }
        if (editable) {
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

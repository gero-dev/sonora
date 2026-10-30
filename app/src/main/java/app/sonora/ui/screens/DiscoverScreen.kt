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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.util.Formatters
import app.sonora.ui.MainViewModel
import app.sonora.ui.components.BackIconButton
import app.sonora.ui.components.CoverArt
import app.sonora.ui.components.EmptyState
import app.sonora.ui.components.SourceChip
import app.sonora.ui.components.sourceColor

private val PagePadding = 16.dp
private val CardRadius = 20.dp

/**
 * «Обзор»: поиск строго по открытым каталогам (Internet Archive, Jamendo).
 *
 * Здесь нет ни Spotify, ни YouTube, ни SoundCloud: только public domain и
 * Creative Commons, у которых авторы сами разрешили скачивание. Лицензия
 * показывается прямо в карточке трека. Логика и колбэки прежние.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(vm: MainViewModel, padding: PaddingValues, navigate: (String) -> Unit) {
    val state by vm.discover.collectAsState()
    val saveStates by vm.saveStates.collectAsState()
    val sources = remember { vm.discoverSources() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PagePadding,
            end = PagePadding,
            top = padding.calculateTopPadding() + 16.dp,
            bottom = padding.calculateBottomPadding() + 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // «Обзор» открывается с Главной: стрелка возвращает туда же,
                    // даже если нижнее меню почему-то недоступно.
                    BackIconButton()
                    Spacer(Modifier.width(4.dp))
                    Text("Обзор", style = MaterialTheme.typography.headlineLarge)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Свободная музыка из открытых каталогов",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "search") {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onDiscoverQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = { Text("Что искать в открытых каталогах?") },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onDiscoverQueryChanged("") }) {
                            Icon(Icons.Rounded.Clear, contentDescription = "Очистить")
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
            )
        }

        item(key = "sources") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sources.forEach { source ->
                    val needsKey = vm.discoverHint(source) != null
                    FilterChip(
                        selected = state.source == source,
                        onClick = { vm.setDiscoverSource(source) },
                        label = { Text(source.title) },
                        leadingIcon = if (needsKey) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Key,
                                    contentDescription = "Нужен ключ",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        // Подсказка по ключу — над результатами, но под переключателем каталогов.
        sources.forEach { source ->
            val hint = vm.discoverHint(source)
            if (!hint.isNullOrBlank()) {
                item(key = "hint-${source.name}") {
                    HintCard(source = source, hint = hint)
                }
            }
        }

        item(key = "honest") { HonestCatalogCard() }

        when {
            state.query.isBlank() -> item(key = "empty-start") {
                EmptyState(
                    icon = Icons.Rounded.Explore,
                    title = "Найдите свободную музыку",
                    subtitle = "Например: jazz, lo-fi, piano, ambient…",
                )
            }

            state.loading -> item(key = "loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
                    )
                }
            }

            state.results.isEmpty() && state.error != null -> item(key = "error") {
                Text(
                    text = state.error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }

            state.results.isEmpty() -> item(key = "empty-results") {
                EmptyState(
                    icon = Icons.Rounded.Search,
                    title = "Ничего не нашлось",
                    subtitle = "Попробуйте другой запрос или другой каталог.",
                )
            }

            else -> {
                // Ошибку скачивания показываем над списком, не скрывая результаты.
                if (state.error != null) {
                    item(key = "inline-error") {
                        Text(
                            text = state.error.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                items(state.results, key = { "${it.source.name}-${it.id}" }) { track ->
                    DiscoverTrackCard(
                        track = track,
                        downloading = track.id in state.downloadingIds ||
                            saveStates[track.id]?.isActive == true,
                        downloaded = track.id in state.downloadedIds ||
                            vm.isTrackDownloaded(track),
                        percent = saveStates[track.id]?.percent ?: -1,
                        onPlay = { vm.playOrOpen(track) },
                        onDownload = { vm.downloadDiscoverTrack(track) },
                    )
                }
            }
        }
    }
}

/** Карточка-подсказка: какому каталогу нужен ключ и где его взять. */
@Composable
private fun HintCard(source: Source, hint: String) {
    val text = buildString {
        append(hint)
        if (source == Source.JAMENDO && !hint.contains("devportal", ignoreCase = true)) {
            append("\nБесплатный ключ: devportal.jamendo.com.")
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = source.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Честная плашка о том, что именно можно искать и скачивать в «Обзоре». */
@Composable
private fun HonestCatalogCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Здесь только свободная музыка: public domain и Creative Commons. " +
                "Лицензия указана у каждого трека. Скачивание законно, потому что авторы " +
                "сами разрешили его. Spotify/YouTube/SoundCloud сюда не входят — их правила " +
                "запрещают скачивание.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** Карточка результата: обложка, лицензия, источник, играть и скачать. */
@Composable
private fun DiscoverTrackCard(
    track: Track,
    downloading: Boolean,
    downloaded: Boolean,
    percent: Int,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    val license = track.extra["license"]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onPlay)
            .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(url = track.artworkUrl, size = 56.dp, cornerRadius = 12.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${track.artist} · ${Formatters.duration(track.durationMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!license.isNullOrBlank()) {
                    LicenseChip(
                        text = license,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                SourceChip(label = track.source.title, color = sourceColor(track.source))
            }
        }
        IconButton(onClick = onPlay) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Играть",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        IconButton(onClick = onDownload, enabled = !downloading && !downloaded) {
            when {
                downloading -> {
                    if (percent in 0..99) {
                        CircularProgressIndicator(
                            progress = percent / 100f,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }

                downloaded -> Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Уже скачано",
                    tint = MaterialTheme.colorScheme.primary,
                )

                else -> Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = "Скачать",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Крошечный чип лицензии (public domain / CC BY / CC BY-SA …). */
@Composable
private fun LicenseChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

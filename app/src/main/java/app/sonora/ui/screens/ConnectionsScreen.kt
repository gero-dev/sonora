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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sonora.core.model.ConnectionState
import app.sonora.core.model.Source
import app.sonora.ui.GoogleAccountUiState
import app.sonora.ui.MainViewModel
import app.sonora.ui.components.BackIconButton
import app.sonora.ui.components.SectionHeader
import app.sonora.ui.components.sourceColor
import app.sonora.ui.nav.Routes

private val YouTubeRed = Color(0xFFFF0033)

private const val INNERTUBE_TEXT =
    "Sonora ходит в music.youtube.com через InnerTube — тот же протокол, что использует " +
        "Metrolist. Ключи Google Cloud, OAuth и квоты Data API больше не нужны.\n\n" +
        "• Поиск песен, альбомов и плейлистов — прямо в приложении.\n" +
        "• Аудио играет ExoPlayer: очередь, seek, уведомление, виджет и Bluetooth-кнопки.\n" +
        "• Тексты песен и очередь «дальше» приходят из того же InnerTube.\n" +
        "• Скачивание треков YouTube не поддерживается: только потоковое воспроизведение."

/**
 * Подключения и источники. YouTube Music больше не требует настройки:
 * InnerTube работает анонимно, поэтому экран объясняет, что и как подключено.
 */
@Composable
fun ConnectionsScreen(vm: MainViewModel, padding: PaddingValues, navigate: (String) -> Unit) {
    val connections by vm.connections.collectAsState()
    val youtube = connections.firstOrNull { it.source == Source.YOUTUBE }
    val google by vm.googleAccount.collectAsState()
    val syncing by vm.syncing.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackIconButton()
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text("Подключения", style = MaterialTheme.typography.headlineLarge)
                Text(
                    text = "InnerTube, открытые каталоги и локальная музыка",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        InnerTubeCard(
            state = youtube,
            onSearch = { navigate(Routes.SEARCH) },
            onOpenWeb = { navigate(Routes.YOUTUBE_WEB) },
            onOpenLibrary = { navigate(Routes.LIBRARY) },
        )

        Spacer(Modifier.height(12.dp))
        GoogleAccountCard(
            state = google,
            syncing = Source.YOUTUBE in syncing,
            onLogin = { navigate(Routes.GOOGLE_LOGIN) },
            onSync = vm::syncGoogleAccount,
            onLogout = vm::logoutGoogle,
        )

        SectionHeader(title = "Что изменилось")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Без ключей и квот",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = INNERTUBE_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionHeader(title = "Другие источники")
        Text(
            text = "Spotify и SoundCloud настраиваются отдельно: им нужны client_id и OAuth. " +
                "Internet Archive и Jamendo — открытые каталоги без ключей (кроме Jamendo).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { navigate(Routes.DISCOVER) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Открыть каталоги")
        }

        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { navigate(Routes.SETTINGS) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Настройки")
        }
    }
}

@Composable
private fun InnerTubeCard(
    state: ConnectionState?,
    onSearch: () -> Unit,
    onOpenWeb: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val connected = state?.connected != false
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        YouTubeRed.copy(alpha = 0.30f),
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ),
            )
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(YouTubeRed.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = sourceColor(Source.YOUTUBE),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("YouTube Music", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = if (connected) "InnerTube · подключено без ключа" else "InnerTube · недоступно",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (connected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (connected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Подключено",
                    tint = sourceColor(Source.YOUTUBE),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = state?.hint ?: "Поиск, плейлисты, очередь и тексты — через InnerTube.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSearch, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Найти")
            }
            OutlinedButton(onClick = onOpenLibrary) {
                Icon(
                    imageVector = Icons.Rounded.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Медиатека")
            }
        }
        TextButton(onClick = onOpenWeb, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Rounded.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Открыть music.youtube.com в приложении")
        }
        if (!connected) {
            Text(
                text = "Проверьте соединение: InnerTube не требует настройки.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Карточка входа в Google для личной библиотеки YouTube Music. */
@Composable
private fun GoogleAccountCard(
    state: GoogleAccountUiState,
    syncing: Boolean,
    onLogin: () -> Unit,
    onSync: () -> Unit,
    onLogout: () -> Unit,
) {
    val status = when {
        state.busy -> "Завершаем вход…"
        state.connected -> "Подключено: ${state.accountName ?: "Google-аккаунт"}"
        else -> "Не подключено"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Google-аккаунт", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.connected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (state.connected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Подключено",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Вход через WebView, как в Metrolist: Sonora получает cookie сессии и " +
                "читает ваши плейлисты и «Понравившиеся» через InnerTube. Пароль не сохраняется.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val error = state.error
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.connected) {
                Button(
                    onClick = onSync,
                    enabled = !syncing,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (syncing) "Синхронизация…" else "Синхронизировать")
                }
                OutlinedButton(onClick = onLogout) { Text("Выйти") }
            } else {
                Button(
                    onClick = onLogin,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.busy) "Ожидание входа…" else "Войти в Google")
                }
            }
        }
        if (state.playlistsImported > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Импортировано плейлистов: ${state.playlistsImported}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

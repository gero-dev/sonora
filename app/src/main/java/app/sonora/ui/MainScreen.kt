package app.sonora.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.sonora.AppGraph
import app.sonora.ui.components.MiniPlayer
import app.sonora.ui.nav.LocalArtistNavigator
import app.sonora.ui.nav.LocalTrackRadio
import app.sonora.ui.nav.Routes
import app.sonora.ui.screens.AlbumDetailScreen
import app.sonora.ui.screens.ArtistDetailScreen
import app.sonora.ui.screens.ConnectionsScreen
import app.sonora.ui.screens.DiscoverScreen
import app.sonora.ui.screens.GoogleLoginScreen
import app.sonora.ui.screens.HistoryScreen
import app.sonora.ui.screens.HomeScreen
import app.sonora.ui.screens.LibraryScreen
import app.sonora.ui.screens.OnboardingScreen
import app.sonora.ui.screens.PlayerScreen
import app.sonora.ui.screens.PlaylistDetailScreen
import app.sonora.ui.screens.QueueScreen
import app.sonora.ui.screens.RemoteAlbumScreen
import app.sonora.ui.screens.SearchScreen
import app.sonora.ui.screens.SettingsScreen
import app.sonora.ui.screens.YouTubeWebScreen

private data class TabItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val TABS = listOf(
    TabItem(Routes.HOME, "Главная", Icons.Rounded.Home),
    TabItem(Routes.SEARCH, "Поиск", Icons.Rounded.Search),
    TabItem(Routes.HISTORY, "История", Icons.Rounded.History),
    TabItem(Routes.LIBRARY, "Медиатека", Icons.Rounded.LibraryMusic),
    TabItem(Routes.SETTINGS, "Настройки", Icons.Rounded.Settings),
)

/**
 * Экраны, на которых виден «хром» плеера (мини-плеер + нижнее меню).
 * Кроме пяти вкладок это «Обзор» ([Routes.DISCOVER]) и «Подключения»
 * ([Routes.CONNECTIONS]): их открывают с Главной, и раньше они оставались без
 * нижнего меню, поэтому вернуться на Главную можно было только системным
 * «назад».
 */
private val CHROME_ROUTES: Set<String> =
    TABS.map { it.route }.toSet() + setOf(Routes.DISCOVER, Routes.CONNECTIONS)

/**
 * Каркас приложения: нижняя навигация, мини-плеер и маршруты.
 * Экраны лежат в пакете [app.sonora.ui.screens].
 */
@Composable
fun MainScreen(graph: AppGraph) {
    val viewModel: MainViewModel = viewModel(factory = MainViewModelFactory(graph))
    val showOnboarding by viewModel.showOnboarding.collectAsState()
    if (showOnboarding) {
        OnboardingScreen(onFinish = viewModel::finishOnboarding)
        return
    }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // Только «хром» плеера: без позиции — экран не перерисовывается каждые 500 мс.
    val playerState by viewModel.playerChrome.collectAsState()
    val mobilePending by viewModel.pendingMobileDownloads.collectAsState()

    val showChrome = currentRoute != null && currentRoute in CHROME_ROUTES
    // Какая вкладка подсвечивается. «Обзор» и «Подключения» — вложенные экраны
    // Главной, поэтому подсвечиваем «Главную».
    val selectedTabRoute: String? = when {
        currentRoute != null && TABS.any { it.route == currentRoute } -> currentRoute
        currentRoute == Routes.DISCOVER || currentRoute == Routes.CONNECTIONS -> Routes.HOME
        else -> null
    }
    // Тап по нижнему меню ВСЕГДА переключает раздел, даже если открыт вложенный
    // экран («Обзор», «Подключения»). Лямбда стабильная: экраны не пересобираются
    // на каждый тик позиции плеера.
    val onTabClick: (String) -> Unit = androidx.compose.runtime.remember(navController) {
        { route ->
            val current = navController.currentBackStackEntry?.destination?.route
            if (current != route) {
                // С «Обзора»/«Подключений» на Главную возвращаемся именно
                // popBackStack: обычный navigate(HOME) с restoreState может
                // вернуть вложенный экран и оставить пользователя на месте.
                val nestedHome = route == Routes.HOME &&
                    (current == Routes.DISCOVER || current == Routes.CONNECTIONS)
                val returnedByPop = nestedHome &&
                    navController.popBackStack(Routes.HOME, inclusive = false)
                if (!returnedByPop) {
                    navController.navigate(route) {
                        // Снимаем всё, что открыто поверх Главной («Обзор»,
                        // «Подключения» и т.п.), сохраняя состояние вкладок.
                        popUpTo(Routes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
    }
    // Стабильная лямбда: иначе экраны пересобираются на каждый тик позиции плеера.
    val navigate: (String) -> Unit = androidx.compose.runtime.remember(navController) {
        { route ->
            navController.navigate(route) { launchSingleTop = true }
        }
    }

    CompositionLocalProvider(
        LocalArtistNavigator provides { name: String ->
            if (name.isNotBlank()) navController.navigate(Routes.artist(name)) {
                launchSingleTop = true
            }
        },
        LocalTrackRadio provides { track -> viewModel.startTrackRadio(track) },
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showChrome) {
                Column {
                    // Нижний мини-плеер из предыдущей версии: свайпы влево/вправо
                    // переключают трек, свайп вверх и тап открывают полный плеер.
                    if (playerState.current != null) {
                        MiniPlayer(
                            state = playerState,
                            progress = viewModel.playerProgress,
                            onOpen = { navigate(Routes.PLAYER) },
                            onTogglePlay = viewModel::togglePlayPause,
                            onNext = viewModel::next,
                            onPrevious = viewModel::previous,
                            onArtistClick = playerState.current?.artist
                                ?.takeIf { it.isNotBlank() }
                                ?.let { artist -> { navigate(Routes.artist(artist)) } },
                        )
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        TABS.forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTabRoute == tab.route,
                                onClick = { onTabClick(tab.route) },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Routes.HOME) { HomeScreen(viewModel, padding, navigate) }
            composable(Routes.SEARCH) { SearchScreen(viewModel, padding, navigate) }
            composable(Routes.HISTORY) { HistoryScreen(viewModel, padding, navigate) }
            composable(Routes.DISCOVER) { DiscoverScreen(viewModel, padding, navigate) }
            composable(Routes.LIBRARY) { LibraryScreen(viewModel, padding, navigate) }
            composable(Routes.SETTINGS) { SettingsScreen(viewModel, padding, navigate) }
            composable(Routes.CONNECTIONS) { ConnectionsScreen(viewModel, padding, navigate) }
            composable(Routes.QUEUE) { QueueScreen(viewModel, navigate) }

            composable(
                route = Routes.YOUTUBE_WEB,
                enterTransition = { slideInVertically(animationSpec = tween(280)) { it } },
                exitTransition = { slideOutVertically(animationSpec = tween(280)) { it } },
            ) {
                YouTubeWebScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = Routes.GOOGLE_LOGIN,
                enterTransition = { slideInVertically(animationSpec = tween(280)) { it } },
                exitTransition = { slideOutVertically(animationSpec = tween(280)) { it } },
            ) {
                GoogleLoginScreen(
                    vm = viewModel,
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.PLAYER,
                enterTransition = { slideInVertically(animationSpec = tween(280)) { it } },
                exitTransition = { slideOutVertically(animationSpec = tween(280)) { it } + fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(200)) },
                popExitTransition = { slideOutVertically(animationSpec = tween(280)) { it } },
            ) {
                PlayerScreen(viewModel, onBack = { navController.popBackStack() }, navigate = navigate)
            }

            composable(Routes.PLAYLIST) { entry ->
                val playlistId = entry.arguments?.getString("playlistId").orEmpty()
                PlaylistDetailScreen(viewModel, playlistId, navigate)
            }

            composable(Routes.ALBUM) { entry ->
                val albumName = entry.arguments?.getString("albumName").orEmpty()
                val artist = entry.arguments?.getString("artist").orEmpty()
                AlbumDetailScreen(viewModel, albumName, artist, navigate)
            }

            composable(Routes.ARTIST) { entry ->
                val artistName = entry.arguments?.getString("artistName").orEmpty()
                ArtistDetailScreen(viewModel, artistName, navigate)
            }

            composable(Routes.REMOTE_ALBUM) { entry ->
                val albumId = entry.arguments?.getString("albumId").orEmpty()
                val albumTitle = entry.arguments?.getString("albumTitle").orEmpty()
                val artist = entry.arguments?.getString("artist").orEmpty()
                RemoteAlbumScreen(viewModel, albumId, albumTitle, artist, navigate)
            }
        }
    }
    if (mobilePending.isNotEmpty()) {
        var always by remember(mobilePending.size) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = viewModel::cancelMobileDownloads,
            title = { Text("Мобильный интернет") },
            text = {
                Column {
                    Text(
                        if (mobilePending.size == 1) {
                            "Скачать «${mobilePending.first().title}» через мобильную сеть? " +
                                "Может расходоваться трафик."
                        } else {
                            "Скачать ${mobilePending.size} трека(ов) через мобильную сеть? " +
                                "Может расходоваться трафик."
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Разрешение действует на всё приложение: плейлисты и " +
                            "следующие треки скачаются без повторных вопросов.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = always,
                            onCheckedChange = { always = it },
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Не спрашивать снова")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmMobileDownloads(always) }) {
                    Text("Скачать")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelMobileDownloads) {
                    Text("Отмена")
                }
            },
        )
    }
    }
}

package app.sonora.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import app.sonora.ui.nav.Routes
import app.sonora.ui.screens.AlbumDetailScreen
import app.sonora.ui.screens.ArtistDetailScreen
import app.sonora.ui.screens.ConnectionsScreen
import app.sonora.ui.screens.DiscoverScreen
import app.sonora.ui.screens.GoogleLoginScreen
import app.sonora.ui.screens.HomeScreen
import app.sonora.ui.screens.LibraryScreen
import app.sonora.ui.screens.OnboardingScreen
import app.sonora.ui.screens.PlayerScreen
import app.sonora.ui.screens.PlaylistDetailScreen
import app.sonora.ui.screens.QueueScreen
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
    TabItem(Routes.DISCOVER, "Обзор", Icons.Rounded.Explore),
    TabItem(Routes.LIBRARY, "Медиатека", Icons.Rounded.LibraryMusic),
    TabItem(Routes.SETTINGS, "Настройки", Icons.Rounded.Settings),
)

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
    val playerState by viewModel.player.collectAsState()

    val showChrome = currentRoute != null && TABS.any { it.route == currentRoute }
    val navigate: (String) -> Unit = { route ->
        navController.navigate(route) { launchSingleTop = true }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showChrome) {
                Column {
                    playerState.current?.let { track ->
                        MiniPlayer(
                            state = playerState,
                            onOpen = { navigate(Routes.PLAYER) },
                            onTogglePlay = viewModel::togglePlayPause,
                            onNext = viewModel::next,
                            onPrevious = viewModel::previous,
                        )
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        TABS.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = {
                                    if (currentRoute != tab.route) {
                                        navController.navigate(tab.route) {
                                            popUpTo(Routes.HOME) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
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
        }
    }
}

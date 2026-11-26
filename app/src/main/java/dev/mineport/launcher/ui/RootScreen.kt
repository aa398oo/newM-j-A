package dev.mineport.launcher.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.mineport.launcher.MineportApplication
import dev.mineport.launcher.ui.downloads.DownloadsScreen
import dev.mineport.launcher.ui.home.HomeScreen
import dev.mineport.launcher.ui.instances.InstancesScreen
import dev.mineport.launcher.ui.motion.Motion
import dev.mineport.launcher.ui.settings.SettingsScreen

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("home", "首页", Icons.Filled.Home),
    Tab("instances", "实例", Icons.Filled.Apps),
    Tab("downloads", "下载", Icons.Filled.Download),
    Tab("settings", "设置", Icons.Filled.Settings)
)

@Composable
fun RootScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as MineportApplication).container
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    LaunchedEffect(Unit) {
        container.instanceStore.load()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val selected = currentRoute == tab.route
                    val iconScale by animateFloatAsState(
                        targetValue = if (selected) 1.15f else 1f,
                        animationSpec = tween(durationMillis = 180, easing = Motion.Ease),
                        label = "navIconScale"
                    )
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                tab.icon,
                                contentDescription = tab.label,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                }
                            )
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding),
            // 页面切换：淡入 + 轻微上移，220ms ease-out（DESIGN-STYLE 第五节）。
            enterTransition = {
                fadeIn(tween(Motion.ScreenMs, easing = Motion.Ease)) +
                    slideInVertically(tween(Motion.ScreenMs, easing = Motion.Ease)) { it / 24 }
            },
            exitTransition = { fadeOut(tween(150, easing = Motion.Ease)) },
            popEnterTransition = {
                fadeIn(tween(Motion.ScreenMs, easing = Motion.Ease)) +
                    slideInVertically(tween(Motion.ScreenMs, easing = Motion.Ease)) { -it / 24 }
            },
            popExitTransition = { fadeOut(tween(150, easing = Motion.Ease)) }
        ) {
            composable("home") {
                HomeScreen(
                    container = container,
                    onOpenInstances = { navController.navigate("instances") }
                )
            }
            composable("instances") {
                InstancesScreen(container = container)
            }
            composable("downloads") {
                DownloadsScreen()
            }
            composable("settings") {
                SettingsScreen(container = container)
            }
        }
    }
}
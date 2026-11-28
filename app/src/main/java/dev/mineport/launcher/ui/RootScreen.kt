package dev.mineport.launcher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.mineport.launcher.BuildConfig
import dev.mineport.launcher.MineportApplication
import dev.mineport.launcher.ui.downloads.DownloadsScreen
import dev.mineport.launcher.ui.home.HomeScreen
import dev.mineport.launcher.ui.instances.InstancesScreen
import dev.mineport.launcher.ui.more.MoreScreen
import dev.mineport.launcher.ui.motion.LocalAnimationsEnabled
import dev.mineport.launcher.ui.motion.Motion
import dev.mineport.launcher.ui.personalize.PersonalizeScreen
import dev.mineport.launcher.ui.settings.SettingsScreen
import dev.mineport.launcher.ui.theme.MonoFontFamily

/** 左侧导航五项（UX-14）：启动 / 下载 / 设置 / 个性化 / 更多。 */
private data class NavTab(val route: String, val label: String, val icon: ImageVector)

private val navTabs = listOf(
    NavTab("home", "启动", Icons.Filled.PlayArrow),
    NavTab("downloads", "下载", Icons.Filled.Download),
    NavTab("settings", "设置", Icons.Filled.Settings),
    NavTab("personalize", "个性化", Icons.Filled.Palette),
    NavTab("more", "更多", Icons.Filled.MoreHoriz)
)

/**
 * 全站横屏外壳（UX-14）：左侧 80dp 导航 rail + 右侧内容区。
 *
 * 为什么不用 Material 的 NavigationRail：它自带胶囊选中背景与固定 72dp 宽度，
 * 而设计要的是「左侧贴边 3dp 主蓝条 + 主蓝文字」的窄栏，横屏下省下的每一 dp
 * 都还给内容区。自绘一列 52dp 的条目反而更简单，也不用跟 M3 的 item 内边距打架。
 */
@Composable
fun RootScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as MineportApplication).container
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val animations = LocalAnimationsEnabled.current

    LaunchedEffect(Unit) {
        container.instanceStore.load()
    }

    fun navigateTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavRail(
            currentRoute = currentRoute,
            onNavigate = ::navigateTab
        )
        // rail 与内容区之间 1dp 分隔线；比给 rail 加边框省一层绘制。
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline)
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                // 内容区让开状态栏 / 手势条 / 右侧圆角。三层 insets 都在这里消费掉，
                // 页面内部的 Scaffold 就不会再叠一层 padding（否则内容会缩两次）。
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End
                    )
                )
        ) {
            NavHost(
                navController = navController,
                startDestination = "home",
                // 页面切换：淡入 + 轻微右移。横屏下横向位移比纵向更贴合「往左推进一层」的层级感。
                enterTransition = {
                    val ms = if (animations) Motion.ScreenMs else 0
                    fadeIn(tween(ms, easing = Motion.Ease)) +
                        slideInHorizontally(tween(ms, easing = Motion.Ease)) { it / 24 }
                },
                exitTransition = { fadeOut(tween(if (animations) 150 else 0, easing = Motion.Ease)) },
                popEnterTransition = {
                    val ms = if (animations) Motion.ScreenMs else 0
                    fadeIn(tween(ms, easing = Motion.Ease)) +
                        slideInHorizontally(tween(ms, easing = Motion.Ease)) { -it / 24 }
                },
                popExitTransition = { fadeOut(tween(if (animations) 150 else 0, easing = Motion.Ease)) }
            ) {
                composable("home") {
                    HomeScreen(
                        container = container,
                        onOpenInstances = { navController.navigate("instances") }
                    )
                }
                // 「版本管理」不再是导航项（UX-14 五项里没有它）：首页的版本画廊
                // 与更多页的入口进这里，返回仍是首页。
                composable("instances") {
                    InstancesScreen(
                        container = container,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("downloads") {
                    DownloadsScreen()
                }
                composable("settings") {
                    SettingsScreen(container = container)
                }
                composable("personalize") {
                    PersonalizeScreen(container = container)
                }
                composable("more") {
                    MoreScreen(
                        container = container,
                        onOpenInstances = { navController.navigate("instances") },
                        onOpenSettings = { navigateTab("settings") }
                    )
                }
            }
        }
    }
}

@Composable
private fun NavRail(currentRoute: String?, onNavigate: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxHeight()
            // 背景铺满到屏幕边缘（含圆角/挖孔区），inset 只内缩内容：
            // 这样 80dp 是「可用内容宽度」而不是「含安全区的总宽度」。
            .background(cs.surface)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)
            )
            .width(80.dp)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        navTabs.forEach { tab ->
            // instances 不属于任何 tab，但它由「启动」页进入，高亮留在启动上。
            val selected = currentRoute == tab.route ||
                (currentRoute == "instances" && tab.route == "home")
            RailItem(tab = tab, selected = selected, onClick = { onNavigate(tab.route) })
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "v${BuildConfig.VERSION_NAME}-alpha",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = MonoFontFamily,
                fontSize = 8.sp
            ),
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(bottom = 2.dp)
        )
    }
}

@Composable
private fun RailItem(tab: NavTab, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val animations = LocalAnimationsEnabled.current
    val spec = tween<androidx.compose.ui.graphics.Color>(if (animations) 180 else 0, easing = Motion.Ease)
    val tint by animateColorAsState(
        targetValue = if (selected) cs.primary else cs.onSurfaceVariant,
        animationSpec = spec,
        label = "railTint"
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = tween(if (animations) 180 else 0, easing = Motion.Ease),
        label = "railScale"
    )
    Box(Modifier.fillMaxWidth().height(52.dp)) {
        if (selected) {
            // 「左侧贴边 3dp 主蓝色条」：贴的是 rail 自己的左边缘（= 屏幕左边缘 + inset）。
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(26.dp)
                    .background(cs.primary, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = tab.label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = tint,
                maxLines = 1
            )
        }
    }
}

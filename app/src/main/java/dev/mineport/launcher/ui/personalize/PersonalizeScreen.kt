package dev.mineport.launcher.ui.personalize

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mineport.launcher.AppContainer
import dev.mineport.launcher.input.PadPrefs
import dev.mineport.launcher.ui.motion.EnterFromBottom
import dev.mineport.launcher.ui.prefs.Prefs

/**
 * 个性化页（UX-18）：四组外观设置 + 右侧实时预览。
 *
 * 这里没有「应用 / 确定」按钮：每一项改完立刻写 Prefs，而 Prefs 的字段是 Compose
 * 可观察状态，MainActivity 那一层的 MineportTheme 会整体重组——预览卡显示的就是
 * 真实组件，不是另画一套假的示意图。
 *
 * 游戏内 HUD 的三项写的是 [PadPrefs]（局内物理参数的库），不是 Prefs：
 * 轮盘大小要重建 ComposeView，所以下一局进游戏才生效，卡片里直说。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalizeScreen(container: AppContainer) {
    val prefs = container.uiPrefs
    val context = LocalContext.current
    val padPrefs = remember { PadPrefs(context) }

    // PadPrefs 是普通 getter/setter（没有 Compose 状态），页面里用本地镜像驱动 UI，
    // 写入时两边同步。这样拖滑块不会每帧都读一次 SharedPreferences。
    var hudSizeDp by remember { mutableIntStateOf(padPrefs.actionPadSizeDp) }
    var hudAlphaPct by remember { mutableFloatStateOf(padPrefs.actionPadAlpha * 100f) }
    var hudLeft by remember { mutableStateOf(padPrefs.isActionPadLeft()) }

    Scaffold(topBar = { TopAppBar(title = { Text("个性化") }) }) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                EnterFromBottom(0) { ThemeCard(prefs) }
                EnterFromBottom(1) { AccentCard(prefs) }
                EnterFromBottom(2) { MotionCard(prefs) }
                EnterFromBottom(3) {
                    HudCard(
                        sizeDp = hudSizeDp,
                        alphaPct = hudAlphaPct,
                        left = hudLeft,
                        onSize = { hudSizeDp = it; padPrefs.actionPadSizeDp = it },
                        onAlpha = { hudAlphaPct = it; padPrefs.actionPadAlpha = it / 100f },
                        onCorner = { hudLeft = it; padPrefs.setActionPadCorner(it) }
                    )
                }
            }
            Column(modifier = Modifier.weight(0.9f).fillMaxHeight()) {
                EnterFromBottom(1) { PreviewCard() }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@Composable
private fun ThemeCard(prefs: Prefs) {
    SectionCard("主题", "浅色是默认（UX-15）；横屏大面积纯白会刺眼，所以底色是浅蓝灰。") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Prefs.THEME_LIGHT to "浅色",
                Prefs.THEME_DARK to "深色",
                Prefs.THEME_SYSTEM to "跟随系统"
            ).forEach { (value, label) ->
                FilterChip(
                    selected = prefs.theme == value,
                    onClick = { prefs.applyTheme(value) },
                    label = { Text(label) }
                )
            }
        }
    }
}

@Composable
private fun AccentCard(prefs: Prefs) {
    SectionCard("主色调", "只换强调色，背景与卡片保持中性，避免整页花掉。") {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Prefs.ACCENTS.forEach { name ->
                val color = Prefs.accentColor(name)
                val selected = prefs.accent == name
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (selected) color.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable { prefs.applyAccent(name) },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (selected) 28.dp else 24.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (selected) 2.dp else 0.dp,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                    Text(
                        Prefs.accentLabel(name),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MotionCard(prefs: Prefs) {
    SectionCard("动效", "关掉后页面切换与列表入场立刻完成：省电，也照顾对动画敏感的人。") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("界面动画", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = prefs.animations, onCheckedChange = { prefs.applyAnimations(it) })
        }
    }
}

@Composable
private fun HudCard(
    sizeDp: Int,
    alphaPct: Float,
    left: Boolean,
    onSize: (Int) -> Unit,
    onAlpha: (Float) -> Unit,
    onCorner: (Boolean) -> Unit
) {
    SectionCard("游戏内 HUD", "轮盘外观。改大小/位置要重建轮盘外框，下一局进游戏生效。") {
        Text("轮盘大小", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(200, 220, 260).forEach { dp ->
                FilterChip(
                    selected = sizeDp == dp,
                    onClick = { onSize(dp) },
                    label = { Text("${dp}dp") }
                )
            }
        }

        Text("不透明度", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = alphaPct,
                onValueChange = onAlpha,
                valueRange = 40f..100f,
                steps = 5,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "${alphaPct.toInt()}%",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            )
        }

        Text("位置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = left, onClick = { onCorner(true) }, label = { Text("左下") })
            FilterChip(selected = !left, onClick = { onCorner(false) }, label = { Text("右下") })
        }
    }
}

/** 实时预览：直接摆真实的主题色块与组件，改了哪一项这里立刻能看到对比。 */
@Composable
private fun PreviewCard() {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, cs.outline)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("预览", style = MaterialTheme.typography.titleMedium)
            Text(
                "改哪一项这里立刻变，不用重启。",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.background)
                    .border(1.dp, cs.outline, RoundedCornerShape(12.dp))
            ) {
                Row(Modifier.fillMaxSize()) {
                    // 迷你 rail：只画三个条目 + 选中态，足够验证「3dp 蓝条 + 主蓝文字」。
                    Column(
                        modifier = Modifier
                            .width(56.dp)
                            .fillMaxHeight()
                            .background(cs.surface)
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        listOf("启动" to true, "下载" to false, "设置" to false).forEach { (label, selected) ->
                            Box(Modifier.fillMaxWidth().height(28.dp)) {
                                if (selected) {
                                    Box(
                                        Modifier
                                            .align(Alignment.CenterStart)
                                            .width(3.dp)
                                            .height(18.dp)
                                            .background(
                                                cs.primary,
                                                RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)
                                            )
                                    )
                                }
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = if (selected) cs.primary else cs.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(cs.surface)
                                .border(1.dp, cs.outline, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Text("1.20.1 · Fabric", style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(cs.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "启动游戏",
                                style = MaterialTheme.typography.labelLarge,
                                color = cs.onPrimary
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("12 模组", "光影 ✓").forEach { chip ->
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(cs.secondary.copy(alpha = 0.18f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        chip,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                        color = cs.secondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

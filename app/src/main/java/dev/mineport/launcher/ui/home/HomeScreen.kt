package dev.mineport.launcher.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mineport.launcher.AppContainer
import dev.mineport.launcher.data.GameInstance
import dev.mineport.launcher.data.Loader
import dev.mineport.launcher.game.GameActivity
import dev.mineport.launcher.ui.components.EmptyState
import dev.mineport.launcher.ui.components.PageHeader
import dev.mineport.launcher.ui.motion.EnterFromBottom
import dev.mineport.launcher.ui.motion.drawCommandBlock
import dev.mineport.launcher.ui.motion.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 首页启动台（UX-16）：横屏左右两栏。
 *
 * 左栏是「主角卡」——最近游玩的版本：封面 + 版本号 + 加载器色块 + 大号启动按钮 + 准备度三连。
 * 右栏是版本画廊（网格），最后一格是「新建版本」。
 *
 * 为什么不再把设备信息放首屏：横屏可用高约 375dp，设备卡/后端卡各占一百多 dp，
 * 真正要点的「启动」被挤到第二屏。设备信息在「更多」页，渲染后端在「设置」页，首页让位给操作。
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenInstances: () -> Unit,
    onOpenDownloads: () -> Unit = {}
) {
    val context = LocalContext.current
    val instances by container.instanceStore.instances.collectAsStateWithLifecycle()
    val primary = remember(instances) {
        instances.maxByOrNull { it.lastPlayedEpochMs } ?: instances.firstOrNull()
    }
    val ready = remember(primary?.id) { mutableStateOf(Ready(null, null, null)) }
    LaunchedEffect(primary?.id) {
        ready.value = scanReady(context, primary)
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = "启动",
            subtitle = primary?.subtitle ?: "还没有版本"
        )
        if (primary == null) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Icons.Filled.Add,
                    title = "还没有版本",
                    hint = "先去「下载」装一个版本，或者点右边的「新建版本」手动建一个。"
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HeroCard(
                    instance = primary,
                    ready = ready.value,
                    onLaunch = { context.startActivity(GameActivity.intent(context, primary.id)) },
                    onOpenInstances = onOpenInstances,
                    onOpenDownloads = onOpenDownloads,
                    modifier = Modifier.weight(1.15f)
                )
                GalleryPane(
                    instances = instances,
                    activeId = primary.id,
                    onOpen = { instance ->
                        context.startActivity(GameActivity.intent(context, instance.id))
                    },
                    onManage = onOpenInstances,
                    onDownload = onOpenDownloads,
                    modifier = Modifier.weight(0.85f)
                )
            }
        }
    }
}

/** 准备度：模组数 / 有没有光影包 / 有没有 Iris（光影前置）。null = 还没读完。 */
private data class Ready(val mods: Int?, val shader: Boolean?, val iris: Boolean?)

private suspend fun scanReady(context: android.content.Context, instance: GameInstance?): Ready {
    if (instance == null) return Ready(0, false, false)
    return withContext(Dispatchers.IO) {
        val gameDir = File(context.filesDir, "instances/" + instance.gameDirName)
        val jars = File(gameDir, "mods").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jar", ignoreCase = true) }
            ?: emptyList()
        val packs = File(gameDir, "shaderpacks").listFiles()?.filter { it.isFile } ?: emptyList()
        Ready(
            mods = jars.size,
            shader = packs.isNotEmpty(),
            iris = jars.any { it.name.startsWith("iris", ignoreCase = true) }
        )
    }
}

@Composable
private fun HeroCard(
    instance: GameInstance,
    ready: Ready,
    onLaunch: () -> Unit,
    onOpenInstances: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier
) {
    val launchSource = remember { MutableInteractionSource() }
    Card(
        modifier = modifier.fillMaxHeight(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.fillMaxSize()) {
            // 封面吃剩余高度而不是固定 16:7：横屏可视高只有 ~375dp，
            // 固定比例会把下面的准备度 chip 挤出屏幕。
            Cover(instance, Modifier.weight(1f))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoaderChip(instance.loader)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "内存 " + instance.memoryMB + " MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = onLaunch,
                    modifier = Modifier.fillMaxWidth().pressScale(launchSource),
                    interactionSource = launchSource
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("启动游戏", fontWeight = FontWeight.SemiBold)
                }
                ReadyChips(ready, instance, onOpenDownloads, onOpenInstances)
            }
        }
    }
}

@Composable
private fun Cover(instance: GameInstance, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp)
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = 0.92f),
                        accent.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
    ) {
        // 等距命令方块：UX-22 的视觉锚点，首页封面先把它立起来（跟 rail 底部同一个绘制函数，
        // 只是这里 scale 小、material 固定 0.55 让它带一点主色）。
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.92f)
        ) {
            drawCommandBlock(
                accent = accent,
                material = 0.55f,
                flash = 0f,
                center = Offset(size.width * 0.74f, size.height * 0.44f),
                scale = 0.62f
            )
        }
        Text(
            text = instance.minecraftVersion,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            fontSize = 38.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.22f),
            maxLines = 1
        )
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(
                instance.name,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                instance.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun LoaderChip(loader: Loader) {
    val color = when (loader) {
        Loader.FABRIC -> Color(0xFFE8C9A0)
        Loader.FORGE -> Color(0xFFE87A3C)
        Loader.NEOFORGE -> Color(0xFFD98C3F)
        Loader.QUILT -> Color(0xFFB27BE0)
        Loader.VANILLA -> Color(0xFFA0A8B4)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.22f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            loader.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 三连 chip：模组数 / 光影 / 轮盘。
 *
 * 未就绪时不是灰掉，而是变成行动号召（「+ 装 Fabric」「+ 加光影」），把三层菜单压成一行。
 */
@Composable
private fun ReadyChips(
    ready: Ready,
    instance: GameInstance,
    onOpenDownloads: () -> Unit,
    onOpenInstances: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (instance.loader == Loader.VANILLA && (ready.mods ?: 0) == 0) {
            ActionChip("+ 装 Fabric", onOpenDownloads)
        } else {
            ActionChip((ready.mods ?: 0).toString() + " 模组", onOpenInstances)
        }
        if (ready.shader == true) {
            ActionChip("光影 ✓", onOpenDownloads)
        } else {
            ActionChip("+ 加光影", onOpenDownloads)
        }
        ActionChip("轮盘 ✓", onOpenInstances)
    }
    if (instance.loader == Loader.VANILLA && (ready.mods ?: 0) > 0) {
        Text(
            "原版没有加载器，mods 里的模组不会生效。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun GalleryPane(
    instances: List<GameInstance>,
    activeId: String,
    onOpen: (GameInstance) -> Unit,
    onManage: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxHeight()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("版本", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Text(
                "管理",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onManage)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(instances, key = { it.id }) { instance ->
                EnterFromBottom(0) {
                    GalleryCard(
                        instance = instance,
                        active = instance.id == activeId,
                        onClick = { onOpen(instance) }
                    )
                }
            }
            item { NewCard(onClick = onDownload) }
        }
    }
}

@Composable
private fun GalleryCard(
    instance: GameInstance,
    active: Boolean,
    onClick: () -> Unit
) {
    val border = if (active) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), border = border) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoaderChip(instance.loader)
                Spacer(Modifier.weight(1f))
                if (active) {
                    Text(
                        "当前",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                instance.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                instance.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun NewCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            Modifier.padding(10.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                "新建版本",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

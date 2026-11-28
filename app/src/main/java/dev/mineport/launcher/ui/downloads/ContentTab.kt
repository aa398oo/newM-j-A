package dev.mineport.launcher.ui.downloads

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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mineport.launcher.data.GameInstance
import dev.mineport.launcher.data.Loader
import dev.mineport.launcher.mod.ContentInstaller
import dev.mineport.launcher.mod.ModrinthHit
import dev.mineport.launcher.mod.ModrinthClient
import dev.mineport.launcher.mod.ProjectType
import dev.mineport.launcher.ui.components.EmptyState
import dev.mineport.launcher.ui.motion.PixelDotsLoading
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** 每页条数：与 Modrinth 默认 limit 一致，太大会拖慢首屏。 */
private const val PAGE_SIZE = 20

/**
 * 下载页的「附属内容」分栏：模组 / 光影 / 材质 / 地图。
 *
 * 目标是「选中一个已装版本，把内容装进它的目录」：
 * - 模组、地图 → `<实例>/mods`，按实例的加载器 + 游戏版本过滤
 * - 光影 → `<实例>/shaderpacks`
 * - 材质 → `<实例>/resourcepacks`
 *
 * 没实例时不做「假装能装」：直接给入口回版本页。
 */
@Composable
fun ContentTab(
    kind: ProjectType,
    instance: GameInstance?,
    onOpenInstances: () -> Unit
) {
    if (instance == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EmptyState(
                icon = Icons.Filled.Search,
                title = "还没有版本",
                hint = "先去「版本」页创建一个版本，再回来装" + kind.displayName + "。"
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = onOpenInstances) { Text("去创建版本") }
            }
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val instanceDir = remember(instance.id) {
        ContentInstaller.instanceDir(context.filesDir, instance.gameDirName)
    }
    // 原版没有 loader：Modrinth 上没有 categories:vanilla，带上就一条都搜不到。
    // 所以原版照样能浏览（不过滤加载器），但安装前拦下来提示先装加载器。
    val loaderFilter: String? =
        if (instance.loader == Loader.VANILLA) null else instance.loader.name.lowercase()
    val loaderName = loaderFilter ?: "vanilla"

    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<ModrinthHit>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    // 装完只刷新「已装」标记，不重搜列表（重搜会闪一次 loading，还把滚动位置顶回去）。
    var installedTick by remember { mutableStateOf(0) }
    var active by remember { mutableStateOf<ModrinthHit?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val progress = remember { MutableStateFlow("") }
    val progressText by progress.collectAsState()

    // 输入即搜：250ms 内的连续输入只发一次请求（Modrinth 限量 300 次/分钟）。
    // query 为空时不加关键词，等于按下载量取热门——省掉一套「推荐」接口。
    LaunchedEffect(kind, instance.id, query, reloadKey) {
        if (query.isNotBlank()) delay(250)
        loading = true
        failure = null
        hits = emptyList()
        ModrinthClient.searchProjects(
            query = query,
            type = kind,
            gameVersion = instance.minecraftVersion,
            loader = loaderFilter
        ).onSuccess {
            hits = it
            hasMore = it.size >= PAGE_SIZE
        }.onFailure { failure = it.message ?: "搜索失败" }
        loading = false
    }

    // 追加下一页：offset 用已拿到的条数，命中重复就按 project_id 去重（服务端排序可能抖动）。
    fun loadMore() {
        if (loadingMore || !hasMore) return
        loadingMore = true
        scope.launch {
            ModrinthClient.searchProjects(
                query = query,
                type = kind,
                gameVersion = instance.minecraftVersion,
                loader = loaderFilter,
                offset = hits.size
            ).onSuccess { page ->
                val known = hits.mapTo(HashSet()) { it.project_id }
                val fresh = page.filter { known.add(it.project_id) }
                hits = hits + fresh
                hasMore = page.size >= PAGE_SIZE
            }.onFailure { message = "加载更多失败：" + (it.message ?: "未知错误") }
            loadingMore = false
        }
    }

    fun install(hit: ModrinthHit) {
        if (active != null) return
        if (kind.needsLoader && instance.loader == Loader.VANILLA) {
            message = "这个版本是原版，没有加载器。先去「版本」页给它装 Fabric / Forge，" +
                "或者新建一个带加载器的版本再装" + kind.displayName + "。"
            return
        }
        active = hit
        progress.value = "准备…"
        scope.launch {
            ContentInstaller.install(
                projectId = hit.project_id,
                type = kind,
                gameVersion = instance.minecraftVersion,
                loader = loaderName,
                instanceDir = instanceDir,
                onStage = { stage -> progress.value = stage }
            ).onSuccess { message = it }
                .onFailure { message = "安装失败：" + (it.message ?: "未知错误") }
            active = null
            installedTick++
        }
    }

    val needsLoaderWarning = kind.needsLoader && instance.loader == Loader.VANILLA

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)
    ) {
        item(key = "__search__") {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜" + kind.displayName + "，如 " + sampleQuery(kind)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = MaterialTheme.shapes.medium
                )
                if (needsLoaderWarning) {
                    Text(
                        "这个版本是原版（没有加载器），装进 mods 不会生效。光影/材质不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Text(
                    "目标：" + instance.minecraftVersion + " · " + instance.loader.displayName + " · " + when {
                        !kind.needsLoader -> "不挑加载器"
                        loaderFilter == null -> "不过滤加载器（原版）"
                        else -> "按 " + instance.loader.displayName + " 过滤"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
            }
        }

        when {
            loading -> item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PixelDotsLoading(color = MaterialTheme.colorScheme.primary)
                    Text("正在搜索" + kind.displayName + "…", style = MaterialTheme.typography.bodySmall)
                }
            }

            failure != null -> item {
                Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("搜索失败", style = MaterialTheme.typography.titleSmall)
                    Text(failure ?: "", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { reloadKey++ }) { Text("重试") }
                }
            }

            hits.isEmpty() -> item {
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = "没有找到" + kind.displayName,
                    hint = "换个关键词，或者确认这个版本（" + instance.minecraftVersion + "）有对应的内容。"
                )
            }

            else -> {
                items(hits, key = { it.project_id }) { hit ->
                    val installed = remember(hit.project_id, kind, installedTick) {
                        ContentInstaller.isInstalled(instanceDir, kind, hit.slug)
                    }
                    ContentRow(
                        hit = hit,
                        installed = installed,
                        onClick = { install(hit) }
                    )
                }
                // 一页 20 条，滑到底再决定要不要下一页：手机流量与 Modrinth 限额都要省着用。
                item(key = "__more__") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            loadingMore -> Text(
                                "正在加载…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            hasMore -> TextButton(onClick = { loadMore() }) {
                                Text("加载更多（已有 " + hits.size + " 条）")
                            }

                            else -> Text(
                                "共 " + hits.size + " 条，到底了",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    active?.let { hit ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("正在安装 " + hit.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(kind.displayName + " → " + instance.name, style = MaterialTheme.typography.bodySmall)
                    Text(progressText.ifBlank { "准备…" }, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { }
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("完成") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("好") } }
        )
    }
}

@Composable
private fun ContentRow(hit: ModrinthHit, installed: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 没有图片加载库，图标位置用首字母色块——比留白或用外链图片干净。
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(cs.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    hit.title.take(1).uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    hit.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatDownloads(hit.downloads),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )
                Text(
                    if (installed) "已装 · 重装" else "安装",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onClick)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

private fun sampleQuery(kind: ProjectType): String = when (kind) {
    ProjectType.MOD -> "sodium"
    ProjectType.SHADER -> "bsl"
    ProjectType.RESOURCE_PACK -> "faithful"
    ProjectType.MAP -> "terralith"
}

/** 12345 → 12.3k；1_234_567 → 1.2M。 */
private fun formatDownloads(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fk", n / 1_000.0)
    else -> n.toString()
}

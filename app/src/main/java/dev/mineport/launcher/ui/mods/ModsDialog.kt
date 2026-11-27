package dev.mineport.launcher.ui.mods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mineport.launcher.data.GameInstance
import dev.mineport.launcher.mod.ModInstaller
import dev.mineport.launcher.mod.ModrinthClient
import dev.mineport.launcher.mod.ModrinthHit
import dev.mineport.launcher.mod.ModUpdates
import dev.mineport.launcher.mod.ModrinthClient.ResolvedMod
import dev.mineport.launcher.ui.theme.mono
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * 模组搜索与安装。
 *
 * 走 Modrinth 公开 API：搜索 → 选第一个匹配版本 → 自动补 required 前置。
 * 原版实例不能装模组（没有加载器会直接忽略 mods 目录），所以先提示去实例设置
 * 把加载器改掉；加载器的实际安装（Fabric/Forge installer）是下一步。
 */
@Composable
fun ModsDialog(
    instance: GameInstance,
    onDismiss: () -> Unit,
    /** 原版实例点「去装加载器」时回调：关闭本对话框并打开实例设置。 */
    onOpenSettings: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val loader = instance.loader.name.lowercase(Locale.US)
    val supported = loader != "vanilla"

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ModrinthHit>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    // UX-06：启发式更新检测（猜不准的 slug 一律不误报）
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    // UX-04：先解析依赖、展示清单，确认后才真正下载
    var pendingPlan by remember { mutableStateOf<Pair<ModrinthHit, List<ResolvedMod>>?>(null) }

    val modsDir = File(context.filesDir, "instances/" + instance.gameDirName + "/mods")

    var installed by remember { mutableStateOf(listOf<File>()) }
    fun refreshInstalled() {
        installed = modsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jar", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?: emptyList()
    }
    LaunchedEffect(modsDir.path) { refreshInstalled() }

    // UX-03：按 slug 前缀判断是否已装（Modrinth 文件名一般是 <slug>-<version>.jar）。
    // 宁可漏判也不要误判：slug 为空一律当作未安装。
    val installedNames = remember(installed) {
        installed.map { it.name.lowercase(Locale.US) }
    }
    fun isInstalled(slug: String): Boolean {
        val key = slug.lowercase(Locale.US)
        if (key.isBlank()) return false
        return installedNames.any { it.startsWith(key + "-") || it.startsWith(key + "_") }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模组 · " + instance.minecraftVersion) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!supported) {
                    Text(
                        "这是原版实例，游戏不会读取 mods 目录。先在实例设置里把加载器改成 " +
                            "Fabric / Forge / NeoForge / Quilt，再回来搜索。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (onOpenSettings != null) {
                        Button(onClick = { onDismiss(); onOpenSettings() }) {
                            Text("去实例设置装加载器")
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "已安装 " + installed.size + " 个模组文件",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // UX-06：启发式更新检测，猜不准的 slug 不报「可更新」
                        TextButton(
                            enabled = !updateBusy && installed.isNotEmpty(),
                            onClick = {
                                updateBusy = true
                                updateMsg = "检查中…"
                                scope.launch {
                                    val statuses = ModUpdates.check(modsDir)
                                    updateMsg = ModUpdates.summarize(statuses)
                                    updateBusy = false
                                }
                            }
                        ) { Text(if (updateBusy) "检查中…" else "检查更新") }
                        // UX-07：想看/想手动放 jar 时，至少能拿到路径
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                modsDir.absolutePath,
                                style = MaterialTheme.typography.bodySmall.mono(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(modsDir.absolutePath))
                                message = "路径已复制"
                            }) { Text("复制路径") }
                        }
                    }
                    if (updateMsg != null) {
                        Text(
                            updateMsg!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    installed.take(6).forEach { file ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                file.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                val removed = file.delete()
                                refreshInstalled()
                                message = if (removed) "已删除 " + file.name else "删除失败：" + file.name
                            }) { Text("删除") }
                        }
                    }
                    if (installed.size > 6) {
                        Text(
                            "…另有 " + (installed.size - 6) + " 个文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("搜索模组") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            enabled = !loading && query.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    loading = true
                                    message = null
                                    ModrinthClient.search(
                                        query = query.trim(),
                                        gameVersion = instance.minecraftVersion,
                                        loader = loader
                                    ).onSuccess { hits ->
                                        results = hits
                                        if (hits.isEmpty()) message = "没有匹配的模组"
                                    }.onFailure { error ->
                                        message = "搜索失败：" + (error.message ?: "网络错误")
                                    }
                                    loading = false
                                }
                            }
                        ) { Text(if (loading) "搜索中" else "搜索") }
                    }

                    if (loading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(2.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                progress ?: "处理中…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }

                    // UX-04：确认前先亮清单，避免「点一个装了三个」
                    pendingPlan?.let { (planHit, mods) ->
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                "将安装 " + mods.size + " 个文件" +
                                    if (mods.size > 1) "（含 " + (mods.size - 1) + " 个依赖）" else "",
                                style = MaterialTheme.typography.titleSmall
                            )
                            mods.take(5).forEach { resolved ->
                                Text(
                                    "· " + (resolved.version.files.firstOrNull { it.primary }?.filename
                                        ?: resolved.version.version_number),
                                    style = MaterialTheme.typography.bodySmall.mono(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (mods.size > 5) {
                                Text(
                                    "…另有 " + (mods.size - 5) + " 个",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    enabled = !loading,
                                    onClick = {
                                        scope.launch {
                                            loading = true
                                            progress = "准备中…"
                                            ModInstaller.install(
                                                projectId = planHit.project_id,
                                                gameVersion = instance.minecraftVersion,
                                                loader = loader,
                                                modsDir = modsDir,
                                                onFile = { label, done, total ->
                                                    progress = done.toString() + "/" + total + " " + label
                                                }
                                            ).onSuccess { count ->
                                                refreshInstalled()
                                                message = "已安装 " + count + " 个文件（含依赖）"
                                            }.onFailure { error ->
                                                message = "安装失败：" + (error.message ?: "未知错误")
                                            }
                                            pendingPlan = null
                                            progress = null
                                            loading = false
                                        }
                                    }
                                ) { Text("开始下载") }
                                TextButton(
                                    enabled = !loading,
                                    onClick = { pendingPlan = null }
                                ) { Text("取消") }
                            }
                        }
                    }

                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(results, key = { it.project_id }) { hit ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(hit.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    hit.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "下载 " + hit.downloads,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val alreadyIn = isInstalled(hit.slug)
                                    TextButton(
                                        enabled = !loading && !alreadyIn,
                                        onClick = {
                                            scope.launch {
                                                loading = true
                                                progress = "解析依赖…"
                                                ModrinthClient.resolveDependencies(
                                                    rootProjectId = hit.project_id,
                                                    gameVersion = instance.minecraftVersion,
                                                    loader = loader
                                                ).let { plan ->
                                                    if (plan.isEmpty()) {
                                                        message = "没有找到适配 " + instance.minecraftVersion + " 的版本"
                                                    } else {
                                                        pendingPlan = hit to plan
                                                    }
                                                }
                                                progress = null
                                                loading = false
                                            }
                                        }
                                    ) { Text(if (alreadyIn) "已安装" else "安装") }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                if (message != null && !loading) {
                    Text(
                        message!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

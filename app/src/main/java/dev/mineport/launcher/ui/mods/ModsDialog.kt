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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mineport.launcher.data.GameInstance
import dev.mineport.launcher.mod.ModInstaller
import dev.mineport.launcher.mod.ModrinthClient
import dev.mineport.launcher.mod.ModrinthHit
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
fun ModsDialog(instance: GameInstance, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = instance.loader.name.lowercase(Locale.US)
    val supported = loader != "vanilla"

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ModrinthHit>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val modsDir = File(context.filesDir, "instances/" + instance.gameDirName + "/mods")

    var installed by remember { mutableStateOf(listOf<File>()) }
    fun refreshInstalled() {
        installed = modsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jar", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?: emptyList()
    }
    LaunchedEffect(modsDir.path) { refreshInstalled() }

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
                            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                        }
                    }

                    message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
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
                                    TextButton(onClick = {
                                        scope.launch {
                                            loading = true
                                            ModInstaller.install(
                                                projectId = hit.project_id,
                                                gameVersion = instance.minecraftVersion,
                                                loader = loader,
                                                modsDir = modsDir
                                            ).onSuccess { count ->
                                                message = "已安装 " + count + " 个文件到 " +
                                                    modsDir.absolutePath
                                            }.onFailure { error ->
                                                message = "安装失败：" + (error.message ?: "未知错误")
                                            }
                                            loading = false
                                        }
                                    }) { Text("安装") }
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

package dev.mineport.launcher.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mineport.launcher.download.VersionEntry
import dev.mineport.launcher.download.VersionManifest
import dev.mineport.launcher.download.VersionManifestClient
import dev.mineport.launcher.install.InstallReport
import dev.mineport.launcher.install.VersionInstaller
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import dev.mineport.launcher.ui.components.EmptyState
import dev.mineport.launcher.ui.motion.PixelDotsLoading
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private sealed interface ManifestUiState {
    data object Loading : ManifestUiState
    data class Error(val message: String) : ManifestUiState
    data class Ready(val manifest: VersionManifest, val fromCache: Boolean) : ManifestUiState
}

/**
 * 下载页第一阶段：真实版本清单（第 4 步验收的一半）。
 * 清单通了，才能谈安装：客户端、libraries、assets 的下载队列接在 FCLCore 之后。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    var state by remember { mutableStateOf<ManifestUiState>(ManifestUiState.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<VersionEntry?>(null) }
    var install by remember { mutableStateOf<InstallUi?>(null) }
    var installJob by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<InstallReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun startInstall(version: VersionEntry) {
        selected = null
        install = InstallUi(stage = "准备", done = 0, total = 0, current = "")
        installJob = scope.launch {
            try {
                val outcome = VersionInstaller.install(
                    versionId = version.id,
                    versionUrl = version.url,
                    rootDir = context.filesDir,
                    assetsDir = File(context.filesDir, "assets"),
                    onStage = { stage -> install = install?.copy(stage = stage) },
                    onFileProgress = { done, total, current ->
                        install = install?.copy(done = done, total = total, current = current)
                    }
                )
                outcome.onSuccess { result = it }.onFailure { error = it.message ?: "安装失败" }
            } catch (e: CancellationException) {
                // 已下到 .part 的文件保留，重新点安装会断点续传。
                error = "安装已取消；已经下载的文件会保留，重新安装会继续。"
                throw e
            } finally {
                install = null
                installJob = null
            }
        }
    }

    LaunchedEffect(reloadKey) {
        state = ManifestUiState.Loading
        val cache = File(context.filesDir, "cache/version_manifest_v2.json")
        state = when (val result = VersionManifestClient.load(cache)) {
            is VersionManifestClient.Result.Ok ->
                ManifestUiState.Ready(result.manifest, result.fromCache)
            is VersionManifestClient.Result.Fail ->
                ManifestUiState.Error(result.message)
        }
    }

    // 横屏可用高度只有约 375dp，TopAppBar 的 64dp + 大块统计卡太贵；
    // 标题、统计、刷新压成一行，把高度让给版本列表。
    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val snapshot = state) {
                is ManifestUiState.Loading -> LoadingBlock()
                is ManifestUiState.Error -> ErrorBlock(snapshot.message) { reloadKey++ }
                is ManifestUiState.Ready -> ReadyBlock(
                    state = snapshot,
                    onReload = { reloadKey++ },
                    onSelect = { selected = it }
                )
            }
        }
    }

    selected?.let { version ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(version.id) },
            text = {
                Text(
                    "类型：" + typeLabel(version.type) + "\n" +
                        "发布时间：" + version.releaseTime + "\n\n" +
                        "安装会下载客户端 jar、libraries、asset index 与全部 resources 到应用私有目录；" +
                        "启动参数由启动器按 version JSON 组装，不依赖外部组件。"
                )
            },
            confirmButton = {
                TextButton(onClick = { startInstall(version) }) { Text("安装") }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) { Text("取消") }
            }
        )
    }

    install?.let { ui ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("正在安装") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ui.stage, style = MaterialTheme.typography.bodyMedium)
                    if (ui.total > 0) {
                        LinearProgressIndicator(
                            progress = { ui.done.toFloat() / ui.total.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            ui.done.toString() + " / " + ui.total + " 个文件",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (ui.current.isNotBlank()) {
                        Text(
                            ui.current,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { installJob?.cancel() }) { Text("取消") }
            }
        )
    }

    result?.let { report ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(if (report.ok) "安装完成" else "有文件下载失败") },
            text = {
                if (report.ok) {
                    Text("共 " + report.totalFiles + " 个文件，已落到：\n" + report.versionDir.absolutePath)
                } else {
                    Text(
                        report.failed.take(8).joinToString("\n") { pair ->
                            pair.first + "：" + pair.second
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { result = null }) { Text("好") }
            }
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("安装失败") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { error = null }) { Text("知道了") }
            }
        )
    }
}

private data class InstallUi(
    val stage: String,
    val done: Int,
    val total: Int,
    val current: String
)

@Composable
private fun LoadingBlock() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        PixelDotsLoading(color = MaterialTheme.colorScheme.primary)
        Text("正在拉取版本清单…", style = MaterialTheme.typography.bodyMedium)
        Text(
            "BMCLAPI 镜像 → Mojang 官方 → 本地缓存",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("清单加载失败", style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun ReadyBlock(
    state: ManifestUiState.Ready,
    onReload: () -> Unit,
    onSelect: (VersionEntry) -> Unit
) {
    val manifest = state.manifest
    var query by remember { mutableStateOf("") }
    val shown = remember(manifest, query) {
        val q = normalizeVersionQuery(query)
        if (q.isEmpty()) manifest.versions
        else manifest.versions.filter { it.id.contains(q, ignoreCase = true) }
    }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // 头部（标题行 + 搜索框）约 116dp；滚过这段距离就完全淡出，手指不会觉得「卡住」。
    val headerFadePx = with(density) { 116.dp.toPx() }
    val headerLiftPx = with(density) { 18.dp.toPx() }
    val headerAlpha = remember(listState, headerFadePx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 0f
            else (1f - listState.firstVisibleItemScrollOffset / headerFadePx).coerceIn(0f, 1f)
        }
    }

    // 整页是一个 LazyColumn：头部是第 0 项，跟着滚；滚动时按距离淡出并轻微上移。
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)
    ) {
        item(key = "__header__") {
            Column(
                modifier = Modifier.graphicsLayer {
                    alpha = headerAlpha.value
                    translationY = -headerLiftPx * (1f - headerAlpha.value)
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "下载",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "最新 " + manifest.latest.release + " · " + manifest.versions.size +
                            " 个版本 · " + (if (state.fromCache) "离线缓存" else "在线"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onReload) { Text("刷新") }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    placeholder = { Text("搜版本号，如 1.20.1") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = MaterialTheme.shapes.medium
                )
            }
        }

        if (shown.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = "没有匹配的版本",
                    hint = "试着输入完整版本号，例如 1.20.1；或清空搜索框看全部版本。"
                )
            }
        }
        items(shown, key = { it.id }) { version ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSelect(version) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        version.id,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        typeLabel(version.type) + " · " + version.releaseTime.take(10),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "›",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun typeLabel(type: String): String = when (type) {
    "release" -> "正式版"
    "snapshot" -> "快照"
    "old_beta" -> "旧测试版"
    "old_alpha" -> "旧 Alpha"
    else -> type
}

/**
 * 版本号搜索的输入容错。
 *
 * 中文输入法会把 "." 打成全角 "。"（小米搜狗默认如此），还有 "．" 与全角数字混入，
 * 直接 contains 会一条都搜不到。这里统一转半角，顺带去掉首尾空白。
 */
private fun normalizeVersionQuery(raw: String): String {
    val sb = StringBuilder(raw.length)
    for (ch in raw.trim()) {
        when (ch) {
            '。', '．', '｡' -> sb.append('.')
            '－', '—' -> sb.append('-')
            in '０'..'９' -> sb.append('0' + (ch - '０'))
            '　' -> sb.append(' ')
            else -> sb.append(ch)
        }
    }
    return sb.toString().trim()
}

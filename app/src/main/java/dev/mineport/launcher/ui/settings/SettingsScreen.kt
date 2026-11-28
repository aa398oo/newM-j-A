package dev.mineport.launcher.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.mineport.launcher.ui.components.PageHeader
import dev.mineport.launcher.AppContainer
import dev.mineport.launcher.crash.CrashRecorder
import dev.mineport.launcher.crash.DiagnosticsExporter
import dev.mineport.launcher.jvm.StartupTrace
import dev.mineport.launcher.data.RenderBackend
import dev.mineport.launcher.jvm.RuntimeStager
import dev.mineport.launcher.net.LanDiscovery
import dev.mineport.launcher.ui.motion.EnterFromBottom
import dev.mineport.launcher.ui.motion.PixelDotsLoading
import dev.mineport.launcher.ui.theme.mono
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer) {
    val profile = container.deviceProfile
    var defaultBackend by remember { mutableStateOf(RenderBackend.AUTO) }
    var defaultMemory by remember { mutableFloatStateOf(profile.recommendedMemoryMB.toFloat()) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            PageHeader("设置")
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            EnterFromBottom(0) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("新建版本的默认渲染后端", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "自动 = 按机型推荐（" + profile.recommendedBackend.backend.uppercase() + "）",
                            style = MaterialTheme.typography.bodySmall
                        )
                        RenderBackend.entries.forEach { backend ->
                            androidx.compose.foundation.layout.Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                RadioButton(
                                    selected = defaultBackend == backend,
                                    onClick = { defaultBackend = backend }
                                )
                                Column(Modifier.padding(start = 4.dp)) {
                                    Text(backend.displayName, style = MaterialTheme.typography.bodyMedium)
                                    if (backend == RenderBackend.ZINK) {
                                        Text(
                                            "需要 Turnip 驱动，仅部分骁龙设备可用",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            EnterFromBottom(1) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("默认内存", style = MaterialTheme.typography.titleMedium)
                        Text(
                            defaultMemory.toInt().toString() + " MB（设备共 " + profile.totalRamMB + " MB）",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = defaultMemory,
                            onValueChange = { defaultMemory = it },
                            valueRange = 512f..4096f,
                            steps = 13
                        )
                        Text(
                            "堆越大越不容易因区块加载卡顿，但系统留给 GPU 驱动与纹理的内存会变少。" +
                                "高版本加光影时反而要把堆调小一点。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            EnterFromBottom(2) { AccountCard(container) }

            EnterFromBottom(3) { RuntimeCard() }

            EnterFromBottom(4) { CrashCard() }

            EnterFromBottom(5) { StartupCard() }

            EnterFromBottom(6) { DiagnosticsCard() }

            EnterFromBottom(7) { LanCard() }

            EnterFromBottom(8) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("关于", style = MaterialTheme.typography.titleMedium)
                        Text("newM-j-A 0.1.0（纵向切片阶段）", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "启动链路目前直接调用原版 mainClass；FCLCore 接入后由它接管窗口与输入层。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("许可", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "本项目以 GPL-3.0 发布。内置运行时与图形栈来自各自上游：" +
                                "OpenJDK 17（GPLv2 + Classpath Exception）、LWJGL 3（BSD-3）、" +
                                "GL4ES（MIT）、Jetpack Compose（Apache-2.0）、Fabric Loader（Apache-2.0）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        }
    }
}

/** 运行时自检：不触发解包，只报告 filesDir/runtime 的落地状态（第 1/2 步验收用）。 */
@Composable
private fun RuntimeCard() {
    val context = LocalContext.current
    var state by remember { mutableStateOf<RuntimeStager.State?>(null) }
    LaunchedEffect(Unit) {
        state = RuntimeStager.inspect(File(context.filesDir, "runtime"))
    }
    val snapshot = state
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("运行时", style = MaterialTheme.typography.titleMedium)
            if (snapshot == null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PixelDotsLoading(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("检查中", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(
                    if (snapshot.libjvmPresent) "libjvm.so 已就位" else "缺 libjvm.so（见 docs/binaries.md）",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    if (snapshot.modulePresent) "lib/modules 已就位" else "缺 lib/modules",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "目录：" + File(context.filesDir, "runtime").absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 上次启动停在哪一步：native 崩掉时 Java 层写不了栈，但里程碑已经落盘。 */
@Composable
private fun StartupCard() {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        text = StartupTrace.read(context)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("上次启动进度", style = MaterialTheme.typography.titleMedium)
            val body = text
            if (body == null) {
                Text(
                    "没有记录（还没启动过游戏，或记录被清空）。启动时会在每一步写一行，" +
                        "闪退后回到这里就能看到停在哪个阶段。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = {
                    StartupTrace.clear(context)
                    text = null
                }) {
                    Text("清空")
                }
            }
        }
    }
}

/** 联机基础：扫描同一 WiFi 下的 Minecraft 局域网世界（D37，不自建联机服务）。 */
@Composable
private fun LanCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var worlds by remember { mutableStateOf<List<LanDiscovery.LanWorld>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("联机", style = MaterialTheme.typography.titleMedium)
            Text(
                "同一 WiFi 下扫描「对局域网开放」的世界。点地址复制，进游戏后在「多人 → 直接连接」里粘贴。" +
                    "跨网联机需要端口转发或虚拟局域网，本应用不内置中转服务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                enabled = !scanning,
                onClick = {
                    scanning = true
                    hint = null
                    scope.launch {
                        worlds = LanDiscovery.scan(context) { world ->
                            worlds = (worlds + world).distinctBy { it.connectAddress }
                        }
                        scanning = false
                        if (worlds.isEmpty()) {
                            hint = "没有发现世界：先在另一台设备上「对局域网开放」，并确认两台设备在同一 WiFi。"
                        }
                    }
                }
            ) { Text(if (scanning) "扫描中…" else "扫描局域网世界") }
            worlds.forEach { world ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(world.motd, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            world.connectAddress,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(world.connectAddress))
                        hint = "已复制 " + world.connectAddress
                    }) { Text("复制地址") }
                }
            }
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 诊断包：设备信息 + 启动进度 + 崩溃记录，一键分享出去，不用连电脑。 */
@Composable
private fun DiagnosticsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var perfSummary by remember { mutableStateOf<String?>(null) }
    // 性能基线：GameActivity 里 PerfSampler 每 2s 追加一行 JSON，这里只读最后一行做展示。
    LaunchedEffect(Unit) {
        perfSummary = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val f = java.io.File(context.filesDir, "perf-game.log")
                if (!f.isFile) {
                    "尚无采样（启动一次游戏后自动记录）"
                } else {
                    val lines = f.readLines().filter { it.isNotBlank() }
                    "采样 " + lines.size + " 条\n最近：" + (lines.lastOrNull() ?: "")
                }
            }.getOrElse { "读取失败：" + (it.message ?: it) }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("诊断与日志", style = MaterialTheme.typography.titleMedium)
            Text(
                "把设备信息、上次启动进度和崩溃记录打成一个文本文件，用系统分享发给开发者。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = DiagnosticsExporter.export(context)
                            busy = false
                            result.onSuccess { file ->
                                DiagnosticsExporter.share(context, file)
                                message = "已生成：" + file.name
                            }.onFailure { error ->
                                message = "导出失败：" + (error.message ?: error)
                            }
                        }
                    }
                ) { Text(if (busy) "导出中…" else "导出并分享诊断包") }
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            perfSummary?.let {
                Text("性能采样", style = MaterialTheme.typography.labelMedium)
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall.mono(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CrashCard() {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        text = CrashRecorder.read(context)
        loaded = true
    }
    val clipboard = LocalClipboardManager.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("上次崩溃", style = MaterialTheme.typography.titleMedium)
            val body = text
            when {
                !loaded -> Text("读取中", style = MaterialTheme.typography.bodySmall)
                body == null -> Text(
                    "没有记录。只有 Java 层异常会写到这里；如果一点启动就闪退且这里为空，" +
                        "多半是 native 崩了（libjvm / 16KB 页），需要 logcat。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    Text(
                        body.take(1500),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(body)) }) {
                            Text("复制全文")
                        }
                        TextButton(onClick = {
                            CrashRecorder.clear(context)
                            text = null
                        }) {
                            Text("清空")
                        }
                    }
                }
            }
        }
    }
}

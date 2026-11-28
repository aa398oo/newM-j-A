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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mineport.launcher.AppContainer
import dev.mineport.launcher.data.GameInstance
import dev.mineport.launcher.data.Loader
import dev.mineport.launcher.data.javaMajorFor
import dev.mineport.launcher.download.VersionEntry
import dev.mineport.launcher.install.FabricLoaderInstaller
import dev.mineport.launcher.install.ForgeInstaller
import dev.mineport.launcher.install.ForgeJvmRunner
import dev.mineport.launcher.install.VersionInstaller
import dev.mineport.launcher.jvm.JvmLauncher
import dev.mineport.launcher.jvm.RuntimeStager
import dev.mineport.launcher.ui.components.EmptyState
import dev.mineport.launcher.ui.motion.LocalMotionStage
import dev.mineport.launcher.ui.motion.MotionIntent
import dev.mineport.launcher.ui.motion.PixelDotsLoading
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 版本安装页（点版本清单里的一行进来）。
 *
 * 结构参照 PCL II 的「安装预览」：先把要装的东西摆成一张清单，
 * 再让人选加载器、改版本名，最后一下开始装。
 *
 * 为什么不在列表里直接弹「确认安装」：原版 + 加载器是两次安装，
 * 中间还要选 loader 版本，弹窗塞不下也不该塞。
 */
@Composable
fun VersionInstallScreen(
    container: AppContainer,
    version: VersionEntry,
    onBack: () -> Unit,
    /** @param name 新版本的名字 */
    onDone: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(version.id) { mutableStateOf(version.id) }
    var loader by remember(version.id) { mutableStateOf(Loader.VANILLA) }
    // Fabric 装完返回的是 profile id（fabric-loader-0.16.x-1.20.1），实例里就存它。
    var loaderId by remember(version.id) { mutableStateOf<String?>(null) }
    var pickingLoader by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf<String?>(null) }
    var fileProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val motion = LocalMotionStage.current

    val busy = stage != null
    val javaTarget = javaMajorFor(version.id)

    fun start() {
        if (busy) return
        scope.launch {
            error = null
            try {
                // 1) 原版本体：已装过就跳过（比如之前只装了原版、这次来补加载器）。
                val vanillaJson = File(context.filesDir, "versions/${version.id}/${version.id}.json")
                if (!vanillaJson.isFile) {
                    stage = "安装原版 ${version.id}"
                    val outcome = VersionInstaller.install(
                        versionId = version.id,
                        versionUrl = version.url,
                        rootDir = context.filesDir,
                        assetsDir = File(context.filesDir, "assets"),
                        onStage = { stage = it },
                        onFileProgress = { done, total, _ -> fileProgress = done to total }
                    )
                    if (outcome.isFailure) {
                        error = "原版安装失败：" + (outcome.exceptionOrNull()?.message ?: "未知错误")
                        return@launch
                    }
                }
                fileProgress = null

                // 2) 加载器：Fabric 走纯 Kotlin 合并；Forge 要跑官方 install_profile 的 processor 链。
                //
                // 实例先建出来再装：Forge 的安装 JVM 会把 `-Dfcl.game.dir` 固定成实例目录，
                // 装完复用同一台 JVM 进游戏时，目录/用户名/分辨率都已经是这台实例的，
                // 不需要「装完请重启应用」。
                val draft = GameInstance(
                    name = name.trim().ifBlank { version.id },
                    minecraftVersion = version.id,
                    loader = loader,
                    javaVersion = javaTarget,
                    memoryMB = container.deviceProfile.recommendedMemoryMB
                )
                var resolvedLoader: String? = null
                if (loader == Loader.FABRIC) {
                    stage = "安装 Fabric 加载器"
                    val outcome = FabricLoaderInstaller.install(
                        rootDir = context.filesDir,
                        minecraftVersion = version.id,
                        loaderVersion = loaderId,
                        onStage = { stage = it }
                    )
                    if (outcome.isFailure) {
                        error = "Fabric 安装失败：" + (outcome.exceptionOrNull()?.message ?: "未知错误")
                        return@launch
                    }
                    resolvedLoader = outcome.getOrNull()
                } else if (loader == Loader.FORGE) {
                    // 进程内 JVM 只能创建一次，classpath 建好就改不了。装 Forge 要往
                    // classpath 里塞安装期工具，所以必须拿一台还没建过的 JVM。
                    val existing = runCatching { JvmLauncher.nativeRuntimeVersion() }.getOrDefault("")
                    if (existing.isNotBlank()) {
                        error = "安装 Forge 需要一台干净的 JVM（当前进程的 JVM 已经建好了，classpath 改不了）。\n" +
                            "请先从最近任务里划掉本应用，再回来重装。"
                        return@launch
                    }
                    val runtimeRoot = File(context.filesDir, "runtime")
                    stage = "准备运行时"
                    withContext(Dispatchers.IO) { RuntimeStager.ensure(context, runtimeRoot) }
                    val runtimeState = RuntimeStager.inspect(runtimeRoot)
                    if (!runtimeState.libjvmPresent || !runtimeState.modulePresent) {
                        error = "安装 Forge 需要内置运行时：缺 libjvm.so 或 lib/modules（" +
                            runtimeRoot.absolutePath + "）"
                        return@launch
                    }
                    val clientJar = File(context.filesDir, "versions/${version.id}/${version.id}.jar")
                    // 先把实例落盘：下面那台 JVM 的 gameDir 指向它，属性建好后改不了。
                    container.instanceStore.upsert(draft)
                    val metrics = context.resources.displayMetrics
                    val runner = ForgeJvmRunner(
                        context = context,
                        rootDir = context.filesDir,
                        minecraftVersion = version.id,
                        memoryMB = container.deviceProfile.recommendedMemoryMB,
                        runtimeRoot = runtimeRoot,
                        gameDir = File(context.filesDir, "instances/" + draft.gameDirName),
                        playerName = draft.playerName,
                        width = if (draft.resolutionWidth > 0) draft.resolutionWidth else metrics.widthPixels,
                        height = if (draft.resolutionHeight > 0) draft.resolutionHeight else metrics.heightPixels,
                        onStage = { stage = it }
                    )
                    val outcome = ForgeInstaller.install(
                        rootDir = context.filesDir,
                        installerUrl = ForgeInstaller.installerUrl(),
                        minecraftClientJar = clientJar,
                        onStage = { stage = it },
                        runner = runner,
                        onProgress = { done, total ->
                            if (total > 0) fileProgress = done.toInt() to total.toInt()
                        }
                    )
                    if (outcome.isFailure) {
                        // 装失败别留半成品：实例一存在，首页就会把它当可启动版本。
                        container.instanceStore.delete(draft.id)
                        error = "Forge 安装失败：" + (outcome.exceptionOrNull()?.message ?: "未知错误")
                        return@launch
                    }
                    resolvedLoader = ForgeInstaller.loaderVersion()
                }

                // 3) 落成版本记录。（Forge 分支为了给安装 JVM 一个稳定的 gameDir，
                //    前面已经 upsert 过一次；同 id 覆盖，不会多出一条。）
                val instance = draft.copy(loaderVersion = resolvedLoader)
                container.instanceStore.upsert(instance)
                onDone(instance.name)
            } catch (e: CancellationException) {
                throw e
            } finally {
                stage = null
                fileProgress = null
            }
        }
    }

    // 父级是 Box：安装页与 loader 选择页必须显式分层，否则两块 fillMaxSize 会叠在一起透字。
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack, enabled = !busy) { Text("‹ 版本") }
            Spacer(Modifier.width(4.dp))
            Text(
                "安装 " + version.id,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Java " + javaTarget + " · 自动分配内存",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 名称直接做进预览卡：横屏只剩 375dp 高，独立输入框要多吃 70dp。
            PreviewCard(
                versionId = version.id,
                name = name,
                enabled = !busy,
                onNameChange = { name = it }
            )

            PickRow(
                title = "Minecraft",
                detail = version.id,
                trailing = "本体",
                onClick = null
            )
            PickRow(
                title = "Fabric",
                detail = loaderId?.let { "已选 " + it } ?: "未选择",
                trailing = if (loader == Loader.FABRIC) "更换 ›" else "点击选择 ›",
                onClick = if (busy) null else ({ pickingLoader = true })
            )
            val forgeSupported = version.id == ForgeInstaller.SUPPORTED_MINECRAFT
            PickRow(
                title = "Forge",
                detail = when {
                    !forgeSupported -> "仅支持 " + ForgeInstaller.SUPPORTED_MINECRAFT
                    loader == Loader.FORGE -> "已选 " + ForgeInstaller.SUPPORTED_FORGE
                    else -> ForgeInstaller.SUPPORTED_FORGE + "（首发只做这一个版本）"
                },
                trailing = when {
                    !forgeSupported -> null
                    loader == Loader.FORGE -> "已选 ›"
                    else -> "点击选择 ›"
                },
                onClick = if (!forgeSupported || busy) null else ({ loader = Loader.FORGE; loaderId = null })
            )
            PickRow(
                title = "OptiFine",
                detail = "暂未支持",
                trailing = null,
                onClick = null
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when {
                    loader == Loader.FABRIC -> "将安装：原版 " + version.id + " + Fabric"
                    loader == Loader.FORGE -> "将安装：原版 " + version.id + " + Forge " + ForgeInstaller.SUPPORTED_FORGE
                    else -> "将安装：原版 " + version.id
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Button(onClick = { start() }, enabled = !busy) {
                Text(if (busy) "安装中…" else "开始安装")
            }
        }
    }

    if (pickingLoader) {
        FabricLoaderPicker(
            minecraftVersion = version.id,
            onBack = { pickingLoader = false },
            onPick = { picked ->
                loader = Loader.FABRIC
                loaderId = picked
                pickingLoader = false
                // 选出 Fabric 版本 = 这个方块被装上了加载器：让 rail 旁的命令方块
                // 被三条丝带绕一圈唤醒（UX-22 T6）。宿主在 RootScreen，靠 CompositionLocal 拿。
                motion?.play(MotionIntent.Awaken)
            }
        )
    }

    stage?.let { text ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("正在安装") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                    val progress = fileProgress
                    if (progress != null && progress.second > 0) {
                        LinearProgressIndicator(
                            progress = { progress.first.toFloat() / progress.second.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            progress.first.toString() + " / " + progress.second + " 个文件",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "下载可断点续传；中断后重新点「开始安装」会接着下。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { }
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("安装失败") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }
        )
    }
    }
}

/** 顶部预览卡：左侧方块标识 + 可直接改的名称 / 版本。开始安装固定在底栏。 */
@Composable
private fun PreviewCard(
    versionId: String,
    name: String,
    enabled: Boolean,
    onNameChange: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 没有图片资源，用色块 + 首字母代替草方块图标——和下载页列表保持一致。
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(cs.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    versionId.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                BasicTextField(
                    value = name,
                    onValueChange = onNameChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        color = cs.onSurface,
                        fontWeight = FontWeight.SemiBold
                    ),
                    cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        // 空值时给个占位，不然一行空白不知道怎么下手。
                        Box {
                            if (name.isEmpty()) {
                                Text(
                                    "版本名称",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = cs.onSurfaceVariant
                                )
                            }
                            inner()
                        }
                    }
                )
                Text(
                    "Minecraft " + versionId + " · 点名称可改",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
        }
    }
}

/** 清单里的一行：标题 + 说明 + 右侧动作（null 表示不可点）。 */
@Composable
private fun PickRow(
    title: String,
    detail: String,
    trailing: String?,
    onClick: (() -> Unit)?
) {
    val cs = MaterialTheme.colorScheme
    val enabled = onClick != null
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(96.dp)
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f)
            )
            if (trailing != null) {
                Text(
                    trailing,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (enabled) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/** 选 Fabric loader 版本：点版本清单里的 Fabric 行进来。 */
@Composable
private fun FabricLoaderPicker(
    minecraftVersion: String,
    onBack: () -> Unit,
    onPick: (String) -> Unit
) {
    var options by remember(minecraftVersion) {
        mutableStateOf<List<FabricLoaderInstaller.LoaderOption>?>(null)
    }
    var failure by remember(minecraftVersion) { mutableStateOf<String?>(null) }
    var reload by remember(minecraftVersion) { mutableStateOf(0) }

    LaunchedEffect(minecraftVersion, reload) {
        options = null
        failure = null
        FabricLoaderInstaller.listLoaders(minecraftVersion)
            .onSuccess { options = it }
            .onFailure { failure = it.message ?: "拉取 Fabric 版本失败" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Spacer(Modifier.width(4.dp))
            Text(
                "Fabric · " + minecraftVersion,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            if (options != null) {
                Text(
                    (options?.size ?: 0).toString() + " 个版本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val list = options
        val fail = failure
        when {
            fail != null -> Column(
                modifier = Modifier.fillMaxSize().padding(vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("拉取失败", style = MaterialTheme.typography.titleSmall)
                Text(fail, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { reload++ }) { Text("重试") }
            }

            list == null -> Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PixelDotsLoading(color = MaterialTheme.colorScheme.primary)
                Text("正在拉取 Fabric 版本…", style = MaterialTheme.typography.bodySmall)
            }

            list.isEmpty() -> EmptyState(
                icon = Icons.Filled.Search,
                title = "没有可用的 Fabric 版本",
                hint = "这个 Minecraft 版本 Fabric 还没出 loader，换版本或用原版。"
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(list, key = { it.version }) { option ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(option.version) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                option.version,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (option.stable) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        "稳定",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
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

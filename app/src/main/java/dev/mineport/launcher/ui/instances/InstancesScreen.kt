package dev.mineport.launcher.ui.instances

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import dev.mineport.launcher.ui.components.EmptyState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mineport.launcher.AppContainer
import dev.mineport.launcher.MineportApplication
import dev.mineport.launcher.data.GameInstance
import dev.mineport.launcher.data.Loader
import dev.mineport.launcher.data.RenderBackend
import dev.mineport.launcher.data.javaMajorFor
import dev.mineport.launcher.game.GameActivity
import dev.mineport.launcher.install.FabricLoaderInstaller
import dev.mineport.launcher.install.QuiltLoaderInstaller
import dev.mineport.launcher.install.VersionImporter
import dev.mineport.launcher.install.ZipVersionImporter
import dev.mineport.launcher.share.ShareConfig
import dev.mineport.launcher.share.ShortCode
import dev.mineport.launcher.render.ShaderInstaller
import dev.mineport.launcher.ui.mods.ModsDialog
import dev.mineport.launcher.ui.motion.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstancesScreen(container: AppContainer) {
    val instances by container.instanceStore.instances.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showCreate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GameInstance?>(null) }
    var modsFor by remember { mutableStateOf<GameInstance?>(null) }
    var pendingDelete by remember { mutableStateOf<GameInstance?>(null) }
    // 导入桌面端已装好的版本目录（Forge/NeoForge/整合包），复制进私有 versions/。
    val importVersionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    VersionImporter.import(context, uri, File(context.filesDir, "versions"))
                }
                result.onSuccess { id ->
                    container.instanceStore.upsert(
                        GameInstance(
                            name = "导入 " + id,
                            minecraftVersion = id,
                            javaVersion = javaMajorFor(id)
                        )
                    )
                    Toast.makeText(context, "已导入版本 $id", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(context, "导入失败：" + (it.message ?: "未知错误"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    // 从标准整合包 zip（versions/<id>/<id>.json）导入版本目录。
    val importZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    ZipVersionImporter.import(
                        context,
                        uri,
                        File(context.filesDir, "versions"),
                        context.cacheDir
                    )
                }
                result.onSuccess { id ->
                    container.instanceStore.upsert(
                        GameInstance(
                            name = "导入 " + id,
                            minecraftVersion = id,
                            javaVersion = javaMajorFor(id)
                        )
                    )
                    Toast.makeText(context, "已导入版本 $id", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(context, "导入失败：" + (it.message ?: "未知错误"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val fabSource = remember { MutableInteractionSource() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("版本") },
                actions = {
                    TextButton(onClick = { importVersionLauncher.launch(null) }) { Text("导入目录") }
                    TextButton(onClick = { importZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) {
                        Text("导入 zip")
                    }
                    TextButton(onClick = { showImport = true }) { Text("短码导入") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreate = true },
                modifier = Modifier.pressScale(fabSource),
                interactionSource = fabSource
            ) {
                Icon(Icons.Filled.Add, contentDescription = "新建版本")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (instances.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Add,
                        title = "还没有版本",
                        hint = "每个版本是一份独立的游戏目录：版本、加载器、模组互不干扰。\n先在「下载」里装一个版本，或用右下角 + 手动新建。"
                    )
                }
            }
            items(instances, key = { it.id }) { instance ->
                Card(
                    modifier = Modifier.fillMaxWidth().animateItem()
                        .clickable { editing = instance }
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(instance.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                instance.subtitle + " · " + instance.renderBackend.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                instance.memoryMB.toString() + " MB · Java " +
                                    javaMajorFor(instance.minecraftVersion),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { context.startActivity(GameActivity.intent(context, instance.id)) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "启动")
                        }
                        IconButton(onClick = { pendingDelete = instance }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateInstanceDialog(
            suggestedMemory = container.deviceProfile.recommendedMemoryMB,
            onDismiss = { showCreate = false },
            onCreate = { instance ->
                scope.launch { container.instanceStore.upsert(instance) }
                showCreate = false
            }
        )
    }

    if (showImport) {
        ImportCodeDialog(
            lookup = { code -> container.shareStore.load(code) },
            onDismiss = { showImport = false },
            onImport = { code, config ->
                scope.launch {
                    container.instanceStore.upsert(
                        GameInstance(
                            name = "来自短码 " + code,
                            minecraftVersion = config.version,
                            loader = loaderOf(config.loader),
                            loaderVersion = config.loaderVersion,
                            javaVersion = javaMajorFor(config.version),
                            memoryMB = config.memoryMB,
                            renderBackend = backendOf(config.backend)
                        )
                    )
                }
                showImport = false
            }
        )
    }

    pendingDelete?.let { instance ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除版本") },
            text = {
                Text(
                    "确定删除「" + instance.name + "」？\n\n" +
                        "只删除版本配置，游戏目录（" + instance.gameDirName + "）里的存档与模组会保留。"
                )
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch { container.instanceStore.delete(instance.id) }
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    editing?.let { instance ->
        InstanceSettingsDialog(
            instance = instance,
            onDismiss = { editing = null },
            onSave = { updated ->
                scope.launch { container.instanceStore.upsert(updated) }
                editing = null
            },
            onOpenMods = { target ->
                editing = null
                modsFor = target
            },
            onGenerateShareCode = { loader, memory, backend ->
                container.shareStore.create(
                    ShareConfig(
                        version = instance.minecraftVersion,
                        loader = loader.name.lowercase(Locale.US),
                        loaderVersion = instance.loaderVersion,
                        memoryMB = memory,
                        backend = backend.name.lowercase(Locale.US)
                    )
                )
            }
        )
    }

    modsFor?.let { instance ->
        ModsDialog(
            instance = instance,
            onDismiss = { modsFor = null },
            onOpenSettings = {
                modsFor = null
                editing = instance
            }
        )
    }
}

/** 版本设置：渲染后端、内存、模组加载器；模组入口在加载器选好后可用。 */
@Composable
private fun InstanceSettingsDialog(
    instance: GameInstance,
    onDismiss: () -> Unit,
    onSave: (GameInstance) -> Unit,
    onOpenMods: (GameInstance) -> Unit,
    onGenerateShareCode: (Loader, Int, RenderBackend) -> String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backend by remember { mutableStateOf(instance.renderBackend) }
    var loader by remember { mutableStateOf(instance.loader) }
    var loaderVersion by remember { mutableStateOf(instance.loaderVersion) }
    var loaderBusy by remember { mutableStateOf(false) }
    var loaderMessage by remember { mutableStateOf<String?>(null) }
    var memory by remember { mutableFloatStateOf(instance.memoryMB.toFloat()) }
    var wantShaders by remember { mutableStateOf(instance.wantShaders) }
    var shaderBusy by remember { mutableStateOf(false) }
    var shaderMsg by remember { mutableStateOf<String?>(null) }
    var useCacio by remember { mutableStateOf(instance.useCacio) }
    var shareCode by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(instance.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("渲染后端", style = MaterialTheme.typography.labelLarge)
                RenderBackend.values().forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable { backend = option },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = backend == option, onClick = { backend = option })
                        Text(option.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("模组加载器", style = MaterialTheme.typography.labelLarge)
                Loader.values().forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable { loader = option },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = loader == option, onClick = { loader = option })
                        Text(option.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (loader == Loader.FABRIC || loader == Loader.QUILT) {
                    val loaderName = if (loader == Loader.FABRIC) "Fabric" else "Quilt"
                    Text(
                        loaderVersion?.let { "已安装 $loaderName：$it" } ?: "尚未安装 $loaderName 加载器",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        enabled = !loaderBusy,
                        onClick = {
                            val app = context.applicationContext as MineportApplication
                            loaderBusy = true
                            scope.launch {
                                val result = if (loader == Loader.FABRIC) {
                                    FabricLoaderInstaller.install(
                                        rootDir = app.filesDir,
                                        minecraftVersion = instance.minecraftVersion
                                    ) { stage -> loaderMessage = stage }
                                } else {
                                    QuiltLoaderInstaller.install(
                                        rootDir = app.filesDir,
                                        minecraftVersion = instance.minecraftVersion
                                    ) { stage -> loaderMessage = stage }
                                }
                                loaderBusy = false
                                result.onSuccess { id ->
                                    loaderVersion = id
                                    loaderMessage = "已安装：$id"
                                }.onFailure { error ->
                                    loaderMessage = "安装失败：" + (error.message ?: error)
                                }
                            }
                        }
                    ) { Text(if (loaderBusy) "安装中…" else "安装 $loaderName 加载器") }
                    loaderMessage?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (loader == Loader.FORGE || loader == Loader.NEOFORGE) {
                    Text(
                        "这个加载器暂未接入一键安装；可先用 Fabric/Quilt，或把官方 installer 打好的版本放到 versions/ 目录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "内存：" + memory.toInt() + " MB",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = memory,
                    onValueChange = { memory = it },
                    valueRange = 512f..4096f,
                    steps = 13
                )
                Text(
                    "GL4ES 适用范围最广；Zink 需要设备能加载 Turnip 驱动，失败会自动回退。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier.fillMaxWidth().clickable { wantShaders = !wantShaders },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = wantShaders, onCheckedChange = { wantShaders = it })
                    Column(Modifier.weight(1f)) {
                        Text("光影", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "光影要由 Iris（Fabric）接管渲染管线；这个开关只决定后端走 Zink 还是 GL4ES。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (wantShaders) {
                    val ctx = LocalContext.current
                    val instanceMods = remember(instance.gameDirName) {
                        ShaderInstaller.modsDir(ctx.filesDir, instance.gameDirName)
                    }
                    val irisThere = remember(instanceMods, shaderBusy, shaderMsg) {
                        ShaderInstaller.isIrisInstalled(instanceMods)
                    }
                    if (loader != Loader.FABRIC) {
                        Text(
                            "Iris 只能装在 Fabric 版本上：请先把上面的加载器切到 Fabric 并安装。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                enabled = !shaderBusy,
                                onClick = {
                                    shaderBusy = true
                                    shaderMsg = null
                                    scope.launch {
                                        val r = ShaderInstaller.installIris(
                                            instance.minecraftVersion,
                                            instanceMods
                                        ) { label, done, total ->
                                            shaderMsg = "$done/$total $label"
                                        }
                                        shaderMsg = r.fold(
                                            { "已写入 $it 个文件（Iris + 必需依赖）" },
                                            { "失败：" + (it.message ?: it.toString()) }
                                        )
                                        shaderBusy = false
                                    }
                                }
                            ) { Text(if (irisThere) "重装 Iris" else "安装 Iris 光影加载器") }
                            if (shaderBusy) {
                                Text(
                                    "  下载中…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        shaderMsg?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val packsDir = ShaderInstaller.shaderPacksDir(ctx.filesDir, instance.gameDirName)
                        var packMsg by remember { mutableStateOf<String?>(null) }
                        val installedPacks = remember(packsDir, packMsg) {
                            ShaderInstaller.installedPacks(packsDir)
                        }
                        Text("推荐光影包", style = MaterialTheme.typography.labelMedium)
                        ShaderInstaller.recommended.forEach { pack ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(pack.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        pack.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(
                                    enabled = !shaderBusy,
                                    onClick = {
                                        shaderBusy = true
                                        packMsg = null
                                        scope.launch {
                                            val r = ShaderInstaller.installPack(pack.slug, packsDir) { stage ->
                                                packMsg = stage
                                            }
                                            packMsg = r.fold(
                                                { "已下载 " + it },
                                                { "失败：" + (it.message ?: it.toString()) }
                                            )
                                            shaderBusy = false
                                        }
                                    }
                                ) { Text("下载") }
                            }
                        }
                        packMsg?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (installedPacks.isNotEmpty()) {
                            Text(
                                "已装 " + installedPacks.size + " 个：" + installedPacks.joinToString("、"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "目录：" + packsDir.absolutePath +
                                "（也可手动把 .zip 拖进来）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { useCacio = !useCacio },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = useCacio, onCheckedChange = { useCacio = it })
                    Column(Modifier.weight(1f)) {
                        Text("AWT 桥（caciocavallo，实验）", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "给 JVM 提供 Android 的 AWT 窗口层，原版 GLFW 才有机会拿到画面；不确定时保持关闭。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(
                    onClick = {
                        onOpenMods(
                            instance.copy(
                                renderBackend = backend,
                                loader = loader,
                                loaderVersion = loaderVersion,
                                memoryMB = memory.toInt(),
                                wantShaders = wantShaders,
                                useCacio = useCacio
                            )
                        )
                    }
                ) { Text("安装模组…") }
                Text("分享", style = MaterialTheme.typography.labelLarge)
                if (shareCode == null) {
                    TextButton(
                        onClick = { shareCode = onGenerateShareCode(loader, memory.toInt(), backend) }
                    ) { Text("生成 6 位分享码") }
                    Text(
                        "短码只包含版本、加载器与内存等引用信息，不含游戏文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            shareCode ?: "",
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Monospace
                        )
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("newM-j-A 分享码", shareCode)
                                )
                                copied = true
                            }
                        ) { Text(if (copied) "已复制" else "复制") }
                    }
                    Text(
                        "对方在「版本 → 短码导入」里输入即可复刻配置。跨设备分享需要短码服务，本地码只在本机有效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    instance.copy(
                        renderBackend = backend,
                        loader = loader,
                        loaderVersion = loaderVersion,
                        memoryMB = memory.toInt(),
                        wantShaders = wantShaders,
                        useCacio = useCacio
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 从 6 位短码复刻版本配置。跨设备前先把 [lookup] 换成远端查询即可。 */
@Composable
private fun ImportCodeDialog(
    lookup: (String) -> ShareConfig?,
    onDismiss: () -> Unit,
    onImport: (String, ShareConfig) -> Unit
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val normalized = ShortCode.normalize(code)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("短码导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it.uppercase(Locale.US).take(8)
                        error = null
                    },
                    label = { Text("6 位短码") },
                    placeholder = { Text("如 K7M2Q5") },
                    singleLine = true
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "短码由对方在「版本设置 → 分享」里生成；只带版本与配置引用，不传游戏文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                enabled = normalized.isNotEmpty(),
                onClick = {
                    if (!ShortCode.isValid(normalized)) {
                        error = "短码校验未通过：请核对每一位，容易混淆的 O/I/L 可写成 0/1/1"
                        return@Button
                    }
                    val config = lookup(normalized)
                    if (config == null) {
                        error = "本机没有登记这个短码。跨设备分享需要短码服务，目前只支持本机导入。"
                        return@Button
                    }
                    onImport(normalized, config)
                }
            ) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CreateInstanceDialog(
    suggestedMemory: Int,
    onDismiss: () -> Unit,
    onCreate: (GameInstance) -> Unit
) {
    var name by remember { mutableStateOf("新的版本") }
    var version by remember { mutableStateOf("1.20.1") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建版本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = version,
                    onValueChange = { version = it },
                    label = { Text("Minecraft 版本") },
                    singleLine = true
                )
                Text(
                    "内存默认 " + suggestedMemory + " MB，点版本卡片可调整内存与渲染后端。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && version.isNotBlank(),
                onClick = {
                    onCreate(
                        GameInstance(
                            name = name.trim(),
                            minecraftVersion = version.trim(),
                            javaVersion = javaMajorFor(version),
                            memoryMB = suggestedMemory
                        )
                    )
                }
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun loaderOf(id: String): Loader =
    Loader.entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: Loader.VANILLA

private fun backendOf(id: String): RenderBackend =
    RenderBackend.entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: RenderBackend.AUTO

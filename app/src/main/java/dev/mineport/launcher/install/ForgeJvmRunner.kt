package dev.mineport.launcher.install

import android.content.Context
import android.util.Log
import dev.mineport.launcher.game.MinecraftLauncher
import dev.mineport.launcher.jvm.JvmLaunchSpec
import dev.mineport.launcher.jvm.JvmLauncher
import dev.mineport.launcher.jvm.JvmStartResult
import java.io.File

/**
 * Android 侧的 Forge processor 执行器：把 6 个安装期工具跑在进程内的 HotSpot 里。
 *
 * 为什么 classpath 要扫整个 libraries 目录：
 * `install_profile.json` 里的 processor 依赖（installertools / jarsplitter / FART /
 * binarypatcher）与它们自己的传递依赖，位置由 profile 决定，安装前不知道具体清单。
 * stage 阶段已经把该下的都下完了，这里全量扫一遍最省事，也顺便把 Forge 运行时库
 * （version.json 里那 29 个）放进 classpath——安装完再进游戏时复用同一个 JVM，
 * 它需要的类此刻已经可见。
 *
 * 为什么不建第二个 JVM：`jni_bridge.cpp` 的 `nativeStartJvm` 一个进程只能成功一次
 * （HotSpot 的约束，不是我们的选择），`stop()` 也只是标记，不能重建。所以安装用的这个
 * JVM 必须「装完还能跑游戏」——内存和 lwjgl 目录都按游戏的标准配置，见下面 [startJvm]。
 */
class ForgeJvmRunner(
    private val context: Context,
    private val rootDir: File,
    private val minecraftVersion: String,
    private val memoryMB: Int,
    private val runtimeRoot: File,
    /**
     * 安装期 JVM 的 `-Dfcl.game.dir`。
     *
     * 默认给一个临时目录，但真正该传的是**目标实例的目录**：同一个进程只能建一台
     * JVM，装完进游戏会复用它，`-Dfcl.*` 这时改不了。传实例目录，装完就能直接玩。
     */
    private val gameDir: File = File(rootDir, "instances/forge-install"),
    private val playerName: String = "installer",
    private val width: Int = 1280,
    private val height: Int = 720,
    private val onStage: (String) -> Unit = {}
) : ForgeInstaller.ProcessorRunner {

    private var started = false
    private var failure: String? = null
    private var stagedJvmArgs: List<String> = emptyList()
    private var stagedRuns: List<ForgeInstaller.ProcessorRun> = emptyList()

    /**
     * stage 一结束就调用：把 Forge 的 `-p` / `--add-modules` / `--add-opens` 抄出来。
     *
     * 这台 JVM 装完还要接着跑游戏，Forge 的 module path 必须在创建时就在位——
     * 之后再进游戏时 `JvmLauncher.start` 会直接复用，不会（也不能）补参数。
     */
    override fun attach(staged: ForgeInstaller.Staged) {
        stagedRuns = staged.runs
        stagedJvmArgs = runCatching {
            val version = MinecraftLauncher.parseVersionJson(staged.versionJson.toString())
            val id = ForgeInstaller.loaderVersion()
            MinecraftLauncher.buildJvmArgs(listOf(version), id, File(rootDir, "libraries"))
        }.getOrElse {
            Log.w(TAG, "解析 Forge 的 jvm 参数失败：" + it.message)
            emptyList()
        }
        Log.i(TAG, "forge jvm args = " + stagedJvmArgs.size)
    }

    override fun run(job: ForgeInstaller.ProcessorRun): Int {
        if (!started) startJvm()
        failure?.let { throw IllegalStateException(it) }
        Log.i(TAG, "processor ${job.mainClass} args=" + job.args.size)
        Log.i(TAG, "  cmd: " + job.args.joinToString(" "))
        // 让 jni_bridge 把 main 的异常文本落到这个文件：processor 的 stdout 抓不到，
        // 但「main threw: <异常栈>」已经足够区分参数错与缺类。
        val log = File(rootDir, "logs").apply { mkdirs() }
            .resolve("forge-processor-" + job.index + ".log")
        return JvmLauncher.runMainClass(job.mainClass, job.args.toTypedArray(), log.absolutePath)
    }

    override fun logTail(index: Int): String? {
        val log = File(rootDir, "logs/forge-processor-$index.log")
        if (!log.isFile) return null
        return runCatching {
            log.readLines().takeLast(LOG_TAIL_LINES).joinToString("\n").take(LOG_TAIL_CHARS)
        }.getOrNull()
    }

    private fun startJvm() {
        started = true
        onStage("准备安装用 JVM")
        val gameDir = gameDir.apply { mkdirs() }
        val assetsDir = File(rootDir, "assets").apply { mkdirs() }

        val classPath = buildList {
            // 原版 client jar 与打补丁后的产物：后者此刻可能还不存在，但 classpath 允许
            // 指向尚不存在的路径（JVM 只是忽略），装完就能被同一 JVM 读到。
            add(File(rootDir, "versions/$minecraftVersion/$minecraftVersion.jar"))
            ForgeInstaller.patchedJar(rootDir)?.let { add(it) }
            // 只给 processor 的 classpath，不全量扫 libraries：libraries 里同时躺着多个
            // 版本的同一个库（比如 Fabric 装过的 authlib 6.x，Java 25 编译），
            // FART 扫 classpath 建继承表时会读到它们，直接 UnsupportedClassVersionError。
            stagedRuns.forEach { addAll(it.classPath) }
            if (stagedRuns.isEmpty()) {
                // 兜底：attach 没被调用（单独测 runner 时），退回全量扫描。
                val libraries = File(rootDir, "libraries")
                if (libraries.isDirectory) {
                    libraries.walkTopDown()
                        .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
                        .sortedBy { it.absolutePath }
                        .forEach { add(it) }
                }
            }
        }.filter { it.isFile }.distinctBy { it.absolutePath }

        val spec = JvmLaunchSpec(
            instanceId = "forge-install",
            gameDir = gameDir,
            assetsDir = assetsDir,
            version = minecraftVersion,
            width = width,
            height = height,
            username = playerName,
            backend = "gl4es",
            shaders = false,
            memoryMB = memoryMB,
            // 与游戏一致：装完复用的就是这台 JVM，代码缓存小了后面跑 MC 会退化成 C1。
            codeCacheMB = 256,
            runtimeRoot = runtimeRoot,
            classPath = classPath,
            useCacio = false,
            // 必须 true：FCL 的 lwjgl 3.4.1 要进 classpath，否则装完进游戏时
            // GLFW 会去找 Android 上不存在的 libglfw.so。
            useFclLwjgl = true,
            extraJvmArgs = stagedJvmArgs
        )
        when (val result = JvmLauncher.start(context, spec)) {
            is JvmStartResult.Ready -> Log.i(TAG, "JVM ready " + result.javaVersion)
            is JvmStartResult.Failed -> failure = "安装用 JVM 启动失败：" + result.message
        }
    }

    private companion object {
        const val TAG = "ForgeJvmRunner"
        /** 报错里带的输出尾部：够看异常栈，又不会被对话框撑爆。 */
        const val LOG_TAIL_LINES = 24
        const val LOG_TAIL_CHARS = 2400
    }
}

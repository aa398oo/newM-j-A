package dev.mineport.launcher.install

import android.content.Context
import android.util.Log
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
    private val onStage: (String) -> Unit = {}
) : ForgeInstaller.ProcessorRunner {

    private var started = false
    private var failure: String? = null

    override fun run(job: ForgeInstaller.ProcessorRun): Int {
        if (!started) startJvm()
        failure?.let { throw IllegalStateException(it) }
        Log.i(TAG, "processor ${job.mainClass} args=" + job.args.size)
        return JvmLauncher.runMainClass(job.mainClass, job.args.toTypedArray(), null)
    }

    private fun startJvm() {
        started = true
        onStage("准备安装用 JVM")
        val gameDir = File(rootDir, "instances/forge-install").apply { mkdirs() }
        val assetsDir = File(rootDir, "assets").apply { mkdirs() }

        val classPath = buildList {
            // 原版 client jar 与打补丁后的产物：后者此刻可能还不存在，但 classpath 允许
            // 指向尚不存在的路径（JVM 只是忽略），装完就能被同一 JVM 读到。
            add(File(rootDir, "versions/$minecraftVersion/$minecraftVersion.jar"))
            ForgeInstaller.patchedJar(rootDir)?.let { add(it) }
            // 全量 libraries：安装期工具 + Forge 运行时库 + 原版库都在这里。
            val libraries = File(rootDir, "libraries")
            if (libraries.isDirectory) {
                libraries.walkTopDown()
                    .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
                    .sortedBy { it.absolutePath }
                    .forEach { add(it) }
            }
        }.filter { it.isFile }

        val spec = JvmLaunchSpec(
            instanceId = "forge-install",
            gameDir = gameDir,
            assetsDir = assetsDir,
            version = minecraftVersion,
            width = 1,
            height = 1,
            username = "installer",
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
            useFclLwjgl = true
        )
        when (val result = JvmLauncher.start(context, spec)) {
            is JvmStartResult.Ready -> Log.i(TAG, "JVM ready " + result.javaVersion)
            is JvmStartResult.Failed -> failure = "安装用 JVM 启动失败：" + result.message
        }
    }

    private companion object {
        const val TAG = "ForgeJvmRunner"
    }
}

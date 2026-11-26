package dev.mineport.launcher.jvm

import android.content.Context
import android.util.Log
import java.io.File

/** 一次 JVM 启动的完整参数。由 [dev.mineport.launcher.game.GameActivity] 组装。 */
data class JvmLaunchSpec(
    val instanceId: String,
    val gameDir: File,
    val assetsDir: File,
    val version: String,
    val width: Int,
    val height: Int,
    val username: String,
    val backend: String,
    /** 是否开启光影（Zink 试跑）；只影响 GL 层与诊断参数，不改 classpath。 */
    val shaders: Boolean,
    val memoryMB: Int,
    val codeCacheMB: Int,
    val runtimeRoot: File,
    val classPath: List<File>,
    /**
     * 是否启用 caciocavallo（AWT 的 Android 端口）。
     *
     * 原版 mainClass 的 GLFW 在 Android 上没有桌面窗口，必须由 cacio 提供 AWT 窗口层；
     * 不开时保持「headless + 由 GLSurfaceView 出画面」的现有路径，避免影响已验证的启动链路。
     */
    val useCacio: Boolean = false,
    /**
     * 是否用 FCL 的 LWJGL 3.4.1（merged-modules + core jar + CallbackBridge 补丁 jar）。
     *
     * 原版 3.3.1 的 GLFW 会 loadLibrary("libglfw.so")，Android 上没有这个库；
     * FCL 的 3.4.1 GLFW 改为 loadLibrary("pojavexec")，由 libpojavexec 提供
     * glfwInit/窗口桥。关掉时回到 MC 自带的 3.3.1（会在 GLFW 初始化处
     * UnsatisfiedLinkError: libglfw.so）。
     */
    val useFclLwjgl: Boolean = true
)

sealed interface JvmStartResult {
    /** JVM 已创建，java.version 就是验收要显示的那一行。 */
    data class Ready(val javaVersion: String) : JvmStartResult
    data class Failed(val message: String) : JvmStartResult
}

/**
 * JVM 生命周期。真正的 JNI_CreateJavaVM 在 jni_bridge.cpp 里，
 * 这里只负责参数校验、libjvm 定位与错误翻译。
 *
 * 二进制目录约定（都由用户/CI 提供，仓库不携带）：
 *   filesDir/runtime/lib/server/libjvm.so   OpenJDK 的 JRE 副本（由 assets/runtime 解出）
 *   filesDir/runtime/lib/modules            没有它，JVM 起来也会立刻报找不到模块
 *   jniLibs/arm64-v8a 下的 .so                  同批 .so 的再放一份，供加载器按 soname 解析依赖
 *   filesDir/runtime/natives 下的 .so           LWJGL / GL4ES / Turnip
 *
 * 平台事实：JVM 创建后运行的是 HotSpot，它的类加载器只读 classpath 上的 jar，
 * 读不到 APK 里的 dex。所以「JVM 内入口」不能是 Kotlin 对象，必须是 jar 里的类；
 * 第 4 步接入的 FCLCore.jar 就是那个 jar。
 */
object JvmLauncher {

    init {
        // .so 名沿用 fclandroid：它是内部构建名，用户不可见；改名会牵动 CMake 与打包，收益为零。
        System.loadLibrary("fclandroid")
    }

    const val RESULT_OK = 0
    const val RESULT_LIBJVM_MISSING = -1
    const val RESULT_DLOPEN_FAILED = -2
    const val RESULT_CREATE_FAILED = -3
    const val RESULT_BRIDGE_MISSING = -4
    const val RESULT_BRIDGE_FAILED = -5
    const val RESULT_ALREADY_RUNNING = -6

    @JvmStatic
    external fun nativeStartJvm(libjvmPath: String, options: Array<String>, codeCacheMB: Int): Int

    @JvmStatic
    external fun nativeStopJvm()

    @JvmStatic
    external fun nativeRuntimeVersion(): String

    /** 在已创建的 JVM 里调用 mainClass.main(args)；会阻塞到游戏退出，必须后台线程调用。 */
    @JvmStatic
    external fun nativeRunMainClass(className: String, args: Array<String>, outputPath: String?): Int

    /**
     * 调 FCLBridge.initializeHooks()：libpojavexec 需要它填充 JVM 方法 hook 表，
     * 否则 hookExec 会跳到空指针。必须在 JVM 就绪后、跑 MC main 之前调用。
     *
     * nativeLibraryDir 用于 dlopen libfcl/libpojavexec；runtimeLibDir 指向解包后的
     * JRE lib 目录，先 RTLD_GLOBAL 打开 libjava.so，否则 initializeHooks 内部
     * 的 dlsym(RTLD_DEFAULT, "Java_java_lang_ProcessImpl_forkAndExec") 查不到符号。
     */
    @JvmStatic
    external fun nativeInitializeHooks(nativeLibraryDir: String, runtimeLibDir: String): Boolean

    @JvmStatic
    external fun nativeLastError(): String

    @JvmStatic
    external fun nativeAbiInfo(): String

    /** 按优先级找 libjvm.so：解包后的 JRE 优先，其次 jniLibs 里的那份。 */
    fun findLibjvm(runtimeRoot: File, nativeLibDir: File?): File? = buildList {
        add(File(runtimeRoot, "lib/server/libjvm.so"))
        add(File(runtimeRoot, "libjvm.so"))
        if (nativeLibDir != null) add(File(nativeLibDir, "libjvm.so"))
    }.firstOrNull { it.isFile }

    /** 供界面提前给出「缺 JVM」提示，避免走到创建 JVM 才失败。 */
    fun resolveLibjvm(context: Context, runtimeRoot: File): File? =
        findLibjvm(runtimeRoot, context.applicationInfo.nativeLibraryDir?.let(::File))

    fun start(context: Context, spec: JvmLaunchSpec): JvmStartResult {
        // 进程内 JVM 只创建一次：返回桌面后再次进游戏时，直接复用并回报同一个版本。
        val existing = nativeRuntimeVersion()
        if (existing.isNotBlank()) {
            Log.i("MineportJVM", "复用已存在的 JVM, java.version=" + existing)
            return JvmStartResult.Ready(existing)
        }

        val libjvm = resolveLibjvm(context, spec.runtimeRoot)
            ?: return JvmStartResult.Failed(
                "缺少 JVM：在 ${spec.runtimeRoot.absolutePath} 与 nativeLibraryDir 下都没找到 libjvm.so。\n" +
                    "把 OpenJDK for Android 的 arm64 JRE 放到 app/src/main/assets/runtime/ 后再构建（见 docs/binaries.md）。"
            )

        val modules = File(spec.runtimeRoot, "lib/modules")
        if (!modules.isFile) {
            Log.w("MineportJVM", "缺少 ${modules.absolutePath}：JVM 可能创建失败或找不到 java.base")
        }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        // APK 里的 arm64 .so（LWJGL / GL4ES / JRE 伴生库）由 extractNativeLibs 解到
        // nativeLibraryDir，必须排在解包目录前面，否则会先命中旧版/异架构的库。
        val libraryPath = buildList {
            if (!nativeLibDir.isNullOrBlank()) add(nativeLibDir)
            add(File(spec.runtimeRoot, "natives").absolutePath)
            // B12：HotSpot 自己也要按 java.library.path 找 libjvm 依赖（libjava/libnio/
            // libnet 等），解包目录的 lib/server 与 lib 都必须在搜索路径里，
            // 否则 System.loadLibrary 会落到 APK 的 nativeLibraryDir 找不到同名库。
            add(File(spec.runtimeRoot, "lib").absolutePath)
            add(File(spec.runtimeRoot, "lib/server").absolutePath)
        }.joinToString(":")

        // HotSpot 默认从 libjvm 所在位置推 java.home；这里显式指到解包目录，
        // 避免「libjvm 来自 jniLibs、模块来自 filesDir」时对不上。
        // 开了 cacio 时把 cacio 的两个 jar 放到 classpath 最前面：CTCToolkit 与
        // CTCSurfaceManager 必须在 AWT 初始化前可见，否则 agent 反射设置 toolkit 会失败。
        val classPath = buildList {
            if (spec.useCacio) {
                val cacioDir = File(spec.runtimeRoot, "cacio")
                listOf("cacio-shared-1.19.1-SNAPSHOT.jar", "cacio-tta-1.19.1-SNAPSHOT.jar")
                    .map { File(cacioDir, it) }
                    .filter { it.isFile }
                    .forEach { add(it) }
            }
            if (spec.useFclLwjgl) {
                // 顺序有意义：patch jar 里的 CallbackBridge 要覆盖 merged jar 的同名类，
                // core 必须在 merged 前面（merged 缺 org.lwjgl.system.* 会 NoClassDefFoundError）。
                val lwjglDir = File(spec.runtimeRoot, "lwjgl")
                listOf(
                    "callbackbridge-patch.jar",
                    "lwjgl.jar",
                    "lwjgl-3.4.1-merged-modules.jar",
                    // MC 1.20.1 还要用 openal（音频）、stb（纹理）、tinyfd（文件框）、
                    // freetype（字体）这些 LWJGL 模块，缺一个初始化就 NoClassDefFoundError。
                    "lwjgl-openal.jar",
                    "lwjgl-stb.jar",
                    "lwjgl-tinyfd.jar",
                    "lwjgl-freetype.jar"
                ).map { File(lwjglDir, it) }.filter { it.isFile }.forEach { add(it) }
            }
            addAll(spec.classPath.filterNot { spec.useFclLwjgl && isMinecraftLwjglJar(it) })
        }

        val options = buildList {
            add("-Djava.home=" + spec.runtimeRoot.absolutePath)
            add("-Djava.class.path=" + classPath.joinToString(":") { it.absolutePath })
            add("-Djava.library.path=" + libraryPath)
            // LWJGL fork 用这个属性决定 GL 实现库；PojavRendererInit 靠库名识别 GL4ES，
            // 识别到才会调 nativeInitGl4esInternals 初始化 GL4ES 内部状态。不设它时
            // LWJGL 默认按 liblwjgl_opengl.so 处理，GL4ES 的 FBO 路径没初始化，
            // MC 建 Framebuffer 后会报 GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT。
            add("-Dorg.lwjgl.opengl.libname=libng_gl4es.so")
            add("-Dfcl.instance.id=" + spec.instanceId)
            add("-Dfcl.game.dir=" + spec.gameDir.absolutePath)
            add("-Dfcl.assets.dir=" + spec.assetsDir.absolutePath)
            add("-Dfcl.version=" + spec.version)
            add("-Dfcl.username=" + spec.username)
            add("-Dfcl.width=" + spec.width)
            add("-Dfcl.height=" + spec.height)
            add("-Dfcl.render.backend=" + spec.backend)
            add("-Dfcl.shaders=" + if (spec.shaders) "on" else "off")
            add("-Djava.io.tmpdir=" + File(spec.gameDir, "tmp").apply { mkdirs() }.absolutePath)
            // Android/arm64 上改回 HotSpot 默认（压缩指针开启）：关掉时会用 64 位
            // 指针，在 Android 16 的 tagged-pointer 进程里更容易踩到 bionic 的
            // "Pointer tag truncated"（free 校验 tag）。
            add("-XX:+UseSerialGC")
            // heap tagging 已由 AndroidManifest 的 allowNativeHeapPointerTagging=false
            // 在进程启动时关掉，C2 不再踩 free 的 tag 校验；恢复分层编译（默认），
            // 否则渲染线程跑在 C1 代码上会明显掉帧。此前的 C1-only 只是绕崩溃的临时手段。
            // 桌面 JVM 的 perf data 会往 /tmp 写 mmap 文件，Android 上默认目录不可写；
            // 关掉后少一条初始化路径，也避免启动时多一次文件系统探测。
            add("-XX:-UsePerfData")
            if (spec.useCacio && File(spec.runtimeRoot, "cacio/cacio-agent.jar").isFile) {
                // cacio 用 java agent 在 AWT 初始化前反射替换 Toolkit 与 GraphicsEnvironment，
                // 并把 headless 置为 false——没有这一步，Android 上的 AWT 会直接进 headless。
                add("-javaagent:" + File(spec.runtimeRoot, "cacio/cacio-agent.jar").absolutePath)
                add("-Djava.awt.headless=false")
            } else {
                // 明确无 AWT 窗口：桌面 mainClass 会探测 AWT，没有 headless 时可能去初始化
                // AWT 的 native 库（Android 上没有），提前说明由游戏自己接管画面。
                add("-Djava.awt.headless=true")
            }
            add("-Xmx" + spec.memoryMB + "m")
            // 初始堆压在物理内存的 1/8 内，启动更快，也避免低内存机一开始就换页。
            add("-Xms" + minOf(512, spec.memoryMB / 2).coerceAtLeast(128) + "m")
        }
        Log.i("MineportJVM", "libjvm=" + libjvm.absolutePath)
        Log.i("MineportJVM", "options=" + options.joinToString(" "))

        val code = nativeStartJvm(libjvm.absolutePath, options.toTypedArray(), spec.codeCacheMB)
        if (code == RESULT_OK) {
            val version = nativeRuntimeVersion().ifBlank { "unknown" }
            Log.i("MineportJVM", "JVM ready, java.version=" + version)
            // 窗口桥：libpojavexec.so（FCL/Pojav 的 GLFW/EGL 桥）导出
            // Java_org_lwjgl_glfw_CallbackBridge_* / FCLBridge_* / Surface_nativeGetBridgeSurfaceAWT。
            // 现在只加载进进程，等 Java 侧移植 CallbackBridge 后原生方法才能解析到它；
            // 加载失败不影响现有启动链路（GL 层仍会走 GLSurfaceView 路径）。
            runCatching { System.loadLibrary("pojavexec") }
                .onSuccess { Log.i("MineportJVM", "libpojavexec loaded (window bridge)") }
                .onFailure { Log.w("MineportJVM", "libpojavexec not loadable: " + it.message) }
            return JvmStartResult.Ready(version)
        }

        val detail = nativeLastError().ifBlank { "未知错误（code=" + code + "）" }
        return JvmStartResult.Failed(
            when (code) {
                RESULT_LIBJVM_MISSING -> "libjvm.so 不可读：" + detail
                RESULT_DLOPEN_FAILED ->
                    "dlopen libjvm.so 失败：" + detail +
                        "\n常见原因：缺依赖 .so（jniLibs 里要放同一批 JRE 库）、ABI 不是 arm64、16 KB 页未重打包。"
                RESULT_CREATE_FAILED ->
                    "JNI_CreateJavaVM 失败：" + detail +
                        "\n常见原因：java.home 指向的目录缺 lib/modules、-Xmx 超过进程可用内存。"
                RESULT_ALREADY_RUNNING -> "JVM 已在本进程创建过，不能重复创建。"
                else -> detail
            }
        )
    }

    fun abiInfo(): String = nativeAbiInfo()

    fun lastError(): String = nativeLastError()

    fun runMainClass(className: String, args: Array<String>, outputPath: String? = null): Int =
        nativeRunMainClass(className, args, outputPath)

    /** MC 自带的 lwjgl 3.3.1 类 jar（natives 除外）；用 FCL 3.4.1 时要从 classpath 里剔掉。 */
    private fun isMinecraftLwjglJar(file: File): Boolean =
        file.name.startsWith("lwjgl") &&
            file.name.contains("3.3.1") &&
            !file.name.contains("natives")

    /** 进程内 JVM 保留到进程结束（见 jni_bridge.cpp 里 nativeStopJvm 的注释）。 */
    fun stop() = nativeStopJvm()
}

package dev.mineport.launcher.game

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.mineport.launcher.MineportApplication
import dev.mineport.launcher.data.GameInstance
import dev.mineport.launcher.input.ActionWheel
import dev.mineport.launcher.input.GameAction
import dev.mineport.launcher.jvm.JvmLaunchSpec
import dev.mineport.launcher.jvm.JvmLauncher
import dev.mineport.launcher.jvm.JvmStartResult
import dev.mineport.launcher.jvm.RuntimeStager
import dev.mineport.launcher.jvm.StartupTrace
import dev.mineport.launcher.performance.PerfSampler
import dev.mineport.launcher.render.ShaderOutcome
import dev.mineport.launcher.render.ShaderPolicy
import dev.mineport.launcher.ui.theme.MineportTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 游戏窗口。
 *
 * 视图层级：GameHostLayout（根，按矩形分流触摸）
 *            ├── GameSurfaceView（全屏，GL 由 JVM 侧 LWJGL 接管）
 *            └── ComposeView（左下角 220dp 动作轮盘）
 *
 * 触摸只走一条路径：GameHostLayout.dispatchTouchEvent → TouchInjector → nativeSendPointer
 * → critical_send_cursor_pos/mouse_button（FCL 正统输入队列）。反射 enn/eno 仅作兜底。
 */
class GameActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_INSTANCE_ID = "instance_id"
        private const val TAG = "MineportGame"

        fun intent(context: Context, instanceId: String): Intent =
            Intent(context, GameActivity::class.java).putExtra(EXTRA_INSTANCE_ID, instanceId)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var glView: GameSurfaceView? = null
    private lateinit var instance: GameInstance
    private lateinit var root: GameHostLayout

    private fun status(text: String) {
        Log.i(TAG, text)
    }

    /** 轮盘按下过、还没抬起的动作，避免重复 press/release。 */
    private val heldWheel = HashSet<GameAction>()

    /** 轮盘当前高亮的扇区；触摸命中由 GameHostLayout 按 pointer 手动判定后回调这里。 */
    private var wheelActive by mutableStateOf<GameAction?>(null)

    private fun keyOf(action: GameAction): Int = when (action) {
        GameAction.FORWARD -> 87     // W
        GameAction.BACK -> 83        // S
        GameAction.LEFT -> 65        // A
        GameAction.RIGHT -> 68       // D
        GameAction.JUMP -> 32        // Space
        GameAction.SNEAK -> 340      // LeftShift
        GameAction.ATTACK -> 69      // E
        GameAction.USE -> 81         // Q
    }

    private fun handleWheel(action: GameAction, down: Boolean) {
        val key = keyOf(action)
        if (down) {
            if (heldWheel.add(action)) {
                NativeRenderer.nativeSendKey(key, NativeRenderer.KEY_PRESS)
                Log.i(TAG, "wheel press " + action.name)
            }
        } else if (heldWheel.remove(action)) {
            NativeRenderer.nativeSendKey(key, NativeRenderer.KEY_RELEASE)
            Log.i(TAG, "wheel release " + action.name)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val app = application as MineportApplication
        val id = intent.getStringExtra(EXTRA_INSTANCE_ID)
        val found = app.container.instanceStore.instances.value.firstOrNull { it.id == id }
        if (found == null) {
            finish()
            return
        }
        instance = found

        // 根容器按矩形分流：轮盘区交给 Compose，其余全部给游戏。
        // 之前用 root.setOnTouchListener 的做法会被兄弟 View / Compose 覆盖层抢先消费者，
        // 真手指事件因此到不了 JNI（adb 注入路径不同，所以会误判成可用）。
        val host = GameHostLayout(this)
        root = host
        val shaderPlan = ShaderPolicy.plan(app.container.deviceProfile, instance.wantShaders)
        val surface = GameSurfaceView(this, if (shaderPlan.outcome == ShaderOutcome.ZINK) 3 else 2)
        glView = surface
        host.gameView = surface
        host.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 左下角动作轮盘：纯展示层，触摸命中由 host 按 pointer 手动判定（见 GameHostLayout），
        // 这样「一根手指转视角、另一根手指按轮盘」能同时成立。
        val density = resources.displayMetrics.density
        val wheelSize = (220 * density).toInt()
        val wheel = ComposeView(this).apply {
            setContent {
                MineportTheme {
                    ActionWheel(active = wheelActive)
                }
            }
        }
        host.onWheelAction = { action, down ->
            handleWheel(action, down)
            wheelActive = if (down) action else null
        }
        host.addView(
            wheel,
            FrameLayout.LayoutParams(wheelSize, wheelSize, android.view.Gravity.BOTTOM or android.view.Gravity.START).apply {
                leftMargin = (12 * density).toInt()
                bottomMargin = (12 * density).toInt()
            }
        )
        host.wheelView = wheel
        setContentView(host)

        // 先把窗口像素尺寸同步给 GLFW/CallbackBridge：Android 上 Surface 尺寸就是
        // 窗口尺寸，但 libpojavexec 的窗口字段默认是 0，主菜单会因此点不动。
        val metrics = resources.displayMetrics
        NativeRenderer.nativeSetScreenSize(metrics.widthPixels, metrics.heightPixels)

        scope.launch {
            app.container.instanceStore.markPlayed(instance.id)
            StartupTrace.begin(this@GameActivity)
            startJvm(app)
        }
    }

    private suspend fun startJvm(app: MineportApplication) {
        // 性能基线：游戏进程同时采 Java/native 堆、线程数、CPU 占用，写 files/perf-game.log。
        PerfSampler.start(this, "game")
        val runtimeRoot = File(filesDir, "runtime")
        status("准备运行时…")
        StartupTrace.mark(this, "runtime-staging")

        val staged = withContext(Dispatchers.IO) { RuntimeStager.ensure(this@GameActivity, runtimeRoot) }
        StartupTrace.mark(this, "runtime-staged:" + staged.copied)
        val runtimeState = RuntimeStager.inspect(runtimeRoot)
        status(
            if (staged.skipped) "运行时已就绪（跳过解包）"
            else "运行时已解包 " + staged.copied + " 个文件（" + staged.bytes / (1024 * 1024) + " MB）"
        )

        if (!runtimeState.libjvmPresent && JvmLauncher.resolveLibjvm(this, runtimeRoot) == null) {
            StartupTrace.mark(this, "missing-libjvm")
            status("缺少 JVM：未找到 libjvm.so（" + runtimeRoot.absolutePath + "）")
            return
        }
        if (!runtimeState.modulePresent) {
            StartupTrace.mark(this, "missing-modules")
            status("运行时缺少 lib/modules：JVM 能创建但找不到 java.base。")
            return
        }

        status("准备启动参数…")
        StartupTrace.mark(this, "plan-start")
        val profile = app.container.deviceProfile
        val gameDir = File(filesDir, "instances/" + instance.gameDirName).apply { mkdirs() }
        val assetsDir = File(filesDir, "assets").apply { mkdirs() }

        // 装了加载器就用加载器 profile 当 versionId（Fabric 自包含版本只有 profile id）。
        val launchVersionId = instance.loaderVersion
            ?.takeIf { instance.loader != dev.mineport.launcher.data.Loader.VANILLA }
            ?: instance.minecraftVersion
        val planResult = MinecraftLauncher.prepare(
            rootDir = filesDir,
            versionId = launchVersionId,
            playerName = instance.playerName,
            gameDir = gameDir,
            assetsDir = assetsDir,
            nativesDir = File(runtimeRoot, "natives"),
            account = app.container.accountStore.current
        )
        val plan = planResult.getOrNull()
        if (plan == null) {
            StartupTrace.mark(this, "plan-missing")
            status(
                "未安装 " + launchVersionId + "，或版本文件不完整：" +
                    (planResult.exceptionOrNull()?.message ?: "")
            )
            return
        }

        val classPath = buildList {
            addAll(plan.classPath)
            // FCLCore 由 CI 构建后放进 assets/runtime，RuntimeStager 会解到 filesDir/runtime。
            listOf(
                File(filesDir, "FCLCore.jar"),
                File(runtimeRoot, "FCLCore.jar")
            ).firstOrNull { it.isFile }?.let { add(it) }
        }
        val shaderPlan = ShaderPolicy.plan(profile, instance.wantShaders)
        val backend = if (shaderPlan.outcome == ShaderOutcome.ZINK) "zink" else "gl4es"
        val width = if (instance.resolutionWidth > 0) instance.resolutionWidth else 1280
        val height = if (instance.resolutionHeight > 0) instance.resolutionHeight else 720

        val spec = JvmLaunchSpec(
            instanceId = instance.id,
            gameDir = gameDir,
            assetsDir = assetsDir,
            version = launchVersionId,
            width = width,
            height = height,
            username = instance.playerName,
            backend = backend,
            shaders = instance.wantShaders,
            memoryMB = instance.memoryMB,
            codeCacheMB = 256,
            runtimeRoot = runtimeRoot,
            classPath = classPath,
            useCacio = instance.useCacio
        )
        StartupTrace.mark(this, "plan-ok:" + plan.classPath.size + " jars")
        StartupTrace.mark(this, "jvm-creating")

        val result = withContext(Dispatchers.IO) { JvmLauncher.start(this@GameActivity, spec) }
        when (result) {
            is JvmStartResult.Ready -> {
                StartupTrace.mark(this, "jvm-ready:" + result.javaVersion)
                // JVM 创建后 FCL 的窗口桥才在本进程里可用，补一次 Surface 挂载。
                BridgeWindow.attach(glView?.holder?.surface)
                // 尺寸不再用 displayMetrics 覆盖：Surface 的 surfaceChanged 已经在
                // GameSurfaceView 里调 nativeResize，native 侧会把真实尺寸同步给
                // GLFW/CallbackBridge（之前两条路各写一份，横竖屏切换后点击会偏移）。
                withContext(Dispatchers.IO) {
                    val libDir = applicationInfo.nativeLibraryDir ?: ""
                    // 注意：这里**不能**再调 JvmLauncher.runMainClass 跑任何探针类。
                    // nativeRunMainClass 只允许消费一次 main（g_mainConsumed 原子门），
                    // 早先这里跑 dev.mineport.probe.Boot 会把这次机会用光，
                    // 导致真正的 MC main 直接返回 -4、黑屏。
                    if (JvmLauncher.nativeInitializeHooks(libDir, File(filesDir, "runtime/lib").absolutePath)) {
                        status("FCL hooks 已初始化")
                    } else {
                        status("initializeHooks 失败：" + JvmLauncher.lastError())
                    }
                }
                // 诊断探针：在 MC main 之前把 GLFW 初始化结果写到 filesDir/probe.txt。
                // 默认**不跑**——探针自己会调 glfwInit，FCL 的 glfwInit 有「已初始化就返回」
                // 的短路，先跑探针会让 MC 复用一个还没绑 Surface 的窗口。真机要诊断时：
                //   adb shell run-as dev.mineport.launcher touch files/enable-probe
                val probeOut = File(filesDir, "probe.txt")
                val probeEnabled = File(filesDir, "enable-probe").exists()
                val dm = resources.displayMetrics
                if (probeEnabled) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            JvmLauncher.runMainClass(
                                "dev.mineport.probe.GlfwProbe",
                                arrayOf(
                                    probeOut.absolutePath,
                                    dm.widthPixels.toString(),
                                    dm.heightPixels.toString()
                                )
                            )
                        }.onFailure { status("探针执行失败：" + it.message) }
                    }
                }
                status("JVM 已创建：OpenJDK " + result.javaVersion + "，启动 " + plan.mainClass)
                StartupTrace.mark(this, "main-invoking:" + plan.mainClass)
                val errorFile = File(filesDir, "last-main-error.txt")
                val exit = withContext(Dispatchers.IO) {
                    JvmLauncher.runMainClass(
                        plan.mainClass,
                        plan.gameArgs.toTypedArray(),
                        errorFile.absolutePath
                    )
                }
                StartupTrace.mark(this, "main-exit=" + exit)
                if (exit == -4) {
                    status("本进程已经运行过一次游戏，JVM 不能复用。\n请从最近任务里彻底划掉本应用后重新启动。")
                } else if (exit != 0) {
                    status("游戏启动失败（code=" + exit + "）：" + JvmLauncher.lastError())
                } else {
                    status("游戏已退出")
                }
            }
            is JvmStartResult.Failed -> {
                StartupTrace.mark(this, "jvm-failed:" + result.message)
                status("启动失败：" + result.message)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        glView?.onResume()
    }

    override fun onPause() {
        glView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        PerfSampler.stop()
        JvmLauncher.stop()
        glView?.onDestroy()
        super.onDestroy()
    }
}

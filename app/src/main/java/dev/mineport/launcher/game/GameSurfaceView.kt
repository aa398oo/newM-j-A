package dev.mineport.launcher.game

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * 游戏窗口的宿主 Surface。
 *
 * 这里刻意不再是 GLSurfaceView：GL 由 JVM 里的 LWJGL（pojavexec / GL4ES）接管，
 * 宿主再起一套 EGL 上下文会和它抢同一个 surface，Android 16 上直接以
 * "pthread_mutex_lock called on a destroyed mutex"（FenceMonitor/hwui）崩掉。
 * 所以宿主只提供 Surface，并在 holder 生命周期里把 surface 交给 JVM 侧。
 */
class GameSurfaceView(context: Context, private val glVersion: Int = 2) : SurfaceView(context) {

    init {
        Log.i("MineportSurface", "host surface view created (requested GL $glVersion)")
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i("MineportSurface", "surfaceCreated " + holder.surface + " " + width + "x" + height)
                // 先交给 JVM 的 GLFW 窗口桥（libpojavexec），JVM 内 GLFW 创建窗口时用同一个 surface。
                BridgeWindow.attach(holder.surface)
                NativeRenderer.nativeAttachSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                Log.i("MineportSurface", "surfaceChanged " + w + "x" + h)
                // surfaceCreated 时 Surface 常常还没 layout（0x0），libpojavexec 的
                // setupBridgeWindow 会按当时尺寸建窗口；尺寸确定后必须重挂一次，
                // 否则 GLFW 窗口是 0x0，MC 的 RenderTarget 建 0x0 纹理后 FBO 会报
                // GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT。
                BridgeWindow.attach(holder.surface)
                NativeRenderer.nativeResize(w, h)
                // 以 Surface 的真实横向像素为准覆盖 displayMetrics 估值（方向切换时更可靠）。
                NativeRenderer.nativeSetScreenSize(w, h)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i("MineportSurface", "surfaceDestroyed")
                NativeRenderer.nativeDetachSurface()
            }
        })
        isClickable = true
    }

    /**
     * 触摸 → GLFW 指针事件。MC 的窗口完全由 JVM 侧接管，宿主 View 不画任何东西，
     * 但必须把 MotionEvent 翻译成 GLFW 的 cursorPos/mouseButton 回调，主菜单按钮才能点。
     *
     * 注意：这里调的是 APK dex 里的 org.lwjgl.glfw.CallbackBridge（ART 侧副本），
     * 它的 native 由 libpojavexec.so 实现；HotSpot 里的 GLFW 读同一个原生输入队列，
     * 两个 VM 共享同一份 .so，所以事件能进到 MC 的窗口循环。
     */
    // 触摸只保留 GameHostLayout -> TouchInjector 一条路径，避免同一手势被注入两次。

    /** 与旧 GLSurfaceView 生命周期调用保持兼容（宿主不再自管 EGL）。 */
    fun onResume() = Unit

    fun onPause() = Unit

    fun onDestroy() = Unit
}

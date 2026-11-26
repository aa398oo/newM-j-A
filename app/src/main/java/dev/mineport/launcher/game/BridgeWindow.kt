package dev.mineport.launcher.game

import android.util.Log
import android.view.Surface

/**
 * 把 Android Surface 交给 FCL 的 lwjgl 窗口桥。
 *
 * org.lwjgl.glfw.CallbackBridge 在 APK dex 里有一份（本工程 app/src/main/java/org/lwjgl/glfw/
 * CallbackBridge.java），ART 调它的 native 方法时由 libpojavexec.so 实现
 * `Java_org_lwjgl_glfw_CallbackBridge_setupBridgeWindow`；HotSpot 里的 GLFW 随后通过
 * 同一个原生桥把 EGL surface 接到游戏渲染线程。
 *
 * 用反射是为了让 APK 编译不依赖 lwjgl；失败只打日志，不阻断启动。
 */
object BridgeWindow {

    fun attach(surface: Surface?) {
        if (surface == null) return
        runCatching {
            val cls = Class.forName("org.lwjgl.glfw.CallbackBridge")
            val method = cls.getMethod("setupBridgeWindow", Any::class.java)
            method.invoke(null, surface)
            Log.i("MineportGame", "CallbackBridge.setupBridgeWindow attached")
        }.onFailure {
            Log.w("MineportGame", "CallbackBridge.setupBridgeWindow failed: " + it.message)
        }
    }

    fun detach() {
        // 目前 libpojavexec 没有显式的 detach 入口；surfaceDestroyed 时交给 GL 层自然停渲染。
    }
}

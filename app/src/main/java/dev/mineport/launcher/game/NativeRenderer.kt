package dev.mineport.launcher.game

import android.view.Surface

/** 渲染桥。EGL 与 GL 调用都在 render_bridge.cpp，Kotlin 只负责生命周期。 */
object NativeRenderer {

    init {
        System.loadLibrary("fclandroid")
    }

    @JvmStatic
    external fun nativeInit(glVersion: String): Int

    @JvmStatic
    external fun nativeResize(width: Int, height: Int)

    @JvmStatic
    external fun nativeDrawFrame(): Int

    @JvmStatic
    external fun nativeAttachSurface(surface: Surface?)

    @JvmStatic
    external fun nativeDetachSurface()

    /**
     * 把触摸事件转成 GLFW 的指针事件（MC 主菜单/游戏世界都靠它）。
     * action：0=按下、1=移动、2=抬起；坐标是 SurfaceView 的像素坐标。
     */
    @JvmStatic
    external fun nativeSendPointer(x: Float, y: Float, action: Int)

    /** 把 Surface 像素尺寸告诉 GLFW/CallbackBridge（窗口尺寸为 0 时主菜单点不动）。 */
    external fun nativeSetScreenSize(width: Int, height: Int)

    /**
     * 键盘输入（轮盘/虚拟按键）。key 用 GLFW 键码：W=87 A=65 S=83 D=68
     * Space=32 LeftShift=340 Esc=256；action 用 KEY_RELEASE=0 / KEY_PRESS=1。
     */
    @JvmStatic
    external fun nativeSendKey(key: Int, action: Int)

    /**
     * 鼠标按键（动作轮盘的攻击/使用槽）。button 用 GLFW 口径：0=左键、1=右键；
     * action 与 [nativeSendKey] 同一套契约（KEY_RELEASE=0 / KEY_PRESS=1）。
     */
    @JvmStatic
    external fun nativeSendMouseButton(button: Int, action: Int)

    /**
     * nativeSendKey 的 action 契约，与 GLFW 的 GLFW_RELEASE=0 / GLFW_PRESS=1 对齐。
     * 轮盘按下要传 KEY_PRESS，松手传 KEY_RELEASE——写反会变成「按住不前进、松手卡键」。
     */
    const val KEY_RELEASE = 0
    const val KEY_PRESS = 1

    /** nativeSendMouseButton 的 button 参数（GLFW 口径）。 */
    const val MOUSE_LEFT = 0
    const val MOUSE_RIGHT = 1

    /** 复位输入状态（触摸被取消/系统手势打断时调用，避免按键卡在按下态）。 */
    @JvmStatic
    external fun nativeResetInputState()

    @JvmStatic
    external fun nativeDestroy()
}

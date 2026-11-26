package dev.mineport.launcher.input

import android.util.Log

/**
 * 动作轮盘 → JVM 的输入桥。
 *
 * 为什么先做这一层：轮盘的 UI 已经能画出扇区，但「按下后游戏里真的动」
 * 需要等 FCLCore 把输入队列接进 JVM。在那之前，这里先把意图沉到 native：
 * native 侧维护一个 8 位动作掩码，并按位记录日志，接入 FCLCore 时只需把
 * 掩码翻译成它的事件结构，Kotlin 侧与 UI 侧一行都不用改。
 *
 * 语义：按住扇区期间为 held（前/后/左/右会持续移动），松手统一 release。
 * 切换扇区时先 release 旧动作再 press 新动作，避免「一直向前」的卡键。
 */
object InputBridge {

    private const val TAG = "MineportInput"
    private const val LIBRARY = "fclandroid"

    /** nativeStartJvm 所在库；输入出口也放在同一个库里，省一次加载。 */
    private val loaded: Boolean by lazy {
        runCatching { System.loadLibrary(LIBRARY) }
            .onFailure { Log.w(TAG, "loadLibrary(" + LIBRARY + ") 失败：" + it.message) }
            .isSuccess
    }

    /** 掩码里每个 bit 对应 [GameAction.ordinal]。 */
    external fun nativeAction(action: String, pressed: Boolean): Boolean

    external fun nativeActionMask(): Int

    fun setHeld(action: GameAction, pressed: Boolean) {
        if (!loaded) {
            Log.w(TAG, (if (pressed) "hold " else "release ") + action.name + "（native 未加载，仅 UI 反馈）")
            return
        }
        val accepted = runCatching { nativeAction(action.name, pressed) }.getOrDefault(false)
        Log.i(
            TAG,
            buildString {
                append(if (pressed) "hold " else "release ")
                append(action.name)
                append(" accepted=").append(accepted)
                append(" mask=0x").append(Integer.toHexString(actionMask()))
            }
        )
    }

    /** 当前按住的动作掩码；诊断页/输入层接入时可读。 */
    fun actionMask(): Int =
        if (loaded) runCatching { nativeActionMask() }.getOrDefault(0) else 0
}

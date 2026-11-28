package dev.mineport.launcher.input

import androidx.compose.runtime.mutableStateOf
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 浮动移动摇杆（UX-21）。
 *
 * 职责很小：把「拖动向量」换算成 W/A/S/D 的组合，并通过 [onKey] 上报按下/抬起。
 * 触摸事件由 [dev.mineport.launcher.game.GameHostLayout] 按 pointerId 分流后转发进来，
 * 绘制由 [dev.mineport.launcher.ui.game.MovePadOverlay] 读取 [visible] / [headX] / [headY]。
 *
 * 这里刻意不用 `by mutableStateOf` 委托，而是显式持有 [MutableState] 并暴露普通只读属性：
 * 委托写法在 `private set` + 多个扩展 import 的情况下会触发重载歧义（已踩过）。
 * 在 Composable 里读 `pad.visible` 仍会订阅——getter 内部读的就是 `.value`。
 */
class MovePad(
    /** 基座半径（像素）。摇杆头最多偏移这么多，超过就贴边。 */
    private val baseRadiusPx: Float,
    /** 键状态回调：key 是 LWJGL 键码，pressed=true 按下、false 抬起。 */
    private val onKey: (key: Int, pressed: Boolean) -> Unit,
) {
    private val _visible = mutableStateOf(false)
    private val _anchorX = mutableStateOf(0f)
    private val _anchorY = mutableStateOf(0f)
    private val _headX = mutableStateOf(0f)
    private val _headY = mutableStateOf(0f)

    /** 是否处于按住状态（绘制层只在这时候画摇杆）。 */
    val visible: Boolean get() = _visible.value

    /** 基座中心（屏幕坐标）。 */
    val anchorX: Float get() = _anchorX.value
    val anchorY: Float get() = _anchorY.value

    /** 摇杆头相对基座中心的偏移（像素）。 */
    val headX: Float get() = _headX.value
    val headY: Float get() = _headY.value

    private var active = false
    private var held: Set<Int> = emptySet()

    /** 手指按下：记录锚点，摇杆出现。 */
    fun down(x: Float, y: Float) {
        _anchorX.value = x
        _anchorY.value = y
        _headX.value = 0f
        _headY.value = 0f
        _visible.value = true
        active = true
        applyKeys(emptySet())
    }

    /** 手指拖动：更新摇杆头位置并换算方向。 */
    fun move(x: Float, y: Float) {
        if (!active) return
        val rawDx: Float = x - _anchorX.value
        val rawDy: Float = y - _anchorY.value
        val len: Float = sqrt(rawDx * rawDx + rawDy * rawDy)
        val scale: Float = if (len > baseRadiusPx) baseRadiusPx / len else 1f
        val dx: Float = rawDx * scale
        val dy: Float = rawDy * scale
        _headX.value = dx
        _headY.value = dy
        applyKeys(keysFor(dx, dy, len))
    }

    /** 手指抬起或手势取消：摇杆消失，所有方向键松开。 */
    fun up() {
        if (!active) return
        active = false
        _visible.value = false
        _headX.value = 0f
        _headY.value = 0f
        applyKeys(emptySet())
    }

    /** 当前按住的键（测试与调试用）。 */
    fun heldKeys(): Set<Int> = held

    /**
     * 把偏移换算成 8 扇区方向键。
     *
     * 角度以「正上」为 0°，顺时针增大；每个扇区 ±22.5°。斜向返回两个键（右上 = W + D）。
     */
    private fun keysFor(dx: Float, dy: Float, len: Float): Set<Int> {
        if (len < baseRadiusPx * DEAD_ZONE_RATIO) return emptySet()
        // atan2(dy, dx)：0° 指右，顺时针为正（屏幕 y 向下）
        val deg: Double = (Math.toDegrees(atan2(dy, dx).toDouble()) + 90.0 + 360.0) % 360.0
        val sector: Int = (((deg + 22.5) / 45.0).toInt()) % 8
        return when (sector) {
            0 -> setOf(KEY_W)
            1 -> setOf(KEY_W, KEY_D)
            2 -> setOf(KEY_D)
            3 -> setOf(KEY_S, KEY_D)
            4 -> setOf(KEY_S)
            5 -> setOf(KEY_S, KEY_A)
            6 -> setOf(KEY_A)
            else -> setOf(KEY_W, KEY_A)
        }
    }

    /**
     * 差量上报：先松开旧键、再按新键。
     *
     * 顺序很重要——如果先按 W 再松 S，中间会有一帧同时按住 W 和 S，
     * Minecraft 会把两个方向抵消成不动，手感上就是「卡了一下」。
     */
    private fun applyKeys(next: Set<Int>) {
        if (next == held) return
        val released = held - next
        val pressed = next - held
        held = next
        for (key in released) onKey(key, false)
        for (key in pressed) onKey(key, true)
    }

    companion object {
        const val KEY_W = 87
        const val KEY_A = 65
        const val KEY_S = 83
        const val KEY_D = 68

        /** 死区：偏移小于基座半径的这个比例时视为不动。 */
        const val DEAD_ZONE_RATIO = 0.15f

        /** 默认基座直径（dp）；PadPrefs 的 movePadSize 默认就取这个值。 */
        const val DEFAULT_BASE_DP = 140
    }
}

package dev.mineport.launcher.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 浮动移动摇杆（UX-21）。
 *
 * 职责很小：把「拖动向量」换算成 W/A/S/D 的组合，并通过 [onKey] 上报按下/抬起。
 * 触摸事件由 [dev.mineport.launcher.game.GameHostLayout] 按 pointerId 分流后转发进来，
 * 绘制由图层 [dev.mineport.launcher.game.MovePadOverlay] 读取 [visible] / [headX] / [headY]。
 *
 * 为什么是浮动而不是固定：手指在左半屏任意位置按下都能开始移动，不需要先找摇杆在哪，
 * 拇指落在屏幕的哪个位置都能用。这也是手机动作游戏的主流做法。
 *
 * 方向按 8 扇区离散化（而不是连续角度），因为 Minecraft 的移动键本来就是离散的：
 * 连续角度最终还是要转成 W/A/S/D 的组合，离散化能让「正前」更容易按到，也避免
 * 斜向时两个键的时序抖动。
 */
class MovePad(
    /** 基座半径（像素）。摇杆头最多偏移这么多，超过就贴边。 */
    private val baseRadiusPx: Float,
    /** 键状态回调：key 是 LWJGL 键码，pressed=true 按下、false 抬起。 */
    private val onKey: (key: Int, pressed: Boolean) -> Unit,
) {
    /** 摇杆头相对基座中心的偏移（像素），供绘制层读取。 */
    var headX by mutableStateOf(0f)
        private set
    var headY by mutableStateOf(0f)
        private set

    /** 是否处于按住状态（绘制层只在这时候画摇杆）。 */
    var visible by mutableStateOf(false)
        private set

    private var anchorX = 0f
    private var anchorY = 0f
    private var active = false
    private var held: Set<Int> = emptySet()

    /** 手指按下：记录锚点，摇杆出现。 */
    fun down(x: Float, y: Float) {
        anchorX = x
        anchorY = y
        headX = 0f
        headY = 0f
        visible = true
        active = true
        // 按下瞬间不动，先松开所有（防止上一根手指残留）
        applyKeys(emptySet())
    }

    /** 手指拖动：更新摇杆头位置并换算方向。 */
    fun move(x: Float, y: Float) {
        if (!active) return
        var dx = x - anchorX
        var dy = y - anchorY
        val len = sqrt(dx * dx + dy * dy)
        if (len > baseRadiusPx) {
            dx = dx / len * baseRadiusPx
            dy = dy / len * baseRadiusPx
        }
        headX = dx
        headY = dy
        applyKeys(keysFor(dx, dy, len))
    }

    /** 手指抬起或手势取消：摇杆消失，所有方向键松开。 */
    fun up() {
        if (!active) return
        active = false
        visible = false
        headX = 0f
        headY = 0f
        applyKeys(emptySet())
    }

    /** 当前按住的键（测试与调试用）。 */
    fun heldKeys(): Set<Int> = held

    /**
     * 把偏移换算成 8 扇区方向键。
     *
     * 角度以「正上」为 0°，顺时针增大；每个扇区 ±22.5°。
     * 斜向返回两个键（例如右上 = W + D）。
     */
    private fun keysFor(dx: Float, dy: Float, len: Float): Set<Int> {
        if (len < baseRadiusPx * DEAD_ZONE_RATIO) return emptySet()
        // atan2(dy, dx)：0° 指右，顺时针为正（屏幕坐标 y 向下）
        val deg = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0 + 360.0) % 360.0
        val sector = (((deg + 22.5) / 45.0).toInt()) % 8
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
        released.forEach { onKey(it, false) }
        pressed.forEach { onKey(it, true) }
    }

    companion object {
        const val KEY_W = 87
        const val KEY_A = 65
        const val KEY_S = 83
        const val KEY_D = 68

        /** 死区：偏移小于基座半径的这个比例时视为不动。 */
        const val DEAD_ZONE_RATIO = 0.15f
    }
}

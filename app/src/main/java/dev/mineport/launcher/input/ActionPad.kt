package dev.mineport.launcher.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.atan2
import kotlin.math.sqrt

/** 动作槽的动作类型：键盘键 / 鼠标左键 / 鼠标右键。 */
enum class ActionType { KEY, MOUSE_LEFT, MOUSE_RIGHT }

/**
 * 动作轮盘的一个槽位。
 *
 * [code] 在 [ActionType.KEY] 时是 LWJGL 键码（如 Space=32），
 * 在鼠标类型时忽略（用 [ActionType] 区分左右键）。
 */
data class ActionSlot(
    val index: Int,
    val type: ActionType,
    val code: Int,
    val label: String,
)

/**
 * 动作轮盘（UX-21 的右半边）。
 *
 * 它只做三件事：记录展开/收起状态、把触摸点换算成槽位、把槽位按下/抬起上报给 [onSlot]。
 * 具体是「发包给 nativeSendKey 还是 nativeSendMouseButton」由调用方按 [ActionSlot.type] 决定。
 *
 * 收起形态是一个 48dp 小球；点球展开、点中心收起；自动收起由调用方用计时器驱动 [collapse]，
 * 这里不持有计时器——保持纯函数式，方便测试。
 */
class ActionPad(
    private val slots: List<ActionSlot>,
    private val onSlot: (slot: ActionSlot, pressed: Boolean) -> Unit,
) {
    /** 是否展开成 8 槽轮盘。 */
    var expanded by mutableStateOf(true)
        private set

    /** 当前按住的槽位（null 表示没有）。 */
    var activeSlot by mutableStateOf<ActionSlot?>(null)
        private set

    fun expand() {
        expanded = true
    }

    fun collapse() {
        expanded = false
    }

    fun toggle() {
        expanded = !expanded
    }

    /**
     * 计算命中槽位。
     *
     * @param cx/cy 轮盘圆心
     * @param radius 轮盘半径（外缘）
     * @return 槽位下标；-1 表示落在中心圆（不触发）或圆外
     */
    fun hit(cx: Float, cy: Float, x: Float, y: Float, radius: Float): Int {
        val dx = x - cx
        val dy = y - cy
        val dist = sqrt(dx * dx + dy * dy)
        // 中心圆不触发：那里留给「收起」按钮
        if (dist < radius * CENTER_RATIO) return -1
        // 外圈留白：手指点到边缘外不算
        if (dist > radius * OUTER_RATIO) return -1
        val deg = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0 + 360.0) % 360.0
        val step = 360.0 / slots.size
        return (((deg + step / 2.0) % 360.0) / step).toInt() % slots.size
    }

    /** 手指按下：命中则上报 press。 */
    fun down(cx: Float, cy: Float, x: Float, y: Float, radius: Float) {
        val idx = hit(cx, cy, x, y, radius)
        if (idx < 0) return
        val slot = slots[idx]
        if (slot == activeSlot) return
        activeSlot?.let { onSlot(it, false) }
        activeSlot = slot
        onSlot(slot, true)
    }

    /** 手指拖动：滑到别的槽就换键（先松旧、再按新）。 */
    fun move(cx: Float, cy: Float, x: Float, y: Float, radius: Float) {
        if (activeSlot == null) return
        val idx = hit(cx, cy, x, y, radius)
        if (idx < 0) {
            // 拖到中心/圆外 → 松开当前槽，但不选新的
            activeSlot?.let { onSlot(it, false) }
            activeSlot = null
            return
        }
        val slot = slots[idx]
        if (slot == activeSlot) return
        activeSlot?.let { onSlot(it, false) }
        activeSlot = slot
        onSlot(slot, true)
    }

    /** 手指抬起（或手势取消）：松开当前槽。 */
    fun up() {
        activeSlot?.let { onSlot(it, false) }
        activeSlot = null
    }

    companion object {
        /** 中心圆占半径的比例（点这里收起）。 */
        const val CENTER_RATIO = 0.32f

        /** 外缘判定比例（超过就不触发）。 */
        const val OUTER_RATIO = 1.15f

        /** 默认 8 槽：跳跃 / 使用 / 攻击 / 丢弃 / 潜行 / 副手 / 视角切换 / 菜单。 */
        fun defaultSlots(): List<ActionSlot> = listOf(
            ActionSlot(0, ActionType.KEY, 32, "跳"),
            ActionSlot(1, ActionType.KEY, 69, "用"),
            ActionSlot(2, ActionType.MOUSE_LEFT, 0, "攻"),
            ActionSlot(3, ActionType.KEY, 81, "丢"),
            ActionSlot(4, ActionType.KEY, 340, "潜"),
            ActionSlot(5, ActionType.MOUSE_RIGHT, 1, "副"),
            ActionSlot(6, ActionType.KEY, 116, "视"),
            ActionSlot(7, ActionType.KEY, 256, "菜单"),
        )
    }
}

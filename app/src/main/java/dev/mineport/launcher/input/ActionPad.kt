package dev.mineport.launcher.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.atan2
import kotlin.math.sqrt

/** 动作轮盘的模式：游戏态 / 编辑态。两者互斥。 */
enum class PadMode { PLAY, EDIT }

/** 动作槽的动作类型：键盘键 / 鼠标左键 / 鼠标右键。 */
enum class ActionType { KEY, MOUSE_LEFT, MOUSE_RIGHT }

/**
 * 动作轮盘的一个槽位。
 *
 * [code] 在 [ActionType.KEY] 时是 GLFW 键码（如 Space=32）；
 * 在 [ActionType.MOUSE_LEFT] / [ActionType.MOUSE_RIGHT] 时是鼠标键号（0=左、1=右），
 * 由 `nativeSendMouseButton` 原样下传。
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
    /** 8 个槽位的初始绑定。编辑模式改绑后整体替换，绘制与命中都读这里。 */
    slots: List<ActionSlot>,
    private val onSlot: (slot: ActionSlot, pressed: Boolean) -> Unit,
    /** 进游戏时是否直接展开；默认收起成小球（UX-21 §21.3）。 */
    initiallyExpanded: Boolean = false,
) {
    /** 当前槽位绑定。改绑走 [bind] / [cycleSlot]，保证回调与状态一起更新。 */
    var slots: List<ActionSlot> = slots
        private set

    /** 是否展开成 8 槽轮盘。 */
    var expanded by mutableStateOf(initiallyExpanded)
        private set

    /** 展开/收起变化回调：宿主持久化 actionPadExpanded 用。 */
    var onExpandedChange: ((Boolean) -> Unit)? = null

    /** 槽位绑定变化回调：宿主持久化 action-pad.json 用。 */
    var onBindingsChange: ((List<ActionSlot>) -> Unit)? = null

    /** 编辑态当前选中的槽位（-1 表示还没点过）。 */
    var editSlot by mutableStateOf(-1)
        private set

    /**
     * 当前模式。编辑态与游戏态**互斥**：进入编辑一定先 `up()`（松开所有按住的键），
     * 避免「正在按住攻击时点编辑」导致按键卡死。编辑态本身不进持久化模型——
     * 它只存在于当前进程内存里，重进游戏永远回到 PLAY。
     */
    var mode by mutableStateOf(PadMode.PLAY)
        private set

    /** 进入编辑模式：先松开所有按住的槽，再强制展开（编辑必须看得见全部槽位）。 */
    fun enterEdit() {
        up()
        // 走 expand() 而不是直接改 expanded：这样 onExpandedChange 会落盘，重进游戏仍是展开态。
        expand()
        editSlot = -1
        mode = PadMode.EDIT
    }

    /** 退出编辑模式。 */
    fun exitEdit() {
        up()
        editSlot = -1
        mode = PadMode.PLAY
        markTouch()
    }

    /** 最近一次触摸轮盘的时间（uptimeMillis）。用于「4 秒无操作自动收起」。 */
    private var lastTouchAt = 0L

    /** 当前有几根手指按在轮盘上（宿主记账）。>0 时禁止自动收起。 */
    private var pointersDown = 0

    /** 记录一次触摸；由宿主在 WHEEL 指针的 DOWN/MOVE 时调用。 */
    fun markTouch() {
        lastTouchAt = android.os.SystemClock.uptimeMillis()
    }

    /** 宿主把一根手指判给轮盘时调用（含收起态点球展开那一指）。 */
    fun pointerDown() {
        pointersDown++
        markTouch()
    }

    /** 与 [pointerDown] 配对；宿主漏调也不会让计数变负。 */
    fun pointerUp() {
        if (pointersDown > 0) pointersDown--
        markTouch()
    }

    /**
     * 空闲超过 [timeoutMs] 就自动收起成小球。
     *
     * 只在**展开、非编辑态、没有按住任何槽、也没有手指搭在轮盘上**时收——
     * 否则会出现「按着攻击键突然缩成球」或者「手指还按着就被收走」的诡异手感。
     * @return 是否真的收起了
     */
    fun autoCollapseIfIdle(
        timeoutMs: Long = 4000L,
        now: Long = android.os.SystemClock.uptimeMillis(),
    ): Boolean {
        if (!expanded) return false
        if (mode != PadMode.PLAY) return false
        if (activeSlot != null) return false
        if (pointersDown > 0) return false
        if (now - lastTouchAt < timeoutMs) return false
        collapse()
        return true
    }

    /** 当前按住的槽位（null 表示没有）。 */
    var activeSlot by mutableStateOf<ActionSlot?>(null)
        private set

    fun expand() {
        if (!expanded) {
            expanded = true
            onExpandedChange?.invoke(true)
        }
        markTouch()
    }

    /**
     * 收起成小球。**先松开正在按的槽**：否则「按着攻击键时自动收起」会把鼠标左键卡在按下态。
     */
    fun collapse() {
        up()
        editSlot = -1
        if (expanded) {
            expanded = false
            onExpandedChange?.invoke(false)
        }
    }

    fun toggle() {
        if (expanded) collapse() else expand()
    }

    /**
     * 改绑一个槽位。绑定变了以后，如果这个槽正被按住，先松开——否则松手时会释放到「新动作」上，
     * 旧动作永远停在按下态。
     */
    fun bind(index: Int, slot: ActionSlot) {
        if (index !in slots.indices) return
        if (activeSlot?.index == index) up()
        val next = slots.toMutableList()
        next[index] = slot.copy(index = index)
        slots = next
        onBindingsChange?.invoke(slots)
    }

    /**
     * 编辑态点一下槽位：切到候选池 [REBIND_POOL] 里的下一个动作。
     *
     * 为什么是「循环切换」而不是弹面板：面板要自己抢触摸（本工程的触摸全由宿主按 pointer 分发），
     * 弹出来反而会挡住轮盘、还要处理面板内外的手势竞争。循环切换零额外触摸层，
     * 点 18 次最多，也足够改出任何常用组合。
     */
    fun cycleSlot(index: Int) {
        val current = slots.getOrNull(index) ?: return
        val at = REBIND_POOL.indexOfFirst { it.type == current.type && it.code == current.code }
        val next = REBIND_POOL[(at + 1).mod(REBIND_POOL.size)]
        bind(index, next)
        editSlot = index
        markTouch()
    }

    /**
     * 计算命中槽位。
     *
     * 按豆包评审建议：命中用**像素**而不是 dp/百分比，径向用**带**判定，角度加**滞回**。
     * - 径向内带：`dist < innerPx` 视为中心圆（留给「收起」按钮），不触发
     * - 径向外带：`dist > radius - outerMarginPx`（即超出外缘）不触发
     * - 角度滞回：手指停在两个扇区交界附近时，只要还落在 [lastIndex] 的 ±[HYSTERESIS_DEG] 内就保持不动，
     *   避免手指轻微抖动导致「W→W+D→W」的键位闪跳
     *
     * @param cx/cy 轮盘圆心（px）
     * @param radius 轮盘半径（px）
     * @param innerPx 中心圆半径（px）
     * @param lastIndex 上一次命中的槽位；-1 表示没有
     * @return 槽位下标；-1 表示落在中心圆或圆外
     */
    fun hit(
        cx: Float,
        cy: Float,
        x: Float,
        y: Float,
        radius: Float,
        innerPx: Float = radius * CENTER_RATIO,
        lastIndex: Int = -1,
    ): Int {
        val dx = x - cx
        val dy = y - cy
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < innerPx) return -1
        if (dist > radius * OUTER_RATIO) return -1

        val step = 360.0 / slots.size
        val deg = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0 + 360.0) % 360.0

        // 角度滞回：仍在上一次槽位的角度带里就保持
        if (lastIndex in slots.indices) {
            val centerDeg = lastIndex * step
            val delta = kotlin.math.abs(((deg - centerDeg + 540.0) % 360.0) - 180.0)
            if (delta < HYSTERESIS_DEG) return lastIndex
        }
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

        /** 角度滞回宽度（度）：手指在扇区交界 ±3° 内不切换。 */
        const val HYSTERESIS_DEG = 6.0

        /** 外缘判定比例（超过就不触发）。 */
        const val OUTER_RATIO = 1.15f

        /** 默认 8 槽：跳跃 / 使用 / 攻击 / 丢弃 / 潜行 / 副手 / 视角切换 / 菜单。 */
        fun defaultSlots(): List<ActionSlot> = listOf(
            ActionSlot(0, ActionType.KEY, 32, "跳"),
            ActionSlot(1, ActionType.MOUSE_RIGHT, 1, "用"),
            ActionSlot(2, ActionType.MOUSE_LEFT, 0, "攻"),
            ActionSlot(3, ActionType.KEY, 81, "丢"),
            ActionSlot(4, ActionType.KEY, 340, "潜"),
            ActionSlot(5, ActionType.KEY, 116, "副"),
            ActionSlot(6, ActionType.KEY, 294, "视"),
            ActionSlot(7, ActionType.KEY, 256, "菜单"),
        )

        /**
         * 编辑态可循环切换的动作池：只放 MC 常用动作，避免翻几十次才轮到。
         *
         * 键码是 GLFW 口径（Space=32、Shift=340、E=69、Q=81、F=116、F5=294、Esc=256、
         * T=84、Tab=258、1/2/3=49/50/51、W/A/S/D=87/65/83/68）。
         * 里面每个 slot 的 index 都是占位 0，[ActionPad.bind] 会改成真实下标。
         */
        val REBIND_POOL: List<ActionSlot> = listOf(
            ActionSlot(0, ActionType.MOUSE_LEFT, 0, "攻"),
            ActionSlot(0, ActionType.MOUSE_RIGHT, 1, "用"),
            ActionSlot(0, ActionType.KEY, 32, "跳"),
            ActionSlot(0, ActionType.KEY, 340, "潜"),
            ActionSlot(0, ActionType.KEY, 81, "丢"),
            ActionSlot(0, ActionType.KEY, 69, "包"),
            ActionSlot(0, ActionType.KEY, 116, "副"),
            ActionSlot(0, ActionType.KEY, 294, "视"),
            ActionSlot(0, ActionType.KEY, 256, "菜单"),
            ActionSlot(0, ActionType.KEY, 84, "聊天"),
            ActionSlot(0, ActionType.KEY, 258, "列表"),
            ActionSlot(0, ActionType.KEY, 49, "1"),
            ActionSlot(0, ActionType.KEY, 50, "2"),
            ActionSlot(0, ActionType.KEY, 51, "3"),
            ActionSlot(0, ActionType.KEY, 87, "前"),
            ActionSlot(0, ActionType.KEY, 83, "后"),
            ActionSlot(0, ActionType.KEY, 65, "左"),
            ActionSlot(0, ActionType.KEY, 68, "右"),
        )
    }
}

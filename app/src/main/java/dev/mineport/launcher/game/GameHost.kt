package dev.mineport.launcher.game

import android.content.Context
import android.util.AttributeSet
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import dev.mineport.launcher.input.ActionPad
import dev.mineport.launcher.input.ActionSlot
import dev.mineport.launcher.input.MovePad
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 游戏根容器：按 pointer 分流触摸（十指各归各的）。
 *
 * 和旧版最大的区别：**归属是按指针记的，不是按手势**。
 * 旧版在 ACTION_DOWN 时定一次 owner，整条手势都跟着走，所以「一根手指转视角时
 * 另一根手指去按轮盘」永远进不来。现在每个 pointerId 单独记账：
 * 落在轮盘圆内 -> 轮盘扇区（手动命中，不经 Compose 触摸系统）；
 * 其余 -> TouchInjector 注入 MC 鼠标。
 */
class GameHostLayout @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    var wheelView: View? = null
    var gameView: View? = null

    /** 左侧浮动摇杆（UX-21）。由 GameActivity 注入；为空时左半屏仍按原逻辑注入鼠标。 */
    var movePad: MovePad? = null

    /** 动作轮盘逻辑（可收起 / 可绑定 / 带角度滞回）。注入后命中判定优先走它。 */
    var actionPad: ActionPad? = null

    /** 轮盘动作回调：true=按下（持续），false=松开。 */
    var onWheelAction: ((ActionSlot, Boolean) -> Unit)? = null

    private val injector = TouchInjector(this)

    /** pointerId -> OWNER_* */
    private val owners = SparseIntArray()

    /** pointerId -> 当前扇区下标（-1 表示圆内但太靠近中心，无动作） */
    private val wheelIndex = SparseIntArray()

    private val actions: List<ActionSlot> = ActionPad.defaultSlots()

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val id = ev.getPointerId(i)
                val x = ev.getX(i)
                val y = ev.getY(i)
                // 收起态：先判定是否点在小球（48dp 半径内）——点球展开，这一指不参与游戏。
                val pad = actionPad
                if (pad != null && !pad.expanded) {
                    val w = wheelView
                    if (w != null && w.width > 0) {
                        val cx = w.left + w.width / 2f
                        val cy = w.top + w.height / 2f
                        val ball = 24f * resources.displayMetrics.density
                        if (hypot(x - cx, y - cy) <= ball) {
                            pad.expand()
                        }
                    }
                }
                // 展开态：点中心圆（24dp 内）收起成小球。
                if (pad != null && pad.expanded) {
                    val w = wheelView
                    if (w != null && w.width > 0) {
                        val cx = w.left + w.width / 2f
                        val cy = w.top + w.height / 2f
                        val ball = 24f * resources.displayMetrics.density
                        if (hypot(x - cx, y - cy) <= ball) {
                            pad.collapse()
                        }
                    }
                }
                val idx = pickWheelIndex(x, y, id)
                if (idx >= 0 || inWheelCircle(x, y)) {
                    // 记录一次触摸，让「4 秒无操作自动收起」不会在刚点完就缩回去
                    pad?.markTouch()
                    owners.put(id, OWNER_WHEEL)
                    wheelIndex.put(id, idx)
                    if (idx >= 0) onWheelAction?.invoke(actions[idx], true)
                } else if (x < width / 2f && movePad != null) {
                    // 左半屏 = 移动摇杆（浮动：按在哪，基座就出现在哪）
                    owners.put(id, OWNER_MOVE_PAD)
                    movePad?.down(x, y)
                } else {
                    owners.put(id, OWNER_GAME)
                    injector.pointerDown(id, x, y)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    val id = ev.getPointerId(i)
                    val x = ev.getX(i)
                    val y = ev.getY(i)
                    when (owners.get(id, OWNER_GAME)) {
                        OWNER_WHEEL -> {
                            val idx = pickWheelIndex(x, y)
                            val old = wheelIndex.get(id, -1)
                            if (idx != old) {
                                if (old >= 0) onWheelAction?.invoke(actions[old], false)
                                if (idx >= 0) onWheelAction?.invoke(actions[idx], true)
                                wheelIndex.put(id, idx)
                            }
                        }
                        OWNER_MOVE_PAD -> movePad?.move(x, y)
                        else -> injector.pointerMove(id, x, y)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val i = ev.actionIndex
                val id = ev.getPointerId(i)
                if (owners.get(id, OWNER_GAME) == OWNER_MOVE_PAD) movePad?.up()
                releaseWheelPointer(id)
                injector.pointerUp(id)
                owners.delete(id)
            }

            MotionEvent.ACTION_UP -> {
                val id = ev.getPointerId(0)
                if (owners.get(id, OWNER_GAME) == OWNER_MOVE_PAD) movePad?.up()
                releaseWheelPointer(id)
                injector.pointerUp(id)
                owners.clear()
                wheelIndex.clear()
            }

            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until wheelIndex.size()) {
                    val idx = wheelIndex.valueAt(i)
                    if (idx >= 0) onWheelAction?.invoke(actions[idx], false)
                }
                wheelIndex.clear()
                owners.clear()
                movePad?.up()
                injector.cancelAll()
            }
        }
        return true
    }

    /** 子 View 一律不参与触摸竞争：所有事件都由本容器按 pointer 分发。 */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    private fun releaseWheelPointer(id: Int) {
        if (owners.get(id, OWNER_GAME) != OWNER_WHEEL) return
        val idx = wheelIndex.get(id, -1)
        if (idx >= 0) onWheelAction?.invoke(actions[idx], false)
        wheelIndex.delete(id)
    }

    /** 触摸点是否落在轮盘可视圆内（含中心死区，用于把「按在轮盘上」判给轮盘）。 */
    private fun inWheelCircle(x: Float, y: Float): Boolean {
        val w = wheelView ?: return false
        if (w.width == 0 || w.height == 0) return false
        if (x < w.left || x > w.right || y < w.top || y > w.bottom) return false
        val dx = x - (w.left + w.width / 2f)
        val dy = y - (w.top + w.height / 2f)
        val outer = minOf(w.width, w.height) * 0.42f * 1.18f
        return hypot(dx, dy) <= outer
    }

    /**
     * 命中扇区；返回 -1 表示在圆内但没选中（中心死区或超出扇区半径）。
     *
     * 注入了 [actionPad] 时走它的 `hit()` —— 那套算法带**角度滞回**（[id] 用来取上一次槽位），
     * 手指停在扇区交界不会闪跳。没注入时退回旧的纯几何算法。
     */
    private fun pickWheelIndex(x: Float, y: Float, id: Int = -1): Int {
        val w = wheelView ?: return -1
        if (w.width == 0 || w.height == 0) return -1
        val cx = w.left + w.width / 2f
        val cy = w.top + w.height / 2f
        val pad = actionPad
        if (pad != null) {
            val r = minOf(w.width, w.height) * 0.5f
            val last = if (id >= 0) wheelIndex.get(id, -1) else -1
            return pad.hit(cx, cy, x, y, r, innerPx = 24f, lastIndex = last)
        }
        if (!inWheelCircle(x, y)) return -1
        val dx = x - cx
        val dy = y - cy
        val dist = hypot(dx, dy)
        if (dist < 24f) return -1
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0f) angle += 360f
        val step = 360f / actions.size
        return (((angle + 90f + step / 2f) % 360f) / step).toInt()
            .coerceIn(0, actions.size - 1)
    }

    /** 触摸轨迹落盘：真机验证时 adb 直接 cat files/touch.log 就能确认事件是否到达。 */
    fun trace(line: String) {
        runCatching {
            java.io.File(context.filesDir, "touch.log").appendText(line + "\n")
        }
    }

    private companion object {
        const val OWNER_GAME = 0
        const val OWNER_WHEEL = 1

        /** 左半屏浮动摇杆（UX-21）。 */
        const val OWNER_MOVE_PAD = 2
    }
}

/**
 * 游戏区触摸 -> MC 输入。
 *
 * 支持多指针：第一个按下的指针当「视角指针」，其余指针按下时只发一次点击
 * （按住超 300ms 升级长按），互不干扰。
 *
 * 语义对齐 FCL 的 `putMouseEventWithCoords`：
 * - **DOWN 立即 press**，33ms 后自动 release（保证 MC 的 `Screen.mouseClicked`
 *   拿到「按下 + 抬起」一对事件）；
 * - 按住超过 300ms 升级为长按（挖矿/持续攻击），取消自动 release；
 * - UP：若仍按住就 release；CANCEL：释放 + 复位 FCL 输入状态。
 */
class TouchInjector(private val host: GameHostLayout) {

    private var lookId = -1
    private var lastX = 0f
    private var lastY = 0f

    /** 当前是否处于「按下」状态（已经发过 press、还没 release）。 */
    private var held = false

    /** 是否已升级为长按：true 时 33ms 的自动 release 不再执行。 */
    private var longHeld = false

    /** 除视角指针外，还有多少个「点击指针」正按着（它们不参与视角）。 */
    private var extraHeld = 0

    private val clickRelease = Runnable {
        if (held && !longHeld) {
            inject(lastX, lastY, ACTION_RELEASE)
            held = false
        }
    }

    private val longPressArm = Runnable {
        longHeld = true
    }

    fun pointerDown(id: Int, x: Float, y: Float) {
        if (host.gameView == null) return
        if (lookId < 0) {
            lookId = id
            lastX = x
            lastY = y
            held = false
            longHeld = false
            host.removeCallbacks(clickRelease)
            host.removeCallbacks(longPressArm)
            // 先把光标挪到按下点再按下——顺序不能反，否则 MC 用上一点坐标判定命中。
            inject(x, y, ACTION_MOVE)
            inject(x, y, ACTION_PRESS)
            held = true
            host.postDelayed(clickRelease, CLICK_RELEASE_MS)
            host.postDelayed(longPressArm, LONG_PRESS_MS)
        } else {
            // 视角指针已经存在：这一指当独立点击（例如边走边打）。
            extraHeld++
            inject(x, y, ACTION_MOVE)
            inject(x, y, ACTION_PRESS)
            host.postDelayed({ inject(x, y, ACTION_RELEASE) }, CLICK_RELEASE_MS)
        }
    }

    fun pointerMove(id: Int, x: Float, y: Float) {
        if (id != lookId) return
        // 视角必须逐点连续：任意像素变化都发一次，否则慢速拖动会「不动 → 突然跳一格」。
        if (x != lastX || y != lastY) {
            inject(x, y, ACTION_MOVE)
            lastX = x
            lastY = y
        }
    }

    fun pointerUp(id: Int) {
        if (id != lookId) {
            if (extraHeld > 0) extraHeld--
            return
        }
        host.removeCallbacks(clickRelease)
        host.removeCallbacks(longPressArm)
        if (held) {
            inject(lastX, lastY, ACTION_RELEASE)
            held = false
        }
        lookId = -1
    }

    fun cancelAll() {
        host.removeCallbacks(clickRelease)
        host.removeCallbacks(longPressArm)
        if (held) {
            inject(lastX, lastY, ACTION_RELEASE)
            held = false
        }
        // 系统手势打断后 FCL 内部可能残留按下态，必须复位。
        NativeRenderer.nativeResetInputState()
        lookId = -1
        longHeld = false
        extraHeld = 0
    }

    private fun inject(x: Float, y: Float, action: Int) {
        host.trace("ptr x=" + x.toInt() + " y=" + y.toInt() + " a=" + action)
        try {
            NativeRenderer.nativeSendPointer(x, y, action)
        } catch (t: Throwable) {
            android.util.Log.w("MineportInput", "nativeSendPointer failed", t)
        }
    }

    private companion object {
        const val ACTION_PRESS = 0
        const val ACTION_MOVE = 1
        const val ACTION_RELEASE = 2
        const val LONG_PRESS_MS = 300L
        const val CLICK_RELEASE_MS = 33L
    }
}

package dev.mineport.launcher.game

import android.content.Context
import android.util.AttributeSet
import android.util.SparseIntArray
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import dev.mineport.launcher.input.ActionPad
import dev.mineport.launcher.input.ActionSlot
import dev.mineport.launcher.input.MovePad
import dev.mineport.launcher.input.PadMode
import kotlin.math.abs
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

    /** 8 个槽位跟着 [actionPad] 走（编辑模式改绑后立刻生效），没注入时用默认绑定。 */
    private fun slotAt(i: Int): ActionSlot? = (actionPad?.slots ?: defaultSlots).getOrNull(i)

    /** 没注入 actionPad 时的兜底绑定：只在极早期（宿主还没 setContent）用得到。 */
    private val defaultSlots: List<ActionSlot> by lazy(LazyThreadSafetyMode.NONE) {
        ActionPad.defaultSlots()
    }

    private val density: Float get() = resources.displayMetrics.density

    /** 收起态小球命中半径：视觉 24dp，命中放宽到 28dp（56dp 直径，游戏场景够大又不吃环带）。 */
    private val ballHitPx: Float get() = 28f * density

    /** 展开态中心死区 = 点中心收起的热区，32dp（绝对值，不用比例——豆包 UX-21 要求）。 */
    private val centerPx: Float get() = 32f * density

    /** 按住小球多久进编辑模式（UX-21 §21.3：长按 1.5s）。 */
    private val editLongPressMs = 1500L

    /** 长按判定的手抖容差：按下后位移超过它就算拖动，取消长按。 */
    private val editSlopPx: Float get() = 12f * density

    /** 小球拖动的起拖阈值：比长按容差大一点，避免「点球」被轻微手抖判成拖动。 */
    private val dragSlopPx: Float get() = 16f * density

    /** 正在长按的小球：pointerId / 按下坐标。 */
    private var ballPressId = -1
    private var ballPressX = 0f
    private var ballPressY = 0f

    /** 小球拖动状态：按下点、最近一次拖动点、是否已超过起拖阈值。 */
    private var ballDownX = 0f
    private var ballDownY = 0f
    private var ballDragX = 0f
    private var ballDragY = 0f
    private var ballDragging = false

    /** 小球吸附落位后回调归一化坐标，宿主用它落盘（PadPrefs.actionPadBallX/Y）。 */
    var onPadMoved: ((Float, Float) -> Unit)? = null

    /** 轮盘左上角的归一化位置（0..1，相对可用区域，不存绝对像素——豆包 UX-21 要求）。 */
    var padNormX = 0f
        private set
    var padNormY = 1f
        private set

    private val editLongPress = Runnable {
        val pad = actionPad
        if (ballPressId >= 0 && pad != null && pad.mode != PadMode.EDIT) {
            // 进编辑前 enterEdit() 内部会先 up()，把按住的槽/键全部松开。
            pad.enterEdit()
            trace("edit: long-press ball x=" + ballPressX.toInt() + " y=" + ballPressY.toInt())
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val id = ev.getPointerId(i)
                val x = ev.getX(i)
                val y = ev.getY(i)
                if (claimWheel(id, x, y)) {
                    // 归轮盘/小球，这一指不参与游戏输入
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
                        OWNER_BALL -> {
                            // 收起态按住小球：先判「拖」还是「点/长按」。
                            if (!ballDragging &&
                                (abs(x - ballDownX) > dragSlopPx || abs(y - ballDownY) > dragSlopPx)
                            ) {
                                ballDragging = true
                                disarmBallLongPress()
                                trace("ball: drag start")
                            }
                            if (ballDragging) {
                                ballDragX = x
                                ballDragY = y
                                // 只动 translation，不 requestLayout：拖动时每帧改 margin 会掉帧。
                                wheelView?.let { w ->
                                    w.translationX = x - (w.left + w.width / 2f)
                                    w.translationY = y - (w.top + w.height / 2f)
                                }
                            }
                        }
                        OWNER_WHEEL -> {
                            val pad = actionPad
                            // 编辑态由 OWNER_EDIT 负责；收起态下的残留手指也不参与扇区判定。
                            if (pad == null || (pad.mode == PadMode.PLAY && pad.expanded)) {
                                pad?.markTouch()
                                // 按在球上又滑开 = 想拖动轮盘，不是长按：取消进编辑。
                                if (ballPressId == id &&
                                    (abs(x - ballPressX) > editSlopPx || abs(y - ballPressY) > editSlopPx)
                                ) {
                                    disarmBallLongPress()
                                }
                                val old = wheelIndex.get(id, -1)
                                // WHEEL_IGNORE = 用来展开/收起的那一指，整条手势都不出动作。
                                if (old != WHEEL_IGNORE) {
                                    val idx = pickWheelIndex(x, y, id)
                                    if (idx != old) {
                                        if (old >= 0) slotAt(old)?.let { onWheelAction?.invoke(it, false) }
                                        if (idx >= 0) slotAt(idx)?.let { onWheelAction?.invoke(it, true) }
                                        wheelIndex.put(id, idx)
                                    }
                                }
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
                endPointer(id)
            }

            MotionEvent.ACTION_UP -> {
                // ACTION_UP 时只剩最后一根，但仍按 pointerCount 收尾，避免漏账。
                for (i in 0 until ev.pointerCount) endPointer(ev.getPointerId(i))
                owners.clear()
                wheelIndex.clear()
            }

            MotionEvent.ACTION_CANCEL -> {
                // 每根手指按自己的 owner 收尾（松槽位/松摇杆），再统一复位 FCL 输入态。
                disarmBallLongPress()
                ballDragging = false
                wheelView?.let { it.translationX = 0f; it.translationY = 0f }
                for (i in 0 until ev.pointerCount) endPointer(ev.getPointerId(i))
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

    /**
     * DOWN：判定这一指是否归轮盘，归则记账并返回 true。
     *
     * 分支顺序不能换（否则小球会「展开完立刻收起」）：
     * 0. 编辑态（OWNER_EDIT，优先级最高）-> 中心「完成」/ 扇区「换动作」，轮盘外不吞；
     * 1. 收起态且落在小球 56dp 命中圈内 -> 展开并开始计长按，这一指不触发任何动作；
     * 2. 展开态且落在中心 32dp 死区内 -> 收起（collapse 内部先 up 掉按住的槽）；
     * 3. 展开态且落在环带内（≤1.15R）-> 命中扇区并按下。
     */
    private fun claimWheel(id: Int, x: Float, y: Float): Boolean {
        val pad = actionPad ?: return false
        val geo = wheelGeometry() ?: return false
        val dist = hypot(x - geo.cx, y - geo.cy)

        // 编辑态：轮盘内全部吞掉，免得改键时误触游戏；轮盘外放行，还能转视角看画面。
        if (pad.mode == PadMode.EDIT) {
            if (dist > geo.r * ActionPad.OUTER_RATIO) return false
            if (dist <= centerPx) {
                pad.exitEdit()
                trace("edit: done")
            } else {
                val idx = pad.hit(geo.cx, geo.cy, x, y, geo.r, innerPx = centerPx, lastIndex = -1)
                if (idx >= 0) {
                    pad.cycleSlot(idx)
                    trace("edit: slot " + idx + " -> " + pad.slots[idx].label)
                }
            }
            owners.put(id, OWNER_EDIT)
            return true
        }

        if (!pad.expanded) {
            if (dist > ballHitPx) return false
            // 收起态按住小球：这一指交给 OWNER_BALL，用来区分「点一下展开」「拖到边上」「长按进编辑」。
            ballDownX = x
            ballDownY = y
            ballDragX = x
            ballDragY = y
            ballDragging = false
            armBallLongPress(id, x, y)
            owners.put(id, OWNER_BALL)
            wheelIndex.put(id, WHEEL_IGNORE)
            return true
        }
        if (dist <= centerPx) {
            pad.collapse()
            pad.markTouch()
            disarmBallLongPress()
            owners.put(id, OWNER_WHEEL)
            wheelIndex.put(id, WHEEL_IGNORE)
            return true
        }
        if (dist > geo.r * ActionPad.OUTER_RATIO) return false
        pad.markTouch()
        val idx = pad.hit(geo.cx, geo.cy, x, y, geo.r, innerPx = centerPx, lastIndex = -1)
        owners.put(id, OWNER_WHEEL)
        wheelIndex.put(id, idx)
        if (idx >= 0) slotAt(idx)?.let { onWheelAction?.invoke(it, true) }
        return true
    }

    /** 单指收尾：松摇杆 / 松轮盘槽位 / 收小球手势，都只做自己那一份。 */
    private fun endPointer(id: Int, cancelled: Boolean = false) {
        if (ballPressId == id) disarmBallLongPress()
        when (owners.get(id, OWNER_GAME)) {
            OWNER_MOVE_PAD -> movePad?.up()
            // CANCEL 是「这一手势作废」：既不展开也不吸附，只把临时位移清掉。
            OWNER_BALL -> if (cancelled) clearBallDrag() else settleBall()
            OWNER_WHEEL -> {
                releaseWheelPointer(id)
                actionPad?.pointerUp()
            }
            // 编辑态只吞触摸，不产生按键，收尾时什么都不用松。
            OWNER_EDIT -> Unit
            else -> injector.pointerUp(id)
        }
        owners.delete(id)
    }

    /** 手势作废：把拖动时的临时位移抹掉，回到上一次落位。 */
    private fun clearBallDrag() {
        ballDragging = false
        wheelView?.let {
            it.translationX = 0f
            it.translationY = 0f
        }
    }

    /**
     * 小球手指离开：拖过就吸附到最近的左右边并落盘；没拖（且没进编辑）就展开轮盘。
     *
     * 吸附只算左右，不算上下：横屏两侧才有足够宽的「停靠位」，上下吸附会挡住画面中间。
     */
    private fun settleBall() {
        val w = wheelView
        val pad = actionPad
        if (w == null || pad == null) {
            ballDragging = false
            return
        }
        if (ballDragging) {
            ballDragging = false
            val halfW = w.width / 2f
            val halfH = w.height / 2f
            val inset = PAD_INSET_DP * density
            val left = inset + halfW
            val right = width - inset - halfW
            val targetX = if (ballDragX < width / 2f) left else right
            val targetY = ballDragY.coerceIn(halfH + inset, height - halfH - inset)
            val rangeY = (height - 2f * inset - w.height).coerceAtLeast(1f)
            val normX = if (targetX <= left + 1f) 0f else 1f
            val normY = ((targetY - halfH - inset) / rangeY).coerceIn(0f, 1f)
            w.translationX = 0f
            w.translationY = 0f
            setPadPosition(normX, normY)
            onPadMoved?.invoke(normX, normY)
            trace("ball: settle x=" + normX + " y=" + normY)
        } else if (pad.mode != PadMode.EDIT) {
            pad.expand()
            trace("ball: expand")
        }
    }

    private fun releaseWheelPointer(id: Int) {
        if (owners.get(id, OWNER_GAME) != OWNER_WHEEL) return
        val idx = wheelIndex.get(id, -1)
        if (idx >= 0) slotAt(idx)?.let { onWheelAction?.invoke(it, false) }
        wheelIndex.delete(id)
    }

    /** 轮盘圆心的屏幕坐标与可视半径；wheelView 还没测量出来时返回 null。 */
    private fun wheelGeometry(): WheelGeo? {
        val w = wheelView ?: return null
        if (w.width == 0 || w.height == 0) return null
        return WheelGeo(
            w.left + w.width / 2f,
            w.top + w.height / 2f,
            minOf(w.width, w.height) * WHEEL_RADIUS_RATIO
        )
    }

    /** 触摸点是否落在轮盘环带内（含中心死区，用于把「按在轮盘上」判给轮盘）。 */
    private fun inWheelCircle(x: Float, y: Float): Boolean {
        val geo = wheelGeometry() ?: return false
        if (hypot(x - geo.cx, y - geo.cy) > geo.r * ActionPad.OUTER_RATIO) return false
        val w = wheelView ?: return false
        return x >= w.left && x <= w.right && y >= w.top && y <= w.bottom
    }

    /**
     * 命中扇区；返回 -1 表示在圆内但没选中（中心死区或超出扇区半径）。
     *
     * 注入了 [actionPad] 时走它的 `hit()` —— 那套算法带**角度滞回**（[id] 用来取上一次槽位），
     * 手指停在扇区交界不会闪跳。未展开时不接受命中。没注入时退回旧的纯几何算法。
     */
    private fun pickWheelIndex(x: Float, y: Float, id: Int = -1): Int {
        val geo = wheelGeometry() ?: return -1
        val pad = actionPad
        if (pad != null) {
            if (!pad.expanded) return -1
            val last = if (id >= 0) wheelIndex.get(id, -1) else -1
            return pad.hit(geo.cx, geo.cy, x, y, geo.r, innerPx = centerPx, lastIndex = last)
        }
        if (!inWheelCircle(x, y)) return -1
        val dx = x - geo.cx
        val dy = y - geo.cy
        val dist = hypot(dx, dy)
        if (dist < centerPx) return -1
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0f) angle += 360f
        val slots = defaultSlots
        val step = 360f / slots.size
        return (((angle + 90f + step / 2f) % 360f) / step).toInt()
            .coerceIn(0, slots.size - 1)
    }

    /**
     * 摆放轮盘外框（收起态的小球也在里面）。
     *
     * 存归一化位置而不是绝对像素：换分辨率 / 旋转 / 分屏后仍在可见区域。
     * 默认 (0, 1) = 左下角 12dp 内边距。
     */
    fun setPadPosition(normX: Float, normY: Float) {
        padNormX = normX.coerceIn(0f, 1f)
        padNormY = normY.coerceIn(0f, 1f)
        applyPadPosition()
    }

    private fun applyPadPosition() {
        val w = wheelView ?: return
        if (width == 0 || height == 0) return
        val lp = w.layoutParams as? FrameLayout.LayoutParams ?: return
        // 还没测量时（首次布局前）用 LayoutParams 里的尺寸，它等于测量后的值。
        val ww = if (w.width > 0) w.width else lp.width
        val wh = if (w.height > 0) w.height else lp.height
        if (ww <= 0 || wh <= 0) return
        val inset = (PAD_INSET_DP * density).toInt()
        val availX = (width - ww - 2 * inset).coerceAtLeast(0)
        val availY = (height - wh - 2 * inset).coerceAtLeast(0)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = inset + (availX * padNormX).toInt()
        lp.topMargin = inset + (availY * padNormY).toInt()
        w.layoutParams = lp
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyPadPosition()
    }

    /** 收起态按下小球：等到 1.5s 手指没抬也没怎么动就进编辑。 */
    private fun armBallLongPress(id: Int, x: Float, y: Float) {
        ballPressId = id
        ballPressX = x
        ballPressY = y
        removeCallbacks(editLongPress)
        postDelayed(editLongPress, editLongPressMs)
    }

    private fun disarmBallLongPress() {
        ballPressId = -1
        removeCallbacks(editLongPress)
    }

    /** 触摸轨迹落盘：真机验证时 adb 直接 cat files/touch.log 就能确认事件是否到达。 */
    fun trace(line: String) {
        runCatching {
            java.io.File(context.filesDir, "touch.log").appendText(line + "\n")
        }
    }

    /** 轮盘圆心与半径（px）。 */
    private class WheelGeo(val cx: Float, val cy: Float, val r: Float)

    private companion object {
        const val OWNER_GAME = 0
        const val OWNER_WHEEL = 1

        /** 左半屏浮动摇杆（UX-21）。 */
        const val OWNER_MOVE_PAD = 2

        /** 轮盘编辑态（长按小球 1.5s 进入）：优先级高于其它 owner。 */
        const val OWNER_EDIT = 3

        /** 收起态按住小球：可能是点一下展开、拖到边上、或长按进编辑。 */
        const val OWNER_BALL = 4

        /** wheelIndex 里的哨兵：这一指只用来展开/收起，全程不触发槽位动作。 */
        const val WHEEL_IGNORE = -2

        /** 轮盘与屏幕边缘的固定内边距（dp）：吸附落位也用它。 */
        const val PAD_INSET_DP = 12f

        /** 轮盘可视半径占外框短边的比例（和 ActionWheel / ActionPadOverlay 保持一致）。 */
        const val WHEEL_RADIUS_RATIO = 0.42f
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

package dev.mineport.launcher.game

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * 游戏根容器：按矩形把触摸分流。
 *
 * 轮盘区 -> 交给 Compose（super.dispatchTouchEvent）；
 * 其余全部 -> TouchInjector 直接注入 MC 输入，Compose/加载遮罩抢不走。
 * 一次手势的归属在 ACTION_DOWN 时确定，之后不再变化。
 */
class GameHostLayout @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    var wheelView: View? = null
    var gameView: View? = null

    private val injector = TouchInjector(this)
    private var owner = OWNER_NONE
    private val hitRect = Rect()

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            owner = if (hitWheel(ev)) OWNER_UI else OWNER_GAME
        }
        return when (owner) {
            OWNER_UI -> super.dispatchTouchEvent(ev)
            OWNER_GAME -> injector.touch(ev, gameView)
            else -> false
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // 游戏区的手势一律不交给子 View 竞争。
        return owner == OWNER_GAME
    }

    /** 触摸轨迹落盘：真机验证时 adb 直接 cat files/touch.log 就能确认事件是否到达。 */
    fun trace(line: String) {
        runCatching {
            java.io.File(context.filesDir, "touch.log").appendText(line + "\n")
        }
    }

    private fun hitWheel(ev: MotionEvent): Boolean {
        val wheel = wheelView ?: return false
        if (wheel.width == 0 || wheel.height == 0) return false
        hitRect.set(wheel.left, wheel.top, wheel.right, wheel.bottom)
        return hitRect.contains(ev.x.toInt(), ev.y.toInt())
    }

    private companion object {
        const val OWNER_NONE = 0
        const val OWNER_UI = 1
        const val OWNER_GAME = 2
    }
}

/**
 * 游戏区触摸 -> MC 输入。
 *
 * 语义对齐 FCL 的 `putMouseEventWithCoords`：
 * - **DOWN 立即 press**，33ms 后自动 release（这样 MC 的 `Screen.mouseClicked`
 *   一定拿到「按下 + 抬起」一对事件，点击才会生效）；
 * - 按住超过 300ms 升级为长按（挖矿/持续攻击），取消自动 release，保持按下直到 UP；
 * - UP：若仍按住就 release；CANCEL：释放 + 复位 FCL 输入状态。
 *
 * 之前「UP 时才补发 press」的写法是「adb 能点、真手指点不动」的直接原因：
 * adb tap 的 DOWN/UP 坐标完全相同，而真手指按 300ms 内必有几像素抖动，
 * press 落在抬起点上，一旦跨过控件边界就点到空处或相邻按钮。
 */
class TouchInjector(private val host: GameHostLayout) {

    private var lookId = -1
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f

    /** 当前是否处于「按下」状态（已经发过 press、还没 release）。 */
    private var held = false

    /** 是否已升级为长按：true 时 33ms 的自动 release 不再执行。 */
    private var longHeld = false

    private val clickRelease = Runnable {
        if (held && !longHeld) {
            inject(lastX, lastY, ACTION_RELEASE)
            held = false
        }
    }

    private val longPressArm = Runnable {
        longHeld = true
    }

    fun touch(ev: MotionEvent, gameView: View?): Boolean {
        if (gameView == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lookId = ev.getPointerId(0)
                downX = ev.x
                downY = ev.y
                lastX = downX
                lastY = downY
                held = false
                longHeld = false
                host.removeCallbacks(clickRelease)
                host.removeCallbacks(longPressArm)
                // 先把光标挪到按下点，再按下——顺序不能反，否则 MC 会用上一点的坐标判定命中。
                inject(downX, downY, ACTION_MOVE)
                inject(downX, downY, ACTION_PRESS)
                held = true
                host.postDelayed(clickRelease, CLICK_RELEASE_MS)
                host.postDelayed(longPressArm, LONG_PRESS_MS)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二指不参与视角，避免来回抖动。
            }

            MotionEvent.ACTION_MOVE -> {
                val idx = ev.findPointerIndex(lookId)
                if (idx < 0) return true
                val x = ev.getX(idx)
                val y = ev.getY(idx)
                // 视角必须逐点连续：任意像素变化都发一次，不能按阈值丢弃，
                // 否则慢速拖动会变成「不动 → 突然跳一格」。
                if (x != lastX || y != lastY) {
                    inject(x, y, ACTION_MOVE)
                    lastX = x
                    lastY = y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (ev.getPointerId(ev.actionIndex) == lookId) {
                    // 取剩余指针里 id 最小的那个接管视角（三指以上也成立）。
                    var best = -1
                    for (i in 0 until ev.pointerCount) {
                        if (i == ev.actionIndex) continue
                        if (best < 0 || ev.getPointerId(i) < ev.getPointerId(best)) best = i
                    }
                    if (best >= 0) {
                        lookId = ev.getPointerId(best)
                        lastX = ev.getX(best)
                        lastY = ev.getY(best)
                    } else {
                        lookId = -1
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                host.removeCallbacks(clickRelease)
                host.removeCallbacks(longPressArm)
                if (held) {
                    inject(lastX, lastY, ACTION_RELEASE)
                    held = false
                }
                lookId = -1
            }

            MotionEvent.ACTION_CANCEL -> {
                host.removeCallbacks(clickRelease)
                host.removeCallbacks(longPressArm)
                if (held) {
                    inject(lastX, lastY, ACTION_RELEASE)
                    held = false
                }
                // 系统手势打断后 FCL 内部可能残留按下态，必须复位。
                NativeRenderer.nativeResetInputState()
                lookId = -1
            }
        }
        return true
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

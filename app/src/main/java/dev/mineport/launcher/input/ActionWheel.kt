package dev.mineport.launcher.input

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 动作轮盘：按住任意位置拖动选择方向，松手触发对应 [GameAction]。
 *
 * 回调语义是「按住 / 松开」而不是「点一下」：前后左右移动是持续输入，
 * 松手必须 release，否则游戏里会卡键。切换扇区时先松开旧动作再按下新动作。
 *
 * 交互口径（DESIGN-STYLE 六）：描边圆 + 极淡填充，选中扇区才亮；背景压暗 40%。
 * 绘制用 Canvas，文字用 nativeCanvas（避免依赖 TextMeasurer 的版本差异）。
 */
@Composable
fun ActionWheel(
    modifier: Modifier = Modifier,
    actions: List<GameAction> = GameAction.Defaults,
    onAction: (GameAction, Boolean) -> Unit
) {
    if (actions.isEmpty()) return
    var active by remember { mutableStateOf<GameAction?>(null) }
    val latestOnAction by rememberUpdatedState(onAction)
    val density = LocalDensity.current

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(actions) {
                detectDragGestures(
                    onDragStart = { active = null },
                    onDrag = { change, _ ->
                        change.consume()
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val next = pick(change.position - center, actions)
                        if (next != active) {
                            active?.let { latestOnAction(it, false) }
                            next?.let { latestOnAction(it, true) }
                            active = next
                        }
                    },
                    onDragEnd = {
                        active?.let { latestOnAction(it, false) }
                        active = null
                    },
                    onDragCancel = {
                        active?.let { latestOnAction(it, false) }
                        active = null
                    }
                )
            }
    ) {
        val radius = minOf(size.width, size.height) * 0.42f
        val center = Offset(size.width / 2f, size.height / 2f)
        val step = 360f / actions.size
        val startOffset = -90f - step / 2f

        // 背景压暗
        drawCircle(Color(0x66000000), radius * 1.18f, center)

        actions.forEachIndexed { index, action ->
            val selected = action == active
            // 选中的扇区外扩 4%，形成「被按下去又弹起」的层次，而不是只换颜色
            val r = if (selected) radius * 1.04f else radius
            drawArc(
                color = if (selected) Color(0xCC22D3A6) else Color(0x22161B22),
                startAngle = startOffset + index * step,
                sweepAngle = step - 2f,
                useCenter = true,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2f, r * 2f)
            )
            // 1.5px 描边：DESIGN-STYLE 六「动作键：描边圆 + 极淡填充」
            drawArc(
                color = if (selected) Color(0xFF4ADE80) else Color(0x33FFFFFF),
                startAngle = startOffset + index * step,
                sweepAngle = step - 2f,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = with(density) { 1.5.dp.toPx() })
            )
        }

        // 中心圆与当前动作
        drawCircle(Color(0xDD0E1116), radius * 0.42f, center)
        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                textSize = with(density) { 12.dp.toPx() }
            }
            actions.forEachIndexed { index, action ->
                val mid = Math.toRadians((startOffset + index * step + step / 2f).toDouble())
                val x = center.x + (radius * 0.72f) * kotlin.math.cos(mid).toFloat()
                val y = center.y + (radius * 0.72f) * kotlin.math.sin(mid).toFloat()
                paint.color = if (action == active) 0xFF0E1116.toInt() else 0xFFE6EDF3.toInt()
                canvas.nativeCanvas.drawText(action.label, x, y + paint.textSize / 3f, paint)
            }
            paint.color = 0xFF22D3A6.toInt()
            paint.textSize = with(density) { 14.dp.toPx() }
            canvas.nativeCanvas.drawText(active?.label ?: "动作", center.x, center.y + paint.textSize / 3f, paint)
        }
    }
}

private fun pick(delta: Offset, actions: List<GameAction>): GameAction? {
    val distance = hypot(delta.x, delta.y)
    if (distance < 24f) return null
    var angle = Math.toDegrees(atan2(delta.y.toDouble(), delta.x.toDouble())).toFloat()
    if (angle < 0f) angle += 360f
    val step = 360f / actions.size
    val index = (((angle + 90f + step / 2f) % 360f) / step).toInt()
        .coerceIn(0, actions.size - 1)
    return actions[index]
}

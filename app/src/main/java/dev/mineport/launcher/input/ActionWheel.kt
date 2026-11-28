package dev.mineport.launcher.input

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * 动作轮盘：**纯展示**，不接收触摸。
 *
 * 命中判定在 [dev.mineport.launcher.game.GameHostLayout] 里按 pointer 手动做，
 * 这样「一根手指转视角、另一根手指按轮盘」才能同时成立——Compose 的触摸系统
 * 一次只能把整条手势交给一个 View，做不到按手指分流。
 *
 * 回调语义是「按住 / 松开」而不是「点一下」：前后左右是持续输入，松手必须 release。
 */
@Composable
fun ActionWheel(
    active: ActionSlot?,
    modifier: Modifier = Modifier,
    actions: List<ActionSlot> = ActionPad.defaultSlots(),
    /** 编辑态：扇区点击 = 换动作，中心变成「完成」。 */
    editing: Boolean = false,
    /** 编辑态刚改过的槽位下标（-1 表示没有）。 */
    editSlot: Int = -1,
) {
    if (actions.isEmpty()) return
    val density = LocalDensity.current

    Canvas(modifier = modifier.fillMaxSize()) {
        val radius = minOf(size.width, size.height) * 0.42f
        val center = Offset(size.width / 2f, size.height / 2f)
        val step = 360f / actions.size
        val startOffset = -90f - step / 2f

        // 背景压暗：编辑态再深一点，压住游戏画面、突出槽位
        drawCircle(if (editing) Color(0x99000000) else Color(0x66000000), radius * 1.18f, center)

        actions.forEachIndexed { index, action ->
            val selected = action == active || (editing && index == editSlot)
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
            val centerText = when {
                editing -> "完成"
                else -> active?.label ?: "动作"
            }
            canvas.nativeCanvas.drawText(centerText, center.x, center.y + paint.textSize / 3f, paint)
        }
    }
}

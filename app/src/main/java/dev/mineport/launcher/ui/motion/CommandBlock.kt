package dev.mineport.launcher.ui.motion

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * UX-22 的视觉锚点：一颗等距投影的命令方块。
 *
 * 全部几何在 Canvas 里画（顶面菱形 + 左右侧面 + 1dp 内描边），不用切图：
 * 40dp 的方块放大到 160dp 也不糊，而且颜色能跟着主色走。
 *
 * 动画值全部在 `graphicsLayer` / 绘制阶段读取，因此每帧只重绘、不重组。
 */
@Composable
fun CommandBlock(
    stage: MotionStage,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.graphicsLayer {
            // 碎裂时整体缩小 + 淡出；T5 接粒子后这里只留体积变化。
            val shrink = 1f - 0.35f * stage.shatter.value
            val s = stage.blockScale.value * shrink
            scaleX = s
            scaleY = s
            rotationZ = stage.blockRotate.value
            alpha = (1f - stage.shatter.value).coerceIn(0f, 1f)
            // rail 被拽动时方块跟着下坠一点（14dp 满量程），表达「方块压在车上」。
            translationY = stage.railPull.value * 14.dp.toPx()
        }
    ) {
        drawCommandBlock(
            accent = accent,
            material = stage.blockMaterial.value.coerceIn(0f, 1f),
            flash = stage.flash.value.coerceIn(0f, 1f)
        )
    }
}

/**
 * 等距立方体：一个顶面菱形 + 两个侧面。
 *
 * 尺寸关系（a = 竖棱长）：宽 = 2·0.866a，高 = 1.5a。整套坐标以画布中心为原点，
 * 所以放进任何 size 都会居中。
 */
private fun DrawScope.drawCommandBlock(accent: Color, material: Float, flash: Float) {
    val unit = size.minDimension
    if (unit <= 0f) return
    val a = unit * 0.55f           // 竖棱长
    val halfW = a * 0.866f         // 等距投影的水平半宽
    val halfH = a * 0.5f           // 顶面菱形的半高
    val cx = size.width / 2f
    val cy = size.height / 2f
    // 让 bbox（高 1.5a）在画布内垂直居中：顶面下顶点的 y。
    val px = cx
    val py = cy - a * 0.25f

    val x0 = px
    val yTop = py - halfH
    val yMid = py - halfH * 0.5f
    val yDown = py + a
    val yRight = yMid + a

    // 命令方块绿 → 主色（唤醒）。lerp 只在两个端点间插值，比手写 rgb 混算省。
    val base = Color(0xFF2E8B57)
    val body = lerp(base, accent, material * 0.8f)
    val topColor = lerp(body, Color.White, 0.22f + 0.10f * material)
    val leftColor = lerp(body, Color.Black, 0.16f)
    val rightColor = lerp(body, Color.Black, 0.30f)

    val top = Path().apply {
        moveTo(x0, yTop)
        lineTo(px + halfW, yMid)
        lineTo(x0, py)
        lineTo(px - halfW, yMid)
        close()
    }
    val left = Path().apply {
        moveTo(px - halfW, yMid)
        lineTo(x0, py)
        lineTo(x0, yDown)
        lineTo(px - halfW, yRight)
        close()
    }
    val right = Path().apply {
        moveTo(x0, py)
        lineTo(px + halfW, yMid)
        lineTo(px + halfW, yRight)
        lineTo(x0, yDown)
        close()
    }

    drawPath(left, leftColor)
    drawPath(right, rightColor)
    drawPath(top, topColor)

    // 唤醒后顶面有一道随相位扫过的流光（T6 由丝带驱动 phase）。
    if (material > 0.01f) {
        drawPath(
            path = top,
            brush = Brush.sweepGradient(
                colors = listOf(
                    Color.Transparent,
                    accent.copy(alpha = 0.45f * material),
                    Color.Transparent
                ),
                center = Offset(x0, py - halfH * 0.5f)
            )
        )
    }

    val stroke = 1.dp.toPx()
    val outline = Color.Black.copy(alpha = 0.30f)
    drawPath(top, outline, style = Stroke(stroke))
    drawPath(left, outline, style = Stroke(stroke))
    drawPath(right, outline, style = Stroke(stroke))

    // 撞击白闪：整块叠一层白，比逐面提亮便宜且更"闪"。
    if (flash > 0.01f) {
        drawRect(Color.White.copy(alpha = 0.38f * flash))
    }
}

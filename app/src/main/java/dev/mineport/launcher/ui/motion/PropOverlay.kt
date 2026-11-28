package dev.mineport.launcher.ui.motion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * UX-22 T3：道具层。全屏透明 overlay，只在 [MotionStage.prop] 不为 None 时画东西。
 *
 * 为什么单独一层而不是画进 CommandBlock：道具从右侧屏幕外飞过来，跨度远超
 * rail 底部那 42dp；放进方块自己的 Canvas 会被裁掉。
 *
 * Canvas 不吃触摸（Canvas 本身只有 draw 阶段，没有 pointerInput），所以它盖在
 * 内容区上面不会挡住任何点击。
 */
@Composable
fun PropOverlay(
    stage: MotionStage,
    accent: Color,
    blockCenter: Offset,
    modifier: Modifier = Modifier
) {
    Canvas(modifier.fillMaxSize()) {
        // 在绘制阶段读 state：只在有道具的那 ~300ms 里重绘，平时零成本。
        val kind = stage.prop
        if (kind == MotionProp.None) return@Canvas
        val p = stage.propProgress.value
        val r = stage.propRecoil.value
        when (kind) {
            MotionProp.Arrow -> drawPropArrow(
                progress = p,
                recoil = r,
                from = Offset(size.width + 90.dp.toPx(), blockCenter.y),
                to = blockCenter,
                accent = accent
            )

            MotionProp.Gear -> drawPropGear(
                progress = p,
                recoil = r,
                from = Offset(blockCenter.x + 240.dp.toPx(), blockCenter.y - 150.dp.toPx()),
                to = Offset(blockCenter.x, blockCenter.y - 8.dp.toPx()),
                accent = accent
            )

            MotionProp.None -> Unit
        }
    }
}

/** 下载箭头：钝头箭 + 三条速度线，从右往左冲。 */
private fun DrawScope.drawPropArrow(
    progress: Float,
    recoil: Float,
    from: Offset,
    to: Offset,
    accent: Color
) {
    val p = progress.coerceIn(0f, 1f)
    val len = 54.dp.toPx()
    val half = 21.dp.toPx()
    // 撞上后往右弹开 34dp，同时稍微变淡 —— 给「被顶回来」的方向感。
    val x = from.x + (to.x - from.x) * p + recoil * 34.dp.toPx()
    val y = from.y + (to.y - from.y) * p
    val tipX = x - len * 0.5f
    val alpha = (1f - 0.35f * recoil).coerceIn(0.3f, 1f)

    val path = Path().apply {
        moveTo(tipX, y)
        lineTo(tipX + len * 0.56f, y - half)
        lineTo(tipX + len * 0.42f, y - half * 0.34f)
        lineTo(tipX + len, y - half * 0.34f)
        lineTo(tipX + len, y + half * 0.34f)
        lineTo(tipX + len * 0.42f, y + half * 0.34f)
        lineTo(tipX + len * 0.56f, y + half)
        close()
    }
    drawPath(path, accent.copy(alpha = 0.92f * alpha))

    val lineColor = accent.copy(alpha = 0.32f * alpha)
    repeat(3) { i ->
        val ly = y - half * 0.62f + i * half * 0.62f
        val startX = tipX + len * 1.04f
        drawLine(
            color = lineColor,
            start = Offset(startX, ly),
            end = Offset(startX + (22f + i * 13f).dp.toPx(), ly),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

/**
 * 设置齿轮：8 齿多边形 + 中心挖孔，沿一条上凸弧线飞到方块左上角扣合。
 *
 * 弧线用 `sin(p * PI)` 抬一下：直线飞会像贴图平移，抬 26dp 才有「甩过来」的重量。
 */
private fun DrawScope.drawPropGear(
    progress: Float,
    recoil: Float,
    from: Offset,
    to: Offset,
    accent: Color
) {
    val p = progress.coerceIn(0f, 1f)
    val arc = sin(p * PI).toFloat() * 26.dp.toPx()
    val cx = from.x + (to.x - from.x) * p + recoil * 18.dp.toPx()
    val cy = from.y + (to.y - from.y) * p - arc
    val outer = 23.dp.toPx()
    val inner = 17.dp.toPx()
    val hole = 7.dp.toPx()

    val teeth = Path()
    val points = 32
    for (i in 0 until points) {
        // -90° 起步，保证第一颗齿朝上；每 4 个点交替内外半径 = 8 齿。
        val a = Math.toRadians(i * 360.0 / points - 90.0)
        val radius = if (i % 4 < 2) outer else inner
        val px = cx + cos(a).toFloat() * radius
        val py = cy + sin(a).toFloat() * radius
        if (i == 0) teeth.moveTo(px, py) else teeth.lineTo(px, py)
    }
    teeth.close()

    val holePath = Path().apply {
        addOval(
            androidx.compose.ui.geometry.Rect(
                center = Offset(cx, cy),
                radius = hole
            )
        )
    }    // EvenOdd 差集挖孔，比再画一个背景色圆更稳（overlay 是透明的，画不了背景色）。
    val gear = Path.combine(PathOperation.Difference, teeth, holePath)

    // 扣合过程转 45°：从斜插到对正。
    rotate(degrees = -45f + 45f * p, pivot = Offset(cx, cy)) {
        drawPath(gear, accent.copy(alpha = 0.95f))
        drawPath(gear, Color.White.copy(alpha = 0.18f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
    }
    // 扣合瞬间的一圈金属反光。用极细的圆环，成本可以忽略。
    if (p > 0.85f) {
        drawCircle(
            color = Color.White.copy(alpha = 0.30f * (p - 0.85f) / 0.15f),
            radius = inner * 1.28f,
            center = Offset(cx, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())
        )
    }

}

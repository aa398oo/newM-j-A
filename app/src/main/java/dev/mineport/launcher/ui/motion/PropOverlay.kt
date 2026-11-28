package dev.mineport.launcher.ui.motion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * UX-22 T3：道具层。全屏透明 overlay，只在 [MotionStage.prop] 不为 None 时画东西。
 *
 * 为什么单独一层而不是画进 CommandBlock：道具从右侧屏幕外飞过来，跨度远超
 * rail 图标本身（22dp）；放进图标自己的 Canvas 会被裁掉。
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
                from = Offset(blockCenter.x + 70.dp.toPx(), blockCenter.y),
                to = blockCenter,
                accent = accent
            )

            MotionProp.Gear -> drawPropGear(
                progress = p,
                recoil = r,
                from = Offset(blockCenter.x + 84.dp.toPx(), blockCenter.y - 52.dp.toPx()),
                to = Offset(blockCenter.x, blockCenter.y - 2.dp.toPx()),
                accent = accent
            )

            MotionProp.Brush -> drawPropBrush(
                progress = p,
                from = Offset(blockCenter.x - 40.dp.toPx(), blockCenter.y - 12.dp.toPx()),
                to = Offset(blockCenter.x + 14.dp.toPx(), blockCenter.y - 12.dp.toPx()),
                accent = accent
            )

            MotionProp.None -> Unit
        }

        // T5 碎裂粒子：与道具互斥（碎裂时 prop 为 None），单 t 驱动，
        // 只在这一帧有值时才计算，shatter 归零后完全不再进循环。
        val sh = stage.shatter.value
        if (sh > 0.001f) {
            drawShards(t = sh, center = blockCenter, accent = accent)
        }

        // T6 丝带：只在唤醒过程中存在，phase 归零即消失。
        val ribbon = stage.ribbonPhase.value
        if (ribbon > 0.001f) {
            drawRibbons(phase = ribbon, center = blockCenter, accent = accent)
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
    val len = 22.dp.toPx()
    val half = 9.dp.toPx()
    // 撞上后往右弹开 34dp，同时稍微变淡 —— 给「被顶回来」的方向感。
    val x = from.x + (to.x - from.x) * p + recoil * 12.dp.toPx()
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
            end = Offset(startX + (7f + i * 5f).dp.toPx(), ly),
            strokeWidth = 1.6.dp.toPx(),
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
    val arc = sin(p * PI).toFloat() * 9.dp.toPx()
    val cx = from.x + (to.x - from.x) * p + recoil * 7.dp.toPx()
    val cy = from.y + (to.y - from.y) * p - arc
    val outer = 11.dp.toPx()
    val inner = 8.dp.toPx()
    val hole = 3.4.dp.toPx()

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
        drawPath(gear, Color.White.copy(alpha = 0.18f), style = androidx.compose.ui.graphics.drawscope.Stroke(0.8.dp.toPx()))
    }
    // 扣合瞬间的一圈金属反光。用极细的圆环，成本可以忽略。
    if (p > 0.85f) {
        drawCircle(
            color = Color.White.copy(alpha = 0.30f * (p - 0.85f) / 0.15f),
            radius = inner * 1.28f,
            center = Offset(cx, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
        )
    }
}

/**
 * 个性化刷子（T4）：斜 18° 的木柄 + 白刷毛 + 身后一条淡色扫痕。
 *
 * 刷子从方块左侧外扫到右侧，跟 `blockMaterial` 的 0→1 线性推进同源，
 * 所以「刷到哪，方块的彩色就亮到哪」。
 */
private fun DrawScope.drawPropBrush(
    progress: Float,
    from: Offset,
    to: Offset,
    accent: Color
) {
    val p = progress.coerceIn(0f, 1f)
    val x = from.x + (to.x - from.x) * p
    val y = from.y + (to.y - from.y) * p
    val w = 15.dp.toPx()
    val h = 6.5.dp.toPx()

    // 扫痕压在手底下。
    drawLine(
        color = accent.copy(alpha = 0.20f),
        start = from,
        end = Offset(x, y),
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round
    )

    rotate(degrees = 18f, pivot = Offset(x, y)) {
        drawRoundRect(
            color = accent.copy(alpha = 0.95f),
            topLeft = Offset(x - w * 0.5f, y - h * 2.1f),
            size = Size(w, h * 1.55f),
            cornerRadius = CornerRadius(6.dp.toPx())
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.92f),
            topLeft = Offset(x - w * 0.5f, y - h * 0.62f),
            size = Size(w, h * 0.70f),
            cornerRadius = CornerRadius(2.dp.toPx())
        )
    }
}

/**
 * T5 碎裂粒子：12 块小方块，固定角度 + 初速，位置全是 t 的函数。
 *
 * 为什么不做 12 个 Animatable：12 条动画 = 12 次状态写入 + 12 次重组调度；
 * 这里只有一个 `shatter` 在动，绘制时按 t 算出位置，成本是一次 for 循环。
 * 抛物线用 `0.5 * g * t²`，旋转按块号奇偶反向，看起来就是炸开的方块。
 */
private fun DrawScope.drawShards(t: Float, center: Offset, accent: Color) {
    val count = 12
    val gravity = 900f
    val baseSpeed = 52.dp.toPx()
    val base = Color(0xFF2E8B57)

    for (i in 0 until count) {
        val angle = Math.toRadians((i * (360.0 / count) + 12.0))
        val speed = baseSpeed * (0.55f + 0.45f * ((i % 3) / 2f))
        // 每块错开 2% 起始延迟，避免 12 块同时起步显得假。
        val local = (t - 0.02f * i).coerceIn(0f, 1f)
        if (local <= 0f) continue
        val x = center.x + cos(angle).toFloat() * speed * local * 1.8f
        val y = center.y + sin(angle).toFloat() * speed * local * 1.8f +
            0.5f * gravity * local * local
        val size = (3.2.dp.toPx() * (1f - local * 0.7f)).coerceAtLeast(1f)
        val alpha = (1f - local).coerceIn(0f, 1f)
        val color = if (i % 3 == 0) accent.copy(alpha = alpha) else base.copy(alpha = alpha)
        rotate(degrees = local * 220f * if (i % 2 == 0) 1f else -1f, pivot = Offset(x, y)) {
            drawRect(
                color = color,
                topLeft = Offset(x - size / 2f, y - size / 2f),
                size = Size(size, size)
            )
        }
    }
}
/**
 * T6 Fabric 丝带：三条彩带绕方块转一圈。
 *
 * 每条用 8 段三次贝塞尔近似椭圆（标准圆弧公式 `k = 4/3 * tan(θ/4)`，椭圆按 y 轴压缩），
 * 比 28 段直线更省顶点、也更贴「丝带」的顺滑感。
 * 「背面」段落（sin(a) < 0 的部分）用低 alpha 先画，「正面」用高 alpha 后画，
 * 不需要真的把方块夹在中间也能造出缠绕的错觉。
 */
private fun DrawScope.drawRibbons(phase: Float, center: Offset, accent: Color) {
    val specs = listOf(
        Triple(11.dp.toPx(), accent, 0f),
        Triple(13.dp.toPx(), Color(0xFF22D3A6), 120f),
        Triple(15.dp.toPx(), Color(0xFFE8C9A0), 240f)
    )
    val tilt = 0.34f
    val seg = 8
    val stroke = 1.8.dp.toPx()

    for ((index, spec) in specs.withIndex()) {
        val (radius, color, offset) = spec
        val start = Math.toRadians((phase + offset).toDouble())
        for (s in 0 until seg) {
            val a1 = start + 2 * PI * s / seg
            val a2 = start + 2 * PI * (s + 1) / seg
            val k = (4.0 / 3.0 * tan((a2 - a1) / 4.0)).toFloat()

            val p1 = Offset(center.x + cos(a1).toFloat() * radius, center.y + sin(a1).toFloat() * radius * tilt)
            val p2 = Offset(center.x + cos(a2).toFloat() * radius, center.y + sin(a2).toFloat() * radius * tilt)
            val t1 = Offset(-sin(a1).toFloat() * radius, cos(a1).toFloat() * radius * tilt)
            val t2 = Offset(-sin(a2).toFloat() * radius, cos(a2).toFloat() * radius * tilt)

            val path = Path().apply {
                moveTo(p1.x, p1.y)
                cubicTo(
                    p1.x + t1.x * k, p1.y + t1.y * k,
                    p2.x - t2.x * k, p2.y - t2.y * k,
                    p2.x, p2.y
                )
            }
            // 背面（椭圆上半，sin > 0 在屏幕坐标里是下方）压暗，形成前后关系。
            val back = sin((a1 + a2) / 2) < 0.0
            val alpha = if (back) 0.38f else 0.92f
            drawPath(path, color.copy(alpha = alpha), style = Stroke(stroke, cap = StrokeCap.Round))
            if (index == 0 && !back) {
                drawPath(path, Color.White.copy(alpha = 0.22f), style = Stroke(stroke * 0.45f, cap = StrokeCap.Round))
            }
        }
    }
}
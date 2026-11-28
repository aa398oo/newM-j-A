package dev.mineport.launcher.ui.motion

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * rail 上的五个图标，改成 Canvas 几何自绘。
 *
 * 为什么不用 Material 的 `ImageVector`：UX-22 要求「全 Canvas 几何、禁高清切图」，
 * 而且图标要能跟着动画值变形（压缩/旋转/白闪/材质刷出），`Icon()` 只吃静态 vector。
 */
enum class RailGlyph { Play, Download, Settings, Palette, More }

/** 画一个图标。几何按 `size.minDimension` 归一化，22dp 与 42dp 下都清楚。 */
fun DrawScope.drawRailGlyph(glyph: RailGlyph, color: Color) {
    val s = size.minDimension
    val cx = size.width / 2f
    val cy = size.height / 2f
    val stroke = s * 0.10f
    when (glyph) {
        RailGlyph.Play -> {
            val w = s * 0.20f
            val h = s * 0.24f
            val path = Path().apply {
                moveTo(cx - w * 0.9f, cy - h)
                lineTo(cx - w * 0.9f, cy + h)
                lineTo(cx + w * 1.5f, cy)
                close()
            }
            drawPath(path, color)
        }

        RailGlyph.Download -> {
            val halfW = s * 0.17f
            val topY = cy - s * 0.32f
            val bottomY = cy + s * 0.10f
            drawLine(color, Offset(cx, topY), Offset(cx, bottomY), stroke * 1.2f, StrokeCap.Round)
            val head = Path().apply {
                moveTo(cx - halfW, bottomY - halfW * 0.50f)
                lineTo(cx + halfW, bottomY - halfW * 0.50f)
                lineTo(cx, bottomY + halfW * 0.95f)
                close()
            }
            drawPath(head, color)
            drawLine(
                color, Offset(cx - s * 0.28f, cy + s * 0.30f), Offset(cx + s * 0.28f, cy + s * 0.30f),
                stroke, StrokeCap.Round
            )
        }

        RailGlyph.Settings -> {
            // 8 齿齿轮：32 个采样点，每 4 个里前 2 个在外圈；中心挖孔。
            val outer = s * 0.36f
            val inner = s * 0.27f
            val hole = s * 0.13f
            val teeth = Path()
            val points = 32
            for (i in 0 until points) {
                val a = (i.toDouble() / points) * PI * 2.0 - PI / 2.0
                val r = if (i % 4 < 2) outer else inner
                val x = cx + (cos(a) * r).toFloat()
                val y = cy + (sin(a) * r).toFloat()
                if (i == 0) teeth.moveTo(x, y) else teeth.lineTo(x, y)
            }
            teeth.close()
            val holePath = Path().apply { addOval(Rect(Offset(cx, cy), hole)) }
            drawPath(Path.combine(PathOperation.Difference, teeth, holePath), color)
        }

        RailGlyph.Palette -> {
            // 22dp 下最稳的调色板符号：实心圆盘 + 三个镂空色孔（孔要够大，
            // 盘要占满 22dp 的 70%，否则缩到 21px 就只剩一个点）。
            val r = s * 0.35f
            val disc = Path().apply { addOval(Rect(Offset(cx, cy), r)) }
            val holes = Path()
            listOf(
                Offset(cx - r * 0.42f, cy - r * 0.10f),
                Offset(cx + r * 0.02f, cy - r * 0.48f),
                Offset(cx + r * 0.46f, cy - r * 0.02f)
            ).forEach { o -> holes.addOval(Rect(o, r * 0.20f)) }
            drawPath(Path.combine(PathOperation.Difference, disc, holes), color)
        }

        RailGlyph.More -> {
            val gap = s * 0.24f
            val r = s * 0.082f
            for (i in -1..1) drawCircle(color, r, Offset(cx + gap * i, cy))
        }
    }
}

/**
 * rail 里真正用的图标：把 [MotionStage] 的当前动画值套到「正在动的那个图标」上。
 *
 * 六种动画全部落在图标本身：
 * - `blockScale` / `blockRotate`：撞击压缩、齿轮扣合旋转
 * - `flash`：撞上那一帧的白闪
 * - `blockMaterial`：刷子从左到右把单色刷成主色（`clipRect` 两层叠加）
 * - `shatter`：碎裂时图标整体缩小淡出（碎片由 PropOverlay 画）
 *
 * 动画值都在 `graphicsLayer{}` / `Canvas.onDraw` 的 draw 阶段读，不逐帧重组。
 */
@Composable
fun RailGlyphIcon(
    glyph: RailGlyph,
    stage: MotionStage,
    color: Color,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val active = stage.glyph == glyph
    Canvas(
        modifier.graphicsLayer {
            scaleX = if (active) stage.blockScale.value else 1f
            scaleY = if (active) stage.blockScale.value else 1f
            rotationZ = if (active) stage.blockRotate.value else 0f
            alpha = if (active) 1f - stage.shatter.value else 1f
        }
    ) {
        drawRailGlyph(glyph, color)
        if (active) {
            val material = stage.blockMaterial.value
            if (material > 0.001f) {
                clipRect(right = size.width * material) { drawRailGlyph(glyph, accent) }
            }
            val flash = stage.flash.value
            if (flash > 0.001f) {
                drawRailGlyph(glyph, Color.White.copy(alpha = 0.55f * flash))
            }
        }
    }
}

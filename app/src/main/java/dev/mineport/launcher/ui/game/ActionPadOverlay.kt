package dev.mineport.launcher.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.mineport.launcher.input.ActionPad

/**
 * 动作轮盘「收起态」的绘制层（UX-21）。
 *
 * 收起时轮盘缩成一颗 48dp 半透明小球：点它展开，展开态由 `ActionWheel` 负责画。
 * 这里只在 `pad.expanded == false` 时绘制，所以两层不会同时出现。
 *
 * 球上画一个简单的剑形（菱形 + 竖线），避免依赖字体渲染「⚔」——
 * Canvas 画文字需要 TextMeasurer，横屏低端机上反而更贵。
 */
@Composable
fun ActionPadOverlay(
    pad: ActionPad,
    centerX: Float,
    centerY: Float,
    modifier: Modifier = Modifier,
) {
    if (pad.expanded) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val radius = 24.dp.toPx()
        val center = Offset(centerX, centerY)
        // 球体：品牌蓝 80% 不透明 + 白色描边
        drawCircle(color = Color(0xCC1E5EFF), radius = radius, center = center)
        drawCircle(color = Color(0x99FFFFFF), radius = radius, center = center, style = Stroke(width = 2f))

        // 剑形：菱形剑身 + 下方短柄
        val w = radius * 0.28f
        val h = radius * 0.55f
        val blade = Path().apply {
            moveTo(center.x, center.y - h)          // 剑尖
            lineTo(center.x + w, center.y)          // 右
            lineTo(center.x, center.y + h * 0.35f)  // 剑格
            lineTo(center.x - w, center.y)          // 左
            close()
        }
        drawPath(blade, Color.White)
        // 剑柄
        drawLine(
            color = Color.White,
            start = Offset(center.x, center.y + h * 0.35f),
            end = Offset(center.x, center.y + h * 0.75f),
            strokeWidth = 4f,
        )
        // 剑格横线
        drawLine(
            color = Color.White,
            start = Offset(center.x - w * 1.4f, center.y + h * 0.35f),
            end = Offset(center.x + w * 1.4f, center.y + h * 0.35f),
            strokeWidth = 4f,
        )
    }
}

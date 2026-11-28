package dev.mineport.launcher.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.mineport.launcher.input.MovePad

/**
 * 浮动摇杆的绘制层（UX-21）。
 *
 * 只画三样东西：基座圆、基座描边、摇杆头。
 * 位置全部来自 [MovePad] 的 state——`visible=false` 时整层不绘制（`return` 早退），
 * 所以它叠在游戏画面上不占触摸、也不额外耗帧。
 *
 * 颜色用品牌绿 `#22D3A6` + 白色描边，和 HUD 的深色底形成对比；
 * 透明度都压到 0.8 以下，避免挡住方块。
 */
@Composable
fun MovePadOverlay(pad: MovePad, baseRadiusPx: Float, modifier: Modifier = Modifier) {
    if (!pad.visible) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(pad.anchorX, pad.anchorY)
        // 基座：淡白填充 + 稍亮描边
        drawCircle(color = Color(0x33FFFFFF), radius = baseRadiusPx, center = center)
        drawCircle(
            color = Color(0x66FFFFFF),
            radius = baseRadiusPx,
            center = center,
            style = Stroke(width = 3f),
        )
        // 摇杆头：品牌绿实心 + 白色描边
        val head = Offset(center.x + pad.headX, center.y + pad.headY)
        drawCircle(color = Color(0xCC22D3A6), radius = baseRadiusPx * 0.42f, center = head)
        drawCircle(
            color = Color(0xFFFFFFFF),
            radius = baseRadiusPx * 0.42f,
            center = head,
            style = Stroke(width = 4f),
        )
    }
}

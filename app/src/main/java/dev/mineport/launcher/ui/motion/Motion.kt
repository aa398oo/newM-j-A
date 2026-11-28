package dev.mineport.launcher.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 动效规范（DESIGN-STYLE 第五节）的唯一实现处，避免每个页面各写一套时长与曲线。
 *
 * 三条规则：
 * 1. 页面切换：淡入 + 轻微上移，220ms ease-out；
 * 2. 按钮：按下缩放 0.97（涟漪由 Material 自带）；
 * 3. 加载：三个方块依次呼吸，不用转圈。
 */
object Motion {
    /** 页面转场。 */
    const val ScreenMs = 220

    /** 列表项入场，每项递增的延迟。 */
    const val ItemStepMs = 40L

    /** 无限长的列表只给前几项做延迟，否则最后一项要等很久。 */
    const val MaxStagger = 8

    /** 220ms 内先快后慢，比 Material 默认的线性更有“落位感”。 */
    val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 按压回弹：阻尼低一点，松手有轻微回弹。 */
    val PressSpring = spring<Float>(dampingRatio = 0.55f, stiffness = 900f)
}

/**
 * 界面动画总开关（UX-18「动效」组）。关掉后所有入口动画与转场立刻完成：
 * 省电，也让对动画敏感的用户不被拖慢。由 MainActivity 按 Prefs 提供。
 */
val LocalAnimationsEnabled = staticCompositionLocalOf { true }

/**
 * 按下缩放。必须和按钮共享同一个 [MutableInteractionSource]：
 * ```
 * val source = remember { MutableInteractionSource() }
 * Button(onClick = ..., interactionSource = source, modifier = Modifier.pressScale(source)) { ... }
 * ```
 */
@Composable
fun Modifier.pressScale(
    source: MutableInteractionSource,
    scaleWhenPressed: Float = 0.97f
): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleWhenPressed else 1f,
        animationSpec = Motion.PressSpring,
        label = "pressScale"
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 入场：从下方 14dp 处淡入，按 [index] 依次延迟，形成轻微的瀑布感。
 * 用于卡片、列表首屏；不要给每次重组都重放（LaunchedEffect 只跑一次）。
 */
@Composable
fun EnterFromBottom(index: Int = 0, content: @Composable () -> Unit) {
    val enabled = LocalAnimationsEnabled.current
    val progress = remember { Animatable(if (enabled) 0f else 1f) }
    val offsetPx = with(LocalDensity.current) { 14.dp.toPx() }
    LaunchedEffect(index, enabled) {
        if (!enabled) {
            progress.snapTo(1f)
        } else {
            delay(index.coerceIn(0, Motion.MaxStagger) * Motion.ItemStepMs)
            progress.animateTo(1f, tween(durationMillis = 260, easing = Motion.Ease))
        }
    }
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * offsetPx
        }
    ) {
        content()
    }
}

/**
 * 像素风加载指示：三个 6dp 方块依次呼吸（不是转圈）。
 * 用在「正在检测运行时」这类短等待上，颜色由调用方给（一般 primary 或 onSurfaceVariant）。
 */
@Composable
fun PixelDotsLoading(
    modifier: Modifier = Modifier,
    color: Color
) {
    val transition = rememberInfiniteTransition(label = "pixelDots")
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { index ->
            val dotAlpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 420,
                        delayMillis = index * 140,
                        easing = LinearEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .graphicsLayer { alpha = dotAlpha }
                    .background(color)
            )
        }
    }
}

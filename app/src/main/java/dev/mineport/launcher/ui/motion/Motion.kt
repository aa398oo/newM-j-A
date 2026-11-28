package dev.mineport.launcher.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TweenSpec
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
 * 动效规范（UX-20 + DESIGN-STYLE 第五节）的唯一实现处，避免每个页面各写一套时长与曲线。
 *
 * 三条规则：
 * 1. 短时长、先快后慢：micro 120 / small 180 / medium 260 / large 340，**一律不超过 400ms**；
 * 2. 灵动靠 spring（按压、选中），平滑靠 ease-out（转场、内容变化）；
 * 3. 加载：三个方块依次呼吸，不用转圈。
 */
object Motion {
    /** 按压反馈、图标切换。 */
    const val MicroMs = 120

    /** 单项内容变化、勾选、chip 切换。 */
    const val SmallMs = 180

    /** 页面切换、卡片展开、弹层。 */
    const val MediumMs = 260

    /** 主题切换、全屏转场。 */
    const val LargeMs = 340

    /** 页面转场（= medium）。保留旧名，调用点不用改。 */
    const val ScreenMs = MediumMs

    /** 页面退出：比进场短，避免来回拖沓（取 120–180 之间的 150）。 */
    const val ExitMs = 150

    /** rail 左侧 3dp 蓝条高度动画，UX-20 场景表明确写 220ms。 */
    const val RailBarMs = 220

    /** 列表项入场，每项递增的延迟。 */
    const val ItemStepMs = 30L

    /** 无限长的列表只给前几项做延迟，否则最后一项要等很久。 */
    const val MaxStagger = 8

    /** 默认曲线：快进慢出。 */
    val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 数值滚动用的强调曲线（EaseOutCubic）。 */
    val EaseOutCubic = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

    /** 按压回弹：阻尼 0.8 + 中刚度，按下有回落但不晃。 */
    val PressSpring = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)

    /** 四档 tween，直接给 animateFloatAsState / Animatable 用。 */
    val Micro: TweenSpec<Float> = tween(durationMillis = MicroMs, easing = Ease)
    val Small: TweenSpec<Float> = tween(durationMillis = SmallMs, easing = Ease)
    val Medium: TweenSpec<Float> = tween(durationMillis = MediumMs, easing = Ease)
    val Large: TweenSpec<Float> = tween(durationMillis = LargeMs, easing = Ease)
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
            progress.animateTo(1f, Motion.Medium)
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

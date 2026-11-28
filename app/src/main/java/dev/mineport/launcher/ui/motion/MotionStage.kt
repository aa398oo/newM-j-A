package dev.mineport.launcher.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * UX-22 的动画事件。一个事件 = 一次「可被打断的动画序列」。
 *
 * 注意这里刻意不是完整状态机的入口：逻辑阶段见 [MotionStageState]，
 * 但动画值全部由 [MotionStage] 里的 Animatable 承载，事件之间靠
 * [MotionStage.play] 的 MutatorMutex 排队/抢占。
 */
enum class MotionIntent {
    /** 点 rail 某一项：方块被撞一下，然后回弹。 */
    TabImpact,

    /** 进下载页：巨型箭头从右侧冲进来撞方块（T3）。 */
    DownloadImpact,

    /** 进设置页：齿轮从右上飞过来扣合（T3）。 */
    SettingsImpact,

    /** 进个性化页：刷子从左往右粉刷方块，材质逐段显现（T4）。 */
    PersonalizeBrush,

    /** 子目录进入：方块碎裂（T5 才接粒子，T1 只有缩小淡出）。 */
    Shatter,

    /** Fabric 丝带唤醒材质（T6 接丝带，T1 只有颜色过渡）。 */
    Awaken
}

/** T3 的道具。绘制在 [dev.mineport.launcher.ui.motion.PropOverlay] 里。 */
enum class MotionProp { None, Arrow, Gear, Brush }

/** 当前的逻辑阶段。只用于内容切换 / 测试 / a11y，不驱动动画值。 */
enum class MotionStageState { Idle, Impacted, Shattering }

/**
 * UX-22 动画宿主：四层并行（L1 rail / L2 方块 / L3 道具 / L4 碎裂）共用的
 * 唯一动画值容器。
 *
 * 为什么不是 sealed class 状态机：四层可以同时进行（方块碎裂时用户仍能滑
 * rail），串行枚举会互相阻塞。这里用一把 [MutatorMutex] 表达「新事件取消
 * 旧事件」，Animatable 从当前值继续，所以天然可打断、可倒放。
 *
 * 所有动画值都是 [Animatable]：Compose 只会在绘制阶段读到它们时复用同一帧，
 * 不会每帧写 mutableState 触发重组。逻辑阶段 [stage] 才是 State。
 *
 * @param reduceMotion 来自 `LocalAnimationsEnabled`；true 时所有事件直接落
 *   终态（`snapTo`），不播动画，也不做第二套逻辑。
 */
/**
 * 把 rail 旁的动画宿主暴露给整棵子树的 CompositionLocal。
 *
 * 只有 `VersionInstallScreen` 这种「深处才知道发生了大事」的地方需要它
 * （选出 Fabric 版本 → 方块被丝带唤醒），一路传参数会把每个中间层都污染。
 */
val LocalMotionStage = staticCompositionLocalOf<MotionStage?> { null }
class MotionStage(
    private val scope: CoroutineScope,
    private val reduceMotion: Boolean = false
) {
    private val mutex = MutatorMutex()

    // L2 方块层
    val blockY = Animatable(0f)          // px，rail 拖拽时方块滞后跟随
    val blockScale = Animatable(1f)
    val blockRotate = Animatable(0f)     // 度
    val blockMaterial = Animatable(0f)   // 0=命令方块原色 1=材质唤醒
    val flash = Animatable(0f)           // 撞击白闪，0..1

    /**
     * L1 导航层：rail 被拖动的归一化位移，-1（往上拽）..1（往下拽）。
     *
     * 之所以是归一化而不是 px：方块的下坠距离要跟 rail 的「拉伸比例」走，
     * 不同屏宽/字号下观感一致。拖拽时直接 snapTo，松手后由 [settleRail] 接管。
     */
    val railPull = Animatable(0f)

    // L3 道具层（T3 起才驱动）
    val propProgress = Animatable(0f)    // 0=未进场 1=撞到方块
    val propRecoil = Animatable(0f)      // 撞后弹开 0→1→0
    val ribbonPhase = Animatable(0f)     // Fabric 丝带相位，0..540 度

    // L4 碎裂层
    val shatter = Animatable(0f)         // 0=完整 1=完全碎裂

    /** 当前正在飞的道具；None 时 overlay 不绘制任何东西。 */
    var prop by mutableStateOf(MotionProp.None)
        private set

    var stage by mutableStateOf(MotionStageState.Idle)
        private set

    /**
     * 这次动画作用在哪个 rail 图标上。null = 没有图标参与的动画（或已结束）。
     * rail 的五个图标都是 Canvas 自绘的，它们各自读这个值判断「是不是我」。
     */
    var glyph by mutableStateOf<RailGlyph?>(null)
        private set

    /**
     * 投递一个事件。并发投递时后到的会取消前一个（MutatorMutex 语义），
     * 这正是「任何时刻新输入都能接管」的实现方式。
     */
    fun play(intent: MotionIntent, glyph: RailGlyph? = null) {
        this.glyph = glyph
        scope.launch {
            try {
                mutex.mutate { dispatch(intent) }
            } finally {
                // 被新事件取消时也要清，否则那个图标会一直停在半动画状态。
                this@MotionStage.glyph = null
            }
        }
    }

    /**
     * rail 松手：先按松手速度把位移弹回 0（带惯性），再用 18ms 的短促抖动
     * 表现「方块落地」。速度单位是「归一化位移/秒」，由调用方从 VelocityTracker
     * 换算，避免这里依赖 px/秒与屏幕密度。
     */
    fun settleRail(velocity: Float) {
        scope.launch { mutex.mutate { settleRailImpl(velocity) } }
    }

    private suspend fun settleRailImpl(velocity: Float) {
        if (reduceMotion) {
            railPull.snapTo(0f)
            return
        }
        railPull.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = 0.78f,
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = 0.004f
            ),
            // 惯性来自松手速度：Animatable 的 initialVelocity 才是 spring 的入口，
            // 不是 spring() 的参数（后者只描述曲线刚度/阻尼）。
            initialVelocity = velocity
        )
        // 落地抖动：18ms 一次缩小 + 2° 偏转，然后一次过冲回位。
        coroutineScope {
            launch { blockScale.animateTo(0.96f, tween(60, easing = Motion.Ease)) }
            launch { blockRotate.animateTo(2f, tween(60, easing = Motion.Ease)) }
        }
        coroutineScope {
            launch { blockScale.animateTo(1f, spring(dampingRatio = 0.30f, stiffness = 1100f)) }
            launch { blockRotate.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 900f)) }
        }
    }

    private suspend fun dispatch(intent: MotionIntent) {
        when (intent) {
            MotionIntent.TabImpact -> impact()
            MotionIntent.DownloadImpact -> propHit(MotionProp.Arrow)
            MotionIntent.SettingsImpact -> propHit(MotionProp.Gear)
            MotionIntent.PersonalizeBrush -> brushSweep()
            MotionIntent.Shatter -> shatterBlock()
            MotionIntent.Awaken -> awaken()
        }
    }

    /**
     * T3 道具撞击：道具从右侧冲入 → 撞上方块 → 被弹开。
     *
     * 冲入用 `CubicBezierEasing(0.1, 0.9, 0.2, 1)`：前 60% 走完 90% 距离，
     * 剩下 40% 慢慢贴上去——力量感来自「快到撞上时反而减速」，而不是匀速。
     */
    private suspend fun propHit(kind: MotionProp) {
        stage = MotionStageState.Impacted
        prop = kind
        if (reduceMotion) {
            propProgress.snapTo(0f)
            propRecoil.snapTo(0f)
            prop = MotionProp.None
            stage = MotionStageState.Idle
            return
        }
        propProgress.snapTo(0f)
        propRecoil.snapTo(0f)
        propProgress.animateTo(1f, tween(130, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)))
        coroutineScope {
            launch { blockScale.animateTo(0.94f, tween(70, easing = Motion.Ease)) }
            launch { blockRotate.animateTo(-3f, tween(70, easing = Motion.Ease)) }
            launch {
                flash.animateTo(
                    targetValue = 0f,
                    animationSpec = keyframes {
                        durationMillis = 220
                        0f at 0 using LinearEasing
                        1f at 60 using LinearEasing
                        1f at 110 using LinearEasing
                        0f at 220 using LinearEasing
                    }
                )
            }
            launch { propRecoil.animateTo(1f, tween(90, easing = Motion.Ease)) }
        }
        coroutineScope {
            launch { blockScale.animateTo(1f, spring(dampingRatio = 0.30f, stiffness = 1100f)) }
            launch { blockRotate.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 900f)) }
            launch { propRecoil.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 700f)) }
        }
        propProgress.snapTo(0f)
        prop = MotionProp.None
        stage = MotionStageState.Idle
    }

    /**
     * 撞击：两段式。先 70ms 压到 0.94 / -3°，再用过冲 spring 弹回。
     *
     * 为什么用 spring 而不是 tween 回弹：过冲一次的「物理回弹」比缓动曲线更像
     * 被撞到，而且 spring 带初速度，能自然接上用户又快又慢的点击节奏。
     */
    private suspend fun impact() {
        stage = MotionStageState.Impacted
        if (reduceMotion) {
            blockScale.snapTo(1f)
            blockRotate.snapTo(0f)
            flash.snapTo(0f)
            stage = MotionStageState.Idle
            return
        }
        coroutineScope {
            launch { blockScale.animateTo(0.94f, tween(Motion.MicroMs, easing = Motion.Ease)) }
            launch { blockRotate.animateTo(-3f, tween(Motion.MicroMs, easing = Motion.Ease)) }
            launch {
                // 白闪用 keyframes：60ms 起亮到 110ms 保持，220ms 归零。
                // 只有「精确到某一帧」的视觉才值得用关键帧，其余走 tween/spring。
                flash.animateTo(
                    targetValue = 0f,
                    animationSpec = keyframes {
                        durationMillis = 220
                        0f at 0 using LinearEasing
                        1f at 60 using LinearEasing
                        1f at 110 using LinearEasing
                        0f at 220 using LinearEasing
                    }
                )
            }
        }
        coroutineScope {
            launch { blockScale.animateTo(1f, spring(dampingRatio = 0.30f, stiffness = 1100f)) }
            launch { blockRotate.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 900f)) }
        }
        stage = MotionStageState.Idle
    }

    private suspend fun shatterBlock() {
        stage = MotionStageState.Shattering
        if (reduceMotion) {
            shatter.snapTo(1f)
            stage = MotionStageState.Idle
            return
        }
        shatter.snapTo(0f)
        // 线性时间轴：粒子的物理量在绘制里按 t 算，避免多条 Animatable 同时跑。
        shatter.animateTo(1f, tween(Motion.LargeMs, easing = LinearEasing))
        shatter.snapTo(0f)
        stage = MotionStageState.Idle
    }

    /**
     * T6 丝带唤醒：三条彩带绕方块转一圈，材质跟着从丝带扫过的地方亮起来。
     *
     * 丝带和材质共用 340ms 线性时间轴——丝带转到哪，方块就亮到哪；
     * 结束时 `ribbonPhase` 归零，丝带消失但材质留在 1，表示「方块已被唤醒」。
     */
    private suspend fun awaken() {
        if (reduceMotion) {
            blockMaterial.snapTo(1f)
            ribbonPhase.snapTo(0f)
            return
        }
        ribbonPhase.snapTo(0f)
        coroutineScope {
            launch { ribbonPhase.animateTo(360f, tween(Motion.LargeMs, easing = LinearEasing)) }
            launch { blockMaterial.animateTo(1f, tween(Motion.LargeMs, easing = Motion.Ease)) }
        }
        ribbonPhase.snapTo(0f)
    }

    /**
     * T4 刷子粉刷：刷子从左扫到右，材质沿着刷子在方块上的位置逐段显现。
     *
     * 两个值必须同步推进（都是 260ms 线性）：`propProgress` 决定刷子在哪，
     * `blockMaterial` 决定方块被刷到哪儿——只要时间轴同源，视觉上就是「刷到哪亮到哪」，
     * 不需要在绘制里互相读。
     */
    private suspend fun brushSweep() {
        stage = MotionStageState.Impacted
        prop = MotionProp.Brush
        if (reduceMotion) {
            blockMaterial.snapTo(1f)
            propProgress.snapTo(0f)
            prop = MotionProp.None
            stage = MotionStageState.Idle
            return
        }
        blockMaterial.snapTo(0f)
        propProgress.snapTo(0f)
        coroutineScope {
            launch { propProgress.animateTo(1f, tween(Motion.MediumMs, easing = LinearEasing)) }
            launch { blockMaterial.animateTo(1f, tween(Motion.MediumMs, easing = LinearEasing)) }
        }
        // 刷完轻轻抬起一点：方块"活"过来，但不像撞击那样抖。
        if (!reduceMotion) {
            blockScale.animateTo(1.03f, tween(90, easing = Motion.Ease))
            blockScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 700f))
        }
        propProgress.snapTo(0f)
        prop = MotionProp.None
        stage = MotionStageState.Idle
    }
}

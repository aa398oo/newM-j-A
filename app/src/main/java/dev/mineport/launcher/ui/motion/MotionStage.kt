package dev.mineport.launcher.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    /** 子目录进入：方块碎裂（T5 才接粒子，T1 只有缩小淡出）。 */
    Shatter,

    /** Fabric 丝带唤醒材质（T6 接丝带，T1 只有颜色过渡）。 */
    Awaken
}

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

    // L3 道具层（T3 起才驱动）
    val propProgress = Animatable(0f)    // 0=未进场 1=撞到方块
    val propRecoil = Animatable(0f)      // 撞后弹开 0→1→0
    val ribbonPhase = Animatable(0f)     // Fabric 丝带相位，0..540 度

    // L4 碎裂层
    val shatter = Animatable(0f)         // 0=完整 1=完全碎裂

    var stage by mutableStateOf(MotionStageState.Idle)
        private set

    /**
     * 投递一个事件。并发投递时后到的会取消前一个（MutatorMutex 语义），
     * 这正是「任何时刻新输入都能接管」的实现方式。
     */
    fun play(intent: MotionIntent) {
        scope.launch { mutex.mutate { dispatch(intent) } }
    }

    private suspend fun dispatch(intent: MotionIntent) {
        when (intent) {
            MotionIntent.TabImpact -> impact()
            MotionIntent.Shatter -> shatterBlock()
            MotionIntent.Awaken -> awaken()
        }
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

    private suspend fun awaken() {
        if (reduceMotion) {
            blockMaterial.snapTo(1f)
            return
        }
        blockMaterial.animateTo(1f, tween(Motion.MediumMs, easing = Motion.Ease))
    }
}

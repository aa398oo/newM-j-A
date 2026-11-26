package dev.mineport.launcher.render

import dev.mineport.launcher.device.DeviceProfile
import dev.mineport.launcher.device.SocVendor

enum class ShaderOutcome {
    /** 走 Zink（GL→Vulkan）；骁龙加载 Turnip，天玑走系统 Vulkan。 */
    ZINK,

    /** 光影不可用，回退 GL4ES。 */
    GL4ES,

    /** 连 GL4ES 都不该开（极低端/异常设备）。 */
    NONE
}

data class ShaderPlan(
    val outcome: ShaderOutcome,
    val reason: String,
    /** 给用户看的大致预期，不当作验收数字（PRODUCT-CORE 四.6：不承诺固定帧数）。 */
    val expectation: String
)

/**
 * 光影策略：探测 → 试跑 → 自动回退（PRODUCT-CORE 二.5）。
 *
 * 这里只做「能不能试」的判定与解释；真正试跑由启动器在 GL3.3 上下文里加载探测 shader，
 * 失败后把结果写进驱动黑名单，下次直接回退。
 */
object ShaderPolicy {

    /** 已知在 Zink 上不稳的驱动/GPU 片段（示例池，靠实测扩充）。 */
    private val blocklist = listOf("mali-g57", "mali-g610", "mali-g715")

    fun plan(profile: DeviceProfile, wantShaders: Boolean): ShaderPlan {
        if (!wantShaders) {
            return ShaderPlan(ShaderOutcome.GL4ES, "未开启光影：GL4ES 就是正确选择。", "无光影，按 100fps 基线对齐 FCL")
        }
        if (!profile.vulkanSupported) {
            return ShaderPlan(
                ShaderOutcome.GL4ES,
                "设备没有 Vulkan，光影无法开启，自动回退 GL4ES。",
                "无光影，按基线帧数运行"
            )
        }
        if (profile.isLowRamDevice) {
            return ShaderPlan(
                ShaderOutcome.GL4ES,
                "低内存设备，开光影会先 OOM 而不是先卡，回退 GL4ES。",
                "无光影"
            )
        }

        val hardware = (profile.socModel + " " + profile.manufacturer).lowercase()
        if (blocklist.any { hardware.contains(it) }) {
            return ShaderPlan(
                ShaderOutcome.GL4ES,
                "该 GPU 在已知黑名单里，Zink 试跑风险高，直接回退。",
                "无光影"
            )
        }

        return when (profile.socVendor) {
            SocVendor.QUALCOMM -> ShaderPlan(
                ShaderOutcome.ZINK,
                "骁龙 + Vulkan：加载 Turnip 后走 Zink，最轻光影 720p 目标 25–30fps。",
                "720p 最轻光影约 25–30fps；失败自动回退 GL4ES 45fps+"
            )
            SocVendor.MEDIATEK, SocVendor.SAMSUNG -> ShaderPlan(
                ShaderOutcome.ZINK,
                "Mali/Immortalis 系：不依赖 Turnip，用系统 Vulkan 跑 Zink；驱动不稳时回退。",
                "720p 最轻光影约 20–25fps；不稳则回退 GL4ES"
            )
            else -> ShaderPlan(
                ShaderOutcome.GL4ES,
                "该平台 Vulkan 驱动不确定性高，先保证能跑，光影留待实测。",
                "无光影"
            )
        }
    }
}

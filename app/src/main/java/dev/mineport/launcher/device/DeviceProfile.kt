package dev.mineport.launcher.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

enum class SocVendor { QUALCOMM, MEDIATEK, SAMSUNG, HUAWEI, UNISOC, UNKNOWN }

/**
 * 设备画像。首页的「推荐渲染后端」与默认内存档位都由这里推导，
 * 全部来自公开的 Android API，不做任何猜测性硬编码。
 */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val socVendor: SocVendor,
    val socModel: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abi: String,
    val totalRamMB: Int,
    val isLowRamDevice: Boolean,
    val glEsVersion: String,
    val glEsVersionCode: Int,
    val vulkanSupported: Boolean,
    val vulkanVersion: String?,
    val coreCount: Int
) {
    /** 建议的 JVM 堆：低内存机保守，8GB 以上才给 2GB+。 */
    val recommendedMemoryMB: Int
        get() = when {
            totalRamMB >= 12288 -> 3072
            totalRamMB >= 8192 -> 2048
            totalRamMB >= 6144 -> 1536
            totalRamMB >= 4096 -> 1024
            else -> 768
        }

    val recommendedBackend: RenderBackendRecommendation
        get() {
            // 天玑/Exynos/麒麟：Mali 或闭源 Vulkan，Zink 不可靠 → 固定 GL4ES。
            if (socVendor != SocVendor.QUALCOMM) {
                return RenderBackendRecommendation(
                    backend = "gl4es",
                    reason = "非骁龙平台（Mali 系）的 Vulkan 驱动封闭，Zink 无法稳定工作，使用 GL4ES 翻译层。"
                )
            }
            if (!vulkanSupported) {
                return RenderBackendRecommendation(
                    backend = "gl4es",
                    reason = "未检测到 Vulkan 支持，回退 GL4ES。"
                )
            }
            if (!isLowRamDevice && glEsVersionCode >= 0x00030000) {
                return RenderBackendRecommendation(
                    backend = "zink",
                    reason = "骁龙 + Vulkan + GLES 3.x，可尝试 Zink + Turnip 直通；若启动失败会自动回退 GL4ES。"
                )
            }
            return RenderBackendRecommendation(
                backend = "gl4es",
                reason = "骁龙但 GPU/内存偏弱，GL4ES 更稳。"
            )
        }

    companion object {
        fun detect(context: Context): DeviceProfile {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val pm = context.packageManager

            val glEsCode = am.deviceConfigurationInfo.reqGlEsVersion
            val vulkanSupported = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
            val vulkanVersion: String? = null

            return DeviceProfile(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                socVendor = detectVendor(),
                socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE,
                androidRelease = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT,
                abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                totalRamMB = (memInfo.totalMem / (1024 * 1024)).toInt(),
                isLowRamDevice = am.isLowRamDevice,
                glEsVersion = formatGlEs(glEsCode),
                glEsVersionCode = glEsCode,
                vulkanSupported = vulkanSupported,
                vulkanVersion = vulkanVersion,
                coreCount = Runtime.getRuntime().availableProcessors()
            )
        }

        private fun detectVendor(): SocVendor {
            val raw = buildString {
                if (Build.VERSION.SDK_INT >= 31) append(Build.SOC_MANUFACTURER)
                append(' ').append(Build.HARDWARE)
                append(' ').append(Build.BOARD)
                append(' ').append(Build.PRODUCT)
            }.lowercase()

            return when {
                raw.contains("qcom") || raw.contains("qualcomm") || raw.contains("adreno") -> SocVendor.QUALCOMM
                raw.contains("mediatek") || raw.contains("mt6") || raw.contains("mt8") -> SocVendor.MEDIATEK
                raw.contains("exynos") || raw.contains("samsung") -> SocVendor.SAMSUNG
                raw.contains("kirin") || raw.contains("hi3") || raw.contains("huawei") -> SocVendor.HUAWEI
                raw.contains("unisoc") || raw.contains("spreadtrum") -> SocVendor.UNISOC
                else -> SocVendor.UNKNOWN
            }
        }

        private fun formatGlEs(code: Int): String {
            if (code == 0) return "未上报"
            val major = (code shr 16) and 0xFFFF
            val minor = code and 0xFFFF
            return "GLES " + major + "." + minor
        }
    }
}

data class RenderBackendRecommendation(val backend: String, val reason: String)
package dev.mineport.launcher.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/** 渲染后端。Zink 需要 Turnip（仅部分骁龙设备可加载），默认 GL4ES。 */
@Serializable
enum class RenderBackend {
    @SerialName("gl4es") GL4ES,
    @SerialName("zink") ZINK,
    @SerialName("auto") AUTO;

    val displayName: String
        get() = when (this) {
            GL4ES -> "GL4ES"
            ZINK -> "Zink + Turnip（实验）"
            AUTO -> "自动"
        }
}

@Serializable
data class GameInstance(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val minecraftVersion: String,
    val loader: Loader = Loader.VANILLA,
    val loaderVersion: String? = null,
    val javaVersion: Int = 17,
    val memoryMB: Int = 2048,
    val renderBackend: RenderBackend = RenderBackend.AUTO,
    /** 开启后按 ShaderPolicy 试 Zink，失败回退 GL4ES；光影包本身仍需 Iris/OptiFine 模组。 */
    val wantShaders: Boolean = false,
    /**
     * 实验开关：用 caciocavallo 提供 AWT 窗口层（FCL/Pojav 同路径）。
     * 关闭时走「headless + GLSurfaceView 出画面」的现有链路；开启后 JVM 侧多一个
     * AWT toolkit，原版 GLFW 才有机会拿到 Android 窗口。
     */
    val useCacio: Boolean = false,
    /** 动态分辨率上限，0 表示跟随设备。 */
    val resolutionWidth: Int = 0,
    val resolutionHeight: Int = 0,
    val maxFps: Int = 120,
    val playerName: String = "Steve",
    val gameDirName: String = "instance-" + UUID.randomUUID().toString().take(8),
    val lastPlayedEpochMs: Long = 0L
) {
    val subtitle: String
        get() = buildString {
            append(minecraftVersion)
            if (loader != Loader.VANILLA) {
                append(" · ")
                append(loader.displayName)
                loaderVersion?.let { append(" ").append(it) }
            }
        }
}

@Serializable
enum class Loader {
    @SerialName("vanilla") VANILLA,
    @SerialName("fabric") FABRIC,
    @SerialName("forge") FORGE,
    @SerialName("neoforge") NEOFORGE,
    @SerialName("quilt") QUILT;

    val displayName: String
        get() = when (this) {
            VANILLA -> "原版"
            FABRIC -> "Fabric"
            FORGE -> "Forge"
            NEOFORGE -> "NeoForge"
            QUILT -> "Quilt"
        }
}

/**
 * 按 Minecraft 版本推断所需 Java 大版本。
 * 1.20.5 起官方把最低要求提到 Java 21；更早的版本用 Java 17。
 * 拿不到版本号时按 17 处理（内置运行时就是 17）。
 */
fun javaMajorFor(minecraftVersion: String): Int {
    val parts = minecraftVersion.trim().removePrefix("v").split('.', '-')
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: return 17
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return if (minor >= 21 || (minor == 20 && patch >= 5)) 21 else 17
}

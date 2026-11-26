package dev.mineport.launcher.install

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * version JSON 的最小子集（1.20.x 及旧版本通用）。
 * 只解析安装/启动真正需要的字段，其余忽略——避免跟着 Mojang 加字段就崩。
 */
@Serializable
data class VersionJson(
    val id: String = "",
    val type: String = "release",
    val mainClass: String = "",
    /** Fabric/Forge 的 profile 用 inheritsFrom 指向原版；启动器按链合并。 */
    val inheritsFrom: String? = null,
    /** 合并后的加载器 profile 用 jar 指回原版 jar 所在版本 id。 */
    val jar: String? = null,
    val assetIndex: AssetIndex? = null,
    val downloads: VersionDownloads = VersionDownloads(),
    val libraries: List<Library> = emptyList(),
    /** 1.13+ 的参数结构里 value 可能是字符串或数组，这里原样保留、不解析。 */
    val arguments: JsonElement? = null,
    val minecraftArguments: String = ""
)

@Serializable
data class AssetIndex(
    val id: String = "",
    val url: String = "",
    val sha1: String = "",
    val size: Long = 0,
    val totalSize: Long = 0
)

@Serializable
data class VersionDownloads(
    val client: Artifact? = null,
    val server: Artifact? = null
)

@Serializable
data class Artifact(
    val path: String = "",
    val url: String = "",
    val sha1: String = "",
    val size: Long = 0
)

@Serializable
data class Library(
    val name: String = "",
    val downloads: LibraryDownloads = LibraryDownloads(),
    val natives: Map<String, String> = emptyMap(),
    val rules: List<Rule> = emptyList()
)

@Serializable
data class LibraryDownloads(
    val artifact: Artifact? = null,
    val classifiers: Map<String, Artifact> = emptyMap()
)

@Serializable
data class Rule(
    val action: String = "allow",
    val os: OsRule? = null
)

@Serializable
data class OsRule(
    val name: String = "",
    val arch: String = ""
)

/** Android 按 linux 规则处理。 */
object Rules {
    fun allows(rules: List<Rule>, osName: String = "linux", arch: String = "arm64"): Boolean {
        if (rules.isEmpty()) return true
        var allowed = false
        for (rule in rules) {
            val matches = rule.os == null ||
                (rule.os.name.ifBlank { osName } == osName &&
                    (rule.os.arch.isBlank() || rule.os.arch == arch))
            if (!matches) continue
            allowed = rule.action != "disallow"
        }
        return allowed
    }
}

/** Maven 坐标 → 本地路径，例如 org.lwjgl:lwjgl:3.3.3 → org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar */
fun mavenPath(name: String, classifier: String? = null): String? {
    val parts = name.split(":")
    if (parts.size < 3) return null
    val group = parts[0].replace('.', '/')
    val artifact = parts[1]
    val version = parts[2]
    val suffix = if (classifier.isNullOrBlank()) "" else "-$classifier"
    return "$group/$artifact/$version/$artifact-$version$suffix.jar"
}

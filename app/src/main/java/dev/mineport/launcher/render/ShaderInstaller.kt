package dev.mineport.launcher.render

import dev.mineport.launcher.mod.ModInstaller
import dev.mineport.launcher.mod.ModrinthClient
import java.io.File

/**
 * 光影加载器安装：Iris（Fabric）走 Modrinth 的 `iris` 项目。
 *
 * 光影不是启动器自己能渲染的东西——它需要 Iris（Fabric）或 OptiFine 这类客户端模组
 * 接管渲染管线。之前「光影（Zink 试跑）」开关只决定用 GL4ES 还是 Zink 作后端，
 * 没有加载器时包里放多少 shaderpack 都不会生效。
 *
 * 这里只负责「把 Iris 装进 mods 目录」；光影包本体（*.zip）由用户放进
 * `instances/<dir>/shaderpacks/`，Iris 启动后会自己列出来。
 */
object ShaderInstaller {

    /** Modrinth 上 Iris Shaders 的 slug。 */
    private const val IRIS_SLUG = "iris"

    /** 把 Iris 装进实例的 mods 目录；返回写入的文件数（含 Fabric API 等 required 依赖）。 */
    suspend fun installIris(
        gameVersion: String,
        modsDir: File,
        onFile: (label: String, done: Int, total: Int) -> Unit = { _, _, _ -> }
    ): Result<Int> {
        val hits = ModrinthClient.search("iris shaders", gameVersion, "fabric", limit = 10)
            .getOrElse { return Result.failure(it) }
        val iris = hits.firstOrNull { it.slug == IRIS_SLUG }
            ?: hits.firstOrNull { it.title.contains("Iris", ignoreCase = true) }
            ?: return Result.failure(
                IllegalStateException("没有找到适用于 $gameVersion 的 Iris（需要 Fabric 支持）")
            )
        return ModInstaller.install(iris.project_id, gameVersion, "fabric", modsDir, onFile)
            .onSuccess {
                // Iris 不会自己建 shaderpacks；先建好，用户直接把光影包 zip 拖进去就行。
                modsDir.parentFile?.let { dir -> File(dir, "shaderpacks").mkdirs() }
            }
    }

    /** 实例的 mods 目录。 */
    fun modsDir(filesDir: File, gameDirName: String): File =
        File(filesDir, "instances/$gameDirName/mods")

    /** 实例的 shaderpacks 目录（Iris 从这里读光影包）。 */
    fun shaderPacksDir(filesDir: File, gameDirName: String): File =
        File(filesDir, "instances/$gameDirName/shaderpacks")

    /** 已经装过 Iris 没有（粗判：mods 里有 iris-*.jar）。 */
    fun isIrisInstalled(modsDir: File): Boolean =
        modsDir.listFiles()?.any { it.isFile && it.name.startsWith("iris-", ignoreCase = true) } == true
}

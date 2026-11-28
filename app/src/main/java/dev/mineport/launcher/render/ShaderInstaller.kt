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

    /** 把 Iris 装进版本的 mods 目录；返回写入的文件数（含 Fabric API 等 required 依赖）。 */
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

    /** 版本的 mods 目录。 */
    fun modsDir(filesDir: File, gameDirName: String): File =
        File(filesDir, "instances/$gameDirName/mods")

    /** 版本的 shaderpacks 目录（Iris 从这里读光影包）。 */
    fun shaderPacksDir(filesDir: File, gameDirName: String): File =
        File(filesDir, "instances/$gameDirName/shaderpacks")

    /** 已经装过 Iris 没有（粗判：mods 里有 iris-*.jar）。 */
    fun isIrisInstalled(modsDir: File): Boolean =
        modsDir.listFiles()?.any { it.isFile && it.name.startsWith("iris-", ignoreCase = true) } == true

    /** 推荐光影包（Modrinth slug）。挑的是「一眼能看出差别、显存占用可接受」的三个。 */
    data class KnownPack(val name: String, val slug: String, val note: String)

    val recommended = listOf(
        KnownPack("BSL Shaders", "bsl-shaders", "风格化偏写实，帧率友好"),
        KnownPack("Complementary Reimagined", "complementary-reimagined", "综合平衡，效果最讨喜"),
        KnownPack("Sildur's Vibrant", "sildurs-vibrant-shaders", "色彩鲜艳，老牌选项")
    )

    /**
     * 下载一个光影包到 `shaderpacks/`。
     *
     * 光影包不绑定加载器、也不进 mods 目录——它只是 shaderpacks 里的一个 zip，
     * 所以这里只取项目的最新版本，不做 gameVersion/loader 过滤。
     */
    suspend fun installPack(
        slug: String,
        shaderPacksDir: File,
        onStage: (String) -> Unit = {}
    ): Result<String> {
        onStage("查询 " + slug + " …")
        val versions = ModrinthClient.allVersions(slug)
        val version = versions.firstOrNull()
            ?: return Result.failure(IllegalStateException("没有找到 " + slug + " 的可用版本"))
        val file = version.files.firstOrNull { it.primary } ?: version.files.firstOrNull()
        if (file == null || file.url.isBlank()) {
            return Result.failure(IllegalStateException(slug + " 的版本里没有可下载文件"))
        }
        shaderPacksDir.mkdirs()
        onStage("下载 " + file.filename + " …")
        val outcome = dev.mineport.launcher.download.FileDownloader.download(
            urls = listOf(file.url),
            target = File(shaderPacksDir, file.filename),
            sha1 = file.hashes.sha1.ifBlank { null }
        )
        return outcome.fold(
            { Result.success(file.filename) },
            { Result.failure(it) }
        )
    }

    /** 已装的光影包文件名。 */
    fun installedPacks(shaderPacksDir: File): List<String> =
        shaderPacksDir.listFiles()?.filter { it.isFile && it.name.endsWith(".zip", true) }
            ?.map { it.name }?.sorted().orEmpty()
}

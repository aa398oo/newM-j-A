package dev.mineport.launcher.mod

import dev.mineport.launcher.download.FileDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把「模组 / 光影 / 材质 / 地图」装进某个版本目录。
 *
 * 四类内容落点不同，这是下载页最容易做错的地方：
 * - 模组、地图（worldgen 模组）→ `mods/`，要匹配加载器 + 游戏版本，走依赖解析
 * - 光影 → `shaderpacks/`，纯 zip，不绑加载器；但要有 Iris 才会生效
 * - 材质 → `resourcepacks/`，纯 zip，不绑加载器
 */
object ContentInstaller {

    fun instanceDir(filesDir: File, gameDirName: String): File =
        File(filesDir, "instances/$gameDirName")

    fun targetDir(instanceDir: File, type: ProjectType): File = when (type) {
        // 地图在 Modrinth 上就是「地形生成模组」，所以照样进 mods/
        ProjectType.MOD, ProjectType.MAP -> File(instanceDir, "mods")
        ProjectType.SHADER -> File(instanceDir, "shaderpacks")
        ProjectType.RESOURCE_PACK -> File(instanceDir, "resourcepacks")
    }

    /**
     * 安装一个项目。
     *
     * @param onStage 进度回调，会从 IO 线程触发，调用方用 StateFlow/主线程派发接住。
     */
    suspend fun install(
        projectId: String,
        type: ProjectType,
        gameVersion: String,
        loader: String,
        instanceDir: File,
        onStage: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        when (type) {
            ProjectType.MOD, ProjectType.MAP -> {
                onStage("解析依赖…")
                ModInstaller.install(
                    projectId = projectId,
                    gameVersion = gameVersion,
                    loader = loader,
                    modsDir = targetDir(instanceDir, type)
                ) { label, done, total ->
                    onStage("$done/$total  $label")
                }.map { count ->
                    if (count <= 0) "没有写入任何文件" else "已安装 $count 个文件（含前置）"
                }
            }

            ProjectType.SHADER, ProjectType.RESOURCE_PACK ->
                downloadLatest(projectId, targetDir(instanceDir, type), onStage)
        }
    }

    private suspend fun downloadLatest(
        projectId: String,
        dir: File,
        onStage: (String) -> Unit
    ): Result<String> {
        onStage("查询可用版本…")
        val version = ModrinthClient.allVersions(projectId).firstOrNull()
            ?: return Result.failure(IllegalStateException("没有找到可下载的版本"))
        val file = version.files.firstOrNull { it.primary } ?: version.files.firstOrNull()
        if (file == null || file.url.isBlank()) {
            return Result.failure(IllegalStateException("版本里没有可下载文件"))
        }
        dir.mkdirs()
        onStage("下载 " + file.filename + " …")
        return FileDownloader.download(
            urls = listOf(file.url),
            target = File(dir, file.filename),
            sha1 = file.hashes.sha1.ifBlank { null }
        ).map { file.filename }
    }

    /**
     * 粗判已装：目标目录里有没有「slug 同源」的文件。
     *
     * 不能直接 startsWith(slug)：光影包文件名是 `ComplementaryReimagined_r5.10.zip`，
     * slug 是 `complementary-reimagined`。两边都去掉非字母数字再比。
     */
    fun isInstalled(instanceDir: File, type: ProjectType, slug: String): Boolean {
        val key = slugKey(slug)
        if (key.isEmpty()) return false
        val dir = targetDir(instanceDir, type)
        return dir.listFiles()?.any { it.isFile && slugKey(it.name).startsWith(key) } == true
    }

    /**
     * 找出这个项目落在目标目录里的文件（给「卸载」用）。
     *
     * 跳过 `.part`：那是没下完的临时文件，删它没有意义。
     */
    fun installedFile(instanceDir: File, type: ProjectType, slug: String): File? {
        val key = slugKey(slug)
        if (key.isEmpty()) return null
        return targetDir(instanceDir, type).listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.firstOrNull { slugKey(it.name).startsWith(key) }
    }

    /**
     * 删掉这个项目的文件。
     *
     * 只删 slug 命中的那一个：模组走 [ModInstaller] 装时会连带前置（Fabric API 之类），
     * 前置是给别的模组共用的，跟着删会连累别的模组起不来。
     */
    fun uninstall(instanceDir: File, type: ProjectType, slug: String): Boolean {
        val file = installedFile(instanceDir, type, slug) ?: return false
        return file.delete()
    }

    private fun slugKey(raw: String): String =
        raw.lowercase().filter { it.isLetterOrDigit() }
}

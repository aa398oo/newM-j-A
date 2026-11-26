package dev.mineport.launcher.install

import dev.mineport.launcher.download.DownloadQueue
import dev.mineport.launcher.download.DownloadTask
import dev.mineport.launcher.download.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AssetIndexFile(
    val objects: Map<String, AssetObject> = emptyMap()
)

@Serializable
data class AssetObject(
    val hash: String = "",
    val size: Long = 0
)

/**
 * assets 资源对象下载（安装的第二段）。
 *
 * 资源按 sha1 分片存放：assets/objects/<前两位>/<完整 hash>。
 * 两个源：BMCLAPI 镜像优先，官方 resources.download.minecraft.net 兜底。
 * 已存在且大小一致的文件直接跳过——避免每次重装都重下几百 MB。
 */
object AssetInstaller {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun install(
        indexFile: File,
        assetsDir: File,
        onStage: (String) -> Unit = {},
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!indexFile.isFile) {
                return@withContext Result.failure(IllegalStateException("缺少 asset index：${indexFile.absolutePath}"))
            }
            val index = json.decodeFromString<AssetIndexFile>(indexFile.readText())
            val objectsDir = File(assetsDir, "objects").apply { mkdirs() }

            val tasks = mutableListOf<DownloadTask>()
            for ((name, obj) in index.objects) {
                if (obj.hash.length < 2) continue
                val sub = obj.hash.substring(0, 2)
                val target = File(objectsDir, "$sub/${obj.hash}")
                if (target.isFile && target.length() == obj.size) continue
                tasks += DownloadTask(
                    label = name,
                    urls = listOf(
                        "https://bmclapi2.bangbang93.com/assets/$sub/${obj.hash}",
                        "https://resources.download.minecraft.net/$sub/${obj.hash}"
                    ),
                    target = target,
                    sha1 = obj.hash
                )
            }

            onStage("下载 " + tasks.size + " 个资源文件")
            val outcomes = DownloadQueue(8).run(tasks, onProgress)
            val failed = outcomes.count { !it.ok }
            if (failed > 0) {
                return@withContext Result.failure(
                    IllegalStateException("有 $failed 个资源文件下载失败，可重试")
                )
            }
            Result.success(tasks.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

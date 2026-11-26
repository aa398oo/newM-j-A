package dev.mineport.launcher.install

import dev.mineport.launcher.download.DownloadQueue
import dev.mineport.launcher.download.DownloadTask
import dev.mineport.launcher.download.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

data class InstallReport(
    val versionDir: File,
    val totalFiles: Int,
    val failed: List<Pair<String, String>>,
    val assetFiles: Int = 0
) {
    val ok: Boolean get() = failed.isEmpty()
}

/**
 * 版本安装器第一阶段：客户端 + libraries + asset index + assets 资源。
 *
 * 目录布局保持与桌面 .minecraft 一致，FCLCore 可以直接接管：
 *   filesDir/versions/<id>/<id>.json
 *   filesDir/versions/<id>/<id>.jar
 *   filesDir/versions/<id>/asset_index.json
 *   filesDir/libraries/<maven path>
 *   filesDir/assets/objects/<xx>/<hash>
 */
object VersionInstaller {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun install(
        versionId: String,
        versionUrl: String,
        rootDir: File,
        assetsDir: File,
        onStage: (String) -> Unit = {},
        onFileProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): Result<InstallReport> = withContext(Dispatchers.IO) {
        try {
            onStage("拉取 $versionId 的版本 JSON")
            val rawJson = Http.get(versionUrl)
            val version = json.decodeFromString<VersionJson>(rawJson)

            val versionDir = File(rootDir, "versions/$versionId").apply { mkdirs() }
            val librariesDir = File(rootDir, "libraries").apply { mkdirs() }
            File(versionDir, "$versionId.json").writeText(rawJson)

            val tasks = mutableListOf<DownloadTask>()

            version.downloads.client?.let { client ->
                if (client.url.isNotBlank()) {
                    tasks += DownloadTask(
                        label = "$versionId.jar",
                        urls = listOf(client.url),
                        target = File(versionDir, "$versionId.jar"),
                        sha1 = client.sha1.ifBlank { null }
                    )
                }
            }

            for (library in version.libraries) {
                if (!Rules.allows(library.rules)) continue

                library.downloads.artifact?.let { artifact ->
                    if (artifact.url.isNotBlank()) {
                        val path = artifact.path.takeIf { it.isNotBlank() }
                            ?: mavenPath(library.name)
                        if (path != null) {
                            tasks += DownloadTask(
                                label = path.substringAfterLast('/'),
                                urls = listOf(artifact.url),
                                target = File(librariesDir, path),
                                sha1 = artifact.sha1.ifBlank { null }
                            )
                        }
                    }
                }

                val nativesKey = library.natives["linux"] ?: continue
                val classifier = library.downloads.classifiers[nativesKey] ?: continue
                if (classifier.url.isNotBlank()) {
                    val path = classifier.path.takeIf { it.isNotBlank() }
                        ?: mavenPath(library.name, nativesKey)
                    if (path != null) {
                        tasks += DownloadTask(
                            label = path.substringAfterLast('/'),
                            urls = listOf(classifier.url),
                            target = File(librariesDir, path),
                            sha1 = classifier.sha1.ifBlank { null }
                        )
                    }
                }
            }

            version.assetIndex?.let { index ->
                if (index.url.isNotBlank()) {
                    tasks += DownloadTask(
                        label = "asset_index.json",
                        urls = listOf(index.url),
                        target = File(versionDir, "asset_index.json"),
                        sha1 = index.sha1.ifBlank { null }
                    )
                }
            }

            onStage("下载 ${tasks.size} 个文件")
            // 12 路并发：assets 是几千个几十 KB 的小文件，瓶颈在连接建立而不是带宽。
            val outcomes = DownloadQueue(12).run(tasks, onFileProgress)
            val failed = outcomes.filter { !it.ok }.map { it.task.label to (it.error ?: "未知错误") }.toMutableList()

            val assetIndexFile = File(versionDir, "asset_index.json")
            var assetFiles = 0
            if (assetIndexFile.isFile) {
                val assetResult = AssetInstaller.install(assetIndexFile, assetsDir, onStage, onFileProgress)
                assetResult.onSuccess { assetFiles = it }
                assetResult.exceptionOrNull()?.let { failed += "assets" to (it.message ?: "下载失败") }
            }

            Result.success(InstallReport(versionDir, tasks.size, failed, assetFiles))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

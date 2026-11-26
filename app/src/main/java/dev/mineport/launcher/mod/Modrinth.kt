package dev.mineport.launcher.mod

import dev.mineport.launcher.download.FileDownloader
import dev.mineport.launcher.download.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLEncoder

@Serializable
data class ModrinthHit(
    val project_id: String,
    val slug: String = "",
    val title: String = "",
    val description: String = "",
    val downloads: Long = 0,
    val icon_url: String? = null
)

@Serializable
data class ModrinthSearch(
    val hits: List<ModrinthHit> = emptyList(),
    val total_hits: Int = 0
)

@Serializable
data class ModrinthVersion(
    val id: String = "",
    val project_id: String = "",
    val version_number: String = "",
    val game_versions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    val files: List<ModrinthFile> = emptyList(),
    val dependencies: List<ModrinthDependency> = emptyList()
)

@Serializable
data class ModrinthFile(
    val url: String = "",
    val filename: String = "",
    val primary: Boolean = false,
    val hashes: ModrinthHashes = ModrinthHashes()
)

@Serializable
data class ModrinthHashes(
    val sha1: String = ""
)

@Serializable
data class ModrinthDependency(
    val version_id: String? = null,
    val project_id: String? = null,
    val dependency_type: String = ""
)

/**
 * Modrinth API v2 的最小客户端（免 key、公开接口）。
 *
 * 只做三件事：搜索、按版本/加载器取可用版本、解析 required 依赖。
 * 依赖解析是「模组全自动」的第一步；崩溃归因与冲突检测在安装器之后。
 */
object ModrinthClient {

    private val json = Json { ignoreUnknownKeys = true }
    private const val BASE = "https://api.modrinth.com/v2"

    suspend fun search(
        query: String,
        gameVersion: String,
        loader: String,
        limit: Int = 20
    ): Result<List<ModrinthHit>> = withContext(Dispatchers.IO) {
        try {
            val facets = """[["categories:$loader"],["versions:$gameVersion"]]"""
            val url = "$BASE/search?query=" + URLEncoder.encode(query, "UTF-8") +
                "&limit=$limit&facets=" + URLEncoder.encode(facets, "UTF-8")
            val body = Http.get(url)
            Result.success(json.decodeFromString<ModrinthSearch>(body).hits)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun versions(
        projectId: String,
        gameVersion: String,
        loader: String
    ): List<ModrinthVersion> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE/project/$projectId/version?game_versions=" +
                URLEncoder.encode("[\"$gameVersion\"]", "UTF-8") +
                "&loaders=" + URLEncoder.encode("[\"$loader\"]", "UTF-8")
            json.decodeFromString<List<ModrinthVersion>>(Http.get(url))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun versionById(versionId: String): ModrinthVersion? = withContext(Dispatchers.IO) {
        try {
            json.decodeFromString<ModrinthVersion>(Http.get("$BASE/version/$versionId"))
        } catch (e: Exception) {
            null
        }
    }

    data class ResolvedMod(val version: ModrinthVersion, val requiredBy: String)

    /**
     * 递归解析 required 依赖（DOWNLOAD/模组策略：自动装前置、不装 optional）。
     * 用 BFS，按 project_id 去重，避免循环依赖。
     */
    suspend fun resolveDependencies(
        rootProjectId: String,
        gameVersion: String,
        loader: String
    ): List<ResolvedMod> = withContext(Dispatchers.IO) {
        val result = mutableListOf<ResolvedMod>()
        val seenProjects = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, String>>() // projectId to "requiredBy"
        queue.add(rootProjectId to "")

        while (queue.isNotEmpty()) {
            val (projectId, requiredBy) = queue.removeFirst()
            if (!seenProjects.add(projectId)) continue

            val version = versions(projectId, gameVersion, loader).firstOrNull() ?: continue
            result += ResolvedMod(version, requiredBy)

            for (dependency in version.dependencies) {
                if (dependency.dependency_type != "required") continue
                val depProject = dependency.project_id
                when {
                    depProject != null -> queue.add(depProject to version.version_number)
                    dependency.version_id != null -> {
                        val dep = versionById(dependency.version_id) ?: continue
                        if (seenProjects.add(dep.project_id)) {
                            result += ResolvedMod(dep, version.version_number)
                        }
                    }
                }
            }
        }
        result
    }
}

/**
 * 模组安装器：把解析出的版本逐个下载到 mods 目录。
 * 返回实际写入的文件数；失败文件单独回报（不中断其他 mod）。
 */
object ModInstaller {

    suspend fun install(
        projectId: String,
        gameVersion: String,
        loader: String,
        modsDir: File,
        onFile: (label: String, done: Int, total: Int) -> Unit = { _, _, _ -> }
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val resolved = ModrinthClient.resolveDependencies(projectId, gameVersion, loader)
            if (resolved.isEmpty()) return@withContext Result.failure(IllegalStateException("没有找到匹配版本/加载器的模组"))

            modsDir.mkdirs()
            var written = 0
            val total = resolved.size
            for ((index, mod) in resolved.withIndex()) {
                val file = mod.version.files.firstOrNull { it.primary } ?: mod.version.files.firstOrNull()
                if (file == null || file.url.isBlank()) {
                    onFile(mod.version.project_id, index + 1, total)
                    continue
                }
                val target = File(modsDir, file.filename)
                val outcome = FileDownloader.download(
                    urls = listOf(file.url),
                    target = target,
                    sha1 = file.hashes.sha1.ifBlank { null }
                )
                if (outcome.isSuccess) written++
                onFile(file.filename, index + 1, total)
            }
            Result.success(written)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

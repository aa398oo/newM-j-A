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
 * 下载页的「附属内容」类目。
 *
 * Modrinth 没有「地图」这个 project_type（facets 查 project_type:world 恒为 0），
 * 最接近的是 mod + categories:worldgen（地形/世界生成），所以地图单列一类但走 mod 的装载路径。
 */
enum class ProjectType(val facet: String, val extraCategory: String? = null) {
    MOD("mod"),
    SHADER("shader"),
    RESOURCE_PACK("resourcepack"),
    MAP("mod", "worldgen");

    val displayName: String
        get() = when (this) {
            MOD -> "模组"
            SHADER -> "光影"
            RESOURCE_PACK -> "材质"
            MAP -> "地图"
        }

    /** 只有模组需要加载器过滤：光影/材质是 zip，地图是地形生成模组。 */
    val needsLoader: Boolean get() = this == MOD || this == MAP
}

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

    /**
     * 按类目搜索：模组看加载器，光影/材质只看游戏版本，地图=mod+worldgen。
     *
     * query 为空时 Modrinth 按下载量返回热门，所以首屏不必再写一套「推荐」接口。
     */
    suspend fun searchProjects(
        query: String,
        type: ProjectType,
        gameVersion: String?,
        loader: String?,
        limit: Int = 20
    ): Result<List<ModrinthHit>> = withContext(Dispatchers.IO) {
        try {
            val groups = ArrayList<List<String>>(4)
            groups.add(listOf("project_type:" + type.facet))
            type.extraCategory?.let { groups.add(listOf("categories:$it")) }
            if (type.needsLoader && !loader.isNullOrBlank()) {
                groups.add(listOf("categories:" + loader.lowercase()))
            }
            if (!gameVersion.isNullOrBlank()) {
                groups.add(listOf("versions:" + gameVersion))
            }
            val facets = groups.joinToString(",", "[", "]") { group ->
                group.joinToString(",", "[", "]") { "\"$it\"" }
            }
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

    /**
     * 取项目的全部版本（不按游戏版本/加载器过滤）。
     *
     * 光影包（shaderpack）不是模组：它不绑定 loader，也不进 mods 目录，
     * 只是 `shaderpacks/` 里的一个 zip，所以按「最新版本」取即可。
     */
    suspend fun allVersions(slugOrId: String): List<ModrinthVersion> = withContext(Dispatchers.IO) {
        try {
            json.decodeFromString<List<ModrinthVersion>>(Http.get("$BASE/project/$slugOrId/version"))
        } catch (e: Exception) {
            emptyList()
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

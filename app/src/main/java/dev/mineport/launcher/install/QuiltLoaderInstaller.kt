package dev.mineport.launcher.install

import dev.mineport.launcher.download.DownloadQueue
import dev.mineport.launcher.download.DownloadTask
import dev.mineport.launcher.download.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Quilt 加载器安装。和 [FabricLoaderInstaller] 同一策略：不跑官方 installer，
 * 用 Quilt Meta 的 loader profile 与原版 version json 合并成自包含版本。
 *
 * Quilt 的 profile 格式与 Fabric 兼容（Quilt 自己的 loader 会接管 Fabric 模组），
 * 所以这里只换元数据源与默认主类，合并、下载逻辑保持一致。
 */
object QuiltLoaderInstaller {

    private const val META = "https://meta.quiltmc.net"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun install(
        rootDir: File,
        minecraftVersion: String,
        loaderVersion: String? = null,
        onStage: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            onStage("查询 Quilt 加载器版本…")
            val version = loaderVersion ?: pickLoader(minecraftVersion)

            onStage("获取 Quilt profile（$version）…")
            val profileText = Http.get("$META/v2/versions/loader/$minecraftVersion/$version/profile/json")
            val profile = json.parseToJsonElement(profileText).jsonObject
            val profileId = profile["id"]?.jsonPrimitive?.content
                ?: "quilt-loader-$version-$minecraftVersion"
            val quiltMain = profile["mainClass"]?.jsonPrimitive?.content
                ?: "org.quiltmc.loader.impl.launch.knot.KnotClient"

            val vanillaFile = File(rootDir, "versions/$minecraftVersion/$minecraftVersion.json")
            if (!vanillaFile.isFile) {
                return@withContext Result.failure(
                    IllegalStateException("先安装原版 $minecraftVersion（下载页安装），再装 Quilt。")
                )
            }
            val vanilla = json.parseToJsonElement(vanillaFile.readText()).jsonObject
            val vanillaLibs = vanilla["libraries"]?.jsonArray ?: JsonArray(emptyList())
            val quiltLibs = profile["libraries"]?.jsonArray ?: JsonArray(emptyList())

            val merged = buildJsonObject {
                put("id", profileId)
                put("type", "release")
                put("jar", minecraftVersion)
                put("mainClass", quiltMain)
                put("libraries", JsonArray(vanillaLibs + quiltLibs))
                vanilla["assetIndex"]?.let { put("assetIndex", it) }
                vanilla["assets"]?.let { put("assets", it) }
                vanilla["downloads"]?.let { put("downloads", it) }
                put("arguments", mergeArguments(vanilla, profile))
            }
            val outDir = File(rootDir, "versions/$profileId").apply { mkdirs() }
            File(outDir, "$profileId.json").writeText(merged.toString())

            val tasks = quiltLibs.mapNotNull { element ->
                val lib = element.jsonObject
                val name = lib["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val base = lib["url"]?.jsonPrimitive?.content ?: "https://maven.quiltmc.org/repository/release/"
                val path = mavenPath(name) ?: return@mapNotNull null
                val target = File(rootDir, "libraries/$path")
                if (target.isFile && target.length() > 0) return@mapNotNull null
                DownloadTask(label = name, urls = listOf(base.trimEnd('/') + "/" + path), target = target)
            }
            if (tasks.isNotEmpty()) {
                onStage("下载 ${tasks.size} 个 Quilt 库…")
                val outcomes = DownloadQueue(4).run(tasks) { done, total, current ->
                    onStage("下载 Quilt 库 $done/$total：$current")
                }
                val failed = outcomes.count { !it.ok }
                if (failed > 0) {
                    return@withContext Result.failure(
                        IllegalStateException("$failed 个 Quilt 库下载失败，重试一次仍失败则换网络。")
                    )
                }
            }
            onStage("Quilt $version 已就绪")
            Result.success(profileId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun pickLoader(minecraftVersion: String): String {
        val text = Http.get("$META/v2/versions/loader/$minecraftVersion")
        val list = json.parseToJsonElement(text).jsonArray
        for (element in list) {
            val loader = element.jsonObject["loader"]?.jsonObject ?: continue
            if ((loader["stable"]?.jsonPrimitive?.content ?: "false") == "true") {
                loader["version"]?.jsonPrimitive?.content?.let { return it }
            }
        }
        val first = list.firstOrNull()?.jsonObject ?: throw IllegalStateException("Quilt 没有可用 loader")
        return first["loader"]?.jsonObject?.get("version")?.jsonPrimitive?.content
            ?: throw IllegalStateException("Quilt loader 版本解析失败")
    }

    private fun mergeArguments(vanilla: JsonObject, profile: JsonObject): JsonObject {
        val v = vanilla["arguments"]?.let { it as? JsonObject }
        val q = profile["arguments"]?.let { it as? JsonObject }
        val vanillaGame = v?.get("game")?.jsonArray ?: JsonArray(emptyList())
        val quiltGame = q?.get("game")?.jsonArray ?: JsonArray(emptyList())
        val vanillaJvm = v?.get("jvm")?.jsonArray ?: JsonArray(emptyList())
        val quiltJvm = q?.get("jvm")?.jsonArray ?: JsonArray(emptyList())
        return buildJsonObject {
            put("game", JsonArray(vanillaGame + quiltGame))
            put("jvm", JsonArray(vanillaJvm + quiltJvm))
        }
    }
}

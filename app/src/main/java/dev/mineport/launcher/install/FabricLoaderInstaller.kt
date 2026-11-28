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
 * Fabric 加载器安装（不跑官方 installer）：
 * 1) 从 meta.fabricmc.net 取 loader 版本与 profile json；
 * 2) 把 profile 与原版 version json 合并成一个自包含的版本（libraries + arguments + mainClass）；
 * 3) 下载 Fabric 库到 files/libraries。
 *
 * 好处是纯 Kotlin、离线可控；代价是 Forge/NeoForge 走不了这条路（它们的 installer 会改 jar）。
 */
object FabricLoaderInstaller {

    private const val META = "https://meta.fabricmc.net"
    private val json = Json { ignoreUnknownKeys = true }

    /** 可选 loader 版本。stable 来自 Fabric meta 的 loader.stable 字段。 */
    data class LoaderOption(val version: String, val stable: Boolean)

    /**
     * 列出某个 Minecraft 版本可用的 Fabric loader 版本（最新在前）。
     * 安装页要让人自己选，不能只用 pickLoader 的默认值。
     */
    suspend fun listLoaders(minecraftVersion: String): Result<List<LoaderOption>> =
        withContext(Dispatchers.IO) {
            try {
                val text = Http.get("$META/v2/versions/loader/$minecraftVersion")
                val list = json.parseToJsonElement(text).jsonArray
                val options = list.mapNotNull { element ->
                    val loader = element.jsonObject["loader"]?.jsonObject ?: return@mapNotNull null
                    val version = loader["version"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val stable = (loader["stable"]?.jsonPrimitive?.content ?: "false") == "true"
                    LoaderOption(version, stable)
                }
                Result.success(options)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun install(
        rootDir: File,
        minecraftVersion: String,
        loaderVersion: String? = null,
        onStage: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            onStage("查询 Fabric 加载器版本…")
            val version = loaderVersion ?: pickLoader(minecraftVersion)

            onStage("获取 Fabric profile（$version）…")
            val profileText = Http.get("$META/v2/versions/loader/$minecraftVersion/$version/profile/json")
            val profile = json.parseToJsonElement(profileText).jsonObject
            val profileId = profile["id"]?.jsonPrimitive?.content
                ?: "fabric-loader-$version-$minecraftVersion"
            val fabricMain = profile["mainClass"]?.jsonPrimitive?.content
                ?: "net.fabricmc.loader.impl.launch.knot.KnotClient"

            val vanillaFile = File(rootDir, "versions/$minecraftVersion/$minecraftVersion.json")
            if (!vanillaFile.isFile) {
                return@withContext Result.failure(
                    IllegalStateException("先安装原版 $minecraftVersion（下载页安装），再装 Fabric。")
                )
            }
            val vanilla = json.parseToJsonElement(vanillaFile.readText()).jsonObject
            val vanillaLibs = vanilla["libraries"]?.jsonArray ?: JsonArray(emptyList())
            val fabricLibs = profile["libraries"]?.jsonArray ?: JsonArray(emptyList())

            val merged = buildJsonObject {
                put("id", profileId)
                put("type", "release")
                // 原版 jar 留在 versions/<mc>/；启动时按 jar 字段回找。
                put("jar", minecraftVersion)
                put("mainClass", fabricMain)
                put("libraries", JsonArray(vanillaLibs + fabricLibs))
                vanilla["assetIndex"]?.let { put("assetIndex", it) }
                vanilla["assets"]?.let { put("assets", it) }
                vanilla["downloads"]?.let { put("downloads", it) }
                put("arguments", mergeArguments(vanilla, profile))
            }
            val outDir = File(rootDir, "versions/$profileId").apply { mkdirs() }
            File(outDir, "$profileId.json").writeText(merged.toString())

            val tasks = fabricLibs.mapNotNull { element ->
                val lib = element.jsonObject
                val name = lib["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val base = lib["url"]?.jsonPrimitive?.content ?: "https://maven.fabricmc.net/"
                val path = mavenPath(name) ?: return@mapNotNull null
                val target = File(rootDir, "libraries/$path")
                if (target.isFile && target.length() > 0) return@mapNotNull null
                DownloadTask(label = name, urls = listOf(base.trimEnd('/') + "/" + path), target = target)
            }
            if (tasks.isNotEmpty()) {
                onStage("下载 ${tasks.size} 个 Fabric 库…")
                val outcomes = DownloadQueue(4).run(tasks) { done, total, current ->
                    onStage("下载 Fabric 库 $done/$total：$current")
                }
                val failed = outcomes.count { !it.ok }
                if (failed > 0) {
                    return@withContext Result.failure(
                        IllegalStateException("$failed 个 Fabric 库下载失败，重试一次仍失败则换网络。")
                    )
                }
            }
            onStage("Fabric $version 已就绪")
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
        // 没有 stable 标记时用第一个（Fabric 通常最新版就是稳定版）。
        val first = list.firstOrNull()?.jsonObject ?: throw IllegalStateException("Fabric 没有可用 loader")
        return first["loader"]?.jsonObject?.get("version")?.jsonPrimitive?.content
            ?: throw IllegalStateException("Fabric loader 版本解析失败")
    }

    private fun mergeArguments(vanilla: JsonObject, profile: JsonObject): JsonObject {
        val v = vanilla["arguments"]?.let { it as? JsonObject }
        val f = profile["arguments"]?.let { it as? JsonObject }
        val vanillaGame = v?.get("game")?.jsonArray ?: JsonArray(emptyList())
        val fabricGame = f?.get("game")?.jsonArray ?: JsonArray(emptyList())
        val vanillaJvm = v?.get("jvm")?.jsonArray ?: JsonArray(emptyList())
        val fabricJvm = f?.get("jvm")?.jsonArray ?: JsonArray(emptyList())
        return buildJsonObject {
            put("game", JsonArray(vanillaGame + fabricGame))
            put("jvm", JsonArray(vanillaJvm + fabricJvm))
        }
    }
}

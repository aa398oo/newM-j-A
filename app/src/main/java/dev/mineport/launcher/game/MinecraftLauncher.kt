package dev.mineport.launcher.game

import dev.mineport.launcher.install.Rules
import dev.mineport.launcher.install.VersionJson
import dev.mineport.launcher.install.mavenPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.zip.ZipInputStream

/** 启动游戏所需的三样东西：主类、classpath、参数。 */
data class LaunchPlan(
    val mainClass: String,
    val classPath: List<File>,
    val gameArgs: List<String>,
    val nativesDir: File
)

/**
 * 原版启动路径：不依赖 FCLCore，直接按 version JSON 组装 classpath 与 main 参数，
 * 调 JvmLauncher.nativeRunMainClass 启动 `net.minecraft.client.main.Main`。
 *
 * 这样即使 FCLCore 构建失败，只要版本装好了也能进游戏——P1 生死线的兜底。
 * 加载器（Fabric/Forge）的启动参数与 FCLCore 接管在后续迭代。
 */
object MinecraftLauncher {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun prepare(
        rootDir: File,
        versionId: String,
        playerName: String,
        gameDir: File,
        assetsDir: File,
        nativesDir: File,
        /** 登录账号；null 时按离线账号处理（playerName + accessToken=0）。 */
        account: dev.mineport.launcher.data.Account? = null
    ): Result<LaunchPlan> = withContext(Dispatchers.IO) {
        try {
            val versionDir = File(rootDir, "versions/$versionId")
            val versionFile = File(versionDir, "$versionId.json")
            if (!versionFile.isFile) {
                return@withContext Result.failure(
                    IllegalStateException("未安装 $versionId：缺少 " + versionFile.absolutePath)
                )
            }
            // Fabric/Forge 的 profile 用 inheritsFrom 链到原版；从子往父逐层读，
            // 主类取子层，库与参数从父到子合并（子层同名库覆盖父层）。
            val chain = loadChain(rootDir, versionId)
            val mainClass = chain.firstOrNull { it.mainClass.isNotBlank() }?.mainClass.orEmpty()
            if (mainClass.isBlank()) {
                return@withContext Result.failure(IllegalStateException("版本 JSON 没有 mainClass"))
            }
            val librariesDir = File(rootDir, "libraries")
            val libraries = chain.reversed().flatMap { it.libraries }.distinctBy { it.name }

            val classPath = mutableListOf<File>()
            // 版本 jar：优先自身目录，其次沿链找（Fabric 版本目录里没有原版 jar），
            // 最后用合并 profile 的 jar 字段指回原版版本 id。
            val jarIds = (listOf(versionId) + chain.map { it.id } + chain.mapNotNull { it.jar }).distinct()
            val jar = jarIds
                .mapNotNull { id -> File(rootDir, "versions/$id/$id.jar").takeIf { it.isFile } }
                .firstOrNull()
            jar?.let { classPath += it }
            for (library in libraries) {
                if (!Rules.allows(library.rules)) continue
                val artifact = library.downloads.artifact ?: continue
                val path = artifact.path.takeIf { it.isNotBlank() } ?: mavenPath(library.name) ?: continue
                val libJar = File(librariesDir, path)
                if (libJar.isFile) classPath += libJar
            }

            // Android/arm64 上不能解压 version JSON 里的 natives-linux：
            // 那是 x86_64 构建，解进 java.library.path 会让 LWJGL 加载到错误架构的 .so。
            // arm64 的 LWJGL / GL4ES 由 APK 的 jniLibs/arm64-v8a 提供，JvmLauncher 已把
            // nativeLibraryDir 放进 java.library.path 的首位，所以这里不再解压。
            @Suppress("UNUSED_PARAMETER")
            val ignoredNativesDir = nativesDir

            val args = buildArgs(chain, versionId, playerName, gameDir, assetsDir, account)
            Result.success(LaunchPlan(mainClass, classPath, args, nativesDir))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 从 [versionId] 沿 inheritsFrom 往上读，最多 8 层，防止环。 */
    private fun loadChain(rootDir: File, versionId: String): List<VersionJson> {
        val chain = mutableListOf<VersionJson>()
        var current: String? = versionId
        val seen = mutableSetOf<String>()
        while (current != null && seen.size < 8 && seen.add(current)) {
            val file = File(rootDir, "versions/$current/$current.json")
            if (!file.isFile) break
            val jsonText = file.readText()
            val parsed = json.decodeFromString<VersionJson>(jsonText)
            chain += parsed
            current = parsed.inheritsFrom
        }
        return chain
    }

    private fun buildArgs(
        chain: List<VersionJson>,
        versionId: String,
        playerName: String,
        gameDir: File,
        assetsDir: File,
        account: dev.mineport.launcher.data.Account? = null
    ): List<String> {
        val raw = mutableListOf<String>()

        // 父（原版）参数先、子（Fabric/Forge）参数后；子层可以追加自己的参数。
        for (version in chain.reversed()) {
            val arguments = version.arguments
            if (arguments != null) {
                val game = (arguments as? JsonObject)?.get("game")
                if (game is JsonArray) {
                    for (item in game) {
                        when (item) {
                            is JsonPrimitive -> if (item.isString) raw += item.content
                            is JsonObject -> {
                                if (!rulesAllow(item["rules"])) continue
                                when (val value = item["value"]) {
                                    is JsonPrimitive -> if (value.isString) raw += value.content
                                    is JsonArray -> value.forEach { part ->
                                        if (part is JsonPrimitive && part.isString) raw += part.content
                                    }
                                    else -> {}
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
            if (raw.isEmpty() && version.minecraftArguments.isNotBlank()) {
                raw += version.minecraftArguments.split(" ").filter { it.isNotBlank() }
            }
        }

        val assetIndexName = chain.firstNotNullOfOrNull { it.assetIndex?.id } ?: "legacy"
        // 账号优先：有登录账号就用它的名字/uuid/令牌，否则退回版本里的 playerName。
        val effectiveName = account?.name?.takeIf { it.isNotBlank() } ?: playerName
        val authUuid = account?.uuid?.replace("-", "")?.takeIf { it.isNotBlank() } ?: "0"
        val authToken = account?.accessToken?.takeIf { it.isNotBlank() } ?: "0"
        val userType = if (account?.isMs == true) "msa" else "legacy"
        val replacements = mapOf(
            "\${auth_player_name}" to effectiveName,
            "\${version_name}" to versionId,
            "\${game_directory}" to gameDir.absolutePath,
            "\${assets_root}" to assetsDir.absolutePath,
            "\${assets_index_name}" to assetIndexName,
            "\${auth_uuid}" to authUuid,
            "\${auth_access_token}" to authToken,
            "\${auth_session}" to authToken,
            "\${user_type}" to userType,
            "\${version_type}" to "release",
            "\${user_properties}" to "{}",
            "\${clientid}" to "0",
            "\${auth_xuid}" to "0"
        )

        // 逐个参数替换占位符；替换后仍含 "\${" 的说明是启动器没提供的值
        // （如 1.20.1 的 "--quickPlayPath \${quickPlayPath}"），要把占位符连同
        // 它前面的开关参数一起丢掉，否则 MC 会把下一个参数当成路径/地址。
        // 注意不能反过来「下一个参数含占位符就跳过上一对」——那会把
        // "--username \${auth_player_name}" 这类正常参数也误删。
        val out = mutableListOf<String>()
        for (original in raw) {
            var result = original
            for ((key, value) in replacements) result = result.replace(key, value)
            if (result.contains("\${")) {
                if (out.isNotEmpty() && out.last().startsWith("--")) out.removeAt(out.lastIndex)
                continue
            }
            out += result
        }
        return out
    }

    private fun rulesAllow(rules: JsonElement?): Boolean {
        if (rules !is JsonArray || rules.isEmpty()) return true
        var allowed = false
        for (rule in rules) {
            if (rule !is JsonObject) continue
            val action = (rule["action"] as? JsonPrimitive)?.content ?: continue
            val os = rule["os"] as? JsonObject
            val osName = (os?.get("name") as? JsonPrimitive)?.content
            if (osName != null && osName != "linux") continue
            allowed = action == "allow"
        }
        return allowed
    }

    private fun extractNatives(version: VersionJson, librariesDir: File, nativesDir: File) {
        nativesDir.mkdirs()
        for (library in version.libraries) {
            if (!Rules.allows(library.rules)) continue
            val key = library.natives["linux"] ?: continue
            val artifact = library.downloads.classifiers[key] ?: continue
            val path = artifact.path.takeIf { it.isNotBlank() } ?: mavenPath(library.name, key) ?: continue
            unzip(File(librariesDir, path), nativesDir)
        }
    }

    private fun unzip(jar: File, targetDir: File) {
        if (!jar.isFile) return
        ZipInputStream(jar.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || entry.name.startsWith("META-INF/")) {
                    zip.closeEntry()
                    continue
                }
                val target = File(targetDir, File(entry.name).name)
                target.outputStream().use { output -> zip.copyTo(output) }
                zip.closeEntry()
            }
        }
    }
}

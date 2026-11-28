package dev.mineport.launcher.install

import dev.mineport.launcher.download.FileDownloader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile

/**
 * Forge 1.20.1 的安装器（install_profile.json 驱动的「最小可用」实现）。
 *
 * 为什么不是「下载 universal jar 就完事」：Forge 自 1.13 起不再是整包覆盖，而是
 * 用 install_profile.json 里的 10 个 **processor** 去改原版 jar：
 * `installertools` 抽 MCP 映射 → `jarsplitter` 切 client → `FART` 重命名 →
 * `binarypatcher` 打 client.lzma 补丁。缺任何一步都得不到能启动的 client jar。
 *
 * 本类只做三件事，且刻意不碰 JVM：
 * 1) 从 installer jar 里读 `install_profile.json` / `version.json`；
 * 2) 把 46+29 个库、原版 client jar、`data/client.lzma` 全部落盘；
 * 3) 算好每个 processor 的 `mainClass + classpath + args`（`{VAR}` 与 `[coord]` 都已替换）。
 *
 * 真正执行靠 [ProcessorRunner]（Android 侧由 [dev.mineport.launcher.jvm.JvmLauncher] 提供）。
 * 这样「解析/替换」这类纯逻辑可以单测，而「起 JVM」这类副作用只在最后一步发生。
 */
object ForgeInstaller {

    /** 默认首发目标：只支持这一个组合，避免过早铺开。 */
    const val SUPPORTED_MINECRAFT = "1.20.1"
    const val SUPPORTED_FORGE = "47.2.0"

    private const val FORGE_MAVEN = "https://maven.minecraftforge.net/"

    /**
     * 第二仓库。
     *
     * Forge 自己的 maven 只放 `net.minecraftforge:*` 与部分 `org.ow2.asm:*`；
     * processor 的 classpath 里还有 `com.google.guava:guava`、`commons-io` 这些
     * 只在 Central 有的库。缺任何一个，`java -cp` 起来就是 NoClassDefFoundError。
     */
    private const val CENTRAL_MAVEN = "https://repo1.maven.org/maven2/"
    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** `group:artifact:version[:classifier][@ext]`。 */
    data class MavenRef(
        val group: String,
        val artifact: String,
        val version: String,
        val classifier: String? = null,
        val extension: String = "jar"
    ) {
        /** 相对 `libraries/` 的路径，Maven 布局。 */
        val relativePath: String
            get() {
                val base = "$artifact-$version" + (classifier?.let { "-$it" } ?: "")
                return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + base + "." + extension
            }

        fun resolve(librariesDir: File): File = File(librariesDir, relativePath)
        override fun toString(): String =
            "$group:$artifact:$version" + (classifier?.let { ":$it" } ?: "") + "@$extension"
    }

    /** install_profile 里 `data` 的一项：同一变量在 client/server 下含义不同。 */
    data class DataVar(val client: String, val server: String)

    /** 一个待执行的 processor。 */
    data class ProcessorRun(
        val mainClass: String,
        val classPath: List<File>,
        val args: List<String>
    ) {
        /** 给日志/诊断用的单行形式。 */
        fun describe(): String = mainClass + " " + args.joinToString(" ")
    }

    /** 解析出来的 install_profile。 */
    data class InstallProfile(
        val spec: Int,
        val version: String,
        val minecraft: String,
        val data: Map<String, DataVar>,
        val processors: List<JsonObject>,
        val libraryNames: List<String>
    )

    /**
     * processor 的 jar → mainClass。
     *
     * 不用读 MANIFEST 是因为这 4 个工具的入口名是 install_profile 之外的隐式约定；
     * 写死换来的是「拿不到 manifest 也能跑」，且这 4 个 jar 的版本由 profile 锁定。
     */
    private fun mainClassOf(jarCoord: String): String? = when {
        jarCoord.startsWith("net.minecraftforge:installertools:") -> "net.minecraftforge.installertools.ConsoleTool"
        jarCoord.startsWith("net.minecraftforge:jarsplitter:") -> "net.minecraftforge.jarsplitter.ConsoleTool"
        jarCoord.startsWith("net.minecraftforge:ForgeAutoRenamingTool:") -> "net.minecraftforge.fart.Main"
        jarCoord.startsWith("net.minecraftforge:binarypatcher:") -> "net.minecraftforge.binarypatcher.ConsoleTool"
        else -> null
    }

    /** 解析 `group:artifact:version[:classifier][@ext]`，非法返回 null。 */
    fun parseMaven(raw: String): MavenRef? {
        val at = raw.indexOf('@')
        val ext = if (at >= 0) raw.substring(at + 1) else "jar"
        val body = if (at >= 0) raw.substring(0, at) else raw
        val parts = body.split(':')
        if (parts.size < 3) return null
        return MavenRef(
            group = parts[0],
            artifact = parts[1],
            version = parts[2],
            classifier = parts.getOrNull(3)?.takeIf { it.isNotBlank() },
            extension = ext.ifBlank { "jar" }
        )
    }

    /** 从 installer jar 里读 `install_profile.json` 与 `version.json`。 */
    fun readProfile(installerJar: File): Result<Pair<InstallProfile, JsonObject>> = runCatching {
        ZipFile(installerJar).use { zip ->
            val profileText = zip.getEntry("install_profile.json")?.let { e ->
                zip.getInputStream(e).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } ?: error("installer 里没有 install_profile.json")
            val versionText = zip.getEntry("version.json")?.let { e ->
                zip.getInputStream(e).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } ?: error("installer 里没有 version.json")

            val profileJson = JSON.parseToJsonElement(profileText).jsonObject
            val versionJson = JSON.parseToJsonElement(versionText).jsonObject

            val data = LinkedHashMap<String, DataVar>()
            profileJson["data"]?.jsonObject?.forEach { (key, value) ->
                val obj = value.jsonObject
                data[key] = DataVar(
                    client = obj["client"]?.jsonPrimitive?.content.orEmpty(),
                    server = obj["server"]?.jsonPrimitive?.content.orEmpty()
                )
            }

            val processors = profileJson["processors"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
            val libraryNames = profileJson["libraries"]?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content } ?: emptyList()

            val profile = InstallProfile(
                spec = profileJson["spec"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
                version = profileJson["version"]?.jsonPrimitive?.content.orEmpty(),
                minecraft = profileJson["minecraft"]?.jsonPrimitive?.content.orEmpty(),
                data = data,
                processors = processors,
                libraryNames = libraryNames
            )
            profile to versionJson
        }
    }

    /**
     * 把 data 变量结算成「client 侧的文件路径或字面量」。
     *
     * - `[group:artifact:...]` → `libraries/` 下的文件
     * - `/data/xxx` → 从 installer jar 解出来的文件（调用方负责先解压）
     * - `'literal'` → 去掉引号的字面量
     */
    fun resolveDataVars(
        profile: InstallProfile,
        librariesDir: File,
        unpacked: Map<String, File>,
        literals: Map<String, String>,
        side: String = "client"
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((key, v) in profile.data) {
            val raw = if (side == "client") v.client else v.server
            out[key] = when {
                raw.startsWith("[") && raw.endsWith("]") -> {
                    val coord = raw.substring(1, raw.length - 1)
                    parseMaven(coord)?.resolve(librariesDir)?.absolutePath ?: raw
                }
                raw.startsWith("/") -> unpacked[raw]?.absolutePath ?: raw
                raw.startsWith("'") && raw.endsWith("'") && raw.length >= 2 -> raw.substring(1, raw.length - 1)
                else -> raw
            }
        }
        // 这几个不在 data 里，但 processor 的 args 会用到。
        out.putAll(literals)
        return out
    }

    /** 单条 arg 的替换：`{KEY}` 与 `[coord]`。 */
    fun substitute(arg: String, vars: Map<String, String>, librariesDir: File): String {
        var s = arg
        if (s.startsWith("[") && s.endsWith("]")) {
            parseMaven(s.substring(1, s.length - 1))?.let { m ->
                return m.resolve(librariesDir).absolutePath
            }
        }
        for ((k, v) in vars) {
            s = s.replace("{$k}", v)
        }
        return s
    }

    /**
     * 生成 client 侧要跑的 processor 列表。
     *
     * 跳过 `sides = server` 的（0/1/2/7）：那几条是给专用服务器解包/解 bundler 用的，
     * 客户端跑了只会白下载。
     */
    fun buildRuns(
        profile: InstallProfile,
        librariesDir: File,
        vars: Map<String, String>
    ): List<ProcessorRun> {
        val runs = ArrayList<ProcessorRun>()
        for (p in profile.processors) {
            val sides = p["sides"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            if (!sideAllows(sides, "client")) continue

            val jarCoord = p["jar"]?.jsonPrimitive?.content ?: continue
            val mainClass = mainClassOf(jarCoord) ?: continue

            val cp = ArrayList<File>()
            parseMaven(jarCoord)?.let { cp.add(it.resolve(librariesDir)) }
            p["classpath"]?.jsonArray?.forEach { el ->
                parseMaven(el.jsonPrimitive.content)?.let { cp.add(it.resolve(librariesDir)) }
            }

            val args = p["args"]?.jsonArray?.map { substitute(it.jsonPrimitive.content, vars, librariesDir) } ?: emptyList()
            runs.add(ProcessorRun(mainClass = mainClass, classPath = cp, args = args))
        }
        return runs
    }

    /** `sides` 是「允许的 side 列表」；空表示通用。 */
    private fun sideAllows(sides: List<String>, side: String): Boolean =
        sides.isEmpty() || sides.contains(side) || sides.contains("")

    /** version.json 里 `libraries[].name` + `downloads.artifact`。 */
    private fun librariesOf(json: JsonObject): List<Pair<String, String?>> {
        val out = ArrayList<Pair<String, String?>>()
        json["libraries"]?.jsonArray?.forEach { el ->
            val lib = el.jsonObject
            val name = lib["name"]?.jsonPrimitive?.content ?: return@forEach
            val url = lib["downloads"]?.jsonObject
                ?.get("artifact")?.jsonObject
                ?.get("url")?.jsonPrimitive?.content
            out.add(name to url)
        }
        return out
    }

    /**
     * 下载一个库到 Maven 布局的路径。
     *
     * 优先用 `downloads.artifact.url`（Mojang/Forge 都会给绝对地址），
     * 没有就用 Maven 坐标拼 Forge 仓库的地址。
     */
    private suspend fun fetchLibrary(
        librariesDir: File,
        name: String,
        url: String?,
        onProgress: (Long, Long) -> Unit
    ): Result<Unit> {
        val ref = parseMaven(name) ?: return Result.failure(IllegalArgumentException("非法坐标 $name"))
        val target = ref.resolve(librariesDir)
        val urls = ArrayList<String>(3)
        if (!url.isNullOrBlank()) urls.add(url)
        urls.add(FORGE_MAVEN + ref.relativePath)
        urls.add(CENTRAL_MAVEN + ref.relativePath)
        return FileDownloader.download(urls, target, sha1 = null, onProgress = onProgress)
    }

    /**
     * 把安装需要的全部静态文件落盘：installer、libraries、原版 client jar、data 里的补丁。
     *
     * 返回 `vars`（`{VAR}` 表）与 `runs`（待执行命令），供调用方交给 JVM 执行。
     */
    suspend fun stage(
        rootDir: File,
        installerUrl: String,
        minecraftClientJar: File,
        onStage: (String) -> Unit,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<Staged> = runCatching {
        val librariesDir = File(rootDir, "libraries")
        val installer = File(rootDir, "forge-installer.jar")
        onStage("下载 Forge 安装器 …")
        FileDownloader.download(listOf(installerUrl), installer).getOrThrow()

        val (profile, versionJson) = readProfile(installer).getOrThrow()
        require(profile.minecraft == SUPPORTED_MINECRAFT) {
            "只支持 Minecraft $SUPPORTED_MINECRAFT 的 Forge，拿到的是 ${profile.minecraft}"
        }

        // data 里以 "/" 开头的项在 installer jar 内（只有 client.lzma），先解出来。
        val unpacked = HashMap<String, File>()
        ZipFile(installer).use { zip ->
            for ((key, v) in profile.data) {
                val raw = if (v.client.isNotBlank()) v.client else v.server
                if (!raw.startsWith("/")) continue
                val entry = zip.getEntry(raw.trimStart('/'))
                if (entry == null) {
                    // data/ 在 jar 根下，也允许带前缀写成 "data/xxx"。
                    val alt = zip.getEntry("data/" + raw.trimStart('/').removePrefix("data/"))
                    if (alt != null) {
                        val out = File(rootDir, "data/" + File(alt.name).name)
                        out.parentFile?.mkdirs()
                        zip.getInputStream(alt).use { input -> out.outputStream().use { input.copyTo(it) } }
                        unpacked[raw] = out
                    }
                    continue
                }
                val out = File(rootDir, "data/" + File(entry.name).name)
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                unpacked[raw] = out
            }
        }

        val all = LinkedHashMap<String, String?>()
        for ((n, u) in librariesOf(versionJson)) all[n] = u
        for ((n, u) in librariesOf(profileToJson(profile))) all[n] = u

        // processor 的 jar、classpath 与 args 里的 [coord] 同样是「运行前必须在本地」的文件。
        // 只下 libraries 是不够的：mcp_config 的 mappings zip 就只出现在 args 里。
        for (p in profile.processors) {
            fun need(coord: String?) {
                if (coord != null && parseMaven(coord) != null) all.putIfAbsent(coord, null)
            }
            need(p["jar"]?.jsonPrimitive?.content)
            p["classpath"]?.jsonArray?.forEach { need(it.jsonPrimitive.content) }
            p["args"]?.jsonArray?.forEach { el ->
                val a = el.jsonPrimitive.content
                if (a.startsWith("[") && a.endsWith("]")) need(a.substring(1, a.length - 1))
            }
        }

        var done = 0
        for ((name, url) in all) {
            onStage("库 ${++done}/${all.size}：$name")
            fetchLibrary(librariesDir, name, url, onProgress).getOrThrow()
        }

        onStage("检查原版客户端 …")
        require(minecraftClientJar.isFile) { "缺少原版 client jar：${minecraftClientJar.absolutePath}" }

        val vars = resolveDataVars(
            profile = profile,
            librariesDir = librariesDir,
            unpacked = unpacked,
            literals = mapOf(
                "ROOT" to rootDir.absolutePath,
                "MINECRAFT_JAR" to minecraftClientJar.absolutePath,
                "INSTALLER" to installer.absolutePath,
                "SIDE" to "client"
            )
        )
        val runs = buildRuns(profile, librariesDir, vars)
        Staged(installer = installer, librariesDir = librariesDir, profile = profile, versionJson = versionJson, vars = vars, runs = runs)
    }

    /** [stage] 的产物。 */
    data class Staged(
        val installer: File,
        val librariesDir: File,
        val profile: InstallProfile,
        val versionJson: JsonObject,
        val vars: Map<String, String>,
        val runs: List<ProcessorRun>
    )

    /**
     * 依次执行 processor。
     *
     * [runner] 的实现方负责「把 classpath 装进 JVM 并调用 main」。任何一步非 0 就停，
     * 因为后面的步骤依赖前面的输出（jarsplitter 的输出是 FART 的输入）。
     */
    suspend fun runProcessors(
        staged: Staged,
        onStage: (String) -> Unit,
        runner: ProcessorRunner
    ): Result<Unit> = runCatching {
        runner.attach(staged)
        var i = 0
        for (run in staged.runs) {
            onStage("processor ${++i}/${staged.runs.size}：${run.mainClass}")
            val code = runner.run(run)
            check(code == 0) { "processor 失败（退出码 $code）：${run.mainClass}" }
        }
    }

    /** processor 执行器。Android 侧用 JVM；测试里可以换成假实现。 */
    fun interface ProcessorRunner {
        fun run(job: ProcessorRun): Int

        /**
         * 拿到 stage 结果后补配置。
         *
         * 给 [dev.mineport.launcher.install.ForgeJvmRunner] 用：它要在建 JVM 之前
         * 读 Forge 的 `arguments.jvm`（module path），而那要到 stage 之后才可见。
         * JVM 一个进程只能建一次，参数错过就补不回来了。
         */
        fun attach(staged: Staged) {}
    }

    /**
     * 完整安装：落盘 → 跑 processor → 写版本 json。
     *
     * 写版本 json 放在 processor 之后，是为了「没跑完就不出现半成品版本」——
     * 版本目录一旦出现，启动器就会认为它可启动。
     */
    suspend fun install(
        rootDir: File,
        installerUrl: String,
        minecraftClientJar: File,
        onStage: (String) -> Unit,
        runner: ProcessorRunner,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<File> = runCatching {
        val staged = stage(rootDir, installerUrl, minecraftClientJar, onStage, onProgress).getOrThrow()
        runProcessors(staged, onStage, runner).getOrThrow()

        val patched = staged.vars["PATCHED"]?.let { File(it) }
        require(patched != null && patched.isFile) {
            "binarypatcher 没有产出 client jar：${patched?.absolutePath ?: "PATCHED 变量缺失"}"
        }
        // install_profile 自带 PATCHED_SHA：对得上才说明 4 个工具的输入/参数没跑偏。
        // 这条校验值钱的地方在于「静默产出坏 jar」是最难查的失败模式。
        val expectSha = staged.profile.data["PATCHED_SHA"]?.client?.trim('\'', '"')
        if (!expectSha.isNullOrBlank()) {
            val actual = FileDownloader.sha1Of(patched!!)
            require(actual.equals(expectSha, ignoreCase = true)) {
                "打补丁后的 client jar 校验失败：期望 $expectSha，实际 $actual"
            }
        }

        val id = versionId(staged.versionJson)
        val dir = File(rootDir, "versions/$id").apply { mkdirs() }
        // 把打补丁的 client jar 放到版本目录：MinecraftLauncher 找 jar 只看
        // versions/<id>/<id>.jar，而 Forge 的 -DignoreList 里写的也是
        // "${version_name}.jar"——两边正好落到同一个文件上。
        val versionJar = File(dir, "$id.jar")
        if (!versionJar.isFile || versionJar.length() != patched!!.length()) {
            patched.copyTo(versionJar, overwrite = true)
        }
        val json = File(dir, "$id.json")
        json.writeText(staged.versionJson.toString(), Charsets.UTF_8)
        json
    }

    /**
     * 打补丁后的 client jar 落在哪。
     *
     * 路径能从 `PATCHED` 变量算，但那个变量要等 stage 之后才有；启动 classpath 与
     * 安装器的 JVM 都要在 stage 之前引用它，所以这里按 Maven 布局正推一遍。
     * 返回的文件可能尚不存在（安装中），调用方自己 `isFile` 判断。
     */
    fun patchedJar(rootDir: File): File? {
        val ref = MavenRef(
            group = "net.minecraftforge",
            artifact = "forge",
            version = "$SUPPORTED_MINECRAFT-$SUPPORTED_FORGE",
            classifier = "client"
        )
        return ref.resolve(File(rootDir, "libraries"))
    }

    /** 首发的 installer jar 地址（5.9 MB，含 install_profile 与 data/client.lzma）。 */
    fun installerUrl(): String =
        "https://maven.minecraftforge.net/net/minecraftforge/forge/" +
            "$SUPPORTED_MINECRAFT-$SUPPORTED_FORGE/" +
            "forge-$SUPPORTED_MINECRAFT-$SUPPORTED_FORGE-installer.jar"

    /** 把 [InstallProfile] 转回一份「只有 libraries」的 JsonObject，复用 [librariesOf]。 */
    private fun profileToJson(profile: InstallProfile): JsonObject =
        JsonObject(mapOf("libraries" to JsonArray(profile.libraryNames.map { JsonPrimitive(it) })))

    /** 从 version.json 取 playable 版本号（写进 `versions/<id>/<id>.json`）。 */
    fun versionId(versionJson: JsonObject): String =
        versionJson["id"]?.jsonPrimitive?.content ?: "${SUPPORTED_MINECRAFT}-forge-${SUPPORTED_FORGE}"

    /** 供 [dev.mineport.launcher.data.GameInstance] 用的 loader 版本串。 */
    fun loaderVersion(): String = "forge-$SUPPORTED_FORGE"
}

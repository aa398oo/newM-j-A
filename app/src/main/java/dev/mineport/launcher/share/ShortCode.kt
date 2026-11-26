package dev.mineport.launcher.share

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

/** 可分享的配置（引用清单，不含游戏本体与 mod 文件——DOWNLOADS/PRODUCT-CORE 的 6 位短码口径）。 */
@Serializable
data class ShareConfig(
    val version: String,
    val loader: String = "vanilla",
    val loaderVersion: String? = null,
    val memoryMB: Int = 2048,
    val backend: String = "auto",
    val mods: List<String> = emptyList()
)

/**
 * 6 位短码。
 *
 * 编码：Crockford Base32（去掉易混的 I/L/O/U）。前 5 位是 25 bit 数据，第 6 位是校验，
 * 用户念错/打错一位时能当场判出来，不用等服务器返回 404。
 *
 * 现阶段没有服务端：短码在本机登记（filesDir/share/<code>.json），跨设备分享需要
 * 一个极小的短码服务（PRODUCT-CORE 允许；只存引用清单，成本与合规压力都最低）。
 */
object ShortCode {

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun generate(): String = encodePayload(random.nextInt() and 0x1FFFFFF)

    fun encodePayload(payload: Int): String {
        var value = payload and 0x1FFFFFF
        val chars = CharArray(6)
        var sum = 0
        for (index in 4 downTo 0) {
            val digit = value and 31
            chars[index] = ALPHABET[digit]
            sum += digit
            value = value shr 5
        }
        chars[5] = ALPHABET[sum and 31]
        return String(chars)
    }

    /** 合法则返回 25 bit 载荷，否则 null。 */
    fun decode(code: String): Int? {
        val normalized = normalize(code)
        if (normalized.length != 6) return null
        var value = 0
        var sum = 0
        for (index in 0 until 5) {
            val digit = ALPHABET.indexOf(normalized[index])
            if (digit < 0) return null
            value = (value shl 5) or digit
            sum += digit
        }
        val check = ALPHABET.indexOf(normalized[5])
        if (check < 0 || check != (sum and 31)) return null
        return value
    }

    fun isValid(code: String): Boolean = decode(code) != null

    /** 容忍用户输入的小写、空格与连字符。 */
    fun normalize(code: String): String =
        code.uppercase().filter { it.isLetterOrDigit() }.replace("O", "0").replace("I", "1").replace("L", "1")
}

/**
 * 短码 → 配置的本地登记表。跨设备时把 [ShareStore] 换成远端实现即可。
 */
class ShareStore(private val rootDir: File) {

    private val dir = File(rootDir, "share")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun save(code: String, config: ShareConfig): Boolean {
        val normalized = ShortCode.normalize(code)
        if (!ShortCode.isValid(normalized)) return false
        dir.mkdirs()
        File(dir, "$normalized.json").writeText(json.encodeToString(ShareConfig.serializer(), config))
        return true
    }

    fun load(code: String): ShareConfig? {
        val normalized = ShortCode.normalize(code)
        val file = File(dir, "$normalized.json")
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(ShareConfig.serializer(), file.readText())
        }.getOrNull()
    }

    fun create(config: ShareConfig): String {
        // 本机只有 2^25 个载荷；冲突概率极低，冲突时重试即可。
        repeat(8) {
            val code = ShortCode.generate()
            if (load(code) == null && save(code, config)) return code
        }
        error("短码空间暂时冲突，请重试")
    }

    fun all(): List<Pair<String, ShareConfig>> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { file ->
                runCatching {
                    file.nameWithoutExtension to
                        json.decodeFromString(ShareConfig.serializer(), file.readText())
                }.getOrNull()
            }
    }
}

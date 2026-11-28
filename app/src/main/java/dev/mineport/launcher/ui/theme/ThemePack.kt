package dev.mineport.launcher.ui.theme

import android.content.ContentResolver
import android.net.Uri
import dev.mineport.launcher.input.PadPrefs
import dev.mineport.launcher.ui.prefs.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * `.mptheme` 主题包（UX-19）。
 *
 * 一个 zip，里面至少有一个 `theme.json`。之所以用 zip 而不是裸 json：
 * 以后要放预览图（`preview.png`）、字体、背景图，扩展位是现成的，旧版本读到
 * 不认识的条目直接跳过——`ignoreUnknownKeys` 负责 json 侧，`substringAfterLast` 负责 zip 侧。
 */
@Serializable
data class ThemePack(
    /** 格式版本。以后加字段不改它；只有语义变了才 +1，旧版本据此拒收。 */
    val format: Int = 1,
    val name: String = "我的主题",
    val author: String = "",
    /** system / light / dark。 */
    val theme: String = "light",
    /** 预设名（blue/green/…）或 `#RRGGBB`。 */
    val accent: String = "#1E5EFF",
    val animations: Boolean = true,
    /** 轮盘直径，进游戏时生效。 */
    val padSizeDp: Int = 220,
    /** 轮盘不透明度 0..1。 */
    val padAlpha: Float = 1f
)

object ThemePackStore {

    const val EXTENSION = ".mptheme"

    /** 用 zip 的 mime：系统文件选择器才不会把它当文本打开。 */
    const val MIME = "application/zip"

    private const val MANIFEST = "theme.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** 把当前外观收成一份主题包（导出前调用）。 */
    fun snapshot(prefs: Prefs, padPrefs: PadPrefs): ThemePack = ThemePack(
        name = "当前主题",
        theme = prefs.theme,
        accent = prefs.accentCustom ?: Prefs.toHex(Prefs.accentColor(prefs.accent)),
        animations = prefs.animations,
        padSizeDp = padPrefs.actionPadSizeDp,
        padAlpha = padPrefs.actionPadAlpha
    )

    /** 写到 SAF 给的 uri（用户在系统面板里选的位置）。 */
    suspend fun write(
        prefs: Prefs,
        padPrefs: PadPrefs,
        resolver: ContentResolver,
        uri: Uri
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val pack = snapshot(prefs, padPrefs)
            val stream = resolver.openOutputStream(uri) ?: error("打不开目标文件")
            stream.use { raw ->
                ZipOutputStream(raw).use { zip ->
                    zip.putNextEntry(ZipEntry(MANIFEST))
                    zip.write(
                        json.encodeToString(ThemePack.serializer(), pack).toByteArray(Charsets.UTF_8)
                    )
                    zip.closeEntry()
                }
            }
            Unit
        }
    }

    /** 从 uri 读主题包。不是 zip / 没有 theme.json / json 坏，都走 failure。 */
    suspend fun read(resolver: ContentResolver, uri: Uri): Result<ThemePack> =
        withContext(Dispatchers.IO) {
            runCatching {
                val stream = resolver.openInputStream(uri) ?: error("打不开文件")
                var manifest: String? = null
                // 允许 theme.json 在子目录里：别人从别处打包时可能多套一层文件夹。
                ZipInputStream(stream.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.substringAfterLast('/') == MANIFEST) {
                            manifest = zip.readBytes().toString(Charsets.UTF_8)
                            break
                        }
                        entry = zip.nextEntry
                    }
                }
                val text = manifest ?: error("不是有效的 .mptheme：包里没有 theme.json")
                json.decodeFromString(ThemePack.serializer(), text)
            }
        }

    /** 校验：返回 null 表示能应用，否则是可展示给用户的理由。 */
    fun problem(pack: ThemePack): String? = when {
        pack.format != 1 -> "这个主题包是第 " + pack.format + " 版格式，当前只认识第 1 版"
        pack.padSizeDp !in 96..400 -> "轮盘尺寸 " + pack.padSizeDp + "dp 超出范围（96–400）"
        pack.padAlpha < 0f || pack.padAlpha > 1f -> "轮盘透明度要在 0 到 1 之间"
        pack.accent.isBlank() -> "缺少主色"
        else -> null
    }

    /** 应用到偏好。调用前先 [problem] 校验。 */
    fun apply(pack: ThemePack, prefs: Prefs, padPrefs: PadPrefs) {
        prefs.applyTheme(
            when (pack.theme) {
                Prefs.THEME_SYSTEM -> Prefs.THEME_SYSTEM
                Prefs.THEME_DARK -> Prefs.THEME_DARK
                else -> Prefs.THEME_LIGHT
            }
        )
        val accent = pack.accent.trim()
        if (accent.startsWith("#")) {
            prefs.applyAccentCustom(accent.uppercase())
        } else {
            prefs.applyAccent(accent.lowercase())
        }
        prefs.applyAnimations(pack.animations)
        // PadPrefs 的 setter 自带范围钳制，这里不用再 coerce。
        padPrefs.actionPadSizeDp = pack.padSizeDp
        padPrefs.actionPadAlpha = pack.padAlpha
    }
}

package dev.mineport.launcher.ui.prefs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 界面偏好（UX-18）。只用 SharedPreferences：这里全是十几个标量键，
 * DataStore 的异步 Flow 在这一层是纯负担——左侧导航首帧就要知道用浅色还是
 * 深色，异步读会先闪一下默认色再跳。
 *
 * 每个字段都是 Compose 可观察状态：改值即重组，写入即时落盘。
 * 进程级单例放在 [dev.mineport.launcher.AppContainer] 里，界面各处读同一个实例。
 *
 * 注意与 `input/PadPrefs` 的分工：那个存「局内操作的物理参数」（摇杆半径、
 * 轮盘位置、8 槽绑定），这里存「外观」。
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("mineport-ui", Context.MODE_PRIVATE)

    /** 主题：system / light / dark。默认浅色（UX-15 定案）。 */
    var theme: String by mutableStateOf(sp.getString(KEY_THEME, THEME_LIGHT) ?: THEME_LIGHT)
        private set

    /** 主色调：blue / green / purple / orange / pink。只换强调色，背景与卡片保持中性。 */
    var accent: String by mutableStateOf(sp.getString(KEY_ACCENT, ACCENT_BLUE) ?: ACCENT_BLUE)
        private set

    /**
     * 自定义主色（`#RRGGBB`）。null 表示用 [accent] 指向的预设色。
     *
     * 存在的理由：UX-19 的 `.mptheme` 主题包要能带任意配色进来，
     * 五个预设装不下别人的主题；选预设时这里会被清空。
     */
    var accentCustom: String? by mutableStateOf(sp.getString(KEY_ACCENT_CUSTOM, null))
        private set

    /** 界面动画总开关。关掉后页面切换、列表入场立刻完成（省电/无障碍）。 */
    var animations: Boolean by mutableStateOf(sp.getBoolean(KEY_ANIM, true))
        private set

    fun applyTheme(value: String) {
        if (value == theme) return
        theme = value
        sp.edit().putString(KEY_THEME, value).apply()
    }

    fun applyAccent(value: String) {
        // 选预设即放弃自定义色，否则个性化页会显示两个「选中」。
        if (value != accent || accentCustom != null) {
            accent = value
            accentCustom = null
            sp.edit().putString(KEY_ACCENT, value).remove(KEY_ACCENT_CUSTOM).apply()
        }
    }

    /** 主题包导入用：设任意主色（`#RRGGBB`）。传 null 回到预设。 */
    fun applyAccentCustom(hex: String?) {
        if (hex == accentCustom) return
        accentCustom = hex
        sp.edit().putString(KEY_ACCENT_CUSTOM, hex).apply()
    }

    fun applyAnimations(value: Boolean) {
        if (value == animations) return
        animations = value
        sp.edit().putBoolean(KEY_ANIM, value).apply()
    }

    /** 浅色/深色最终落点：system 跟随系统，其余按用户选择。 */
    fun darkTheme(systemDark: Boolean): Boolean = when (theme) {
        THEME_DARK -> true
        THEME_LIGHT -> false
        else -> systemDark
    }

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        const val ACCENT_BLUE = "blue"
        const val ACCENT_GREEN = "green"
        const val ACCENT_PURPLE = "purple"
        const val ACCENT_ORANGE = "orange"
        const val ACCENT_PINK = "pink"

        /** 个性化页按这个顺序排主色圆点。 */
        val ACCENTS = listOf(ACCENT_BLUE, ACCENT_GREEN, ACCENT_PURPLE, ACCENT_ORANGE, ACCENT_PINK)

        private const val KEY_THEME = "theme"
        private const val KEY_ACCENT = "accent"
        private const val KEY_ACCENT_CUSTOM = "accentCustom"
        private const val KEY_ANIM = "animations"

        /** 主色在浅色主题下的取色（UX-18：蓝/绿/紫/橙/粉）；`hex` 非空时优先用它。 */
        fun accentColor(
            name: String,
            hex: String? = null
        ): androidx.compose.ui.graphics.Color {
            if (hex != null) parseHex(hex)?.let { return it }
            return when (name) {
                ACCENT_GREEN -> androidx.compose.ui.graphics.Color(0xFF16B88C)
                ACCENT_PURPLE -> androidx.compose.ui.graphics.Color(0xFF7C5CFF)
                ACCENT_ORANGE -> androidx.compose.ui.graphics.Color(0xFFF08A24)
                ACCENT_PINK -> androidx.compose.ui.graphics.Color(0xFFE0528F)
                else -> androidx.compose.ui.graphics.Color(0xFF1E5EFF)
            }
        }

        /** `#RRGGBB` / `#AARRGGBB` / 不带 # 都认；非法返回 null，调用方退回预设。 */
        fun parseHex(hex: String): androidx.compose.ui.graphics.Color? {
            val raw = hex.trim().removePrefix("#")
            if (raw.length != 6 && raw.length != 8) return null
            val value = raw.toLongOrNull(16) ?: return null
            val argb = if (raw.length == 6) 0xFF000000L or value else value
            return androidx.compose.ui.graphics.Color(argb.toInt())
        }

        /** 反过来：把当前主色写成 `#RRGGBB`，导出主题包用。 */
        fun toHex(color: androidx.compose.ui.graphics.Color): String =
            String.format("#%06X", 0xFFFFFF and color.toArgb())

        fun accentLabel(name: String): String = when (name) {
            ACCENT_GREEN -> "青绿"
            ACCENT_PURPLE -> "紫"
            ACCENT_ORANGE -> "橙"
            ACCENT_PINK -> "粉"
            else -> "蓝"
        }
    }
}

package dev.mineport.launcher.ui.prefs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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

    /** 界面动画总开关。关掉后页面切换、列表入场立刻完成（省电/无障碍）。 */
    var animations: Boolean by mutableStateOf(sp.getBoolean(KEY_ANIM, true))
        private set

    fun applyTheme(value: String) {
        if (value == theme) return
        theme = value
        sp.edit().putString(KEY_THEME, value).apply()
    }

    fun applyAccent(value: String) {
        if (value == accent) return
        accent = value
        sp.edit().putString(KEY_ACCENT, value).apply()
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
        private const val KEY_ANIM = "animations"

        /** 主色在浅色主题下的取色（UX-18：蓝/绿/紫/橙/粉）。 */
        fun accentColor(name: String): androidx.compose.ui.graphics.Color = when (name) {
            ACCENT_GREEN -> androidx.compose.ui.graphics.Color(0xFF16B88C)
            ACCENT_PURPLE -> androidx.compose.ui.graphics.Color(0xFF7C5CFF)
            ACCENT_ORANGE -> androidx.compose.ui.graphics.Color(0xFFF08A24)
            ACCENT_PINK -> androidx.compose.ui.graphics.Color(0xFFE0528F)
            else -> androidx.compose.ui.graphics.Color(0xFF1E5EFF)
        }

        fun accentLabel(name: String): String = when (name) {
            ACCENT_GREEN -> "青绿"
            ACCENT_PURPLE -> "紫"
            ACCENT_ORANGE -> "橙"
            ACCENT_PINK -> "粉"
            else -> "蓝"
        }
    }
}

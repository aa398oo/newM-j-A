package dev.mineport.launcher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

// 色板来自 docs/UX-BACKLOG.md「UX-15 蓝白配色」：
// 品牌主蓝 #1E5EFF，浅色背景 #F4F8FF → #E8F0FF，卡片纯白 + 1dp #E3EAF5 细边框。
// 旧版的信号青绿 #22D3A6 降级为「可启动状态」的强调色：保留一点绿，和主蓝形成对比，
// 但不再当品牌色用（和 FCL 的草绿也拉开了距离）。
private val BrandBlue = Color(0xFF1E5EFF)
private val SoftBlue = Color(0xFFE8F0FF)
private val CardBorder = Color(0xFFE3EAF5)
private val AccentGreen = Color(0xFF22D3A6)
private val Warning = Color(0xFFFFB020)
private val Danger = Color(0xFFEF4444)

private val Ink = Color(0xFF101418)
private val InkSecondary = Color(0xFF53606C)

// 深色仍是原来的 #0E1116 体系（UX-15：游戏内 HUD 必须保持深色）。
private val DarkBackground = Color(0xFF0E1116)
private val DarkSurface = Color(0xFF161B22)
private val DarkSurfaceElevated = Color(0xFF1F2630)
private val TextPrimary = Color(0xFFE6EDF3)
private val TextSecondary = Color(0xFF8B98A5)

/**
 * 浅色板：主色由用户选（UX-18），其余中性色固定。
 *
 * onPrimary 按主色亮度自动选黑/白字：橙色 #F08A24 上白字对比度不够，
 * 而 M3 默认会把 onPrimary 写死，换色后就看不见了。
 */
private fun lightScheme(accent: Color): androidx.compose.material3.ColorScheme = lightColorScheme(
    primary = accent,
    onPrimary = if (accent.luminance() > 0.55f) Color(0xFF1A1206) else Color.White,
    primaryContainer = lerp(accent, Color.White, 0.88f),
    onPrimaryContainer = lerp(accent, Color.Black, 0.45f),
    secondary = AccentGreen,
    onSecondary = Color(0xFF04211A),
    // FilterChip / 分段控件选中态默认吃 secondaryContainer；不覆盖会掉回 M3 的默认淡紫。
    secondaryContainer = lerp(accent, Color.White, 0.88f),
    onSecondaryContainer = lerp(accent, Color.Black, 0.25f),
    tertiary = Warning,
    onTertiary = Color(0xFF2A1B00),
    tertiaryContainer = lerp(Warning, Color.White, 0.84f),
    onTertiaryContainer = Color(0xFF3A2600),
    error = Danger,
    onError = Color.White,
    background = Color(0xFFF4F8FF),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = SoftBlue,
    onSurfaceVariant = InkSecondary,
    // M3 1.2+ 的 Card 默认容器色是 surfaceContainerHighest；不显式给值会沿用库里的淡紫，
    // 这里是「卡片为什么发紫」的根因。整组都按蓝白灰阶给。
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFCFF),
    surfaceContainer = Color(0xFFF1F6FE),
    surfaceContainerHigh = Color(0xFFEAF1FC),
    surfaceContainerHighest = Color(0xFFE8F0FF),
    outline = CardBorder,
    outlineVariant = Color(0xFFEFF3FA)
)

/** 深色板：主色统一提亮一档，否则 #1E5EFF 落在 #0E1116 上几乎看不出选中态。 */
private fun darkScheme(accent: Color): androidx.compose.material3.ColorScheme = darkColorScheme(
    primary = lerp(accent, Color.White, 0.32f),
    onPrimary = Color(0xFF04102B),
    primaryContainer = Color(0xFF12213D),
    onPrimaryContainer = Color(0xFFD6E2FF),
    secondary = AccentGreen,
    onSecondary = Color(0xFF04211A),
    secondaryContainer = Color(0xFF123528),
    onSecondaryContainer = Color(0xFFA7F3D8),
    tertiary = Warning,
    onTertiary = Color(0xFF2A1B00),
    tertiaryContainer = Color(0xFF3A2600),
    onTertiaryContainer = Color(0xFFFFE0A8),
    error = Danger,
    onError = Color.Black,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = Color(0xFF0B0F13),
    surfaceContainerLow = Color(0xFF12171D),
    surfaceContainer = Color(0xFF161B22),
    surfaceContainerHigh = Color(0xFF1B222B),
    surfaceContainerHighest = Color(0xFF1F2630),
    outline = Color(0xFF2A3240),
    outlineVariant = Color(0xFF232B36)
)

/**
 * 等宽数字样式（DESIGN-STYLE 四：数字与参数用等宽）。
 *
 * 用途：帧率、内存、版本号、下载速度、坐标。位数变化时不左右抖，是「科技感」的关键细节。
 * 用法：`Text(..., style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily))`
 */
val MonoFontFamily: FontFamily = FontFamily.Monospace

/** 便捷写法：在已有样式上叠加等宽字体。 */
fun TextStyle.mono(): TextStyle = copy(fontFamily = MonoFontFamily)

@Composable
fun MineportTheme(
    /** 默认浅色（UX-15）；具体用哪套由 ui/prefs/Prefs 决定，system 时调用方传系统值。 */
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** 用户选的主色，只影响强调色，背景与卡片保持中性。 */
    accent: Color = BrandBlue,
    /** 品牌色是识别度的一部分：默认不跟随系统取色，第三方 ROM 的壁纸配色会把它冲掉。 */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkScheme(accent)
        else -> lightScheme(accent)
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

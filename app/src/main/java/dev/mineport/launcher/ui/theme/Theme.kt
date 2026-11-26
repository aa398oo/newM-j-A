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
import androidx.compose.ui.platform.LocalContext

// 色板来自 docs/DESIGN-STYLE.md 第二节。主色是信号青绿 #22D3A6，
// 刻意不使用 FCL 的草绿，避免被认成同一个产品。
private val BackgroundBase = Color(0xFF0E1116)
private val Surface = Color(0xFF161B22)
private val SurfaceElevated = Color(0xFF1F2630)
private val Primary = Color(0xFF22D3A6)
private val Accent = Color(0xFFFFB020)
private val Danger = Color(0xFFFF5C5C)
private val Success = Color(0xFF4ADE80)
private val TextPrimary = Color(0xFFE6EDF3)
private val TextSecondary = Color(0xFF8B98A5)

private val DarkColors = darkColorScheme(
    primary = Primary,
    onPrimary = BackgroundBase,
    secondary = Accent,
    onSecondary = BackgroundBase,
    tertiary = Success,
    error = Danger,
    background = BackgroundBase,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = Color(0x0FFFFFFF)
)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color(0xFF04211A),
    secondary = Accent,
    onSecondary = Color(0xFF2A1B00),
    tertiary = Success,
    error = Danger,
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF101418),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101418),
    surfaceVariant = Color(0xFFEDF0F4),
    onSurfaceVariant = Color(0xFF53606C)
)

@Composable
fun MineportTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** 品牌色是识别度的一部分：默认不跟随系统取色，第三方 ROM 的壁纸配色会把它冲掉。 */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

package dev.mineport.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import dev.mineport.launcher.game.GameActivity
import dev.mineport.launcher.ui.RootScreen
import dev.mineport.launcher.ui.motion.LocalAnimationsEnabled
import dev.mineport.launcher.ui.prefs.Prefs
import dev.mineport.launcher.ui.theme.MineportTheme

/**
 * 启动器主界面。全站横屏（UX-14）：横屏高度只有约 400dp，顶部/底部导航都在
 * 抢最宝贵的高度，所以导航改成左侧 rail，细节见 [RootScreen]。
 *
 * 旋转不重建 Activity（manifest 里 configChanges 带 orientation|screenSize）：
 * 这套界面没有需要重新解析的资源配置，重建只会让导航状态与滚动位置复位。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 调试入口：adb shell am start -n dev.mineport.launcher/.MainActivity \
        //   --es launch_instance <instance-id>
        // 用于免触摸启动游戏，方便自动化验证（正常使用不受影响）。
        intent.getStringExtra("launch_instance")?.takeIf { it.isNotBlank() }?.let { id ->
            startActivity(GameActivity.intent(this, id))
        }
        enableEdgeToEdge()

        val prefs = (application as MineportApplication).container.uiPrefs

        setContent {
            val systemDark = isSystemInDarkTheme()
            val dark = prefs.darkTheme(systemDark)
            val accent = Prefs.accentColor(prefs.accent)
            CompositionLocalProvider(LocalAnimationsEnabled provides prefs.animations) {
                MineportTheme(darkTheme = dark, accent = accent) {
                    RootScreen()
                }
            }
        }
    }
}

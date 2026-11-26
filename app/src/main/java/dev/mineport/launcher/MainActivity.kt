package dev.mineport.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.mineport.launcher.game.GameActivity
import dev.mineport.launcher.ui.RootScreen
import dev.mineport.launcher.ui.theme.MineportTheme

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
        setContent {
            MineportTheme {
                RootScreen()
            }
        }
    }
}

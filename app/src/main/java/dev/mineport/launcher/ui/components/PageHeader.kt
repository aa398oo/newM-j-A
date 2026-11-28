package dev.mineport.launcher.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 页面标题行，替代 Material 的 TopAppBar。
 *
 * 为什么不用 TopAppBar：横屏可用高只有约 375dp，TopAppBar 固定 64dp 且带状态栏 inset，
 * 四页加起来就是「标题吃掉一整行列表」。这里把标题压成 44dp 的一行，
 * 副标题（统计/说明）和操作按钮都塞进同一行，剩下的高度全给内容。
 *
 * 用法：
 * ```
 * Column(Modifier.fillMaxSize()) {
 *     PageHeader("设置", subtitle = "…", actions = { TextButton(...) { } })
 *     // 内容，自己 weight(1f)
 * }
 * ```
 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (leading == null) 20.dp else 6.dp,
                end = 20.dp,
                top = 6.dp,
                bottom = 2.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            // 这行标题直接摆在 Row 里，没有 Surface 兜底，LocalContentColor 会退回 Color.Black
            // —— 深色主题下就是「黑底黑字」，必须显式取 onSurface。
            color = MaterialTheme.colorScheme.onSurface
        )
        if (subtitle != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                // 20sp 标题与 12sp 副标题居中对齐会显得副标题「浮」在半空，
                // 往下压 3dp 更接近视觉基线。
                modifier = Modifier.padding(top = 3.dp),
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(1f))
        actions?.invoke(this)
    }
}

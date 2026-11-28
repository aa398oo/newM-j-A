package dev.mineport.launcher.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.mineport.launcher.AppContainer
import dev.mineport.launcher.BuildConfig
import dev.mineport.launcher.device.DeviceProfile
import dev.mineport.launcher.ui.motion.EnterFromBottom
import dev.mineport.launcher.ui.theme.MonoFontFamily

/**
 * 更多页（UX-14 第五项）。
 *
 * 这一项不是「垃圾桶」：从主界面挪出来的低频入口都收在这里——
 * 版本管理（原本是底部栏的一项）、账号与联机、诊断，以及关于信息。
 * 高频动作（启动 / 下载 / 设置 / 个性化）留在 rail 上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    container: AppContainer,
    onOpenInstances: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Scaffold(topBar = { TopAppBar(title = { Text("更多") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EnterFromBottom(0) {
                EntryCard {
                    MoreRow(
                        icon = Icons.Filled.Apps,
                        title = "版本管理",
                        subtitle = "导入、导出、删除本地版本",
                        onClick = onOpenInstances
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    MoreRow(
                        icon = Icons.Filled.Person,
                        title = "账号与联机",
                        subtitle = "离线 / 微软账号、局域网世界",
                        onClick = onOpenSettings
                    )
                }
            }
            EnterFromBottom(1) {
                EntryCard {
                    MoreRow(
                        icon = Icons.Filled.BugReport,
                        title = "诊断与日志",
                        subtitle = "崩溃记录、启动日志、导出诊断包",
                        onClick = onOpenSettings
                    )
                }
            }
            EnterFromBottom(2) { AboutCard(container.deviceProfile) }
        }
    }
}

@Composable
private fun EntryCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column { content() }
    }
}

@Composable
private fun MoreRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 关于：版本号 + 设备画像。等宽数字，位数变化不抖。 */
@Composable
private fun AboutCard(profile: DeviceProfile) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, cs.outline)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = cs.primary)
                Spacer(Modifier.width(10.dp))
                Text("关于 Mineport", style = MaterialTheme.typography.titleMedium)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cs.surfaceVariant, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "v${BuildConfig.VERSION_NAME}-alpha",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoFontFamily),
                    color = cs.primary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (BuildConfig.DEBUG) "debug" else "release",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                    color = cs.onSurfaceVariant
                )
            }
            InfoLine("设备", "${profile.manufacturer} ${profile.model}")
            InfoLine("SoC", "${profile.socModel} · ${profile.coreCount} 核")
            InfoLine("内存", "${profile.totalRamMB} MB" + if (profile.isLowRamDevice) " · 低内存" else "")
            InfoLine("图形", "${profile.glEsVersion} · Vulkan " + if (profile.vulkanSupported) "✓" else "✗")
            InfoLine("系统", "Android ${profile.androidRelease} (API ${profile.sdkInt}) · ${profile.abi}")
            Spacer(Modifier.height(2.dp))
            Text(
                "为 Android 定制的 Minecraft 启动器。本版本只支持 arm64 与 1.20.1 起的版本。",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

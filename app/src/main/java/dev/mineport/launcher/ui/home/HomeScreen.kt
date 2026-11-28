package dev.mineport.launcher.ui.home

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mineport.launcher.AppContainer
import dev.mineport.launcher.data.GameInstance
import dev.mineport.launcher.device.DeviceProfile
import dev.mineport.launcher.ui.motion.EnterFromBottom
import dev.mineport.launcher.ui.motion.pressScale

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenInstances: () -> Unit
) {
    val profile = container.deviceProfile
    val instances by container.instanceStore.instances.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item {
            EnterFromBottom(0) {
                Column {
                    Text("newM-j-A", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "启动器壳 + 可切换渲染后端",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { EnterFromBottom(1) { DeviceCard(profile) } }
        item { EnterFromBottom(2) { BackendCard(profile) } }
        item {
            EnterFromBottom(3) {
                val startSource = remember { MutableInteractionSource() }
                val manageSource = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenInstances,
                        modifier = Modifier.weight(1f).pressScale(startSource),
                        interactionSource = startSource
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.height(0.dp))
                        Text("  开始游戏")
                    }
                    OutlinedButton(
                        onClick = onOpenInstances,
                        modifier = Modifier.weight(1f).pressScale(manageSource),
                        interactionSource = manageSource
                    ) {
                        Text("版本管理")
                    }
                }
            }
        }

        item {
            EnterFromBottom(4) {
                Text("最近游玩", style = MaterialTheme.typography.titleMedium)
            }
        }

        if (instances.isEmpty()) {
            item {
                EnterFromBottom(5) {
                    Text(
                        "还没有版本。先去「下载」装一个原版版本，或在「版本」里手动新建。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            itemsIndexed(instances.take(5), key = { _, instance -> instance.id }) { index, instance ->
                EnterFromBottom(index + 5) {
                    RecentInstanceRow(instance, onOpenInstances)
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DeviceCard(profile: DeviceProfile) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("设备", style = MaterialTheme.typography.titleMedium)
            InfoRow("机型", profile.manufacturer + " " + profile.model)
            InfoRow("芯片", vendorName(profile) + " · " + profile.socModel)
            InfoRow("内存", profile.totalRamMB.toString() + " MB（建议堆 " + profile.recommendedMemoryMB + " MB）")
            InfoRow("系统", "Android " + profile.androidRelease + "（API " + profile.sdkInt + "）")
            InfoRow("图形", profile.glEsVersion + (if (profile.vulkanSupported) " · Vulkan " + (profile.vulkanVersion ?: "支持") else " · 无 Vulkan"))
            InfoRow("ABI", profile.abi + " · " + profile.coreCount + " 核")
        }
    }
}

@Composable
private fun BackendCard(profile: DeviceProfile) {
    val recommendation = profile.recommendedBackend
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("推荐渲染后端", style = MaterialTheme.typography.titleMedium)
            AssistChip(
                onClick = {},
                label = { Text(recommendation.backend.uppercase()) }
            )
            Text(recommendation.reason, style = MaterialTheme.typography.bodySmall)
            Text(
                "可在版本设置里覆盖。Zink 需要设备能加载 Turnip 驱动，失败会自动回退 GL4ES。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RecentInstanceRow(instance: GameInstance, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(instance.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    instance.subtitle + " · " + instance.renderBackend.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(instance.memoryMB.toString() + " MB", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.28f))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.72f))
    }
}

private fun vendorName(profile: DeviceProfile): String = when (profile.socVendor) {
    dev.mineport.launcher.device.SocVendor.QUALCOMM -> "Qualcomm"
    dev.mineport.launcher.device.SocVendor.MEDIATEK -> "MediaTek"
    dev.mineport.launcher.device.SocVendor.SAMSUNG -> "Samsung"
    dev.mineport.launcher.device.SocVendor.HUAWEI -> "HiSilicon"
    dev.mineport.launcher.device.SocVendor.UNISOC -> "Unisoc"
    dev.mineport.launcher.device.SocVendor.UNKNOWN -> "未知"
}

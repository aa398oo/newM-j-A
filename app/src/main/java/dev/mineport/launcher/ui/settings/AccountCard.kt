package dev.mineport.launcher.ui.settings

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mineport.launcher.AppContainer
import dev.mineport.launcher.auth.MicrosoftAuth
import dev.mineport.launcher.data.Account
import dev.mineport.launcher.data.AccountType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** 微软登录的界面状态：设备码已拿到 → 轮询中 → 成功/失败。 */
private sealed interface MsaUi {
    data class Device(val userCode: String, val uri: String, val pending: String) : MsaUi
    data class Failed(val message: String) : MsaUi
}

/**
 * 账号卡片：展示全部账号、切换当前账号、离线改名、微软设备码登录。
 *
 * 微软流程是阻塞式的，所以整段放在 IO 线程，UI 更新通过主线程 Handler 回投；
 * 设备码对话框一旦出现就表示「等用户去浏览器输入短码」，轮询超时/拒绝会切到失败态。
 */
@Composable
fun AccountCard(container: AppContainer) {
    val store = container.accountStore
    val accounts by store.accounts.collectAsState()
    val selectedId by store.selectedId.collectAsState()
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }

    var renaming by remember { mutableStateOf<Account?>(null) }
    var renameText by remember { mutableStateOf("") }
    var msa by remember { mutableStateOf<MsaUi?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("账号", style = MaterialTheme.typography.titleMedium)
            Text(
                "离线账号不发请求；微软账号走设备码授权，登录后启动参数会带上 uuid 与令牌。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            accounts.forEach { account ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.padding(end = 8.dp)) {
                        Text(
                            account.name + if (account.id == selectedId) "  ✓" else "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            account.type.displayName + " · " + account.uuid.take(8),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        if (account.id != selectedId) {
                            TextButton(onClick = { scope.launch { store.select(account.id) } }) {
                                Text("切换")
                            }
                        }
                        if (account.type == AccountType.OFFLINE) {
                            TextButton(onClick = {
                                renaming = account
                                renameText = account.name
                            }) { Text("改名") }
                        }
                        if (accounts.size > 1) {
                            TextButton(onClick = { scope.launch { store.remove(account.id) } }) {
                                Text("删除")
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    scope.launch {
                        store.add(Account(name = "Player" + (accounts.size + 1)))
                    }
                }) { Text("新建离线账号") }

                TextButton(onClick = {
                    msa = MsaUi.Device("…", "https://microsoft.com/link", "正在申请设备码")
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                MicrosoftAuth.login(
                                    onDeviceCode = { device ->
                                        main.post {
                                            msa = MsaUi.Device(
                                                device.userCode,
                                                device.verificationUri,
                                                "在浏览器打开上面的网址并输入短码"
                                            )
                                        }
                                    },
                                    onPending = { msg ->
                                        main.post {
                                            val cur = msa
                                            if (cur is MsaUi.Device) msa = cur.copy(pending = msg)
                                        }
                                    }
                                )
                            }
                        }
                        result.fold(
                            onSuccess = { profile ->
                                store.add(
                                    Account(
                                        name = profile.name,
                                        type = AccountType.MSA,
                                        uuid = formatUuid(profile.uuid),
                                        accessToken = profile.accessToken,
                                        refreshToken = profile.refreshToken,
                                        // MSA 的 Minecraft 令牌约 24 小时有效，留提前量。
                                        expiresAtEpochMs = System.currentTimeMillis() + 23 * 60 * 60 * 1000L
                                    )
                                )
                                msa = null
                            },
                            onFailure = { e ->
                                msa = MsaUi.Failed(e.message ?: e.javaClass.simpleName)
                            }
                        )
                    }
                }) { Text("微软登录") }
            }
        }
    }

    renaming?.let { target ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名离线账号") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("玩家名") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        val name = renameText.trim()
                        scope.launch {
                            // 改名 = 换 uuid（离线 uuid 由名字派生），所以新建一条再删旧的。
                            store.add(Account(name = name, type = AccountType.OFFLINE))
                            store.remove(target.id)
                        }
                        renaming = null
                    }
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } }
        )
    }

    when (val state = msa) {
        is MsaUi.Device -> AlertDialog(
            onDismissRequest = { msa = null },
            title = { Text("微软登录") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("在浏览器打开：", style = MaterialTheme.typography.bodySmall)
                    Text(state.uri, style = MaterialTheme.typography.bodyMedium)
                    Text("输入短码：", style = MaterialTheme.typography.bodySmall)
                    Text(state.userCode, style = MaterialTheme.typography.headlineSmall)
                    Text(state.pending, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { msa = null }) { Text("取消") } }
        )
        is MsaUi.Failed -> AlertDialog(
            onDismissRequest = { msa = null },
            title = { Text("登录失败") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = { msa = null }) { Text("知道了") } }
        )
        null -> {}
    }
}

/** 服务端给的 uuid 没有连字符，写成 8-4-4-4-12 便于和存档/皮肤核对。 */
private fun formatUuid(raw: String): String {
    val hex = raw.replace("-", "")
    if (hex.length != 32) return hex
    return runCatching { UUID.fromString(
        hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" +
            hex.substring(16, 20) + "-" + hex.substring(20)
    ).toString() }.getOrDefault(hex)
}

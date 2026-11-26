package dev.mineport.launcher.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/** 账号类型。离线账号不发任何网络请求；MSA 走微软 OAuth 设备码。 */
@Serializable
enum class AccountType {
    @SerialName("offline") OFFLINE,
    @SerialName("msa") MSA;

    val displayName: String
        get() = when (this) {
            OFFLINE -> "离线"
            MSA -> "微软账号"
        }
}

/**
 * 一个登录账号。
 *
 * 离线账号只需要 [name]（uuid 由名字派生，与原版离线模式一致）；
 * 微软账号额外持有 [uuid] / [accessToken] / [refreshToken] / [expiresAtEpochMs]。
 *
 * **注意**：阶段一先把令牌以明文存进 filesDir/accounts.json（与实例同一套存储），
 * 后续需要用 Android Keystore 对 refreshToken 做加密封装（见台账 P2-01 待办）。
 */
@Serializable
data class Account(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: AccountType = AccountType.OFFLINE,
    val uuid: String = offlineUuid(name),
    val accessToken: String = "0",
    val refreshToken: String = "",
    /** MSA 令牌过期时间（epoch ms）；离线账号恒为 0。 */
    val expiresAtEpochMs: Long = 0L,
    val lastUsedEpochMs: Long = 0L
) {
    val isMs: Boolean get() = type == AccountType.MSA

    /** 原版离线模式用 name 的 MD5 派生 uuid，这里保持同一算法才能和存档/皮肤对齐。 */
    companion object {
        fun offlineUuid(name: String): String {
            val digest = java.security.MessageDigest.getInstance("MD5")
                .digest(("OfflinePlayer:" + name).toByteArray(Charsets.UTF_8))
            val bytes = digest.copyOf(16)
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x30).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            val sb = StringBuilder(36)
            for (i in bytes.indices) {
                if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
                sb.append("%02x".format(bytes[i]))
            }
            return sb.toString()
        }
    }
}

/**
 * 账号清单的持久化。与 [InstanceStore] 同一套思路：单 JSON 文件 + StateFlow。
 * 首次启动时若没有任何账号，自动建一个离线账号（沿用实例里的默认玩家名）。
 */
class AccountStore(context: Context) {

    private val file = File(context.filesDir, "accounts.json")
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    /** 当前选中的账号 id；空表示沿用实例里的 playerName。 */
    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    val current: Account?
        get() = _accounts.value.firstOrNull { it.id == _selectedId.value }
            ?: _accounts.value.firstOrNull()

    fun loadBlocking(defaultName: String = "Steve") {
        val list = if (file.exists()) {
            runCatching {
                json.decodeFromString(ListSerializer(Account.serializer()), file.readText())
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val effective = if (list.isEmpty()) listOf(Account(name = defaultName)) else list
        _accounts.value = effective.sortedByDescending { it.lastUsedEpochMs }
        _selectedId.value = _accounts.value.firstOrNull()?.id
        if (list.isEmpty()) persistBlocking(_accounts.value)
    }

    suspend fun load(defaultName: String = "Steve") = mutex.withLock {
        withContext(Dispatchers.IO) {
            val list = if (file.exists()) {
                runCatching {
                    json.decodeFromString(ListSerializer(Account.serializer()), file.readText())
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val effective = if (list.isEmpty()) listOf(Account(name = defaultName)) else list
            _accounts.value = effective.sortedByDescending { it.lastUsedEpochMs }
            _selectedId.value = _accounts.value.firstOrNull()?.id
            if (list.isEmpty()) write(effective)
        }
    }

    suspend fun add(account: Account) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val next = _accounts.value.filterNot { it.id == account.id } + account
            _accounts.value = next.sortedByDescending { it.lastUsedEpochMs }
            _selectedId.value = account.id
            write(_accounts.value)
        }
    }

    suspend fun remove(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val next = _accounts.value.filterNot { it.id == id }
            val effective = if (next.isEmpty()) listOf(Account(name = "Steve")) else next
            _accounts.value = effective
            if (_selectedId.value == id) _selectedId.value = effective.first().id
            write(effective)
        }
    }

    suspend fun select(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            _selectedId.value = id
            val now = System.currentTimeMillis()
            _accounts.value = _accounts.value.map { if (it.id == id) it.copy(lastUsedEpochMs = now) else it }
            write(_accounts.value)
        }
    }

    /** 更新令牌（MSA 刷新后调用）。 */
    suspend fun update(account: Account) = mutex.withLock {
        withContext(Dispatchers.IO) {
            _accounts.value = _accounts.value.map { if (it.id == account.id) account else it }
            write(_accounts.value)
        }
    }

    private fun persistBlocking(list: List<Account>) {
        runCatching { write(list) }
    }

    private fun write(list: List<Account>) {
        file.parentFile?.mkdirs()
        // 原子写：先写临时文件再 rename。直接覆盖时进程被杀会留下截断的 JSON，
        // 下次 load 会把解析失败静默吞掉并重建默认账号，用户的微软账号就全丢了。
        val text = json.encodeToString(ListSerializer(Account.serializer()), list)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                throw IOException("accounts.json rename failed")
            }
        }
    }
}

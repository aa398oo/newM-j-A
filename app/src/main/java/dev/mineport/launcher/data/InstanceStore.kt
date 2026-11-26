package dev.mineport.launcher.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 实例清单的持久化。用单个 JSON 文件而不是数据库：
 * 实例数量在几十个量级，读写整表足够快，也便于用户手工备份。
 */
class InstanceStore(context: Context) {

    private val file = File(context.filesDir, "instances.json")
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val _instances = MutableStateFlow<List<GameInstance>>(emptyList())
    val instances: StateFlow<List<GameInstance>> = _instances.asStateFlow()

    suspend fun load() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val list = if (file.exists()) {
                runCatching {
                    json.decodeFromString(ListSerializer(GameInstance.serializer()), file.readText())
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            _instances.value = list.sortedByDescending { it.lastPlayedEpochMs }
        }
    }

    /**
     * 进程启动时同步读一次。:game 是独立进程，GameActivity 在 onCreate 里
     * 就要拿到实例（决定 Java 版本、内存、渲染后端），等不了 IO 协程；
     * instances.json 只有几 KB，主线程读一次的代价可以接受。
     */
    fun loadBlocking() {
        val list = if (file.exists()) {
            runCatching {
                json.decodeFromString(ListSerializer(GameInstance.serializer()), file.readText())
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        _instances.value = list.sortedByDescending { it.lastPlayedEpochMs }
    }

    suspend fun upsert(instance: GameInstance) = mutate { list ->
        val index = list.indexOfFirst { it.id == instance.id }
        if (index >= 0) list.toMutableList().also { it[index] = instance } else list + instance
    }

    suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    suspend fun markPlayed(id: String) = mutate { list ->
        list.map { if (it.id == id) it.copy(lastPlayedEpochMs = System.currentTimeMillis()) else it }
    }

    private suspend fun mutate(transform: (List<GameInstance>) -> List<GameInstance>) = mutex.withLock {
        val updated = transform(_instances.value)
        _instances.value = updated.sortedByDescending { it.lastPlayedEpochMs }
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(ListSerializer(GameInstance.serializer()), _instances.value))
        }
    }
}

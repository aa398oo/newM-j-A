package dev.mineport.launcher.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Mojang 的 version_manifest_v2.json 子集（多余字段忽略）。 */
@Serializable
data class VersionManifest(
    val latest: Latest,
    val versions: List<VersionEntry>
)

@Serializable
data class Latest(
    val release: String,
    val snapshot: String
)

@Serializable
data class VersionEntry(
    val id: String,
    val type: String,
    val url: String,
    val time: String = "",
    val releaseTime: String = ""
)

/**
 * 版本清单客户端（DOWNLOADS.md 三级降级的最小实现）。
 *
 * 顺序：BMCLAPI 镜像 → Mojang 官方 launchermeta → piston-meta。
 * 全部失败时退回上次成功缓存（缓存文件在 filesDir/cache/ 下），保证离线也能看到列表。
 */
object VersionManifestClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val sources = listOf(
        "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json",
        "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json",
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    )

    sealed interface Result {
        data class Ok(val manifest: VersionManifest, val source: String, val fromCache: Boolean) : Result
        data class Fail(val message: String) : Result
    }

    suspend fun load(cacheFile: File): Result = withContext(Dispatchers.IO) {
        var lastError = "未知错误"
        for (url in sources) {
            try {
                val text = httpGet(url)
                val manifest = json.decodeFromString<VersionManifest>(text)
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(text)
                return@withContext Result.Ok(manifest, url, fromCache = false)
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }

        if (cacheFile.isFile) {
            try {
                val manifest = json.decodeFromString<VersionManifest>(cacheFile.readText())
                return@withContext Result.Ok(manifest, "缓存", fromCache = true)
            } catch (e: Exception) {
                lastError += "；缓存也读不了：" + (e.message ?: "")
            }
        }
        Result.Fail("版本清单拉取失败：$lastError")
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "newM-j-A/0.1 (launcher; +https://github.com)")
        conn.setRequestProperty("Accept-Encoding", "gzip")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val encoding = conn.contentEncoding ?: ""
            val raw = conn.inputStream
            val stream = if (encoding.contains("gzip", ignoreCase = true)) GZIPInputStream(raw) else raw
            return stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

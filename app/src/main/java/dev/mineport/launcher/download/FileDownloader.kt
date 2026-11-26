package dev.mineport.launcher.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 文件下载内核（DOWNLOADS.md 的「以文件为单位」）。
 *
 * - 多源顺序降级：主源失败自动换下一个；
 * - 断点续传：写 `<name>.part`，下次用 Range 接着下；
 * - sha1 校验：校验通过才改名为正式文件；
 * - 进度回调：(已下载字节, 总字节)，总字节未知时为 -1。
 */
object FileDownloader {

    private const val UA = "newM-j-A/0.1 (launcher)"

    suspend fun download(
        urls: List<String>,
        target: File,
        sha1: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): kotlin.Result<Unit> = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) {
            return@withContext kotlin.Result.failure(IOException("没有可用的下载地址"))
        }
        if (target.isFile && sha1 != null && sha1Of(target).equals(sha1, ignoreCase = true)) {
            return@withContext kotlin.Result.success(Unit)
        }

        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        var lastError: Exception? = null

        for (url in urls) {
            try {
                downloadOne(url, part, onProgress)
                if (sha1 != null) {
                    val actual = sha1Of(part)
                    if (!actual.equals(sha1, ignoreCase = true)) {
                        part.delete()
                        throw IOException("sha1 不匹配：期望 $sha1，实际 $actual")
                    }
                }
                if (target.isFile) target.delete()
                if (!part.renameTo(target)) {
                    target.writeBytes(part.readBytes())
                    part.delete()
                }
                return@withContext kotlin.Result.success(Unit)
            } catch (e: Exception) {
                lastError = e
            }
        }

        kotlin.Result.failure(IOException("所有源都失败：" + (lastError?.message ?: "未知错误"), lastError))
    }

    private fun downloadOne(url: String, part: File, onProgress: (Long, Long) -> Unit) {
        val existing = if (part.isFile) part.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        // 资源服务器偶尔返回 gzip；带 Range 的续传必须拿到原始字节，显式声明 identity。
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")

        try {
            val code = conn.responseCode
            val append = existing > 0 && code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $code")
            }
            val total = if (append) existing + conn.contentLengthLong else conn.contentLengthLong
            FileOutputStream(part, append).use { output ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var written = if (append) existing else 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, if (total > 0) total else -1L)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    fun sha1Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

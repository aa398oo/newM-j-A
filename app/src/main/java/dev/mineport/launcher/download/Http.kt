package dev.mineport.launcher.download

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 极简 HTTP 客户端（标准库，不引第三方网络栈）。
 *
 * 与常见封装的区别：**非 2xx 也返回响应体**。OAuth 设备码轮询在
 * `authorization_pending` 时会回 400 且错误信息只在 body 里，抛异常会丢上下文。
 */
object Http {

    private const val UA = "newM-j-A/0.1 (launcher)"

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectMs: Int = 8000,
        readMs: Int = 20000
    ): String = request("GET", url, null, null, headers, connectMs, readMs)

    /** application/x-www-form-urlencoded。form 需调用方自行编码。 */
    fun postForm(
        url: String,
        form: String,
        headers: Map<String, String> = emptyMap(),
        connectMs: Int = 8000,
        readMs: Int = 20000
    ): String = request("POST", url, form, "application/x-www-form-urlencoded", headers, connectMs, readMs)

    fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        connectMs: Int = 8000,
        readMs: Int = 20000
    ): String = request("POST", url, json, "application/json", headers, connectMs, readMs)

    private fun request(
        method: String,
        url: String,
        body: String?,
        contentType: String?,
        headers: Map<String, String>,
        connectMs: Int,
        readMs: Int
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.instanceFollowRedirects = true
        conn.requestMethod = method
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Encoding", "gzip")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            contentType?.let { conn.setRequestProperty("Content-Type", it) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { raw ->
                val encoding = conn.contentEncoding ?: ""
                val decoded = if (encoding.contains("gzip", ignoreCase = true)) GZIPInputStream(raw) else raw
                decoded.bufferedReader().use { it.readText() }
            } ?: ""
            if (code !in 200..299 && text.isBlank()) throw IOException("HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }
}

package dev.mineport.launcher.auth

import dev.mineport.launcher.download.Http
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * 微软账号登录（OAuth 2.0 设备码流程）。
 *
 * 为什么要设备码而不是 WebView 授权码：Android 上需要自定义 scheme 回调，
 * 而设备码流程只需「打开网页 + 输入短码」，对启动器更稳，也不依赖浏览器回调。
 *
 * 流程（与官方启动器一致）：
 * 1. 申请 device_code → 显示 user_code 和验证网址
 * 2. 轮询 token 端点（间隔约 5 秒，直到用户完成授权）
 * 3. MSA token → Xbox Live (XBL) → XSTS
 * 4. XSTS → Minecraft Services 换取 accessToken
 * 5. 用 accessToken 拉 profile（uuid + name）
 *
 * 所有请求走 [Http]，UA 与官方启动器保持接近，避免被服务端拒绝。
 */
object MicrosoftAuth {

    /** 官方启动器使用的公开 client id（Pojav/FCL 等第三方启动器同样使用）。 */
    private const val CLIENT_ID = "00000000402b5328"

    private const val SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"

    private const val DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf"
    private const val TOKEN_URL = "https://login.live.com/oauth20_token.srf"
    private const val XBL_URL = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox"
    private const val MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 设备码：给用户看的短码 + 内部轮询用的 device_code。 */
    data class DeviceCode(
        val userCode: String,
        val deviceCode: String,
        val verificationUri: String,
        val expiresInSec: Int,
        val intervalSec: Int
    )

    /** 登录成功后拿到的三件套。refreshToken 用于下次免交互续期。 */
    data class McProfile(
        val uuid: String,
        val name: String,
        val accessToken: String,
        val refreshToken: String = ""
    )

    /** 设备码阶段返回的服务端错误（例如 authorization_pending 表示还没点授权）。 */
    class AuthException(message: String, val code: String? = null) : Exception(message)

    @Serializable
    private data class DeviceCodeResponse(
        val user_code: String = "",
        val device_code: String = "",
        val verification_uri: String = "https://microsoft.com/link",
        @SerialName("expires_in") val expiresIn: Int = 900,
        val interval: Int = 5,
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String = "",
        @SerialName("refresh_token") val refreshToken: String = "",
        @SerialName("expires_in") val expiresIn: Long = 0,
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null
    )

    /** 第 1 步：申请设备码。 */
    fun requestDeviceCode(): DeviceCode {
        val form = listOf(
            "client_id" to CLIENT_ID,
            "scope" to SCOPE,
            "response_type" to "device_code"
        ).joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }

        val body = Http.postForm(DEVICE_CODE_URL, form)
        val resp = json.decodeFromString(DeviceCodeResponse.serializer(), body)
        if (resp.error != null) {
            throw AuthException(resp.errorDescription ?: resp.error, resp.error)
        }
        if (resp.device_code.isBlank()) throw AuthException("设备码响应为空")
        return DeviceCode(
            userCode = resp.user_code,
            deviceCode = resp.device_code,
            verificationUri = resp.verification_uri,
            expiresInSec = resp.expiresIn,
            intervalSec = resp.interval.coerceAtLeast(2)
        )
    }

    /**
     * 第 2 步：轮询等待用户授权。
     *
     * @param onPending 每次「还没授权」时回调，可用于更新 UI 的等待秒数。
     * @return MSA 的 access_token 与 refresh_token。
     */
    fun pollForToken(
        device: DeviceCode,
        timeoutMs: Long = device.expiresInSec * 1000L,
        onPending: (String) -> Unit = {}
    ): Pair<String, String> {
        val form = listOf(
            "client_id" to CLIENT_ID,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            "device_code" to device.deviceCode
        ).joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }

        val deadline = System.currentTimeMillis() + timeoutMs
        // RFC 8628：slow_down 时把轮询间隔 +5s 并**保持加长后的值**，上限 60s。
        // 之前每轮都睡 device.intervalSec，服务端要求降频后仍按原频率打，会被继续 slow_down。
        var intervalMs = device.intervalSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(intervalMs)
            val resp = runCatching {
                json.decodeFromString(TokenResponse.serializer(), Http.postForm(TOKEN_URL, form))
            }.getOrNull()
            if (resp == null) {
                onPending("网络异常，重试中")
                continue
            }
            when (resp.error) {
                null -> if (resp.accessToken.isNotBlank()) {
                    return resp.accessToken to resp.refreshToken
                }
                "authorization_pending" -> onPending("等待在浏览器中完成授权")
                "slow_down" -> {
                    intervalMs = (intervalMs + 5000L).coerceAtMost(60_000L)
                    onPending("请求过快，已降到 " + (intervalMs / 1000) + " 秒一次")
                }
                "expired_token" -> throw AuthException("设备码已过期，请重新登录", resp.error)
                "authorization_declined" -> throw AuthException("用户拒绝了授权", resp.error)
                else -> throw AuthException(resp.errorDescription ?: resp.error, resp.error)
            }
        }
        throw AuthException("登录超时（未在有效期内完成授权）")
    }

    /**
     * 用 refresh_token 静默续期（不需要用户再点授权）。
     * 返回新的 MSA access_token 与 refresh_token（微软会轮换 refresh_token，必须存新的）。
     */
    fun refreshMsaToken(refreshToken: String): Pair<String, String> {
        val form = listOf(
            "client_id" to CLIENT_ID,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "scope" to SCOPE
        ).joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val resp = runCatching {
            json.decodeFromString(TokenResponse.serializer(), Http.postForm(TOKEN_URL, form))
        }.getOrElse { throw AuthException("刷新令牌失败：" + (it.message ?: "网络异常")) }
        if (resp.accessToken.isBlank()) {
            throw AuthException(resp.errorDescription ?: "刷新令牌被拒绝", resp.error)
        }
        return resp.accessToken to resp.refreshToken.ifBlank { refreshToken }
    }

    /** 第 3 步：MSA → Xbox Live。 */
    private fun authenticateXbl(msaToken: String): String {
        val payload = buildJsonObject {
            put("Properties", buildJsonObject {
                put("AuthMethod", "RPS")
                put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", "d=$msaToken")
            })
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
        }.toString()
        val body = Http.postJson(XBL_URL, payload, mapOf("Accept" to "application/json"))
        val obj = json.parseToJsonElement(body).jsonObject
        return obj["Token"]?.jsonPrimitive?.content ?: throw AuthException("XBL 未返回 Token")
    }

    /** 第 4 步：XBL → XSTS，返回 (token, userHash)。 */
    private fun authenticateXsts(xblToken: String): Pair<String, String> {
        val payload = buildJsonObject {
            put("Properties", buildJsonObject { put("SandboxId", "RETAIL") })
            put("RelyingParty", "rp://api.minecraftservices.com/")
            put("TokenType", "JWT")
            put("Tokens", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(xblToken)) })
        }.toString()
        val body = Http.postJson(XSTS_URL, payload, mapOf("Accept" to "application/json"))
        val obj = json.parseToJsonElement(body).jsonObject
        val token = obj["Token"]?.jsonPrimitive?.content ?: throw AuthException("XSTS 未返回 Token")
        val claims = obj["DisplayClaims"]?.jsonObject
            ?.get("xui")?.let { it as? kotlinx.serialization.json.JsonArray }
            ?.firstOrNull()?.jsonObject?.get("uhs")?.jsonPrimitive?.content
            ?: throw AuthException("XSTS 未返回 uhs")
        return token to claims
    }

    /** 第 5 步：XSTS → Minecraft accessToken。 */
    private fun loginWithXbox(xstsToken: String, uhs: String): String {
        val payload = buildJsonObject { put("identityToken", "XBL3.0 x=$uhs;$xstsToken") }.toString()
        val body = Http.postJson(MC_LOGIN_URL, payload, mapOf("Accept" to "application/json"))
        val obj = json.parseToJsonElement(body).jsonObject
        return obj["access_token"]?.jsonPrimitive?.content ?: throw AuthException("Minecraft 登录未返回 access_token")
    }

    /** 第 6 步：用 accessToken 拉 profile（uuid 无连字符）。 */
    private fun fetchProfile(accessToken: String): Pair<String, String> {
        val body = Http.get(MC_PROFILE_URL, mapOf("Authorization" to "Bearer $accessToken"))
        val obj = json.parseToJsonElement(body).jsonObject
        val id = obj["id"]?.jsonPrimitive?.content ?: throw AuthException("profile 缺少 id")
        val name = obj["name"]?.jsonPrimitive?.content ?: throw AuthException("profile 缺少 name")
        return id to name
    }

    /**
     * 完整登录（阻塞调用，放到 IO 线程）。
     * @param onDeviceCode 拿到设备码后回调，UI 显示短码与网址。
     * @param onPending 轮询中的状态回调。
     */
    fun login(
        onDeviceCode: (DeviceCode) -> Unit,
        onPending: (String) -> Unit = {}
    ): McProfile {
        val device = requestDeviceCode()
        onDeviceCode(device)
        val (msaToken, refreshToken) = pollForToken(device, onPending = onPending)
        val xbl = authenticateXbl(msaToken)
        val (xsts, uhs) = authenticateXsts(xbl)
        val mcToken = loginWithXbox(xsts, uhs)
        val (uuid, name) = fetchProfile(mcToken)
        return McProfile(uuid = uuid, name = name, accessToken = mcToken, refreshToken = refreshToken)
    }
}

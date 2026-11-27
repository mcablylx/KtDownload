/*
 * Ported from YunX-Desktop — https://github.com/tidain/YunX-Desktop
 * Upstream: main branch @ 00a030f7 (tag v1.3.4, 2026-11-13).
 * Copyright (C) the YunX-Desktop contributors.
 * License: GNU Affero General Public License v3.0 (AGPL-3.0).
 * Full text: third-party/yunx-desktop/LICENSE.txt and THIRD-PARTY-NOTICES.md.
 * This file is a derivative work: namespace isolated, app services shimmed.
 */
package top.mcly99.dl.data.network

import top.mcly99.dl.data.network.model.DownloadLink
import top.mcly99.dl.data.network.model.QuotaInfo
import top.mcly99.dl.data.network.model.ShareExpire
import top.mcly99.dl.data.network.model.ShareFile
import top.mcly99.dl.data.network.model.ShareInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.security.MessageDigest
import kotlin.random.Random

/** 迅雷分享解析结果 */
data class XunleiShareResult(
    val title: String,
    val files: List<ShareFile>,
    val passCodeToken: String,
    val shareId: String,
    val nextPageToken: String = ""
)

data class XunleiFilePage(val files: List<ShareFile>, val nextPageToken: String)

/** 迅雷登录中间结果 */
data class XunleiLoginStep(
    val needSms: Boolean = false,     // 是否需要短信验证
    val smsCreditKey: String = "",    // sendsms 返回的 creditkey
    val smsToken: String = "",        // sendsms 返回的 token
    val sessionKey: String = "",      // 登录成功的会话（loginKey）
    val sessionId: String = "",       // 登录成功的 sessionID（换 token 用 signin_token）
    val nickname: String = "",
    val userID: String = "",
    val reviewUrl: String = "",       // review_panel 返回的验证页 URL（可浏览器兜底）
    val message: String = ""
)

/**
 * 一次 captcha/init 的结果：token + 过期时间。
 *
 * ★ 必须带过期时间：网页 token 只有 300s，缓存里拿死 token 去请求会先吃一次 `captcha_invalid`，
 * 等于每次解析都先失败一次再重试。
 */
data class PanCaptcha(val token: String, val expiresAtMs: Long) {
    fun isValid(now: Long = System.currentTimeMillis()): Boolean = token.isNotBlank() && expiresAtMs > now
}

/**
 * 网页签名换 captcha 的结果。
 *
 * ★ 必须区分「签名被拒（盐/版本轮换）」与「client_id 配错」：后者回退网页会话也救不回来
 * （网页会话同样只认网页 client_id）。[Failed] 带 [WebCaptchaFailure] 是为了把原因透传到 UI：
 * 1.0.3 把所有失败压成 null，用户只看到笼统的「人机验证失败」，不知道该重新登录还是该等限流。
 */
sealed interface WebCaptchaInit {
    data class Ok(val captcha: PanCaptcha) : WebCaptchaInit
    data object ClientIdMismatch : WebCaptchaInit

    /**
     * @param retryAfterMs 服务端 `Retry-After` 给出的等待时间（毫秒）；0 表示「没给或解析不出」，
     *   由调用方用默认冷却。P2-08：只判到 `RATE_LIMITED` 而不带等待时长，UI 只能说「稍后重试」，
     *   用户往往几秒后连点，反而把限流窗口越拉越长。
     */
    data class Failed(val reason: WebCaptchaFailure, val retryAfterMs: Long = 0) : WebCaptchaInit
}

/** 网页 captcha/init 的失败原因（用于错误文案与自愈决策，不含任何凭证）。 */
enum class WebCaptchaFailure {
    /** 签名被拒 / 空 token：多半是 client_version 或 9 盐已轮换，需要更新签名配置或回退网页会话 */
    SIGN_REJECTED,
    /** 429 / Retry-After：等一会儿再试，不要盲目重签 */
    RATE_LIMITED,
    /** 网络异常 / 超时 */
    NETWORK,
    /** 非 2xx 且不是限流 */
    HTTP_ERROR,
    /** client_id 与当前登录方式不匹配，只能重新登录 */
    CLIENT_ID_MISMATCH,
}

/**
 * 迅雷 API 封装（OkHttp）：
 * - 登录：captcha/init → v3/login（密码，可能触发短信）→ sendsms → smslogin → v1/auth/token
 * - Pan：文件列表 / 分享解析 / 转存 / 直链（Bearer 认证，无需 x-signature，抓包确认）
 */
class XunleiApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() },
    /**
     * 时钟（毫秒）。限流冷却与 captcha 过期都用它判定，单测注入假时钟即可验证
     * 「冷却期内不再发请求 / 冷却结束后恢复」，不需要真的 sleep（P2-08）。
     */
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    /** 每次请求动态获取全局客户端（忽略 SSL 开关切换即时生效） */
    private val client get() = clientProvider()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val formMediaType = "application/x-www-form-urlencoded".toMediaType()

    /**
     * 刷新出的 captcha，键是 **设备号 + action + 账号**（见 [captchaCacheKey]）。
     *
     * ★ P2-08：早期版本只按 `deviceId` 缓存，而 `captcha/init` 的 body 带 `action`、
     * `meta.user_id`，服务端签发的 token 与这两者绑定。只按设备号缓存会出现两种串用：
     * 1. 同一账号先列目录（`GET:/drive/v1/files`）再删文件（`POST:/drive/v1/files:batchDelete`），
     *    第二次直接复用第一次的 token，服务端判 `captcha_invalid`，白撞一次失败再重签；
     * 2. 多账号共用同一台设备号（网页登录的 device_id 来自浏览器配置）时，B 账号复用 A 的
     *    token，拿到的是「给 A 的验证」，写操作被降级拒绝，错误文案完全指不到这里。
     */
    private val refreshedCaptchas = java.util.concurrent.ConcurrentHashMap<String, PanCaptcha>()

    /** 游客 captcha：按 (deviceId, action) 缓存，服务端只给 300s，过期重签（游客没有账号维度） */
    private val guestCaptchas = java.util.concurrent.ConcurrentHashMap<String, PanCaptcha>()

    /**
     * `captcha/init` 的限流冷却截止时间（毫秒）。
     *
     * P2-08：被 429 之后如果继续按「失败就重签」的逻辑重试，每次解析都会再打一次
     * captcha/init，把限流窗口越拖越长（典型的请求放大）。所以这里记一个进程级冷却：
     * 冷却期内 [initWebCaptcha] 直接返回 `RATE_LIMITED`，一个请求都不发。
     * 服务端限流按 IP / 设备维度计，进程内全局冷却足够，且不会因为多账号而互相绕过。
     */
    @Volatile
    private var captchaCooldownUntilMs: Long = 0L

    /** 最近一次限流建议的等待毫秒数（0 = 服务端没给），仅用于文案，不含任何凭证 */
    @Volatile
    var lastWebCaptchaRetryAfterMs: Long = 0L
        private set

    /** 冷却剩余毫秒（0 = 未在冷却） */
    internal fun captchaCooldownRemainingMs(now: Long = clock()): Long =
        (captchaCooldownUntilMs - now).coerceAtLeast(0L)

    /**
     * 记录一次限流冷却：`Retry-After` 优先，没给就用默认值，并封顶防异常响应头。
     *
     * 三条 captcha 通道（网页 9 盐、App 登录、App pan 重签）共用同一张保护表：它们打的是
     * 同一个 `captcha/init` 接口，服务端按 IP / 设备计限流，分开记等于没记。
     */
    private fun markCaptchaRateLimited(retryAfterMs: Long) {
        val wait = (retryAfterMs.takeIf { it > 0 } ?: DEFAULT_RATE_LIMIT_COOLDOWN_MS)
            .coerceAtMost(MAX_RATE_LIMIT_COOLDOWN_MS)
        captchaCooldownUntilMs = clock() + wait
        lastWebCaptchaRetryAfterMs = wait
    }

    // 注意：access_token 一律按请求参数传递，不再用实例级全局缓存。
    // 旧实现用 currentAccessToken 覆盖传入 token，导致多账号下载时 A 账号凭证被 B 账号覆盖（P0-2）。
    // 401 刷新后的新 token 由 refreshTokenProvider 持久化，并通过 panCallInternal 的局部
    // effectiveToken 传给本次重试的 build 闭包，不经过实例字段。

    /** 401/unauthenticated 时自动刷新：提供 refresh_token 换新 token（由调用方注入并持久化） */
    var refreshTokenProvider: suspend (deviceId: String) -> Pair<String, String>? = { null }

    /**
     * 网页登录（webToken）captcha 的刷新入口（非网页账号返回 null，调用方走 App 通道重签）。
     *
     * 由 `XunleiAccountRepository.refreshWebCaptcha` 实现：本地 9 盐签名优先，被拒才回退网页会话。
     * 返回值必须带 `expiresAtMs`，调用方据此判断能否复用。
     */
    var webCaptchaRefresher: suspend (deviceId: String, action: String) -> PanCaptcha? = { _, _ -> null }

    /**
     * 最近一次网页 captcha/init 的失败原因（P1-02）。
     *
     * 由 [initWebCaptcha] 写入，[panCallInternal] 拼错误信息时读取，把「签名参数可能已过期 /
     * 被限流 / 网络异常」区分开，而不是统一显示「人机验证失败」。供 UI 决定提示「去网页重新登录」
     * 还是「稍后重试」；只包含枚举与状态码，不含 URL、token 或请求体。
     */
    @Volatile
    var lastWebCaptchaFailure: WebCaptchaFailure? = null
        private set

    /** 供诊断/日志读取最近一次失败原因（不落任何敏感内容）。 */
    fun noteWebCaptchaFailure(reason: WebCaptchaFailure?) {
        lastWebCaptchaFailure = reason
    }

    /** 当前用户 ID（从 access_token JWT 解析，captcha init 的 meta 需要） */
    @Volatile
    private var currentUserId: String = ""

    // ---------- 登录 ----------

    /** 1. 验证码盾初始化，返回 captcha_token（换 token 与 pan 请求需带 X-Captcha-Token）。
     * 官方抓包：client_id 用 app 凭据 Xp6vsxz_7IYVw2BB。
     * 按文档 §9.5：meta 必须带 captcha_sign（10-salt 算法），否则拿到降级 token（POST 类接口可能拒绝）。 */
    suspend fun initCaptcha(
        deviceId: String,
        username: String,
        action: String = "POST:/auth/signin/token"
    ): String? = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis().toString()
        val sign = buildCaptchaSign(deviceId, ts)
        val body = JSONObject()
            .put("action", action)
            .put("captcha_token", "")
            .put("client_id", XunleiConstants.APP_CLIENT_ID)
            .put("device_id", deviceId)
            .put("meta", JSONObject()
                .put("username", username)
                .put("client_version", XunleiConstants.APP_CLIENT_VERSION)
                .put("package_name", XunleiConstants.APP_PACKAGE_NAME)
                .put("timestamp", ts)
                .put("captcha_sign", sign)
                .put("user_id", currentUserId)) // 真实 user_id，空会得到降级 token
            .put("redirect_uri", "xlaccsdk01://xunlei.com/callback?state=harbor")
            .toString()
        val request = Request.Builder()
            .url(XunleiConstants.CAPTCHA_INIT_URL)
            .header("User-Agent", XunleiConstants.APP_UA)
            .header("Accept", "application/json;charset=UTF-8")
            .header("Content-Type", "application/json")
            .header("X-Client-Id", XunleiConstants.APP_CLIENT_ID)
            .header("X-Device-Id", deviceId)
            .header("X-Client-Version", "8.31.0.9726")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                // P2-08：非 2xx 不再硬塞进 JSONObject——错误页（HTML）解析必抛异常，
                // 又被外层 runCatching 压成 null，最后只能报「请求参数错误」，限流/网关错误全看不出来。
                // 登录是人主动触发的一次性动作，这里只记录冷却、不短路（短路会让点登录毫无反应）。
                val retryAfter = resp.header("Retry-After")
                if (resp.code == 429 || (retryAfter != null && !resp.isSuccessful)) {
                    markCaptchaRateLimited(
                        retryAfter?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.times(1000L) ?: 0L
                    )
                    return@use null
                }
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body?.string() ?: "{}")
                json.optString("captcha_token").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /** 2. 账号密码登录（官方首次登录：creditkey=""，sdk UA）。
     * 新客户端第一次登录必然返回 review_panel(1007) → 走短信验证；不要填任何 creditkey/captcha。 */
    suspend fun loginWithPassword(
        username: String,
        password: String,
        deviceId: String,
        checkCode: String = ""
    ): XunleiLoginStep = withContext(Dispatchers.IO) {
        val body = baseLoginBody(deviceId, "25.0.5.25", "513006")
            .put("userName", username)
            .put("passWord", password)
            .put("verifyKey", "")
            .put("verifyCode", checkCode)
            .put("isMd5Pwd", "0")
            .toString()
        // 官方抓包 v3/login 头：sdk UA + content-type，无 Cookie，无 x-device-id/x-client-id
        val request = Request.Builder()
            .url(XunleiConstants.LOGIN_URL)
            .header("User-Agent", "android-ok-http-client/xl-acc-sdk/version-5.1.3.513006")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { resp ->
            val json = JSONObject(resp.body?.string() ?: "{}")
            parseLoginResponse(json)
        }
    }

    /** 3a. 发送短信验证码（官方：UA=xl-acc-sdk/version-5.0.12.512000，creditkey=""，无 Cookie） */
    suspend fun sendSms(mobile: String, deviceId: String): XunleiLoginStep = withContext(Dispatchers.IO) {
        val body = baseLoginBody(deviceId, "8.31.0.9726", "231500")
            .put("mobile", mobile)
            .put("register", "0")
            .toString()
        val request = Request.Builder()
            .url(XunleiConstants.SEND_SMS_URL)
            .header("User-Agent", "android-ok-http-client/xl-acc-sdk/version-5.0.12.512000")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { resp ->
            val json = JSONObject(resp.body?.string() ?: "{}")
            XunleiLoginStep(
                needSms = true,
                smsCreditKey = json.optString("creditkey"),
                smsToken = json.optString("token"),
                message = json.optString("errorDesc").ifBlank { "短信已发送" }
            )
        }
    }

    /** 3b. 短信验证码登录（官方：UA=xl-acc-sdk/version-5.0.12.512000，body 带 creditkey/token，无 Cookie） */
    suspend fun smsLogin(
        mobile: String,
        smsCode: String,
        creditKey: String,
        smsToken: String,
        deviceId: String
    ): XunleiLoginStep = withContext(Dispatchers.IO) {
        val body = baseLoginBody(deviceId, "8.31.0.9726", "231500", creditKey)
            .put("mobile", mobile)
            .put("smsCode", smsCode)
            .put("token", smsToken)
            .put("register", "0")
            .toString()
        val request = Request.Builder()
            .url(XunleiConstants.SMS_LOGIN_URL)
            .header("User-Agent", "android-ok-http-client/xl-acc-sdk/version-5.0.12.512000")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { resp ->
            val json = JSONObject(resp.body?.string() ?: "{}")
            parseLoginResponse(json)
        }
    }

    /** 4. 用 v3/smslogin 返回的 sessionID 换取 access_token（官方抓包 POST /v1/auth/signin/token）。
     * body：{"client_id":"Xp6vsxz_7IYVw2BB","client_secret":"Xp6vsy4tN9toTVdMSpomVdXpRmES",
     *        "provider":"access_end_point_token","signin_token":"<sessionID>"}
     * 请求头必须带 X-Captcha-Token（captcha/init 返回）。 */
    suspend fun exchangeToken(sessionId: String, deviceId: String, captchaToken: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("client_id", XunleiConstants.APP_CLIENT_ID)
            .put("client_secret", XunleiConstants.APP_CLIENT_SECRET)
            .put("provider", "access_end_point_token")
            .put("signin_token", sessionId)
            .toString()
        val builder = Request.Builder()
            .url(XunleiConstants.TOKEN_URL)
            .header("User-Agent", XunleiConstants.APP_UA)
            .header("Accept", "application/json;charset=UTF-8")
            .header("Content-Type", "application/json")
            .header("X-Client-Id", XunleiConstants.APP_CLIENT_ID)
            .header("X-Device-Id", deviceId)
            .header("X-Client-Version", "8.31.0.9726")
        if (captchaToken.isNotBlank()) builder.header("X-Captcha-Token", captchaToken)
        val request = builder.post(body.toRequestBody(jsonMediaType)).build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                val at = json.optString("access_token").ifBlank { json.optString("accessToken") }
                val rt = json.optString("refresh_token").ifBlank { json.optString("refreshToken") }
                if (at.isBlank()) null else {
                    // 缓存 user_id（JWT sub），captcha/init 的 meta 需要
                    jwtSub(at).takeIf { it.isNotBlank() }?.let { currentUserId = it }
                    at to rt
                }
            }
        }.getOrNull()
    }

    /**
     * 缓存当前用户 ID（从 access_token JWT 解析），供 captcha/init 的 meta 使用。
     *
     * @param force 多账号 / 换号场景下必须为 true：原来的「空才写」会让 B 账号继续用
     *   A 的 user_id 去换 captcha，拿到降级 token（写操作被拒），而错误文案完全指不到这里。
     *   解析不出 sub（非常规 token）时保留旧值，不要用一个空串把能用的值冲掉。
     */
    fun cacheUserId(accessToken: String, force: Boolean = false) {
        val parsed = jwtSub(accessToken)
        if (parsed.isNotBlank() && (force || currentUserId.isBlank())) currentUserId = parsed
    }

    /**
     * 用 refresh_token 刷新 access_token（OAuth2 refresh_token）。
     * 导入恢复后旧 token 可能已过期（12h），刷新后立即有效。
     *
     * @param authType 登录方式：网页登录（[XunleiWebCredential.AUTH_TYPE]）的 token 与 App token
     *   **不是同一套 OAuth 客户端**——网页 token 只能用网页 client_id 刷新，请求形态也不同
     *   （JSON + 桌面 UA + Origin/Referer，且必须**不带** client_secret）；带错了会被直接拒绝，
     *   表现为「登录才没多久就提示过期」。
     * @return 新 (access_token, refresh_token)；失败返回 null
     */
    suspend fun refreshToken(
        refreshToken: String,
        deviceId: String,
        authType: String = ""
    ): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val web = authType == XunleiWebCredential.AUTH_TYPE
            val request = if (web) {
                val body = JSONObject()
                    .put("grant_type", "refresh_token")
                    .put("client_id", XunleiWebCredential.CLIENT_ID)
                    .put("refresh_token", refreshToken)
                    .toString()
                Request.Builder()
                    .url(XunleiConstants.REFRESH_URL)
                    .header("Content-Type", "application/json")
                    .header("X-Device-Id", deviceId)
                    .header("X-Client-Id", XunleiWebCredential.CLIENT_ID)
                    .header("Origin", "https://pan.xunlei.com")
                    .header("Referer", "https://pan.xunlei.com/")
                    .header("User-Agent", XunleiWebCredential.DESKTOP_UA)
                    // 桌面 UA 必须同步这套 client hints：只改 UA 不同步 Sec-CH-UA 会被判成移动端而冲突
                    .header("Sec-Ch-Ua", XunleiWebCredential.DESKTOP_SEC_CH_UA)
                    .header("Sec-Ch-Ua-Mobile", XunleiWebCredential.DESKTOP_SEC_CH_UA_MOBILE)
                    .header("Sec-Ch-Ua-Platform", XunleiWebCredential.DESKTOP_SEC_CH_UA_PLATFORM)
                    .post(body.toRequestBody(jsonMediaType))
                    .build()
            } else {
                val body = "grant_type=refresh_token" +
                    "&client_id=${XunleiConstants.APP_CLIENT_ID}" +
                    "&client_secret=${XunleiConstants.APP_CLIENT_SECRET}" +
                    "&refresh_token=${java.net.URLEncoder.encode(refreshToken, "UTF-8")}"
                Request.Builder()
                    .url(XunleiConstants.REFRESH_URL)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-Device-Id", deviceId)
                    .post(body.toRequestBody(formMediaType))
                    .build()
            }
            runCatching {
                client.newCall(request).execute().use { resp ->
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val at = json.optString("access_token").ifBlank { json.optString("accessToken") }
                    val rt = json.optString("refresh_token").ifBlank { json.optString("refreshToken") }
                    if (at.isBlank()) null else {
                        jwtSub(at).takeIf { it.isNotBlank() }?.let { currentUserId = it }
                        at to rt
                    }
                }
            }.getOrNull()
        }

    /**
     * 校验一个 access_token 是否真的能用（网页登录落库前调用）。
     *
     * 只用网页 localStorage 里读到的东西无法判断登录是否已完成——页面在登录过程中会把
     * 中间态写进去，所以这里打一次真实的云盘接口（GET /drive/v1/about）再决定要不要落库；
     * 网络异常也返回 false，交给上层的自动检测继续轮询重试，不会误判成「登录成功」。
     *
     * 校验时只认传入的 token：panRequest 一律用按请求传入的 accessToken，
     * 不读任何实例级全局 token，因此不会把「有效」判在别的账号头上。
     */
    suspend fun verifyAccessToken(
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) return@withContext false
        runCatching {
            val request = panRequest(
                "${XunleiConstants.PAN_BASE}/drive/v1/about",
                accessToken, deviceId, captchaToken
            )
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val json = JSONObject(resp.body?.string() ?: "{}")
                // 该接口的返回结构随通道不同：App 通道包在 data 里，网页（webToken）通道是**顶层**
                // kind/quota（没有 data 包裹，实测 `{kind:"drive#about",quota:{...}}`）。只判 data 会把
                // 网页登录的合法 token 误判成无效 →「登录后提示未检测到登录态」。两种结构都接受。
                json.optJSONObject("data") != null || json.optString("kind") == "drive#about"
            }
        }.getOrDefault(false)
    }

    /** 解析 JWT 的 exp（秒）；解析失败返回 0 */
    fun jwtExp(token: String): Long = runCatching {
        val payload = token.split(".").getOrNull(1) ?: return@runCatching 0L
        val json = String(
            Base64.getUrlDecoder().decode(payload)
        )
        JSONObject(json).optLong("exp")
    }.getOrDefault(0L)

    /** 从 JWT 中解析 sub（用户 ID） */
    private fun jwtSub(token: String): String = runCatching {
        val payload = token.split(".").getOrNull(1) ?: return@runCatching ""
        val json = String(
            Base64.getUrlDecoder().decode(payload)
        )
        JSONObject(json).optString("sub")
    }.getOrDefault("")

    /** 解析 v3/login / v3/smslogin 响应 */
    private fun parseLoginResponse(json: JSONObject): XunleiLoginStep {
        val errorCode = json.optString("errorCode")
        if (errorCode == "0" || json.optString("error") == "success") {
            return XunleiLoginStep(
                needSms = false,
                sessionKey = json.optString("loginKey"),
                sessionId = json.optString("sessionID"),
                nickname = json.optString("nickName"),
                userID = json.optString("userID"),
                message = "登录成功"
            )
        }
        // 触发验证面板（review_panel）→ 需要短信验证
        val error = json.optString("error")
        val needSms = error == "review_panel" || errorCode == "1007" ||
            json.optString("verifyType") == "MEA" || json.optString("verifyType").isNotBlank()
        return XunleiLoginStep(
            needSms = needSms,
            reviewUrl = json.optString("reviewurl"), // 短信发不出时可让用户浏览器完成验证（alist 方式）
            message = json.optString("errorDesc").ifBlank { json.optString("error_description") }
        )
    }

    /** 登录请求公共体（对齐官方 app 抓包字段；peerID/devicesign 用动态生成的设备指纹） */
    private fun baseLoginBody(
        deviceId: String,
        clientVersion: String,
        sdkVersion: String,
        creditKey: String = ""
    ): JSONObject = JSONObject()
        .put("protocolVersion", "301")
        .put("sequenceNo", Random.nextLong(10000000, 99999999).toString())
        .put("platformVersion", "10")
        .put("isCompressed", "0")
        .put("appid", "40")
        .put("clientVersion", clientVersion)
        .put("peerID", XunleiDeviceFingerprint.peerId()) // 动态设备 peerID
        .put("appName", "ANDROID-com.xunlei.downloadprovider")
        .put("sdkVersion", sdkVersion)
        .put("devicesign", XunleiDeviceFingerprint.deviceSign()) // 动态设备指纹（§8 公式）
        .put("netWorkType", "WIFI")
        .put("providerName", "NONE")
        .put("deviceModel", "M2004J7AC")
        .put("deviceName", "Xiaomi_M2004j7ac")
        .put("OSVersion", "12")
        .put("creditkey", creditKey)
        .put("hl", "zh-CN")

    // ---------- Pan ----------

    /** 文件列表（个人网盘，parent_id 为空=根目录） */
    suspend fun getFiles(
        parentId: String,
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val filters = java.net.URLEncoder.encode("""{"trashed":{"eq":false}}""", "UTF-8")
        val url = buildString {
            append(XunleiConstants.FILES_URL)
            append("?parent_id=").append(parentId)
            append("&page_token=&limit=50&with_audit=true&filters=").append(filters)
        }
        panCall(captchaToken, deviceId, "GET:/drive/v1/files", accessToken, { t, at ->
            panRequest(url, at, deviceId, t)
        }) { data ->
            data.optJSONArray("files")?.let(::parseFileArray) ?: emptyList()
        }
    }

    /** 创建文件夹（个人网盘），返回新文件夹 id */
    suspend fun createFolder(
        name: String,
        parentId: String,
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("kind", "drive#folder")
            .put("name", name)
            .put("parent_id", parentId)
            .put("space", "") // 官方 proto 要求字符串，数字会 400
            .toString()
        panCall(captchaToken, deviceId, "POST:/drive/v1/files", accessToken, { t, at ->
            panRequest(XunleiConstants.FILES_URL, at, deviceId, t, body)
        }) { data -> data.optString("id").takeIf { it.isNotBlank() } }
    }

    /** 文件详情（返回下载直链 links.application/octet-stream.url） */
    suspend fun getFileDetail(
        fileId: String,
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): DownloadLink? = withContext(Dispatchers.IO) {
        val url = "${XunleiConstants.FILES_URL}/$fileId?_magic=2021&usage=PLAY&thumbnail_size=SIZE_LARGE" +
            "&with=hdr10&with=subtitle_files&with=task&with=public_share_tag"
        panCall(captchaToken, deviceId, "GET:/drive/v1/files/$fileId", accessToken, { t, at ->
            panRequest(url, at, deviceId, t)
        }) { data ->
            val links = data.optJSONObject("links")
            val urlStr = links?.optJSONObject("application/octet-stream")?.optString("url")
                ?: data.optString("web_content_link")
                ?: ""
            DownloadLink(
                fid = data.optString("id"),
                filename = data.optString("name"),
                downloadUrl = urlStr,
                size = data.optLong("size")
            )
        }
    }

    /** 分享解析（share_id + 可选 pass_code） */
    suspend fun getShare(
        shareId: String,
        passCode: String,
        accessToken: String,
        deviceId: String,
        captchaToken: String,
        pageToken: String = ""
    ): XunleiShareResult? = withContext(Dispatchers.IO) {
        val url = buildString {
            append(XunleiConstants.SHARE_URL)
            append("?share_id=").append(shareId)
            append("&pass_code=").append(java.net.URLEncoder.encode(passCode, "UTF-8"))
            append("&limit=100&page_token=")
                .append(java.net.URLEncoder.encode(pageToken, "UTF-8"))
                .append("&thumbnail_size=SIZE_SMALL")
        }
        val build: (String, String) -> Request = { t, at -> panRequest(url, at, deviceId, t) }
        val parse: (JSONObject) -> XunleiShareResult = { data ->
            // 提取码状态检查：PASS_CODE_EMPTY（没填）/ PASS_CODE_ERROR（错误）/ PASS_CODE_NEED（需要）
            // 这三种情况 files 为空数组且 HTTP 200，若不识别会被误判为「此目录为空」
            when (data.optString("share_status")) {
                "PASS_CODE_EMPTY" -> throw QuarkApiException("请输入提取码")
                "PASS_CODE_ERROR" -> throw QuarkApiException("提取码错误")
                "PASS_CODE_NEED" -> throw QuarkApiException("该分享需要提取码")
            }
            val files = data.optJSONArray("files")?.let(::parseFileArray) ?: emptyList()
            XunleiShareResult(
                title = data.optString("title"),
                files = files,
                passCodeToken = data.optString("pass_code_token"),
                shareId = shareId,
                nextPageToken = data.optString("next_page_token")
            )
        }
        // 游客模式（无 access_token）走匿名调用：不带验证码、也不刷新 token
        if (accessToken.isBlank()) {
            panCallAnonymous(captchaToken, deviceId, "GET:/drive/v1/share", build, parse)
        } else {
            panCall(captchaToken, deviceId, "GET:/drive/v1/share", accessToken, build, parse)
        }
    }

    /** 分享子目录文件列表（share/detail，parent_id + pass_code_token） */
    suspend fun getShareDetail(
        shareId: String,
        parentId: String,
        passCodeToken: String,
        accessToken: String,
        deviceId: String,
        captchaToken: String,
        pageToken: String = ""
    ): XunleiFilePage? = withContext(Dispatchers.IO) {
        val url = buildString {
            append(XunleiConstants.SHARE_DETAIL_URL)
            append("?share_id=").append(shareId)
            append("&parent_id=").append(parentId)
            append("&pass_code_token=").append(java.net.URLEncoder.encode(passCodeToken, "UTF-8"))
            append("&limit=100&page_token=")
                .append(java.net.URLEncoder.encode(pageToken, "UTF-8"))
                .append("&thumbnail_size=SIZE_SMALL")
        }
        val build: (String, String) -> Request = { t, at -> panRequest(url, at, deviceId, t) }
        val parse: (JSONObject) -> XunleiFilePage = { data ->
            XunleiFilePage(
                files = data.optJSONArray("files")?.let(::parseFileArray) ?: emptyList(),
                nextPageToken = data.optString("next_page_token")
            )
        }
        // 游客模式（无 access_token）走匿名调用：不带验证码、也不刷新 token
        if (accessToken.isBlank()) {
            panCallAnonymous(captchaToken, deviceId, "GET:/drive/v1/share/detail", build, parse)
        } else {
            panCall(captchaToken, deviceId, "GET:/drive/v1/share/detail", accessToken, build, parse)
        }
    }

    /** 转存到指定目录（官方同步返回 RESTORE_COMPLETE + trace_file_ids 映射），返回转存后的新文件 id */
    suspend fun restore(
        shareId: String,
        passCodeToken: String,
        parentFolderId: String,
        fileIds: List<String>,
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("share_id", shareId)
            .put("pass_code_token", passCodeToken)
            .put("parent_id", parentFolderId)
            .put("ancestor_ids", JSONArray())
            .put("file_ids", JSONArray().apply { fileIds.forEach { put(it) } })
            .put("specify_parent_id", true)
            .toString()
        panCall(captchaToken, deviceId, "POST:/drive/v1/share/restore", accessToken, { t, at ->
            panRequest(XunleiConstants.RESTORE_URL, at, deviceId, t, body)
        }) { data ->
            // params.trace_file_ids 是 JSON 字符串：{"分享文件id":"转存后新id"}
            val trace = data.optJSONObject("params")?.optString("trace_file_ids").orEmpty()
            runCatching {
                val map = JSONObject(trace)
                fileIds.firstOrNull { map.has(it) }
                    ?.let { map.optString(it) }
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
                ?: data.optString("file_id").takeIf { it.isNotBlank() }
        }
    }

    /** 批量删除文件（转存后的临时文件；直链已自带签名，删除不影响下载） */
    suspend fun batchDelete(
        ids: List<String>,
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("ids", JSONArray().apply { ids.forEach { put(it) } })
            .put("space", "")
            .toString()
        panCall(captchaToken, deviceId, "POST:/drive/v1/files:batchDelete", accessToken, { t, at ->
            panRequest("${XunleiConstants.FILES_URL}:batchDelete", at, deviceId, t, body)
        }) { true }
    }

    /** 轮询转存任务（最多 15 次 × 1s） */
    suspend fun pollTask(taskId: String, accessToken: String, deviceId: String, captchaToken: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = "${XunleiConstants.TASKS_URL}/$taskId?type=share"
            for (i in 0 until 15) {
                val done = runCatching {
                    panCall(captchaToken, deviceId, "GET:/drive/v1/tasks/$taskId", accessToken, { t, at ->
                        panRequest(url, at, deviceId, t)
                    }) { data ->
                        val status = data.optString("status").ifBlank { data.optString("phase") }
                        status == "PHASE_TYPE_COMPLETE" || data.optInt("error_code") == 0
                    }
                }.getOrDefault(false)
                if (done) return@withContext true
                delay(1000)
            }
            false
        }

    /** 确保「MclyDownload临时转存」目录存在，返回其 id */
    suspend fun ensureTempDir(
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): String? = withContext(Dispatchers.IO) {
        val root = getFiles("", accessToken, deviceId, captchaToken) ?: emptyList()
        root.firstOrNull { it.isdir && it.fname == XunleiConstants.TEMP_DIR_NAME }?.fid
            ?: createFolder(XunleiConstants.TEMP_DIR_NAME, "", accessToken, deviceId, captchaToken)
    }

    // ---------- 云盘文件管理（迅雷网盘功能） ----------

    /** 网盘空间详情（GET /drive/v1/about：quota.limit / usage） */
    suspend fun getQuota(
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): QuotaInfo? = withContext(Dispatchers.IO) {
        runCatching {
            panCall(captchaToken, deviceId, "GET:/drive/v1/about", accessToken, { t, at ->
                panRequest("${XunleiConstants.PAN_BASE}/drive/v1/about", at, deviceId, t)
            }) { data ->
                val quota = data.optJSONObject("quota")
                QuotaInfo(
                    used = quota?.optLong("usage") ?: 0L,
                    total = quota?.optLong("limit") ?: 0L,
                    usedInTrash = quota?.optLong("usage_in_trash") ?: 0L
                )
            }
        }.getOrNull()
    }

    /** 重命名（PATCH /drive/v1/files/{id}，body {"name"}） */
    suspend fun renameFile(
        fileId: String,
        name: String,
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        panCall(captchaToken, deviceId, "PATCH:/drive/v1/files/$fileId", accessToken, { t, at ->
            panRequestM(
                "${XunleiConstants.FILES_URL}/$fileId",
                at, deviceId, t, "PATCH",
                JSONObject().put("name", name).toString()
            )
        }) { data -> data.optString("id").isNotBlank() }
    }

    /** 移动（batchMove：ids + to.parent_id）；返回 task_id */
    suspend fun moveFile(
        fileIds: List<String>,
        toParentId: String,
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): String? = withContext(Dispatchers.IO) {
        panCall(captchaToken, deviceId, "POST:/drive/v1/files:batchMove", accessToken, { t, at ->
            panRequestM(
                XunleiConstants.MOVE_URL,
                at, deviceId, t, "POST",
                JSONObject()
                    .put("ids", JSONArray().apply { fileIds.forEach { put(it) } })
                    .put("to", JSONObject().put("parent_id", toParentId).put("space", ""))
                    .put("space", "")
                    .toString()
            )
        }) { data -> data.optString("task_id").takeIf { it.isNotBlank() } }
    }

    /** 创建分享（POST /drive/v1/share，迅雷分享带提取码；官方默认自动生成，可自定义 4 位）
     *  @param expirationDays "-1"=永久 "1"/"7"/"30"=天数（用 [top.mcly99.dl.data.network.model.ShareExpire.xunleiDays]
     *    从中性码转换，别直接下发中性码）
     *  @param passCode 自定义提取码（4 位字母数字，留空则服务端自动生成）
     *  @return 分享信息（share_url/pass_code 直接返回，无需二次查询）；接口不返回有效期，
     *    `expiredType` 填 [top.mcly99.dl.data.network.model.ShareExpire.UNKNOWN]，由调用方用用户所选值覆盖
     */
    suspend fun createShare(
        fileIds: List<String>,
        title: String,
        expirationDays: String,
        accessToken: String,
        deviceId: String,
        captchaToken: String,
        passCode: String = ""
    ): ShareInfo? = withContext(Dispatchers.IO) {
        panCall(captchaToken, deviceId, "POST:/drive/v1/share", accessToken, { t, at ->
            panRequestM(
                XunleiConstants.SHARE_CREATE_URL,
                at, deviceId, t, "POST",
                JSONObject()
                    .put("file_ids", JSONArray().apply { fileIds.forEach { put(it) } })
                    .put("share_to", "copy")
                    .put("params", JSONObject()
                        .put("subscribe_push", "false")
                        .put("WithPassCodeInLink", "true")
                        .put("with_pass_code_in_link", "true")
                        .apply { if (passCode.isNotBlank()) put("pass_code", passCode) })
                    .put("title", title.ifBlank { "分享文件" })
                    .put("restore_limit", "-1")
                    .put("expiration_days", expirationDays)
                    .toString()
            )
        }) { data ->
            ShareInfo(
                shareUrl = data.optString("share_url"),
                passcode = data.optString("pass_code"),
                pwdId = data.optString("share_id"),
                title = data.optString("title").ifBlank { title },
                // 响应的 data 里没有有效期字段，中性码由调用方（XunleiCloudViewModel）覆盖
                expiredType = ShareExpire.UNKNOWN
            )
        }
    }

    /** 删除（batchTrash：ids + space，回收站） */
    suspend fun deleteFiles(
        fileIds: List<String>,
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        panCall(captchaToken, deviceId, "POST:/drive/v1/files:batchTrash", accessToken, { t, at ->
            panRequestM(
                XunleiConstants.TRASH_URL,
                at, deviceId, t, "POST",
                JSONObject()
                    .put("ids", JSONArray().apply { fileIds.forEach { put(it) } })
                    .put("space", "")
                    .toString()
            )
        }) { true }
    }

    // ---------- 请求构造 ----------

    private fun parseFileArray(array: JSONArray): List<ShareFile> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            add(
                ShareFile(
                    fid = item.optString("id"),
                    fname = item.optString("name"),
                    fsize = item.optLong("size"),
                    isdir = item.optString("kind") == "drive#folder",
                    pdirFid = item.optString("parent_id"),
                    fidToken = "",
                    modifyTime = item.optString("modified_time")
                )
            )
        }
    }

    /**
     * pan 请求（Bearer + 设备 + captcha 头，抓包确认无 x-signature）。
     * accessToken 为空 = 游客模式：**不写 Authorization 头** —— 迅雷分享接口允许匿名访问，
     * 但带上失效的 `Bearer ` 反而会被服务端判成 unauthenticated。
     */
    private fun panRequest(
        url: String,
        accessToken: String,
        deviceId: String,
        captchaToken: String,
        body: String? = null
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", XunleiConstants.WEB_UA)
            .header("X-Device-Id", deviceId)
            .header("X-Client-Version", "8.31.0.9726")
            .header("Content-Type", "application/json")
            .header("Origin", "https://pan.xunlei.com")
            .header("Referer", "https://pan.xunlei.com/")
        if (accessToken.isNotBlank()) {
            // 只用本请求显式传入的 token。多账号下载时每个请求带各自的凭证，
            // 绝不读实例级全局 token（否则 A 账号凭证被 B 账号覆盖，P0-2）。
            builder.header("Authorization", "Bearer $accessToken")
        }
        // ktdown 适配：游客态 captcha_token 由网页通道本地签名换取，pan 接口会拿
        // X-Client-Id 与 token 里的 client_id 比对；缺这个头一律回 captcha_invalid /
        // client_id not match。上游只给登录态留了 captcha，匿名分享接口现已强制校验。
        if (accessToken.isBlank()) builder.header("X-Client-Id", XunleiWebCredential.CLIENT_ID)
        if (captchaToken.isNotBlank()) builder.header("X-Captcha-Token", captchaToken)
        return if (body != null) builder.post(body.toRequestBody(jsonMediaType)).build()
        else builder.get().build()
    }

    /** pan 请求（支持 PATCH 等动词，云盘重命名用）；accessToken 为空同 [panRequest]：游客模式不写 Authorization */
    private fun panRequestM(
        url: String,
        accessToken: String,
        deviceId: String,
        captchaToken: String,
        method: String,
        body: String? = null
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", XunleiConstants.WEB_UA)
            .header("X-Device-Id", deviceId)
            .header("X-Client-Version", "8.31.0.9726")
            .header("Content-Type", "application/json")
            .header("Origin", "https://pan.xunlei.com")
            .header("Referer", "https://pan.xunlei.com/")
        if (accessToken.isNotBlank()) {
            builder.header("Authorization", "Bearer $accessToken")
        }
        if (captchaToken.isNotBlank()) builder.header("X-Captcha-Token", captchaToken)
        val rb = body?.toRequestBody(jsonMediaType) ?: "{}".toRequestBody(jsonMediaType)
        return when (method) {
            "PATCH" -> builder.patch(rb).build()
            "GET" -> builder.get().build()
            else -> builder.post(rb).build()
        }
    }

    /** pan 请求带验证码自动刷新重试：失败 captcha_invalid → 用旧 token 换新 token → 重试一次（对齐官方） */
    private suspend fun <T> panCall(
        captchaToken: String,
        deviceId: String,
        action: String,
        accessToken: String,
        build: (String, String) -> Request,
        parse: (JSONObject) -> T
    ): T = panCallInternal(captchaToken, deviceId, action, accessToken, false, build, parse)

    /**
     * 游客模式的 pan 调用（没有 access_token）：不写 Authorization（带上失效的 `Bearer `
     * 会被判成 unauthenticated），但**仍要带验证码**。
     *
     * ktdown 适配（2026-11）：迅雷匿名分享接口已强制校验 `X-Captcha-Token`，上游原实现
     * 「游客不带验证码」会稳定返回 `captcha_invalid / captcha_token is empty / 验证码无效`。
     * 这里改为用网页通道本地 9 盐签名现签游客 token，并在 captcha_invalid 时重签重试一次。
     */
    private suspend fun <T> panCallAnonymous(
        captchaToken: String,
        deviceId: String,
        action: String,
        build: (String, String) -> Request,
        parse: (JSONObject) -> T
    ): T = panCallInternal(captchaToken, deviceId, action, "", true, build, parse)

    /**
     * 游客 captcha：本地签名 → captcha/init 换 token，按 (deviceId, action) 缓存到过期。
     * 不需要账号，也不需要隐藏浏览器；签名盐轮换时返回空串，让错误照常抛给上层。
     */
    private suspend fun guestCaptcha(deviceId: String, action: String, forceRefresh: Boolean = false): String {
        val key = "$deviceId|$action"
        if (!forceRefresh) guestCaptchas[key]?.takeIf { it.isValid() }?.let { return it.token }
        return when (val result = initWebCaptcha(deviceId, action)) {
            is WebCaptchaInit.Ok -> {
                guestCaptchas[key] = result.captcha
                result.captcha.token
            }
            else -> {
                // 失败原因已由 initWebCaptcha 写入 lastWebCaptchaFailure，
                // panCallInternal 抛错时会据此给出可操作的提示，而不是笼统的「验证码无效」。
                guestCaptchas.remove(key)
                ""
            }
        }
    }

    /**
     * captcha 缓存键：设备号 + action + 账号。
     *
     * 三个维度都参与，是因为服务端签发的 token 与它们绑定：
     * - `device_id` 参与 body 与 `X-Device-Id`，换设备必须重签；
     * - `action` 参与 body（`GET:/drive/v1/files` 的 token 拿去发 `POST:` 会被判 `captcha_invalid`）；
     * - `meta.user_id` 参与签名，A 账号的 token 给 B 用只会拿到降级/被拒的验证。
     *
     * `userId` 允许为空串：登录前 `currentUserId` 尚未解析出来，此时按「匿名」这一档单独缓存，
     * 等账号信息补上后会自然换到新键，不会把匿名档的 token 当成账号档复用。
     */
    private fun captchaCacheKey(deviceId: String, action: String, userId: String): String =
        "$deviceId|$action|$userId"

    /** 写入 captcha 缓存，并按 [CAPTCHA_CACHE_LIMIT] 收敛，避免「设备 × action × 账号」组合无界增长。 */
    private fun storeCaptcha(key: String, captcha: PanCaptcha) {
        if (refreshedCaptchas.size >= CAPTCHA_CACHE_LIMIT) {
            val now = clock()
            refreshedCaptchas.entries.removeAll { !it.value.isValid(now) }
            if (refreshedCaptchas.size >= CAPTCHA_CACHE_LIMIT) refreshedCaptchas.clear()
        }
        refreshedCaptchas[key] = captcha
    }

    private suspend fun <T> panCallInternal(
        captchaToken: String,
        deviceId: String,
        action: String,
        accessToken: String,
        anonymous: Boolean,
        build: (String, String) -> Request,
        parse: (JSONObject) -> T
    ): T {
        // 账号快照：captcha/init 的 meta.user_id 取的就是这一刻的 currentUserId，
        // 缓存键必须用同一份值，否则「按 A 签出的 token」会被记到 B 名下（P2-08）。
        val userIdSnapshot = currentUserId
        val captchaKey = captchaCacheKey(deviceId, action, userIdSnapshot)
        // 网页账号的 captcha 只有 300s：缓存里过期的绝不再用，直接换新的（本地签名，几百毫秒）。
        // 不这么做的话，每次解析都会先拿死 token 撞一次 captcha_invalid 再重试。
        var token = if (anonymous) {
            // 没有注入账号 captcha 时自签一个游客 token（迅雷匿名接口现在也必须带）
            captchaToken.takeIf { it.isNotBlank() } ?: guestCaptcha(deviceId, action)
        } else {
            val cached = refreshedCaptchas[captchaKey]?.takeIf { it.isValid(clock()) }
            (cached ?: webCaptchaRefresher(deviceId, action)?.also { storeCaptcha(captchaKey, it) })
                ?.token ?: captchaToken
        }
        // 本次调用的有效 token：初始为请求传入的那一份，401 刷新后仅更新到本次重试，
        // 不写实例字段，避免多账号互相覆盖（P0-2）。
        var effectiveToken = accessToken
        repeat(2) { attempt ->
            val response = client.newCall(build(token, effectiveToken)).execute()
            val body = response.use { it.body?.string() ?: throw QuarkApiException("请求失败：响应为空") }
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw QuarkApiException("响应解析失败（HTTP ${response.code}）")
            }
            if (!response.isSuccessful || json.has("error")) {
                val err = json.optString("error")
                // access_token 过期（401/unauthenticated）：refresh_token 换新 → 重新 init captcha → 重试（对齐官方抓包）
                if (!anonymous && (response.code == 401 || err == "unauthenticated") && attempt == 0) {
                    val refreshed = refreshTokenProvider(deviceId)
                    if (refreshed != null) {
                        // 刷新的新 token 只用于本次重试；持久化已由 refreshTokenProvider
                        // （refreshXunleiToken）完成，下次请求会经 access() 拿到最新值。
                        effectiveToken = refreshed.first
                        val newCaptcha = webCaptchaRefresher(deviceId, action)
                            ?: initPanCaptcha(deviceId, action, token)?.let { PanCaptcha(it, Long.MAX_VALUE) }
                        if (newCaptcha != null) {
                            // 存的时候按「签发时的账号」重新算一次键：refreshTokenProvider 可能刚
                            // 把 currentUserId 填上/换掉，用进入时的快照会记到别的账号名下
                            storeCaptcha(captchaCacheKey(deviceId, action, currentUserId), newCaptcha)
                            token = newCaptcha.token
                        }
                        return@repeat
                    }
                }
                if (!anonymous && err == "captcha_invalid" && attempt == 0) {
                    // client_id 配错：回退网页会话也救不回来（网页会话同样只认网页 client_id），
                    // 只会白等十几秒再失败，所以直接报错。
                    if (isClientIdMismatch(json)) {
                        throw QuarkApiException("验证码与当前登录方式不匹配，请重新登录迅雷网页版")
                    }
                    // 网页账号：本地 9 盐签名换 token（被拒时由实现内部回退网页会话）；
                    // 非网页账号返回 null，继续走原来的 App 通道 captcha_sign 重签。
                    val newCaptcha = webCaptchaRefresher(deviceId, action)
                        ?: initPanCaptcha(deviceId, action, token)?.let { PanCaptcha(it, Long.MAX_VALUE) }
                    if (newCaptcha != null) {
                        storeCaptcha(captchaCacheKey(deviceId, action, currentUserId), newCaptcha)
                        token = newCaptcha.token
                        return@repeat
                    }
                }
                if (anonymous && err == "captcha_invalid" && attempt == 0) {
                    // 游客 token 过期 / 被服务端轮换：强制重签一次再试，别直接把「验证码无效」抛给用户
                    val fresh = guestCaptcha(deviceId, action, forceRefresh = true)
                    if (fresh.isNotBlank() && fresh != token) {
                        token = fresh
                        return@repeat
                    }
                }
                val raw = json.optString("error_description").ifBlank { json.optString("message") }
                    .ifBlank { err }.ifBlank { "请求失败" }
                val msg = if (err == "captcha_invalid") {
                    // P1-02：把网页 captcha/init 的真实失败原因透传到 UI。
                    // SIGN_REJECTED / CLIENT_ID_MISMATCH 意味着本地 9 盐或登录态已失效，
                    // 明确指引「去网页重新登录」（该按钮在「我的网盘」错误区已提供）；
                    // 限流/网络则提示稍后重试，避免用户反复重登。
                    val hint = when (lastWebCaptchaFailure) {
                        WebCaptchaFailure.SIGN_REJECTED ->
                            "签名参数可能已过期，请点「去网页重新登录」后重试，或更新签名配置"
                        WebCaptchaFailure.CLIENT_ID_MISMATCH ->
                            "登录方式与验证码不匹配，请重新登录迅雷网页版"
                        WebCaptchaFailure.RATE_LIMITED -> {
                            // 带上具体等待时长：只说「稍后再试」用户会立刻连点，反而拉长限流窗口
                            val waitSec = (lastWebCaptchaRetryAfterMs + 999) / 1000
                            if (waitSec > 0) "验证请求被限流，请 $waitSec 秒后再试（无需重新登录）"
                            else "验证请求被限流，请稍后再试（无需重新登录）"
                        }
                        WebCaptchaFailure.NETWORK ->
                            "验证请求网络异常，请检查网络或代理后重试"
                        WebCaptchaFailure.HTTP_ERROR ->
                            "验证服务暂时不可用，请稍后再试"
                        null -> null
                    }
                    if (hint != null) "$raw（$hint）"
                    else "$raw（迅雷人机验证失败，请稍后重试；若持续失败可填写该网盘的 Cookie / 令牌）"
                } else raw
                throw QuarkApiException(msg)
            }
            return parse(json.optJSONObject("data") ?: json)
        }
        throw QuarkApiException("验证码刷新后仍失败")
    }

    /** 服务端是否明确回了 client_id 配错（`error_details[].detail = "client_id not match"`） */
    private fun isClientIdMismatch(json: JSONObject): Boolean =
        json.toString().contains("client_id not match")

    /**
     * 网页通道 captcha/init：用当前生效的 9 盐签名换 token（不依赖隐藏浏览器）。
     *
     * 网页的 `captcha_sign` 约一天有效（每天 00:00 失效），换来的 token 只有 300s，
     * 所以这里每次需要 token 就现签一次。签名参数来自 [XunleiWebSignConfig]：服务端轮换
     * client_version / 盐后可通过运行时下发恢复，不必发版（P1-02）。
     *
     * 失败不再一律压成 `Failed(null)`：区分签名被拒、限流、网络、HTTP 错误、client_id 错配，
     * 写入 [lastWebCaptchaFailure] 供错误文案透传到 UI。`client_id not match` 单独返回
     * [WebCaptchaInit.ClientIdMismatch]：那种情况回退网页会话也救不回来，只能重新登录。
     */
    suspend fun initWebCaptcha(deviceId: String, action: String): WebCaptchaInit =
        withContext(Dispatchers.IO) {
            // 冷却期（上一次被 429）内一个请求都不发：失败即重签的循环只会把限流窗口越拖越长
            val cooling = captchaCooldownRemainingMs()
            if (cooling > 0) {
                noteWebCaptchaFailure(WebCaptchaFailure.RATE_LIMITED)
                lastWebCaptchaRetryAfterMs = cooling
                return@withContext WebCaptchaInit.Failed(WebCaptchaFailure.RATE_LIMITED, cooling)
            }

            var config = XunleiWebSignConfig.current()
            var result = runCatching { requestWebCaptcha(deviceId, action, config, clock()) }
                .getOrElse { WebCaptchaInit.Failed(WebCaptchaFailure.NETWORK) }

            // 运行时自愈（P1-02）：签名被拒说明 9 盐 / client_version 大概率已轮换。
            // 重新读一次本地配置——用户可能刚编辑了 yunx-misc.properties，或远程下发刚落盘——
            // 若参数确实变了就用新参数再签一次。只重试一次，限流/网络/错配都不重试，避免放大。
            if (result is WebCaptchaInit.Failed && result.reason == WebCaptchaFailure.SIGN_REJECTED) {
                XunleiWebSignConfig.load()
                val reloaded = XunleiWebSignConfig.current()
                if (reloaded != config) {
                    config = reloaded
                    result = runCatching { requestWebCaptcha(deviceId, action, config, clock()) }
                        .getOrElse { WebCaptchaInit.Failed(WebCaptchaFailure.NETWORK) }
                }
            }

            // 被限流：按 Retry-After 记冷却（没给就用默认值，且封顶），
            // 后续调用在冷却期内直接短路，不再消耗服务端配额（P2-08）。
            if (result is WebCaptchaInit.Failed && result.reason == WebCaptchaFailure.RATE_LIMITED) {
                markCaptchaRateLimited(result.retryAfterMs)
            } else if (result is WebCaptchaInit.Ok) {
                lastWebCaptchaRetryAfterMs = 0L
            }

            noteWebCaptchaFailure(
                when (result) {
                    is WebCaptchaInit.Ok -> null
                    is WebCaptchaInit.ClientIdMismatch -> WebCaptchaFailure.CLIENT_ID_MISMATCH
                    is WebCaptchaInit.Failed -> result.reason
                }
            )
            result
        }

    /** 单次 captcha/init 请求与响应分类（纯网络逻辑，便于 fake client 单测）。 */
    private fun requestWebCaptcha(
        deviceId: String,
        action: String,
        config: WebSignConfig,
        now: Long
    ): WebCaptchaInit {
        val ts = now
        val sign = XunleiWebCredential.computeCaptchaSign(deviceId, ts, config)
        val body = JSONObject()
            .put("client_id", XunleiWebCredential.CLIENT_ID)
            .put("action", action)
            .put("device_id", deviceId)
            .put(
                "meta",
                JSONObject()
                    .put("username", "")
                    .put("phone_number", "")
                    .put("email", "")
                    .put("package_name", XunleiWebCredential.WEB_PACKAGE_NAME)
                    .put("client_version", config.clientVersion)
                    .put("captcha_sign", sign)
                    .put("timestamp", ts.toString())
                    .put("user_id", currentUserId)
            )
            .toString()
        val request = Request.Builder()
            .url(XunleiConstants.CAPTCHA_INIT_URL)
            .header("Accept", "*/*")
            .header("Content-Type", "text/plain;charset=UTF-8")
            .header("Origin", "https://pan.xunlei.com")
            .header("Referer", "https://pan.xunlei.com/")
            .header("User-Agent", XunleiWebCredential.DESKTOP_UA)
            .post(body.toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val blob = raw.ifBlank { "{}" }
            // client_id 错配：错误体明文含该标记，换谁都救不回来，单独分类
            if (blob.contains("client_id not match", ignoreCase = true)) {
                return WebCaptchaInit.ClientIdMismatch
            }
            // 限流优先于 HTTP 状态判断：429 常带业务体，Retry-After 要给 UI 一个明确信号。
            // 注意只认「被拒绝」（非 2xx）的 Retry-After：200 上带这个头只是提示，不是拒绝，
            // 早期写法 `header != null` 会把带该头的正常响应整条误判成限流（P2-08）。
            val retryAfterHeader = resp.header("Retry-After")
            if (resp.code == 429 || (retryAfterHeader != null && !resp.isSuccessful)) {
                // 只解析 delta-seconds 形态；HTTP-date 形态（极少见）交给默认冷却兜底
                val retryAfterMs = retryAfterHeader?.trim()?.toLongOrNull()
                    ?.takeIf { it > 0 }?.times(1000L) ?: 0L
                return WebCaptchaInit.Failed(WebCaptchaFailure.RATE_LIMITED, retryAfterMs)
            }
            if (!resp.isSuccessful) {
                return WebCaptchaInit.Failed(WebCaptchaFailure.HTTP_ERROR)
            }
            val json = runCatching { JSONObject(blob) }.getOrElse {
                return WebCaptchaInit.Failed(WebCaptchaFailure.SIGN_REJECTED)
            }
            val newToken = json.optString("captcha_token")
            if (newToken.isBlank()) {
                // 有 error/error_description 而没给 token：签名被拒或版本过旧，归为可自愈类
                return WebCaptchaInit.Failed(WebCaptchaFailure.SIGN_REJECTED)
            }
            return WebCaptchaInit.Ok(
                PanCaptcha(newToken, ts + XunleiWebCredential.WEB_CAPTCHA_TOKEN_TTL_MS)
            )
        }
    }

    /** 用请求对应 action + 正确 captcha_sign 初始化 captcha（pan 专用，alist 算法已验证）。
     * 算法：raw = client_id+client_version+package_name+device_id+timestamp_ms，10 层 md5(raw+salt)，sign="1."+结果 */
    private suspend fun initPanCaptcha(deviceId: String, action: String, oldToken: String): String? =
        withContext(Dispatchers.IO) {
            val ts = System.currentTimeMillis().toString()
            val sign = buildCaptchaSign(deviceId, ts)
            val body = JSONObject()
                .put("client_id", XunleiConstants.APP_CLIENT_ID)
                .put("action", action)
                .put("device_id", deviceId)
                .put("redirect_uri", "xlaccsdk01://xunlei.com/callback?state=harbor")
                .put(
                    "meta",
                    JSONObject()
                        .put("client_version", XunleiConstants.APP_CLIENT_VERSION)
                        .put("package_name", XunleiConstants.APP_PACKAGE_NAME)
                        .put("timestamp", ts)
                        .put("captcha_sign", sign)
                        .put("user_id", currentUserId) // 真实 user_id，空会得到降级 token（POST 类接口拒绝）
                )
                .put("captcha_token", oldToken)
                .toString()
            val request = Request.Builder()
                .url(XunleiConstants.CAPTCHA_INIT_URL)
                .header("User-Agent", XunleiConstants.APP_UA)
                .header("Accept", "application/json;charset=UTF-8")
                .header("Content-Type", "application/json")
                .header("X-Client-Id", XunleiConstants.APP_CLIENT_ID)
                .header("X-Device-Id", deviceId)
                .header("X-Client-Version", "8.31.0.9726")
                .post(body.toRequestBody(jsonMediaType))
                .build()
            runCatching {
                client.newCall(request).execute().use { resp ->
                    // 同 initCaptcha：先判 HTTP，再判限流；错误页不进 JSONObject（P2-08）
                    val retryAfter = resp.header("Retry-After")
                    if (resp.code == 429 || (retryAfter != null && !resp.isSuccessful)) {
                        markCaptchaRateLimited(
                            retryAfter?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.times(1000L) ?: 0L
                        )
                        return@use null
                    }
                    if (!resp.isSuccessful) return@use null
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    json.optString("captcha_token").takeIf { it.isNotBlank() }
                }
            }.getOrNull()
        }

    /** captcha_sign：client_id+client_version+package_name+device_id+timestamp_ms → 10 层 md5(raw+salt)，前缀 "1." */
    private fun buildCaptchaSign(deviceId: String, tsMs: String): String {
        var h = XunleiConstants.APP_CLIENT_ID + XunleiConstants.APP_CLIENT_VERSION +
            XunleiConstants.APP_PACKAGE_NAME + deviceId + tsMs
        for (salt in XunleiConstants.CAPTCHA_SALTS) {
            h = md5Hex(h + salt)
        }
        return "1.$h"
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 迅雷中文口令（如「张三丰资源」）→ 带提取码的分享链接（免登录）。
     *
     * 口令是「分享链接 + 提取码」的打包形式：把口令当关键词打 shoulei 的搜索跳转接口，
     * `ext.kouling_type == "share_page"` 时 `location` 就是带 `pwd` 的分享页地址（明文，无需解密）；
     * 没有对应资源时只返回 `search_url`，这里按「口令无效」抛出。
     * 纯逻辑（判定 / 拼 URL / 解 location）在 [XunleiKouling]，便于单测。
     */
    suspend fun parseKouling(keyword: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(XunleiKouling.buildJumpUrl(keyword))
            .header("User-Agent", XunleiKouling.USER_AGENT)
            .header("Accept", "*/*")
            .header("Origin", XunleiKouling.ORIGIN)
            .header("Referer", XunleiKouling.REFERER)
            .header("Accept-Language", "zh-CN")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("口令解析失败（HTTP ${resp.code}）")
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: throw IllegalStateException("口令解析失败：响应格式异常")
            val type = json.optJSONObject("ext")?.optString("kouling_type").orEmpty()
            if (type != "share_page") throw IllegalStateException("口令「$keyword」没有对应的网盘分享")
            XunleiKouling.shareUrlFromLocation(json.optString("location"))
                ?: throw IllegalStateException("口令「$keyword」没有对应的网盘分享")
        }
    }

    companion object {
        /** captcha 缓存的键数量上限：超过就先清过期项，仍超则整体清空（token 只有 300s，重建代价几百毫秒） */
        private const val CAPTCHA_CACHE_LIMIT = 64

        /** 服务端没给 `Retry-After` 时的默认冷却（秒级抖动足够，不要立即重试） */
        private const val DEFAULT_RATE_LIMIT_COOLDOWN_MS = 60_000L

        /** 冷却上限：防止异常响应头（如 `Retry-After: 86400`）把功能锁死一整天 */
        private const val MAX_RATE_LIMIT_COOLDOWN_MS = 30 * 60_000L

        /** 设备 ID：动态生成的设备指纹（进程启动时由 Application 初始化并持久化） */
        fun newDeviceId(): String = XunleiDeviceFingerprint.deviceId()

        /**
         * 解析 reviewurl（review_panel 返回的验证面板链接）的查询参数。
         * 响应体通常自带 creditkey / token，可优先用于自有短信登录流，而非跳外部浏览器。
         */
        fun parseReviewUrl(reviewUrl: String): Map<String, String> {
            val map = mutableMapOf<String, String>()
            runCatching {
                val q = reviewUrl.substringAfter('?', "")
                q.split('&').forEach { pair ->
                    val parts = pair.split('=', limit = 2)
                    if (parts.size == 2) {
                        map[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                    } else if (parts.size == 1 && parts[0].isNotBlank()) {
                        map[parts[0]] = ""
                    }
                }
            }
            return map
        }
    }
}

package top.mcly99.dl.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P2-08 回归：迅雷 captcha 的**缓存键维度**与**限流冷却**。
 *
 * 两类真实故障：
 * 1. 缓存只按 `deviceId`：同一账号先列目录再删文件，第二次会复用第一次的 token。而
 *    `captcha/init` 的 body 带 `action`、`meta.user_id`，服务端签发的 token 与两者绑定，
 *    复用必然判 `captcha_invalid`；多账号共用设备号时更糟——B 账号拿到 A 的验证，写操作
 *    被降级拒绝且错误文案指不到原因。
 * 2. 被 429 后没有冷却：上层是「失败就重签」，每次解析都再打一次 `captcha/init`，
 *    限流窗口被越拖越长（请求放大）。
 *
 * 这里用 OkHttp 拦截器伪造服务端，不发真实网络请求。
 */
class XunleiCaptchaCacheTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun captcha_cache_is_scoped_by_action() = runBlocking {
        val fake = FakeXunleiServer()
        val api = XunleiApi(clientProvider = { fake.client }, clock = { t0 })
        val actions = mutableListOf<String>()
        api.webCaptchaRefresher = { _, action ->
            actions += action
            PanCaptcha("tok-${actions.size}", t0 + 270_000)
        }

        api.getFiles("", "at", DEVICE, "")
        api.createFolder("dir", "", "at", DEVICE, "")

        // 两个 action 必须各签一次：token 与 action 绑定，复用只会换来一次 captcha_invalid
        assertEquals(listOf("GET:/drive/v1/files", "POST:/drive/v1/files"), actions)
        assertEquals(listOf("tok-1", "tok-2"), fake.panCaptchaTokens())
    }

    @Test
    fun captcha_cache_is_scoped_by_account() = runBlocking {
        val fake = FakeXunleiServer()
        val api = XunleiApi(clientProvider = { fake.client }, clock = { t0 })
        var minted = 0
        api.webCaptchaRefresher = { _, _ -> PanCaptcha("tok-${++minted}", t0 + 270_000) }

        api.cacheUserId(jwt("1001"), force = true)
        api.getFiles("", "at-a", DEVICE, "")
        api.cacheUserId(jwt("2002"), force = true)
        api.getFiles("", "at-b", DEVICE, "")

        // 同一设备同一 action，换账号必须重签：captcha 里带 meta.user_id
        assertEquals(2, minted)
        assertEquals(listOf("tok-1", "tok-2"), fake.panCaptchaTokens())
    }

    @Test
    fun same_scope_reuses_cached_token_without_resigning() = runBlocking {
        val fake = FakeXunleiServer()
        val api = XunleiApi(clientProvider = { fake.client }, clock = { t0 })
        var minted = 0
        api.webCaptchaRefresher = { _, _ -> PanCaptcha("tok-${++minted}", t0 + 270_000) }

        repeat(3) { api.getFiles("", "at", DEVICE, "") }

        // 维度一致就必须命中缓存，否则每次列表都要多打一次 captcha/init
        assertEquals(1, minted)
        assertEquals(listOf("tok-1", "tok-1", "tok-1"), fake.panCaptchaTokens())
    }

    @Test
    fun rate_limit_sets_cooldown_and_stops_further_requests() = runBlocking {
        val fake = FakeXunleiServer().apply {
            captchaStatus = 429
            captchaRetryAfter = "30"
        }
        var now = t0
        val api = XunleiApi(clientProvider = { fake.client }, clock = { now })

        val first = api.initWebCaptcha(DEVICE, ACTION) as WebCaptchaInit.Failed
        assertEquals(WebCaptchaFailure.RATE_LIMITED, first.reason)
        assertEquals(30_000L, first.retryAfterMs)
        assertEquals(30_000L, api.lastWebCaptchaRetryAfterMs)
        assertEquals(30_000L, api.captchaCooldownRemainingMs())
        assertEquals(1, fake.captchaInitCount)

        // 冷却期内：一个请求都不发，直接短路，并回报剩余时间
        now += 29_000
        val second = api.initWebCaptcha(DEVICE, ACTION) as WebCaptchaInit.Failed
        assertEquals(WebCaptchaFailure.RATE_LIMITED, second.reason)
        assertEquals(1_000L, second.retryAfterMs)
        assertEquals(1, fake.captchaInitCount)

        // 冷却结束后恢复
        now += 2_000
        fake.captchaStatus = 200
        assertTrue(api.initWebCaptcha(DEVICE, ACTION) is WebCaptchaInit.Ok)
        assertEquals(2, fake.captchaInitCount)
        assertEquals(0L, api.captchaCooldownRemainingMs())
        assertEquals(0L, api.lastWebCaptchaRetryAfterMs)
    }

    @Test
    fun rate_limit_without_retry_after_falls_back_to_default_cooldown() = runBlocking {
        val fake = FakeXunleiServer().apply { captchaStatus = 429 }
        val api = XunleiApi(clientProvider = { fake.client }, clock = { t0 })

        val failed = api.initWebCaptcha(DEVICE, ACTION) as WebCaptchaInit.Failed
        assertEquals(WebCaptchaFailure.RATE_LIMITED, failed.reason)
        // 服务端没给 Retry-After：用默认 60s，而不是「0 = 立刻重试」
        assertEquals(0L, failed.retryAfterMs)
        assertEquals(60_000L, api.captchaCooldownRemainingMs())
    }

    @Test
    fun server_html_error_page_is_not_parsed_as_json() = runBlocking {
        val fake = FakeXunleiServer().apply {
            captchaStatus = 502
            captchaBody = "<html><body>502 Bad Gateway</body></html>"
        }
        val api = XunleiApi(clientProvider = { fake.client }, clock = { t0 })

        val failed = api.initWebCaptcha(DEVICE, ACTION) as WebCaptchaInit.Failed
        // 错误页不能被压成 NETWORK/SIGN_REJECTED，要如实报 HTTP 错误
        assertEquals(WebCaptchaFailure.HTTP_ERROR, failed.reason)
    }

    // ---------------------------------------------------------------- 辅助

    private fun jwt(sub: String): String {
        fun b64(raw: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
        return "${b64("""{"alg":"none"}""")}.${b64("""{"sub":"$sub"}""")}.sig"
    }

    private class FakeXunleiServer {
        val requests = mutableListOf<Request>()
        var captchaStatus = 200
        var captchaRetryAfter: String? = null
        var captchaBody = """{"captcha_token":"ct-1","expires_in":300}"""

        val captchaInitCount: Int get() = requests.count { it.url.encodedPath.endsWith("captcha/init") }

        fun panCaptchaTokens(): List<String> =
            requests.filterNot { it.url.encodedPath.endsWith("captcha/init") }
                .map { it.header("X-Captcha-Token").orEmpty() }

        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                requests += req
                val isCaptcha = req.url.encodedPath.endsWith("captcha/init")
                val builder = Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .body(
                        (if (isCaptcha) captchaBody else """{"id":"f1","files":[]}""")
                            .toResponseBody("application/json".toMediaType())
                    )
                val code = if (isCaptcha) captchaStatus else 200
                builder.code(code).message(if (code == 200) "OK" else "Error")
                if (isCaptcha && captchaRetryAfter != null) {
                    builder.header("Retry-After", captchaRetryAfter!!)
                }
                builder.build()
            }
            .build()
    }

    private companion object {
        const val DEVICE = "dev-1"
        const val ACTION = "GET:/drive/v1/files"
    }
}

package top.mcly99.dl.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P2-13 回归：蓝奏云原生账号密码登录的重定向 / Cookie 域校验。
 *
 * 旧实现：登录专用 client `followSslRedirects(true)`，CookieJar 无域校验地把该 host
 * 全部 Cookie 用 `domain(url.host)` 重建后回传；POST 表单含**明文 password**，
 * 一个被篡改的 302 就可能把密码（307/308 会重发 body）或登录 Cookie 带出可信域。
 *
 * 这里用 OkHttp 拦截器伪造整个登录链路，不发真实网络请求。
 */
class LanzouLoginSecurityTest {

    // ---------------------------------------------------------------- 纯函数：目标域

    @Test
    fun trusted_url_requires_https_and_woozooo_domain() {
        assertTrue(isTrustedLanzouLoginUrl("https://accounts.woozooo.com/accounts.php".toHttpUrl()))
        assertTrue(isTrustedLanzouLoginUrl("https://pc.woozooo.com/mydisk.php".toHttpUrl()))
        assertTrue(isTrustedLanzouLoginUrl("https://woozooo.com/x".toHttpUrl()))
        assertTrue(!isTrustedLanzouLoginUrl("http://pc.woozooo.com/x".toHttpUrl()))
        assertTrue(!isTrustedLanzouLoginUrl("https://evil.example.com/x".toHttpUrl()))
        // 后缀必须是「点 + woozooo.com」，避免形似域名蒙混过关
        assertTrue(!isTrustedLanzouLoginUrl("https://woozooo.com.evil.com/x".toHttpUrl()))
        assertTrue(!isTrustedLanzouLoginUrl("https://evilwoozooo.com/x".toHttpUrl()))
    }

    @Test
    fun redirect_resolution_rejects_downgrade_and_off_domain() {
        val current = "https://accounts.woozooo.com/accounts.php".toHttpUrl()
        assertEquals("pc.woozooo.com", resolveTrustedLanzouRedirect(current, "https://pc.woozooo.com/relay")?.host)
        assertEquals("accounts.woozooo.com", resolveTrustedLanzouRedirect(current, "/next")?.host)
        assertEquals("pc.woozooo.com", resolveTrustedLanzouRedirect(current, "//pc.woozooo.com/relay")?.host)
        assertNull(resolveTrustedLanzouRedirect(current, "http://pc.woozooo.com/relay"))
        assertNull(resolveTrustedLanzouRedirect(current, "https://evil.example.com/steal"))
        // 无 scheme 的畸形串会被当作相对路径留在同域，不会跳出可信域（这是预期行为）
        assertEquals(
            "accounts.woozooo.com",
            resolveTrustedLanzouRedirect(current, "://not-a-url")?.host,
        )
    }

    // ---------------------------------------------------------------- Cookie 罐

    @Test
    fun cookie_jar_never_returns_cookies_over_http_or_to_other_hosts() {
        val jar = LanzouLoginCookieJar()
        val pc = "https://pc.woozooo.com/mydisk.php".toHttpUrl()
        jar.saveFromResponse(
            pc,
            listOf(Cookie.Builder().domain("pc.woozooo.com").path("/").name("ylogin").value("secret").build()),
        )
        jar.put("pc.woozooo.com", "phpdisk_info", "disk")

        // 明文 http、别的域都不该回传
        assertTrue(jar.loadForRequest("http://pc.woozooo.com/mydisk.php".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://evil.example.com/x".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://accounts.woozooo.com/x".toHttpUrl()).isEmpty())

        val sent = jar.loadForRequest(pc)
        assertEquals(2, sent.size)
        assertTrue(sent.all { it.secure && it.httpOnly })
        assertEquals("secret", sent.first { it.name == "ylogin" }.value)
        assertEquals(mapOf("ylogin" to "secret", "phpdisk_info" to "disk"), jar.snapshot("pc.woozooo.com"))
    }

    @Test
    fun cookie_jar_ignores_set_cookie_from_untrusted_responses() {
        val jar = LanzouLoginCookieJar()
        jar.saveFromResponse(
            "http://pc.woozooo.com/mydisk.php".toHttpUrl(),
            listOf(Cookie.Builder().domain("pc.woozooo.com").path("/").name("ylogin").value("leak").build()),
        )
        jar.saveFromResponse(
            "https://evil.example.com/x".toHttpUrl(),
            listOf(Cookie.Builder().domain("evil.example.com").path("/").name("ylogin").value("leak").build()),
        )
        assertTrue(jar.loadForRequest("https://pc.woozooo.com/mydisk.php".toHttpUrl()).isEmpty())
        assertTrue(jar.snapshot("pc.woozooo.com").isEmpty())
    }

    // ---------------------------------------------------------------- login() 链路

    @Test
    fun https_redirect_chain_still_logs_in() = runBlocking {
        val fake = FakeLanzouServer()
        val api = LanzouApi(clientProvider = { fake.client })
        // 应用拦截器会短路 OkHttp 的 BridgeInterceptor，无法触发 Set-Cookie 回写；
        // 这里预置登录 Cookie，专注验证「可信 https 跳转链没有被拦下」这一条。
        val jar = LanzouLoginCookieJar().apply {
            put("pc.woozooo.com", "ylogin", "u1")
            put("pc.woozooo.com", "phpdisk_info", "p1")
        }

        val cookie = api.login("user", "pass", jar)

        assertTrue(cookie.contains("ylogin=u1"), cookie)
        assertTrue(cookie.contains("phpdisk_info=p1"), cookie)
        // 中转页的 302 确实被手动跟了一次，而不是被整个跳过
        assertTrue(fake.requests.any { it.url.encodedPath == "/done" })
    }

    @Test
    fun password_post_never_follows_a_redirect() = runBlocking {
        val fake = FakeLanzouServer().apply {
            postCode = 302
            postLocation = "http://evil.example.com/steal"
        }
        val api = LanzouApi(clientProvider = { fake.client })

        val error = runCatching { api.login("user", "pass") }.exceptionOrNull()
        assertTrue(error?.message?.contains("登录接口返回了重定向") == true, "实际: $error")
        // 关键：带明文密码的 POST 只发了一次，绝不能因为 307/308 重发到别处
        assertEquals(1, fake.requests.count { it.method == "POST" })
        assertTrue(fake.requests.none { it.url.host == "evil.example.com" })
    }

    @Test
    fun msgs_over_http_or_off_domain_is_rejected_before_any_request() = runBlocking {
        for (bad in listOf("http://pc.woozooo.com/relay", "https://evil.example.com/relay")) {
            val fake = FakeLanzouServer().apply {
                postBody = """{"zt":"1","msgs":"$bad"}"""
            }
            val api = LanzouApi(clientProvider = { fake.client })
            val error = runCatching { api.login("user", "pass") }.exceptionOrNull()
            assertTrue(error?.message?.contains("中转地址不在可信域") == true, "$bad → 实际: $error")
            assertTrue(fake.requests.none { it.method == "GET" }, "$bad 不应发出任何中转请求")
        }
    }

    @Test
    fun redirect_to_http_or_off_domain_fails_closed() = runBlocking {
        for (bad in listOf("http://pc.woozooo.com/done", "https://evil.example.com/steal")) {
            val fake = FakeLanzouServer().apply { relayLocation = bad }
            val api = LanzouApi(clientProvider = { fake.client })
            val error = runCatching { api.login("user", "pass") }.exceptionOrNull()
            assertTrue(error is LanzouLoginRejected, "$bad → 实际: $error")
            assertTrue(error.message?.contains("跳转目标不在可信域") == true, "$bad → 实际: $error")
            assertTrue(fake.requests.none { it.url.host == "evil.example.com" }, "$bad 不应被请求")
        }
    }

    @Test
    fun redirect_loop_is_bounded() = runBlocking {
        val fake = FakeLanzouServer().apply { relayLocation = "https://pc.woozooo.com/relay" }
        val api = LanzouApi(clientProvider = { fake.client })

        val error = runCatching { api.login("user", "pass") }.exceptionOrNull()
        assertTrue(error is LanzouLoginRejected, "实际: $error")
        assertTrue(error.message?.contains("跳转次数过多") == true, "实际: $error")
        assertTrue(
            fake.requests.count { it.url.encodedPath == "/relay" } <= MAX_REDIRECTS,
            "重定向必须有上限，实际请求数: ${fake.requests.size}",
        )
    }

    // ---------------------------------------------------------------- 假服务端

    /**
     * 用「应用拦截器」返回合成响应：OkHttp 4 的网络拦截器必须 `proceed()` 恰好一次，
     * 不能短路。Cookie 罐的行为已由上面的单元测试直接覆盖，这里专注请求形状与跳转策略。
     */
    private class FakeLanzouServer {
        val requests = mutableListOf<Request>()
        var postCode = 200
        var postLocation: String? = null
        var postBody = """{"zt":"1","msgs":"https://pc.woozooo.com/relay"}"""
        var relayLocation: String? = "https://pc.woozooo.com/done"

        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                synchronized(requests) { requests += req }
                val builder = Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                when {
                    req.url.host == "accounts.woozooo.com" && req.method == "POST" -> {
                        builder.code(postCode).message("POST")
                        postLocation?.let { builder.header("Location", it) }
                        builder.body(postBody.toResponseBody("application/json".toMediaType()))
                    }
                    req.url.encodedPath == "/relay" -> {
                        if (relayLocation != null) {
                            builder.code(302).message("Found").header("Location", relayLocation!!)
                        } else {
                            builder.code(200).message("OK")
                        }
                        builder.addHeader("Set-Cookie", "ylogin=u1; Path=/")
                        builder.addHeader("Set-Cookie", "phpdisk_info=p1; Path=/")
                        builder.body("relay".toResponseBody("text/plain".toMediaType()))
                    }
                    else -> {
                        builder.code(200).message("OK")
                            .addHeader("Set-Cookie", "PHPSESSID=sess; Path=/")
                            .body("ok".toResponseBody("text/plain".toMediaType()))
                    }
                }
                builder.build()
            }
            .build()
    }

    private companion object {
        const val MAX_REDIRECTS = 8
    }
}

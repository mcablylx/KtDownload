package top.mcly99.dl.engine

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * P2-18 回归：网页上传访问令牌的强度、轮转、常量时间比较与失败限速。
 *
 * 旧实现：UUID 前 16 hex = **64 bit**、进程内不轮转、`==` 非常量时间、失败无限速、
 * 令牌走 URL query 且内嵌进上传页 JS。现在令牌只在一次性引导路径出现，换 HttpOnly 会话
 * Cookie 后上传页与上传请求都不再持有令牌；这里用本机 HttpServer + 假时钟验证，不发外网请求。
 */
class WebUploadServerTokenTest {

    private lateinit var dir: File
    private val servers = mutableListOf<WebUploadServer>()
    private var now = T0

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("webupload-token-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop() }
        dir.deleteRecursively()
    }

    private fun newServer(bindHost: String = "0.0.0.0"): WebUploadServer =
        WebUploadServer(saveDir = dir.absolutePath, bindHost = bindHost, clock = { now })
            .also { servers += it }

    @Test
    fun token_is_128_bit_lowercase_hex() {
        val server = newServer()
        assertTrue(server.start(freePort()))
        val token = server.accessToken
        assertEquals(32, token.length, "128 bit = 32 位 hex，实际: $token")
        assertTrue(token.all { it in '0'..'9' || it in 'a'..'f' }, "令牌必须是小写 hex: $token")
    }

    @Test
    fun token_rotates_on_actual_start_but_not_on_same_port_restart() {
        val server = newServer()
        val port = freePort()
        assertTrue(server.start(port))
        val first = server.accessToken

        // 同端口重复 start 不应轮转：否则界面上刚展示的地址会立刻失效
        assertTrue(server.start(port))
        assertEquals(first, server.accessToken)

        // 换端口 = 真正重启，旧地址必须作废
        val another = freePort()
        assertTrue(server.start(another))
        val second = server.accessToken
        assertNotEquals(first, second)
        assertTrue(server.accessUrl("127.0.0.1:$another").contains(second))
        assertFalse(server.accessUrl("127.0.0.1:$another").contains(first))
    }

    @Test
    fun rotation_callback_receives_the_current_token() {
        val server = newServer()
        val observed = mutableListOf<String>()
        server.onTokenRotated = { observed += it }
        assertTrue(server.start(freePort()))

        assertEquals(1, observed.size)
        assertEquals(server.accessToken, observed.single())

        val rotated = server.rotateAccessToken()
        assertEquals(2, observed.size)
        assertEquals(rotated, observed.last())
    }

    @Test
    fun manual_rotation_invalidates_the_old_url_immediately() {
        val server = newServer()
        val port = freePort()
        assertTrue(server.start(port))
        val old = server.accessToken
        val fresh = server.rotateAccessToken()

        assertNotEquals(old, fresh)
        assertEquals(403, get(port, old).first)
        assertEquals(200, get(port, fresh).first)
    }

    @Test
    fun repeated_wrong_tokens_are_rate_limited_and_recover_after_lockout() {
        val server = newServer()
        val port = freePort()
        assertTrue(server.start(port))

        // 前 MAX_TOKEN_FAILURES 次只是 403，之后进入锁定窗口
        repeat(WebUploadServer.MAX_TOKEN_FAILURES) {
            assertEquals(403, get(port, "wrong-token").first, "第 ${it + 1} 次失败应只回 403")
        }
        val locked = get(port, server.accessToken)
        assertEquals(429, locked.first, "失败次数用尽后，连正确令牌也必须先等锁定窗口过去")
        assertTrue((locked.second.header("Retry-After")?.toIntOrNull() ?: 0) > 0, "429 必须带 Retry-After")

        // 锁定期间不再放行
        now += WebUploadServer.TOKEN_LOCKOUT_MS - 1
        assertEquals(429, get(port, server.accessToken).first)

        // 窗口过后恢复正常
        now += 2
        assertEquals(200, get(port, server.accessToken).first)
    }

    @Test
    fun a_successful_auth_clears_the_failure_counter() {
        val server = newServer()
        val port = freePort()
        assertTrue(server.start(port))

        repeat(WebUploadServer.MAX_TOKEN_FAILURES - 1) {
            assertEquals(403, get(port, "wrong-token").first)
        }
        // 一次成功把该来源的失败记录清掉，后续再试错不应立刻被锁死
        assertEquals(200, get(port, server.accessToken).first)
        repeat(WebUploadServer.MAX_TOKEN_FAILURES - 1) {
            assertEquals(403, get(port, "wrong-token").first)
        }
        assertEquals(200, get(port, server.accessToken).first)
        assertNotEquals(429, get(port, "wrong-token").first)
    }

    @Test
    fun prefix_and_wrong_length_tokens_are_rejected() {
        val server = newServer()
        val port = freePort()
        assertTrue(server.start(port))
        val token = server.accessToken

        assertEquals(403, get(port, token.take(31)).first)
        assertEquals(403, get(port, "$token-extra").first)
        assertEquals(403, get(port, "").first)
        assertEquals(403, get(port, null).first)
        assertEquals(200, get(port, token).first)
    }

    @Test
    fun upload_page_sends_no_referrer_policy() {
        val server = newServer()
        val port = freePort()
        assertTrue(server.start(port))
        val (code, headers) = get(port, server.accessToken)
        assertEquals(200, code)
        assertEquals("no-referrer", headers.header("Referrer-Policy"))
        assertEquals("no-store", headers.header("Cache-Control"))
    }

    @Test
    fun custom_bind_host_is_honored_and_invalid_host_fails_closed() {
        val loopback = newServer(bindHost = "127.0.0.1")
        val port = freePort()
        assertTrue(loopback.start(port))
        assertEquals(200, get(port, loopback.accessToken).first)

        // 显式指定监听地址时，无效主机名必须让 start 失败，而不是悄悄退回 0.0.0.0
        val invalid = newServer(bindHost = "256.256.256.256")
        assertFalse(invalid.start(freePort()), "不可解析的 bindHost 不应降级成全网卡监听")
    }

    @Test
    fun bootstrap_exchanges_token_for_httponly_cookie_redirects_to_token_free_url() {
        val server = newServer()
        val port = freePort()
        assertTrue(server.start(port))

        val result = WebUploadTestSession.bootstrap(port, server.accessToken)
        assertEquals(302, result.code)
        val location = result.headers.entries.first { it.key.equals("Location", ignoreCase = true) }.value
        assertEquals("/", location.trim(), "登录后地址栏应回到无 token 的 /")
        val setCookie = result.headers.entries.first { it.key.equals("Set-Cookie", ignoreCase = true) }.value
        assertTrue(setCookie.contains("HttpOnly"), setCookie)
        assertTrue(setCookie.contains("SameSite=Lax"), setCookie)
        assertTrue(setCookie.contains("Path=/"), setCookie)
        assertFalse(setCookie.contains("Secure=true"), "局域网 http 场景不加 Secure，源码注释需如实说明")
        assertTrue(result.cookie!!.startsWith("${WebUploadServer.SESSION_COOKIE}="), result.cookie)
        assertFalse(result.cookie.contains(server.accessToken), "Cookie 里不能再放引导令牌本体")

        val (code, _, _) = getWithCookie(port, result.cookie)
        assertEquals(200, code, "只带会话 Cookie 就该能打开上传页")
    }

    @Test
    fun upload_page_script_never_contains_the_bootstrap_token() {
        val server = newServer()
        val port = freePort()
        assertTrue(server.start(port))
        val result = WebUploadTestSession.bootstrap(port, server.accessToken)
        assertEquals(302, result.code)

        val (code, _, html) = getWithCookie(port, result.cookie)
        assertEquals(200, code)
        assertFalse(html.contains(server.accessToken), "上传页 JS 不应再内嵌令牌")
        assertTrue(html.contains("var uploadUrl = '/';"), "上传请求应走同源路径 + 会话 Cookie")
    }

    @Test
    fun rotating_the_token_invalidates_sessions_issued_from_the_old_one() {
        val server = newServer()
        val port = freePort()
        assertTrue(server.start(port))
        val session = WebUploadTestSession.bootstrap(port, server.accessToken)
        assertEquals(302, session.code)
        assertEquals(200, getWithCookie(port, session.cookie).first)

        server.rotateAccessToken()
        assertEquals(403, getWithCookie(port, session.cookie).first, "令牌轮转后旧会话必须立即失效")
        val fresh = WebUploadTestSession.bootstrap(port, server.accessToken)
        assertEquals(302, fresh.code)
        assertEquals(200, getWithCookie(port, fresh.cookie).first)
    }

    @Test
    fun sessions_expire_on_the_injected_clock() {
        val server = newServer()
        val port = freePort()
        assertTrue(server.start(port))
        val session = WebUploadTestSession.bootstrap(port, server.accessToken)
        assertEquals(302, session.code)

        now += WebUploadServer.SESSION_TTL_MS + 1
        assertEquals(403, getWithCookie(port, session.cookie).first, "空闲超时后会话应失效")
    }

    // ---------------------------------------------------------------- 辅助

    /** @return HTTP 状态码 + 响应头（名称大小写按 JDK 返回原样） */
    private fun get(port: Int, token: String?): Pair<Int, Map<String, String>> {
        // 引导失败时直接返回引导响应：锁定（429）必须能被子用例观察到，
        // 不能退化成「无 Cookie 访问 / 的 403」，否则限速回归会失真。
        val bootstrap = WebUploadTestSession.bootstrap(port, token)
        if (bootstrap.code != 302) return bootstrap.code to bootstrap.headers

        val (code, headers, _) = getWithCookie(port, bootstrap.cookie)
        return code to headers
    }

    /** 不带引导令牌、只带会话 Cookie 访问上传页，返回状态码 + 响应头 + 正文 */
    private fun getWithCookie(
        port: Int,
        cookie: String?,
    ): Triple<Int, Map<String, String>, String> {
        val conn = (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 10_000
            cookie?.let { setRequestProperty("Cookie", it) }
        }
        return try {
            val code = conn.responseCode
            val headers = conn.headerFields.mapNotNull { (k, v) ->
                k?.let { it to v.joinToString(",") }
            }.toMap()
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            Triple(code, headers, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** JDK 返回的头名大小写不保证，按大小写不敏感查 */
    private fun Map<String, String>.header(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private companion object {
        const val T0 = 1_700_000_000_000L
    }
}

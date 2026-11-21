package top.mcly99.dl.engine

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 对齐上游 v1.3.4 的「浏览器插件 Cookie 含中文导致下载中断」修复（下游口径更严）。 */
class HttpHeaderValuesTest {

    private lateinit var server: HttpServer
    private lateinit var executor: java.util.concurrent.ExecutorService
    private val seen = java.util.concurrent.ConcurrentLinkedQueue<String>()

    @BeforeTest
    fun setUp() {
        seen.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/") { exchange ->
            seen.add(exchange.requestHeaders.getFirst("Cookie"))
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        executor.shutdownNow()
    }

    @Test
    fun ascii_header_values_are_untouched() {
        val value = "BDUSS=abc123; STOKEN=xyz-_./%40; __puus=1"
        assertEquals(value, sanitizeHttpHeaderValue(value))
    }

    @Test
    fun chinese_and_emoji_are_utf8_percent_encoded() {
        assertEquals("sid=%E4%B8%AD%E6%96%87", sanitizeHttpHeaderValue("sid=中文"))
        assertEquals("sid=%F0%9F%98%80", sanitizeHttpHeaderValue("sid=😀"))
        assertEquals("a=%E4%B8%AD; b=ok", sanitizeHttpHeaderValue("a=中; b=ok"))
    }

    @Test
    fun control_characters_are_encoded_too_so_headers_cannot_be_injected() {
        assertEquals("x=%0D%0ASet-Cookie: evil", sanitizeHttpHeaderValue("x=\r\nSet-Cookie: evil"))
        assertEquals("x=%09%00%7F", sanitizeHttpHeaderValue("x=\t\u0000\u007F"))
    }

    @Test
    fun structured_json_credentials_are_not_mangled() {
        val json = "{\"access_token\":\"令牌\",\"refresh_token\":\"abc\"}"
        assertEquals(json, sanitizeCredentialForHeaders(json))
        assertEquals("BDUSS=%E4%B8%AD%E6%96%87", sanitizeCredentialForHeaders("BDUSS=中文"))
    }

    @Test
    fun connection_helper_really_sends_the_sanitized_value() {
        val connection = java.net.URL("http://127.0.0.1:${server.address.port}/").openConnection() as HttpURLConnection
        connection.setSafeHeader("Cookie", "sid=中文\r\nX-Injected: 1")
        connection.responseCode
        connection.disconnect()
        assertEquals(1, seen.size)
        assertEquals("sid=%E4%B8%AD%E6%96%87%0D%0AX-Injected: 1", seen.single())
        assertTrue(seen.single().all { it.code in 0x20..0x7E })
    }
}

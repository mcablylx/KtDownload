package top.mcly99.dl.engine.web

import com.sun.net.httpserver.HttpServer
import top.mcly99.dl.engine.player.QualityOptions
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HttpProbe] 集成测试：本地 [HttpServer] 造播放列表、HEAD 与 Range 响应。
 *
 * 只覆盖 P3 用到的两条能力（读主播放列表、探文件大小），不发外网请求。
 */
class HttpProbeTest {

    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private lateinit var baseUrl: String
    private val lastReferer = AtomicReference<String?>(null)
    private val lastCookie = AtomicReference<String?>(null)
    private val crossTargetCookie = AtomicReference<String?>(null)

    @BeforeTest
    fun setUp() {
        executor = Executors.newFixedThreadPool(4)
        // 通配绑定：127.0.0.2 也指向本机，用它模拟「跨域」但不依赖真实外网
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.executor = executor

        server.createContext("/playlist.m3u8") { exchange ->
            lastReferer.set(exchange.requestHeaders.getFirst("Referer"))
            val body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nv.m3u8\n"
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        // 带 Cookie 的探测：服务器回显是否收到 Cookie，用于验证会话能带上、且不破坏请求方法设置
        server.createContext("/cookie-playlist.m3u8") { exchange ->
            lastCookie.set(exchange.requestHeaders.getFirst("Cookie"))
            val body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\ncookie-v.m3u8\n"
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        // 跨域重定向（127.0.0.1 → 127.0.0.2）：目标端点记录是否收到 Cookie
        server.createContext("/cross-redirect.m3u8") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.2:${server.address.port}/cross-target.m3u8")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/cross-target.m3u8") { exchange ->
            crossTargetCookie.set(exchange.requestHeaders.getFirst("Cookie"))
            val body = "#EXTM3U\ncross-v.m3u8\n".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        server.createContext("/empty.m3u8") { exchange ->
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }

        // 主列表 302 到另一个目录：档位里的相对路径必须基于最终地址解析
        server.createContext("/master.m3u8") { exchange ->
            exchange.responseHeaders.add("Location", "$baseUrl/moved/master.m3u8")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }

        server.createContext("/moved/master.m3u8") { exchange ->
            val body = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
                v/720/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080
                v/1080/index.m3u8
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        // HEAD 直接给 Content-Length（必须手写响应头：HttpServer 对 HEAD 不接受正数长度）
        server.createContext("/head-ok") { exchange ->
            exchange.responseHeaders.set("Content-Length", "12345")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }

        // 常见 CDN：HEAD 405，但带 Range 的 GET 正常
        server.createContext("/head-rejected") { exchange ->
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            if (exchange.requestHeaders.getFirst("Range") == null) {
                exchange.sendResponseHeaders(400, -1)
                exchange.close()
                return@createContext
            }
            exchange.responseHeaders.add("Content-Range", "bytes 0-0/98765")
            exchange.sendResponseHeaders(206, 1)
            exchange.responseBody.use { it.write(byteArrayOf(0)) }
        }

        server.createContext("/forbidden") { exchange ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }

        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        executor.shutdownNow()
    }

    // ---------------------------------------------------------------- 播放列表

    @Test
    fun fetch_text_returns_playlist_body() {
        val text = HttpProbe().fetchText("$baseUrl/playlist.m3u8")

        assertTrue(text!!.startsWith("#EXTM3U"))
        assertTrue(text.contains("v.m3u8"))
    }

    @Test
    fun fetch_text_sends_referer_for_hotlink_protection() {
        HttpProbe().fetchText("$baseUrl/playlist.m3u8", referer = "https://page.example.com/watch/1")

        // 主播放列表本身也常校验 Referer，漏了这条清晰度探测就永远拿不到档位
        assertEquals("https://page.example.com/watch/1", lastReferer.get())
    }

    @Test
    fun fetch_text_with_cookie_succeeds() {
        // 回归 P0-1：cookie 路径下 followRedirectsKeepingCookie 已建立连接，
        // 若 fetchTextWithUrl 再设 requestMethod 会抛 Already connected 被吞成 null
        val text = HttpProbe().fetchText("$baseUrl/cookie-playlist.m3u8", cookie = "STOKEN=abc")
        assertTrue(text!!.startsWith("#EXTM3U"), "带 Cookie 的探测必须成功返回播放列表")
        assertEquals("STOKEN=abc", lastCookie.get())
    }

    @Test
    fun cookie_not_leaked_on_cross_host_redirect() {
        val text = HttpProbe().fetchText("$baseUrl/cross-redirect.m3u8", cookie = "STOKEN=secret")
        assertTrue(text!!.startsWith("#EXTM3U"))
        assertNull(crossTargetCookie.get(), "会话 Cookie 不得随跨域重定向发送到第三方主机")
    }

    @Test
    fun fetch_text_returns_null_on_http_error() {
        assertNull(HttpProbe().fetchText("$baseUrl/forbidden"))
    }

    @Test
    fun fetch_text_returns_null_on_empty_body() {
        assertNull(HttpProbe().fetchText("$baseUrl/empty.m3u8"))
    }

    @Test
    fun fetch_text_reports_final_url_after_redirect() {
        val probed = HttpProbe().fetchTextWithUrl("$baseUrl/master.m3u8")

        assertEquals("$baseUrl/moved/master.m3u8", probed!!.finalUrl)
        // 档位里的相对路径必须基于重定向后的地址解析，否则会拼到错误目录
        val options = QualityOptions.fromMaster(probed.text, probed.finalUrl)
        assertEquals("$baseUrl/moved/v/1080/index.m3u8", options.first().url)
    }

    // ---------------------------------------------------------------- 文件大小

    @Test
    fun content_length_uses_head_when_available() {
        assertEquals(12345L, HttpProbe().contentLength("$baseUrl/head-ok"))
    }

    @Test
    fun content_length_falls_back_to_range_get() {
        // HEAD 被 CDN 拒绝时不能直接放弃：Range: bytes=0-0 只传 1 字节
        assertEquals(98765L, HttpProbe().contentLength("$baseUrl/head-rejected"))
    }

    @Test
    fun content_length_is_null_when_both_paths_fail() {
        assertNull(HttpProbe().contentLength("$baseUrl/forbidden"))
    }

    @Test
    fun content_length_is_null_for_unreachable_host() {
        // 端口 1 基本不可能有服务在听；探测失败只能是 null，不能抛异常
        assertNull(HttpProbe().contentLength("http://127.0.0.1:1/a.mp4"))
    }

    // ---------------------------------------------------------------- 头解析

    @Test
    fun content_range_total_is_parsed() {
        assertEquals(123456L, HttpProbe.parseTotalFromContentRange("bytes 0-0/123456"))
        assertEquals(1L, HttpProbe.parseTotalFromContentRange("bytes 0-0/1"))
    }

    @Test
    fun content_range_with_unknown_total_is_null() {
        // 服务器不知道总长时规范允许写 `*`
        assertNull(HttpProbe.parseTotalFromContentRange("bytes 0-0/*"))
        assertNull(HttpProbe.parseTotalFromContentRange("bytes 0-0/0"))
        assertNull(HttpProbe.parseTotalFromContentRange("garbage"))
        assertNull(HttpProbe.parseTotalFromContentRange(""))
    }
}

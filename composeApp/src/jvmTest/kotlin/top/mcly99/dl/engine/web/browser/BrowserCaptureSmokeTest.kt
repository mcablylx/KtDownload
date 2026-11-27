package top.mcly99.dl.engine.web.browser

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import top.mcly99.dl.engine.web.MediaKind
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 深度抓取的**真浏览器**冒烟测试（默认不跑）。
 *
 * 这条链路（`CdpSession` + `awaitDebugPort` + `awaitPageTarget` + `toCandidate`）没有任何
 * 纯单测能覆盖：它的价值就在于「一个只会用 JS 换地址的页面，真的能被拦下来」。
 * mock 掉浏览器恰好把唯一想验证的东西（Chrome 升级后行为有没有变）也 mock 掉了。
 *
 * - 只有设置环境变量 `KTDL_CDP_SMOKE=1` 时才执行，其余情况用 JUnit Assume **标记为 SKIPPED**
 *   （P2-29：不再静默 return 假装通过）；
 * - 需要一个本机 Chrome / Edge / Chromium，找不到同样标记 SKIPPED；
 * - 本地假站点，不依赖外部网络：`/page` 用 `fetch` 换地址 → `/api/play` 返回带签名的 m3u8。
 *
 * 手工验证命令：
 * ```
 * KTDL_CDP_SMOKE=1 ./gradlew :composeApp:jvmTest --tests "*BrowserCaptureSmokeTest"
 * ```
 */
class BrowserCaptureSmokeTest {

    @Test
    fun captures_a_playlist_injected_by_javascript() {
        assumeTrue(
            "未设置 KTDL_CDP_SMOKE=1，跳过真实浏览器冒烟",
            System.getenv("KTDL_CDP_SMOKE") == "1",
        )
        val browser = BrowserLocator.find()
        assumeTrue("本机没有 Chrome / Edge / Chromium，无法跑 CDP 冒烟测试", browser != null)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/page") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            // 顺带验证 Network.getAllCookies 那条链路：会话 Cookie 必须能带回下载侧
            exchange.responseHeaders.add("Set-Cookie", "sid=smoke-123; Path=/")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            respond(
                exchange,
                """
                <!doctype html>
                <html><body>
                <script>
                  fetch('/api/play')
                    .then(r => r.json())
                    .then(j => fetch(j.url));
                </script>
                </body></html>
                """.trimIndent(),
            )
        }
        server.createContext("/api/play") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            respond(exchange, """{"url":"/media/index.m3u8?wsSecret=abc&wsTime=123"}""")
        }
        server.createContext("/media/index.m3u8") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            respond(exchange, "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-ENDLIST\n")
        }
        server.start()

        try {
            val pageUrl = "http://127.0.0.1:${server.address.port}/page"
            val result = BrowserCapture(browser = browser!!).capture(pageUrl)
            val playlist = result.candidates.firstOrNull { it.url.contains("index.m3u8") }

            assertNotNull(
                playlist,
                "没有抓到 JS 注入的 m3u8：failure=${result.failure} detail=${result.detail} " +
                    "candidates=${result.candidates.map { it.url }} stats=${result.stats}",
            )
            assertEquals(MediaKind.M3u8, playlist.kind)
            // 签名参数必须原样保留：下载真正要用的就是这条带 query 的地址
            assertTrue(playlist.url.contains("wsSecret=abc"), "地址被改写了: ${playlist.url}")
            // 页面 + /api/play + m3u8 至少三个响应
            assertTrue(result.stats.requestsSeen >= 3, "只收到 ${result.stats.requestsSeen} 个响应")
            assertTrue(result.stats.candidatesFound >= 1)
            assertEquals("sid=smoke-123", result.cookieFor(playlist.url))
            assertNotNull(result.stats.browser?.version, "浏览器版本探测失败：${result.stats.browser}")
            println("CDP 冒烟通过：${result.stats}")
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

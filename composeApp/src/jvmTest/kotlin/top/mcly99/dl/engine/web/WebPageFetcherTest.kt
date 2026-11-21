package top.mcly99.dl.engine.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [WebPageFetcher] 集成测试：用本地 [HttpServer] 造各种「不友好」的响应。
 *
 * 覆盖的是编码识别、重定向、非网页、超限截断、错误码这几条真实站点最常踩的坑。
 * 这里不发任何外网请求。
 */
class WebPageFetcherTest {

    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private lateinit var baseUrl: String

    private val gbk: Charset = Charset.forName("GBK")

    private val flakyHits = AtomicInteger(0)
    private val downHits = AtomicInteger(0)
    private val missingHits = AtomicInteger(0)

    @BeforeTest
    fun setUp() {
        executor = Executors.newFixedThreadPool(2)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor

        server.createContext("/plain") { exchange ->
            respond(
                exchange,
                status = 200,
                contentType = "text/html; charset=UTF-8",
                body = """<html><a href="/v/a.mp4">预告片</a></html>""".toByteArray(StandardCharsets.UTF_8),
            )
        }

        server.createContext("/gbk-header") { exchange ->
            respond(
                exchange,
                status = 200,
                contentType = "text/html; charset=GBK",
                body = "<html><title>中文标题</title></html>".toByteArray(gbk),
            )
        }

        server.createContext("/gbk-meta") { exchange ->
            respond(
                exchange,
                status = 200,
                // 响应头不带 charset，只能靠 meta 判断
                contentType = "text/html",
                body = """<html><head><meta charset="gbk"></head><body>中文正文</body></html>"""
                    .toByteArray(gbk),
            )
        }

        // 老中文站常见的写法：声明 gb2312，实际内容却是 GBK 超集
        server.createContext("/gb2312") { exchange ->
            respond(
                exchange,
                status = 200,
                contentType = "text/html; charset=gb2312",
                body = "<html><title>老站点</title></html>".toByteArray(gbk),
            )
        }

        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/plain")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }

        server.createContext("/missing") { exchange ->
            missingHits.incrementAndGet()
            respond(exchange, status = 404, contentType = "text/html", body = "not found".toByteArray())
        }

        // Cloudflare 522：第一次连不上源站，第二次正常 —— 重试一次就该成功
        server.createContext("/flaky-522") { exchange ->
            if (flakyHits.incrementAndGet() == 1) {
                respond(exchange, status = 522, contentType = "text/html", body = "origin timeout".toByteArray())
            } else {
                respond(
                    exchange,
                    status = 200,
                    contentType = "text/html; charset=UTF-8",
                    body = """<html><a href="/v/b.mp4">重试后</a></html>""".toByteArray(StandardCharsets.UTF_8),
                )
            }
        }

        // 源站一直挂着：重试一次后仍然要如实报 5xx，不能无限重试
        server.createContext("/always-500") { exchange ->
            downHits.incrementAndGet()
            respond(exchange, status = 500, contentType = "text/html", body = "boom".toByteArray())
        }

        server.createContext("/binary") { exchange ->
            respond(
                exchange,
                status = 200,
                contentType = "application/octet-stream",
                body = ByteArray(32),
            )
        }

        server.createContext("/huge") { exchange ->
            respond(
                exchange,
                status = 200,
                contentType = "text/html; charset=UTF-8",
                body = "a".repeat(8 * 1024).toByteArray(StandardCharsets.UTF_8),
            )
        }

        // 无视 Accept-Encoding: identity，坚持返回 gzip 的站点
        server.createContext("/gzip") { exchange ->
            val raw = """<html><a href="/v/c.mp4">压缩过的页面</a></html>"""
                .toByteArray(StandardCharsets.UTF_8)
            val compressed = ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { it.write(raw) }
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, compressed.size.toLong())
            exchange.responseBody.use { it.write(compressed) }
        }

        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        executor.shutdownNow()
    }

    @Test
    fun fetches_html_and_reports_final_url() : Unit = runBlocking {
        val page = WebPageFetcher().fetch("$baseUrl/plain")

        assertEquals("$baseUrl/plain", page.finalUrl)
        assertTrue(page.html.contains("预告片"))
        assertEquals("UTF-8", page.charset)
    }

    @Test
    fun follows_redirect_and_uses_final_url_as_base() : Unit = runBlocking {
        val page = WebPageFetcher().fetch("$baseUrl/redirect")

        // 相对路径必须基于重定向后的地址解析，否则会拼到 /redirect 这一层上
        assertEquals("$baseUrl/plain", page.finalUrl)
        assertTrue(page.html.contains("a.mp4"))
    }

    @Test
    fun decodes_gbk_declared_in_content_type() : Unit = runBlocking {
        val page = WebPageFetcher().fetch("$baseUrl/gbk-header")

        assertTrue(page.html.contains("中文标题"), "实际: ${page.html}")
        assertEquals("GBK", page.charset)
    }

    @Test
    fun decodes_gbk_declared_in_meta_charset() : Unit = runBlocking {
        val page = WebPageFetcher().fetch("$baseUrl/gbk-meta")

        assertTrue(page.html.contains("中文正文"), "实际: ${page.html}")
    }

    @Test
    fun treats_gb2312_as_gbk() : Unit = runBlocking {
        val page = WebPageFetcher().fetch("$baseUrl/gb2312")

        // GBK 是 GB2312 的超集：按 GB2312 严格解码会丢掉部分汉字
        assertTrue(page.html.contains("老站点"), "实际: ${page.html}")
        assertEquals("GBK", page.charset)
    }

    @Test
    fun reports_http_error_code() : Unit = runBlocking {
        val failure = assertFailsWith<FetchFailure.HttpError> {
            WebPageFetcher().fetch("$baseUrl/missing")
        }

        assertEquals(404, failure.code)
    }

    @Test
    fun rejects_non_html_content_type() : Unit = runBlocking {
        assertFailsWith<FetchFailure.NotHtml> {
            WebPageFetcher().fetch("$baseUrl/binary")
        }
    }

    @Test
    fun truncates_oversized_page_instead_of_failing() : Unit = runBlocking {
        val page = WebPageFetcher(maxBytes = 1024).fetch("$baseUrl/huge")

        assertEquals(1024, page.html.length)
    }

    @Test
    fun decodes_gzip_body_when_server_ignores_identity() : Unit = runBlocking {
        // 少数站点坚持压缩：不兜底的话提取器会在一堆压缩字节里找不到任何地址
        val page = WebPageFetcher().fetch("$baseUrl/gzip")

        assertTrue(page.html.contains("c.mp4"), "实际: ${page.html}")
    }

    @Test
    fun rejects_invalid_url_without_touching_network() : Unit = runBlocking {
        assertFailsWith<FetchFailure.InvalidUrl> { WebPageFetcher().fetch("   ") }
        assertFailsWith<FetchFailure.InvalidUrl> { WebPageFetcher().fetch("ftp://example.com/a") }
        assertFailsWith<FetchFailure.InvalidUrl> { WebPageFetcher().fetch("magnet:?xt=urn:btih:x") }
    }

    @Test
    fun connection_failure_is_reported_as_network_error() : Unit = runBlocking {
        // 端口 1 基本不可能有服务在听
        assertFailsWith<FetchFailure.NetworkError> {
            WebPageFetcher().fetch("http://127.0.0.1:1/")
        }
    }

    // ---------------------------------------------------------------- 5xx 重试

    @Test
    fun retries_once_when_the_origin_returns_522() : Unit = runBlocking {
        // Cloudflare 522 = 连不上源站，多半是瞬时超时，隔一会儿再来经常就好了
        val page = WebPageFetcher().fetch("$baseUrl/flaky-522")

        assertTrue(page.html.contains("b.mp4"), "实际: ${page.html}")
        assertEquals(2, flakyHits.get())
    }

    @Test
    fun gives_up_after_one_retry_and_keeps_the_5xx_code() : Unit = runBlocking {
        val failure = assertFailsWith<FetchFailure.HttpError> {
            WebPageFetcher().fetch("$baseUrl/always-500")
        }

        // 重试不能掩盖真实状态：UI 要靠这个码告诉用户「是站点挂了」
        assertEquals(500, failure.code)
        assertEquals(2, downHits.get())
    }

    @Test
    fun does_not_retry_4xx() : Unit = runBlocking {
        // 404 / 403 是确定性的，重试只会让用户多等
        assertFailsWith<FetchFailure.HttpError> { WebPageFetcher().fetch("$baseUrl/missing") }

        assertEquals(1, missingHits.get())
    }

    @Test
    fun cancelling_during_retry_wait_prevents_the_second_request() : Unit = runBlocking {
        val job = launch(Dispatchers.Default) {
            runCatching { WebPageFetcher().fetch("$baseUrl/always-500") }
        }
        // 等第一次请求真的发出去（重试等待 700ms 之后才会发第二次）
        while (downHits.get() == 0) delay(10)
        job.cancelAndJoin()

        // 再等一个完整重试间隔：取消若没打断 delay，第二次请求此时已经发出去了
        delay(900)
        assertEquals(1, downHits.get())
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        contentType: String,
        body: ByteArray,
    ) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}

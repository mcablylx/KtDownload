package top.mcly99.dl.engine.web

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [MediaSizeProbe] 的并发与容错测试（本地 [HttpServer]）。
 *
 * 关键契约：**任何一条探测失败都只是「这条没有大小」**，不能影响其它条目，
 * 也不能让整批抛异常 —— 否则打开一个开关就会让抓取结果整体变空。
 */
class MediaSizeProbeTest {

    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private lateinit var baseUrl: String
    private val requests = AtomicInteger(0)
    private val lastReferer = AtomicReference<String?>(null)
    private val inFlight = AtomicInteger(0)
    private val maxInFlight = AtomicInteger(0)

    @BeforeTest
    fun setUp() {
        executor = Executors.newFixedThreadPool(4)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor

        server.createContext("/a.mp4") { exchange ->
            requests.incrementAndGet()
            lastReferer.set(exchange.requestHeaders.getFirst("Referer"))
            respondWithLength(exchange, 1000L)
        }
        server.createContext("/b.mp4") { exchange ->
            requests.incrementAndGet()
            respondWithLength(exchange, 2000L)
        }
        server.createContext("/missing.mp4") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }

        // 慢响应 + 在途计数：用来验证 probe() 真的按 concurrency 限流
        server.createContext("/slow") { exchange ->
            val now = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            Thread.sleep(80)
            inFlight.decrementAndGet()
            respondWithLength(exchange, 1000L)
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
    fun keeps_successful_sizes_and_drops_failures() = runBlocking {
        val sizes = MediaSizeProbe(HttpProbe()).probe(
            listOf(
                SizeProbeTarget("$baseUrl/a.mp4"),
                SizeProbeTarget("$baseUrl/missing.mp4"),
                SizeProbeTarget("$baseUrl/b.mp4"),
                // 连不上的地址混在中间：不能拖垮整批
                SizeProbeTarget("http://127.0.0.1:1/c.mp4"),
            ),
            concurrency = 2,
        )

        assertEquals(
            mapOf("$baseUrl/a.mp4" to 1000L, "$baseUrl/b.mp4" to 2000L),
            sizes,
        )
    }

    @Test
    fun duplicate_urls_are_probed_once() = runBlocking {
        val sizes = MediaSizeProbe(HttpProbe()).probe(
            listOf(SizeProbeTarget("$baseUrl/a.mp4"), SizeProbeTarget("$baseUrl/a.mp4")),
            concurrency = 2,
        )

        assertEquals(mapOf("$baseUrl/a.mp4" to 1000L), sizes)
        assertEquals(1, requests.get())
    }

    @Test
    fun referer_is_forwarded_to_the_probe() = runBlocking {
        MediaSizeProbe(HttpProbe()).probe(
            listOf(SizeProbeTarget("$baseUrl/a.mp4", referer = "https://page.example.com/watch/1")),
        )

        // 防盗链 CDN 不带 Referer 会 403，大小就永远探不到
        assertEquals("https://page.example.com/watch/1", lastReferer.get())
    }

    @Test
    fun empty_targets_return_empty_without_requests() = runBlocking {
        assertTrue(MediaSizeProbe(HttpProbe()).probe(emptyList()).isEmpty())
        assertEquals(0, requests.get())
    }

    @Test
    fun never_exceeds_the_requested_concurrency() = runBlocking {
        // 一次全发出去会被 CDN 当扫描，也会打满连接池 —— 分批是刻意的
        val targets = (1..8).map { SizeProbeTarget("$baseUrl/slow?i=$it") }

        val sizes = MediaSizeProbe(HttpProbe()).probe(targets, concurrency = 2)

        assertEquals(8, sizes.size)
        assertTrue(maxInFlight.get() <= 2, "最大并发 ${maxInFlight.get()} 超过 2")
    }

    /** 手写 Content-Length：HttpServer 对 HEAD 请求不接受正数长度参数 */
    private fun respondWithLength(exchange: com.sun.net.httpserver.HttpExchange, length: Long) {
        exchange.responseHeaders.set("Content-Length", length.toString())
        exchange.sendResponseHeaders(200, -1)
        exchange.close()
    }
}

package top.mcly99.dl.engine

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import top.mcly99.dl.model.DownloadSource
import top.mcly99.dl.model.DownloadStatus
import top.mcly99.dl.model.DownloadTask
import top.mcly99.dl.testutil.TestTimeouts
import top.mcly99.dl.testutil.awaitElement
import top.mcly99.dl.testutil.isTerminal
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C2 单文件多源分片的本地 HTTP 回归（零真实网络）。
 *
 * 覆盖四件上游没有保证的事：
 * 1. 备选源真的会被分片消费（上游 `pendingAltLinks` 从未传给 `enqueueDownload`）；
 * 2. 每条直链带的是**自己**的 Cookie，而不是主源那份；
 * 3. 某个源失败时该分片换下一个源重试，整任务仍能完成；
 * 4. 备选源返回的 `Content-Range` 总长与主源不一致时被弃用，避免字节错位合并。
 */
class DownloadEngineMultiSourceTest {

    private lateinit var server: HttpServer
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var engine: DownloadEngine
    private lateinit var tempDir: File

    /** 每次请求记一条 `"<tag>|<Cookie>"`，用来核对源与请求头是否成对 */
    private val requests = ConcurrentLinkedQueue<String>()

    /** 备选源模拟 403（失败换源用例） */
    private var brokenAlt = false

    /** 备选源谎报 Content-Range 总长（跨源长度校验用例） */
    private var wrongTotalAlt = false

    private val data = ByteArray(128 * 1024) { (it * 31 + 7).toByte() }

    @BeforeTest
    fun setUp() {
        requests.clear()
        brokenAlt = false
        wrongTotalAlt = false
        tempDir = Files.createTempDirectory("ktdown-multisource-test").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        executor = Executors.newFixedThreadPool(8)
        server.executor = executor
        server.createContext("/primary") { serve(it, "primary", "acc=primary", broken = false, wrongTotal = false) }
        server.createContext("/alt") {
            serve(it, "alt", "acc=second", broken = brokenAlt, wrongTotal = wrongTotalAlt)
        }
        server.start()
        engine = DownloadEngine(
            taskPersistenceEnabled = false,
            multiThreadThresholdBytes = 64L * 1024L,
        )
    }

    @AfterTest
    fun tearDown() {
        runCatching { engine.shutdown() }
        server.stop(0)
        executor.shutdownNow()
        tempDir.deleteRecursively()
    }

    @Test
    fun chunks_rotate_between_sources_and_each_keeps_its_own_cookie() {
        val target = tempDir.resolve("multi.bin")
        val task = download(
            target,
            altSources = listOf(altSource()),
        )

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage)
        assertContentEquals(data, target.readBytes())
        val seen = requests.toList()
        // 两个源都被分片轮转用到（HEAD / Range 探测只走主源，不影响这个结论）
        assertEquals(setOf("primary", "alt"), seen.map { it.substringBefore('|') }.toSet())
        assertTrue(seen.filter { it.startsWith("primary|") }.all { it == "primary|acc=primary" })
        assertTrue(seen.filter { it.startsWith("alt|") }.all { it == "alt|acc=second" })
        assertEquals(1, engine.getTask(task.id)!!.altSources.size)
    }

    @Test
    fun a_failing_source_is_replaced_for_that_chunk_without_failing_the_task() {
        brokenAlt = true
        val target = tempDir.resolve("fallback.bin")
        val task = download(target, altSources = listOf(altSource()))

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage)
        assertContentEquals(data, target.readBytes())
        assertTrue(requests.any { it.startsWith("alt|") }, "备选源至少要被尝试一次")
        // 备选源全挂，所有分片最终都由主源完成
        assertTrue(requests.count { it.startsWith("primary|") } >= 4)
    }

    @Test
    fun a_source_reporting_a_different_total_length_is_rejected() {
        wrongTotalAlt = true
        val target = tempDir.resolve("length-guard.bin")
        val task = download(target, altSources = listOf(altSource()))

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage)
        // 关键：文件没有被另一个长度的副本拼坏
        assertContentEquals(data, target.readBytes())
        assertTrue(requests.any { it.startsWith("alt|") }, "长度不一致的源应当被尝试后弃用")
    }

    @Test
    fun duplicate_blank_and_primary_urls_are_dropped_from_the_source_list() {
        engine.maxConcurrentDownloads = 0
        val primary = url("/primary")
        val id = engine.addTask(
            url = primary,
            savePath = tempDir.resolve("dedup.bin").toPath(),
            threads = 4,
            totalSize = data.size.toLong(),
            cookie = "acc=primary",
            altSources = listOf(
                DownloadSource(url = primary, cookie = "acc=primary"),
                DownloadSource(url = url("/alt"), cookie = "acc=second"),
                DownloadSource(url = url("/alt"), cookie = "acc=second"),
                DownloadSource(url = "", cookie = "acc=empty"),
            ),
        )
        assertEquals(1, engine.getTask(id)!!.altSources.size)
    }

    // ---------------------------------------------------------------- 辅助

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun altSource() = DownloadSource(
        url = url("/alt"),
        cookie = "acc=second",
        referer = "https://pan.quark.cn/",
    )

    private fun download(target: File, altSources: List<DownloadSource>): DownloadTask {
        val id = engine.addTask(
            url = url("/primary"),
            savePath = target.toPath(),
            threads = 4,
            totalSize = data.size.toLong(),
            cookie = "acc=primary",
            referer = "https://pan.quark.cn/",
            altSources = altSources,
        )
        return awaitTerminal(id)
    }

    private fun awaitTerminal(id: String): DownloadTask = runBlocking {
        engine.taskState.awaitElement(TestTimeouts.LONG, { "任务 $id 未在超时前进入终态" }) {
            it.id == id && it.status.isTerminal
        }
    }

    private fun serve(
        exchange: HttpExchange,
        tag: String,
        requiredCookie: String,
        broken: Boolean,
        wrongTotal: Boolean,
    ) {
        try {
            val cookie = exchange.requestHeaders.getFirst("Cookie")
            requests.add("$tag|$cookie")
            if (broken || cookie != requiredCookie) {
                exchange.sendResponseHeaders(403, -1)
                return
            }
            if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.responseHeaders.add("Content-Length", data.size.toString())
                exchange.sendResponseHeaders(200, -1)
                return
            }
            val range = exchange.requestHeaders.getFirst("Range")
            val (start, end) = if (range.isNullOrBlank()) {
                0 to data.size - 1
            } else {
                parseRange(range) ?: run {
                    exchange.sendResponseHeaders(416, -1)
                    return
                }
            }
            val length = end - start + 1
            if (range.isNullOrBlank()) {
                exchange.sendResponseHeaders(200, length.toLong())
            } else {
                val reported = if (wrongTotal) data.size * 2 else data.size
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$reported")
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.sendResponseHeaders(206, length.toLong())
            }
            exchange.responseBody.use { it.write(data, start, length) }
        } finally {
            exchange.close()
        }
    }

    /** 只解析引擎实际会发的 `bytes=start-end` */
    private fun parseRange(header: String): Pair<Int, Int>? {
        if (!header.startsWith("bytes=", ignoreCase = true)) return null
        val spec = header.substringAfter('=').trim()
        val start = spec.substringBefore('-').toIntOrNull() ?: return null
        val end = spec.substringAfter('-').toIntOrNull() ?: (data.size - 1)
        if (start !in 0 until data.size || end < start) return null
        return start to end.coerceAtMost(data.size - 1)
    }
}

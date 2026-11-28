package top.mcly99.dl.engine

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import top.mcly99.dl.model.DownloadStatus
import top.mcly99.dl.model.DownloadTask
import top.mcly99.dl.testutil.TestTimeouts
import top.mcly99.dl.testutil.awaitElement
import top.mcly99.dl.testutil.isTerminal
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D / E / H 三项的本地回归（零真实网络）：
 *
 * - **D 首包即探测**：探测阶段只允许发一次 `Range: bytes=0-0`；
 * - **E 主机连接上限学习**：服务器只允许 2 条并发时，任务仍要成功，且不再反复白撞；
 * - **H 每连接退避重试**：注入两次 500，任务必须自己重试成功，用户无感知。
 */
class DownloadEngineResilienceTest {

    private lateinit var server: HttpServer
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var engine: DownloadEngine
    private lateinit var tempDir: File

    /** `Range: bytes=0-0` 探测请求次数（D 项的核心指标） */
    private val probeRangeHits = AtomicInteger(0)

    /** 所有带 Range 的请求次数 */
    private val rangeHits = AtomicInteger(0)

    /** `/flaky` 注入并已经真的返回 500 的次数 */
    private val injectedFailures = AtomicInteger(0)

    /** `/limited` 因超过并发上限而返回 503 的次数 */
    private val rejectedByConcurrency = AtomicInteger(0)

    /** `/limited` 当前并发收数据的连接数与其峰值 */
    private val concurrentGets = AtomicInteger(0)
    private val peakConcurrentGets = AtomicInteger(0)

    private val data = ByteArray(256 * 1024) { (it * 37 + 13).toByte() }

    private companion object {
        /** 4 PiB：任何本机磁盘都装不下，用来触发空间预检 */
        const val HUGE_SIZE = 1L shl 52
    }

    @BeforeTest
    fun setUp() {
        probeRangeHits.set(0)
        rangeHits.set(0)
        injectedFailures.set(0)
        rejectedByConcurrency.set(0)
        concurrentGets.set(0)
        peakConcurrentGets.set(0)
        tempDir = Files.createTempDirectory("ktdown-resilience-test").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        executor = Executors.newFixedThreadPool(12)
        server.executor = executor
        // HEAD 直接 405（夸克式 CDN），只能靠 Range 探测拿大小
        server.createContext("/nohead") { exchange ->
            if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
            } else {
                serve(exchange, failFirst = 0, concurrencyLimit = Int.MAX_VALUE)
            }
        }
        // 前两次分块请求回 500，之后正常（H 项）
        server.createContext("/flaky") { exchange -> serve(exchange, failFirst = 2, concurrencyLimit = Int.MAX_VALUE) }
        // 最多允许 2 条并发收数据，超出回 503（E 项）
        server.createContext("/limited") { exchange -> serve(exchange, failFirst = 0, concurrencyLimit = 2) }
        // 声明一个远超本机容量的长度，只回应探测；真来 GET 就直接 503（附：磁盘空间预检）
        server.createContext("/huge") { exchange ->
            if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.responseHeaders.add("Content-Length", HUGE_SIZE.toString())
                exchange.sendResponseHeaders(200, -1)
            } else {
                exchange.sendResponseHeaders(503, -1)
            }
            exchange.close()
        }
        server.start()
        engine = DownloadEngine(
            taskPersistenceEnabled = false,
            multiThreadThresholdBytes = 64L * 1024L,
            segmentBytes = 64L * 1024L,
        )
    }

    @AfterTest
    fun tearDown() {
        runCatching { engine.shutdown() }
        server.stop(0)
        executor.shutdownNow()
        tempDir.deleteRecursively()
    }

    /**
     * 探测请求去重：HEAD 不可用时，`Range: bytes=0-0` 只应发一次。
     *
     * 旧实现里 `probeRemote` 已经发过一次 `bytes=0-0`，`supportsRangeRequests` 又会为
     * 「支不支持分段」再发一次一模一样的请求 —— 两个重复的 RTT，纯浪费。
     */
    @Test
    fun probe_sends_the_zero_range_request_exactly_once() {
        val target = tempDir.resolve("nohead.bin")
        val task = download("/nohead", target, totalSize = 0L, threads = 4)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage)
        assertContentEquals(data, target.readBytes())
        assertEquals(
            1,
            probeRangeHits.get(),
            "探测阶段的 bytes=0-0 只应出现一次，实际 ${probeRangeHits.get()} 次",
        )
        // 1 次探测 + 4 个分块
        assertEquals(5, rangeHits.get(), "总 Range 请求数应为 1 探测 + 4 分块")
    }

    /** 注入两次 500：任务必须自己退避重试成功，且内容一字不差。 */
    @Test
    fun transient_500_is_retried_without_failing_the_task() {
        val target = tempDir.resolve("flaky.bin")
        val task = download("/flaky", target, totalSize = data.size.toLong(), threads = 4)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage)
        assertContentEquals(data, target.readBytes())
        assertEquals(2, injectedFailures.get(), "两次 500 都真的发生过，才说明重试路径被走到")
    }

    /**
     * 服务器只允许 2 条并发：任务仍要成功，且不能反复白撞。
     *
     * 旧实现下 4 条连接里有 2 条被判失败，`downloadAllChunks` 抛 `lastFailure`，整任务红。
     * 现在被拒的连接把段放回池、自己退出，外层按学到的上限（2）重规划。
     */
    @Test
    fun a_host_that_allows_two_connections_still_completes_the_task() {
        val target = tempDir.resolve("limited.bin")
        val task = download("/limited", target, totalSize = data.size.toLong(), threads = 4)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage)
        assertContentEquals(data, target.readBytes())
        assertTrue(
            peakConcurrentGets.get() <= 2,
            "服务端只允许 2 条并发，实际峰值 ${peakConcurrentGets.get()}",
        )
        assertTrue(
            rejectedByConcurrency.get() <= 4,
            "被拒次数应有限（学一次就够），实际 ${rejectedByConcurrency.get()}",
        )
    }

    /**
     * 磁盘空间预检的接线：远端声明 4 PiB，本机不可能装得下，必须在写第一个字节之前就失败，
     * 并且失败原因要是人话（「空间不足」），而不是下到一半的 `IOException: not enough space`。
     */
    @Test
    fun a_file_larger_than_the_disk_fails_before_any_byte_is_written() {
        val target = tempDir.resolve("huge.bin")
        val task = download("/huge", target, totalSize = HUGE_SIZE, threads = 4)

        assertEquals(DownloadStatus.FAILED, task.status, "装不下的文件不该开始下载")
        assertTrue(
            task.errorMessage.orEmpty().contains("空间不足"),
            "失败原因要说清是磁盘不够：${task.errorMessage}",
        )
        assertTrue(!target.exists(), "不该留下目标文件")
        assertTrue(
            !partialDownloadFile(target).exists(),
            "预检失败时不该已经建出临时文件",
        )
    }

    // ---------------------------------------------------------------- 辅助

    private fun download(path: String, target: File, totalSize: Long, threads: Int): DownloadTask {
        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}$path",
            savePath = target.toPath(),
            threads = threads,
            totalSize = totalSize,
        )
        return runBlocking {
            engine.taskState.awaitElement(TestTimeouts.LONG, { "任务 $id 未在超时前进入终态" }) {
                it.id == id && it.status.isTerminal
            }
        }
    }

    private fun serve(exchange: HttpExchange, failFirst: Int, concurrencyLimit: Int) {
        try {
            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            val isProbe = rangeHeader == "bytes=0-0"
            if (!rangeHeader.isNullOrBlank()) {
                rangeHits.incrementAndGet()
                if (isProbe) probeRangeHits.incrementAndGet()
            }

            if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.responseHeaders.add("Content-Length", data.size.toString())
                exchange.sendResponseHeaders(200, -1)
                return
            }

            // 探测请求不参与并发计数：它只是问一句「支不支持」，不该被当成占用的连接
            if (!isProbe) {
                val now = concurrentGets.incrementAndGet()
                if (now > concurrencyLimit) {
                    concurrentGets.decrementAndGet()
                    rejectedByConcurrency.incrementAndGet()
                    exchange.sendResponseHeaders(503, -1)
                    return
                }
                // 峰值只统计「真的被接纳」的连接，被拒的那条不算
                peakConcurrentGets.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            }
            try {
                // 只在真正的分块请求上注入失败，探测不受影响
                if (!isProbe && failFirst > 0 && injectedFailures.get() < failFirst) {
                    injectedFailures.incrementAndGet()
                    exchange.sendResponseHeaders(500, -1)
                    return
                }
                val (start, end) = if (rangeHeader.isNullOrBlank()) {
                    0 to data.size - 1
                } else {
                    parseRange(rangeHeader) ?: run {
                        exchange.sendResponseHeaders(416, -1)
                        return
                    }
                }
                val length = end - start + 1
                if (rangeHeader.isNullOrBlank()) {
                    exchange.sendResponseHeaders(200, length.toLong())
                } else {
                    exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${data.size}")
                    exchange.responseHeaders.add("Accept-Ranges", "bytes")
                    exchange.sendResponseHeaders(206, length.toLong())
                }
                exchange.responseBody.use { out ->
                    // 分几批写并稍作停顿：让并发的连接真的重叠，才测得出峰值
                    var sent = 0
                    while (sent < length) {
                        val n = minOf(16 * 1024, length - sent)
                        out.write(data, start + sent, n)
                        out.flush()
                        sent += n
                        Thread.sleep(2)
                    }
                }
            } finally {
                if (!isProbe) concurrentGets.decrementAndGet()
            }
        } finally {
            exchange.close()
        }
    }

    private fun parseRange(header: String): Pair<Int, Int>? {
        if (!header.startsWith("bytes=", ignoreCase = true)) return null
        val spec = header.substringAfter('=').trim()
        val start = spec.substringBefore('-').toIntOrNull() ?: return null
        val end = spec.substringAfter('-').toIntOrNull() ?: (data.size - 1)
        if (start !in 0 until data.size || end < start) return null
        return start to end.coerceAtMost(data.size - 1)
    }
}

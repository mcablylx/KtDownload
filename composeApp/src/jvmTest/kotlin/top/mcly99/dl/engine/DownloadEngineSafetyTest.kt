package top.mcly99.dl.engine

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import top.mcly99.dl.model.DownloadStatus
import top.mcly99.dl.model.DownloadTask
import top.mcly99.dl.testutil.TestTimeouts
import top.mcly99.dl.testutil.awaitElement
import top.mcly99.dl.testutil.awaitUntil
import top.mcly99.dl.testutil.awaitValue
import top.mcly99.dl.testutil.isTerminal
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 第 1 批数据安全修复的回归测试。
 *
 * 覆盖四件事，都是「以前会静默做错、现在必须明确做对」的行为：
 * 1. 服务端给空 body 时不能标完成（P1-8）；
 * 2. 「全部暂停」必须覆盖排队中的任务，且暂停后不能被调度器立刻补位（P1-1）；
 * 3. 移任务（`deleteFile=false`）也要清 `{name}.hls` 分片目录（P1-2）；
 * 4. 并发上限调大后，排队任务不需要等下一次任务事件就能起来（P1-6）。
 */
class DownloadEngineSafetyTest {

    private lateinit var server: HttpServer
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var engine: DownloadEngine
    private lateinit var tempDir: File

    /** 慢速端点：1MB，每 16KB 停 20ms —— 够长，能在下载中途插入断言 */
    private val slowData = ByteArray(1024 * 1024) { (it * 13 + 7).toByte() }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("ktdown-safety-test").toFile()
        engine = DownloadEngine(
            taskPersistenceEnabled = false,
            multiThreadThresholdBytes = 64L * 1024L,
        )
        executor = Executors.newFixedThreadPool(8)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor

        // 明确声明长度 0：有的 CDN 对过期直链就是这么回（200 而不是 403）。
        // JDK HttpServer 里 `sendResponseHeaders(200, -1)` = 没有响应体 → Content-Length: 0
        server.createContext("/empty") { exchange ->
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        // chunked 空 body：没有 Content-Length，长度未知，最容易走成「0 字节也算完成」
        server.createContext("/chunked-empty") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
            exchange.close()
        }
        server.createContext("/slow") { exchange -> slowResponse(exchange, supportsRange = false) }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        runCatching { engine.shutdown() }
        server.stop(0)
        executor.shutdownNow()
        tempDir.deleteRecursively()
    }

    @Test
    fun empty_body_is_failed_not_completed() {
        val target = tempDir.resolve("empty.bin")
        val task = downloadAndWait("/empty", target)

        assertEquals(DownloadStatus.FAILED, task.status, "0 字节被当成完成，用户会得到空文件")
        assertEquals(EMPTY_DOWNLOAD_MESSAGE, task.errorMessage)
        assertEquals(0L, target.length())
    }

    @Test
    fun chunked_empty_body_is_failed_not_completed() {
        val target = tempDir.resolve("chunked.bin")
        val task = downloadAndWait("/chunked-empty", target)

        assertEquals(DownloadStatus.FAILED, task.status, "长度未知 + 空 body 也必须判失败")
        assertEquals(EMPTY_DOWNLOAD_MESSAGE, task.errorMessage)
    }

    @Test
    fun pause_all_also_pauses_queued_tasks_and_does_not_refill() {
        // 并发 1：能让「暂停一个，立刻补位下一个」这个 bug 显形
        engine.maxConcurrentDownloads = 1
        val ids = (1..3).map { i ->
            engine.addTask(
                url = "http://127.0.0.1:${server.address.port}/slow",
                savePath = tempDir.resolve("paused-$i.bin").toPath(),
                threads = 2,
            )
        }
        ids.forEach { engine.startDownload(it) }

        // 等到第一个真正开始下载，此时另外两个还在 QUEUED
        awaitStatus(ids[0], DownloadStatus.DOWNLOADING)
        val paused = engine.pauseAllDownloads()
        assertEquals(3, paused, "全部暂停应覆盖 1 个进行中 + 2 个排队")

        // 给调度器足够时间：如果 pauseAll 只停进行中的任务，这里会看到某个 QUEUED 被
        // 立刻拉起来变成 DOWNLOADING（旧实现就是这个表现）
        Thread.sleep(800)
        val statuses = engine.taskState.value.associate { it.id to it.status }
        ids.forEach { id ->
            assertEquals(DownloadStatus.PAUSED, statuses[id], "全部暂停之后不应还有任务在下载")
        }
    }

    @Test
    fun removing_a_task_cleans_hls_segments_even_when_keeping_files() {
        // 造一个 HLS 任务但不启动：只要 type 是 M3u8，savePath 会被规整成 .mp4
        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/movie.m3u8",
            savePath = tempDir.resolve("movie.mp4").toPath(),
            threads = 1,
        )
        val task = engine.getTask(id)!!
        val output = task.savePath.toFile()
        output.writeBytes(ByteArray(16) { it.toByte() }) // 假装成品还在
        val segmentDir = File(output.parentFile, "movie.hls").apply { mkdirs() }
        File(segmentDir, "seg0.ts").writeBytes(ByteArray(1024))

        engine.removeTask(id, deleteFile = false)

        waitUntilGone(segmentDir)
        assertFalse(segmentDir.exists(), "分片目录不是用户成品：不移除文件时也必须清掉（P1-2）")
        assertTrue(output.exists(), "deleteFile=false 不能碰成品文件")
        assertEquals(0, engine.taskState.value.count { it.id == id })
    }

    @Test
    fun raising_the_concurrency_limit_starts_queued_tasks_without_another_event() {
        engine.maxConcurrentDownloads = 1
        val ids = (1..3).map { i ->
            engine.addTask(
                url = "http://127.0.0.1:${server.address.port}/slow",
                savePath = tempDir.resolve("limit-$i.bin").toPath(),
                threads = 2,
            )
        }
        ids.forEach { engine.startDownload(it) }
        awaitStatus(ids[0], DownloadStatus.DOWNLOADING)
        // 另外两个应该还排着
        assertEquals(2, engine.taskState.value.count { it.status == DownloadStatus.QUEUED })

        engine.maxConcurrentDownloads = 3
        engine.requestDispatch()

        runBlocking {
            engine.taskState.awaitValue(TestTimeouts.SHORT, {
                "并发上限调大后仍有任务排队: ${engine.taskState.value.map { it.status }}"
            }) { tasks ->
                tasks.count { it.status == DownloadStatus.DOWNLOADING } >= 3
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun downloadAndWait(path: String, target: File): DownloadTask {
        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}$path",
            savePath = target.toPath(),
            threads = 2,
        )
        engine.startDownload(id)
        return runBlocking {
            engine.taskState.awaitElement(TestTimeouts.LONG, { "任务 $id 未在超时前进入终态" }) {
                it.id == id && it.status.isTerminal
            }
        }
    }

    private fun awaitStatus(id: String, status: DownloadStatus) = runBlocking {
        engine.taskState.awaitElement(TestTimeouts.MEDIUM, { "任务 $id 未在超时前变为 $status" }) {
            it.id == id && it.status == status
        }
    }

    /** removeTask 的清理在协程里做（要等下载协程退出），断言前轮询等它完成 */
    private fun waitUntilGone(file: File, timeoutMs: Long = TestTimeouts.MEDIUM) {
        awaitUntil(timeoutMs, describe = { "文件仍在磁盘上: ${file.path}" }) { !file.exists() }
    }

    private fun slowResponse(exchange: HttpExchange, supportsRange: Boolean) {
        try {
            if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                exchange.responseHeaders.add("Accept-Ranges", if (supportsRange) "bytes" else "none")
                exchange.sendResponseHeaders(200, -1)
                return
            }
            exchange.responseHeaders.add("Content-Length", slowData.size.toString())
            exchange.sendResponseHeaders(200, slowData.size.toLong())
            exchange.responseBody.use { out ->
                var sent = 0
                while (sent < slowData.size) {
                    val n = minOf(16 * 1024, slowData.size - sent)
                    out.write(slowData, sent, n)
                    out.flush()
                    sent += n
                    Thread.sleep(20)
                }
            }
        } finally {
            runCatching { exchange.close() }
        }
    }
}

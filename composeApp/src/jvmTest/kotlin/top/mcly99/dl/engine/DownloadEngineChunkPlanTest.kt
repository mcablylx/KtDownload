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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 端到端回归：磁盘上留着上一份分片计划的 `.partN`（内容全是脏字节）时，
 * 引擎必须识别计划漂移、清空重下，最终合并出与服务端完全一致的文件。
 * 没有清单保护的话，这些脏分片会被当成「已完成」直接拼进成品。
 */
class DownloadEngineChunkPlanTest {

    private lateinit var server: HttpServer
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var engine: DownloadEngine
    private lateinit var tempDir: File

    private val data = ByteArray(96 * 1024) { (it * 17 + 3).toByte() }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("ktdown-plan-integration").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        executor = Executors.newFixedThreadPool(8)
        server.executor = executor
        server.createContext("/file") { serve(it) }
        server.start()
        engine = DownloadEngine(
            taskPersistenceEnabled = false,
            multiThreadThresholdBytes = 32L * 1024L,
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
    fun stale_parts_from_another_plan_are_discarded_and_file_is_intact() {
        val target = tempDir.resolve("resume.bin")
        // 旧计划：8 分片，且每片都已「下完」（offset 写成满长度），内容全是脏字节
        for (i in 0 until 8) {
            DownloadEngine.partFile(target, i).writeBytes(ByteArray(16 * 1024) { 0x7F.toByte() })
            DownloadEngine.offsetFile(target, i).writeText((16 * 1024).toString())
        }
        chunkPlanFile(target).writeText(chunkPlanSignature(data.size.toLong(), 8, 8))

        // 新计划：4 线程。若不清空，part1..3 会被当成新计划的对应区间直接合并
        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/file",
            savePath = target.toPath(),
            threads = 4,
            totalSize = data.size.toLong(),
        )
        val task = awaitTerminal(id)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage)
        assertContentEquals(data, target.readBytes())
        // 成功合并后清单与全部分片都要清干净
        assertFalse(chunkPlanFile(target).exists())
        for (i in 0 until 8) assertFalse(DownloadEngine.partFile(target, i).exists())
    }

    private fun awaitTerminal(id: String): DownloadTask = runBlocking {
        engine.taskState.awaitElement(TestTimeouts.LONG, { "任务 $id 未在超时前进入终态" }) {
            it.id == id && it.status.isTerminal
        }
    }

    private fun serve(exchange: HttpExchange) {
        try {
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
                val spec = range.substringAfter('=')
                val s = spec.substringBefore('-').toIntOrNull() ?: 0
                val e = spec.substringAfter('-').toIntOrNull() ?: (data.size - 1)
                s to e.coerceAtMost(data.size - 1)
            }
            val length = end - start + 1
            if (range.isNullOrBlank()) {
                exchange.sendResponseHeaders(200, length.toLong())
            } else {
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${data.size}")
                exchange.sendResponseHeaders(206, length.toLong())
            }
            exchange.responseBody.use { it.write(data, start, length) }
        } finally {
            exchange.close()
        }
    }
}

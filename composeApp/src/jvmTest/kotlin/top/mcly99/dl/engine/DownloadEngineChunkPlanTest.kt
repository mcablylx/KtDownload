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
 * 端到端回归：磁盘上留着**不能信任的旧进度**时，引擎必须识别、丢弃、从零下，
 * 最终交付与服务端完全一致的文件。
 *
 * 两种来源：
 * 1. 老版本（≤1.0.4）的 `.partN` + `.chunks.plan`：模型已经换了，这些字节没有归属；
 * 2. 段表快照与当前文件长度 / 指纹不符：内容不是同一份，游标全部作废。
 *
 * 如果这两种被信任，结果是「文件长度对、内容错」—— 不报错，打开是坏的。
 */
class DownloadEngineChunkPlanTest {

    private lateinit var server: HttpServer
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var engine: DownloadEngine
    private lateinit var tempDir: File

    private val data = ByteArray(256 * 1024) { (it * 17 + 3).toByte() }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("ktdown-segment-plan-e2e").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        executor = Executors.newFixedThreadPool(8)
        server.executor = executor
        server.createContext("/file") { serve(it) }
        server.start()
        engine = DownloadEngine(
            taskPersistenceEnabled = false,
            multiThreadThresholdBytes = 32L * 1024L,
            // 64 KiB 一段：256 KiB 的样本切成 4 段，才测得到「多段并行」这条路径
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

    @Test
    fun artifacts_from_the_old_chunk_model_are_discarded_and_the_file_is_intact() {
        val target = tempDir.resolve("resume.bin")
        // 老版本现场：8 个 `.partN` 全是脏字节，还有一份三段式 `.chunks.plan`
        for (i in 0 until 8) {
            File(tempDir, "resume.bin.part$i").writeBytes(ByteArray(16 * 1024) { 0x7F.toByte() })
            File(tempDir, "resume.bin.part$i.offset").writeText((16 * 1024).toString())
        }
        File(tempDir, "resume.bin.chunks.plan").writeText("${data.size}|8|8")

        val task = awaitTerminal(startTask(target))

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage)
        assertContentEquals(data, target.readBytes())
        // 旧模型的残片必须一并清掉，否则「删任务后还留着 xxx.part3.offset」会复活
        for (i in 0 until 8) {
            assertFalse(File(tempDir, "resume.bin.part$i").exists(), "旧分片 .part$i 未清理")
            assertFalse(File(tempDir, "resume.bin.part$i.offset").exists(), "旧 offset $i 未清理")
        }
        assertFalse(File(tempDir, "resume.bin.chunks.plan").exists(), "旧计划清单未清理")
        assertFalse(partialDownloadFile(target).exists(), "下载完成后不应留着 .part")
        assertFalse(segmentPlanFile(target).exists(), "下载完成后不应留着段表")
    }

    @Test
    fun a_snapshot_with_another_length_is_discarded_before_resuming() {
        val target = tempDir.resolve("stale-length.bin")
        // 长度对不上的段表：若被信任，续传会从错误游标开始，而最终长度又恰好对得上
        segmentPlanFile(target).writeText("ktdl-segments/1\n999999|~\n0-100000-50000\n")
        partialDownloadFile(target).writeBytes(ByteArray(8 * 1024) { 0x5A.toByte() })

        val task = awaitTerminal(startTask(target))

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage)
        assertContentEquals(data, target.readBytes())
    }

    @Test
    fun a_trustworthy_snapshot_resumes_instead_of_redownloading() {
        val target = tempDir.resolve("resume-ok.bin")
        val half = data.size / 2
        // 前半段已经正确落盘，快照声明从 half 接着下 —— 这是「暂停后继续」在磁盘上的样子
        partialDownloadFile(target).writeBytes(data.copyOf(half))
        segmentPlanFile(target).writeText(
            "ktdl-segments/1\n${data.size}|~\n$half-${data.size - 1}-$half\n",
        )

        val task = awaitTerminal(startTask(target))

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage)
        assertContentEquals(data, target.readBytes())
    }

    private fun startTask(target: File): String = engine.addTask(
        url = "http://127.0.0.1:${server.address.port}/file",
        savePath = target.toPath(),
        threads = 4,
        totalSize = data.size.toLong(),
    )

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

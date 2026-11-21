package top.mcly99.dl.engine

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.mcly99.dl.model.DownloadStatus
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P0-2 回归测试：断点续传的内容指纹。
 *
 * 场景是真实世界的顺序 —— 下到一半 → 暂停 → 远端同名文件被覆盖上传（大小常常一模一样）→ 继续。
 * 改动前：单线程拿新文件的后半段拼在旧文件的前半段上，长度恰好等于新总长，
 * 所有本地校验都通过，任务标成「已完成」，用户打开是坏文件，且没有任何提示。
 *
 * 这里的断言都盯着**最终字节**：只有整份都是新内容才算过。任何拼接都会留下旧内容的前缀。
 */
class DownloadResumeFingerprintTest {

    private lateinit var server: HttpServer
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var engine: DownloadEngine
    private lateinit var tempDir: File

    /** 1MB：4 线程 × 256KB，按 8KB 一片慢慢吐，够在中途插进一次暂停 */
    private val size = 1024 * 1024
    private val oldBody = ByteArray(size) { 0xAA.toByte() }
    private val newBody = ByteArray(size) { 0xBB.toByte() }

    @Volatile
    private var body: ByteArray = oldBody

    @Volatile
    private var etag: String = "\"v1\""

    /** 服务端收到的每一条 If-Range（null = 没带），用来证明请求头真的发出去了 */
    private val seenIfRange: MutableList<String?> = Collections.synchronizedList(mutableListOf<String?>())

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("ktdown-resume-test").toFile()
        engine = DownloadEngine(
            taskPersistenceEnabled = false,
            multiThreadThresholdBytes = 64L * 1024L,
        )
        executor = Executors.newFixedThreadPool(8)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/file") { exchange -> serve(exchange) }
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
    fun resuming_after_the_remote_file_changed_redownloads_instead_of_splicing() {
        val target = tempDir.resolve("movie.mp4")
        val id = addAndStart(target, threads = 4)

        awaitPartialProgress(id)
        engine.pauseDownload(id)
        awaitStatus(id, DownloadStatus.PAUSED)
        assertTrue(
            (0 until 4).any { DownloadEngine.partFile(target, it).exists() },
            "暂停后应当留下分片，否则这个用例根本没测到续传",
        )

        // 远端被换成同长度的另一份内容（网盘覆盖上传就是这个样子：大小不变，内容全变）
        body = newBody
        etag = "\"v2\""

        engine.startDownload(id)
        val finished = awaitTerminal(id)

        assertEquals(DownloadStatus.COMPLETED, finished.status, finished.errorMessage)
        assertTrue(
            newBody.contentEquals(target.readBytes()),
            "续传把旧文件的前半段拼到了新文件的后半段上 —— 这正是 P0-2 要消灭的静默坏文件",
        )
    }

    @Test
    fun single_thread_resume_also_notices_a_replaced_remote() {
        // 单线程是另一条代码路径（没有分片、没有计划清单，只有目标文件本身的长度）
        val singleThreadEngine = DownloadEngine(
            taskPersistenceEnabled = false,
            multiThreadThresholdBytes = Long.MAX_VALUE,
        )
        try {
            val target = tempDir.resolve("single.mp4")
            val id = singleThreadEngine.addTask(
                url = "http://127.0.0.1:${server.address.port}/file",
                savePath = target.toPath(),
                threads = 1,
            )
            singleThreadEngine.startDownload(id)

            awaitPartialProgressIn(singleThreadEngine, id)
            singleThreadEngine.pauseDownload(id)
            awaitStatusIn(singleThreadEngine, id, DownloadStatus.PAUSED)
            assertTrue(target.length() > 0, "暂停后目标文件里应当已经有字节")

            body = newBody
            etag = "\"v2\""

            singleThreadEngine.startDownload(id)
            val finished = awaitTerminalIn(singleThreadEngine, id)

            assertEquals(DownloadStatus.COMPLETED, finished.status, finished.errorMessage)
            assertTrue(
                newBody.contentEquals(target.readBytes()),
                "单线程续传同样不能把两份内容的字节拼在一起",
            )
        } finally {
            singleThreadEngine.shutdown()
        }
    }

    @Test
    fun resume_sends_if_range_with_the_recorded_fingerprint() {
        val target = tempDir.resolve("unchanged.mp4")
        val id = addAndStart(target, threads = 4)

        awaitPartialProgress(id)
        engine.pauseDownload(id)
        awaitStatus(id, DownloadStatus.PAUSED)

        // 内容没变：这一次必须真的**续**传（不能因为指纹逻辑每轮都从头下）
        val beforeBytes = (0 until 4).sumOf { DownloadEngine.partFile(target, it).length() }
        assertTrue(beforeBytes > 0)
        seenIfRange.clear()

        engine.startDownload(id)
        val finished = awaitTerminal(id)

        assertEquals(DownloadStatus.COMPLETED, finished.status, finished.errorMessage)
        assertTrue(oldBody.contentEquals(target.readBytes()))
        assertTrue(
            seenIfRange.contains("\"v1\""),
            "续传请求必须带 If-Range（记录下来的指纹），否则服务端没有任何机会发现内容已变：$seenIfRange",
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun addAndStart(target: File, threads: Int): String {
        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/file",
            savePath = target.toPath(),
            threads = threads,
        )
        engine.startDownload(id)
        return id
    }

    /** 等到「下载中且已经有字节落盘、但还没下完」，这时暂停才有残片可续 */
    private fun awaitPartialProgress(id: String, timeoutMs: Long = 20_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val task = engine.getTask(id)
            if (task != null && task.downloadedSize > 0 && task.status == DownloadStatus.DOWNLOADING) return
            Thread.sleep(10)
        }
        error("等不到中途进度，测试无法在下载过程中暂停")
    }

    private fun awaitPartialProgressIn(targetEngine: DownloadEngine, id: String, timeoutMs: Long = 20_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val task = targetEngine.getTask(id)
            if (task != null && task.downloadedSize > 0 && task.status == DownloadStatus.DOWNLOADING) return
            Thread.sleep(10)
        }
        error("等不到单线程的中途进度")
    }

    private fun awaitStatus(id: String, status: DownloadStatus) = runBlocking {
        withTimeout(15_000L) {
            engine.taskState.first { tasks -> tasks.any { it.id == id && it.status == status } }
        }
    }

    private fun awaitStatusIn(targetEngine: DownloadEngine, id: String, status: DownloadStatus) = runBlocking {
        withTimeout(15_000L) {
            targetEngine.taskState.first { tasks -> tasks.any { it.id == id && it.status == status } }
        }
    }

    private fun awaitTerminal(id: String) = runBlocking {
        withTimeout(60_000L) {
            engine.taskState.first { tasks ->
                tasks.any {
                    it.id == id && it.status in setOf(
                        DownloadStatus.COMPLETED,
                        DownloadStatus.FAILED,
                        DownloadStatus.CANCELLED,
                    )
                }
            }.first { it.id == id }
        }
    }

    private fun awaitTerminalIn(targetEngine: DownloadEngine, id: String) = runBlocking {
        withTimeout(60_000L) {
            targetEngine.taskState.first { tasks ->
                tasks.any {
                    it.id == id && it.status in setOf(
                        DownloadStatus.COMPLETED,
                        DownloadStatus.FAILED,
                        DownloadStatus.CANCELLED,
                    )
                }
            }.first { it.id == id }
        }
    }

    /**
     * 一个「老实」的下载端点：支持 Range、给 ETag、遵守 If-Range。
     *
     * 遵守 If-Range 是关键：指纹不匹配时必须回 200 + 整份内容（RFC 7233 §3.2），
     * 而不是装作没事继续回 206 —— 后者正是上游那些「看起来能用」的服务端行为。
     */
    private fun serve(exchange: HttpExchange) {
        try {
            val currentTag = etag
            val currentBody = body
            val ifRange = exchange.requestHeaders.getFirst("If-Range")
            seenIfRange.add(ifRange)

            if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.responseHeaders.add("ETag", currentTag)
                exchange.sendResponseHeaders(200, -1)
                return
            }

            val range = exchange.requestHeaders.getFirst("Range")
            // If-Range 与当前实体不符 → 整份内容（这就是服务端在告诉我们「内容变了」）
            val rangeUsable = range != null && (ifRange == null || ifRange == currentTag)
            if (!rangeUsable) {
                exchange.responseHeaders.add("ETag", currentTag)
                exchange.sendResponseHeaders(200, currentBody.size.toLong())
                trickle(exchange, currentBody, 0, currentBody.size)
                return
            }

            val spec = range!!.removePrefix("bytes=")
            val start = spec.substringBefore('-').trim().toLongOrNull() ?: 0L
            if (start >= currentBody.size) {
                exchange.sendResponseHeaders(416, -1)
                return
            }
            val requestedEnd = spec.substringAfter('-').trim().toLongOrNull()
            val end = requestedEnd?.takeIf { it in start until currentBody.size } ?: (currentBody.size - 1L)
            val length = (end - start + 1).toInt()
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${currentBody.size}")
            exchange.responseHeaders.add("ETag", currentTag)
            exchange.sendResponseHeaders(206, length.toLong())
            trickle(exchange, currentBody, start.toInt(), length)
        } finally {
            runCatching { exchange.close() }
        }
    }

    /** 8KB 一停 12ms：整轮约 1.5s，足够在中间插一次暂停，又不至于让测试跑太久 */
    private fun trickle(exchange: HttpExchange, data: ByteArray, offset: Int, length: Int) {
        exchange.responseBody.use { out ->
            var sent = 0
            while (sent < length) {
                val n = minOf(8 * 1024, length - sent)
                out.write(data, offset + sent, n)
                out.flush()
                sent += n
                Thread.sleep(12)
            }
        }
    }
}

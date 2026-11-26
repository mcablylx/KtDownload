package top.mcly99.dl.engine

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import top.mcly99.dl.model.DownloadStatus
import top.mcly99.dl.model.DownloadTask
import top.mcly99.dl.model.TaskType
import top.mcly99.dl.testutil.TestTimeouts
import top.mcly99.dl.testutil.awaitElement
import top.mcly99.dl.testutil.awaitUntilSuspend
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 用本地 [HttpServer] 覆盖三种关键路径：
 * 1. 小文件（低于阈值）→ 单线程；
 * 2. 大文件 + 206/Range → 多线程分块并合并；
 * 3. 大文件但服务器忽略 Range（始终 200）→ 回退单线程。
 */
class DownloadEngineIntegrationTest {

    private lateinit var server: HttpServer
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var engine: DownloadEngine
    private lateinit var tempDir: java.io.File
    private val rangeAttempts = AtomicInteger(0)

    /** 防盗链站点收到的最后一个 Referer */
    private val lastReferer = java.util.concurrent.atomic.AtomicReference<String>()

    /** 需要会话的站点收到的最后一个 Cookie（v7-P1-1 / P2-2） */
    private val lastCookie = java.util.concurrent.atomic.AtomicReference<String>()

    /** 站点收到的最后一个 User-Agent（v7-P1-2） */
    private val lastUserAgent = java.util.concurrent.atomic.AtomicReference<String>()

    /** 网盘 CDN 校验的 Origin / 平台 UA / 会话 Cookie */
    private val lastOrigin = java.util.concurrent.atomic.AtomicReference<String>()

    /** 重定向后的目标端点被请求了几次 */
    private val redirectTargetHits = AtomicInteger(0)

    private val smallData = ByteArray(32 * 1024) { (it * 7 + 3).toByte() }
    private val largeData = ByteArray(256 * 1024) { (it * 31 + 11).toByte() }
    private val noRangeData = ByteArray(256 * 1024) { (it * 17 + 5).toByte() }
    private val slowDataSize = 1024 * 1024

    /** 支持 Range 且写得很慢：用来在分块中途取消，验证临时文件清理（v6-P1-2） */
    private val slowRangeSize = 1024 * 1024

    /** fMP4 初始化段：`ftyp` + `moov`，合成层据此走直拼快路径 */
    private val hlsInit = box("ftyp", "isom".toByteArray() + ByteArray(4)) + box("moov")
    private val hlsSegments = List(2) { i ->
        box("moof", ByteArray(32) { b -> (i * 19 + b).toByte() })
    }

    @BeforeTest
    fun setUp() {
        rangeAttempts.set(0)
        redirectTargetHits.set(0)
        lastCookie.set(null)
        lastUserAgent.set(null)
        tempDir = Files.createTempDirectory("ktdown-engine-test").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        executor = Executors.newFixedThreadPool(8)
        server.executor = executor
        server.createContext("/small") { handle(it, smallData, supportsRange = true) }
        server.createContext("/large") { handle(it, largeData, supportsRange = true) }
        server.createContext("/norange") { handle(it, noRangeData, supportsRange = false) }
        // 夸克式 CDN：HEAD 直接 405，只有 GET 可用
        server.createContext("/nohead") { exchange ->
            if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            handle(exchange, smallData, supportsRange = true)
        }
        // 网盘 CDN 的另一种坑：HEAD 返回 200 + 一张 6.83 KB 的错误页长度，
        // 但 GET（含 Range）给的仍是完整文件。入队时已知真实大小，不能被 HEAD 覆盖。
        server.createContext("/bogus-head-size") { exchange ->
            if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.responseHeaders.add("Content-Length", "6832")
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
                return@createContext
            }
            handle(exchange, largeData, supportsRange = true)
        }
        server.createContext("/slow") { exchange ->
            try {
                if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                    exchange.responseHeaders.add("Content-Length", slowDataSize.toString())
                    exchange.sendResponseHeaders(200, -1)
                } else {
                    exchange.sendResponseHeaders(200, slowDataSize.toLong())
                    exchange.responseBody.use { out ->
                        val chunk = ByteArray(16 * 1024) { 1 }
                        var sent = 0
                        while (sent < slowDataSize) {
                            out.write(chunk)
                            out.flush()
                            sent += chunk.size
                            Thread.sleep(20)
                        }
                    }
                }
            } finally {
                exchange.close()
            }
        }
        server.createContext("/slowrange") { exchange ->
            // 支持 Range + 慢速：两个分块各 512KB，每 16KB 停 20ms，足够在中途取消
            try {
                val rangeHeader = exchange.requestHeaders.getFirst("Range")
                if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                    exchange.responseHeaders.add("Accept-Ranges", "bytes")
                    exchange.responseHeaders.add("Content-Length", slowRangeSize.toString())
                    exchange.sendResponseHeaders(200, -1)
                    return@createContext
                }
                val (start, end) = if (rangeHeader.isNullOrBlank()) {
                    0 to slowRangeSize - 1
                } else {
                    parseRange(rangeHeader, slowRangeSize) ?: run {
                        exchange.sendResponseHeaders(416, -1)
                        return@createContext
                    }
                }
                val length = end - start + 1
                if (rangeHeader.isNullOrBlank()) {
                    exchange.responseHeaders.add("Accept-Ranges", "bytes")
                    exchange.sendResponseHeaders(200, length.toLong())
                } else {
                    exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$slowRangeSize")
                    exchange.responseHeaders.add("Accept-Ranges", "bytes")
                    exchange.sendResponseHeaders(206, length.toLong())
                }
                exchange.responseBody.use { out ->
                    val chunk = ByteArray(16 * 1024) { 2 }
                    var sent = 0
                    while (sent < length) {
                        val n = minOf(chunk.size, length - sent)
                        out.write(chunk, 0, n)
                        out.flush()
                        sent += n
                        Thread.sleep(20)
                    }
                }
            } finally {
                exchange.close()
            }
        }
        // 要求 Cookie + 支持 Range + 慢速小文件（走单线程）：验证「带会话的续传」
        server.createContext("/cookie-small") { exchange ->
            try {
                val cookie = exchange.requestHeaders.getFirst("Cookie")
                lastCookie.set(cookie)
                if (cookie != GUARDED_COOKIE) {
                    exchange.sendResponseHeaders(403, -1)
                    return@createContext
                }
                val data = ByteArray(slowRangeSize) { (it * 13 + 7).toByte() }
                val rangeHeader = exchange.requestHeaders.getFirst("Range")
                if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                    exchange.responseHeaders.add("Accept-Ranges", "bytes")
                    exchange.responseHeaders.add("Content-Length", data.size.toString())
                    exchange.sendResponseHeaders(200, -1)
                    return@createContext
                }
                val (start, end) = if (rangeHeader.isNullOrBlank()) {
                    0 to data.size - 1
                } else {
                    parseRange(rangeHeader, data.size) ?: run {
                        exchange.sendResponseHeaders(416, -1)
                        return@createContext
                    }
                }
                val length = end - start + 1
                if (rangeHeader.isNullOrBlank()) {
                    exchange.sendResponseHeaders(200, length.toLong())
                } else {
                    exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${data.size}")
                    exchange.sendResponseHeaders(206, length.toLong())
                }
                exchange.responseBody.use { out ->
                    // 每 16KB 停 20ms：1MB 约 1.3s，留出「中途暂停」的窗口
                    var sent = 0
                    while (sent < length) {
                        val n = minOf(16 * 1024, length - sent)
                        out.write(data, start + sent, n)
                        out.flush()
                        sent += n
                        Thread.sleep(20)
                    }
                }
            } finally {
                exchange.close()
            }
        }
        server.createContext("/ua-probe") { exchange ->
            lastUserAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
            handle(exchange, smallData, supportsRange = true)
        }
        // 网盘式 CDN：同时校验平台专用 UA / Origin / Cookie，缺任一即 403
        server.createContext("/platform-headers") { exchange ->
            lastUserAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
            lastOrigin.set(exchange.requestHeaders.getFirst("Origin"))
            lastCookie.set(exchange.requestHeaders.getFirst("Cookie"))
            val ok = exchange.requestHeaders.getFirst("User-Agent") == PLATFORM_UA &&
                exchange.requestHeaders.getFirst("Origin") == PLATFORM_ORIGIN &&
                exchange.requestHeaders.getFirst("Cookie") == PLATFORM_COOKIE
            if (!ok) {
                exchange.sendResponseHeaders(403, -1)
                exchange.close()
            } else {
                handle(exchange, smallData, supportsRange = true)
            }
        }
        server.createContext("/cookie-required") { exchange ->
            val cookie = exchange.requestHeaders.getFirst("Cookie")
            lastCookie.set(cookie)
            redirectTargetHits.incrementAndGet()
            if (cookie != GUARDED_COOKIE) {
                exchange.sendResponseHeaders(403, -1)
                exchange.close()
            } else {
                handle(exchange, smallData, supportsRange = true)
            }
        }
        server.createContext("/redirect-same-host") { exchange ->
            // 相对 Location：顺带验证相对地址能正确解析（否则会拼出 /a/b 这种错路径）
            exchange.responseHeaders.add("Location", "/cookie-required")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/redirect-cross-host") { exchange ->
            // localhost → 127.0.0.1：host 变了，会话不能跟着过去
            exchange.responseHeaders.add(
                "Location",
                "http://127.0.0.1:${server.address.port}/cookie-required",
            )
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/referer-required") { exchange ->
            val referer = exchange.requestHeaders.getFirst("Referer")
            lastReferer.set(referer)
            if (referer != GUARDED_REFERER) {
                exchange.sendResponseHeaders(403, -1)
                exchange.close()
            } else {
                handle(exchange, smallData, supportsRange = true)
            }
        }
        server.createContext("/movie.m3u8") { exchange ->
            try {
                respondBytes(
                    exchange,
                    """
                        #EXTM3U
                        #EXT-X-VERSION:7
                        #EXT-X-TARGETDURATION:4
                        #EXT-X-MAP:URI="movie/init.mp4"
                        #EXTINF:4.0,
                        movie/seg0.m4s
                        #EXTINF:4.0,
                        movie/seg1.m4s
                        #EXT-X-ENDLIST
                    """.trimIndent().toByteArray(),
                )
            } finally {
                exchange.close()
            }
        }
        server.createContext("/movie/init.mp4") { exchange ->
            try {
                respondBytes(exchange, hlsInit)
            } finally {
                exchange.close()
            }
        }
        server.createContext("/movie/seg0.m4s") { exchange ->
            try {
                respondBytes(exchange, hlsSegments[0])
            } finally {
                exchange.close()
            }
        }
        server.createContext("/movie/seg1.m4s") { exchange ->
            try {
                respondBytes(exchange, hlsSegments[1])
            } finally {
                exchange.close()
            }
        }
        server.start()

        // 阈值降到 64KB，测试数据更小；生产仍使用 AppConstants 的 10MB
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
    fun small_file_uses_single_request() {
        val target = tempDir.resolve("small.bin")
        val task = downloadAndWait("/small", target, threads = 4)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage)
        assertContentEquals(smallData, target.readBytes())
        assertEquals(0, rangeAttempts.get(), "小文件不应发起 Range 请求")
    }

    @Test
    fun large_file_uses_range_chunks_and_merges() {
        val target = tempDir.resolve("large.bin")
        val task = downloadAndWait("/large", target, threads = 4)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage)
        assertContentEquals(largeData, target.readBytes())
        assertTrue(
            rangeAttempts.get() >= 5,
            "应包含 1 次探测 + 4 个分块请求，实际 ${rangeAttempts.get()}",
        )
    }

    @Test
    fun server_without_range_support_falls_back_to_single_thread() {
        val target = tempDir.resolve("norange.bin")
        val task = downloadAndWait("/norange", target, threads = 4)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage)
        assertContentEquals(noRangeData, target.readBytes())
        assertEquals(1, rangeAttempts.get(), "只应有一次 Range 探测，随后回退单线程")
    }

    @Test
    fun already_complete_file_is_accepted_when_server_returns_416() {
        val target = tempDir.resolve("already.bin")
        // 先入队但不启动，随后把目标文件写满，模拟“上次已下完、状态未落库”的恢复场景
        engine.maxConcurrentDownloads = 0
        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/small",
            savePath = target.toPath(),
            threads = 4,
        )
        target.writeBytes(smallData)

        engine.maxConcurrentDownloads = 1
        engine.startDownload(id)
        val task = awaitTerminal(id)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage)
        assertContentEquals(smallData, target.readBytes())
    }

    /** HEAD 被 CDN 拒绝时，仍要靠 `Range: bytes=0-0` 的 `Content-Range` 拿到总大小 */
    @Test
    fun head_rejected_still_detects_size_via_range_probe() {
        val target = tempDir.resolve("nohead.bin")
        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/nohead",
            savePath = target.toPath(),
            threads = 4,
        )
        val task = awaitTerminal(id)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage)
        assertContentEquals(smallData, target.readBytes())
        assertEquals(
            smallData.size.toLong(),
            task.totalSize,
            "HEAD 被拒后应从 Content-Range 探测到总大小，而不是留下 0 B",
        )
    }

    /**
     * 带 Cookie 的续传：旧的 `newConnection` 在跟随重定向阶段就把连接建立了，
     * 调用方随后 `setRequestProperty("Range")` 会抛 `Already connected`，续传任务必挂。
     *
     * 用「下到一半 → 暂停 → 继续」触发续传：重新 `addTask` 同一路径会被
     * [handleDuplicateFile] 改名成 `xxx(1).bin`，拿不到真实的续传路径。
     */
    @Test
    fun resume_after_pause_with_cookie_completes() {
        val target = tempDir.resolve("resume-cookie.bin")
        // 单独一个引擎把多线程阈值顶到 1GB：这条用例要的是「单线程直接写目标文件」的续传路径
        val singleThreadEngine = DownloadEngine(
            taskPersistenceEnabled = false,
            multiThreadThresholdBytes = 1L shl 30,
        )
        try {
            val id = singleThreadEngine.addTask(
                url = "http://127.0.0.1:${server.address.port}/cookie-small",
                savePath = target.toPath(),
                threads = 1,
                cookie = GUARDED_COOKIE,
            )

            runBlocking {
                singleThreadEngine.taskState.awaitElement(TestTimeouts.SLOW, { "任务 $id 未开始写入数据" }) {
                    it.id == id && it.downloadedSize > 0
                }
            }
            singleThreadEngine.pauseDownload(id)
            val paused = runBlocking {
                singleThreadEngine.taskState.awaitElement(TestTimeouts.MEDIUM, { "任务 $id 未进入暂停态" }) {
                    it.id == id && it.status == DownloadStatus.PAUSED
                }
            }
            assertTrue(
                paused.downloadedSize > 0,
                "暂停前应已下载一部分，实际 ${paused.downloadedSize}",
            )

            singleThreadEngine.startDownload(id)
            val task = runBlocking {
                singleThreadEngine.taskState.awaitElement(TestTimeouts.LONG, { "续传任务 $id 未在超时前结束" }) {
                    it.id == id && it.status.isTerminal
                }
            }

            assertEquals(
                DownloadStatus.COMPLETED,
                task.status,
                "续传失败：err=${task.errorMessage} total=${task.totalSize} done=${task.downloadedSize}",
            )
            assertEquals(slowRangeSize.toLong(), target.length(), "续传后文件大小必须与源一致")
        } finally {
            singleThreadEngine.shutdown()
        }
    }

    /**
     * 暂停要把每块「已落盘多少字节」写回 `.offset`。
     *
     * 暂停是「先断连接再取消」，停在 `read()` 上的协程抛的是 `IOException` 而不是
     * `CancellationException`，所以收尾写盘必须两个分支都做 —— 只写取消分支的话，
     * 每次暂停都白丢最近一个 `OFFSET_FLUSH_MS` 窗口的进度。
     */
    @Test
    fun pausing_keeps_the_progress_of_each_chunk_on_disk() {
        val target = tempDir.resolve("pause-offset.bin")
        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/slowrange",
            savePath = target.toPath(),
            threads = 2,
        )
        runBlocking {
            engine.taskState.awaitElement(TestTimeouts.SLOW, { "任务 $id 未开始写入数据" }) {
                it.id == id && it.downloadedSize > 0
            }
        }

        engine.pauseDownload(id)
        runBlocking {
            engine.taskState.awaitElement(TestTimeouts.MEDIUM, { "任务 $id 未进入暂停态" }) {
                it.id == id && it.status == DownloadStatus.PAUSED
            }
        }

        val offsets = tempDir
            .listFiles { file -> file.name.startsWith("pause-offset.bin.part") && file.name.endsWith(".offset") }
            .orEmpty()
        assertTrue(offsets.isNotEmpty(), "暂停后应把每块的落盘进度写回 .offset")
        val recorded = offsets.mapNotNull { it.readText().trim().toLongOrNull() }
        assertEquals(offsets.size, recorded.size, "每个 .offset 都应是数字：${offsets.map { it.name }}")
        assertTrue(recorded.any { it > 0 }, "至少要有一块记下了正数进度，否则这一轮白下")
    }

    /** 调用方已知大小时，任务入队就应带上，UI 不必等探测 */
    @Test
    fun caller_provided_size_is_visible_before_download_starts() {
        engine.maxConcurrentDownloads = 0
        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/small",
            savePath = tempDir.resolve("known.bin").toPath(),
            threads = 1,
            totalSize = 12_345L,
        )

        val task = engine.getTask(id)!!
        engine.removeTask(id, deleteFile = false)

        assertEquals(12_345L, task.totalSize)
    }

    /** 截图里的场景：HEAD 把 256 KB 的文件报成 6.83 KB，任务不该变成 100% / 0 B/s */
    @Test
    fun bogus_head_size_does_not_override_the_size_known_at_enqueue_time() {
        val target = tempDir.resolve("bogus-head.bin")
        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/bogus-head-size",
            savePath = target.toPath(),
            threads = 4,
            totalSize = largeData.size.toLong(),
        )

        val task = awaitTerminal(id)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage.orEmpty())
        assertEquals(largeData.size.toLong(), task.totalSize)
        assertEquals(largeData.size.toLong(), task.downloadedSize)
        assertContentEquals(largeData, target.readBytes())
    }

    /** 老版本已经存盘的错误任务：记录 6832、磁盘上却有几 MB，重开后也要自修复 */
    @Test
    fun persisted_task_with_a_too_small_size_is_repaired_before_resuming() {
        engine.maxConcurrentDownloads = 0
        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/bogus-head-size",
            savePath = tempDir.resolve("bogus-head-persisted.bin").toPath(),
            threads = 4,
            totalSize = 6_832L,
        )
        // 模拟老版本留下来的半成品：任务记录 6832，文件已经下了完整内容
        val target = engine.getTask(id)!!.savePath.toFile()
        target.writeBytes(largeData)
        engine.maxConcurrentDownloads = 1
        engine.requestDispatch()

        val task = awaitTerminal(id)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage.orEmpty())
        assertEquals(largeData.size.toLong(), task.totalSize)
        assertContentEquals(largeData, target.readBytes())
    }

    @Test
    fun remove_task_can_keep_the_downloaded_file() {
        val target = tempDir.resolve("keep.bin")
        engine.maxConcurrentDownloads = 0
        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/small",
            savePath = target.toPath(),
            threads = 1,
        )
        target.writeText("keep me")

        engine.removeTask(id, deleteFile = false)

        assertNull(engine.getTask(id))
        assertTrue(target.exists(), "deleteFile=false 时不应删除用户文件")
        assertEquals("keep me", target.readText())
    }

    @Test
    fun remove_all_tasks_clears_finished_and_running_but_keeps_files() {
        // 一个已完成的成品 + 一个正在下载的任务：顶栏「全部删除」要能把两者一起清掉
        val finishedTarget = tempDir.resolve("finished.bin")
        val finishedId = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/small",
            savePath = finishedTarget.toPath(),
            threads = 1,
        )
        val finished = awaitTerminal(finishedId)
        assertEquals(DownloadStatus.COMPLETED, finished.status, finished.errorMessage)

        val runningTarget = tempDir.resolve("running.bin")
        val runningId = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/slowrange",
            savePath = runningTarget.toPath(),
            threads = 2,
        )
        runBlocking {
            engine.taskState.awaitElement(TestTimeouts.MEDIUM, { "任务 $runningId 未开始下载" }) {
                it.id == runningId && it.status == DownloadStatus.DOWNLOADING
            }
        }

        val removed = engine.removeAllTasks(deleteFile = false)

        assertEquals(2, removed, "两个任务（已完成 + 进行中）都应被移除")
        assertTrue(engine.taskState.value.isEmpty(), "任务列表应为空：${engine.taskState.value.map { it.fileName }}")
        assertNull(engine.getTask(finishedId))
        assertNull(engine.getTask(runningId))
        // 已完成的成品文件必须留着：这是「全部删除」不做 deleteFile 的核心承诺
        assertTrue(finishedTarget.exists(), "已完成任务的成品文件不应被删除")

        // 进行中的任务被中断后，`.part*` / `.offset` 必须清干净（同 removeTask 的竞态保护）
        runBlocking {
            awaitUntilSuspend(TestTimeouts.MEDIUM, intervalMs = 50, describe = {
                "running.bin 的临时文件未清理: ${tempDir.listFiles()?.map { it.name }}"
            }) {
                tempDir.listFiles()?.none { it.name.startsWith("running.bin") } == true
            }
        }
        val leftovers = tempDir.listFiles()?.filter { it.name.startsWith("running.bin") }.orEmpty()
        assertTrue(leftovers.isEmpty(), "未完成任务的临时文件应被清理：${leftovers.map { it.name }}")
    }

    @Test
    fun cancel_removes_partial_target_file() {
        val target = tempDir.resolve("cancel.bin")
        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/slow",
            savePath = target.toPath(),
            threads = 1,
        )
        runBlocking {
            engine.taskState.awaitElement(TestTimeouts.MEDIUM, { "任务 $id 未开始下载" }) {
                it.id == id && it.status == DownloadStatus.DOWNLOADING
            }
            awaitUntilSuspend(TestTimeouts.MEDIUM, describe = { "取消前应已写入部分数据，实际 ${target.length()}B" }) {
                target.exists() && target.length() > 0L
            }
        }
        assertTrue(target.exists() && target.length() > 0L, "取消前应已写入部分数据")

        engine.cancelDownload(id)
        runBlocking {
            awaitUntilSuspend(TestTimeouts.MEDIUM, describe = { "取消后文件未被删除: $target" }) {
                !target.exists()
            }
        }

        assertEquals(DownloadStatus.CANCELLED, engine.getTask(id)?.status)
    }

    @Test
    fun removing_a_running_task_leaves_no_part_or_offset_files() {
        val target = tempDir.resolve("removing.bin")
        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/slowrange",
            savePath = target.toPath(),
            threads = 2,
        )
        // 先等分块真的把 `.offset` 写出来（OFFSET_FLUSH_MS = 1s），否则测不到「删完又被回写」这个竞态
        runBlocking {
            awaitUntilSuspend(TestTimeouts.MEDIUM, intervalMs = 50, describe = { "分块未写出 .offset 文件" }) {
                tempDir.listFiles()?.any { it.name.endsWith(".offset") } == true
            }
        }

        // 显式删文件：引擎默认 `deleteFile=false`（P0-1），这个用例要覆盖「删文件时的收尾」
        engine.removeTask(id, deleteFile = true)

        // 清理现在是「等协程退出再删」的异步流程，给足窗口
        runBlocking {
            awaitUntilSuspend(TestTimeouts.MEDIUM, intervalMs = 50, describe = {
                "removing.bin 的临时文件未清理: ${tempDir.listFiles()?.map { it.name }}"
            }) {
                tempDir.listFiles()?.none { it.name.startsWith("removing.bin") } == true
            }
        }
        val leftovers = tempDir.listFiles()?.filter { it.name.startsWith("removing.bin") }.orEmpty()
        // 关键：不能只剩一个 `.part0.offset`（v6-P1-2 的现场）
        assertTrue(leftovers.isEmpty(), "删除任务后不应留下临时文件：${leftovers.map { it.name }}")
    }

    @Test
    fun hls_task_downloads_segments_and_muxes_to_mp4() {
        val target = tempDir.resolve("movie.mp4")
        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/movie.m3u8",
            savePath = target.toPath(),
            threads = 2,
        )

        val task = awaitTerminal(id)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage)
        assertEquals(TaskType.M3u8, task.type)
        assertEquals("movie.mp4", task.fileName)
        assertEquals(2, task.segmentDone)
        assertEquals(2, task.segmentTotal)
        assertNull(task.errorMessage, "fMP4 快路径不应产生警告")

        val expected = hlsInit + hlsSegments[0] + hlsSegments[1]
        assertContentEquals(expected, target.readBytes())
        assertEquals(expected.size.toLong(), task.totalSize)
        // 分片目录在任务确认 COMPLETE 后才清理，等待清理完成
        runBlocking {
            awaitUntilSuspend(TestTimeouts.MEDIUM, describe = { "合成成功后分片目录未被清理" }) {
                !File(tempDir, "movie.hls").exists()
            }
        }
        assertFalse(File(tempDir, "movie.hls").exists(), "合成成功后应清理分片目录")
    }

    @Test
    fun second_hls_task_with_same_name_gets_numbered_path() {
        engine.maxConcurrentDownloads = 0
        val url = "http://127.0.0.1:${server.address.port}/movie.m3u8"

        val first = engine.addTask(url, tempDir.toPath(), threads = 2)
        val second = engine.addTask(url, tempDir.toPath(), threads = 2)

        assertEquals(tempDir.resolve("movie.mp4").toPath(), engine.getTask(first)!!.savePath)
        assertEquals(
            tempDir.resolve("movie(1).mp4").toPath(),
            engine.getTask(second)!!.savePath,
            "已排队任务占用的同名路径应递增序号",
        )
    }

    @Test
    fun existing_hls_work_directory_also_counts_as_duplicate() {
        engine.maxConcurrentDownloads = 0
        // 上次中断留下的分片目录：直接复用会被 .done 标记混拼
        tempDir.resolve("movie.hls").mkdirs()

        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/movie.m3u8",
            savePath = tempDir.toPath(),
            threads = 2,
        )

        assertEquals(tempDir.resolve("movie(1).mp4").toPath(), engine.getTask(id)!!.savePath)
    }

    @Test
    fun referer_from_page_is_sent_with_every_request() {
        lastReferer.set(null)
        val target = tempDir.resolve("guarded.bin")

        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/referer-required",
            savePath = target.toPath(),
            threads = 4,
            referer = GUARDED_REFERER,
        )
        val task = awaitTerminal(id)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage ?: "")
        assertEquals(GUARDED_REFERER, lastReferer.get())
        assertContentEquals(smallData, target.readBytes())
    }

    @Test
    fun guarded_file_without_referer_is_rejected_with_403() {
        lastReferer.set(null)
        val target = tempDir.resolve("no-referer.bin")

        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/referer-required",
            savePath = target.toPath(),
            threads = 4,
        )
        val task = awaitTerminal(id)

        // 没有 Referer 就是 403 —— 这正是抓网页必须把页面地址传下去的原因
        assertEquals(DownloadStatus.FAILED, task.status)
        assertNull(lastReferer.get())
    }

    @Test
    fun cookie_from_deep_capture_is_sent_with_every_request() {
        // v7-P1-1：签名地址把会话绑在 Cookie 里，缺它「浏览器能播、这里下不动」
        val target = tempDir.resolve("with-cookie.bin")

        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/cookie-required",
            savePath = target.toPath(),
            threads = 4,
            referer = GUARDED_REFERER,
            cookie = GUARDED_COOKIE,
        )
        val task = awaitTerminal(id)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage ?: "")
        assertEquals(GUARDED_COOKIE, lastCookie.get())
        assertContentEquals(smallData, target.readBytes())
    }

    @Test
    fun cookie_is_dropped_when_redirect_changes_host() {
        // v7-P2-2：JDK 的自动跟随会连 Cookie 一起复制到新 host。
        // 这里用 localhost → 127.0.0.1 模拟「CDN 跳 CDN」，会话不能跟着过去
        val target = tempDir.resolve("cross-host.bin")

        val id = engine.addTask(
            url = "http://localhost:${server.address.port}/redirect-cross-host",
            savePath = target.toPath(),
            threads = 1,
            referer = GUARDED_REFERER,
            cookie = GUARDED_COOKIE,
        )
        val task = awaitTerminal(id)

        // 目标端点确实被请求到了（重定向被跟随），但那一跳没带 Cookie
        assertTrue(redirectTargetHits.get() > 0, "重定向没有被跟随")
        assertNull(lastCookie.get(), "跨 host 跳转不应携带会话 Cookie")
        // 少了 Cookie 就被 403 挡住，这正是「宁可少带」的代价
        assertEquals(DownloadStatus.FAILED, task.status)
    }

    @Test
    fun cookie_survives_redirect_on_same_host() {
        // 同 host 的相对跳转是 CDN 常态，这里不能把会话丢掉
        val target = tempDir.resolve("same-host.bin")

        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/redirect-same-host",
            savePath = target.toPath(),
            threads = 1,
            referer = GUARDED_REFERER,
            cookie = GUARDED_COOKIE,
        )
        val task = awaitTerminal(id)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage ?: "")
        assertEquals(GUARDED_COOKIE, lastCookie.get())
    }

    @Test
    fun cloud_drive_headers_reach_the_actual_request() {
        // P0：平台专用 UA / Origin / 合并后的 Cookie 必须真的出现在下载请求上。
        // 服务端三条都校验，缺任一返回 403 —— 这正是 115 / UC 大文件下载失败的现场。
        val target = tempDir.resolve("platform-headers.bin")

        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/platform-headers",
            savePath = target.toPath(),
            threads = 1,
            referer = GUARDED_REFERER,
            cookie = PLATFORM_COOKIE,
            userAgent = PLATFORM_UA,
            origin = PLATFORM_ORIGIN,
        )
        val task = awaitTerminal(id)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage ?: "")
        assertEquals(PLATFORM_UA, lastUserAgent.get(), "平台专用 UA 没到请求上")
        assertEquals(PLATFORM_ORIGIN, lastOrigin.get(), "Origin 没到请求上")
        assertEquals(PLATFORM_COOKIE, lastCookie.get())
    }

    @Test
    fun browser_user_agent_is_used_only_for_captured_tasks() {
        // v7-P1-2：源站常按 UA 判非浏览器。抓来的地址要伪装，手动添加的保留自己的标识
        downloadAndWait("/ua-probe", tempDir.resolve("plain-ua.bin"), threads = 1)
        val plain = lastUserAgent.get()
        assertTrue(
            plain != null && plain.startsWith("KtDownload/"),
            "手动任务应自报家门，实际：$plain",
        )

        engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/ua-probe",
            savePath = tempDir.resolve("captured-ua.bin").toPath(),
            threads = 1,
            referer = GUARDED_REFERER,
        ).let { awaitTerminal(it) }

        val captured = lastUserAgent.get()
        assertTrue(
            captured != null && captured.contains("Mozilla"),
            "抓网页任务应使用浏览器 UA，实际：$captured",
        )
    }

    @Test
    fun bt_tasks_are_still_rejected() {        assertFailsWith<IllegalArgumentException> {
            engine.addTask("magnet:?xt=urn:btih:abcdef")
        }
    }

    @Test
    fun hls_task_output_is_renamed_to_mp4() {
        engine.maxConcurrentDownloads = 0
        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}/movie.m3u8",
            savePath = tempDir.toPath(),
            threads = 2,
        )

        val task = engine.getTask(id)!!
        engine.removeTask(id, deleteFile = false)

        assertEquals(TaskType.M3u8, task.type)
        assertEquals("movie.mp4", task.fileName)
        assertEquals(tempDir.resolve("movie.mp4").toPath(), task.savePath)
    }

    /** 构造一个 ISO BMFF box：4 字节大小 + 4 字节类型 + 负载。 */
    private companion object {
        const val GUARDED_REFERER = "https://page.example.com/watch/1"
        const val GUARDED_COOKIE = "SESSDATA=abc%2C123; bili_jct=deadbeef"

        /** 网盘场景：115 客户端 UA + UC 式 Origin / 合并 Cookie */
        const val PLATFORM_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "115Browser/36.0.1 Chrome/109.0.5414.74 Safari/537.36"
        const val PLATFORM_ORIGIN = "https://drive.uc.cn"
        const val PLATFORM_COOKIE = "__puus=guest; UID=1_A1_1; CID=cdn900"
    }

    private fun box(type: String, payload: ByteArray = ByteArray(0)): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf(
            (size ushr 24).toByte(),
            (size ushr 16).toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        ) + type.toByteArray(Charsets.US_ASCII) + payload
    }

    private fun downloadAndWait(path: String, target: java.io.File, threads: Int): DownloadTask {
        val id = engine.addTask(
            url = "http://127.0.0.1:${server.address.port}$path",
            savePath = target.toPath(),
            threads = threads,
        )
        return awaitTerminal(id)
    }

    private fun awaitTerminal(id: String): DownloadTask = runBlocking {
        engine.taskState.awaitElement(TestTimeouts.LONG, { "任务 $id 未在超时前进入终态" }) {
            it.id == id && it.status.isTerminal
        }
    }

    private fun handle(exchange: HttpExchange, data: ByteArray, supportsRange: Boolean) {
        try {
            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            if (!rangeHeader.isNullOrBlank()) rangeAttempts.incrementAndGet()

            if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                exchange.responseHeaders.add("Accept-Ranges", if (supportsRange) "bytes" else "none")
                // JDK HttpServer 不允许 HEAD 调用 sendResponseHeaders(200, size)；
                // 这里手动写入 Content-Length，再用 -1 表示不发送响应体。
                exchange.responseHeaders.add("Content-Length", data.size.toString())
                exchange.sendResponseHeaders(200, -1)
                return
            }

            if (supportsRange && !rangeHeader.isNullOrBlank()) {
                val (start, end) = parseRange(rangeHeader, data.size)
                    ?: run {
                        exchange.sendResponseHeaders(416, -1)
                        return
                    }
                val length = end - start + 1
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${data.size}")
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.sendResponseHeaders(206, length.toLong())
                exchange.responseBody.use { it.write(data, start, length) }
            } else {
                exchange.responseHeaders.add("Accept-Ranges", if (supportsRange) "bytes" else "none")
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.use { it.write(data) }
            }
        } finally {
            exchange.close()
        }
    }

    private fun respondBytes(exchange: HttpExchange, data: ByteArray) {
        exchange.sendResponseHeaders(200, data.size.toLong())
        exchange.responseBody.use { it.write(data) }
    }

    /** 解析 `bytes=start-end` / `bytes=start-` / `bytes=-suffix`。 */
    private fun parseRange(header: String, size: Int): Pair<Int, Int>? {
        if (!header.startsWith("bytes=", ignoreCase = true)) return null
        val spec = header.substringAfter('=').trim()
        val startText = spec.substringBefore('-', "")
        val endText = spec.substringAfter('-', "")
        if (startText.isEmpty() && endText.isEmpty()) return null

        val (start, end) = if (startText.isEmpty()) {
            val suffix = endText.toIntOrNull() ?: return null
            if (suffix <= 0) return null
            (size - suffix).coerceAtLeast(0) to (size - 1)
        } else {
            val start = startText.toIntOrNull() ?: return null
            val end = endText.toIntOrNull() ?: (size - 1)
            start to end
        }
        if (start !in 0 until size || end < start) return null
        return start to end.coerceAtMost(size - 1)
    }
}

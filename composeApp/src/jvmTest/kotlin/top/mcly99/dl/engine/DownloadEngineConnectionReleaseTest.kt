package top.mcly99.dl.engine

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import top.mcly99.dl.model.DownloadStatus
import top.mcly99.dl.model.DownloadTask
import top.mcly99.dl.testutil.TestTimeouts
import top.mcly99.dl.testutil.awaitElement
import top.mcly99.dl.testutil.isTerminal
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2-04 回归：单线程路径的连接必须在**任何**退出路径上断开并注销。
 *
 * 旧实现的 try/finally 只包住「读数据」阶段；416 从头重下、restartFromZero 重连、
 * FileChannel.open/truncate 抛异常（磁盘满 / 目标是目录）这些早期异常会留下
 * 一条既没 disconnect、也没从 taskConnections 移除的 HttpURLConnection。
 *
 * 两层验证：
 * 1. 收口函数本身对假连接确实调用 disconnect() 并注销；
 * 2. 真实走一遍本地 HttpServer，正常成功与「打开通道前抛异常」都不残留登记的连接。
 */
class DownloadEngineConnectionReleaseTest {

    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private lateinit var engine: DownloadEngine
    private lateinit var tempDir: File

    private val data = ByteArray(16 * 1024) { (it * 7 + 3).toByte() }

    /** 确认测试真的走到了建连之后才失败，而不是在 addTask 阶段就被挡掉 */
    private val requests = java.util.concurrent.atomic.AtomicInteger(0)

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("ktdown-conn-release").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        executor = Executors.newFixedThreadPool(4)
        server.executor = executor
        server.createContext("/file") { exchange ->
            try {
                requests.incrementAndGet()
                if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                    // 不声明 Accept-Ranges：Range 探测拿到 200，强制走单线程路径
                    exchange.sendResponseHeaders(200, -1)
                    return@createContext
                }
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.use { it.write(data) }
            } finally {
                exchange.close()
            }
        }
        server.start()
        engine = DownloadEngine(
            taskPersistenceEnabled = false,
            multiThreadThresholdBytes = Long.MAX_VALUE,
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
    fun releaseSingleConnection_disconnects_and_unregisters() {
        val fake = FakeConnection()
        trackedConnections()["task-1"] = fake

        engine.releaseSingleConnection("task-1", fake)

        assertTrue(fake.disconnected, "收口必须真正调用 disconnect()")
        assertFalse(trackedConnections().containsKey("task-1"), "收口必须移除登记表里的引用")
    }

    @Test
    fun early_failure_before_channel_open_leaves_no_tracked_connection() {
        // 父路径是一个普通文件 → FileChannel.open(CREATE, WRITE) 抛 NotDirectoryException。
        // 目标路径本身不存在，addTask 不会因「已存在」自动换名，能真正走到
        // openSingleSource 成功返回之后、打开通道之前的早期异常点（P2-04 的泄漏点）。
        val parentAsFile = tempDir.resolve("not-a-directory").apply { writeBytes(byteArrayOf(1)) }
        val target = parentAsFile.resolve("child.bin")
        val id = engine.addTask(
            url = url(),
            savePath = target.toPath(),
            threads = 1,
            totalSize = data.size.toLong(),
        )
        val task = awaitTerminal(id)

        assertTrue(requests.get() > 0, "必须先完成建连，否则没有覆盖到目标异常路径")
        assertEquals(DownloadStatus.FAILED, task.status, "打开通道失败必须让任务失败，而不是静默成功")
        assertTrue(
            trackedConnections().isEmpty(),
            "早期异常后 taskConnections 不得残留连接（旧实现会泄漏到 GC 才释放）",
        )
    }

    @Test
    fun successful_single_thread_download_also_clears_tracked_connection() {
        val target = tempDir.resolve("ok.bin")
        val id = engine.addTask(
            url = url(),
            savePath = target.toPath(),
            threads = 1,
            totalSize = data.size.toLong(),
        )
        val task = awaitTerminal(id)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.errorMessage)
        assertContentEquals(data, target.readBytes())
        assertTrue(trackedConnections().isEmpty(), "正常完成也必须断开并注销连接")
    }

    // ---------------------------------------------------------------- 辅助

    private fun url() = "http://127.0.0.1:${server.address.port}/file"

    private fun awaitTerminal(id: String): DownloadTask = runBlocking {
        engine.taskState.awaitElement(TestTimeouts.LONG, { "任务 $id 未在超时前进入终态" }) {
            it.id == id && it.status.isTerminal
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun trackedConnections(): MutableMap<String, HttpURLConnection> {
        val field = DownloadEngine::class.java.getDeclaredField("taskConnections")
        field.isAccessible = true
        return field.get(engine) as MutableMap<String, HttpURLConnection>
    }

    /** 不触碰网络的假连接，只记录 disconnect() 是否被调用 */
    private class FakeConnection : HttpURLConnection(URL("http://127.0.0.1/")) {
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
    }
}

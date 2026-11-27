package top.mcly99.dl.engine

import top.mcly99.dl.testutil.awaitUntil
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2-01 回归：网页上传服务的启停串行化与活跃连接回收。
 *
 * 旧实现：`start()` 跑在多线程 ioScope、`stop()` 在 Main，四个生命周期字段无同步；
 * `stop()` 只 `activeUploads.clear()`，而 `HttpServer.stop(0)` 不关已建立的 HttpExchange，
 * 阻塞中的读只能靠看门狗兜底 —— 偏偏看门狗先被关掉。
 */
class WebUploadServerLifecycleTest {

    private lateinit var dir: File
    private val servers = mutableListOf<WebUploadServer>()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("webupload-lifecycle-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop() }
        dir.deleteRecursively()
    }

    private fun newServer(): WebUploadServer =
        WebUploadServer(saveDir = dir.absolutePath, idleTimeoutMs = 60_000, bindHost = "127.0.0.1")
            .also { servers += it }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun concurrent_start_stop_leaves_a_consistent_running_state() {
        val server = newServer()
        val port = freePort()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 12).map { i ->
                pool.submit(Callable<Unit> {
                    if (i % 4 == 0) server.stop() else server.start(port)
                })
            }
            tasks.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        // 并发交错之后仍能收敛到一个确定状态
        assertTrue(server.start(port), "并发启停后应能正常启动")
        assertTrue(server.isRunning)
        server.stop()
        assertFalse(server.isRunning)
    }

    @Test
    fun expired_start_request_is_rejected_inside_the_lifecycle_lock() {
        val server = newServer()
        val port = freePort()
        assertFalse(server.start(port) { false }, "已被禁用的在途启动请求必须放弃")
        assertFalse(server.isRunning)
        assertTrue(server.start(port))
        assertTrue(server.isRunning)
    }

    @Test
    fun stop_closes_an_in_flight_upload_exchange() {
        val server = newServer()
        val port = freePort()
        assertTrue(server.start(port))
        val session = WebUploadTestSession.bootstrap(port, server.accessToken)
        assertTrue(session.code == 302, "引导应成功，否则测不到活跃连接关闭")
        val socket = Socket("127.0.0.1", port)
        try {
            socket.soTimeout = 3_000
            val out = socket.getOutputStream()
            val head = buildString {
                append("POST / HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("Cookie: ${session.cookie}\r\n")
                append("Content-Type: multipart/form-data; boundary=BOUND\r\n")
                // 声明 4096 字节但只发一小段：服务端会阻塞在 requestBody.read()
                append("Content-Length: 4096\r\n\r\n")
                append("--BOUND\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n\r\n")
                append("partial")
            }
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val active = activeUploads(server)
            awaitUntil(3_000, describe = { "服务端应进入 handleUpload 的阻塞读" }) { active.isNotEmpty() }

            server.stop()
            val read = try {
                socket.getInputStream().read()
            } catch (e: java.net.SocketTimeoutException) {
                throw AssertionError("stop 之后活跃连接仍未被关闭（读超时）", e)
            } catch (_: java.io.IOException) {
                -1
            }
            assertEquals(-1, read, "stop 必须主动关闭已建立的 HttpExchange，让阻塞读立即退出")
        } finally {
            socket.close()
        }
    }

    @Test
    fun lifecycle_is_serialized_and_closes_connections_before_the_watchdog() {
        val source = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/engine/WebUploadServer.kt")
        assertTrue(source.contains("private val lifecycleLock = Any()"), "启停必须共用一把锁")
        assertTrue(source.contains("fun start(listenPort: Int, allowStart"), "启动需要过期校验入口")
        assertTrue(source.contains("closeActiveUploads(null)"), "stop 必须关闭全部活跃连接")
        assertTrue(source.contains("closeActiveUploads(previousServer)"), "换端口必须关闭旧服务的活跃连接")

        val stopBody = source.substringAfter("fun stop(): Unit = synchronized(lifecycleLock)")
        val closeAt = stopBody.indexOf("closeActiveUploads(null)")
        val watchdogAt = stopBody.indexOf("watchdog?.shutdownNow()")
        assertTrue(closeAt >= 0 && watchdogAt > closeAt, "顺序必须是先关活跃连接、再关看门狗")

        val viewSource = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/viewmodel/TransferViewModel.kt")
        assertTrue(viewSource.contains("webServerGeneration++"), "禁用开关必须作废旧代次")
        assertTrue(viewSource.contains("isCurrentWebServerGeneration(generation)"), "在途启动必须核对代次")
    }

    /** 只看内部集合是否登记了活跃请求；不依赖裸 sleep 猜服务端时序 */
    private fun activeUploads(server: WebUploadServer): MutableSet<*> {
        val field = WebUploadServer::class.java.getDeclaredField("activeUploads")
            .apply { isAccessible = true }
        return field.get(server) as MutableSet<*>
    }

    private fun sourceOf(relative: String): String =
        listOf(File(relative), File("../$relative"), File("../../$relative"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("找不到源码 $relative（工作目录 ${System.getProperty("user.dir")}）")
}

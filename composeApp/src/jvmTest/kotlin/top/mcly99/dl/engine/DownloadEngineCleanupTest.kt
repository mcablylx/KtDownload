package top.mcly99.dl.engine

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import top.mcly99.dl.model.DownloadStatus
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P1-04 / P1-05 回归：收尾清理协程的生命周期与路径占用。
 *
 * 1.0.3 的清理协程挂在会被 `scope.cancel()` 的可取消作用域上，且 `shutdown()` 先 cancel
 * 不 join；加上 `stoppingJobs` 单值覆盖、`removeTask` 不登记，会导致：
 * - 「移除并删除文件」后立刻关应用 → 文件没删掉、`deleteFile=true` 承诺落空；
 * - 删除分片下载中的任务后立刻重加同名文件 → 复用同一组 `.partN` 被旧协程删掉。
 *
 * 本测试不依赖「一定能抓到竞态窗口」：直接验证 shutdown 会等待清理完成，
 * 以及清理期间同名路径被递增序号。
 */
class DownloadEngineCleanupTest {

    private lateinit var server: HttpServer
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var tempDir: File
    private val slowData = ByteArray(512 * 1024) { (it * 7 + 3).toByte() }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("ktdown-cleanup-test").toFile()
        executor = Executors.newFixedThreadPool(4)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        // 慢速端点：足够在下载中途取消/删除，随后立刻 shutdown
        server.createContext("/slow") { exchange ->
            try {
                if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                    exchange.responseHeaders.add("Accept-Ranges", "none")
                    exchange.sendResponseHeaders(200, -1)
                    return@createContext
                }
                exchange.responseHeaders.add("Content-Length", slowData.size.toString())
                exchange.sendResponseHeaders(200, slowData.size.toLong())
                exchange.responseBody.use { out ->
                    var sent = 0
                    while (sent < slowData.size) {
                        val n = minOf(8 * 1024, slowData.size - sent)
                        out.write(slowData, sent, n)
                        out.flush()
                        sent += n
                        Thread.sleep(25)
                    }
                }
            } finally {
                runCatching { exchange.close() }
            }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        executor.shutdownNow()
        tempDir.deleteRecursively()
    }

    private fun newEngine() = DownloadEngine(
        taskPersistenceEnabled = false,
        multiThreadThresholdBytes = 64L * 1024L,
    )

    private fun url() = "http://127.0.0.1:${server.address.port}/slow"

    private fun awaitDownloading(engine: DownloadEngine, id: String) = runBlocking {
        engine.taskState.awaitElement(TestTimeouts.MEDIUM, { "任务 $id 未进入 DOWNLOADING" }) {
            it.id == id && it.status == DownloadStatus.DOWNLOADING
        }
    }

    @Test
    fun shutdown_waits_for_remove_with_delete_file_to_finish() {
        val engine = newEngine()
        val target = tempDir.resolve("remove-and-exit.bin")
        val id = engine.addTask(url = url(), savePath = target.toPath(), threads = 2)
        engine.startDownload(id)
        awaitDownloading(engine, id)
        // 预置成品文件，让 deleteFile=true 的断言不依赖「下载恰好写出到哪一步」
        target.writeBytes(ByteArray(64) { it.toByte() })

        // 「移除并删除文件」后立刻关应用：这是 1.0.3 会丢文件的时序
        engine.removeTask(id, deleteFile = true)
        engine.shutdown()

        assertFalse(target.exists(), "shutdown 必须等清理协程跑完，deleteFile=true 不能落空（P1-05）")
        assertFalse(File(target.parentFile, "${target.name}.part0").exists(), "残片也要清掉")
    }

    @Test
    fun shutdown_waits_for_cancel_to_remove_partial_files() {
        val engine = newEngine()
        val target = tempDir.resolve("cancel-and-exit.bin")
        val id = engine.addTask(url = url(), savePath = target.toPath(), threads = 2)
        engine.startDownload(id)
        awaitDownloading(engine, id)

        engine.cancelDownload(id)
        engine.shutdown()

        assertFalse(target.exists(), "取消后立即退出也要删掉未完成的目标文件")
        val leftovers = target.parentFile.listFiles().orEmpty().filter {
            it.name.startsWith("${target.name}.part") || it.name.startsWith("${target.name}.offset")
        }
        assertTrue(leftovers.isEmpty(), "不应残留 .partN/.offset: ${leftovers.map { it.name }}")
    }

    @Test
    fun a_path_still_being_cleaned_is_never_reused_for_a_new_task() {
        val engine = newEngine()
        val target = tempDir.resolve("race.bin")
        // 直接构造「清理协程仍在跑」的状态：不依赖能否抓到真实的竞态窗口，
        // 断言的是 addTask 的选名谓词本身（P1-04）。
        engine.cleaningPaths.add(target.toPath().toAbsolutePath().normalize())

        val id = engine.addTask(url = url(), savePath = target.toPath(), threads = 2)
        val newPath = engine.getTask(id)!!.savePath.toFile()
        assertFalse(
            newPath.toPath().toAbsolutePath().normalize() == target.toPath().toAbsolutePath().normalize(),
            "清理协程仍在跑时重加同名文件必须换名（P1-04），实际 ${newPath.name}",
        )

        // 清理完成后路径被释放，后续任务可以重新使用原名
        engine.cleaningPaths.clear()
        val id2 = engine.addTask(url = url(), savePath = target.toPath(), threads = 2)
        assertEquals(target.name, engine.getTask(id2)!!.savePath.fileName.toString())
        engine.shutdown()
    }

    @Test
    fun stopping_jobs_are_joined_before_a_restarted_task_writes_again() {
        val engine = newEngine()
        val target = tempDir.resolve("restart.bin")
        val id = engine.addTask(url = url(), savePath = target.toPath(), threads = 2)
        engine.startDownload(id)
        awaitDownloading(engine, id)

        // 连点「取消 → 开始 → 取消 → 开始」：1.0.3 的 stoppingJobs 单值覆盖会丢掉
        // 前一个 job 的 join 点。现在应能全部 join，且新任务仍能正常开始下载。
        engine.cancelDownload(id)
        engine.pauseDownload(id)
        engine.startDownload(id)
        engine.cancelDownload(id)
        engine.startDownload(id)

        runBlocking {
            engine.taskState.awaitElement(TestTimeouts.MEDIUM, { "重开后任务未进入下载/终态" }) {
                it.id == id && (it.status == DownloadStatus.DOWNLOADING || it.status.isTerminal)
            }
        }
        engine.shutdown()
    }
}

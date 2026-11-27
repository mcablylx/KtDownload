package top.mcly99.dl.engine

import kotlinx.coroutines.runBlocking
import top.mcly99.dl.testutil.awaitUntil
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2-03 回归：TransferEngine 的 `if (job?.isActive != true) startXxx()` 不再是
 * check-then-act；`stop()` 也不再只 cancel 两个根 Job 而把 accept 子协程、旧 Job 的
 * finally 留在半空。
 */
class TransferEngineLifecycleTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun concurrent_start_stop_and_restart_keep_a_single_file_server() = runBlocking {
        val engine = TransferEngine(transferPort = freePort())
        // 关掉 UDP 发现，避免测试与默认引擎抢占固定的 9876 端口
        engine.setDiscoveryEnabled(false)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 12).map { i ->
                pool.submit(Callable<Unit> {
                    if (i % 4 == 0) engine.stop() else engine.start()
                })
            }
            tasks.forEach { it.get(15, TimeUnit.SECONDS) }

            engine.start()
            awaitUntil(3_000, describe = { "并发启停后文件接收服务应处于运行态" }) {
                engine.fileServerRunning
            }
            assertTrue(engine.fileServerRunning)

            engine.stop()
            assertFalse(engine.fileServerRunning, "stop 必须同步清掉运行标志")

            // 作用域重建后可再次启动：旧 Job 的 finally 不许把新作用域的状态覆盖掉
            engine.start()
            awaitUntil(3_000, describe = { "stop 之后应能用新生命周期作用域重新启动" }) {
                engine.fileServerRunning
            }
            assertTrue(engine.fileServerRunning)
        } finally {
            pool.shutdownNow()
            engine.stop()
        }
    }

    @Test
    fun lifecycle_is_serialized_and_cancels_the_whole_job_tree() {
        val source = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/engine/TransferEngine.kt")
        assertTrue(source.contains("private val lifecycleLock = Any()"), "启停必须共用一把锁")
        assertTrue(source.contains("lifecycleJob.cancel()"), "stop 必须取消整个生命周期 Job")
        assertTrue(
            source.contains("scope = CoroutineScope(Dispatchers.IO + lifecycleJob)"),
            "stop 后必须重建作用域，否则无法再次 start",
        )
        assertTrue(source.contains("scope === myScope"), "旧 Job 的字段写回必须有发布守卫")
        assertTrue(source.contains("acceptedSockets.forEach { closeQuietly"), "已 accept 连接必须显式关闭")
        assertFalse(
            source.contains("private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())"),
            "不应再是不可重建的单一作用域",
        )
    }

    private fun sourceOf(relative: String): String =
        listOf(File(relative), File("../$relative"), File("../../$relative"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("找不到源码 $relative（工作目录 ${System.getProperty("user.dir")}）")
}

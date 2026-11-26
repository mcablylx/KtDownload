package top.mcly99.dl.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import top.mcly99.dl.model.AppSettings
import top.mcly99.dl.model.DownloadSource
import top.mcly99.dl.model.DownloadStatus
import top.mcly99.dl.model.DownloadTask
import top.mcly99.dl.testutil.FakeDownloadOperations
import top.mcly99.dl.testutil.FakeDownloadOperations.RemovedTask
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DownloadViewModel] 的行为单测（P3-T2）。
 *
 * 通过 [DownloadOperations] 窄接口 + [FakeDownloadOperations] 把引擎换成内存替身，
 * 这里只验证 ViewModel 自己的决定：什么时候问用户、什么时候连残片删、
 * 「全部开始」拿的是哪份快照、改并发后有没有补位。
 *
 * 真正落盘/连网的下载逻辑仍由 `DownloadEngine*Test` 覆盖，两边不重叠。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadViewModelTest {

    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun cleanTempDirs() {
        tempDirs.forEach { dir ->
            runCatching {
                Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        tempDirs.clear()
    }

    // ------------------------------------------------------------ 状态镜像

    @Test
    fun tasks_mirror_the_engine_state_flow() = runTest {
        val engine = FakeDownloadOperations()
        val vm = viewModel(engine)

        engine.emit(listOf(task("a", DownloadStatus.COMPLETED), task("b", DownloadStatus.DOWNLOADING)))
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), vm.tasks.map { it.id })
    }

    // ------------------------------------------------------------ 点「×」

    @Test
    fun removing_unfinished_task_deletes_record_and_partial() = runTest {
        // 未完成的任务没有值得保留的成品：直接连残片清掉，不问用户
        val engine = FakeDownloadOperations()
        val vm = viewModel(engine)
        engine.setTasks(listOf(task("run", DownloadStatus.DOWNLOADING)))

        vm.requestRemoveTask("run")

        assertNull(vm.pendingRemove)
        assertEquals(listOf(RemovedTask("run", deleteFile = true)), engine.removed)
    }

    @Test
    fun completed_task_with_file_asks_user_then_keeps_file() = runTest {
        val engine = FakeDownloadOperations()
        val vm = viewModel(engine)
        val file = newTempDir().resolve("movie.mp4")
        Files.createFile(file)
        engine.setTasks(listOf(task("done", DownloadStatus.COMPLETED, file)))

        vm.requestRemoveTask("done")

        // 先什么都不删，等用户选
        assertEquals("done", vm.pendingRemove?.id)
        assertTrue(engine.removed.isEmpty())

        vm.confirmRemoveKeepingFile()

        assertEquals(listOf(RemovedTask("done", deleteFile = false)), engine.removed)
        assertNull(vm.pendingRemove)
    }

    @Test
    fun completed_task_with_file_can_be_removed_with_file() = runTest {
        val engine = FakeDownloadOperations()
        val vm = viewModel(engine)
        val file = newTempDir().resolve("movie.mp4")
        Files.createFile(file)
        engine.setTasks(listOf(task("done", DownloadStatus.COMPLETED, file)))

        vm.requestRemoveTask("done")
        vm.confirmRemoveWithFile()

        assertEquals(listOf(RemovedTask("done", deleteFile = true)), engine.removed)
    }

    @Test
    fun completed_task_whose_file_is_gone_removes_record_without_asking() = runTest {
        // 用户已经在资源管理器里删了成品：没有可问的，直接移除记录
        val engine = FakeDownloadOperations()
        val vm = viewModel(engine)
        val missing = newTempDir().resolve("already-deleted.mp4")
        engine.setTasks(listOf(task("done", DownloadStatus.COMPLETED, missing)))

        vm.requestRemoveTask("done")

        assertNull(vm.pendingRemove)
        assertEquals(listOf(RemovedTask("done", deleteFile = false)), engine.removed)
    }

    @Test
    fun cancelling_pending_remove_leaves_task_alone() = runTest {
        val engine = FakeDownloadOperations()
        val vm = viewModel(engine)
        val file = newTempDir().resolve("movie.mp4")
        Files.createFile(file)
        engine.setTasks(listOf(task("done", DownloadStatus.COMPLETED, file)))

        vm.requestRemoveTask("done")
        vm.cancelPendingRemove()

        assertNull(vm.pendingRemove)
        assertTrue(engine.removed.isEmpty())
    }

    // ------------------------------------------------------------ 批量操作

    @Test
    fun clear_completed_removes_records_but_keeps_files() = runTest {
        val engine = FakeDownloadOperations()
        val vm = viewModel(engine)
        val file = newTempDir().resolve("movie.mp4")
        Files.createFile(file)
        engine.setTasks(
            listOf(
                task("done", DownloadStatus.COMPLETED, file),
                task("run", DownloadStatus.DOWNLOADING),
            ),
        )
        advanceUntilIdle()

        vm.clearCompleted()

        assertEquals(listOf(RemovedTask("done", deleteFile = false)), engine.removed)
    }

    @Test
    fun start_all_uses_engine_snapshot_not_ui_list() = runTest {
        // AppViewModel 初始化时 StateFlow 可能还没完成第一次收集，此时 tasks 为空。
        // 「全部开始」必须拿引擎快照，否则恢复的任务不会按 autoStart 启动。
        val engine = FakeDownloadOperations()
        val vm = viewModel(engine)
        engine.setTasks(
            listOf(
                task("queued", DownloadStatus.QUEUED),
                task("paused", DownloadStatus.PAUSED),
                task("running", DownloadStatus.DOWNLOADING),
                task("failed", DownloadStatus.FAILED),
            ),
        )

        vm.startAll()

        assertEquals(listOf("queued", "paused"), engine.started)
    }

    // ------------------------------------------------------------ 配置

    @Test
    fun changing_max_concurrent_requests_a_dispatch() = runTest {
        // P1-6：把 1 改成 8 后排队任务必须立刻补位，调度触发点原来只在任务事件上
        val engine = FakeDownloadOperations()
        val vm = viewModel(engine)

        vm.changeMaxConcurrent(8)

        assertEquals(8, engine.maxConcurrentDownloads)
        assertEquals(1, engine.dispatchRequests)
    }

    @Test
    fun changing_threads_ignores_values_outside_options() = runTest {
        val engine = FakeDownloadOperations()
        val vm = viewModel(engine)

        vm.changeDownloadThreads(3)
        assertEquals(8, engine.downloadThreadsPerFile)

        vm.changeDownloadThreads(4)
        assertEquals(4, engine.downloadThreadsPerFile)
    }

    @Test
    fun settings_are_pushed_into_engine_on_start() = runTest {
        val engine = FakeDownloadOperations()
        val downloadPath = newTempDir().resolve("downloads")
        viewModel(
            engine,
            settings = AppSettings(
                downloadPath = downloadPath.toString(),
                maxConcurrent = 5,
                defaultThreads = 4,
                speedLimit = 2048L,
                keepHlsSegments = true,
            ),
        )

        assertEquals(1, engine.restoreCalls)
        assertEquals(5, engine.maxConcurrentDownloads)
        assertEquals(4, engine.downloadThreadsPerFile)
        assertEquals(2048L, engine.speedLimit)
        assertEquals(downloadPath, engine.defaultDownloadPath)
        assertTrue(Files.isDirectory(downloadPath), "下载目录应被创建")
        assertTrue(engine.keepHlsSegments)
        // 直连配置下不应注入代理
        assertNull(engine.proxyProvider())
    }

    // ------------------------------------------------------------ 添加任务

    @Test
    fun add_download_forwards_headers_and_alt_sources() = runTest {
        val engine = FakeDownloadOperations()
        val vm = viewModel(engine, settings = AppSettings(downloadPath = "", defaultThreads = 4))

        val id = vm.addDownload(
            url = "https://cdn.example.com/v.mp4",
            referer = "https://page.example.com/p/1",
            cookie = "sid=abc",
            totalSize = 42L,
            altSources = listOf(DownloadSource(url = "https://backup.example.com/v.mp4")),
        )
        advanceUntilIdle()

        assertEquals("fake-1", id)
        val added = engine.added.single()
        assertEquals(4, added.threads, "未传 threads 时应沿用配置里的线程数")
        assertEquals("https://page.example.com/p/1", added.referer)
        assertEquals("sid=abc", added.cookie)
        assertEquals(42L, added.totalSize)
        assertEquals(1, added.altSources.size)
    }

    // ------------------------------------------------------------ 生命周期

    @Test
    fun shutdown_closes_engine() = runTest {
        val engine = FakeDownloadOperations()
        val vm = viewModel(engine)

        vm.shutdown()

        assertEquals(1, engine.shutdownCalls)
    }

    // ------------------------------------------------------------ 辅助

    private fun TestScope.viewModel(
        engine: FakeDownloadOperations,
        // 空 downloadPath：默认值 "/Downloads" 会在测试机上真的建目录
        settings: AppSettings = AppSettings(downloadPath = ""),
    ): DownloadViewModel = DownloadViewModel(
        engine = engine,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
        settingsLoader = { settings },
    )

    private fun newTempDir(): Path {
        val dir = Files.createTempDirectory("ktdl-vm-")
        // 注意用 add：Path 实现了 Iterable<Path>，`+=` 会被解析成 plus(Iterable) 返回新 List
        tempDirs.add(dir)
        return dir
    }

    private fun task(
        id: String,
        status: DownloadStatus,
        savePath: Path = Path.of("build", "fake-downloads", "$id.mp4"),
    ) = DownloadTask(
        id = id,
        url = "https://cdn.example.com/$id.mp4",
        fileName = "$id.mp4",
        savePath = savePath,
        status = status,
        threads = 4,
    )
}

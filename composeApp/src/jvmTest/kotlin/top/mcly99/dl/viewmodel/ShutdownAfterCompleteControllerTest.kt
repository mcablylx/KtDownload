package top.mcly99.dl.viewmodel

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import top.mcly99.dl.model.DownloadStatus
import top.mcly99.dl.model.DownloadTask
import java.nio.file.Paths

/** 对齐上游 v1.3.4「完成后关机」的四条判定口径（下游自研状态机）。 */
class ShutdownAfterCompleteControllerTest {

    private fun task(status: DownloadStatus, id: String = "t1") = DownloadTask(
        id = id,
        url = "https://example.com/file",
        fileName = "file.bin",
        savePath = Paths.get("/tmp/file.bin"),
        status = status,
    )

    @Test
    fun just_enabling_the_switch_does_not_shut_down_an_idle_machine() = runTest {
        var calls = 0
        val vm = ShutdownAfterCompleteController(this, { calls++ }, countdownSeconds = 3)
        vm.applyEnabled(true)
        vm.onTasksChanged(emptyList())
        vm.onTasksChanged(listOf(task(DownloadStatus.COMPLETED)))
        advanceUntilIdle()
        assertNull(vm.countdown)
        assertEquals(0, calls)
    }

    @Test
    fun a_run_then_a_drained_queue_triggers_shutdown_after_countdown() = runTest {
        var calls = 0
        val vm = ShutdownAfterCompleteController(this, { calls++ }, countdownSeconds = 3)
        vm.applyEnabled(true)
        vm.onTasksChanged(listOf(task(DownloadStatus.DOWNLOADING)))
        vm.onTasksChanged(listOf(task(DownloadStatus.COMPLETED)))
        runCurrent() // 让倒计时协程跑到第一个 delay，再断言「正在倒计时」
        assertNotNull(vm.countdown)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertNull(vm.countdown)
    }

    @Test
    fun a_paused_task_blocks_shutdown() = runTest {
        var calls = 0
        val vm = ShutdownAfterCompleteController(this, { calls++ }, countdownSeconds = 3)
        vm.applyEnabled(true)
        vm.onTasksChanged(listOf(task(DownloadStatus.DOWNLOADING)))
        vm.onTasksChanged(
            listOf(task(DownloadStatus.COMPLETED), task(DownloadStatus.PAUSED, "paused")),
        )
        advanceUntilIdle()
        assertNull(vm.countdown)
        assertEquals(0, calls)
    }

    @Test
    fun a_new_task_cancels_the_running_countdown() = runTest {
        var calls = 0
        val vm = ShutdownAfterCompleteController(this, { calls++ }, countdownSeconds = 3)
        vm.applyEnabled(true)
        vm.onTasksChanged(listOf(task(DownloadStatus.DOWNLOADING)))
        vm.onTasksChanged(listOf(task(DownloadStatus.COMPLETED)))
        runCurrent()
        assertNotNull(vm.countdown)
        vm.onTasksChanged(listOf(task(DownloadStatus.QUEUED, "new"), task(DownloadStatus.COMPLETED)))
        assertNull(vm.countdown)
        advanceUntilIdle()
        assertEquals(0, calls)
    }

    @Test
    fun cancel_disarms_and_does_not_rearm_on_the_next_refresh() = runTest {
        var calls = 0
        val vm = ShutdownAfterCompleteController(this, { calls++ }, countdownSeconds = 3)
        vm.applyEnabled(true)
        vm.onTasksChanged(listOf(task(DownloadStatus.DOWNLOADING)))
        vm.onTasksChanged(listOf(task(DownloadStatus.COMPLETED)))
        vm.cancel()
        vm.onTasksChanged(listOf(task(DownloadStatus.COMPLETED)))
        advanceUntilIdle()
        assertNull(vm.countdown)
        assertEquals(0, calls)
    }

    @Test
    fun disabling_the_switch_cancels_everything() = runTest {
        var calls = 0
        val vm = ShutdownAfterCompleteController(this, { calls++ }, countdownSeconds = 3)
        vm.applyEnabled(true)
        vm.onTasksChanged(listOf(task(DownloadStatus.DOWNLOADING)))
        vm.onTasksChanged(listOf(task(DownloadStatus.COMPLETED)))
        vm.applyEnabled(false)
        advanceUntilIdle()
        assertNull(vm.countdown)
        assertEquals(0, calls)
    }

    @Test
    fun shutdown_now_fires_exactly_once() = runTest {
        var calls = 0
        val vm = ShutdownAfterCompleteController(this, { calls++ }, countdownSeconds = 3)
        vm.applyEnabled(true)
        vm.onTasksChanged(listOf(task(DownloadStatus.DOWNLOADING)))
        vm.onTasksChanged(listOf(task(DownloadStatus.COMPLETED)))
        vm.shutdownNow()
        advanceUntilIdle()
        assertEquals(1, calls)
        assertNull(vm.countdown)
    }

    @Test
    fun merging_counts_as_active_so_the_file_is_not_left_half_merged() = runTest {
        var calls = 0
        val vm = ShutdownAfterCompleteController(this, { calls++ }, countdownSeconds = 3)
        vm.applyEnabled(true)
        vm.onTasksChanged(listOf(task(DownloadStatus.MERGING)))
        vm.onTasksChanged(listOf(task(DownloadStatus.MERGING), task(DownloadStatus.COMPLETED)))
        advanceUntilIdle()
        assertNull(vm.countdown)
        assertEquals(0, calls)
    }
}

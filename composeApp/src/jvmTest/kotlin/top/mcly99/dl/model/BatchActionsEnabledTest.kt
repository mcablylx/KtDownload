package top.mcly99.dl.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 顶栏批量按钮的 enabled 判定（P2-23）。
 *
 * 判定口径严格对齐三个操作真正会处理的状态：
 * - `startAll()` 只认 QUEUED / PAUSED；
 * - `pauseAllDownloads()` 认 DOWNLOADING / MERGING / QUEUED；
 * - `clearCompleted()` 只认 COMPLETED。
 * 这样不会出现「按钮亮着、点下去什么都没发生」。
 */
class BatchActionsEnabledTest {

    @Test
    fun empty_list_disables_all_three_buttons() {
        val s = batchActionsEnabled(emptyList())
        assertFalse(s.startAll)
        assertFalse(s.pauseAll)
        assertFalse(s.clearCompleted)
    }

    @Test
    fun only_completed_enables_only_clear_completed() {
        val s = batchActionsEnabled(listOf(task(DownloadStatus.COMPLETED)))
        assertFalse(s.startAll)
        assertFalse(s.pauseAll)
        assertTrue(s.clearCompleted)
    }

    @Test
    fun downloading_and_merging_are_pausable_but_not_startable() {
        listOf(DownloadStatus.DOWNLOADING, DownloadStatus.MERGING).forEach { status ->
            val s = batchActionsEnabled(listOf(task(status)))
            assertFalse(s.startAll, "$status 不该算「可开始」")
            assertTrue(s.pauseAll, "$status 必须算「可暂停」")
            assertFalse(s.clearCompleted)
        }
    }

    @Test
    fun queued_is_both_startable_and_pausable() {
        val s = batchActionsEnabled(listOf(task(DownloadStatus.QUEUED)))
        assertTrue(s.startAll)
        assertTrue(s.pauseAll, "QUEUED 必须一起暂停，否则空出的名额会被排队任务立刻占走")
        assertFalse(s.clearCompleted)
    }

    @Test
    fun paused_is_startable_but_not_pausable() {
        val s = batchActionsEnabled(listOf(task(DownloadStatus.PAUSED)))
        assertTrue(s.startAll)
        assertFalse(s.pauseAll, "已经暂停的任务再暂停没有意义")
        assertFalse(s.clearCompleted)
    }

    @Test
    fun failed_and_cancelled_enable_nothing() {
        // startAll 明确「失败任务需手动重试」，不能把按钮点亮后又什么都不做
        listOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED).forEach { status ->
            val s = batchActionsEnabled(listOf(task(status)))
            assertFalse(s.startAll, "$status 不该算「可开始」")
            assertFalse(s.pauseAll)
            assertFalse(s.clearCompleted)
        }
    }

    @Test
    fun mixed_list_evaluates_each_button_independently() {
        val s = batchActionsEnabled(
            listOf(
                task(DownloadStatus.COMPLETED),
                task(DownloadStatus.PAUSED),
                task(DownloadStatus.DOWNLOADING),
                task(DownloadStatus.FAILED),
            )
        )
        assertTrue(s.startAll)
        assertTrue(s.pauseAll)
        assertTrue(s.clearCompleted)
    }

    private fun task(status: DownloadStatus) = UiTask(
        id = "t",
        name = "file.bin",
        url = "https://example.com/file.bin",
        type = TaskType.Http,
        status = status,
        progress = 0,
        size = 0,
        downloaded = 0,
        speed = 0,
        threads = 1,
        createdAtEpochMs = 0L,
        savePath = "/tmp",
    )
}

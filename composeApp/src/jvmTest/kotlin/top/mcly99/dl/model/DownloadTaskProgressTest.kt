package top.mcly99.dl.model

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [DownloadTask.progressPercent] 的显示规则（v3-P3-5）。
 *
 * 未完成时最多显示 99%，避免 199/200 被浮点截断成 100% 后 UI 提前显示"完成"。
 */
class DownloadTaskProgressTest {

    @Test
    fun hls_progress_is_capped_below_completion() {
        assertEquals(99, task(type = TaskType.M3u8, segmentDone = 199, segmentTotal = 200).progressPercent)
        assertEquals(99, task(type = TaskType.M3u8, segmentDone = 9_999, segmentTotal = 10_000).progressPercent)
    }

    @Test
    fun hls_progress_reaches_hundred_only_when_all_segments_done() {
        assertEquals(100, task(type = TaskType.M3u8, segmentDone = 200, segmentTotal = 200).progressPercent)
    }

    @Test
    fun zero_progress_stays_zero() {
        assertEquals(0, task(type = TaskType.M3u8, segmentDone = 0, segmentTotal = 200).progressPercent)
    }

    @Test
    fun small_non_zero_progress_is_visible() {
        // 0.5% 直接截断会变成 0，用户会以为任务没动；至少显示 1%
        assertEquals(1, task(type = TaskType.M3u8, segmentDone = 1, segmentTotal = 200).progressPercent)
    }

    @Test
    fun http_progress_uses_size_and_keeps_previous_behavior() {
        assertEquals(25, task(totalSize = 1_000L, downloadedSize = 250L).progressPercent)
        assertEquals(99, task(totalSize = 1_000L, downloadedSize = 999L).progressPercent)
        assertEquals(100, task(totalSize = 1_000L, downloadedSize = 1_000L).progressPercent)
    }

    private fun task(
        type: TaskType = TaskType.Http,
        totalSize: Long = 0L,
        downloadedSize: Long = 0L,
        segmentDone: Int = 0,
        segmentTotal: Int = 0,
    ) = DownloadTask(
        id = "task-1",
        url = "https://example.com/movie.m3u8",
        fileName = "movie.mp4",
        savePath = Paths.get("/tmp/movie.mp4"),
        totalSize = totalSize,
        downloadedSize = downloadedSize,
        type = type,
        segmentDone = segmentDone,
        segmentTotal = segmentTotal,
    )
}

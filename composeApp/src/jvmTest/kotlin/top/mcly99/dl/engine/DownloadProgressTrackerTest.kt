package top.mcly99.dl.engine

import top.mcly99.dl.utils.AppConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 进度追踪器回归：**总大小未知时也必须能累计字节并算出速度**。
 *
 * 夸克等 CDN 经常对 HEAD 回 403 / 给 chunked 响应，探测不到 `Content-Length`。
 * 旧实现把 `totalSize` 当上限做 `coerceIn(0, totalSize)`，未知大小（0）时累计值
 * 被压成 0，于是「文件一直在下、进度不动、速度恒 0 B/s」。
 */
class DownloadProgressTrackerTest {

    /** 已知大小：按上限截断，不会超出 100% */
    @Test
    fun known_total_size_clamps_progress() {
        val tracker = DownloadProgressTracker(totalSize = 1000, chunkCount = 2)

        tracker.update(0, 800)
        tracker.update(1, 800)

        assertEquals(1000, tracker.totalDone(), "超出部分应被截断到总大小")
    }

    /** 未知大小（0）：累计值就是真实下载字节数，不能被截成 0 */
    @Test
    fun unknown_total_size_keeps_accumulating_bytes() {
        val tracker = DownloadProgressTracker(totalSize = 0, chunkCount = 1)

        tracker.update(0, 5 * 1024 * 1024)

        assertEquals(5L * 1024 * 1024, tracker.totalDone())
    }

    /** 未知大小时也要能算出速度：两次采样之间 Δ字节 / Δ时间 */
    @Test
    fun unknown_total_size_still_reports_speed() {
        val tracker = DownloadProgressTracker(totalSize = 0, chunkCount = 1)
        val base = 1_000_000L
        val step = AppConstants.IO_POLL_MS

        tracker.update(0, 0)
        tracker.sampleSpeed(base)
        tracker.update(0, 100_000)
        val first = tracker.sampleSpeed(base + step)
        tracker.update(0, 200_000)
        val second = tracker.sampleSpeed(base + step * 2)

        // 100KB / 300ms ≈ 341 KB/s（滑动窗口，允许区间放宽）
        assertTrue(first > 0, "未知大小时首个速度不能是 0，实际 $first")
        assertTrue(second > 0, "未知大小时速度不能是 0，实际 $second")
    }

    /** 取消的分块不再计入总和 */
    @Test
    fun discarded_chunk_is_excluded() {
        val tracker = DownloadProgressTracker(totalSize = 0, chunkCount = 2)
        tracker.update(0, 400)
        tracker.update(1, 600)

        tracker.discard(0)

        assertEquals(600, tracker.totalDone())
    }
}

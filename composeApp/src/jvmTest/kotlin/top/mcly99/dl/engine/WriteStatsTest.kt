package top.mcly99.dl.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 写盘耗时统计（F 项）：慢连接判据靠它区分「网络慢」与「盘慢」，口径必须钉死。 */
class WriteStatsTest {

    @Test
    fun a_slow_write_broadcasts_disk_pressure_for_two_seconds() {
        var now = 10_000L
        val stats = WriteStats(
            stallThresholdNanos = 50L * 1_000_000,
            stallHoldMs = 2_000L,
            clock = { now },
        )

        stats.record(10L * 1_000_000)
        assertFalse(stats.stalledRecently(now), "10 ms 不该算卡住")

        stats.record(60L * 1_000_000)
        assertTrue(stats.stalledRecently(now), "60 ms 应广播磁盘压力")

        now += 2_500L
        assertFalse(stats.stalledRecently(now), "两秒半之后不应再豁免慢连接判定")
    }

    @Test
    fun ratio_is_clamped_and_survives_zero_elapsed() {
        val stats = WriteStats()

        assertEquals(0.0, stats.ratioFor(writeNanos = 5_000_000, elapsedMs = 0), 1e-9)
        assertEquals(0.25, stats.ratioFor(writeNanos = 250_000_000, elapsedMs = 1_000), 1e-9)
        assertEquals(1.0, stats.ratioFor(writeNanos = 9_000_000_000, elapsedMs = 1_000), 1e-9)
    }

    @Test
    fun p95_reports_the_tail_not_the_average() {
        val stats = WriteStats()
        repeat(100) { stats.record(1L * 1_000_000) }
        stats.record(80L * 1_000_000)

        assertTrue(
            stats.p95Nanos() >= 1L * 1_000_000,
            "95 分位应落在常见值附近，实际 ${stats.p95Nanos()}",
        )
        assertEquals(80L * 1_000_000, stats.longestNanos(), "最长耗时必须是那个异常样本")
    }

    @Test
    fun ring_buffer_keeps_only_recent_samples() {
        val stats = WriteStats(capacity = 4)
        repeat(100) { stats.record(2L * 1_000_000) }
        // 末尾补满 4 个异常样本：环形缓冲会把 100 个正常样本全部覆盖掉
        repeat(4) { stats.record(99L * 1_000_000) }

        assertEquals(99L * 1_000_000, stats.longestNanos())
        assertEquals(
            99L * 1_000_000,
            stats.p95Nanos(),
            "只保留最近 4 个样本时 p95 应是异常值；若历史仍在，会被 100 个正常值稀释",
        )
    }

    @Test
    fun reset_clears_every_signal() {
        val stats = WriteStats()
        stats.record(60L * 1_000_000)
        stats.reset()

        assertEquals(0L, stats.p95Nanos())
        assertEquals(0L, stats.longestNanos())
        assertFalse(stats.stalledRecently(60_000L))
    }
}

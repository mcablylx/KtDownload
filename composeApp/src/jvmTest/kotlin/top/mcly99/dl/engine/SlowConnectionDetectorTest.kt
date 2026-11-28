package top.mcly99.dl.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 慢连接判据的回归。
 *
 * 这条判据最容易犯的错不是「没判出来」，而是**误杀**：把 8 条正常共享带宽的连接
 * 全部判成慢、每几秒集体重连一次，速度反而更差。所以大部分用例在验证「不该判」。
 */
class SlowConnectionDetectorTest {

    private val detector = SlowConnectionDetector(graceMs = 2_000, ratio = 4, leftMs = 2_000)

    private fun sample(
        elapsedMs: Long = 5_000,
        bytesReceived: Long = 5_000_000,
        remainingBytes: Long = 50_000_000,
        linkBytesPerSec: Long = 8_000_000,
        activeConnections: Int = 8,
        bestConnectionBytesPerSec: Long = 4_000_000,
        writeWaitRatio: Double = 0.05,
        shedCount: Int = 0,
    ) = SlowConnectionDetector.Sample(
        elapsedMs = elapsedMs,
        bytesReceived = bytesReceived,
        remainingBytes = remainingBytes,
        linkBytesPerSec = linkBytesPerSec,
        activeConnections = activeConnections,
        bestConnectionBytesPerSec = bestConnectionBytesPerSec,
        writeWaitRatio = writeWaitRatio,
        shedCount = shedCount,
    )

    @Test
    fun a_connection_still_inside_the_grace_window_is_never_shed() {
        // 刚建连时的一段低速是正常的 TCP 慢启动/首包延迟，不是「慢」
        assertFalse(detector.shouldShed(sample(elapsedMs = 1_500, bytesReceived = 1_000)))
    }

    @Test
    fun a_connection_at_a_quarter_of_the_baseline_is_still_tolerated() {
        // 基准 1 MB/s（链路 8 MB/s ÷ 8 条），这条正好 256 KB/s = 1/4，还没到「明显偏慢」
        val s = sample(
            bytesReceived = 256_000L * 5,
            linkBytesPerSec = 8_000_000,
            activeConnections = 8,
            bestConnectionBytesPerSec = 4_000_000,
        )
        assertFalse(detector.shouldShed(s), "刚好 1/4 不应剔除，否则会在边界上来回抖")
    }

    @Test
    fun a_connection_far_below_the_baseline_is_shed() {
        // 130 KB/s 对 1 MB/s 的均分带宽：明显是被某条坏路径拖住了
        val s = sample(bytesReceived = 130_000L * 5)
        assertTrue(detector.shouldShed(s))
    }

    @Test
    fun eight_connections_sharing_one_link_are_not_all_shed() {
        // 这是对方 #896 的现场：8 条连接共享 1 Gbit/s，每条只有 ~16 MB/s，
        // 若拿「第一条独占时的 100 MB/s」当基准，8 条全部判慢、每几秒集体重连
        val perConnection = 16_000_000L
        val s = sample(
            bytesReceived = perConnection * 5,
            linkBytesPerSec = perConnection * 8,
            activeConnections = 8,
            bestConnectionBytesPerSec = 100_000_000,
        )
        assertFalse(detector.shouldShed(s), "共享带宽必须按「均分后的份额」比，不能按单连接峰值比")
    }

    @Test
    fun a_slow_connection_near_the_end_of_its_segment_is_left_to_finish() {
        // 还剩 200 KB，按 100 KB/s 也就 2 s，重连的开销比省下来的还多
        val s = sample(bytesReceived = 500_000, remainingBytes = 200_000)
        assertFalse(detector.shouldShed(s))
    }

    @Test
    fun a_connection_blocked_by_the_disk_is_not_treated_as_slow_network() {
        // 写盘慢会让读也停下，看起来和网络掉速一模一样；这时换连接只是把好连接换成另一条
        val s = sample(bytesReceived = 130_000L * 5, writeWaitRatio = 0.8)
        assertFalse(detector.shouldShed(s))
    }

    @Test
    fun a_segment_that_was_already_shed_enough_times_is_left_alone() {
        // 防止「剔除 → 重连 → 又判慢」的死循环：同一段最多折腾 MAX_SHED_PER_SEGMENT 次
        val s = sample(bytesReceived = 130_000L * 5, shedCount = SegmentTuning.MAX_SHED_PER_SEGMENT)
        assertFalse(detector.shouldShed(s))
    }

    @Test
    fun a_genuinely_slow_link_does_not_trigger_any_shedding() {
        // 整条链路都很慢（服务器限速 200 KB/s）：没有「快的」可参照，就不该剔除任何一条
        val s = sample(
            elapsedMs = 5_000,
            bytesReceived = 100_000L * 5,
            linkBytesPerSec = 200_000,
            activeConnections = 2,
            bestConnectionBytesPerSec = 100_000,
        )
        assertFalse(detector.shouldShed(s))
    }
}

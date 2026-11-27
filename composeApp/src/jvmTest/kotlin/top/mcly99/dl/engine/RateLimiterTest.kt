package top.mcly99.dl.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.ByteArrayInputStream
import java.io.InterruptedIOException

/**
 * D-36：下载引擎限速策略单测。
 *
 * 只测可确定的契约（禁用、运行时开启、取消响应、字节不丢失），
 * 不对「精确耗时」做强断言，避免 CI 负载抖动造成偶发失败。
 */
class RateLimiterTest {

    @Test
    fun disabled_limiter_never_blocks() {
        val clock = FakeClock()
        val limiter = RateLimiter(0, clock.nanoTime, clock.sleep)
        assertFalse(limiter.enabled)
        limiter.acquire(10_000_000)
        limiter.acquire(-1)
        assertEquals(0, clock.sleepCalls, "禁用时不应进入任何休眠")
    }

    @Test
    fun rate_can_be_enabled_at_runtime_without_deadlocking() {
        // 回归：burstSize 若按构造时的 0 缓存，开启限速后令牌上限会永远是 0。
        // 换成虚拟时钟后不再是「500ms 内跑完」的墙钟赌注：若退化成死循环，
        // FakeClock.sleep 会在有限次调用后直接 check 失败，测试确定性失败而非挂住。
        val clock = FakeClock()
        val limiter = RateLimiter(0, clock.nanoTime, clock.sleep)
        limiter.bytesPerSecond = 1_000_000
        assertTrue(limiter.enabled)
        limiter.acquire(4_096)
        assertTrue(
            clock.totalSleptMs < 100L,
            "运行时开启限速不应阻塞首次小读取（虚拟休眠累计 ${clock.totalSleptMs}ms）",
        )
    }

    @Test
    fun burst_absorbs_the_first_read_without_sleeping() {
        val clock = FakeClock()
        val limiter = RateLimiter(10L * 1024 * 1024, clock.nanoTime, clock.sleep)
        // 手动推进 0.25s 虚拟时间：令牌按 10MB/s 补到 burst 上限（约 2MB）
        clock.advanceMs(250)
        limiter.acquire(64L * 1024)
        assertEquals(0, clock.sleepCalls, "突发量内的首次读取不应触发任何休眠")
    }

    @Test
    fun inactive_download_aborts_waiting_promptly() {
        val clock = FakeClock()
        val limiter = RateLimiter(200_000, clock.nanoTime, clock.sleep)
        var checks = 0
        assertFailsWith<InterruptedIOException> {
            limiter.acquire(20_000_000) { ++checks < 3 }
        }
        assertTrue(checks >= 3, "等待循环里必须反复检查取消标记")
        assertTrue(
            clock.totalSleptMs < 200L,
            "取消应在首个分段休眠周期内生效（虚拟休眠累计 ${clock.totalSleptMs}ms）",
        )
    }

    @Test
    fun thread_interrupt_is_converted_to_interrupted_io_exception() {
        // 刻意不注入虚拟时钟：这里测的是真实 Thread.sleep 被打断后保留中断标志、
        // 并转成 InterruptedIOException 的 JVM 语义，替身无法替代。
        val limiter = RateLimiter(100_000)
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<InterruptedIOException> { limiter.acquire(10_000_000) }
            assertTrue(Thread.currentThread().isInterrupted, "中断标志必须保留给上层")
        } finally {
            Thread.interrupted() // 清标志，别污染同一 worker 的后续测试
        }
    }

    @Test
    fun throttled_stream_preserves_every_byte_across_read_and_skip() {
        val data = ByteArray(256) { it.toByte() }
        val stream = ThrottledInputStream(ByteArrayInputStream(data), RateLimiter(0))
        assertEquals(data[0].toInt() and 0xff, stream.read())
        assertEquals(10L, stream.skip(10))
        val rest = stream.readBytes()
        assertEquals(data.size - 11, rest.size)
        assertContentEquals(data.copyOfRange(11, data.size), rest)
    }

    @Test
    fun throttled_stream_honours_the_active_check() {
        val stream = ThrottledInputStream(
            ByteArrayInputStream(ByteArray(4096)),
            RateLimiter(100),
        ) { false }
        assertFailsWith<InterruptedIOException> { stream.read(ByteArray(4096)) }
    }
}

/**
 * P2-28：可测的虚拟时钟 + 休眠替身。
 *
 * - [nanoTime] 返回由测试控制的虚拟纳秒，时间推进完全确定；
 * - [sleep] 不真的阻塞线程，只累计调用次数并按休眠时长推进虚拟时间，
 *   使 refill() 像真实世界一样补充令牌，限速循环能确定性收敛；
 * - 若限速逻辑退化成死循环（例如 burst 被错误缓存为 0），[sleep] 会在
 *   MAX_SLEEP_CALLS 次后直接 check 失败，让测试快速失败而不是无限挂住。
 */
private class FakeClock {
    private var nowNanos = 0L

    var sleepCalls = 0
        private set
    var totalSleptMs = 0L
        private set

    val nanoTime: () -> Long = { nowNanos }

    val sleep: (Long) -> Unit = { ms ->
        check(++sleepCalls <= MAX_SLEEP_CALLS) {
            "限速循环疑似死循环：sleep 已调用 $sleepCalls 次"
        }
        totalSleptMs += ms
        nowNanos += ms * 1_000_000L
    }

    /** 手动推进虚拟时间，用来把令牌桶补到突发量上限 */
    fun advanceMs(ms: Long) {
        nowNanos += ms * 1_000_000L
    }

    companion object {
        private const val MAX_SLEEP_CALLS = 1_000
    }
}

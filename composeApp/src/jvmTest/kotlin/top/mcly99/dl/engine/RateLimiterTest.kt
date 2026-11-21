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
        val limiter = RateLimiter(0)
        assertFalse(limiter.enabled)
        limiter.acquire(10_000_000)
        limiter.acquire(-1)
    }

    @Test
    fun rate_can_be_enabled_at_runtime_without_deadlocking() {
        // 回归：burstSize 若按构造时的 0 缓存，开启限速后令牌上限会永远是 0
        val limiter = RateLimiter(0)
        limiter.bytesPerSecond = 1_000_000
        assertTrue(limiter.enabled)
        val start = System.nanoTime()
        limiter.acquire(4_096)
        assertTrue(System.nanoTime() - start < 500_000_000L, "运行时开启限速不应阻塞首次小读取")
    }

    @Test
    fun burst_absorbs_the_first_read_without_sleeping() {
        val limiter = RateLimiter(10L * 1024 * 1024)
        val start = System.nanoTime()
        limiter.acquire(64L * 1024)
        assertTrue(System.nanoTime() - start < 500_000_000L)
    }

    @Test
    fun inactive_download_aborts_waiting_promptly() {
        val limiter = RateLimiter(200_000)
        var checks = 0
        val start = System.nanoTime()
        assertFailsWith<InterruptedIOException> {
            limiter.acquire(20_000_000) { ++checks < 3 }
        }
        assertTrue(checks >= 3, "等待循环里必须反复检查取消标记")
        assertTrue(System.nanoTime() - start < 1_000_000_000L, "取消应在分段休眠中快速生效")
    }

    @Test
    fun thread_interrupt_is_converted_to_interrupted_io_exception() {
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

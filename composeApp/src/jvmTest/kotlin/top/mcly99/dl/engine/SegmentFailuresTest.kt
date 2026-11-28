package top.mcly99.dl.engine

import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 退避重试的判据（H 项）：哪些错误值得重试、等多久。 */
class SegmentFailuresTest {

    @Test
    fun the_first_retry_is_immediate() {
        assertEquals(0L, retryBackoffMs(1), "首次重试必须立即：断流多半发生在建连瞬间")
        assertEquals(500L, retryBackoffMs(2))
    }

    @Test
    fun backoff_grows_exponentially_and_stops_at_the_cap() {
        assertEquals(1_000L, retryBackoffMs(3))
        assertEquals(2_000L, retryBackoffMs(4))
        assertEquals(4_000L, retryBackoffMs(5))
        assertEquals(8_000L, retryBackoffMs(6))
        assertEquals(8_000L, retryBackoffMs(20), "封顶之后不能再涨，否则用户等成「卡死」")
    }

    @Test
    fun server_side_and_network_errors_are_retryable() {
        assertTrue(isRetryableSegmentFailure(SegmentHttpException(500, "boom")))
        assertTrue(isRetryableSegmentFailure(SegmentHttpException(503, "busy")))
        assertTrue(isRetryableSegmentFailure(SegmentHttpException(429, "slow down")))
        assertTrue(isRetryableSegmentFailure(SegmentHttpException(408, "timeout")))
        assertTrue(isRetryableSegmentFailure(SocketTimeoutException("read timed out")))
        assertTrue(isRetryableSegmentFailure(IOException("connection reset")))
    }

    @Test
    fun client_errors_and_protocol_mismatches_are_not_retryable() {
        assertFalse(isRetryableSegmentFailure(SegmentHttpException(403, "expired link")))
        assertFalse(isRetryableSegmentFailure(SegmentHttpException(404, "gone")))
        assertFalse(isRetryableSegmentFailure(SegmentHttpException(416, "bad range")))
        assertFalse(isRetryableSegmentFailure(SegmentHttpException(200, "range ignored")))
        assertFalse(isRetryableSegmentFailure(IllegalStateException("写入磁盘失败")))
        assertFalse(isRetryableSegmentFailure(ContentReplacedException("content changed")))
    }

    @Test
    fun only_403_429_503_are_read_as_concurrency_signals() {
        assertTrue(isConcurrencyStatus(403))
        assertTrue(isConcurrencyStatus(429))
        assertTrue(isConcurrencyStatus(503))
        assertFalse(isConcurrencyStatus(500), "500 是服务端出错，不是「你开太多了」")
        assertFalse(isConcurrencyStatus(404))
    }
}

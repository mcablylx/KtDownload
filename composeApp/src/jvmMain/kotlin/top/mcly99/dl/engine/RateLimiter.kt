package top.mcly99.dl.engine

import java.io.FilterInputStream
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * 令牌桶限速流。
 *
 * 包装原始 [InputStream]，在读取字节后按设定速率补充令牌，超出时让当前读阻塞，
 * 从而精确限制单个连接的平均带宽。多个分块线程各自持有一个实例，共享同一个
 * [RateLimiter] 即可实现「整任务 / 全局总带宽」限制。
 *
 * @param delegate 底层输入流
 * @param limiter 共享的令牌桶；为 null 表示不限速
 * @param isActive 取消检查：返回 false 时立即抛出 [InterruptedIOException]。
 *   阻塞中的 `Thread.sleep` 无法感知协程取消，因此必须在等待循环里主动检查。
 */
class ThrottledInputStream(
    delegate: InputStream,
    private val limiter: RateLimiter?,
    private val isActive: () -> Boolean = { true },
) : FilterInputStream(delegate) {

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) limiter?.acquire(1, isActive)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) limiter?.acquire(n.toLong(), isActive)
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) limiter?.acquire(skipped, isActive)
        return skipped
    }
}

/**
 * 简单令牌桶限速器（线程安全）。
 *
 * @param bytesPerSecond 允许的平均速率（字节/秒）；<= 0 表示不限速
 */
class RateLimiter(@Volatile var bytesPerSecond: Long) {

    private var tokens: Double = 0.0
    private var lastRefillAt: Long = System.nanoTime()
    private val lock = Any()

    val enabled: Boolean get() = bytesPerSecond > 0

    /**
     * 允许的突发量：约 0.2 秒的数据量，下限 64KB。
     *
     * 注意这里必须**动态计算**：限速可在运行时从 0 调整为正数（设置页滑块），
     * 若在构造时按 0 计算并缓存，令牌上限会永久为 0，导致所有读取永远阻塞。
     */
    private fun burstSize(rate: Long = bytesPerSecond): Long =
        if (rate > 0) (rate / 5).coerceAtLeast(64L * 1024) else 0L

    fun acquire(count: Long) = acquire(count) { true }

    /**
     * 申请 [count] 个字节的令牌；不足时分段阻塞等待。
     *
     * 每次最多休眠 [MAX_SLEEP_MS]，并进一步切成 [SLEEP_SLICE_MS] 的小片，
     * 每片之间检查 [isActive]，这样暂停/取消下载时不会因为低速率下的长休眠而卡住。
     *
     * v4-P3-9：这里不能用 `delay` / `runInterruptible`——[acquire] 由
     * [ThrottledInputStream.read] 调用，处在同步的 `InputStream.read()` 栈上，
     * 没有 suspend 上下文可用。因此改为「把阻塞切成可取消的小片」来降低取消延迟
     * （200ms → 50ms），而不是引入协程 API。
     */
    fun acquire(count: Long, isActive: () -> Boolean) {
        if (!enabled || count <= 0) return
        var remaining = count
        while (remaining > 0) {
            if (!isActive()) throw InterruptedIOException("下载已取消")
            val waitMs = synchronized(lock) {
                refill()
                val rate = bytesPerSecond
                if (rate <= 0) return   // 运行中被取消限速
                if (tokens >= remaining) {
                    tokens -= remaining
                    return
                }
                // 取走现有令牌，剩余部分换算成等待时间（分段，便于响应取消）
                val available = tokens.toLong().coerceAtMost(remaining)
                if (available > 0) {
                    tokens -= available
                    remaining -= available
                }
                if (remaining <= 0) return
                ((remaining.toDouble() / rate) * 1000.0)
                    .toLong()
                    .coerceIn(1L, MAX_SLEEP_MS)
            }
            var sleepLeft = waitMs
            while (sleepLeft > 0) {
                if (!isActive()) throw InterruptedIOException("下载已取消")
                val slice = sleepLeft.coerceAtMost(SLEEP_SLICE_MS)
                try {
                    Thread.sleep(slice)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("下载已取消")
                }
                sleepLeft -= slice
            }
        }
    }

    /** 按经过的时间补充令牌（调用方需持有 [lock]） */
    private fun refill() {
        val now = System.nanoTime()
        val elapsedSec = (now - lastRefillAt) / 1_000_000_000.0
        if (elapsedSec <= 0) return
        lastRefillAt = now
        val rate = bytesPerSecond
        if (rate <= 0) return
        val burst = burstSize(rate)
        tokens = (tokens + elapsedSec * rate).coerceIn(0.0, burst.toDouble())
    }

    private companion object {
        /** 单次休眠上限：兼顾限速精度与取消响应速度 */
        const val MAX_SLEEP_MS = 200L

        /** 单片休眠上限；越小取消响应越快，但循环开销越大 */
        const val SLEEP_SLICE_MS = 50L
    }
}

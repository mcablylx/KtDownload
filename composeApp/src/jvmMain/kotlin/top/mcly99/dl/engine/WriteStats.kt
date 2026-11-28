package top.mcly99.dl.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * 写盘耗时统计（F 项）。
 *
 * 为什么需要它：慢盘和慢网络在「速度掉下去」这件事上长得一模一样，但处置完全相反 ——
 * 网络慢要断开重连（[SlowConnectionDetector]），磁盘慢重连只会把好连接换掉。判据必须
 * 按**写耗时占比**来分，而这个占比不能靠猜，得实测。
 *
 * 三个产出口径：
 * - [ratioFor]：单条连接「等写盘 / 在跑」的比例，直接喂给慢连接判据做豁免；
 * - [stalledRecently]：最近 [stallHoldMs] 内出现过一次 ≥ [stallThresholdNanos] 的写，
 *   视为「磁盘是当前瓶颈」。NTFS 一次 flush 会挂住该文件的所有写，慢盘能到 3~10 s，
 *   这段时间里所有连接都慢，剔除任何一条都是误杀 —— 所以用「全局广播」而不是各算各的；
 * - [p95Nanos] / [longestNanos]：事后排查用，日志里能看出盘到底有多慢。
 *
 * 样本用定长环形数组：只要「最近 N 次」的量级判断，不需要全量历史，也不会无限吃内存。
 */
internal class WriteStats(
    /** 单次写超过这个耗时即认为「被盘卡了一下」：50 ms 已远高于正常页缓存写入 */
    private val stallThresholdNanos: Long = 50L * 1_000_000,
    /** 一次卡顿的影响窗口：这段时间内不做慢连接剔除 */
    private val stallHoldMs: Long = 2_000L,
    private val capacity: Int = 256,
    /** 可注入时钟：判据全是「多久算久」，没有它就没法确定性测试 */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val samples = LongArray(capacity)
    private val sampleLock = Any()
    private val submitted = AtomicLong(0L)
    private val longest = AtomicLong(0L)
    private val lastStallAt = AtomicLong(0L)

    /** 记一次写的耗时（纳秒） */
    fun record(nanos: Long) {
        val n = submitted.getAndIncrement()
        synchronized(sampleLock) { samples[(n % capacity).toInt()] = nanos }
        if (nanos >= stallThresholdNanos) lastStallAt.set(clock())
        longest.accumulateAndGet(nanos) { a, b -> maxOf(a, b) }
    }

    /** 最近是否有过卡顿（磁盘瓶颈广播） */
    fun stalledRecently(now: Long = clock()): Boolean {
        val at = lastStallAt.get()
        return at > 0L && now - at <= stallHoldMs
    }

    /**
     * 单条连接的写盘等待占比（0..1）。
     *
     * 用「写耗时 / 连接在跑的时间」而不是「写耗时 / 总耗时」：连接刚建起来时总耗时很小，
     * 一次正常写入就能顶出 100%，把刚起步的连接误判成磁盘瓶颈。
     */
    fun ratioFor(writeNanos: Long, elapsedMs: Long): Double {
        if (elapsedMs <= 0L) return 0.0
        return (writeNanos.toDouble() / (elapsedMs * 1_000_000.0)).coerceIn(0.0, 1.0)
    }

    /** 最近 [capacity] 次写的 95 分位耗时（纳秒）；样本不足时取已有样本 */
    fun p95Nanos(): Long {
        val taken = synchronized(sampleLock) {
            val seen = minOf(submitted.get(), capacity.toLong()).toInt()
            LongArray(seen) { samples[it] }
        }
        if (taken.isEmpty()) return 0L
        taken.sort()
        val index = ((taken.size - 1) * 95 / 100).coerceIn(0, taken.size - 1)
        return taken[index]
    }

    fun longestNanos(): Long = longest.get()

    fun reset() {
        synchronized(sampleLock) { samples.fill(0L) }
        submitted.set(0L)
        longest.set(0L)
        lastStallAt.set(0L)
    }
}

package top.mcly99.dl.engine

import top.mcly99.dl.utils.AppConstants
import java.util.concurrent.atomic.AtomicLongArray

/**
 * 单文件分块下载进度追踪器（线程安全）
 *
 * 设计目标：
 * - 64 个分块并发写入而无需加锁：每块用独立的 `AtomicLong` 槽位存放「该块已落盘字节数」
 * - 总进度 = 各块之和；被取消的块调用 [discard] 后从总和中剔除，进度不会超过 100%
 * - 速度使用固定窗口采样 + 滑动平均，避免 `(Δ字节 * 1000 / Δ毫秒)` 的整数抖动
 * - 任务结束调用 [close]，从注册表中移除，避免长期运行内存泄漏
 *
 * @param totalSize 文件总大小（字节）
 * @param chunkCount 分块数量
 */
class DownloadProgressTracker(val totalSize: Long, val chunkCount: Int) {
    private val chunks = AtomicLongArray(chunkCount.coerceAtLeast(1))
    private val active = BooleanArray(chunkCount.coerceAtLeast(1)) { true }
    private val activeLock = Any()

    /** 采样窗口：最多保留 [SPEED_WINDOW] 个 (时间戳毫秒, 累计字节) */
    private val sampleTimes = LongArray(SPEED_WINDOW)
    private val sampleBytes = LongArray(SPEED_WINDOW)
    private var sampleCount = 0
    private var sampleIndex = 0
    private var lastEmittedSpeed = 0L

    companion object {
        private const val SPEED_WINDOW = 5
        private const val SPEED_MIN_INTERVAL_MS = AppConstants.IO_POLL_MS
    }

    /** 单个分块已下载字节数 */
    fun chunkDone(index: Int): Long = chunks.get(index)

    /**
     * 记录某个分块的最新进度（绝对字节数，可重入/覆盖写）
     * @return 全部有效分块已下载字节之和
     */
    fun update(index: Int, downloadedInChunk: Long): Long {
        if (index !in 0 until chunks.length()) return totalDone()
        // totalSize <= 0 表示「大小未知」（服务器没给 Content-Length）。这时绝不能按
        // 0 做上限截断，否则累计值恒为 0 → 进度不动、速度恒 0 B/s（夸克 CDN 即此情形）。
        val value = if (totalSize > 0) {
            downloadedInChunk.coerceIn(0L, totalSize)
        } else {
            downloadedInChunk.coerceAtLeast(0L)
        }
        chunks.set(index, value)
        return totalDone()
    }

    /** 标记某块不再计入总进度（取消/失败的分块） */
    fun discard(index: Int) {
        if (index !in 0 until chunks.length()) return
        synchronized(activeLock) {
            if (index < active.size) active[index] = false
        }
        chunks.set(index, 0L)
    }

    /** 所有有效分块已下载字节之和 */
    fun totalDone(): Long {
        var sum = 0L
        val inactive = synchronized(activeLock) { active.copyOf() }
        for (i in 0 until chunks.length()) {
            if (i < inactive.size && !inactive[i]) continue
            sum += chunks.get(i)
        }
        // 大小未知（totalSize <= 0）时不做上限截断，否则 sum 会被压成 0
        return if (totalSize > 0) sum.coerceIn(0L, totalSize) else sum
    }

    /**
     * 采样并计算瞬时速度（字节/秒）
     * @param now 当前毫秒时间戳
     * @return 滑动窗口平均速度；采样不足时返回上一次的速度
     */
    @Synchronized
    fun sampleSpeed(now: Long = System.currentTimeMillis()): Long {
        val done = totalDone()
        if (sampleCount > 0) {
            val lastIdx = (sampleIndex - 1 + SPEED_WINDOW) % SPEED_WINDOW
            val dt = now - sampleTimes[lastIdx]
            if (dt < SPEED_MIN_INTERVAL_MS) return lastEmittedSpeed
        }
        sampleTimes[sampleIndex] = now
        sampleBytes[sampleIndex] = done
        sampleIndex = (sampleIndex + 1) % SPEED_WINDOW
        if (sampleCount < SPEED_WINDOW) sampleCount++

        if (sampleCount < 2) {
            lastEmittedSpeed = 0L
            return 0L
        }
        // 环形缓冲：sampleIndex 为下一个写入位置，窗口未满时最旧样本在 0
        val oldestIdx = if (sampleCount < SPEED_WINDOW) 0 else sampleIndex % SPEED_WINDOW
        val elapsed = now - sampleTimes[oldestIdx]
        val delta = done - sampleBytes[oldestIdx]
        lastEmittedSpeed = if (elapsed > 0 && delta > 0) (delta * 1000L) / elapsed else 0L
        return lastEmittedSpeed
    }
}

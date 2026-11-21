package top.mcly99.dl.engine

import top.mcly99.dl.utils.AppConstants

/**
 * 字节速率采样器：固定窗口 + 滑动平均，线程安全。
 *
 * 为什么不用 [DownloadProgressTracker]：那个类是按「分块槽位」建模的（`update(index, done)`），
 * HLS 只有一条「已下载总字节」的单调递增曲线，塞进去要假装有 chunk 反而更绕。
 * 这里保留同样的窗口算法，让两条链路的速度口径一致（都是「窗口内 Δ字节 / Δ时间」）。
 *
 * 窗口取 5 个采样点、最小采样间隔 [AppConstants.IO_POLL_MS]：
 * 分片是「下完一片、起下一片」，瞬时速率呈台阶状，窗口越大越平滑；但窗口太大时
 * 「卡住了」会看起来像「在跑」，5 个点约 1 秒，是这两者的折中（v6-P1-1）。
 *
 * @param window 采样窗口长度（采样点个数）
 * @param minIntervalMs 两次采样之间的最小间隔，小于它就复用上一次的速度
 */
class ByteRateSampler(
    private val window: Int = 5,
    private val minIntervalMs: Long = AppConstants.IO_POLL_MS,
) {
    private val times = LongArray(window.coerceAtLeast(2))
    private val bytes = LongArray(window.coerceAtLeast(2))
    private var count = 0
    private var index = 0
    private var lastSpeed = 0L

    /**
     * 采样一次并返回当前速率（字节/秒）。
     *
     * @param totalBytes 累计已下载字节数，必须单调不减
     * @param now 当前毫秒时间戳；测试可注入固定值
     */
    @Synchronized
    fun sample(totalBytes: Long, now: Long = System.currentTimeMillis()): Long {
        if (count > 0) {
            val lastIdx = (index - 1 + times.size) % times.size
            if (now - times[lastIdx] < minIntervalMs) return lastSpeed
        }
        times[index] = now
        bytes[index] = totalBytes
        index = (index + 1) % times.size
        if (count < times.size) count++

        if (count < 2) {
            lastSpeed = 0L
            return 0L
        }
        // 窗口未满时最旧样本在 0，满了就是下一个待写位置
        val oldest = if (count < times.size) 0 else index % times.size
        val elapsed = now - times[oldest]
        val delta = totalBytes - bytes[oldest]
        lastSpeed = if (elapsed > 0 && delta > 0) delta * 1000L / elapsed else 0L
        return lastSpeed
    }

    /** 复位（暂停/恢复时调用），避免把上一段的字节增量算进新窗口 */
    @Synchronized
    fun reset() {
        count = 0
        index = 0
        lastSpeed = 0L
        times.fill(0)
        bytes.fill(0)
    }
}

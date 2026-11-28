package top.mcly99.dl.engine

/**
 * 慢连接判据：一条连接明显跟不上时，断掉重连比让它慢慢拖完全程更划算。
 *
 * 为什么需要它：静态分片下，某一片所在的 TCP 路径变差（CDN 边缘节点抽风、被限速、
 * 走了一条坏的出口），整任务就得按那条路径的速度跑完剩余部分。用户看到的「下载到
 * 一半突然变成 200 KB/s」，多半就是这个。
 *
 * 判据三条件同时成立才剔除（对应 Mister DM `download.rs` 的 `too_slow`，此处独立实现）：
 * 1. 这条连接已经跑了 [graceMs] 以上 —— 刚建连时的首包延迟不算速度；
 * 2. 速度不到基准的四分之一；
 * 3. 按它自己的速度，剩余部分还要跑 [leftMs] 以上 —— 快下完了就不折腾。
 *
 * **基准不能取「别人的速度」**。8 条连接共享 1 Gbit/s 时每条只有 1/8，拿「第一条独占
 * 时的速度」当基准会把 8 条全部判慢，每几秒集体重连一次（对方 `#896` 是用户实测发现的）。
 * 所以基准取 `min(单连接历史最好, 链路总速率 ÷ 在收的连接数)`。
 *
 * **磁盘拖后腿时不判**。写盘慢会让读也停下，看起来和网络慢一模一样；此时剔除只是把
 * 好连接换掉（对方 `#782`）。用写耗时占比粗判，超过一半就认定瓶颈在磁盘。
 */
internal class SlowConnectionDetector(
    private val graceMs: Long = 2_000L,
    private val ratio: Int = 4,
    private val leftMs: Long = 2_000L,
    /** 基准低于此值说明整条链路本来就慢（或还在起步），没有可比的「快」 */
    private val minBaselineBytesPerSec: Long = 256L * 1024L,
    /** 写耗时占这个比例以上视为磁盘瓶颈，不参与慢连接判定 */
    private val diskBoundRatio: Double = 0.5,
    private val maxShedPerSegment: Int = SegmentTuning.MAX_SHED_PER_SEGMENT,
) {

    /** 一次判定需要的观测值 */
    data class Sample(
        /** 这条连接已持续的时间 */
        val elapsedMs: Long,
        /** 这条连接本轮收到的字节数 */
        val bytesReceived: Long,
        /** 它负责的段还剩多少字节 */
        val remainingBytes: Long,
        /** 整条任务当前速率（所有连接之和） */
        val linkBytesPerSec: Long,
        /** 当前正在收数据的连接数 */
        val activeConnections: Int,
        /** 单连接历史最好速度 */
        val bestConnectionBytesPerSec: Long,
        /** 本条连接读过的时间里，等写盘占的比例（0..1） */
        val writeWaitRatio: Double,
        /** 这个段已经被剔除过几次 */
        val shedCount: Int,
        /**
         * 最近一两秒内**任意**一条连接被磁盘卡住过（≥ 50 ms）。
         *
         * 单看自己的 writeWaitRatio 不够：8 条连接里只有一条正在写的时候它占比很高，
         * 其余 7 条看起来「网络慢」。慢盘是所有连接共享的瓶颈，必须用全局广播来豁免。
         */
        val diskRecentlyStalled: Boolean = false,
    )

    fun shouldShed(sample: Sample): Boolean {
        if (sample.elapsedMs < graceMs) return false
        if (sample.shedCount >= maxShedPerSegment) return false
        if (sample.diskRecentlyStalled) return false
        if (sample.writeWaitRatio >= diskBoundRatio) return false

        val rate = sample.bytesReceived * 1000L / sample.elapsedMs.coerceAtLeast(1L)
        val baseline = baselineOf(sample)
        if (baseline < minBaselineBytesPerSec) return false
        // rate * ratio < baseline 才判慢；写成乘法避免整数除法把 3.9 倍的四舍五入成 4 倍
        if (rate * ratio >= baseline) return false

        val remainingMs = sample.remainingBytes * 1000L / rate.coerceAtLeast(1L)
        return remainingMs > leftMs
    }

    /** 基准：单连接上限与「均分链路」取小，避免把共享带宽误判成慢 */
    internal fun baselineOf(sample: Sample): Long {
        val share = sample.linkBytesPerSec / sample.activeConnections.coerceAtLeast(1)
        return minOf(sample.bestConnectionBytesPerSec, share)
    }
}

/**
 * 连接被判为慢、主动断开重连时抛出的控制流异常。
 *
 * 它不是失败：段带着游标回到段表，下一个连接从断点继续写。
 * 用异常而不是返回值，是因为它要从 `read` 循环深处直接跳出来（外面还套着源轮转）。
 */
internal class SlowConnectionShed : Exception("连接速度明显低于基准，主动重建")

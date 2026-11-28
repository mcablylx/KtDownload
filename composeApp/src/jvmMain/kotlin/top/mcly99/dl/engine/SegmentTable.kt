package top.mcly99.dl.engine

import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

/**
 * 动态段表：把「静态等分、一片一个协程」换成「一个可被切分和认领的区间表」。
 *
 * 为什么值得换：静态等分下总耗时由**最慢的那一片**决定。某条 TCP 路径慢 2 倍，
 * 整任务就慢 2 倍，其余连接下完自己的份额后只能干等着。段表让先完成的连接去切
 * 「剩余最多」的那一段，把尾巴追平，总耗时从 `max(片)` 接近 `avg(片)`。
 *
 * 三条认领规则（与 Mister DM `planner.rs` 的判据同构，但完全独立实现）：
 * 1. **接尾**：优先认领与上一段首尾相连的段，少一次乱序写；
 * 2. **空闲段**：任何无主段，按起点顺序；
 * 3. **切分**：都在跑时，把剩余最多的段切一半出来。切点从**已落盘游标之后**
 *    再加一个安全余量起算，保证不会切进正在写的字节 —— 只抄「抢」不抄这个条件，
 *    等于把同一段发两次（对方 `planner.rs` 注释里明确警告过这一点）。
 *
 * 与旧模型的另一个差别：段数不再由线程数决定。线程数是**连接上限**，段数是**任务粒度**；
 * 中途改线程数不再让已有进度作废。
 */
internal object SegmentTuning {
    /** 单段下限：低于这个值再切就只有额外建连成本，没有并行收益 */
    const val MIN_SEGMENT_BYTES = 4L * 1024 * 1024

    /** 切点按 4 KiB 对齐，避免一次写跨两个页 */
    const val SPLIT_ALIGN_BYTES = 4L * 1024

    /** 切点必须领先对方游标至少这么多（两个读缓冲），否则对方手里那批数据会写进新段 */
    const val SPLIT_SAFE_MARGIN = 2L * AppConstants.CHUNK_BUFFER_SIZE

    /** 剩余不足此值就不切了：切完两边都太小 */
    const val MIN_SPLITTABLE_REMAINING = 8L * 1024 * 1024

    /** 同一段被剔除的次数上限；超过后不再判慢，避免「剔除 → 重连 → 又判慢」的死循环 */
    const val MAX_SHED_PER_SEGMENT = 3

    /** 段表快照的落盘间隔（暂停/取消时还会额外落一次） */
    const val PERSIST_INTERVAL_MS = 500L

    /** 慢连接判定的检查间隔；太密会把瞬时抖动当成掉速，太疏则剔除滞后 */
    const val SHED_CHECK_INTERVAL_MS = 500L
}

/**
 * 一个待下载区间（闭区间 `[start, end]`）及其游标。
 *
 * [cursor] 是「下一个要写的绝对偏移」，由持有它的 worker 独占推进；
 * [end] 会被别的 worker 调小（被切分），因此必须 volatile —— worker 每写一块都要重读。
 */
internal class DownloadSegment(
    val start: Long,
    @Volatile var end: Long,
    @Volatile var cursor: Long,
    @Volatile var owner: Int,
    @Volatile var shedCount: Int,
) {
    /** 还没下完的字节数 */
    val remaining: Long get() = (end - cursor + 1).coerceAtLeast(0L)

    val finished: Boolean get() = cursor > end

    fun copyIdle(): DownloadSegment = DownloadSegment(start, end, cursor, -1, shedCount)
}

/**
 * 初始段数：**线程数只是上限，体积才是决定因素**。
 *
 * 12 MiB 的文件在 64 线程设置下被切成 64 片 × 187 KiB 是纯粹的灾难：每片一次建连、
 * 一次落盘、一个合并项。按 4 MiB 一段算，12 MiB 只有 3 段。
 */
internal fun initialSegmentCount(
    totalSize: Long,
    requestedThreads: Int,
    minSegmentBytes: Long = SegmentTuning.MIN_SEGMENT_BYTES,
): Int {
    if (totalSize <= 0L) return 0
    val byThreads = requestedThreads.coerceIn(1, AppConstants.MAX_THREADS).toLong()
    val byVolume = (totalSize / minSegmentBytes.coerceAtLeast(1L)).coerceAtLeast(1L)
    val byBytes = ((totalSize + 1L) / 2L).coerceAtLeast(1L)
    return minOf(byThreads, byVolume, byBytes, totalSize).toInt().coerceAtLeast(1)
}

/** 把 `0..totalSize-1` 等分成 [count] 段，最后一段吃余数，保证恰好覆盖 */
internal fun buildSegments(totalSize: Long, count: Int): List<DownloadSegment> {
    if (totalSize <= 0L || count <= 0) return emptyList()
    val size = totalSize / count
    return List(count) { index ->
        val start = index.toLong() * size
        val end = if (index == count - 1) totalSize - 1L else (index + 1L) * size - 1L
        DownloadSegment(start, end, start, -1, 0)
    }
}

/**
 * 段表本体。结构性操作（认领 / 切分 / 释放）走同一把锁，字节推进走无锁累加。
 *
 * 锁内只做几次比较和偶尔一次列表插入，粒度远小于一次网络读，不构成瓶颈。
 */
internal class SegmentTable(
    val totalSize: Long,
    val fingerprint: ContentFingerprint,
    initial: List<DownloadSegment>,
    val resumed: Boolean,
) {
    private val lock = Any()
    private val pending = ArrayList(initial)

    /** 已完成字节：断点续传时预置为「总长 − 未完成字节」，之后由 worker 累加 */
    private val done = AtomicLong(
        (totalSize - initial.sumOf { it.remaining }).coerceIn(0L, totalSize.coerceAtLeast(0L)),
    )

    /** 当前还没下完的段数（用于决定开多少连接） */
    val pendingCount: Int get() = synchronized(lock) { pending.size }

    fun totalDone(): Long = done.get().coerceIn(0L, totalSize)

    fun addBytes(n: Long) {
        if (n > 0) done.addAndGet(n)
    }

    /**
     * 认领一段活；没有可做的返回 null（此 worker 退出）。
     *
     * @param lastEnd 该 worker 上一段的终点，用于「接尾」优化；首次传 -1
     */
    fun claim(workerId: Int, lastEnd: Long): DownloadSegment? = synchronized(lock) {
        if (pending.isEmpty()) return null

        // 1. 接尾：与自己刚下完的那段相邻，顺序写，少一次寻道
        pending.firstOrNull { it.owner < 0 && it.start == lastEnd + 1 }?.let {
            it.owner = workerId
            return it
        }
        // 2. 任何空闲段（含之前被剔除/放弃的）
        pending.firstOrNull { it.owner < 0 }?.let {
            it.owner = workerId
            return it
        }
        // 3. 都在跑：切剩余最多的一段
        splitLargest(workerId)
    }

    /** 切分最大的在跑段；切点落后于对方游标 + 安全余量，绝不切进正在写的字节 */
    private fun splitLargest(workerId: Int): DownloadSegment? {
        val candidate = pending
            .filter { it.remaining >= SegmentTuning.MIN_SPLITTABLE_REMAINING }
            .maxByOrNull { it.remaining }
            ?: return null

        val rawCut = candidate.cursor + SegmentTuning.SPLIT_SAFE_MARGIN
        val cut = alignUp(rawCut, SegmentTuning.SPLIT_ALIGN_BYTES)
        // 切完新段至少要有一个 MIN_SEGMENT_BYTES，否则收益抵不过一次建连
        if (cut > candidate.end - SegmentTuning.MIN_SEGMENT_BYTES + 1L) return null

        val tail = DownloadSegment(cut, candidate.end, cut, workerId, 0)
        candidate.end = cut - 1L
        pending.add(tail)
        return tail
    }

    /** 段下完（`cursor > end`）后从表里摘掉 */
    fun release(segment: DownloadSegment) {
        synchronized(lock) { pending.remove(segment) }
    }

    /** 放弃一段（连接被剔除、或本轮失败），游标保留，之后可被重新认领 */
    fun abandon(segment: DownloadSegment) {
        synchronized(lock) {
            segment.owner = -1
            // 只有还在表里的段才需要；已 release 的段留着 owner 也无妨
            if (segment !in pending) pending.add(segment)
        }
    }

    /** 记录一次剔除，用于给同一段设重试上限 */
    fun noteShed(segment: DownloadSegment) {
        segment.shedCount += 1
    }

    /** 供落盘用的快照：只含未完成段，游标即恢复起点 */
    fun snapshot(): List<DownloadSegment> = synchronized(lock) {
        pending.map { it.copyIdle() }
    }

    /** 全部下完 */
    fun isComplete(): Boolean = synchronized(lock) { pending.isEmpty() }

    private fun alignUp(value: Long, alignment: Long): Long =
        ((value + alignment - 1L) / alignment) * alignment
}

/**
 * 段表快照：替代旧模型的 `.partN` + `.partN.offset` + `.chunks.plan` 三件套。
 *
 * 格式（纯文本，便于人工排查）：
 * ```
 * ktdl-segments/1
 * <totalSize>|<etag~lastModified>
 * <start>-<end>-<cursor>
 * ```
 *
 * 校验不通过（版本/长度/指纹/区间自洽性）一律返回 null，由调用方连临时文件一起丢掉重下。
 * 宁可多下一次，也不交付一份拼错的字节 —— 后者不会报错，只会打开是坏文件。
 */
internal class SegmentPlanStore(val file: File) {

    companion object {
        private const val FORMAT_HEADER = "ktdl-segments/1"
    }

    fun load(totalSize: Long, fingerprint: ContentFingerprint): List<DownloadSegment>? {
        if (!file.isFile) return null
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        if (lines.size < 2 || lines[0].trim() != FORMAT_HEADER) return null

        val meta = lines[1].split('|')
        if (meta.size != 2 || meta[0].toLongOrNull() != totalSize) return null
        val stored = ContentFingerprint.parseSignature(meta[1]) ?: return null
        // 指纹变了 = 远端换成另一份内容，旧游标全部作废
        if (contentReplacedBy(stored, fingerprint)) return null

        // 空列表是合法状态：所有段都下完了但还没来得及改名。坏行则是整份不可信。
        val rawSegments = lines.drop(2).filter { it.isNotBlank() }
        val segments = rawSegments.mapNotNull { parseSegment(it, totalSize) }
        if (segments.size != rawSegments.size) return null
        if (!isSelfConsistent(segments, totalSize)) return null
        return segments
    }

    fun save(table: SegmentTable) {
        val snapshot = table.snapshot()
        val text = buildString {
            append(FORMAT_HEADER).append('\n')
            append(table.totalSize).append('|').append(table.fingerprint.signature()).append('\n')
            snapshot.forEach {
                append(it.start).append('-').append(it.end).append('-').append(it.cursor).append('\n')
            }
        }
        // 先写临时文件再原子替换：崩溃时不会留下半截快照被当成有效进度
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(text)
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure {
            AppLogger.warn("SegmentPlanStore", "段表落盘失败: ${file.name}", it)
        }
    }

    fun delete() {
        runCatching { file.delete() }
        runCatching { File(file.parentFile, "${file.name}.tmp").delete() }
    }

    private fun parseSegment(line: String, totalSize: Long): DownloadSegment? {
        val parts = line.trim().split('-')
        if (parts.size != 3) return null
        val start = parts[0].toLongOrNull() ?: return null
        val end = parts[1].toLongOrNull() ?: return null
        val cursor = parts[2].toLongOrNull() ?: return null
        if (start < 0L || end < start || end >= totalSize) return null
        if (cursor < start || cursor > end + 1L) return null
        return DownloadSegment(start, end, cursor, -1, 0)
    }

    private fun isSelfConsistent(segments: List<DownloadSegment>, totalSize: Long): Boolean {
        val sorted = segments.sortedBy { it.start }
        var previousEnd = -1L
        for (segment in sorted) {
            if (segment.start <= previousEnd) return false
            if (segment.end >= totalSize) return false
            previousEnd = segment.end
        }
        return true
    }
}

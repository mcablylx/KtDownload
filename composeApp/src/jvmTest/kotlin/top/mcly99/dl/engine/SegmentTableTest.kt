package top.mcly99.dl.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 段表本身的回归：切分不越界、认领不重叠、并发下字节不重不漏。
 *
 * 这些性质坏掉的后果不是「变慢」，而是**静默错位**：两个 worker 写同一段、
 * 或者某段被跳过而总字节数恰好凑够，文件长度对、内容错。所以断言都盯着区间与字节数。
 */
class SegmentTableTest {

    private val mib = 1024L * 1024L

    @Test
    fun initial_segments_cover_the_file_without_gaps() {
        val total = 100L * mib
        val segments = buildSegments(total, 7)

        assertEquals(0L, segments.first().start)
        assertEquals(total - 1L, segments.last().end)
        segments.zipWithNext().forEach { (a, b) ->
            assertEquals(a.end + 1L, b.start, "段之间必须首尾相接")
        }
        assertTrue(segments.all { it.cursor == it.start }, "新段的游标必须从起点开始")
    }

    @Test
    fun segment_count_is_capped_by_size_not_by_thread_count() {
        // 12 MiB 在 64 线程设置下切成 64 片 × 187 KiB 是碎片化灾难：每片一次建连 + 一次落盘
        assertEquals(3, initialSegmentCount(12L * mib, 64))
        // 小到不该分段的量：至少 1 段，由调用方决定是否走单线程
        assertEquals(1, initialSegmentCount(1L * mib, 64))
        // 大文件仍然按线程数封顶
        assertEquals(8, initialSegmentCount(10L * 1024 * mib, 8))
        assertEquals(0, initialSegmentCount(0, 8))
    }

    @Test
    fun a_finished_segment_is_handed_to_the_worker_that_just_finished_it() {
        val table = newTable(64L * mib, 4)
        val first = table.claim(0, -1L)!!
        first.cursor = first.end + 1L
        table.release(first)

        // 起点与「刚下完那段」相接的那一段应当被优先拿走，少一次乱序写
        val next = table.claim(0, first.end)!!
        assertEquals(first.end + 1L, next.start)
    }

    @Test
    fun splitting_never_touches_bytes_that_are_already_written() {
        val table = newTable(100L * mib, 2)
        val held = table.claim(0, -1L)!!
        // 假装这条连接已经写了 20 MiB
        held.cursor = held.start + 20L * mib

        // 另一个 worker 没有活，去切最大的段
        val stolen = table.claim(1, -1L)

        assertNotNull(stolen, "剩余 80 MiB 应当可以切一半出来")
        assertTrue(
            stolen.start >= held.cursor + SegmentTuning.SPLIT_SAFE_MARGIN,
            "切点必须落在对方游标 + 安全余量之后，否则会切进正在写的字节：" +
                "cut=${stolen.start} cursor=${held.cursor}",
        )
        assertEquals(stolen.start - 1L, held.end, "原段的终点要同步收窄")
        assertEquals(0L, stolen.start % SegmentTuning.SPLIT_ALIGN_BYTES, "切点要按 4 KiB 对齐")
    }

    @Test
    fun a_segment_too_small_to_split_is_left_alone() {
        // 6 MiB 连 MIN_SPLITTABLE_REMAINING（8 MiB）都不到，切了只会多一次建连
        val table = newTable(6L * mib, 1)
        table.claim(0, -1L)

        assertNull(table.claim(1, -1L), "剩余不足两条 MIN_SEGMENT 时不应再切")
    }

    @Test
    fun an_abandoned_segment_can_be_claimed_again_from_its_cursor() {
        val table = newTable(64L * mib, 2)
        val segment = table.claim(0, -1L)!!
        segment.cursor = segment.start + 8L * mib
        table.noteShed(segment)
        table.abandon(segment)

        val retaken = table.claim(1, -1L)
        assertNotNull(retaken)
        assertEquals(segment.start + 8L * mib, retaken.cursor, "重新认领必须从断点续，而不是从头")
        assertEquals(1, retaken.shedCount)
    }

    @Test
    fun concurrent_workers_cover_every_byte_exactly_once() {
        // 真并发：8 个 worker 抢 4 个初始段，过程中会切分、会接尾。
        // 断言「总字节恰好等于文件长度且不重复计数」—— 重复写不改变字节数，所以同时记录区间。
        val total = 96L * mib
        val table = newTable(total, 4)
        val written = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())

        runBlocking {
            coroutineScope {
                repeat(8) { workerId ->
                    launch(Dispatchers.Default) {
                        var lastEnd = -1L
                        while (true) {
                            val segment = table.claim(workerId, lastEnd) ?: break
                            val from = segment.cursor
                            val to = segment.end
                            if (to >= from) {
                                val n = to - from + 1
                                segment.cursor = to + 1
                                table.addBytes(n)
                                written.add(from to to)
                            }
                            table.release(segment)
                            lastEnd = segment.end
                        }
                    }
                }
            }
        }

        assertTrue(table.isComplete(), "所有段都应被认领并完成")
        assertEquals(total, table.totalDone(), "累加的字节数必须恰好等于文件长度")
        val sorted = written.sortedBy { it.first }
        var previous = -1L
        for ((from, to) in sorted) {
            assertTrue(from > previous, "区间重叠说明两个 worker 写了同一批字节: $from..$to")
            previous = to
        }
        assertEquals(total - 1L, previous, "最后一个字节必须被覆盖")
    }

    // ---------------------------------------------------------------- 快照

    private lateinit var dir: File
    private lateinit var store: SegmentPlanStore

    @BeforeTest
    fun setUpStore() {
        dir = Files.createTempDirectory("ktdown-segment-plan").toFile()
        store = SegmentPlanStore(File(dir, "movie.bin.segments"))
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun snapshot_round_trips_pending_segments_with_their_cursors() {
        val table = newTable(64L * mib, 4, ContentFingerprint(etag = "\"v1\""))
        val first = table.claim(0, -1L)!!
        first.cursor = first.start + 3L * mib
        table.release(first)

        store.save(table)
        val loaded = store.load(64L * mib, ContentFingerprint(etag = "\"v1\""))

        assertNotNull(loaded)
        assertEquals(table.pendingCount, loaded.size)
        assertEquals(
            table.snapshot().sumOf { it.remaining },
            loaded.sumOf { it.remaining },
            "恢复后的未完成字节必须与快照一致",
        )
    }

    @Test
    fun a_length_change_invalidates_the_snapshot() {
        val table = newTable(64L * mib, 4)
        store.save(table)

        assertNull(store.load(128L * mib, ContentFingerprint.UNKNOWN), "长度变了就不能续")
    }

    @Test
    fun a_new_etag_invalidates_the_snapshot() {
        // 同名覆盖上传：大小一模一样，只有指纹能证明换了文件
        val table = newTable(64L * mib, 4, ContentFingerprint(etag = "\"v1\""))
        store.save(table)

        assertNull(store.load(64L * mib, ContentFingerprint(etag = "\"v2\"")))
    }

    @Test
    fun a_missing_fingerprint_is_not_treated_as_a_new_file() {
        val table = newTable(64L * mib, 4)
        store.save(table)

        assertNotNull(
            store.load(64L * mib, ContentFingerprint(etag = "\"v1\"")),
            "从「没有指纹」到「有指纹」不是内容变化，不能因此丢进度",
        )
    }

    @Test
    fun a_corrupted_snapshot_is_rejected_instead_of_half_trusted() {
        store.save(newTable(64L * mib, 4))
        store.file.appendText("garbage-line\n")

        assertNull(store.load(64L * mib, ContentFingerprint.UNKNOWN))
    }

    @Test
    fun an_unknown_format_version_is_rejected() {
        store.file.writeText("ktdl-segments/2\n${64L * mib}|~\n0-1023-0\n")

        assertNull(store.load(64L * mib, ContentFingerprint.UNKNOWN))
    }

    @Test
    fun an_empty_snapshot_means_everything_finished() {
        // 所有段下完但还没来得及改名就被杀死：快照里没有段行，恢复时应当直接收尾
        val total = 64L * mib
        store.file.writeText("ktdl-segments/1\n$total|~\n")

        val loaded = store.load(total, ContentFingerprint.UNKNOWN)
        assertNotNull(loaded)
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun overlapping_segments_are_rejected() {
        val total = 64L * mib
        store.file.writeText(
            "ktdl-segments/1\n$total|~\n0-${32 * mib - 1}-0\n${16 * mib}-${63 * mib}-${16 * mib}\n",
        )

        assertNull(store.load(total, ContentFingerprint.UNKNOWN), "重叠区间会让两个连接写同一批字节")
    }

    private fun newTable(
        totalSize: Long,
        count: Int,
        fingerprint: ContentFingerprint = ContentFingerprint.UNKNOWN,
    ) = SegmentTable(totalSize, fingerprint, buildSegments(totalSize, count), resumed = false)

    @Test
    fun deleting_the_snapshot_leaves_nothing_behind() {
        store.save(newTable(64L * mib, 2))
        assertTrue(store.file.exists())

        store.delete()

        assertFalse(store.file.exists())
        assertFalse(File(dir, "movie.bin.segments.tmp").exists())
    }
}

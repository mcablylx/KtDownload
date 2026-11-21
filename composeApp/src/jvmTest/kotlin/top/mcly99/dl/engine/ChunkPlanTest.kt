package top.mcly99.dl.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 计划漂移保护：用户改线程数 / 远端长度变了 / 老版本没有清单时，
 * 必须丢掉旧 `.partN`，否则合并出来的是一份静默错位的文件。
 */
class ChunkPlanTest {

    private lateinit var dir: File
    private lateinit var target: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ktdown-plan-test").toFile()
        target = dir.resolve("movie.bin")
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** 造一批「旧计划」落盘分片，内容故意全是同一个字节，方便断言是否被删掉 */
    private fun seedParts(count: Int) {
        for (i in 0 until count) {
            DownloadEngine.partFile(target, i).writeBytes(ByteArray(64) { 0xAA.toByte() })
            DownloadEngine.offsetFile(target, i).writeText("64")
        }
    }

    @Test
    fun same_plan_keeps_resumable_parts() {
        seedParts(4)
        chunkPlanFile(target).writeText(chunkPlanSignature(4096L, 4, 4))
        assertTrue(reconcileChunkPlan(target, 4096L, 4, 4))
        assertContentEquals(ByteArray(64) { 0xAA.toByte() }, DownloadEngine.partFile(target, 0).readBytes())
    }

    @Test
    fun changed_thread_count_discards_all_parts() {
        seedParts(8)
        chunkPlanFile(target).writeText(chunkPlanSignature(4096L, 8, 8))
        assertFalse(reconcileChunkPlan(target, 4096L, 4, 4))
        for (i in 0 until 8) {
            assertFalse(DownloadEngine.partFile(target, i).exists())
            assertFalse(DownloadEngine.offsetFile(target, i).exists())
        }
        assertTrue(chunkPlanFile(target).readText().contains("|4|4"))
    }

    @Test
    fun changed_remote_length_discards_all_parts() {
        seedParts(4)
        chunkPlanFile(target).writeText(chunkPlanSignature(4096L, 4, 4))
        assertFalse(reconcileChunkPlan(target, 8192L, 4, 4))
        assertFalse(DownloadEngine.partFile(target, 0).exists())
    }

    @Test
    fun legacy_parts_without_a_plan_are_never_trusted() {
        seedParts(4)
        assertFalse(chunkPlanFile(target).exists())
        assertFalse(reconcileChunkPlan(target, 4096L, 4, 4))
        assertFalse(DownloadEngine.partFile(target, 0).exists())
    }

    @Test
    fun discard_cleans_parts_beyond_the_current_chunk_count() {
        seedParts(12)
        File(dir, "${target.name}.merging").writeText("junk")
        discardChunkParts(target)
        for (i in 0 until 12) {
            assertFalse(DownloadEngine.partFile(target, i).exists())
            assertFalse(DownloadEngine.offsetFile(target, i).exists())
        }
        assertFalse(File(dir, "${target.name}.merging").exists())
        assertFalse(chunkPlanFile(target).exists())
    }

    @Test
    fun same_plan_but_a_different_etag_discards_parts() {
        // P0-2：总长、分片数、线程数全都一样，只是远端换了文件（同名覆盖上传）。
        // 前三段签名完全一致，旧实现会把这些分片当成自己的直接合并 —— 静默坏文件。
        seedParts(4)
        chunkPlanFile(target).writeText(
            chunkPlanSignature(4096L, 4, 4, ContentFingerprint(etag = "\"v1\"")),
        )

        val kept = reconcileChunkPlan(target, 4096L, 4, 4, ContentFingerprint(etag = "\"v2\""))

        assertFalse(kept, "指纹变了必须丢弃分片")
        assertFalse(DownloadEngine.partFile(target, 0).exists())
        assertTrue(chunkPlanFile(target).readText().contains("v2"), "清单要换成新指纹，否则下次续传又会丢一遍")
    }

    @Test
    fun plan_without_a_fingerprint_is_upgraded_not_discarded() {
        // 上一轮服务器没给 ETag（写成了空指纹），这一轮给了：没有可比的东西，
        // 不能凭「新出现了指纹」就丢掉用户已经下好的部分，但要把指纹补进清单
        seedParts(4)
        chunkPlanFile(target).writeText(chunkPlanSignature(4096L, 4, 4))

        val kept = reconcileChunkPlan(target, 4096L, 4, 4, ContentFingerprint(etag = "\"v1\""))

        assertTrue(kept, "指纹从无到有不算内容变化")
        assertTrue(DownloadEngine.partFile(target, 0).exists())
        assertTrue(chunkPlanFile(target).readText().contains("v1"), "新指纹要落进清单，下次才验得出来")
    }

    @Test
    fun legacy_three_field_plan_is_not_trusted() {
        // 改动前的版本只写了 `总长|分片数|线程数`：没有任何身份信息，无法验明分片属于哪个文件。
        // 升级后第一次续传会从头下 —— 一次性的代价，换掉「永远不知道自己拼的是不是同一份」。
        seedParts(4)
        chunkPlanFile(target).writeText("4096|4|4")

        assertFalse(reconcileChunkPlan(target, 4096L, 4, 4))
        assertFalse(DownloadEngine.partFile(target, 0).exists())
    }
}

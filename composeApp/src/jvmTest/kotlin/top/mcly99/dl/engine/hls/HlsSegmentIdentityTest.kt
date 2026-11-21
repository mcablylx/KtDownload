package top.mcly99.dl.engine.hls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [HlsSegmentIdentity] 的纯逻辑测试。
 *
 * 核心命题只有一句：**只有「同一份播放列表里的同一个分片」才能复用**。
 * 地址、时长、字节区间、密钥任何一项变了都必须判成不同的分片。
 */
class HlsSegmentIdentityTest {

    private fun segment(
        uri: String = "https://cdn.example.com/vod/42/seg-1.ts?sign=abc&expire=1",
        duration: Double = 4.0,
        sequence: Int = 1,
        byteRange: LongRange? = null,
        key: HlsKey? = null,
    ) = HlsSegment(uri = uri, duration = duration, sequence = sequence, byteRange = byteRange, key = key)

    // ---------------------------------------------------------------- 稳定地址

    @Test
    fun stable_uri_drops_the_time_limited_signature() {
        assertEquals(
            "https://cdn.example.com/vod/42/seg-1.ts",
            HlsSegmentIdentity.stableUri("https://cdn.example.com/vod/42/seg-1.ts?sign=abc&expire=1700000000"),
        )
    }

    @Test
    fun stable_uri_drops_fragment_and_surrounding_spaces() {
        assertEquals(
            "https://x/a.ts",
            HlsSegmentIdentity.stableUri("  https://x/a.ts?sig=1#t=10 "),
        )
    }

    @Test
    fun stable_uri_keeps_unescaped_characters_it_cannot_parse() {
        // 播放列表里常见未转义的中文与空格：解析失败也不能把整条地址吞掉
        assertEquals("https://x/视频 第一集/seg 1.ts", HlsSegmentIdentity.stableUri("https://x/视频 第一集/seg 1.ts?sign=z"))
    }

    @Test
    fun re_signing_the_same_segment_does_not_change_its_identity() {
        val first = segment(uri = "https://cdn/a/seg-1.ts?sign=v1")
        val resign = segment(uri = "https://cdn/a/seg-1.ts?sign=v2")

        // 这就是「网盘直链重新解析后继续下载」的常态：签名换了，内容没换
        assertEquals(HlsSegmentIdentity.signature(first), HlsSegmentIdentity.signature(resign))
    }

    // ---------------------------------------------------------------- 内容特征

    @Test
    fun changing_the_path_changes_the_identity() {
        assertNotEquals(
            HlsSegmentIdentity.signature(segment(uri = "https://cdn/a/seg-1.ts")),
            HlsSegmentIdentity.signature(segment(uri = "https://cdn/a/seg-1b.ts")),
        )
    }

    @Test
    fun changing_the_duration_changes_the_identity() {
        assertNotEquals(
            HlsSegmentIdentity.signature(segment(duration = 4.0)),
            HlsSegmentIdentity.signature(segment(duration = 6.0)),
        )
    }

    @Test
    fun changing_the_byte_range_changes_the_identity() {
        assertNotEquals(
            HlsSegmentIdentity.signature(segment(byteRange = 0L..99L)),
            HlsSegmentIdentity.signature(segment(byteRange = 100L..199L)),
        )
    }

    @Test
    fun a_byte_range_differs_from_the_whole_file() {
        assertNotEquals(
            HlsSegmentIdentity.signature(segment(byteRange = null)),
            HlsSegmentIdentity.signature(segment(byteRange = 0L..99L)),
        )
    }

    @Test
    fun changing_the_key_changes_the_identity() {
        val aes = HlsKey(method = "AES-128", uri = "https://cdn/a/key.bin")
        val otherKey = HlsKey(method = "AES-128", uri = "https://cdn/a/key2.bin")
        assertNotEquals(
            HlsSegmentIdentity.signature(segment(key = aes)),
            HlsSegmentIdentity.signature(segment(key = otherKey)),
        )
    }

    @Test
    fun changing_only_the_iv_changes_the_identity() {
        // 密钥地址相同、IV 不同 → 同一段密文解出来的明文完全不同
        val iv1 = HlsKey(method = "AES-128", uri = "https://cdn/a/key.bin", iv = "00000000000000000000000000000001")
        val iv2 = HlsKey(method = "AES-128", uri = "https://cdn/a/key.bin", iv = "00000000000000000000000000000002")
        assertNotEquals(
            HlsSegmentIdentity.signature(segment(key = iv1)),
            HlsSegmentIdentity.signature(segment(key = iv2)),
        )
    }

    @Test
    fun encrypted_and_plain_segments_never_share_an_identity() {
        assertNotEquals(
            HlsSegmentIdentity.signature(segment(key = null)),
            HlsSegmentIdentity.signature(segment(key = HlsKey(method = "AES-128", uri = "https://cdn/a/key.bin"))),
        )
    }

    @Test
    fun a_none_key_is_treated_as_plain_text() {
        // #EXT-X-KEY:METHOD=NONE 是「从这里开始不加密」，与完全没有 KEY 行等价
        assertEquals(
            HlsSegmentIdentity.signature(segment(key = null)),
            HlsSegmentIdentity.signature(segment(key = HlsKey(method = "NONE"))),
        )
    }

    @Test
    fun the_signature_never_contains_a_line_break() {
        val signature = HlsSegmentIdentity.signature(segment())
        assertFalse(signature.contains('\n'), signature)
        assertFalse(signature.contains('\r'), signature)
    }

    // ---------------------------------------------------------------- .done 标记

    @Test
    fun a_fresh_marker_matches_its_own_segment() {
        val target = segment()
        val content = HlsSegmentIdentity.doneContent(target, length = 4096L)

        assertTrue(HlsSegmentIdentity.matches(content, target, onDiskLength = 4096L))
    }

    @Test
    fun the_marker_records_the_version() {
        val content = HlsSegmentIdentity.doneContent(segment(), length = 1L)
        assertEquals(HlsSegmentIdentity.VERSION, content.lines().first())
    }

    @Test
    fun an_empty_marker_from_the_previous_version_is_not_trusted() {
        // 改动前写的是空文件：没有任何身份信息，只能重下
        assertFalse(HlsSegmentIdentity.matches("", segment(), onDiskLength = 4096L))
        assertFalse(HlsSegmentIdentity.matches(null, segment(), onDiskLength = 4096L))
        assertFalse(HlsSegmentIdentity.matches("   \n", segment(), onDiskLength = 4096L))
    }

    @Test
    fun a_truncated_segment_on_disk_is_not_trusted() {
        val target = segment()
        val content = HlsSegmentIdentity.doneContent(target, length = 4096L)

        // 文件被外部截断 / 另一个程序改写：标记说的长度与磁盘上的不符
        assertFalse(HlsSegmentIdentity.matches(content, target, onDiskLength = 1024L))
    }

    @Test
    fun an_empty_file_is_not_trusted_even_with_a_matching_marker() {
        val target = segment()
        val content = HlsSegmentIdentity.doneContent(target, length = 0L)

        assertFalse(HlsSegmentIdentity.matches(content, target, onDiskLength = 0L))
    }

    @Test
    fun a_marker_from_another_segment_is_not_trusted() {
        val original = segment(uri = "https://cdn/a/seg-1.ts")
        val content = HlsSegmentIdentity.doneContent(original, length = 4096L)
        val replaced = segment(uri = "https://cdn/a/seg-1-new.ts")

        assertFalse(HlsSegmentIdentity.matches(content, replaced, onDiskLength = 4096L))
    }

    @Test
    fun an_unknown_version_is_not_trusted() {
        val target = segment()
        val content = HlsSegmentIdentity.doneContent(target, length = 4096L)
        val future = content.replaceFirst(HlsSegmentIdentity.VERSION, "v9")

        assertFalse(HlsSegmentIdentity.matches(future, target, onDiskLength = 4096L))
    }

    @Test
    fun a_marker_missing_fields_is_not_trusted() {
        assertFalse(HlsSegmentIdentity.matches("v1\nhttps://cdn/a/seg-1.ts\n", segment(), onDiskLength = 4096L))
        assertFalse(
            HlsSegmentIdentity.matches(
                "v1\nhttps://cdn/a/seg-1.ts\nnot-a-number\n",
                segment(),
                onDiskLength = 4096L,
            ),
        )
    }
}

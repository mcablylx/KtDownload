package top.mcly99.dl.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 内容指纹的判定逻辑（P0-2）。
 *
 * 这里的每一条断言都对应一种「判错了会怎样」：判晚一步是静默坏文件，判早一步是白丢几十 GB。
 * 所以两个方向都要钉住 —— 能确认变了才说变了，确认不了就说没变。
 */
class ContentFingerprintTest {

    @Test
    fun signature_survives_delimiters_inside_the_etag() {
        // ETag 是带引号的字符串，里面出现 `|`（清单分隔符）和 `%`（转义符）完全合法。
        // 不转义的话清单会被切成五段，解析失败 → 每次续传都丢弃分片，续传形同虚设。
        val original = ContentFingerprint(
            etag = "\"a|b%7Cc~d\"",
            lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
        )

        val parsed = ContentFingerprint.parseSignature(original.signature())

        assertEquals(original, parsed)
        // 签名本身只有一个分隔符，不额外多出段
        assertEquals(1, original.signature().count { it == '~' })
    }

    @Test
    fun signature_of_unknown_fingerprint_is_stable() {
        assertEquals(ContentFingerprint.UNKNOWN, ContentFingerprint.parseSignature(ContentFingerprint.UNKNOWN.signature()))
        assertNull(ContentFingerprint.parseSignature("not-a-signature"), "段数不对必须返回 null，让调用方丢弃分片")
    }

    @Test
    fun weak_etags_never_go_into_if_range() {
        // RFC 7233 §3.2：If-Range 要求强比较，弱校验符会被服务端直接忽略（等于没带）
        val weakOnly = ContentFingerprint(etag = "W/\"abc\"")
        assertNull(weakOnly.ifRangeValue, "只有弱 ETag 时不能发 If-Range")

        val weakWithDate = ContentFingerprint(etag = "W/\"abc\"", lastModified = "Wed, 21 Oct 2015 07:28:00 GMT")
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", weakWithDate.ifRangeValue, "弱 ETag 应退到 Last-Modified")

        assertEquals("\"abc\"", ContentFingerprint(etag = "\"abc\"").ifRangeValue)
    }

    @Test
    fun same_length_different_etag_counts_as_a_replacement() {
        // 这是最危险的一种：长度一样，进度对得上，本地校验全过 —— 只有 ETag 能看出换了文件
        val previous = ContentFingerprint(etag = "\"v1\"", lastModified = "Wed, 21 Oct 2015 07:28:00 GMT")
        val current = ContentFingerprint(etag = "\"v2\"", lastModified = "Wed, 21 Oct 2015 07:28:00 GMT")

        assertTrue(contentReplacedBy(previous, current), "ETag 变了就是换了内容，Last-Modified 没变也不能放行")
    }

    @Test
    fun last_modified_breaks_the_tie_when_no_etag_is_available() {
        val previous = ContentFingerprint(lastModified = "Wed, 21 Oct 2015 07:28:00 GMT")
        val current = ContentFingerprint(lastModified = "Thu, 22 Oct 2015 09:00:00 GMT")

        assertTrue(contentReplacedBy(previous, current))
        assertFalse(contentReplacedBy(previous, ContentFingerprint(lastModified = "Wed, 21 Oct 2015 07:28:00 GMT")))
    }

    @Test
    fun unknown_fingerprints_never_report_a_change() {
        // 判不了就说没变：宁可维持旧行为，也不要把「不知道」当成「变了」去丢掉已下好的数据
        val known = ContentFingerprint(etag = "\"v1\"")

        assertFalse(contentReplacedBy(null, known))
        assertFalse(contentReplacedBy(known, ContentFingerprint.UNKNOWN))
        assertFalse(contentReplacedBy(ContentFingerprint.UNKNOWN, known))
        assertFalse(contentReplacedBy(known, ContentFingerprint(etag = "")))
        // 一边只有 ETag、另一边只有 Last-Modified：没有共同字段可比
        assertFalse(contentReplacedBy(known, ContentFingerprint(lastModified = "Thu, 22 Oct 2015 09:00:00 GMT")))
    }

    @Test
    fun switching_between_weak_and_strong_form_is_not_a_change() {
        // 服务端只是改了校验强度（W/"x" ↔ "x"），内容没变；判成「变了」会让每次续传都从头开始
        assertFalse(contentReplacedBy(ContentFingerprint(etag = "W/\"x\""), ContentFingerprint(etag = "\"x\"")))
        assertFalse(contentReplacedBy(ContentFingerprint(etag = "\"x\""), ContentFingerprint(etag = "W/\"x\"")))
    }

    @Test
    fun content_range_start_is_parsed_or_reported_as_unknown() {
        assertEquals(0L, parseContentRangeStart("bytes 0-0/1024"))
        assertEquals(524_288L, parseContentRangeStart("bytes 524288-1048575/1048576"))
        // 大小未知时服务端回 `bytes 100-199/*`，起点仍然可读
        assertEquals(100L, parseContentRangeStart("bytes 100-199/*"))
        // 不认识的一律 null：宁可放行，也不把看不懂的响应判成错误
        assertNull(parseContentRangeStart(null))
        assertNull(parseContentRangeStart("bytes */1024"))
        assertNull(parseContentRangeStart("items 1-2/3"))
        assertNull(parseContentRangeStart(""))
    }
}

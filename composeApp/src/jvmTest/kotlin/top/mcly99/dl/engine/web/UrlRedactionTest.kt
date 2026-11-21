package top.mcly99.dl.engine.web

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [redactUrl] / [hostOf] 的单测（v8-P0-2）。
 *
 * 这两个函数唯一的职责就是「别把凭证写进日志」，所以用例也围着这一点：
 * query、fragment、解析失败三条路都不能漏出原文。
 */
class UrlRedactionTest {

    @Test
    fun query_and_fragment_are_dropped() {
        val signed = "https://cdn.example.com/media/index.m3u8?wsSecret=deadbeef&wsTime=1793094493&ip=1.2.3.4"

        assertEquals("https://cdn.example.com/media/index.m3u8?<redacted>", redactUrl(signed))
        assertEquals(
            "https://example.com/watch?<redacted>",
            redactUrl("https://example.com/watch#t=120"),
        )
    }

    @Test
    fun url_without_query_is_kept_whole() {
        assertEquals("https://cdn.example.com/a.mp4", redactUrl("https://cdn.example.com/a.mp4"))
    }

    @Test
    fun host_of_returns_only_the_host() {
        assertEquals("cdn.example.com", hostOf("https://cdn.example.com/a.mp4?token=x"))
        assertEquals("127.0.0.1", hostOf("http://127.0.0.1:8080/page"))
    }

    @Test
    fun unparsable_url_never_leaks_the_original_text() {
        // 解析失败时返回占位符，而不是把整条（可能带凭证的）地址回退进日志
        assertEquals("-", hostOf("not a url"))
        assertEquals("-", hostOf(""))
    }
}

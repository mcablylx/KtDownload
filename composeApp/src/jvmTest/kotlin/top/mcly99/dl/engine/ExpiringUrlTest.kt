package top.mcly99.dl.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 失败文案的判定逻辑（v7-P2-4）。
 *
 * 这条链路上没有统一的异常类型，只能靠 message 里的状态码判断；
 * 这里钉住的是「什么时候该换一句更准确的话」，判错的代价只是文案不够贴切，不会误伤流程。
 */
class ExpiringUrlTest {

    // ---------------------------------------------------------------- 像不像时效地址

    @Test
    fun signed_cdn_url_is_recognized() {
        assertTrue(
            looksLikeExpiringUrl(
                "https://cdn.example.com/v/index.m3u8?wsSecret=abc&wsTime=1793094493&ip=1.2.3.4",
            ),
        )
    }

    @Test
    fun common_signature_parameters_are_recognized() {
        listOf(
            "https://cdn/x.m3u8?expires=1793094493",
            "https://cdn/x.m3u8?Expire=1793094493",
            "https://cdn/x.m3u8?auth_key=abc-1793094493-0-def",
            "https://cdn/x.m3u8?token=abc",
            "https://cdn/x.m3u8?sign=abc",
        ).forEach { assertTrue(looksLikeExpiringUrl(it), "应识别为时效地址：$it") }
    }

    @Test
    fun plain_url_is_not_signed() {
        assertFalse(looksLikeExpiringUrl("https://cdn.example.com/v/index.m3u8"))
        assertFalse(looksLikeExpiringUrl("https://cdn.example.com/a.mp4?quality=hd"))
    }

    @Test
    fun matching_is_case_insensitive() {
        assertTrue(looksLikeExpiringUrl("https://cdn/x.m3u8?WSTIME=1"))
    }

    // ---------------------------------------------------------------- 算不算身份失败

    @Test
    fun auth_status_codes_are_detected() {
        assertTrue(isAuthFailure("HTTP 401"))
        assertTrue(isAuthFailure("HTTP 403 Forbidden"))
        assertTrue(isAuthFailure("HTTP 410"))
        assertFalse(isAuthFailure("HTTP 404"))
        assertFalse(isAuthFailure("Connection reset"))
        assertFalse(isAuthFailure(null))
    }

    // ---------------------------------------------------------------- 文案

    @Test
    fun expired_signature_with_403_asks_user_to_recapture() {
        val text = describeDownloadFailure(
            url = "https://cdn/x.m3u8?wsSecret=abc&wsTime=1",
            rawMessage = "HTTP 403",
        )
        assertTrue(text.contains("重新抓取"), "应引导回「抓网页」重新抓取，实际：$text")
        // 原始错误必须保留：用户拿它去搜、去反馈都用得上
        assertTrue(text.contains("HTTP 403"))
    }

    @Test
    fun plain_403_keeps_raw_message() {
        // 没有签名参数时「地址过期」这个猜测站不住脚，不要误导用户
        val text = describeDownloadFailure("https://cdn/a.mp4", "HTTP 403")
        assertEquals("HTTP 403", text)
    }

    @Test
    fun signed_url_with_network_error_keeps_raw_message() {
        // 断网也会失败，但和签名无关，不能一律甩「重新抓取」
        val text = describeDownloadFailure("https://cdn/x.m3u8?wsTime=1", "Connection timed out")
        assertEquals("Connection timed out", text)
    }

    @Test
    fun blank_message_falls_back_to_generic_text() {
        assertEquals("下载失败", describeDownloadFailure("https://cdn/a.mp4", null))
        assertEquals("下载失败", describeDownloadFailure("https://cdn/a.mp4", "   "))
    }
}

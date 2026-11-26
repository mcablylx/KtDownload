package top.mcly99.dl.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P1-07：日志落盘前的共享脱敏器回归。
 *
 * 1.0.3 只覆盖 snake_case 请求体，camelCase 键名、响应体、异常消息、业务日志里的
 * 完整签名 URL 都原样进 `app-*.log`，而诊断包会把这些日志一起收走。
 */
class SensitiveTextRedactorTest {

    @Test
    fun camel_case_and_snake_case_secret_keys_are_all_redacted() {
        val input = """{"accessToken":"AT-123","captcha_token":"CT-456",""" +
            """"refreshToken":"RT-789","clientSecret":"SEC","fileName":"movie.mp4"}"""
        val out = SensitiveTextRedactor.redact(input)
        listOf("AT-123", "CT-456", "RT-789", "SEC").forEach {
            assertFalse(out.contains(it), "camelCase/下划线敏感字段必须脱敏: $it")
        }
        assertTrue(out.contains("movie.mp4"), "普通字段不能误伤")
    }

    @Test
    fun full_signed_urls_lose_their_query_and_fragment() {
        val text = "GET https://cdn.example.com/v/file.mp4?wsSecret=abc&wsTime=123#frag failed"
        val out = SensitiveTextRedactor.redact(text)
        assertFalse(out.contains("wsSecret"))
        assertFalse(out.contains("wsTime"))
        assertFalse(out.contains("frag"))
        assertTrue(out.contains("https://cdn.example.com/v/file.mp4?<redacted>"))
    }

    @Test
    fun redaction_is_idempotent() {
        val once = SensitiveTextRedactor.redact(
            """{"password":"hunter2"} https://x.com/a?token=secret""",
        )
        assertEquals(once, SensitiveTextRedactor.redact(once))
    }

    @Test
    fun form_encoded_secrets_are_redacted_without_gluing_params() {
        val out = SensitiveTextRedactor.redact("user=alice&accessToken=AT&redirect=/home")
        assertEquals("user=alice&accessToken=***&redirect=/home", out)
    }

    @Test
    fun normal_json_is_left_alone() {
        val input = """{"name":"movie.mp4","size":123,"isdir":0}"""
        assertEquals(input, SensitiveTextRedactor.redact(input))
    }

    @Test
    fun redact_url_keeps_scheme_host_and_path() {
        assertEquals("https://a.b/c", SensitiveTextRedactor.redactUrl("https://a.b/c"))
        assertEquals("https://a.b/c?<redacted>", SensitiveTextRedactor.redactUrl("https://a.b/c?x=1"))
        assertEquals("https://a.b/c?<redacted>", SensitiveTextRedactor.redactUrl("https://a.b/c#frag"))
    }
}

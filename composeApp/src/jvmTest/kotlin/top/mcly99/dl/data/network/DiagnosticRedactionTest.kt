package top.mcly99.dl.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 诊断日志请求体脱敏回归测试（P1-4）。
 *
 * 登录 body 的明文密码 / refresh_token 曾被无条件写进诊断日志。这里验证
 * JSON 与 form 两种形态的敏感字段都被替换为 ***，普通字段保持不变。
 */
class DiagnosticRedactionTest {

    private val interceptor = DiagnosticNetworkInterceptor()

    @Test
    fun json_password_and_token_are_redacted() {
        val input = """{"username":"alice","password":"hunter2","access_token":"abc","refresh_token":"xyz"}"""
        val out = interceptor.redactSensitiveBody(input)

        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("abc"))
        assertFalse(out.contains("xyz"))
        // 普通字段保留原值
        assertEquals("""{"username":"alice","password":"***","access_token":"***","refresh_token":"***"}""", out)
    }

    @Test
    fun form_fields_redacted_without_gluing_params() {
        val input = "user=alice&password=s3cr3t&redirect=/home"
        val out = interceptor.redactSensitiveBody(input)

        assertEquals("user=alice&password=***&redirect=/home", out)
        assertFalse(out.contains("s3cr3t"))
    }

    @Test
    fun non_sensitive_fields_untouched() {
        val input = """{"name":"movie.mp4","size":123,"isdir":0}"""
        assertEquals(input, interceptor.redactSensitiveBody(input))
    }

    @Test
    fun case_insensitive_keys_and_numeric_values() {
        val out = interceptor.redactSensitiveBody("""{"PassWord":"x","token":12345}""")
        assertFalse(out.contains("x\""))
        assertFalse(out.contains("12345"))
    }

    @Test
    fun camel_case_keys_are_redacted_too() {
        // 走查 P1-07：GuangYaApi 的 accessToken、Xunlei 的 captchaToken 以前因精确枚举漏掉
        val out = interceptor.redactSensitiveBody(
            """{"accessToken":"AT-1","captchaToken":"CT-2","fileName":"demo.mp4"}""",
        )
        assertFalse(out.contains("AT-1"))
        assertFalse(out.contains("CT-2"))
        assertEquals(true, out.contains("demo.mp4"))
    }

    @Test
    fun response_bodies_and_signed_urls_are_redacted() {
        // 错误响应会原样回显请求参数；签名 URL 的 query 也属于临时凭证
        val out = interceptor.redactSensitiveBody(
            """{"error":"captcha_invalid","url":"https://pan.xunlei.com/api?token=SECRET&t=1"}""",
        )
        assertFalse(out.contains("SECRET"))
        assertFalse(out.contains("t=1"))
        assertTrue(out.contains("?<redacted>"))
    }
}

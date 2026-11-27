package top.mcly99.dl.data.network

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2-06 / P2-07 的契约测试：
 * - HTTP 200 + 业务错误码（31001 登录失效 / 频控）必须判失败，旧 `&&` 写法会整条放行；
 * - 失败响应缺 `status` 字段时按 200 处理，不能把正常响应误判死；
 * - 非 2xx 的 HTML 错误页要压成一行截断后再进异常消息。
 */
class ApiStatusTest {

    @Test
    fun http_200_with_business_code_is_a_failure() {
        val json = JSONObject().put("status", 200).put("code", 31001)
        assertTrue(json.hasBusinessError(), "HTTP 200 + code=31001 必须判失败；旧 && 写法在这里会放行")
    }

    @Test
    fun missing_status_field_defaults_to_200() {
        // 失败响应常见形态：只有 code / message，没有 status
        assertFalse(JSONObject().put("code", 0).hasBusinessError())
        assertTrue(JSONObject().put("code", 14001).hasBusinessError())
    }

    @Test
    fun explicit_non_200_status_is_a_failure() {
        assertTrue(JSONObject().put("status", 500).put("code", 0).hasBusinessError())
    }

    @Test
    fun normal_success_is_not_a_failure() {
        assertFalse(JSONObject().put("status", 200).put("code", 0).hasBusinessError())
    }

    @Test
    fun body_snippet_flattens_whitespace_and_truncates() {
        val html = "<html>\n  <body>403 Forbidden</body>\n</html>"
        val snippet = bodySnippet(html, limit = 20)
        assertFalse(snippet.contains("\n"), "异常消息里不能带换行，否则日志会断行")
        assertTrue(snippet.endsWith("…"), "超长错误页必须截断")
    }

    @Test
    fun blank_body_yields_empty_snippet() {
        assertEquals("", bodySnippet("  \n\t "))
    }
}

package top.mcly99.dl.engine.web.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [MiniJson] 单测。
 *
 * 它只服务 CDP 消息，但 CDP 的消息形状很杂（嵌套对象、数组、`\u` 转义、负数、null），
 * 解析器写错的表现是「某条事件静默丢失」，比崩溃更难查 —— 所以边界都要有用例。
 */
class MiniJsonTest {

    @Test
    fun parses_nested_object_and_scalar_types() {
        val parsed = MiniJson.parse(
            """{"id":3,"method":"Network.responseReceived","params":{"response":{"status":200,"mimeType":"application/vnd.apple.mpegurl","fromDiskCache":false,"note":null}}}"""
        ).asObject()!!
        assertEquals(3, parsed.int("id"))
        assertEquals("Network.responseReceived", parsed.str("method"))
        val response = parsed.obj("params")!!.obj("response")!!
        assertEquals(200, response.int("status"))
        assertEquals("application/vnd.apple.mpegurl", response.str("mimeType"))
        assertEquals(false, response["fromDiskCache"])
        assertNull(response["note"])
    }

    @Test
    fun parses_array_of_objects() {
        val parsed = MiniJson.parse("""[{"type":"page","webSocketDebuggerUrl":"ws://x"},{"type":"iframe"}]""")
            .asArray()!!
        assertEquals(2, parsed.size)
        assertEquals("ws://x", parsed[0].asObject()!!.str("webSocketDebuggerUrl"))
    }

    @Test
    fun decodes_escapes_including_unicode() {
        // 地址里的 `&` 在 CDP 里可能是 \u0026，转义没解对就会把 URL 抓错
        val parsed = MiniJson.parse("""{"url":"https://a.com/x.m3u8?a=1\u0026b=2","q":"say \"hi\"\n"}""").asObject()!!
        assertEquals("https://a.com/x.m3u8?a=1&b=2", parsed.str("url"))
        assertEquals("say \"hi\"\n", parsed.str("q"))
    }

    @Test
    fun stringify_escapes_and_round_trips() {
        val text = MiniJson.stringify(mapOf("method" to "Page.navigate", "params" to mapOf("url" to "a\"b\\c\n")))
        val back = MiniJson.parse(text).asObject()!!
        assertEquals("Page.navigate", back.str("method"))
        assertEquals("a\"b\\c\n", back.obj("params")!!.str("url"))
    }

    @Test
    fun stringify_writes_integers_without_decimal_point() {
        // CDP 的 id 必须是整数：写成 1.0 会被当成非法参数
        assertEquals("""{"id":1}""", MiniJson.stringify(mapOf("id" to 1)))
        assertEquals("""{"id":2}""", MiniJson.stringify(mapOf("id" to 2.0)))
    }

    @Test
    fun rejects_malformed_input() {
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("{") }
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("""{"a":}""") }
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("""{"a":1} extra""") }
    }

    @Test
    fun blank_or_non_object_payload_is_reported_as_null_object() {
        assertTrue(MiniJson.parse("[]").asObject() == null)
        assertNull(MiniJson.parse("null").asObject())
    }
}

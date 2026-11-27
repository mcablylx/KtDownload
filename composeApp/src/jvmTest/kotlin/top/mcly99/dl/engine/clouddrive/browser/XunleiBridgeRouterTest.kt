package top.mcly99.dl.engine.clouddrive.browser

import org.json.JSONObject
import top.mcly99.dl.data.network.XunleiWebCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P2-15：内存桥必须真的替代明文 Cookie 中转，且不把别的页面的字符串收进来。 */
class XunleiBridgeRouterTest {

    private val access = "eyJhbGciOiJSUzI1NiJ9.eyJleHAiOjE3OTQ1NzQ0NzR9.QUJDREVGMTIzNDU2"
    private val refresh = "eyJyZWZyZXNoIjp0cnVlfQ.RUZYUkVTSEE3ODkwMTIzNDU2Nzg5"
    private val trusted = "https://pan.xunlei.com/"
    private val payload = """{"access_token":"$access","refresh_token":"$refresh","device_id":"dev"}"""

    @Test
    fun message_bridge_uses_cef_query_instead_of_cookie_writes() {
        val script = XunleiWebCredential.MESSAGE_BRIDGE_SCRIPT
        assertTrue(script.contains("window.${XunleiWebCredential.BRIDGE_QUERY_FUNCTION}("), script)
        assertFalse(script.contains("document.cookie="), "不得再写 document.cookie（明文 refresh_token）")
        assertTrue(script.contains("location.hostname"), "脚本自身也要有域名白名单")
        assertTrue(script.contains("JSON.stringify"), "载荷应是 JSON 原文，不再 URL 编码分片")
    }

    @Test
    fun untrusted_origins_and_non_json_payloads_are_rejected() {
        assertTrue(xunleiBridgeRequestAllowed(trusted, payload))
        assertFalse(xunleiBridgeRequestAllowed("http://pan.xunlei.com/", payload))
        assertFalse(xunleiBridgeRequestAllowed("https://evil.example.com/", payload))
        assertFalse(xunleiBridgeRequestAllowed("https://xunlei.com.evil.example/", payload))
        assertFalse(xunleiBridgeRequestAllowed(trusted, "just-a-string"))
        assertFalse(xunleiBridgeRequestAllowed(trusted, "x".repeat(XunleiBridgeHandler.MAX_BRIDGE_REQUEST_CHARS + 1)))
    }

    @Test
    fun handler_consumes_each_payload_only_once() {
        val handler = XunleiBridgeHandler()
        assertNull(handler.consume())
        assertTrue(handler.acceptRequest(trusted, payload))
        assertEquals(payload, handler.consume())
        assertNull(handler.consume(), "同一份凭据不能被轮询反复读")
    }

    @Test
    fun credential_collector_prefers_the_in_memory_payload() {
        val handler = XunleiBridgeHandler()
        assertTrue(handler.acceptRequest(trusted, payload))

        val result = collectXunleiCredential(handler)
        assertNotNull(result)
        val json = JSONObject(result)
        assertEquals(access, json.getString("accessToken"))
        assertEquals(refresh, json.getString("refreshToken"))
        assertEquals("dev", json.getString("deviceId"))
        assertNull(handler.consume(), "读回后必须清空内存载荷")
    }

    @Test
    fun legacy_cookie_script_is_kept_only_for_compatibility_not_injected_by_default() {
        val screen = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/ui/components/CloudDriveLoginScreen.kt")
        assertTrue(screen.contains("MESSAGE_BRIDGE_SCRIPT"), "登录窗应注入内存桥脚本")
        assertFalse(screen.contains("XunleiWebCredential.COOKIE_BRIDGE_SCRIPT"), "不能再注入明文 Cookie 桥")
        assertTrue(screen.contains("addMessageRouter"), "必须把 CefMessageRouter 挂到 client")
        assertTrue(screen.contains("collectXunleiCredential(browser.bridgeHandler)"), "轮询应优先读内存桥")
    }

    private fun sourceOf(relative: String): String =
        listOf(java.io.File(relative), java.io.File("../$relative"), java.io.File("../../$relative"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("找不到源码 $relative（工作目录 ${System.getProperty("user.dir")}）")
}

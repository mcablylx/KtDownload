package top.mcly99.dl.engine.clouddrive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.net.URLEncoder

/**
 * 迅雷凭证收敛的回归测试。
 *
 * 背景（2026-11-13）：网页登录成功后点「解析」却报「迅雷登录已过期，请重新登录」。
 * 原因是登录页存下来的是整串 Cookie（`deviceid=…; yunx_xl_cred_0=…`），
 * 而解析层要的是 `Authorization: Bearer <access_token>`：
 * `jwtExp()` 从 Cookie 里解析不出 `exp`（返回 0）→ 被判成已过期 → 刷新又没得刷 → 报错。
 */
class XunleiDriveCredentialTest {

    private val access = "eyJhbGciOiJSUzI1NiJ9.eyJleHAiOjE3OTQ1NzQ0NzR9.QUJDREVGMTIzNDU2"
    private val refresh = "eyJyZWZyZXNoIjp0cnVlfQ.RUZYUkVTSEE3ODkwMTIzNDU2Nzg5"

    /** 网页桥接脚本写进 Cookie 的 localStorage 原文（URL 编码后按 3000 字符分片） */
    private fun bridgeCookies(payload: String, deviceId: String, chunkSize: Int = 3000): String {
        val encoded = URLEncoder.encode(payload, "UTF-8")
        val chunks = encoded.chunked(chunkSize)
        return buildString {
            append("deviceid=$deviceId; xl_fp_rt=abc; XLA_CI=xyz; ")
            chunks.forEachIndexed { i, part -> append("yunx_xl_cred_$i=$part; ") }
            append("yunx_xl_cred_n=${chunks.size}")
        }
    }

    @Test
    fun parses_bridge_cookie_string_into_token_and_refresh() {
        val payload = """{"access_token":"$access","refresh_token":"$refresh","device_id":"dev-from-payload"}"""
        val cred = XunleiDriveCredential.parse(bridgeCookies(payload, "cookie-device"))

        assertNotNull(cred, "Cookie 串里的分片必须能拼回结构化凭证")
        assertEquals(access, cred.accessToken)
        assertEquals(refresh, cred.refreshToken)
        assertEquals("dev-from-payload", cred.deviceId)
        assertTrue(cred.isUsable)
    }

    /** payload 里没带 device_id 时，退回 Cookie 里的 deviceid（去掉 `前缀.` 复合形态） */
    @Test
    fun falls_back_to_deviceid_cookie_when_payload_has_none() {
        val payload = """{"access_token":"$access","refresh_token":"$refresh"}"""
        val cred = XunleiDriveCredential.parse(
            bridgeCookies(payload, "1a2b3c4d5e6f77889900112233445566778899aabbccddeeff0.0123456789abcdef0123456789abcdef"),
        )

        assertEquals("0123456789abcdef0123456789abcdef", cred?.deviceId)
    }

    /** 多片：真实 token 往往超过单条 Cookie 4KB 上限，必须按 n 片序号拼回去 */
    @Test
    fun joins_multiple_cookie_chunks() {
        val longToken = "A".repeat(2600)
        val payload = """{"access_token":"$longToken","refresh_token":"$refresh"}"""
        val cookie = bridgeCookies(payload, "dev", chunkSize = 1000)
        val cred = XunleiDriveCredential.parse(cookie)

        assertTrue(cookie.contains("yunx_xl_cred_2="), "构造出来的样例必须真的分了好几片")
        assertEquals(longToken, cred?.accessToken)
    }

    @Test
    fun round_trips_own_json_format() {
        val original = XunleiDriveCredential(access, refresh, "dev-1", "cap-1", "user-1")
        val parsed = XunleiDriveCredential.parse(original.toJson())

        assertEquals(original, parsed)
    }

    /** 手动粘贴 localStorage 原文（snake_case 键名） */
    @Test
    fun parses_raw_local_storage_json() {
        val captcha = "C".repeat(32)
        val cred = XunleiDriveCredential.parse(
            """{"access_token":"$access","refresh_token":"$refresh","device_id":"dev-9","captcha_token":"$captcha"}""",
        )

        assertEquals(access, cred?.accessToken)
        assertEquals("dev-9", cred?.deviceId)
        assertEquals(captcha, cred?.captchaToken)
    }

    /** 手动粘贴裸 token：没有 refresh_token，过期只能重新登录 */
    @Test
    fun parses_bare_access_token() {
        val cred = XunleiDriveCredential.parse("Bearer $access")

        assertEquals(access, cred?.accessToken)
        assertEquals("", cred?.refreshToken)
    }

    /** 只有普通 Cookie、没有 yunx_xl_cred 分片 → 判失败，交给登录页提示重新登录 */
    @Test
    fun rejects_cookie_without_bridge_chunks() {
        assertNull(XunleiDriveCredential.parse("deviceid=abc; xl_fp_rt=xyz; sessionid=1"))
    }

    @Test
    fun rejects_blank_input() {
        assertNull(XunleiDriveCredential.parse("   "))
    }

    /** 卡片预览只露 token 前缀，不再把整串 Cookie/JSON 掩码成 `{"a••••"}` */
    @Test
    fun preview_shows_token_prefix() {
        val cred = XunleiDriveCredential(access, refresh, "dev")
        assertEquals("token eyJhbGci…", cred.preview())
    }
}

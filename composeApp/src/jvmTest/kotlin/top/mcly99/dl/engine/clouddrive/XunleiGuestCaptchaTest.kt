package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.XunleiApi
import top.mcly99.dl.data.network.XunleiWebCredential
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 迅雷游客解析回归测试。
 *
 * 2026-11 起迅雷匿名分享接口强制校验 `X-Captcha-Token`，且要求 `X-Client-Id`
 * 与 token 的 client_id 一致；否则一律返回 `captcha_invalid / 验证码无效`。
 * 这里用 OkHttp 拦截器伪造服务端，不发真实网络请求，验证游客 token 自签、请求头与重签重试。
 */
class XunleiGuestCaptchaTest {

    @Test
    fun guest_share_request_carries_signed_captcha_token_and_client_id() = runBlocking {
        val fake = FakeXunleiServer(shareBodies = mutableListOf(SHARE_OK))
        val api = XunleiApi(clientProvider = { fake.client })

        val result = api.getShare("VOe-GACxUECwLkT4RI0c6EWkA1", "rvf3", "", "dev-1", "")

        assertEquals("pct", result?.passCodeToken)
        val share = fake.requests.last { it.url.encodedPath.contains("/drive/v1/share") }
        assertEquals("token-1", share.header("X-Captcha-Token"))
        assertEquals(XunleiWebCredential.CLIENT_ID, share.header("X-Client-Id"))
        // 游客态绝不能带失效的 Bearer，否则服务端判 unauthenticated
        assertNull(share.header("Authorization"))
        assertTrue(fake.requests.any { it.url.encodedPath.endsWith("captcha/init") })
    }

    @Test
    fun captcha_invalid_triggers_one_resign_and_retry() = runBlocking {
        val fake = FakeXunleiServer(
            shareBodies = mutableListOf(
                """{"error":"captcha_invalid","error_description":"验证码无效"}""",
                SHARE_OK,
            )
        )
        val api = XunleiApi(clientProvider = { fake.client })

        val result = api.getShare("VOe-GACxUECwLkT4RI0c6EWkA1", "rvf3", "", "dev-1", "")

        assertEquals("pct", result?.passCodeToken)
        val shares = fake.requests.filter { it.url.encodedPath.contains("/drive/v1/share") }
        assertEquals(2, shares.size)
        assertEquals("token-1", shares[0].header("X-Captcha-Token"))
        assertEquals("token-2", shares[1].header("X-Captcha-Token"))
    }

    @Test
    fun web_captcha_sign_is_stable_and_device_bound() {
        // 基准值由独立实现复算；盐表/版本一旦被改动，这里会立刻失败
        assertEquals(
            "1.ab3d43c6b4a2e1116e67c88c5014fdf6",
            XunleiWebCredential.computeCaptchaSign("dev-1", 1_700_000_000_000L),
        )
        assertEquals(
            "1.ad8c9dc09d7ea593ea01cbc0387934a6",
            XunleiWebCredential.computeCaptchaSign("dev-2", 1_700_000_000_000L),
        )
    }

    // ---------------------------------------------------------------- 假服务端

    private companion object {
        const val SHARE_OK =
            """{"share_status":"OK","title":"demo","pass_code_token":"pct","files":[],"next_page_token":""}"""
    }

    private class FakeXunleiServer(
        private val shareBodies: MutableList<String>,
    ) {
        val requests = mutableListOf<Request>()
        private val tokens = ArrayDeque(listOf("token-1", "token-2", "token-3"))

        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                requests += req
                val isCaptchaInit = req.url.encodedPath.endsWith("captcha/init")
                val body = if (isCaptchaInit) {
                    """{"captcha_token":"${tokens.removeFirstOrNull() ?: "token-x"}","expires_in":300}"""
                } else {
                    if (shareBodies.isEmpty()) SHARE_OK else shareBodies.removeAt(0)
                }
                val code = if (body.contains("captcha_invalid")) 400 else 200
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Bad Request")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }
}

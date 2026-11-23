package top.mcly99.dl.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 迅雷多账号 token 隔离回归测试（P0-2）。
 *
 * 旧实现里 panRequest 用实例级 currentAccessToken 覆盖按请求传入的 token：
 * 主账号登录/刷新后，多账号下载传入的 from-account token 会被主账号凭证顶掉，
 * 造成「用 A 账号凭证取 B 账号资源」。这里用假 OkHttp 服务端记录每次请求的
 * Authorization，验证每个请求只带自己那份 token。
 */
class XunleiMultiAccountTokenTest {

    @Test
    fun each_request_uses_its_own_access_token() = runBlocking {
        val fake = RecordingServer()
        val api = XunleiApi(clientProvider = { fake.client })

        // 账号 A 先请求（旧实现此时 currentAccessToken=A）
        api.getFileDetail("file-A", "token-A", "dev-A", "captcha-A")
        // 账号 B 再请求：必须带 token-B，不能被 A 覆盖
        api.getFileDetail("file-B", "token-B", "dev-B", "captcha-B")
        // 账号 A 再来一次：必须仍带 token-A，不能被 B 覆盖
        api.getFileDetail("file-C", "token-A", "dev-A", "captcha-A")

        val authHeaders = fake.authorizations()
        assertEquals("Bearer token-A", authHeaders[0])
        assertEquals("Bearer token-B", authHeaders[1])
        assertEquals("Bearer token-A", authHeaders[2])
    }

    @Test
    fun refreshed_token_does_not_leak_into_other_account_requests() = runBlocking {
        // 模拟真实串号路径：账号 A 请求触发 401 → refreshTokenProvider 返回新 token A'。
        // 旧实现会把 A' 写进全局 currentAccessToken，随后账号 B 的请求被顶成 A'（串号）。
        // 新实现里刷新值只用于本次重试，账号 B 仍带自己的 token-B。
        val fake = RecordingServer(firstGets401 = true)
        val api = XunleiApi(clientProvider = { fake.client })
        var refreshCalls = 0
        api.refreshTokenProvider = { _ ->
            refreshCalls++
            "token-A-refreshed" to "rt"
        }

        api.getFileDetail("file-A", "token-A", "dev-A", "captcha-A")
        api.getFileDetail("file-B", "token-B", "dev-B", "captcha-B")

        assertEquals(1, refreshCalls, "只应在账号 A 的 401 上刷新一次")
        val auth = fake.authorizations()
        // 账号 A：第一次 token-A（401），重试 token-A-refreshed
        assertEquals("Bearer token-A", auth[0])
        assertEquals("Bearer token-A-refreshed", auth[1])
        // 账号 B：绝不能带上 A 的刷新值
        assertEquals("Bearer token-B", auth[2])
    }

    @Test
    fun empty_access_token_stays_anonymous() = runBlocking {
        val fake = RecordingServer()
        val api = XunleiApi(clientProvider = { fake.client })

        api.getFileDetail("file-guest", "", "dev-guest", "captcha-guest")

        assertEquals(null, fake.authorizations().singleOrNull())
    }

    private class RecordingServer(private val firstGets401: Boolean = false) {
        val requests = mutableListOf<Request>()
        private var fileGetCount = 0

        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                requests += req
                val path = req.url.encodedPath
                if (path.endsWith("captcha/init")) {
                    return@addInterceptor Response.Builder()
                        .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("""{"captcha_token":"ct","expires_in":300}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                val okBody = """{"id":"x","name":"n","size":1,"links":{"application/octet-stream":{"url":"https://dl.example/f"}}}"""
                val isFileGet = path.contains("/drive/v1/files/")
                val force401 = firstGets401 && isFileGet && fileGetCount++ == 0
                val code = if (force401) 401 else 200
                val body = if (force401) """{"error":"unauthenticated"}""" else okBody
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Unauthorized")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        fun authorizations(): List<String?> = requests
            .filter { it.url.encodedPath.contains("/drive/v1/files/") }
            .map { it.header("Authorization") }
    }
}

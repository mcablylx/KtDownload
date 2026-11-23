package top.mcly99.dl.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 百度列目录 errno=-6 三重重试的 token 回退回归测试（P0-4）。
 *
 * 旧实现第三重（Cookie 去重后）在 refresh 失败时回退到最初的 bdstoken——
 * 那正是用脏 Cookie 取的失效值，导致兜底形同虚设，用户被迫重新登录。
 * 新实现回退到上一重刷新出的有效 token。这里用假拦截器精确构造
 * 「首试 -6 → 刷新 FRESH 仍 -6 → 第三重 refresh 失败」的链路，
 * 断言第三次 /api/list 带的是 FRESH 而非 STALE。
 */
class BaiduListTokenFallbackTest {

    @Test
    fun third_retry_falls_back_to_refreshed_token_not_initial() = runBlocking {
        val fake = FakeServer(
            templateTokens = mutableListOf("STALE", "FRESH", ""),
            listErrnos = mutableListOf(-6, -6, 0),
        )
        val api = BaiduApi { fake.client }
        // 含两份 STOKEN → deduplicateCookieHeader 后 clean != cookie，触发第三重
        val dirtyCookie = "STOKEN=old; STOKEN=fresh; BDUSS=x"

        val files = api.listCloudFiles("/", dirtyCookie)

        assertEquals(1, files.size)
        val listTokens = fake.listBdstokens()
        assertEquals(listOf("STALE", "FRESH", "FRESH"), listTokens,
            "第三次必须带上一重刷新出的 FRESH，而不是最初的失效 STALE")
    }

    private class FakeServer(
        private val templateTokens: MutableList<String>,
        private val listErrnos: MutableList<Int>,
    ) {
        val listTokenLog = mutableListOf<String>()

        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain: Interceptor.Chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            val body = when {
                path.endsWith("gettemplatevariable") -> {
                    val token = if (templateTokens.isNotEmpty()) templateTokens.removeAt(0) else ""
                    """{"errno":0,"result":{"bdstoken":"$token"}}"""
                }
                path.endsWith("/api/list") -> {
                    val token = req.url.queryParameter("bdstoken").orEmpty()
                    listTokenLog += token
                    val errno = if (listErrnos.isNotEmpty()) listErrnos.removeAt(0) else 0
                    if (errno == 0) {
                        """{"errno":0,"list":[{"fs_id":"1","server_filename":"a.txt","size":10,"isdir":0,"path":"/a.txt"}]}"""
                    } else {
                        """{"errno":$errno}"""
                    }
                }
                else -> "{}"
            }
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        fun listBdstokens(): List<String> = listTokenLog.map { URLDecoder.decode(it, "UTF-8") }
    }
}

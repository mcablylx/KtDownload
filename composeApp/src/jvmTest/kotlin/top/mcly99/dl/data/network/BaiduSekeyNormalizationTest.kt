/*
 * 分享密钥（randsk）编码方向的回归测试。
 *
 * 线上踩到的坑：`share/verify` 回的是 URL 编码后的 randsk（`%2B...%2F...%3D`）。
 *  - 塞进 Cookie 的 `BDCLND` 时**不能**编码，浏览器里是 `BDCLND=decodeURIComponent(randsk)`；
 *  - 塞进 query 的 `sekey` 时**必须**是编码形式。
 *
 * 以前两处都直接用原始字符串：列目录因为「恰好当成已编码」能跑通，转存却因为 BDCLND 是脏值
 * 被判「分享未验证」，长期返回 errno=2 +「文件已存在」。这里把两个方向都锁住。
 */
package top.mcly99.dl.data.network

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BaiduSekeyNormalizationTest {

    @Test
    fun verify_share_returns_decoded_randsk() = runTest {
        val fake = FakeApi("""{"errno":0,"randsk":"%2BpJd3kQ8%2Fabc%3D"}""")
        val api = BaiduApi { fake.client }

        val sekey = api.verifyShare("1abcDEF", "1234", COOKIE)

        assertEquals("+pJd3kQ8/abc=", sekey, "verifyShare 必须把 URL 编码的 randsk 解码成原文")
    }

    @Test
    fun verify_share_keeps_plain_randsk_untouched() = runTest {
        val fake = FakeApi("""{"errno":0,"randsk":"c8ab12ef"}""")
        val api = BaiduApi { fake.client }

        assertEquals("c8ab12ef", api.verifyShare("1abcDEF", "1234", COOKIE))
    }

    @Test
    fun list_share_re_encodes_sekey_into_query() = runTest {
        val fake = FakeApi("""{"errno":0,"list":[]}""")
        val api = BaiduApi { fake.client }

        api.listShare("1abcDEF", "+pJd3kQ8/abc=", "/", COOKIE)

        val url = fake.urls.single()
        assertTrue(
            url.contains("sekey=%2BpJd3kQ8%2Fabc%3D"),
            "sekey 进 query 必须按原文重新编码（否则 + 会被解成空格）：$url",
        )
    }

    private companion object {
        const val COOKIE = "BDUSS=test"
    }

    /** 拦截所有请求，固定回一段 JSON，并记录请求 URL */
    private class FakeApi(private val body: String) {
        val urls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    urls += request.url.toString()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(MEDIA_JSON))
                        .build()
                }
            )
            .build()
    }
}

private val MEDIA_JSON = "application/json".toMediaType()

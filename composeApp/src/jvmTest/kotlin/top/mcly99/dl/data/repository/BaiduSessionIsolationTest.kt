package top.mcly99.dl.data.repository

import top.mcly99.dl.data.network.BaiduApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 百度会话缓存账号隔离回归测试（P1-5）。
 *
 * 旧实现 sekeys/shareInfos 只以 surl 为 key，两账号解析同一分享会互相覆盖 sekey，
 * 导致取链用错凭证。这里让两个账号（不同 Cookie）解析同一 surl 并各自列目录，
 * 断言各自拿到自己 verify 出的 sekey。
 */
class BaiduSessionIsolationTest {

    @Test
    fun two_accounts_same_surl_keep_separate_sekeys() = runTest {
        val fake = FakeServer()
        val repo = BaiduResolveRepository(BaiduApi { fake.client })

        val sessionA = repo.createSession("https://pan.baidu.com/s/1surl123", "1234", "COOKIE_A").getOrThrow()
        repo.listFiles(sessionA, "/", "COOKIE_A")
        val sessionB = repo.createSession("https://pan.baidu.com/s/1surl123", "5678", "COOKIE_B").getOrThrow()
        repo.listFiles(sessionB, "/", "COOKIE_B")
        // A 再来一次：必须仍用 A 的 sekey，不能被 B 覆盖
        repo.listFiles(sessionA, "/", "COOKIE_A")

        assertEquals(listOf("randskA", "randskB", "randskA"), fake.listSekeys,
            "每个账号列目录都应带自己 verify 出的 sekey")
    }

    private class FakeServer {
        val listSekeys = mutableListOf<String>()

        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            val cookie = req.header("Cookie").orEmpty()
            val body = when {
                url.contains("/share/verify") -> {
                    val randsk = if (cookie.contains("COOKIE_A")) "randskA" else "randskB"
                    """{"errno":0,"randsk":"$randsk"}"""
                }
                url.contains("method=list") -> {
                    listSekeys += URLDecoder.decode(req.url.queryParameter("sekey").orEmpty(), "UTF-8")
                    """{"errno":0,"list":[]}"""
                }
                else -> """{"errno":0}"""
            }
            Response.Builder()
                .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
    }
}

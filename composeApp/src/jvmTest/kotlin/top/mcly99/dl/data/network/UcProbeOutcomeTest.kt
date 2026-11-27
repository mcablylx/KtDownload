package top.mcly99.dl.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import top.mcly99.dl.data.network.model.DownloadLink
import top.mcly99.dl.data.network.model.PlayLink
import top.mcly99.dl.data.network.model.ShareFile
import top.mcly99.dl.data.network.model.ShareSession
import top.mcly99.dl.data.repository.UCResolveRepository
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P2-09 回归：UC 免转存 / 原画预览 / 转码播放链路不能再把错误压成 `null`。
 *
 * 旧实现整段 `runCatching{}.getOrNull()`，超时 / 403 / 连接重置 / 业务错误全变成
 * 「不支持」，上层据此回落重复取链。这里用 OkHttp 应用拦截器伪造响应，零真实网络。
 */
class UcProbeOutcomeTest {

    private val session = ShareSession("share-1", "stoken", "测试分享")
    private val file = ShareFile("f-1", "movie.mp4", 2048L, false, "0", "fid-token")

    // ---------------------------------------------------------------- 原画预览

    @Test
    fun successful_preview_returns_the_original_quality_link() {
        val out = previewOutcome { r ->
            response(r, 200, """{"status":200,"data":{"play_info":{"url":"https://oss/orig.mp4","size":321}}}""")
        }
        val success = assertIs<UcProbeOutcome.Success<DownloadLink>>(out)
        assertEquals("https://oss/orig.mp4", success.value.downloadUrl)
        assertEquals(321L, success.value.size)
    }

    @Test
    fun http_403_and_500_are_unavailable_with_http_code() {
        listOf(403, 500).forEach { code ->
            val out = previewOutcome { r -> response(r, code, "error page") }
            val failure = assertIs<UcProbeOutcome.Unavailable>(out)
            assertEquals(code, failure.httpCode, "HTTP $code 必须带状态码")
            assertTrue(failure.reason.contains("$code"), failure.reason)
        }
    }

    @Test
    fun business_error_is_unavailable_not_unsupported() {
        val out = previewOutcome { r ->
            response(r, 200, """{"status":200,"code":31001,"message":"登录失效"}""")
        }
        val failure = assertIs<UcProbeOutcome.Unavailable>(out)
        assertTrue(failure.reason.contains("31001"), failure.reason)
    }

    @Test
    fun missing_data_or_url_is_the_only_unsupported_case() {
        assertTrue(
            previewOutcome { r -> response(r, 200, """{"status":200}""") }
                === UcProbeOutcome.Unsupported,
        )
        assertTrue(
            previewOutcome { r -> response(r, 200, """{"status":200,"data":{"play_info":{}}}""") }
                === UcProbeOutcome.Unsupported,
        )
    }

    @Test
    fun invalid_json_is_unavailable() {
        val out = previewOutcome { r -> response(r, 200, "<html>风控页</html>") }
        val failure = assertIs<UcProbeOutcome.Unavailable>(out)
        assertTrue(failure.reason.contains("JSON"), failure.reason)
    }

    @Test
    fun transport_failure_is_unavailable() {
        val out = runBlocking {
            fakeApi { throw IOException("connection reset") }
                .getVideoPreviewOutcome("pwd", "stoken", "f-1", "fid-token", "cookie")
        }
        val failure = assertIs<UcProbeOutcome.Unavailable>(out)
        assertTrue(failure.reason.contains("connection reset"), failure.reason)
        assertNull(failure.httpCode)
    }

    @Test
    fun legacy_getVideoPreview_keeps_its_nullable_contract() {
        assertNull(runBlocking {
            fakeApi { r -> response(r, 403, "no") }
                .getVideoPreview("pwd", "stoken", "f-1", "fid-token", "cookie")
        })
        assertEquals("https://oss/orig.mp4", runBlocking {
            fakeApi { r ->
                response(r, 200, """{"status":200,"data":{"play_info":{"url":"https://oss/orig.mp4"}}}""")
            }.getVideoPreview("pwd", "stoken", "f-1", "fid-token", "cookie")?.downloadUrl
        })
    }

    @Test
    fun valueOrNull_only_returns_success_values() {
        assertNull(UcProbeOutcome.Unsupported.valueOrNull())
        assertNull(UcProbeOutcome.Unavailable("x").valueOrNull())
        assertEquals(7, UcProbeOutcome.Success(7).valueOrNull())
    }

    // ---------------------------------------------------------------- 转码播放

    @Test
    fun play_link_does_not_retry_the_bare_path_on_transport_error() = runBlocking {
        val calls = AtomicInteger(0)
        val api = fakeApi { r -> calls.incrementAndGet(); response(r, 403, "no") }
        assertIs<UcProbeOutcome.Unavailable>(api.getPlayLinkOutcome("f-1", "cookie"))
        assertEquals(1, calls.get(), "网络错误不该把同一份失败请求再发一遍")
    }

    @Test
    fun play_link_retries_bare_path_only_when_primary_is_unsupported() = runBlocking {
        val calls = AtomicInteger(0)
        val api = fakeApi { r ->
            calls.incrementAndGet()
            response(r, 200, """{"status":200,"data":{"video_list":[]}}""")
        }
        assertTrue(api.getPlayLinkOutcome("f-1", "cookie") === UcProbeOutcome.Unsupported)
        assertEquals(2, calls.get(), "明确不支持时才试 Alist 裸路径")
    }

    @Test
    fun play_link_success_stops_after_the_first_path() = runBlocking {
        val calls = AtomicInteger(0)
        val api = fakeApi { r ->
            calls.incrementAndGet()
            response(
                r, 200,
                """{"status":200,"data":{"video_list":[{"video_info":{"url":"https://x/i.m3u8","resolution":"720p","format":"m3u8"}}]}}""",
            )
        }
        val success = assertIs<UcProbeOutcome.Success<PlayLink>>(api.getPlayLinkOutcome("f-1", "cookie"))
        assertTrue(success.value.isHls)
        assertEquals(1, calls.get())
    }

    // ---------------------------------------------------------------- 仓库层回落

    @Test
    fun repository_reports_unavailable_with_reason_and_never_falls_back_on_403() = runBlocking {
        val calls = AtomicInteger(0)
        val api = fakeApi { r -> calls.incrementAndGet(); response(r, 403, "forbidden") }
        val result = UCResolveRepository(api).getShareDownloadLink(session, file, "cookie")
        assertTrue(result.isFailure, "403 必须作为错误抛出，而不是静默回落")
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("403"), message)
        assertTrue(message.contains("重复取链"), message)
        assertEquals(1, calls.get(), "网络错误时不能回落普通取链放大请求")
    }

    @Test
    fun repository_still_falls_back_to_normal_link_when_preview_is_unsupported() = runBlocking {
        val calls = AtomicInteger(0)
        val api = fakeApi { r ->
            calls.incrementAndGet()
            if (r.url.encodedPath.contains("video_preview")) {
                response(r, 200, """{"status":200,"data":{}}""")
            } else {
                response(
                    r, 200,
                    """{"status":200,"data":[{"download_url":"https://cdn/normal.mp4","file_name":"movie.mp4","size":2048}]}""",
                )
            }
        }
        val result = UCResolveRepository(api).getShareDownloadLink(session, file, "cookie")
        assertTrue(result.isSuccess, "明确的业务否定仍应回落普通取链")
        assertEquals("https://cdn/normal.mp4", result.getOrThrow().downloadUrl)
        assertEquals(2, calls.get())
    }

    @Test
    fun repository_uses_the_original_quality_link_on_success() = runBlocking {
        val calls = AtomicInteger(0)
        val api = fakeApi { r ->
            calls.incrementAndGet()
            response(r, 200, """{"status":200,"data":{"play_info":{"url":"https://oss/orig.mp4","size":999}}}""")
        }
        val result = UCResolveRepository(api).getShareDownloadLink(session, file, "cookie")
        assertEquals("https://oss/orig.mp4", result.getOrThrow().downloadUrl)
        assertEquals(1, calls.get())
    }

    // ---------------------------------------------------------------- 辅助

    private fun previewOutcome(handler: (Request) -> Response): UcProbeOutcome<DownloadLink> = runBlocking {
        fakeApi(handler).getVideoPreviewOutcome("pwd", "stoken", "f-1", "fid-token", "cookie")
    }

    private fun fakeApi(handler: (Request) -> Response): UCApi {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()
        return UCApi(clientProvider = { client })
    }

    private fun response(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
}

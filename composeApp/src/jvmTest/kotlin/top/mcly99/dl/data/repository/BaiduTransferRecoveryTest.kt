/*
 * 百度转存兜底链路的回归测试。
 *
 * 线上踩到的现象：`share/transfer` 长期返回 errno=2 + 「文件已存在」，但目标目录里
 * 既列不到、也删不掉该文件。公开错误码表里 errno=2 的含义是「目标目录不存在 / 参数不被接受」，
 * 而现行客户端（BaiduPCS-Go、批量转存工具）发的是不带引号的 fsidlist。
 * 这里用假 OkHttp 拦截器把百度接口换成可控响应，锁住两件事：
 *  1) 第一次转存必须用「现行客户端形状」（fsidlist 不带引号、不带 sekey）；
 *  2) 失败后能按形状 → 复用 → 刷新 token 的顺序兜底，全部失败时给出可读错误。
 */
package top.mcly99.dl.data.repository

import top.mcly99.dl.data.network.BaiduApi
import top.mcly99.dl.data.network.model.ShareFile
import top.mcly99.dl.data.network.model.ShareSession
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BaiduTransferRecoveryTest {

    @Test
    fun first_attempt_uses_unquoted_fsidlist_and_no_sekey() = runTest {
        val fake = FakeBaiduApi(transferFailTimes = 0, existingInWorkDir = false)
        val repo = BaiduResolveRepository(BaiduApi { fake.client })

        repo.getShareDownloadLink(codedSession, file, COOKIE, skipSpaceCheck = true).getOrThrow()

        val first = fake.transferBodies.first()
        assertTrue(
            first.contains("fsidlist=%5B8888%5D"),
            "首发的 fsidlist 必须是 [8888]（现行客户端写法）：$first",
        )
        assertTrue(!first.contains("%22"), "fsidlist 不应带引号：$first")
        assertTrue(
            !fake.transferUrls.first().contains("sekey="),
            "首发不应带 sekey：${fake.transferUrls.first()}",
        )
    }

    @Test
    fun falls_back_to_next_param_shape_when_first_reports_errno_2() = runTest {
        val fake = FakeBaiduApi(transferFailTimes = 1, existingInWorkDir = false)
        val repo = BaiduResolveRepository(BaiduApi { fake.client })

        val link = repo.getShareDownloadLink(session, file, COOKIE, skipSpaceCheck = true).getOrThrow()

        assertEquals("https://d.pcs.baidu.com/appall01.example/x.zip", link.downloadUrl)
        assertEquals(2, fake.transferCalls.get(), "第一种形状失败后应换第二种形状重试一次")
        assertEquals(
            "https://pan.baidu.com/s/1abcDEF",
            fake.transferReferers.last(),
            "换形状时应带真实分享页 Referer（浏览器就是这个来源）",
        )
    }

    @Test
    fun reuses_existing_file_with_same_name_and_size_instead_of_transferring_again() = runTest {
        val fake = FakeBaiduApi(transferFailTimes = Int.MAX_VALUE, existingInWorkDir = true)
        val repo = BaiduResolveRepository(BaiduApi { fake.client })

        val link = repo.getShareDownloadLink(session, file, COOKIE, skipSpaceCheck = true).getOrThrow()

        assertEquals("https://d.pcs.baidu.com/appall01.example/x.zip", link.downloadUrl)
        assertEquals(
            10,
            fake.transferCalls.get(),
            "一轮参数形状（10 种）失败后命中复用，不应再继续刷新 token 重试",
        )
    }

    @Test
    fun reports_actionable_error_when_every_recovery_step_fails() = runTest {
        val fake = FakeBaiduApi(transferFailTimes = Int.MAX_VALUE, existingInWorkDir = false)
        val repo = BaiduResolveRepository(BaiduApi { fake.client })

        val result = repo.getShareDownloadLink(session, file, COOKIE, skipSpaceCheck = true)

        val message = result.exceptionOrNull()?.message.orEmpty()
        // 弹窗只留一句可执行的话；errno/参数形状/shareid 这些技术细节只进日志
        assertTrue(message.contains("转存被百度拒绝"), "要给出结论：$message")
        assertTrue(message.contains("换参数形状"), "要说明已经试过哪些兜底：$message")
        assertTrue(message.length < 200, "弹窗文案不该堆内部诊断：$message")
        assertTrue(fake.templateCalls.get() >= 2, "失败后应刷新 bdstoken 再试")
    }

    /**
     * 线上第二次误判的根因：列表接口没回 `share_id`/`uk`（子目录列表尤其容易缺），
     * 转存就只能拿空值去请求，百度一律回 errno=2 +「文件已存在」。
     * 这里锁住兜底：缺了就回分享页解析，解析出的 shareid/uk 必须真的用在转存请求上。
     */
    @Test
    fun falls_back_to_share_page_for_share_id_when_list_omits_it() = runTest {
        val fake = FakeBaiduApi(
            transferFailTimes = 0,
            existingInWorkDir = false,
            listIncludesShareMeta = false,
            // 分享页把 yunData 内嵌在 HTML 里，转存要的就是这两个数字
            sharePageHtml = """<script>window.yunData={"SHAREID":1234567890,""" +
                """"SHARE_UK":"987654321","fs_id":8888};</script>""",
        )
        val repo = BaiduResolveRepository(BaiduApi { fake.client })

        repo.getShareDownloadLink(session, file, COOKIE, skipSpaceCheck = true).getOrThrow()

        val url = fake.transferUrls.first()
        assertTrue(url.contains("shareid=1234567890"), "转存必须带分享页解析出的 shareid：$url")
        assertTrue(url.contains("from=987654321"), "转存必须带分享者 uk：$url")
    }

    /** 分享页也解析不出来时，要直接说清「缺 shareid」，而不是发一个注定 errno=2 的请求 */
    @Test
    fun reports_missing_share_id_instead_of_sending_a_doomed_transfer() = runTest {
        val fake = FakeBaiduApi(
            transferFailTimes = 0,
            existingInWorkDir = false,
            listIncludesShareMeta = false,
        )
        val repo = BaiduResolveRepository(BaiduApi { fake.client })

        val result = repo.getShareDownloadLink(session, file, COOKIE, skipSpaceCheck = true)

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("shareid"), "要指出缺的是分享 ID：$message")
        assertEquals(0, fake.transferCalls.get(), "参数不全时不该再发转存请求")
    }

    /** 子目录列表缺 share_id 时，不能用空值覆盖掉根目录已经取到的有效值 */
    @Test
    fun subdirectory_list_without_share_meta_does_not_clobber_cache() = runTest {
        val fake = FakeBaiduApi(
            transferFailTimes = 0,
            existingInWorkDir = false,
            subdirOmitsShareMeta = true,
        )
        val repo = BaiduResolveRepository(BaiduApi { fake.client })

        repo.listFiles(session, "/", COOKIE).getOrThrow()
        repo.listFiles(session, "/sub", COOKIE).getOrThrow()
        repo.getShareDownloadLink(session, file, COOKIE, skipSpaceCheck = true).getOrThrow()

        val url = fake.transferUrls.first()
        assertTrue(url.contains("shareid=123"), "空字段不该覆盖已有 shareid：$url")
        assertTrue(url.contains("from=456"), "空字段不该覆盖已有 uk：$url")
        assertEquals(2, fake.shareListCalls.get(), "缓存被空值冲掉后会多补列一次根目录")
    }

    /**
     * 线上最后一轮的实际形态：BDCLND 是 `%2B...` 这种 URL 编码脏值，服务端判定「分享未验证」→ errno=2。
     * 这里锁住：注入 BDCLND 的形状都失败后，能退到「只带 sekey、不动 Cookie」并成功。
     */
    @Test
    fun falls_back_to_sekey_only_when_injected_bdclnd_is_rejected() = runTest {
        val fake = FakeBaiduApi(transferFailTimes = 2, existingInWorkDir = false)
        val repo = BaiduResolveRepository(BaiduApi { fake.client })

        repo.getShareDownloadLink(codedSession, file, COOKIE, skipSpaceCheck = true).getOrThrow()

        assertEquals(3, fake.transferCalls.get(), "前两种注入 BDCLND 的形状失败后，第三种应成功")
        assertTrue(
            !fake.transferCookies.last().contains("BDCLND="),
            "第三种形状不应改 Cookie：${fake.transferCookies.last()}",
        )
        assertTrue(
            fake.transferUrls.last().contains("sekey=%2BpJd3kQ8%2Fabc%3D"),
            "sekey 必须按原文重新 URL 编码（+ / = 都编码）：${fake.transferUrls.last()}",
        )
    }

    /** 注入 BDCLND 时，值必须是解码后的 randsk：`BDCLND=+pJd...`，不能是 `%2BpJd...` */
    @Test
    fun injected_bdclnd_uses_decoded_randsk() = runTest {
        val fake = FakeBaiduApi(transferFailTimes = 0, existingInWorkDir = false)
        val repo = BaiduResolveRepository(BaiduApi { fake.client })

        repo.getShareDownloadLink(codedSession, file, COOKIE, skipSpaceCheck = true).getOrThrow()

        val cookie = fake.transferCookies.first()
        assertTrue(cookie.contains("BDCLND=+pJd3kQ8/abc="), "BDCLND 必须是解码后的 randsk：$cookie")
        assertTrue(!cookie.contains("BDCLND=%2B"), "BDCLND 不允许是 URL 编码形式：$cookie")
    }

    /**
     * 只有 BDUSS、没有 STOKEN 的「半张 Cookie」是转存恒 errno=2 的经典原因：
     * 浏览目录正常，但 share/transfer 一律被拒。这种情况不该白试参数形状。
     */
    @Test
    fun rejects_half_cookie_without_stoken() = runTest {
        val fake = FakeBaiduApi(transferFailTimes = 0, existingInWorkDir = false)
        val repo = BaiduResolveRepository(BaiduApi { fake.client })

        val result = repo.getShareDownloadLink(session, file, "BDUSS=test", skipSpaceCheck = true)

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("STOKEN"), "要指出缺的是 STOKEN：$message")
        assertEquals(0, fake.transferCalls.get(), "缺 STOKEN 时不应再发注定失败的转存请求")
    }

    /**
     * 免转存取链：签名 + /api/sharedownload 拿到直链就返回，**不应该有任何转存请求**，
     * 也就不可能因为「目标目录不存在 / 转存被拒」失败。
     */
    @Test
    fun direct_dlink_needs_no_transfer() = runTest {
        val fake = FakeBaiduApi(transferFailTimes = Int.MAX_VALUE, existingInWorkDir = false)
        val repo = BaiduResolveRepository(BaiduApi { fake.client })

        val link = repo.getShareDownloadLinkWithoutSave(session, file, COOKIE).getOrThrow()

        assertEquals("https://d.pcs.baidu.com/file/direct.example/x.zip", link.downloadUrl)
        assertEquals(1, fake.directCalls.get())
        assertEquals(0, fake.transferCalls.get(), "免转存取链不应触发转存")
    }

    /**
     * 线上这一轮的根因：`/share/tplconfig` 的 surl **必须带首位 1**（不带时服务端回
     * `{"errno":2,"show_msg":"啊哦，链接出错了"}`，看着跟「提取码失效」一模一样），
     * 而且 `sign` 藏在 `data` 对象里，顶层没有。
     */
    @Test
    fun sign_request_restores_leading_one_and_reads_nested_data() = runTest {
        val fake = FakeBaiduApi(transferFailTimes = 0, existingInWorkDir = false)
        val repo = BaiduResolveRepository(BaiduApi { fake.client })

        val link = repo.getShareDownloadLinkWithoutSave(codedSession, file, COOKIE).getOrThrow()

        assertEquals("https://d.pcs.baidu.com/file/direct.example/x.zip", link.downloadUrl)
        assertEquals(1, fake.signUrls.size, "带 1 的形态应当首发命中，不必再试兜底形态")
        assertTrue(
            fake.signUrls.first().contains("surl=1abcDEF"),
            "签名接口的 surl 必须带首位 1：${fake.signUrls.first()}",
        )
    }

    /** 直链接口拒绝时必须是失败（由上层回退到转存取链），不能返回空直链 */
    @Test
    fun direct_dlink_failure_is_reported() = runTest {
        val fake = FakeBaiduApi(
            transferFailTimes = 0,
            existingInWorkDir = false,
            directDlinkWorks = false,
        )
        val repo = BaiduResolveRepository(BaiduApi { fake.client })

        val result = repo.getShareDownloadLinkWithoutSave(session, file, COOKIE)

        assertTrue(result.isFailure, "直链被拒时应当失败，好让上层回退转存")
        assertTrue(fake.directCalls.get() >= 1, "至少发过一次直链请求")
    }

    /**
     * 线上这一轮的根因：带提取码的分享调 `/api/sharedownload` 时，除了 Cookie 里的 BDCLND，
     * body 还必须给 `extra={"sekey":"<randsk 原文>"}`；少了它服务端只回 errno=2「请求失败」。
     * 同时签名优先用**分享页里那份**（网页端就是用它与页面 bdstoken 一起发请求的）。
     */
    @Test
    fun direct_dlink_uses_share_page_sign_and_sends_extra_sekey() = runTest {
        val fake = FakeBaiduApi(
            transferFailTimes = 0,
            existingInWorkDir = false,
            sharePageHtml = """<script>window.yunData={"shareid":43664127993,""" +
                """"share_uk":"3475848858","sign":"page-sign-1",""" +
                """"timestamp":1757123456789,"bdstoken":"page-token"};</script>""",
        )
        val repo = BaiduResolveRepository(BaiduApi { fake.client })

        val link = repo.getShareDownloadLinkWithoutSave(codedSession, file, COOKIE).getOrThrow()

        assertEquals("https://d.pcs.baidu.com/file/direct.example/x.zip", link.downloadUrl)
        assertEquals(1, fake.directCalls.get(), "首个参数形状就该命中，不该把阶梯跑一遍")
        val url = fake.directUrls.first()
        val body = java.net.URLDecoder.decode(fake.directBodies.first(), "UTF-8")
        assertTrue(url.contains("sign=page-sign-1"), "必须用分享页那份签名：$url")
        assertTrue(url.contains("bdstoken=page-token"), "要带分享页下发的 bdstoken：$url")
        assertTrue(body.contains("fid_list="), "现行网页写法用 fid_list：$body")
        assertTrue(
            body.contains(""""sekey":"+pJd3kQ8/abc="""),
            "带提取码的分享必须给 extra.sekey（randsk 原文）：$body",
        )
    }

    // ---------------------------------------------------------------- 测试替身

    private companion object {
        const val COOKIE = "BDUSS=test; STOKEN=test"
        const val TEMP_DIR = "/YunX临时转存"
        const val FILE_NAME = "DirectX_Repair增强版_v4.3.zip"
        const val FILE_SIZE = 12_345_678L

        // 线上真实形态：链接解析器按 HTTP 接口惯例去掉了首位 1（`xpan/share?shorturl=` 要的就是它），
        // 网页前端接口（/s/、/share/tplconfig）则必须重新补回来
        val session = ShareSession(shareId = "abcDEF", stoken = "", title = "分享")

        /** 带提取码的分享：stoken = verify 返回的 randsk（已由 [BaiduApi.verifyShare] 规范化成原文） */
        val codedSession = ShareSession(shareId = "abcDEF", stoken = "+pJd3kQ8/abc=", title = "分享")

        val file = ShareFile(
            fid = "8888",
            fname = FILE_NAME,
            fsize = FILE_SIZE,
            isdir = false,
            pdirFid = "/",
            fidToken = "",
        )
    }

    /** 用拦截器把百度接口全部换成本地假响应；[transferFailTimes] 控制前几次转存失败 */
    private class FakeBaiduApi(
        private val transferFailTimes: Int,
        private val existingInWorkDir: Boolean,
        /** 列表接口是否回 share_id/uk（真机上不同版本、不同层级都可能缺） */
        private val listIncludesShareMeta: Boolean = true,
        /** 分享页 HTML（[BaiduApi.shareMeta] 的兜底数据源），空串表示页面里没有可用字段 */
        private val sharePageHtml: String = "",
        /** 子目录列表缺 share_id/uk（用于验证缓存不会被空值覆盖） */
        private val subdirOmitsShareMeta: Boolean = false,
        /** 免转存直链（tplconfig + sharedownload）是否可用 */
        private val directDlinkWorks: Boolean = true,
    ) {
        val transferCalls = AtomicInteger()
        val templateCalls = AtomicInteger()

        /** 分享列表请求次数（用于断言 share 元信息没有被反复重取） */
        val shareListCalls = AtomicInteger()

        /** 免转存直链请求次数 */
        val directCalls = AtomicInteger()

        /** 每次免转存直链请求的 URL / body（用于断言签名来源与 extra.sekey） */
        val directUrls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val directBodies: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** 每次 transfer 请求的 body（用于断言 fsidlist 形状） */
        val transferBodies: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** 每次 transfer 请求的 URL（用于断言 sekey 等 query 参数） */
        val transferUrls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** 每次 transfer 请求的 Cookie（用于断言 BDCLND 注入与否） */
        val transferCookies: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** 每次 transfer 请求的 Referer */
        val transferReferers: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** 每次 tplconfig 请求的 URL（用于断言 surl 形态） */
        val signUrls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private var tokenSeq = 0

        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> respond(chain.request()) })
            .build()

        private fun respond(request: okhttp3.Request): Response {
            val url = request.url.toString()
            val body = when {
                url.contains("gettemplatevariable") -> {
                    templateCalls.incrementAndGet()
                    """{"errno":0,"result":{"bdstoken":"token-${++tokenSeq}"}}"""
                }

                // 开放平台入口的转存（rest/2.0/xpan/share?method=transfer）：必须先于列表分支判断，
                // 两者路径前缀相同
                url.contains("method=transfer") -> transferResponse(request)

                url.contains("xpan/share") -> {
                    shareListCalls.incrementAndGet()
                    val withMeta = listIncludesShareMeta &&
                        !(subdirOmitsShareMeta && url.contains("dir=%2Fsub"))
                    val meta = if (withMeta) """"share_id":"123","uk":"456",""" else ""
                    """{"errno":0,$meta"list":[
                         {"fs_id":"8888","server_filename":"$FILE_NAME","size":$FILE_SIZE,
                          "isdir":"0","path":"/$FILE_NAME"}]}"""
                }

                // 真机实测：surl 不带首位 1 → errno=2「啊哦，链接出错了」；
                // 带上 1 → errno=0，且 sign/timestamp 在 data 里，顶层没有
                url.contains("share/tplconfig") -> {
                    signUrls += url
                    val surl = request.url.queryParameter("surl").orEmpty()
                    if (surl.startsWith("1")) {
                        """{"errno":0,"data":{"timestamp":1757123456,"sign":"sig-1"},"show_msg":"success"}"""
                    } else {
                        """{"errno":2,"show_msg":"啊哦，链接出错了"}"""
                    }
                }

                url.contains("api/sharedownload") -> {
                    directCalls.incrementAndGet()
                    directUrls += url
                    directBodies += readBody(request)
                    if (directDlinkWorks) {
                        """{"errno":0,"list":[{"dlink":"https://d.pcs.baidu.com/file/direct.example/x.zip"}]}"""
                    } else {
                        """{"errno":2,"errmsg":"need bdstoken"}"""
                    }
                }

                // 分享页（shareMeta 兜底）：页面 HTML 里内嵌 yunData
                url.startsWith("https://pan.baidu.com/s/") -> sharePageHtml.ifBlank { "<html></html>" }

                url.contains("share/transfer") -> transferResponse(request)

                // 列目录：根目录要有临时目录；工作目录按用例决定是否已有同名文件
                url.contains("/api/list") -> if (url.contains("dir=%2F&") || url.endsWith("dir=%2F")) {
                    """{"errno":0,"list":[{"isdir":1,"path":"$TEMP_DIR"}]}"""
                } else if (existingInWorkDir) {
                    """{"errno":0,"list":[{"fs_id":"999","server_filename":"$FILE_NAME",
                        "size":$FILE_SIZE,"isdir":0,"path":"$TEMP_DIR/$FILE_NAME"}]}"""
                } else {
                    """{"errno":0,"list":[]}"""
                }

                url.contains("api/create") -> """{"errno":0}"""
                url.contains("api/filemanager") -> """{"errno":0}"""
                url.contains("method=locatedownload") ->
                    """{"errno":0,"urls":[{"url":"https://d.pcs.baidu.com/appall01.example/x.zip","encrypt":0}]}"""

                else -> """{"errno":0}"""
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody(MEDIA_JSON))
                .build()
        }

        /** 转存响应：前 [transferFailTimes] 次回 errno=2（线上就是「文件已存在」），之后成功 */
        private fun transferResponse(request: okhttp3.Request): String {
            val n = transferCalls.incrementAndGet()
            transferUrls += request.url.toString()
            transferBodies += readBody(request)
            transferCookies += request.header("Cookie").orEmpty()
            transferReferers += request.header("Referer").orEmpty()
            return if (n <= transferFailTimes) {
                """{"errno":2,"err_msg":"文件已存在"}"""
            } else {
                """{"errno":0,"extra":{"list":[{"to_fs_id":"999","to":"$TEMP_DIR/$FILE_NAME"}]}}"""
            }
        }

        private fun readBody(request: okhttp3.Request): String {
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            return buffer.readUtf8()
        }
    }
}

private val MEDIA_JSON = "application/json".toMediaType()

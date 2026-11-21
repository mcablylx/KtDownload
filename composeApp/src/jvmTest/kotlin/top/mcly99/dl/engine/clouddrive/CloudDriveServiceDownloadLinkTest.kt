package top.mcly99.dl.engine.clouddrive

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import top.mcly99.dl.data.network.C139Constants
import top.mcly99.dl.data.network.LanzouConstants
import top.mcly99.dl.data.network.Pan115Constants
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.UCConstants
import top.mcly99.dl.data.network.model.DownloadLink
import top.mcly99.dl.data.network.model.ShareFile
import top.mcly99.dl.data.network.model.ShareSession
import top.mcly99.dl.data.repository.ShareResolveRepository

/**
 * D-48：取链三条路径与 CloudLink 传参的单测。
 *
 * 通过 [CloudDriveService] 的 repositoryOverride 注入 FakeRepo，
 * 全程零真实网络，重点断言「哪条路径被调用」与「入队参数是否正确」。
 */
class CloudDriveServiceDownloadLinkTest {

    private val session = ShareSession("share-1", "stoken", "测试分享")
    private val file = ShareFile("f-1", "movie.mp4", 2048L, false, "0", "fid-token")

    private fun link(
        url: String,
        name: String = "",
        size: Long = 0L,
        guestCookie: String = "",
        cleanupDirFid: String? = null,
        isHls: Boolean = false,
    ) = DownloadLink(
        fid = "f-1",
        filename = name,
        downloadUrl = url,
        size = size,
        cleanupDirFid = cleanupDirFid,
        isHls = isHls,
        guestCookie = guestCookie,
    )

    private fun service(repo: FakeRepo) = CloudDriveService(repositoryOverride = { repo })

    @Test
    fun guest_path_is_used_when_credential_is_blank_and_keeps_guest_cookie() = runTest {
        val repo = FakeRepo().apply {
            guestResult = Result.success(
                link("https://cdn.example/guest", guestCookie = "__pugs=abc"),
            )
        }

        val result = service(repo).downloadLink(session, file, "", SharePlatform.QUARK)

        assertEquals(1, repo.guestCalls)
        assertEquals(0, repo.noSaveCalls)
        assertEquals(0, repo.transferCalls)
        assertEquals("https://cdn.example/guest", result.url)
        assertEquals("__pugs=abc", result.cookie)
        // 服务端没给体积/文件名时回落到分享文件本身
        assertEquals(file.fsize, result.size)
        assertEquals(file.fname, result.filename)
        assertEquals(SharePlatform.QUARK, result.platform)
        assertEquals(
            CloudDriveService().refererFor(SharePlatform.QUARK),
            result.referer,
        )
    }

    @Test
    fun logged_in_path_uses_nosave_and_never_touches_guest_or_transfer() = runTest {
        val repo = FakeRepo().apply {
            noSaveResult = Result.success(link("https://cdn.example/nosave", size = 4096))
        }

        val result = service(repo).downloadLink(session, file, "BDUSS=xyz", SharePlatform.BAIDU)

        assertEquals(1, repo.noSaveCalls)
        assertEquals(0, repo.guestCalls)
        assertEquals(0, repo.transferCalls)
        assertEquals("BDUSS=xyz", repo.lastCookie)
        assertEquals("BDUSS=xyz", result.cookie)
        assertEquals(file.fsize, result.size, "列表里看到的大小优先于取链响应")
        assertEquals("https://cdn.example/nosave", result.url)
    }

    @Test
    fun listed_file_size_wins_over_a_placeholder_link_size() = runTest {
        val listed = file.copy(fsize = 239_880_000L)
        val repo = FakeRepo().apply {
            noSaveResult = Result.success(link("https://cdn.example/nosave", size = 6_832L))
        }

        val result = service(repo).downloadLink(session, listed, "cookie", SharePlatform.BAIDU)

        assertEquals(239_880_000L, result.size)

        val unknown = service(repo).downloadLink(session, file.copy(fsize = 0L), "cookie", SharePlatform.BAIDU)
        assertEquals(6_832L, unknown.size, "列表没给有效大小时才用取链响应")
    }

    @Test
    fun separate_nosave_failure_falls_back_to_the_transfer_path() = runTest {
        val repo = FakeRepo(separateNoSave = true).apply {
            noSaveResult = failure("免转存不支持该分享类型")
            transferResult = Result.success(
                link("https://cdn.example/transfer", cleanupDirFid = "tr_42"),
            )
        }

        val result = service(repo).downloadLink(session, file, "cookie", SharePlatform.QUARK)

        assertEquals(1, repo.noSaveCalls)
        assertEquals(1, repo.transferCalls)
        assertEquals("https://cdn.example/transfer", result.url)
        assertEquals("tr_42", result.cleanupDirFid)
    }

    @Test
    fun default_nosave_failure_does_not_reissue_the_same_request() = runTest {
        // 默认实现只是委托给 getShareDownloadLink；再回落一次等于重发同一个请求 / 重复转存
        val repo = FakeRepo(separateNoSave = false).apply {
            noSaveResult = failure("取链失败")
            transferResult = Result.success(link("https://cdn.example/should-not-be-used"))
        }

        val error = assertFailsWith<IllegalStateException> {
            service(repo).downloadLink(session, file, "cookie", SharePlatform.UC)
        }

        assertEquals(1, repo.noSaveCalls)
        assertEquals(0, repo.transferCalls)
        assertTrue(error.message!!.contains("取链失败"))
    }

    @Test
    fun both_failures_are_reported_together() = runTest {
        val repo = FakeRepo(separateNoSave = true).apply {
            noSaveResult = failure("免转存失败")
            transferResult = failure("转存也失败")
        }

        val error = assertFailsWith<IllegalStateException> {
            service(repo).downloadLink(session, file, "cookie", SharePlatform.QUARK)
        }

        assertTrue(error.message!!.contains("免转存失败"))
        assertTrue(error.message!!.contains("转存也失败"))
        assertEquals(1, repo.transferCalls)
    }

    @Test
    fun xunlei_token_json_is_never_sent_as_a_cookie_header() = runTest {
        val tokenJson = """{"accessToken":"atk","refreshToken":"rtk","deviceId":"dev"}"""
        val repo = FakeRepo().apply {
            noSaveResult = Result.success(link("https://cdn.xunlei.com/f?sign=1"))
        }

        val result = service(repo).downloadLink(session, file, tokenJson, SharePlatform.XUNLEI)

        assertEquals("", result.cookie)
        assertEquals(SharePlatform.XUNLEI, result.platform)
    }

    // ------------------------------------------------- P0：下载阶段的平台请求头

    @Test
    fun platform_user_agent_and_origin_are_carried_on_the_cloud_link() = runTest {
        // 取链接口对了、下载还是 403，就是因为这三个字段以前根本没往任务上传
        val repo = FakeRepo().apply {
            noSaveResult = Result.success(link("https://oss.example/uc-file"))
        }

        val result = service(repo).downloadLink(session, file, "cookie", SharePlatform.UC)

        assertEquals(UCConstants.USER_AGENT, result.userAgent)
        assertEquals(UCConstants.WEB_ORIGIN, result.origin)
        assertEquals(UCConstants.DOWNLOAD_REFERER, result.referer)
        assertEquals("cookie", result.cookie)
    }

    @Test
    fun pan115_link_merges_login_and_cdn_cookie_into_one_header() = runTest {
        val repo = FakeRepo().apply {
            noSaveResult = Result.success(
                link("https://cdn.115.com/file", guestCookie = "UID=1_A1_1; CID=cdn900"),
            )
        }

        val result = service(repo).downloadLink(session, file, "UID=1_A1_1; SEID=login", SharePlatform.PAN115)

        assertEquals("UID=1_A1_1; SEID=login; UID=1_A1_1; CID=cdn900", result.cookie)
        assertEquals(Pan115Constants.CLIENT_UA, result.userAgent)
    }

    @Test
    fun signed_cdn_platform_does_not_send_the_account_cookie() = runTest {
        val repo = FakeRepo().apply {
            noSaveResult = Result.success(link("https://cdn.139.com/signed"))
        }

        val result = service(repo).downloadLink(session, file, "RMKEY=account", SharePlatform.C139)

        assertEquals("", result.cookie)
        assertEquals(C139Constants.PC_UA, result.userAgent)
    }

    @Test
    fun lanzou_link_referer_follows_the_download_node() = runTest {
        val repo = FakeRepo().apply {
            noSaveResult = Result.success(link("https://down-7.lanzoui.com/file/abc"))
        }

        val result = service(repo).downloadLink(session, file, "cookie", SharePlatform.LANZOU)

        assertEquals("https://down-7.lanzoui.com/", result.referer)
        assertEquals(LanzouConstants.DOWN_IP_COOKIE, result.cookie)
    }

    @Test
    fun cleanup_dir_hls_flag_and_explicit_filename_are_propagated() = runTest {        val repo = FakeRepo().apply {
            noSaveResult = Result.success(
                link(
                    "https://cdn.example/playlist.m3u8",
                    name = "transcoded.mp4",
                    size = 99L,
                    cleanupDirFid = "tr_hls",
                    isHls = true,
                ),
            )
        }

        val result = service(repo).downloadLink(session, file, "cookie", SharePlatform.UC)

        assertEquals("transcoded.mp4", result.filename)
        assertEquals(99L, result.size)
        assertEquals("tr_hls", result.cleanupDirFid)
        assertTrue(result.isHls)
    }

    // ---------------------------------------------------------------- 假实现

    private class FakeRepo(
        private val separateNoSave: Boolean = false,
    ) : ShareResolveRepository {
        override val supportsSeparateNoSaveLink: Boolean get() = separateNoSave

        private fun fail(message: String): Result<DownloadLink> =
            Result.failure(IllegalStateException(message))

        var guestResult: Result<DownloadLink> = fail("guest not configured")
        var noSaveResult: Result<DownloadLink> = fail("no-save not configured")
        var transferResult: Result<DownloadLink> = fail("transfer not configured")

        var guestCalls = 0
        var noSaveCalls = 0
        var transferCalls = 0
        var lastCookie = ""

        override suspend fun createSession(link: String, pwd: String?, cookie: String) =
            Result.success(ShareSession(link, "stoken", "title"))

        override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String) =
            Result.success(emptyList<ShareFile>())

        override suspend fun ensureTempDir(cookie: String) = Result.success("tmp")

        override suspend fun transferFile(
            session: ShareSession,
            file: ShareFile,
            toDirFid: String,
            cookie: String,
            skipSpaceCheck: Boolean,
        ) = Result.success("new-fid")

        override suspend fun getDownloadLink(fid: String, cookie: String) = transferResult

        override suspend fun getShareDownloadLink(
            session: ShareSession,
            file: ShareFile,
            cookie: String,
            skipSpaceCheck: Boolean,
        ): Result<DownloadLink> {
            transferCalls++
            lastCookie = cookie
            return transferResult
        }

        override suspend fun getShareDownloadLinkWithoutSave(
            session: ShareSession,
            file: ShareFile,
            cookie: String,
        ): Result<DownloadLink> {
            noSaveCalls++
            lastCookie = cookie
            return noSaveResult
        }

        override suspend fun getGuestShareDownloadLink(
            session: ShareSession,
            file: ShareFile,
        ): Result<DownloadLink> {
            guestCalls++
            return guestResult
        }
    }

    private companion object {
        fun failure(message: String): Result<DownloadLink> =
            Result.failure(IllegalStateException(message))
    }
}

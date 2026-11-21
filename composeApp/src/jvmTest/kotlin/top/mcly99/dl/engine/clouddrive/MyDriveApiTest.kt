package top.mcly99.dl.engine.clouddrive

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.model.DownloadLink
import top.mcly99.dl.data.network.model.ShareFile
import top.mcly99.dl.data.network.model.ShareSession
import top.mcly99.dl.data.repository.ShareResolveRepository

/**
 * P4 胶水层契约：能力矩阵、根目录语义、下载取链与「转存到我的网盘」三段式。
 * 全部零网络：适配器与 Repository 都注入 fake。
 */
class MyDriveApiTest {

    @Test
    fun all_ten_drives_are_registered_and_github_is_excluded() = runTest {
        val adapters = defaultMyDriveAdapters()
        assertEquals(10, adapters.size)
        assertFalse(adapters.containsKey(SharePlatform.GITHUB))
        adapters.forEach { (platform, adapter) -> assertEquals(platform, adapter.platform) }
        assertEquals(MyDriveCapabilities.NONE, DefaultMyDriveApi().capabilities(SharePlatform.GITHUB))
    }

    @Test
    fun capability_matrix_matches_the_ported_apis() {
        val api = DefaultMyDriveApi()
        assertTrue(api.capabilities(SharePlatform.QUARK).createShare)
        assertTrue(api.capabilities(SharePlatform.UC).quota)
        assertTrue(api.capabilities(SharePlatform.BAIDU).move)
        assertTrue(api.capabilities(SharePlatform.C139).createShare)
        assertTrue(api.capabilities(SharePlatform.PAN123).delete)
        // 115 的「个人网盘」没有取直链接口：getDownloadLink 恒抛 UnsupportedOperationException
        // （那套方法是分享链路用的），能力位必须如实声明 false —— 以前这里断言 true，
        // 于是按钮必然失败、UI 还提示「已加入下载」（P1-15）
        assertFalse(api.capabilities(SharePlatform.PAN115).download)
        assertTrue(api.capabilities(SharePlatform.QUARK).download)
        assertTrue(api.capabilities(SharePlatform.XUNLEI).saveShare)
        assertTrue(api.capabilities(SharePlatform.GUANGYA).createFolder)

        // 两个真实缺口：蓝奏云没有配额 / 转存，蓝奏优享没有创建分享
        val lanzou = api.capabilities(SharePlatform.LANZOU)
        assertFalse(lanzou.quota)
        assertFalse(lanzou.saveShare)
        assertTrue(lanzou.createShare)
        val ilanzou = api.capabilities(SharePlatform.ILANZOU)
        assertFalse(ilanzou.createShare)
        assertTrue(ilanzou.saveShare)
    }

    @Test
    fun root_dir_ids_follow_each_platforms_own_semantics() {
        val api = DefaultMyDriveApi()
        assertEquals("/", api.rootDirId(SharePlatform.BAIDU))
        assertEquals("-1", api.rootDirId(SharePlatform.LANZOU))
        assertEquals("0", api.rootDirId(SharePlatform.ILANZOU))
        assertEquals("0", api.rootDirId(SharePlatform.PAN115))
        assertEquals("", api.rootDirId(SharePlatform.GUANGYA))
        assertEquals("", api.rootDirId(SharePlatform.GITHUB))
    }

    @Test
    fun list_delegates_to_the_platform_adapter() = runTest {
        val adapter = FakeAdapter(SharePlatform.QUARK)
        val api = DefaultMyDriveApi(adapters = mapOf(SharePlatform.QUARK to adapter))
        val page = api.list(DriveContext(SharePlatform.QUARK, "cookie"), "root", null)
        assertEquals(2, page.files.size)
        assertEquals("dir-1", page.files.first().id)
        assertEquals("root", adapter.lastDirId)
    }

    @Test
    fun an_unsupported_action_fails_loudly_with_the_platform_name() = runTest {
        val adapter = FakeAdapter(SharePlatform.QUARK, MyDriveCapabilities.BROWSE_ONLY)
        val api = DefaultMyDriveApi(adapters = mapOf(SharePlatform.QUARK to adapter))
        val e = assertFailsWith<MyDriveUnsupportedException> {
            api.delete(DriveContext(SharePlatform.QUARK, "cookie"), emptyList())
        }
        assertTrue(e.message!!.contains("QUARK"))
    }

    @Test
    fun download_link_wraps_repository_result_with_platform_headers() = runTest {
        val repo = FakeRepo()
        val api = driveApi(SharePlatform.QUARK, repo)
        val link = api.downloadLink(
            DriveContext(SharePlatform.QUARK, "cookie-main"),
            file("a.mp4"),
        )
        assertNotNull(link)
        assertEquals("https://cdn.example.com/a.mp4", link.url)
        assertEquals("a.mp4", link.filename)
        assertEquals(2048L, link.size)
        assertEquals(SharePlatform.QUARK, link.platform)
        assertTrue(link.cookie.isNotBlank(), "夸克直链必须带登录态 Cookie")
        assertEquals("f-a.mp4", repo.lastFid)
    }

    @Test
    fun download_link_prefers_the_size_the_user_saw_in_the_file_list() = runTest {
        val repo = FakeRepo().apply { downloadSize = 6_832L }
        val api = driveApi(SharePlatform.QUARK, repo)
        val ctx = DriveContext(SharePlatform.QUARK, "cookie-main")

        val listed = api.downloadLink(ctx, file("big.mp4", size = 239_880_000L))
        assertNotNull(listed)
        assertEquals(
            239_880_000L,
            listed.size,
            "取链接口返回 6.83 KB 占位值时，必须沿用列表里的 239.88 MB",
        )

        val unknown = api.downloadLink(ctx, file("unknown.mp4", size = 0L))
        assertNotNull(unknown)
        assertEquals(6_832L, unknown.size, "列表没给大小时才回落到取链值")
    }

    @Test
    fun download_is_skipped_for_folders_and_platforms_without_the_capability() = runTest {
        val repo = FakeRepo()
        val noDownload = FakeAdapter(SharePlatform.UC, MyDriveCapabilities(list = true))
        val api = DefaultMyDriveApi(
            adapters = mapOf(SharePlatform.UC to noDownload),
            shareRepository = { _, _ -> repo },
        )
        assertNull(api.downloadLink(DriveContext(SharePlatform.UC, "cookie"), file("a.mp4")))
        assertEquals(0, repo.downloadCalls)

        val quarkApi = driveApi(SharePlatform.QUARK, repo)
        assertNull(quarkApi.downloadLink(
            DriveContext(SharePlatform.QUARK, "cookie"),
            file("folder", isDir = true),
        ))
        assertEquals(0, repo.downloadCalls)
    }

    @Test
    fun save_share_uses_session_list_transfer_and_reports_partial_failure() = runTest {
        val repo = FakeRepo().apply {
            files = listOf(shareFile("a"), shareFile("b"), shareFile("c"))
            failOn = setOf("f-b")
        }
        val api = driveApi(SharePlatform.QUARK, repo)
        val result = api.saveShare(
            DriveContext(SharePlatform.QUARK, "cookie-main"),
            "https://pan.quark.cn/s/abc", "x1y2", "dir-target",
        )
        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().saved)
        assertEquals(1, result.getOrThrow().failed)
        assertEquals("https://pan.quark.cn/s/abc", repo.lastLink)
        assertEquals("x1y2", repo.lastPasscode)
        assertEquals("dir-target", repo.lastToDir)
        assertEquals(3, repo.transferCalls)
    }

    @Test
    fun save_share_fails_when_every_transfer_fails_and_succeeds_on_empty_share() = runTest {
        val repo = FakeRepo().apply {
            files = listOf(shareFile("a"), shareFile("b"))
            failOn = setOf("f-a", "f-b")
        }
        val api = driveApi(SharePlatform.QUARK, repo)
        val failed = api.saveShare(
            DriveContext(SharePlatform.QUARK, "cookie"), "https://pan.quark.cn/s/abc", "", "0",
        )
        assertTrue(failed.isFailure)

        repo.files = emptyList()
        repo.failOn = emptySet()
        val callsBefore = repo.transferCalls
        val empty = api.saveShare(
            DriveContext(SharePlatform.QUARK, "cookie"), "https://pan.quark.cn/s/abc", "", "",
        )
        assertEquals(0, empty.getOrThrow().saved)
        assertEquals(callsBefore, repo.transferCalls, "空分享不该发起任何转存请求")

        // 目标目录为空时确实回落到平台根目录（需要有文件才会触发 transferFile）
        repo.files = listOf(shareFile("a"))
        api.saveShare(
            DriveContext(SharePlatform.QUARK, "cookie"), "https://pan.quark.cn/s/abc", "", "",
        )
        assertEquals(api.rootDirId(SharePlatform.QUARK), repo.lastToDir, "目标目录为空时回落平台根目录")
    }

    @Test
    fun save_share_is_rejected_on_lanzou_without_touching_the_network() = runTest {
        val repo = FakeRepo()
        val api = DefaultMyDriveApi(shareRepository = { _, _ -> repo })
        val result = api.saveShare(
            DriveContext(SharePlatform.LANZOU, "cookie"), "https://lanzou.com/xxx", "", "-1",
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MyDriveUnsupportedException)
        assertEquals(0, repo.sessionCalls)
    }

    @Test
    fun projection_keeps_the_raw_share_file_for_platforms_that_need_it() {
        val raw = shareFile("a")
        val projected = raw.toMyDriveFile()
        assertEquals(raw.fid, projected.id)
        assertEquals(raw.fname, projected.name)
        assertEquals(raw.fsize, projected.size)
        assertEquals(raw.isdir, projected.isDir)
        assertEquals(raw, projected.raw, "123 云盘的删除接口要整份 ShareFile")
    }

    @Test
    fun quota_fraction_is_clamped_against_bad_server_numbers() {
        assertEquals(0.5f, MyDriveQuota(5L, 10L).fraction)
        assertEquals(0f, MyDriveQuota(5L, 0L).fraction)
        assertEquals(1f, MyDriveQuota(50L, 10L).fraction)
    }

    // ---------------------------------------------------------------- 辅助

    private fun driveApi(platform: SharePlatform, repo: FakeRepo) = DefaultMyDriveApi(
        adapters = mapOf(platform to FakeAdapter(platform, fullCaps())),
        shareRepository = { _, _ -> repo },
    )

    private fun fullCaps() = MyDriveCapabilities(
        list = true, createFolder = true, rename = true, move = true, delete = true,
        createShare = true, quota = true, download = true, saveShare = true,
    )

    private fun file(name: String, isDir: Boolean = false, size: Long = 2048L) = MyDriveFile(
        id = "f-$name", name = name, size = size, isDir = isDir,
    )

    private fun shareFile(name: String) = ShareFile(
        fid = "f-$name", fname = name, fsize = 1024L, isdir = false,
        pdirFid = "0", fidToken = "token-$name",
    )

    private class FakeAdapter(
        override val platform: SharePlatform,
        override val capabilities: MyDriveCapabilities = MyDriveCapabilities.BROWSE_ONLY,
        override val rootDirId: String = "root",
    ) : MyDrivePlatformAdapter {
        var lastDirId: String = ""
            private set

        override suspend fun list(ctx: DriveContext, dirId: String, cursor: String?): MyDrivePage {
            lastDirId = dirId
            return MyDrivePage(
                listOf(
                    MyDriveFile("dir-1", "文件夹", isDir = true),
                    MyDriveFile("f-1", "a.mp4", size = 10L),
                ),
            )
        }
    }

    private class FakeRepo : ShareResolveRepository {
        var files: List<ShareFile> = emptyList()
        var failOn: Set<String> = emptySet()
        var downloadSize = 2048L
        var downloadCalls = 0
            private set
        var sessionCalls = 0
            private set
        var transferCalls = 0
            private set
        var lastFid: String = ""
            private set
        var lastLink: String = ""
            private set
        var lastPasscode: String = ""
            private set
        var lastToDir: String = ""
            private set

        override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> {
            sessionCalls++
            lastLink = link
            lastPasscode = pwd.orEmpty()
            return Result.success(ShareSession("share-1", "stoken", "测试分享"))
        }

        override suspend fun listFiles(
            session: ShareSession, dirFid: String, cookie: String,
        ): Result<List<ShareFile>> = Result.success(files)

        override suspend fun ensureTempDir(cookie: String): Result<String> = Result.success("tmp")

        override suspend fun transferFile(
            session: ShareSession, file: ShareFile, toDirFid: String,
            cookie: String, skipSpaceCheck: Boolean,
        ): Result<String> {
            transferCalls++
            lastToDir = toDirFid
            return if (file.fid in failOn) Result.failure(IllegalStateException("转存失败")) else Result.success(file.fid)
        }

        override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> {
            downloadCalls++
            lastFid = fid
            return Result.success(
                DownloadLink(
                    fid = fid,
                    filename = fid.removePrefix("f-"),
                    downloadUrl = "https://cdn.example.com/${fid.removePrefix("f-")}",
                    size = downloadSize,
                ),
            )
        }

        override suspend fun getShareDownloadLink(
            session: ShareSession, file: ShareFile, cookie: String, skipSpaceCheck: Boolean,
        ): Result<DownloadLink> = Result.failure(IllegalStateException("not used here"))
    }
}

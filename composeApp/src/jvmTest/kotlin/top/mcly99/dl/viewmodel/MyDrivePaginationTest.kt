package top.mcly99.dl.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.engine.clouddrive.CloudDriveAccountStore
import top.mcly99.dl.engine.clouddrive.CloudLink
import top.mcly99.dl.engine.clouddrive.DriveContext
import top.mcly99.dl.engine.clouddrive.InMemoryCloudCredentialStorage
import top.mcly99.dl.engine.clouddrive.MyDriveApi
import top.mcly99.dl.engine.clouddrive.MyDriveCapabilities
import top.mcly99.dl.engine.clouddrive.MyDriveFile
import top.mcly99.dl.engine.clouddrive.MyDrivePage
import top.mcly99.dl.engine.clouddrive.MyDriveQuota
import top.mcly99.dl.engine.clouddrive.MyDriveSaveResult
import top.mcly99.dl.engine.clouddrive.MyDriveShareResult
import top.mcly99.dl.theme.myDriveStrings

/**
 * 分页：139 / 123 的列目录接口是分页的，早先只取第一页 ——
 * 第二页之后的文件在「我的网盘」里既看不到也选不中，属于能力缺失而不是显示问题。
 */
class MyDrivePaginationTest {

    private fun TestScope.viewModel(api: MyDriveApi): MyDriveViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
        store.set(SharePlatform.C139, "cookie")
        return MyDriveViewModel(
            api = api,
            accounts = store,
            scope = CoroutineScope(dispatcher + SupervisorJob()),
            ioDispatcher = dispatcher,
            stringsProvider = { myDriveStrings("zh-CN") },
        )
    }

    @Test
    fun load_more_appends_the_next_page_and_drops_duplicates() = runTest {
        val api = PagedApi()
        val vm = viewModel(api)
        vm.selectPlatform(SharePlatform.C139)
        advanceUntilIdle()

        assertEquals(listOf("f-1", "d-1"), vm.files.map { it.id })
        assertTrue(vm.hasMore)
        assertEquals(listOf<String?>(null), api.cursors)

        vm.loadMore()
        advanceUntilIdle()

        // 第二页里的 f-1 与第一页重复：按 id 去重，不能出现两条同名条目
        assertEquals(listOf("f-1", "d-1", "f-2", "d-2"), vm.files.map { it.id })
        assertFalse(vm.hasMore)
        assertEquals(listOf<String?>(null, "p2"), api.cursors)
    }

    @Test
    fun load_more_without_a_cursor_does_not_hit_the_api() = runTest {
        val api = PagedApi()
        val vm = viewModel(api)
        vm.selectPlatform(SharePlatform.C139)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        val calls = api.cursors.size

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(calls, api.cursors.size, "没有下一页时不该再发请求")
    }

    @Test
    fun entering_a_folder_restarts_from_the_first_page() = runTest {
        val api = PagedApi()
        val vm = viewModel(api)
        vm.selectPlatform(SharePlatform.C139)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()

        vm.openDir(vm.files.first { it.isDir })
        advanceUntilIdle()

        // 新目录必须从游标 null 开始：沿用上一目录的游标会取到别人的第二页
        assertNull(api.lastCursorFor("dir-1"), "进入新目录必须从第一页开始")
        assertEquals(listOf("f-1", "d-1"), vm.files.map { it.id })
    }

    @Test
    fun move_dialog_collects_folder_pages_too() = runTest {
        val api = PagedApi()
        val vm = viewModel(api)
        vm.selectPlatform(SharePlatform.C139)
        advanceUntilIdle()
        vm.toggleSelect(vm.files.first { !it.isDir }.id)

        vm.openMove()
        advanceUntilIdle()

        // 根目录第一页只有 d-1，第二页才有 d-2；翻页后两个都要能选
        assertEquals(listOf("d-1", "d-2"), vm.moveFolders.map { it.id })
    }

    @Test
    fun a_failed_page_keeps_the_files_already_loaded() = runTest {
        val api = PagedApi().apply { failOnCursor = "p2" }
        val vm = viewModel(api)
        vm.selectPlatform(SharePlatform.C139)
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("f-1", "d-1"), vm.files.map { it.id }, "失败不该清空已取到的文件")
        assertTrue(vm.notice?.contains("boom") == true)
    }

    private class PagedApi : MyDriveApi {
        val cursors = mutableListOf<String?>()
        var failOnCursor: String? = null
        private val dirCursors = mutableMapOf<String, String?>()

        fun lastCursorFor(dirId: String): String? = dirCursors[dirId]

        override fun capabilities(platform: SharePlatform) = MyDriveCapabilities(list = true, move = true)
        override fun rootDirId(platform: SharePlatform): String = "0"

        override suspend fun list(ctx: DriveContext, dirId: String, cursor: String?): MyDrivePage {
            cursors += cursor
            dirCursors[dirId] = cursor
            if (failOnCursor != null && cursor == failOnCursor) error("boom")
            return when (cursor) {
                null -> MyDrivePage(
                    listOf(
                        MyDriveFile("f-1", "a.mp4", size = 1L, parentId = dirId),
                        MyDriveFile("d-1", "目录一", isDir = true, parentId = dirId),
                    ),
                    nextCursor = "p2",
                )
                "p2" -> MyDrivePage(
                    listOf(
                        MyDriveFile("f-1", "a.mp4", size = 1L, parentId = dirId),
                        MyDriveFile("f-2", "b.mp4", size = 2L, parentId = dirId),
                        MyDriveFile("d-2", "目录二", isDir = true, parentId = dirId),
                    ),
                    nextCursor = null,
                )
                else -> MyDrivePage(emptyList(), null)
            }
        }

        override suspend fun createFolder(ctx: DriveContext, parentId: String, name: String): String? = null
        override suspend fun rename(ctx: DriveContext, file: MyDriveFile, newName: String) = true
        override suspend fun move(ctx: DriveContext, files: List<MyDriveFile>, toParentId: String) = true
        override suspend fun delete(ctx: DriveContext, files: List<MyDriveFile>) = true
        override suspend fun createShare(
            ctx: DriveContext, files: List<MyDriveFile>, passcode: String, expiredType: Int,
        ): MyDriveShareResult? = null
        override suspend fun quota(ctx: DriveContext): MyDriveQuota? = null
        override suspend fun downloadLink(ctx: DriveContext, file: MyDriveFile): CloudLink? = null
        override suspend fun saveShare(
            ctx: DriveContext, link: String, passcode: String, toDirId: String,
        ): Result<MyDriveSaveResult> = Result.success(MyDriveSaveResult(0, 0))
    }
}

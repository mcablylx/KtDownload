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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.model.ShareExpire
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

/** P4 状态机：目录栈、能力位隐藏、删除二次确认、转存与账号切换。全部零网络。 */
class MyDriveViewModelTest {

    private fun TestScope.viewModel(
        api: MyDriveApi = FakeMyDriveApi(),
        accounts: CloudDriveAccountStore = loggedInAccounts(),
    ): MyDriveViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return MyDriveViewModel(
            api = api,
            accounts = accounts,
            scope = CoroutineScope(dispatcher + SupervisorJob()),
            ioDispatcher = dispatcher,
            stringsProvider = { myDriveStrings("zh-CN") },
        )
    }

    private fun loggedInAccounts(): CloudDriveAccountStore {
        val store = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
        store.set(SharePlatform.QUARK, "cookie-main")
        return store
    }

    @Test
    fun selecting_a_platform_loads_its_root_folder() = runTest {
        val api = FakeMyDriveApi()
        val vm = viewModel(api)
        vm.selectPlatform(SharePlatform.QUARK)
        advanceUntilIdle()

        assertEquals(SharePlatform.QUARK, vm.platform)
        assertEquals("0", api.lastDirId)
        assertEquals(2, vm.files.size)
        assertTrue(vm.isLoggedIn)
        assertEquals(MyDriveQuota(5L, 10L), vm.quota)
        assertEquals("0", vm.currentDirId)
    }

    @Test
    fun folder_navigation_uses_a_path_stack_and_go_up_restores_the_parent() = runTest {
        val api = FakeMyDriveApi()
        val vm = viewModel(api)
        vm.selectPlatform(SharePlatform.QUARK)
        advanceUntilIdle()
        val folder = vm.files.first { it.isDir }

        vm.openDir(folder)
        advanceUntilIdle()
        assertEquals(folder.id, vm.currentDirId)
        assertEquals(listOf("子目录"), vm.pathStack.map { it.second })

        vm.goUp()
        advanceUntilIdle()
        assertEquals("0", vm.currentDirId)
        assertTrue(vm.pathStack.isEmpty())
    }

    @Test
    fun a_platform_without_credentials_never_calls_the_api() = runTest {
        val api = FakeMyDriveApi()
        val vm = viewModel(api, CloudDriveAccountStore(InMemoryCloudCredentialStorage()))
        vm.selectPlatform(SharePlatform.QUARK)
        advanceUntilIdle()

        assertEquals(0, api.listCalls)
        assertTrue(vm.files.isEmpty())
        assertFalse(vm.isLoggedIn)
        assertEquals(myDriveStrings("zh-CN").loginRequired, vm.error)
    }

    @Test
    fun signing_in_notifies_the_page_and_loads_files_without_a_manual_refresh() = runTest {
        val api = FakeMyDriveApi()
        val accounts = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
        val vm = viewModel(api, accounts)
        vm.selectPlatform(SharePlatform.QUARK)
        advanceUntilIdle()
        assertTrue(vm.files.isEmpty())
        assertFalse(vm.isLoggedIn)
        val beforeRevision = vm.accountsRevision

        // 「云盘」页登录成功后往同一个账号池写凭证，再发通知
        accounts.set(SharePlatform.QUARK, "cookie-after-login")
        vm.onAccountsChanged()
        advanceUntilIdle()

        assertTrue(vm.isLoggedIn)
        assertTrue(vm.accountsRevision > beforeRevision)
        assertEquals(2, vm.files.size)
        assertNull(vm.error)
        assertTrue(api.listCalls > 0)
    }

    @Test
    fun capability_flags_hide_actions_instead_of_failing_late() = runTest {
        val api = FakeMyDriveApi().apply { caps = MyDriveCapabilities(list = true) }
        val vm = viewModel(api)
        vm.selectPlatform(SharePlatform.QUARK)
        advanceUntilIdle()
        vm.selectAll()

        assertFalse(vm.canCreateFolder)
        assertFalse(vm.canRename)
        assertFalse(vm.canMove)
        assertFalse(vm.canDelete)
        assertFalse(vm.canShare)
        assertFalse(vm.canDownload)
        assertFalse(vm.canSaveShare)
        vm.openDelete()
        vm.confirmDelete()
        vm.openCreateFolder()
        vm.submitCreateFolder("x")
        advanceUntilIdle()
        assertEquals(0, api.createFolderCalls)
        assertEquals(0, api.deleteCalls)
    }

    @Test
    fun delete_requires_an_explicit_confirmation_dialog() = runTest {
        val api = FakeMyDriveApi()
        val vm = viewModel(api)
        vm.selectPlatform(SharePlatform.QUARK)
        advanceUntilIdle()
        vm.toggleSelect(vm.files.first { !it.isDir }.id)

        vm.openDelete()
        assertIs<MyDriveDialog.Delete>(vm.dialog)
        assertEquals(0, api.deleteCalls, "只打开确认框不该删")
        vm.confirmDelete()
        advanceUntilIdle()
        assertEquals(1, api.deleteCalls)
        assertEquals(MyDriveDialog.None, vm.dialog)
        assertTrue(vm.selectedIds.isEmpty())
        assertEquals(2, api.listCalls, "删完要刷新目录")
    }

    @Test
    fun deleting_files_refreshes_the_quota_shown_above_the_list() = runTest {
        val api = FakeMyDriveApi()
        // 模拟服务端在删除后已经释放空间：界面必须重新取一次才能看到新值
        api.quotaAfterDelete = MyDriveQuota(1L, 10L)
        val vm = viewModel(api)
        vm.selectPlatform(SharePlatform.QUARK)
        advanceUntilIdle()
        assertEquals(MyDriveQuota(5L, 10L), vm.quota)
        val quotaCallsBeforeDelete = api.quotaCalls

        vm.toggleSelect(vm.files.first { !it.isDir }.id)
        vm.openDelete()
        vm.confirmDelete()
        advanceUntilIdle()

        assertEquals(1, api.deleteCalls)
        assertEquals(quotaCallsBeforeDelete + 1, api.quotaCalls)
        assertEquals(MyDriveQuota(1L, 10L), vm.quota)
    }

    @Test
    fun move_dialog_navigates_subfolders_without_moving_the_main_page() = runTest {
        val api = FakeMyDriveApi()
        val vm = viewModel(api)
        vm.selectPlatform(SharePlatform.QUARK)
        advanceUntilIdle()
        vm.toggleSelect(vm.files.first { !it.isDir }.id)
        vm.openMove()
        advanceUntilIdle()

        assertIs<MyDriveDialog.Move>(vm.dialog)
        assertEquals("0", vm.moveCurrentDirId)
        assertTrue(vm.moveFolders.isNotEmpty())
        vm.openMoveFolder(vm.moveFolders.first())
        advanceUntilIdle()
        assertEquals("dir-1", vm.moveCurrentDirId)
        assertEquals("0", vm.currentDirId, "移动对话框的目录栈不能改主页面位置")
        vm.moveGoUp()
        advanceUntilIdle()
        assertEquals("0", vm.moveCurrentDirId)

        vm.confirmMove()
        advanceUntilIdle()
        assertEquals(1, api.moveCalls)
        assertEquals("0", api.lastToDirId)
    }

    @Test
    fun create_folder_rename_and_share_all_refresh_or_open_the_result() = runTest {
        val api = FakeMyDriveApi()
        val vm = viewModel(api)
        vm.selectPlatform(SharePlatform.QUARK)
        advanceUntilIdle()

        vm.openCreateFolder()
        vm.submitCreateFolder("新目录")
        advanceUntilIdle()
        assertEquals("新目录", api.lastFolderName)
        assertEquals(MyDriveDialog.None, vm.dialog)

        val target = vm.files.first { !it.isDir }
        vm.toggleSelect(target.id)
        vm.openRename()
        vm.submitRename(target, "改名.mp4")
        advanceUntilIdle()
        assertEquals("改名.mp4", api.lastNewName)

        vm.openShare()
        vm.submitShare(listOf(target), "x1y2", ShareExpire.SEVEN_DAYS)
        advanceUntilIdle()
        val result = vm.dialog
        assertIs<MyDriveDialog.ShareResult>(result)
        assertEquals("https://pan.quark.cn/s/abc", result.result.url)
        assertEquals(ShareExpire.SEVEN_DAYS, api.lastExpiredType)
        assertEquals("x1y2", api.lastPasscode)
    }

    @Test
    fun save_share_refreshes_the_folder_and_reports_partial_success() = runTest {
        val api = FakeMyDriveApi().apply {
            saveResult = MyDriveSaveResult(saved = 2, failed = 1)
        }
        val vm = viewModel(api)
        vm.selectPlatform(SharePlatform.QUARK)
        advanceUntilIdle()

        vm.openSaveShare()
        vm.submitSaveShare("https://pan.quark.cn/s/abc", "x1y2")
        advanceUntilIdle()
        assertEquals(2, api.listCalls)
        assertEquals(MyDriveDialog.None, vm.dialog)
        assertTrue(vm.notice!!.contains("2"))
        assertTrue(vm.notice!!.contains("1"))
    }

    @Test
    fun downloading_selected_files_uses_the_injected_enqueue_callback() = runTest {
        val api = FakeMyDriveApi()
        val vm = viewModel(api)
        vm.selectPlatform(SharePlatform.QUARK)
        advanceUntilIdle()
        val downloaded = mutableListOf<String>()
        vm.onDownload = { link -> downloaded += link.url; "task-1" }
        vm.selectAll()
        vm.downloadSelected()
        advanceUntilIdle()

        assertEquals(1, downloaded.size, "目录不应被加入下载")
        assertTrue(downloaded.single().endsWith("a.mp4"))
    }

    @Test
    fun switching_to_a_non_primary_account_uses_that_accounts_credential() = runTest {
        val accounts = loggedInAccounts()
        val secondId = accounts.addAccount(SharePlatform.QUARK, "cookie-second", "小号")!!
        val api = FakeMyDriveApi()
        val vm = viewModel(api, accounts)
        vm.selectPlatform(SharePlatform.QUARK)
        advanceUntilIdle()
        assertEquals("cookie-main", api.lastCredential)

        vm.selectAccount(secondId)
        advanceUntilIdle()
        assertEquals("cookie-second", api.lastCredential)
        assertEquals(secondId, vm.accountId)
    }

    @Test
    fun switching_platform_clears_selection_and_path() = runTest {
        val api = FakeMyDriveApi()
        val vm = viewModel(api)
        vm.selectPlatform(SharePlatform.QUARK)
        advanceUntilIdle()
        vm.selectAll()
        assertTrue(vm.selectedIds.isNotEmpty())
        vm.openDir(vm.files.first { it.isDir })
        advanceUntilIdle()
        assertTrue(vm.pathStack.isNotEmpty())

        vm.selectPlatform(SharePlatform.UC)
        advanceUntilIdle()
        assertTrue(vm.selectedIds.isEmpty())
        assertTrue(vm.pathStack.isEmpty())
        assertNull(vm.accountId)
    }

    @Test
    fun shutdown_cancels_the_view_model_scope() = runTest {
        val vm = viewModel()
        vm.shutdown()
        // 不抛异常即可；取消后不应再发生调度
        assertEquals(MyDriveDialog.None, vm.dialog)
    }

    // ---------------------------------------------------------------- 辅助

    private class FakeMyDriveApi : MyDriveApi {
        var caps: MyDriveCapabilities = MyDriveCapabilities(
            list = true, createFolder = true, rename = true, move = true, delete = true,
            createShare = true, quota = true, download = true, saveShare = true,
        )
        var saveResult: MyDriveSaveResult = MyDriveSaveResult(3, 0)
        var quotaValue = MyDriveQuota(5L, 10L)
        var quotaAfterDelete: MyDriveQuota? = null
        var listCalls = 0
            private set
        var quotaCalls = 0
            private set
        var deleteCalls = 0
            private set
        var createFolderCalls = 0
            private set
        var moveCalls = 0
            private set
        var lastDirId: String = ""
            private set
        var lastCredential: String = ""
            private set
        var lastFolderName: String = ""
            private set
        var lastNewName: String = ""
            private set
        var lastPasscode: String = ""
            private set
        var lastExpiredType: Int = -1
            private set
        var lastToDirId: String = ""
            private set

        private fun remember(ctx: DriveContext, dirId: String) {
            lastCredential = ctx.credential
            lastDirId = dirId
        }

        override fun capabilities(platform: SharePlatform): MyDriveCapabilities = caps
        override fun rootDirId(platform: SharePlatform): String = "0"

        override suspend fun list(ctx: DriveContext, dirId: String, cursor: String?): MyDrivePage {
            listCalls++
            remember(ctx, dirId)
            return MyDrivePage(
                listOf(
                    MyDriveFile("dir-1", "子目录", isDir = true, parentId = dirId),
                    MyDriveFile("f-1", "a.mp4", size = 2048L, parentId = dirId),
                ),
            )
        }

        override suspend fun createFolder(ctx: DriveContext, parentId: String, name: String): String? {
            createFolderCalls++
            lastFolderName = name
            return "new-dir"
        }

        override suspend fun rename(ctx: DriveContext, file: MyDriveFile, newName: String): Boolean {
            lastNewName = newName
            return true
        }

        override suspend fun move(ctx: DriveContext, files: List<MyDriveFile>, toParentId: String): Boolean {
            moveCalls++
            lastToDirId = toParentId
            return true
        }

        override suspend fun delete(ctx: DriveContext, files: List<MyDriveFile>): Boolean {
            deleteCalls++
            quotaAfterDelete?.let { quotaValue = it }
            return true
        }

        override suspend fun createShare(
            ctx: DriveContext, files: List<MyDriveFile>, passcode: String, expiredType: Int,
        ): MyDriveShareResult {
            lastPasscode = passcode
            lastExpiredType = expiredType
            return MyDriveShareResult("https://pan.quark.cn/s/abc", passcode, files.first().name)
        }

        override suspend fun quota(ctx: DriveContext): MyDriveQuota {
            quotaCalls++
            return quotaValue
        }

        override suspend fun downloadLink(ctx: DriveContext, file: MyDriveFile): CloudLink? {
            if (file.isDir) return null
            return CloudLink(
                url = "https://cdn.example.com/${file.name}",
                filename = file.name,
                size = file.size,
                cookie = ctx.credential,
                referer = "https://pan.quark.cn/",
                platform = ctx.platform,
            )
        }

        override suspend fun saveShare(
            ctx: DriveContext, link: String, passcode: String, toDirId: String,
        ): Result<MyDriveSaveResult> = Result.success(saveResult)
    }
}

package top.mcly99.dl.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 「我的网盘」列目录请求的代次校验（P0-3）。
 *
 * 旧实现在慢网下有两个后果：
 * 1. 先点平台 A 再点平台 B，A 的响应回来后会写进 B 的列表（显示串台）；
 * 2. 更糟的是后续删除 / 移动用 B 的凭证（`ctx()` 现取当前平台）配 A 的文件 id ——
 *    各平台 id 语义不同，可能命中别人的文件。
 *
 * 这些用例用「可控放行的假 API」把时序钉死：请求发出后可以一直挂着，由测试决定谁先返回。
 */
class MyDriveGenerationTest {

    private fun TestScope.viewModel(api: MyDriveApi): MyDriveViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
        // 两个平台都要有凭证，否则 loadDir 会走「请先登录」分支、根本不会发请求
        listOf(SharePlatform.C139, SharePlatform.PAN123).forEach { store.set(it, "cookie-${it.name}") }
        return MyDriveViewModel(
            api = api,
            accounts = store,
            scope = CoroutineScope(dispatcher + SupervisorJob()),
            ioDispatcher = dispatcher,
            stringsProvider = { myDriveStrings("zh-CN") },
        )
    }

    @Test
    fun a_slow_response_from_the_previous_platform_never_lands_in_the_new_list() = runTest {
        val api = GatedApi()
        val vm = viewModel(api)

        vm.selectPlatform(SharePlatform.C139)
        advanceUntilIdle() // 139 的请求已发出，卡在闸门上

        // 用户等不及，换了平台
        vm.selectPlatform(SharePlatform.PAN123)
        advanceUntilIdle()

        // 新平台的响应先回来
        api.release("123-root", null)
        advanceUntilIdle()
        assertEquals(listOf("file@123-root"), vm.files.map { it.id })

        // 旧平台的响应姗姗来迟：必须整份丢弃
        api.release("0", null)
        advanceUntilIdle()
        assertEquals(
            listOf("file@123-root"),
            vm.files.map { it.id },
            "旧平台的响应覆盖了新平台的列表 —— 用户会看到两个平台的文件混在一起",
        )
    }

    @Test
    fun a_page_returning_after_the_user_opens_another_folder_is_dropped() = runTest {
        val api = GatedApi()
        val vm = viewModel(api)

        vm.selectPlatform(SharePlatform.C139)
        api.release("0", null)
        advanceUntilIdle()
        assertEquals(listOf("file@0"), vm.files.map { it.id })

        // 点「加载更多」：这一页会取很久
        vm.loadMore()
        advanceUntilIdle()

        // 翻页还没回来，用户进了子目录
        vm.openDir(MyDriveFile("sub", "子目录", isDir = true, parentId = "0"))
        advanceUntilIdle()

        // 旧目录的第二页现在才回来
        api.release("0", "p2")
        advanceUntilIdle()
        assertFalse(
            vm.files.any { it.id.startsWith("stale@") },
            "旧目录的第二页被拼进了新目录的列表",
        )

        api.release("sub", null)
        advanceUntilIdle()
        assertEquals(listOf("file@sub"), vm.files.map { it.id })
        assertFalse(vm.hasMore, "子目录没有下一页，游标不能被旧请求写回")
    }

    @Test
    fun a_stale_failure_does_not_overwrite_the_new_folder_with_an_error() = runTest {
        val api = GatedApi()
        val vm = viewModel(api)

        vm.selectPlatform(SharePlatform.C139)
        advanceUntilIdle() // 挂在闸门上

        // 换成另一个平台后，旧请求以异常收场（超时 / 连接被断）
        vm.selectPlatform(SharePlatform.PAN123)
        advanceUntilIdle()
        api.release("123-root", null)
        advanceUntilIdle()

        api.fail("0", null)
        advanceUntilIdle()

        assertEquals(null, vm.error, "旧请求的失败不该让新目录显示错误")
        assertEquals(listOf("file@123-root"), vm.files.map { it.id })
    }

    /**
     * 可控放行的假 API：`list` 会一直挂起，直到测试显式 [release] / [fail]。
     *
     * key 用 `目录id|cursor`，这样同一个目录的第一页和翻页可以分别控制。
     */
    private class GatedApi : MyDriveApi {
        private val gates = mutableMapOf<String, CompletableDeferred<Result<Unit>>>()

        private fun gate(dirId: String, cursor: String?) =
            gates.getOrPut("$dirId|$cursor") { CompletableDeferred() }

        fun release(dirId: String, cursor: String?) {
            gate(dirId, cursor).complete(Result.success(Unit))
        }

        fun fail(dirId: String, cursor: String?) {
            gate(dirId, cursor).complete(Result.failure(IllegalStateException("timeout")))
        }

        override fun capabilities(platform: SharePlatform) =
            MyDriveCapabilities(list = true, move = true)

        override fun rootDirId(platform: SharePlatform): String = when (platform) {
            SharePlatform.PAN123 -> "123-root"
            else -> "0"
        }

        override suspend fun list(ctx: DriveContext, dirId: String, cursor: String?): MyDrivePage {
            gate(dirId, cursor).await().getOrThrow()
            return when (cursor) {
                null -> MyDrivePage(
                    listOf(MyDriveFile("file@$dirId", "$dirId.mp4", size = 1L, parentId = dirId)),
                    nextCursor = if (dirId == "0") "p2" else null,
                )
                else -> MyDrivePage(
                    listOf(MyDriveFile("stale@$dirId", "旧页.mp4", size = 1L, parentId = dirId)),
                    nextCursor = null,
                )
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

package top.mcly99.dl.viewmodel

import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.model.ShareFile
import top.mcly99.dl.data.network.model.ShareSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import top.mcly99.dl.engine.clouddrive.CloudDriveAccountStore
import top.mcly99.dl.engine.clouddrive.InMemoryCloudCredentialStorage
import top.mcly99.dl.engine.clouddrive.CloudDriveApi
import top.mcly99.dl.engine.clouddrive.CloudDriveService
import top.mcly99.dl.engine.clouddrive.CloudLink
import top.mcly99.dl.engine.clouddrive.CloudLinkEntry
import top.mcly99.dl.engine.clouddrive.CloudLinkLibrary
import top.mcly99.dl.engine.clouddrive.CloudQuota
import top.mcly99.dl.engine.clouddrive.CloudTreeResult
import top.mcly99.dl.engine.clouddrive.collectTree
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

/**
 * 「云盘」状态机单测。
 *
 * 网络调用全部换成假实现；parse / extractPasscode / rootDirFid 仍委托给真实
 * [CloudDriveService]，确保「链接里带提取码自动填充」测的是真实正则。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudDriveViewModelTest {

    private val quarkLink = "https://pan.quark.cn/s/abc123"
    private val tempDirs = mutableListOf<Path>()

    /** 每个 ViewModel 用独立临时目录存解析历史，绝不写真实的 ~/.mclydownload */
    private fun newLinkLibrary(): CloudLinkLibrary = CloudLinkLibrary(
        Files.createTempDirectory("ktdown-vm-links").also { tempDirs.add(it) },
    )

    @AfterTest
    fun cleanupTempDirs() {
        tempDirs.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    @Test
    fun passcode_in_url_is_autofilled_and_cleared_when_removed() = runTest {
        val vm = viewModel(FakeApi())
        vm.updateUrl("$quarkLink?pwd=8k2f")
        assertEquals("8k2f", vm.passcode)

        // 用户删掉链接里的 pwd：只清掉自动填过的那份
        vm.updateUrl(quarkLink)
        assertEquals("", vm.passcode)
    }

    @Test
    fun manual_passcode_is_never_overwritten_by_url() = runTest {
        val vm = viewModel(FakeApi())
        vm.updateUrl(quarkLink)
        vm.updatePasscode("manual")
        vm.updateUrl("$quarkLink?pwd=8k2f")
        assertEquals("manual", vm.passcode)
    }

    @Test
    fun resolve_shows_session_and_root_files() = runTest {
        val api = FakeApi().apply {
            dirs["0"] = listOf(file("movie.mp4"), folder("剧集"))
        }
        val vm = viewModel(api)
        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()

        val success = assertIs<CloudDriveState.Success>(vm.state)
        assertEquals("夸克网盘", success.resolution.platformName)
        assertEquals(2, success.resolution.fileCount)
        assertEquals(2, vm.files.size)
        assertEquals(1, api.sessionCalls)
        assertTrue(vm.links.isEmpty())
    }

    @Test
    fun invalid_link_fails_without_hitting_network() = runTest {
        val api = FakeApi()
        val vm = viewModel(api)
        vm.updateUrl("这不是一个网盘链接")
        vm.resolve()
        advanceUntilIdle()

        assertIs<CloudDriveState.Failed>(vm.state)
        assertEquals(0, api.sessionCalls)
        assertEquals(1, api.commandCalls) // 问过 glue 层，但假实现没有发真实请求
    }

    @Test
    fun chinese_command_is_resolved_to_a_share_link_before_creating_a_session() = runTest {
        val api = FakeApi().apply { commandResult = Result.success(quarkLink) }
        val vm = viewModel(api)
        vm.updateUrl("绝对有效 咐置松君杂货铺叩苓")
        vm.resolve()
        advanceUntilIdle()

        val success = assertIs<CloudDriveState.Success>(vm.state)
        assertEquals(1, api.commandCalls)
        assertEquals(1, api.sessionCalls)
        assertEquals(quarkLink, api.lastSessionLink)
        assertEquals(quarkLink, success.resolution.link)
        assertEquals(SharePlatform.QUARK, success.resolution.platform)
        assertEquals("中文口令已解析为分享链接，可复制链接后重新解析", vm.notice)
    }

    @Test
    fun command_result_platform_drives_the_rest_of_the_resolve_chain() = runTest {
        val ucLink = "https://drive.uc.cn/s/uc123"
        val api = FakeApi().apply { commandResult = Result.success(ucLink) }
        val vm = viewModel(api)
        vm.updateUrl("咐置松君杂货铺叩苓")
        vm.resolve()
        advanceUntilIdle()

        val success = assertIs<CloudDriveState.Success>(vm.state)
        assertEquals(SharePlatform.UC, success.resolution.platform)
        assertEquals("UC 网盘", success.resolution.platformName)
        assertEquals(ucLink, api.lastSessionLink)
    }

    @Test
    fun command_service_failure_is_surfaced_without_creating_a_session() = runTest {
        val api = FakeApi().apply {
            commandResult = Result.failure(IllegalStateException("口令解析服务限流，请稍后再试"))
        }
        val vm = viewModel(api)
        vm.updateUrl("绝对有效 咐置松君杂货铺叩苓")
        vm.resolve()
        advanceUntilIdle()

        val failed = assertIs<CloudDriveState.Failed>(vm.state)
        assertTrue(failed.message.contains("口令解析服务限流"))
        assertEquals(0, api.sessionCalls)
        assertNull(vm.notice)
    }

    @Test
    fun folder_navigation_and_batch_download() = runTest {
        val api = FakeApi().apply {
            dirs["0"] = listOf(folder("剧集"), file("readme.txt"))
            dirs["dir-1"] = listOf(file("ep01.mp4"), file("ep02.mp4"))
        }
        val vm = viewModel(api)
        val downloaded = mutableListOf<String>()
        vm.onDownload = { downloaded += it.url; "task-${downloaded.size}" }

        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()
        assertEquals(2, vm.files.size)

        // 进入「剧集」→ 面包屑 + 子目录内容
        vm.openFolder(vm.files.first { it.isDir })
        advanceUntilIdle()
        assertEquals(listOf("剧集"), vm.pathNames)
        assertEquals(2, vm.files.size)

        // 全选 → 批量取链 → 两条都送进下载引擎
        vm.toggleSelectAll()
        assertEquals(2, vm.selected.size)
        vm.downloadSelected()
        advanceUntilIdle()
        assertEquals(2, downloaded.size)
        assertEquals(2, vm.links.size)
        assertEquals(2, api.linkCalls)
        assertEquals(2, vm.addedUrls.size)

        // 回到根目录
        vm.goUp()
        advanceUntilIdle()
        assertTrue(vm.pathNames.isEmpty())
        assertEquals(2, vm.files.size)
        assertTrue(vm.selected.isEmpty())
    }

    @Test
    fun clicks_during_resolve_do_not_cancel_it() = runTest {
        val api = FakeApi().apply { dirs["0"] = listOf(folder("剧集")) }
        val vm = viewModel(api)
        vm.updateUrl(quarkLink)
        vm.resolve()

        // 解析协程还没跑完：列表区的点击必须被忽略，不能把解析 Job cancel 掉，
        // 否则 state 会永远停在 Loading（旧实现里共用一个 job 就是这个问题）
        assertTrue(vm.busy)
        vm.goUp()
        vm.goToLevel(0)
        vm.downloadSelected()
        assertTrue(vm.notice == null)

        advanceUntilIdle()
        assertIs<CloudDriveState.Success>(vm.state)
        assertEquals(1, vm.files.size)
        assertEquals(0, api.linkCalls)
    }

    @Test
    fun cancel_during_resolve_resets_to_idle() = runTest {
        val vm = viewModel(FakeApi())
        vm.updateUrl(quarkLink)
        vm.resolve()
        vm.cancel()
        advanceUntilIdle()

        assertIs<CloudDriveState.Idle>(vm.state)
        assertTrue(!vm.busy)
    }

    // ---------------------------------------------------------------- 网盘列表（平台卡片）

    @Test
    fun platform_credential_is_saved_cleared_and_masked() = runTest {
        val accounts = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
        val vm = viewModel(FakeApi(), accounts)

        vm.openPlatform(SharePlatform.UC)
        assertEquals(SharePlatform.UC, vm.editingPlatform)
        vm.updateCredentialDraft("  uc-token-123456  ")
        vm.saveCredential()

        assertTrue(vm.isConfigured(SharePlatform.UC))
        assertEquals("uc-token-123456", accounts.credentialFor(SharePlatform.UC))
        assertEquals("uc-••••56", vm.maskedCredential(SharePlatform.UC))
        assertNull(vm.editingPlatform)
        assertFalse(vm.isConfigured(SharePlatform.QUARK))

        vm.openPlatform(SharePlatform.UC)
        assertEquals("uc-token-123456", vm.credentialDraft)
        vm.clearCredential()
        assertFalse(vm.isConfigured(SharePlatform.UC))
        assertEquals("", vm.credentialDraft)
    }

    @Test
    fun github_card_opens_credential_editor_for_the_optional_token() = runTest {
        val accounts = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
        accounts.set(SharePlatform.GITHUB, "ghp_example")
        val vm = viewModel(FakeApi(), accounts)
        vm.openPlatform(SharePlatform.GITHUB)
        assertEquals(SharePlatform.GITHUB, vm.editingPlatform)
        assertEquals("ghp_example", vm.credentialDraft)
    }

    // ---------------------------------------------------------------- 网页登录 Screen

    @Test
    fun clicking_a_netdisk_card_opens_in_app_login_screen() = runTest {
        val vm = viewModel(FakeApi())
        assertNull(vm.loginPlatform)

        vm.openLogin(SharePlatform.QUARK)
        assertEquals(SharePlatform.QUARK, vm.loginPlatform)
        // 手工粘贴草稿预填当前凭证
        assertEquals("", vm.credentialDraft)

        vm.closeLogin()
        assertNull(vm.loginPlatform)
        assertNull(vm.editingPlatform)
    }

    @Test
    fun github_card_never_opens_in_app_login_screen() = runTest {
        val vm = viewModel(FakeApi())
        vm.openLogin(SharePlatform.GITHUB)
        // 防御性路由：即使误调 openLogin，也只展开凭证编辑器，不初始化 JCEF
        assertNull(vm.loginPlatform)
        assertEquals(SharePlatform.GITHUB, vm.editingPlatform)
    }

    @Test
    fun saving_detected_cookie_closes_login_and_sets_platform_credential() = runTest {
        val api = FakeApi()
        val accounts = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
        val vm = viewModel(api, accounts)
        vm.openLogin(SharePlatform.QUARK)

        val ok = vm.saveLoginCookie("__pus=a; __puus=b")
        advanceUntilIdle()

        assertTrue(ok)
        assertNull(vm.loginPlatform)
        assertTrue(vm.isConfigured(SharePlatform.QUARK))
        assertEquals("__pus=a; __puus=b", accounts.credentialFor(SharePlatform.QUARK))
        assertTrue(vm.notice?.contains("登录成功") == true)
        // 登录后自动刷新配额（FakeApi 会记一次调用）
        assertEquals(1, api.quotaCalls)
    }

    @Test
    fun every_credential_mutation_notifies_the_my_drive_page() = runTest {
        var changes = 0
        val accounts = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
        val vm = viewModel(FakeApi(), accounts, onAccountsChanged = { changes++ })

        vm.openLogin(SharePlatform.QUARK)
        assertTrue(vm.saveLoginCookie("__pus=a"))
        assertEquals(1, changes, "网页登录成功要通知")

        vm.logout(SharePlatform.QUARK)
        assertEquals(2, changes, "登出要通知")

        vm.openPlatform(SharePlatform.BAIDU)
        vm.updateCredentialDraft("BDUSS=abc")
        vm.saveCredential()
        assertEquals(3, changes, "手工保存凭证要通知")

        vm.openPlatform(SharePlatform.BAIDU) // saveCredential 会 closeEditor，须重设编辑平台
        vm.clearCredential()
        assertEquals(4, changes, "清除凭证要通知")
    }

    @Test
    fun blank_cookie_is_rejected_and_logout_clears_credential() = runTest {
        val vm = viewModel(FakeApi())
        assertFalse(vm.saveLoginCookie("")) // 没有打开登录页也不接受

        vm.openLogin(SharePlatform.BAIDU)
        assertFalse(vm.saveLoginCookie("   "))
        assertEquals(SharePlatform.BAIDU, vm.loginPlatform)

        assertTrue(vm.saveLoginCookie("BDUSS=abc"))
        vm.logout(SharePlatform.BAIDU)
        assertFalse(vm.isConfigured(SharePlatform.BAIDU))
    }

    @Test
    fun resolve_uses_the_matching_platform_credential() = runTest {
        val api = FakeApi().apply { dirs["0"] = listOf(file("movie.mp4")) }
        val vm = viewModel(api)
        vm.openPlatform(SharePlatform.QUARK)
        vm.updateCredentialDraft("quark-cookie")
        vm.saveCredential()

        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()

        assertEquals("quark-cookie", api.lastSessionCredential)
        assertEquals("quark-cookie", api.lastListCredential)
        assertEquals(SharePlatform.QUARK, vm.activePlatform)
        assertIs<CloudDriveState.Success>(vm.state)
    }

    @Test
    fun download_all_sends_every_file_in_current_folder() = runTest {
        val api = FakeApi().apply {
            dirs["0"] = listOf(file("a.mp4"), file("b.mp4"), folder("剧集"))
        }
        val vm = viewModel(api)
        val downloaded = mutableListOf<String>()
        vm.onDownload = { downloaded += it.url; "task-${downloaded.size}" }

        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()
        vm.downloadAll()
        advanceUntilIdle()

        assertEquals(2, downloaded.size)
        assertEquals(2, api.linkCalls)
        assertEquals(2, vm.links.size)
    }

    @Test
    fun download_folder_recurses_all_levels_and_preserves_paths() = runTest {
        val api = FakeApi().apply {
            dirs["0"] = listOf(folder("剧集", fid = "d1"), file("readme.txt"))
            dirs["d1"] = listOf(folder("第一季", fid = "d2"), file("ep00.mp4"))
            dirs["d2"] = listOf(file("ep01.mp4"), file("ep02.mp4"))
        }
        val vm = viewModel(api)
        val links = mutableListOf<CloudLink>()
        vm.onDownload = { links += it; "task-${links.size}" }

        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()
        vm.downloadFolder(vm.files.first { it.isDir })
        advanceUntilIdle()

        // 只读「剧集」这一支：根目录的 readme.txt 不该被带进来
        assertEquals(3, links.size)
        assertEquals(3, api.linkCalls)
        assertEquals("剧集", links.first { it.filename == "ep00.mp4" }.relativeDir)
        assertEquals("剧集/第一季", links.first { it.filename == "ep01.mp4" }.relativeDir)
        assertEquals("剧集/第一季", links.first { it.filename == "ep02.mp4" }.relativeDir)
        assertTrue(vm.links.all { it.relativeDir.startsWith("剧集") })
        assertFalse(vm.working)
        assertTrue(vm.notice?.contains("3 个文件") == true)
    }

    @Test
    fun download_folder_with_no_files_reports_without_calling_download_link() = runTest {
        val api = FakeApi().apply {
            dirs["0"] = listOf(folder("空目录", fid = "empty"))
        }
        val vm = viewModel(api)
        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()
        vm.downloadFolder(vm.files.first { it.isDir })
        advanceUntilIdle()

        assertEquals(0, api.linkCalls)
        assertTrue(vm.notice?.contains("没有可下载的文件") == true)
        assertFalse(vm.working)
    }

    @Test
    fun refresh_quotas_collects_results_for_configured_platforms_only() = runTest {
        val accounts = CloudDriveAccountStore(InMemoryCloudCredentialStorage()).apply { set(SharePlatform.QUARK, "cookie") }
        val api = FakeApi().apply { quotaResult = CloudQuota(1024L, 4096L) }
        val vm = viewModel(api, accounts)

        vm.refreshQuotas()
        advanceUntilIdle()

        assertEquals(CloudQuota(1024L, 4096L), vm.quotas[SharePlatform.QUARK])
        assertFalse(vm.quotaLoading)
        assertEquals(1, api.quotaCalls)
        assertTrue(vm.quotas[SharePlatform.UC] == null)
    }

    // ---------------------------------------------------------------- 临时转存目录清理 / 搜索 / 清空

    @Test
    fun cleanup_temp_dir_runs_once_when_download_task_settles() = runTest {
        val api = FakeApi().apply {
            dirs["0"] = listOf(file("a.mp4"))
            cleanupDirFidToReturn = "tr_123"
        }
        val accounts = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
            .apply { set(SharePlatform.QUARK, "quark-cookie") }
        val vm = viewModel(api, accounts)
        var taskId = ""
        vm.onDownload = { taskId = "task-1"; taskId }

        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()
        vm.downloadAll()
        advanceUntilIdle()

        // 任务还在跑，不能提前把转存目录删了（直链还没失效）
        assertTrue(api.cleanups.isEmpty())
        vm.onDownloadTasksChanged(setOf(taskId), setOf(taskId))
        advanceUntilIdle()
        assertEquals(1, api.cleanups.size)
        assertEquals(Triple(SharePlatform.QUARK, "tr_123", "quark-cookie"), api.cleanups.single())

        // 同一个任务重复通知不应再清一次
        vm.onDownloadTasksChanged(setOf(taskId), setOf(taskId))
        advanceUntilIdle()
        assertEquals(1, api.cleanups.size)
    }

    @Test
    fun cleanup_also_runs_when_task_is_deleted_from_the_list() = runTest {
        val api = FakeApi().apply {
            dirs["0"] = listOf(file("a.mp4"))
            cleanupDirFidToReturn = "tr_456"
        }
        val accounts = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
            .apply { set(SharePlatform.QUARK, "quark-cookie") }
        val vm = viewModel(api, accounts)
        vm.onDownload = { "task-2" }

        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()
        vm.downloadAll()
        advanceUntilIdle()

        // knownIds 里没有这个 id = 用户删掉了任务，临时目录同样没用了
        vm.onDownloadTasksChanged(emptySet(), emptySet())
        advanceUntilIdle()
        assertEquals("tr_456", api.cleanups.single().second)
    }

    @Test
    fun no_cleanup_is_registered_without_a_temp_dir() = runTest {
        val api = FakeApi().apply { dirs["0"] = listOf(file("a.mp4")) }
        val vm = viewModel(api)
        vm.onDownload = { "task-3" }

        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()
        vm.downloadAll()
        advanceUntilIdle()
        vm.onDownloadTasksChanged(setOf("task-3"), setOf("task-3"))
        advanceUntilIdle()

        assertTrue(api.cleanups.isEmpty())
    }

    @Test
    fun clear_links_and_clear_results_keep_the_input() = runTest {
        val api = FakeApi().apply { dirs["0"] = listOf(file("a.mp4")) }
        val vm = viewModel(api)
        vm.onDownload = { "task-4" }

        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()
        vm.downloadAll()
        advanceUntilIdle()
        assertTrue(vm.links.isNotEmpty())

        vm.clearLinks()
        assertTrue(vm.links.isEmpty())
        assertTrue(vm.addedUrls.isEmpty())
        assertIs<CloudDriveState.Success>(vm.state)

        vm.clearResults()
        assertIs<CloudDriveState.Idle>(vm.state)
        assertTrue(vm.files.isEmpty())
        assertEquals(quarkLink, vm.url)
    }

    @Test
    fun search_filters_and_sort_orders_visible_files_with_folders_first() = runTest {
        val vm = viewModel(FakeApi().apply {
            dirs["0"] = listOf(file("b.mp4", 100L), file("A.txt", 900L), folder("剧集"))
        })
        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()

        assertEquals(3, vm.visibleFiles.size)
        assertTrue(vm.visibleFiles.first().isDir)

        vm.updateSearchQuery("MP4")
        assertEquals(1, vm.visibleFiles.size)
        assertEquals("b.mp4", vm.visibleFiles.single().name)

        vm.updateSearchQuery("")
        vm.applySortMode(CloudFileSort.SIZE)
        assertTrue(vm.visibleFiles.first().isDir)
        assertEquals("A.txt", vm.visibleFiles[1].name)
        assertEquals("b.mp4", vm.visibleFiles[2].name)

        // 全选只勾中当前可见项
        vm.updateSearchQuery("A.txt")
        vm.toggleSelectAll()
        assertEquals(setOf("f-A.txt"), vm.selected)
    }

    @Test
    fun github_link_resolves_through_the_shared_pipeline() = runTest {
        val api = FakeApi()
        val vm = viewModel(api)
        vm.updateUrl("https://github.com/tidain/YunX-Desktop")
        vm.resolve()
        advanceUntilIdle()

        // D-18：GitHub 不再被硬拦截，走与其它平台相同的 createSession → listFiles
        val success = assertIs<CloudDriveState.Success>(vm.state)
        assertEquals(SharePlatform.GITHUB, success.resolution.platform)
        assertEquals(SharePlatform.GITHUB, vm.activePlatform)
        assertEquals(1, api.sessionCalls)
        assertEquals("https://github.com/tidain/YunX-Desktop", api.lastSessionLink)
    }

    // ------------------------------------------------ 解析历史 / 收藏（D-29）

    @Test
    fun successful_resolve_is_recorded_in_link_history() = runTest {
        val api = FakeApi().apply { dirs["0"] = listOf(file("movie.mp4")) }
        val vm = viewModel(api)
        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()

        assertIs<CloudDriveState.Success>(vm.state)
        assertEquals(1, vm.linkHistory.size)
        val entry = vm.linkHistory.single()
        assertEquals(quarkLink, entry.url)
        assertEquals("测试分享", entry.title)
        assertEquals(SharePlatform.QUARK, entry.platform)
    }

    @Test
    fun failed_resolve_is_not_recorded_in_link_history() = runTest {
        val vm = viewModel(FakeApi())
        vm.updateUrl("这不是一个网盘链接")
        vm.resolve()
        advanceUntilIdle()

        assertIs<CloudDriveState.Failed>(vm.state)
        assertTrue(vm.linkHistory.isEmpty())
    }

    @Test
    fun history_survives_a_new_viewmodel_and_reuse_triggers_a_fresh_resolve() = runTest {
        val dir = Files.createTempDirectory("ktdown-vm-links").also { tempDirs.add(it) }
        val api = FakeApi().apply { dirs["0"] = listOf(file("movie.mp4")) }
        val first = viewModel(api, linkLibrary = CloudLinkLibrary(dir))
        first.updateUrl(quarkLink)
        first.resolve()
        advanceUntilIdle()
        assertIs<CloudDriveState.Success>(first.state)

        // 模拟重启：同一个文件、新的存储实例
        val second = viewModel(api, linkLibrary = CloudLinkLibrary(dir))
        assertEquals(1, second.linkHistory.size)
        second.openLinkPicker(LinkPicker.HISTORY)
        assertEquals(LinkPicker.HISTORY, second.linkPicker)
        second.updateUrl("")
        second.reuseEntry(second.linkHistory.single())
        advanceUntilIdle()

        assertEquals(quarkLink, second.url)
        assertIs<CloudDriveState.Success>(second.state)
        assertNull(second.linkPicker)
        assertEquals(2, api.sessionCalls)
        assertEquals(1, second.linkHistory.size, "同一链接不应重复占一条历史")
    }

    @Test
    fun reuse_entry_clears_the_previous_manual_passcode_and_autofills_the_new_one() = runTest {
        val api = FakeApi().apply { dirs["0"] = listOf(file("a.mp4")) }
        val vm = viewModel(api)
        vm.updateUrl(quarkLink)
        vm.updatePasscode("manual-old")
        vm.resolve()
        advanceUntilIdle()

        val newLink = "https://pan.quark.cn/s/def456?pwd=new99"
        vm.reuseEntry(
            CloudLinkEntry(newLink, "t", SharePlatform.QUARK, 1L),
        )
        advanceUntilIdle()

        assertEquals(newLink, vm.url)
        assertEquals("new99", vm.passcode, "复用条目不应残留上一条的手动提取码")
        assertIs<CloudDriveState.Success>(vm.state)
    }

    @Test
    fun history_picker_can_remove_a_single_entry_and_clear_everything() = runTest {
        val api = FakeApi().apply { dirs["0"] = listOf(file("a.mp4")) }
        val vm = viewModel(api)
        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()
        vm.updateUrl("https://pan.quark.cn/s/def456")
        vm.resolve()
        advanceUntilIdle()

        assertEquals(2, vm.linkHistory.size)
        vm.openLinkPicker(LinkPicker.HISTORY)
        vm.removeHistoryEntry(vm.linkHistory.first())
        assertEquals(1, vm.linkHistory.size)
        vm.clearLinkHistory()
        assertTrue(vm.linkHistory.isEmpty())
    }

    @Test
    fun bookmark_current_link_can_be_toggled_removed_and_cleared() = runTest {
        val api = FakeApi().apply { dirs["0"] = listOf(file("a.mp4")) }
        val vm = viewModel(api)
        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()

        assertFalse(vm.isCurrentBookmarked())
        vm.toggleCurrentBookmark()
        assertTrue(vm.isCurrentBookmarked())
        assertEquals(1, vm.bookmarks.size)
        vm.openLinkPicker(LinkPicker.BOOKMARKS)
        vm.removeBookmarkEntry(vm.bookmarks.single())
        assertTrue(vm.bookmarks.isEmpty())
        vm.closeLinkPicker()
        assertNull(vm.linkPicker)

        vm.toggleCurrentBookmark()
        assertTrue(vm.isCurrentBookmarked())
        vm.clearAllBookmarks()
        assertFalse(vm.isCurrentBookmarked())
    }

    // ------------------------------------------------ 配额刷新钩子（D-48）

    @Test
    fun a_failing_platform_does_not_break_the_other_quota_cards() = runTest {
        val accounts = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
        accounts.set(SharePlatform.QUARK, "q")
        accounts.set(SharePlatform.UC, "u")
        val api = FakeApi().apply {
            quotaResult = CloudQuota(1024L, 4096L)
            quotaFailurePlatforms = setOf(SharePlatform.QUARK)
        }
        val vm = viewModel(api, accounts)
        vm.refreshQuotas()
        advanceUntilIdle()

        assertNull(vm.quotas[SharePlatform.QUARK])
        assertEquals(CloudQuota(1024L, 4096L), vm.quotas[SharePlatform.UC])
        assertEquals(2, api.quotaCalls)
        assertFalse(vm.quotaLoading)
    }

    @Test
    fun refreshing_again_cancels_the_stale_quota_job() = runTest {
        val accounts = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
        accounts.set(SharePlatform.QUARK, "q")
        val gate = CompletableDeferred<CloudQuota?>()
        val api = FakeApi().apply { quotaGate = gate }
        val vm = viewModel(api, accounts)

        vm.refreshQuotas()
        api.quotaGate = null
        api.quotaResult = CloudQuota(10L, 20L)
        vm.refreshQuotas()
        // 旧请求若没被取消，会带回这个过期值并覆盖新结果
        gate.complete(CloudQuota(999L, 999L))
        advanceUntilIdle()

        assertEquals(CloudQuota(10L, 20L), vm.quotas[SharePlatform.QUARK])
        assertFalse(vm.quotaLoading)
    }

    // ---------------------------------------------------------------- P3 多账号 / C1

    @Test
    fun c1_off_always_uses_the_primary_credential() = runTest {
        val accounts = CloudDriveAccountStore(InMemoryCloudCredentialStorage()).apply {
            set(SharePlatform.QUARK, "primary")
            addAccount(SharePlatform.QUARK, "second")
            addAccount(SharePlatform.QUARK, "third")
        }
        val api = FakeApi().apply {
            dirs["0"] = listOf(file("a.mp4"), file("b.mp4"), file("c.mp4"))
        }
        val vm = viewModel(api, accounts)
        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()
        vm.downloadAll()
        advanceUntilIdle()

        assertEquals(listOf("primary", "primary", "primary"), api.linkCredentials)
        assertEquals(3, api.linkCalls)
    }

    @Test
    fun c1_on_rotates_credentials_per_file_with_primary_first() = runTest {
        val accounts = CloudDriveAccountStore(InMemoryCloudCredentialStorage()).apply {
            set(SharePlatform.QUARK, "primary")
            addAccount(SharePlatform.QUARK, "second")
            addAccount(SharePlatform.QUARK, "third")
        }
        val api = FakeApi().apply {
            dirs["0"] = listOf(file("a.mp4"), file("b.mp4"), file("c.mp4"), file("d.mp4"))
        }
        val vm = viewModel(api, accounts, multiAccountProvider = { true })
        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()
        vm.downloadAll()
        advanceUntilIdle()

        // pool 是「主账号优先 + 按更新时间倒序」，第二个文件用最近添加的账号；
        // 3 个账号 / 4 个文件时第 4 个绕回 index 0（主账号）
        assertEquals(4, api.linkCredentials.size)
        assertEquals("primary", api.linkCredentials.first())
        assertEquals("primary", api.linkCredentials.last())
        assertEquals(3, api.linkCredentials.toSet().size)
    }

    @Test
    fun c1_on_keeps_cleanup_on_the_same_account_as_the_link() = runTest {
        val accounts = CloudDriveAccountStore(InMemoryCloudCredentialStorage()).apply {
            set(SharePlatform.QUARK, "primary")
            addAccount(SharePlatform.QUARK, "second")
        }
        val api = FakeApi().apply {
            dirs["0"] = listOf(file("a.mp4"), file("b.mp4"))
            cleanupDirFidToReturn = "tr_c1"
        }
        val vm = viewModel(api, accounts, multiAccountProvider = { true })
        var seq = 0
        vm.onDownload = { "task-${seq++}" }
        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()
        vm.downloadAll()
        advanceUntilIdle()
        vm.onDownloadTasksChanged(setOf("task-0", "task-1"), setOf("task-0", "task-1"))
        advanceUntilIdle()

        // 每个临时目录必须由「取链时那个账号」清理，不能用主账号一把梭
        assertEquals(2, api.cleanups.size)
        assertEquals(api.linkCredentials, api.cleanups.map { it.third })
        assertTrue(api.cleanups.all { it.second == "tr_c1" })
    }

    @Test
    fun c1_on_does_not_rotate_for_guests_or_a_single_account() = runTest {
        val guestApi = FakeApi().apply { dirs["0"] = listOf(file("a.mp4"), file("b.mp4")) }
        val guestVm = viewModel(
            guestApi,
            CloudDriveAccountStore(InMemoryCloudCredentialStorage()),
            multiAccountProvider = { true },
        )
        guestVm.updateUrl(quarkLink)
        guestVm.resolve()
        advanceUntilIdle()
        guestVm.downloadAll()
        advanceUntilIdle()
        // 游客链路必须继续用空凭证，不能被账号池「升级」成登录态
        assertEquals(listOf("", ""), guestApi.linkCredentials)

        val single = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
            .apply { set(SharePlatform.QUARK, "only-one") }
        val singleApi = FakeApi().apply { dirs["0"] = listOf(file("a.mp4"), file("b.mp4")) }
        val singleVm = viewModel(singleApi, single, multiAccountProvider = { true })
        singleVm.updateUrl(quarkLink)
        singleVm.resolve()
        advanceUntilIdle()
        singleVm.downloadAll()
        advanceUntilIdle()
        assertEquals(listOf("only-one", "only-one"), singleApi.linkCredentials)
    }

    @Test
    fun account_panel_manages_primary_remark_and_removal() = runTest {
        val accounts = CloudDriveAccountStore(InMemoryCloudCredentialStorage()).apply {
            set(SharePlatform.QUARK, "primary")
            addAccount(SharePlatform.QUARK, "second", remark = "备用号")
        }
        val vm = viewModel(FakeApi(), accounts)
        vm.openAccountPanel(SharePlatform.QUARK)

        assertEquals(2, vm.accountList.size)
        assertTrue(vm.accountList.first().isPrimary)
        assertEquals("备用号", vm.accountList.last().label)

        val secondId = vm.accountList.last().id
        vm.beginRename(secondId)
        vm.updateRemarkDraft("公司小号")
        vm.commitRename()
        assertEquals("公司小号", vm.accountList.last().label)

        vm.setPrimaryAccount(secondId)
        assertTrue(vm.accountList.first { it.id == secondId }.isPrimary)
        assertEquals(1, vm.accountList.count { it.isPrimary })
        assertEquals("second", accounts.credentialFor(SharePlatform.QUARK))

        vm.removeAccount(secondId)
        assertEquals(1, vm.accountList.size)
        assertEquals("primary", accounts.credentialFor(SharePlatform.QUARK))
        assertTrue(vm.accountList.single().isPrimary) // 删掉主账号后自动补位

        vm.closeAccountPanel()
        assertNull(vm.accountPanelPlatform)
        assertTrue(vm.accountList.isEmpty())
    }

    @Test
    fun adding_an_account_does_not_overwrite_the_primary_login() = runTest {
        val accounts = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
            .apply { set(SharePlatform.QUARK, "primary") }
        val vm = viewModel(FakeApi(), accounts)

        vm.openAccountPanel(SharePlatform.QUARK)
        vm.startAddAccount(SharePlatform.QUARK)
        assertTrue(vm.addingAccount)
        assertTrue(vm.saveLoginCookie("second"))
        assertFalse(vm.addingAccount)

        assertEquals("primary", accounts.credentialFor(SharePlatform.QUARK))
        assertEquals(2, accounts.accountCount(SharePlatform.QUARK))
        assertTrue(vm.notice?.contains("已添加账号") == true)

        // 普通重新登录仍然覆盖主账号，不会越堆越多
        vm.openLogin(SharePlatform.QUARK)
        assertTrue(vm.saveLoginCookie("primary-refreshed"))
        assertEquals("primary-refreshed", accounts.credentialFor(SharePlatform.QUARK))
        assertEquals(2, accounts.accountCount(SharePlatform.QUARK))
    }

    // ---------------------------------------------------------------- C2 单文件多源分片

    private fun threeAccounts() = CloudDriveAccountStore(InMemoryCloudCredentialStorage()).apply {
        set(SharePlatform.QUARK, "primary")
        addAccount(SharePlatform.QUARK, "second")
        addAccount(SharePlatform.QUARK, "third")
    }

    private fun singleFileApi() = FakeApi().apply {
        perCredentialLinks = true
        dirs["0"] = listOf(file("a.mp4"))
    }

    @Test
    fun c2_off_keeps_the_original_single_source_link() = runTest {
        val api = singleFileApi()
        val vm = viewModel(api, threeAccounts(), multiAccountProvider = { true })
        var captured: CloudLink? = null
        vm.onDownload = { captured = it; "task-0" }
        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()
        vm.downloadAll()
        advanceUntilIdle()

        assertTrue(captured?.altSources?.isEmpty() == true)
        assertEquals(1, api.linkCalls)
    }

    @Test
    fun c2_on_prefetches_per_account_and_really_passes_them_to_the_download() = runTest {
        val api = singleFileApi()
        val vm = viewModel(
            api, threeAccounts(),
            multiAccountProvider = { true },
            multiSourceProvider = { true },
        )
        var captured: CloudLink? = null
        vm.onDownload = { captured = it; "task-0" }
        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()
        vm.downloadAll()
        advanceUntilIdle()

        val link = captured!!
        // 上游的 pendingAltLinks 从未传入 enqueueDownload，altSources 恒空；这里必须真的到引擎入口
        assertEquals(2, link.altSources.size)
        assertEquals(setOf("cookie=second", "cookie=third"), link.altSources.map { it.cookie }.toSet())
        assertTrue(link.altSources.all { it.url != link.url })
        assertEquals(setOf("primary", "second", "third"), api.linkCredentials.toSet())
        assertEquals(3, api.linkCalls)
    }

    @Test
    fun c2_requires_c1_even_if_the_switch_itself_is_on() = runTest {
        val api = singleFileApi()
        val vm = viewModel(
            api, threeAccounts(),
            multiAccountProvider = { false },
            multiSourceProvider = { true },
        )
        var captured: CloudLink? = null
        vm.onDownload = { captured = it; "task-0" }
        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()
        vm.downloadAll()
        advanceUntilIdle()

        assertTrue(captured?.altSources?.isEmpty() == true)
        assertEquals(1, api.linkCalls)
    }

    @Test
    fun c2_skips_a_failing_account_without_failing_the_primary_download() = runTest {
        val api = singleFileApi().apply { linkFailures = setOf("second") }
        val vm = viewModel(
            api, threeAccounts(),
            multiAccountProvider = { true },
            multiSourceProvider = { true },
        )
        var captured: CloudLink? = null
        vm.onDownload = { captured = it; "task-0" }
        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()
        vm.downloadAll()
        advanceUntilIdle()

        assertEquals("cookie=third", captured!!.altSources.single().cookie)
        // 失败的账号试过一次就跳过，主链照常入队
        assertEquals(3, api.linkCalls)
    }

    @Test
    fun c2_cleans_up_every_accounts_temp_directory_with_its_own_credential() = runTest {
        val api = singleFileApi().apply { cleanupDirFidToReturn = "tr_c2" }
        val vm = viewModel(
            api, threeAccounts(),
            multiAccountProvider = { true },
            multiSourceProvider = { true },
        )
        vm.onDownload = { "task-0" }
        vm.updateUrl(quarkLink)
        vm.resolve()
        advanceUntilIdle()
        vm.downloadAll()
        advanceUntilIdle()
        vm.onDownloadTasksChanged(setOf("task-0"), setOf("task-0"))
        advanceUntilIdle()

        // 三个账号各自建了 tr_*：只登记主账号会在另外两个网盘里留下垃圾
        assertEquals(3, api.cleanups.size)
        assertEquals(setOf("primary", "second", "third"), api.cleanups.map { it.third }.toSet())
        assertTrue(api.cleanups.all { it.second == "tr_c2" })
    }

    // ---------------------------------------------------------------- 辅助

    private fun TestScope.viewModel(
        api: CloudDriveApi,
        accounts: CloudDriveAccountStore = CloudDriveAccountStore(InMemoryCloudCredentialStorage()),
        linkLibrary: CloudLinkLibrary = newLinkLibrary(),
        multiAccountProvider: () -> Boolean = { false },
        multiSourceProvider: () -> Boolean = { false },
        onAccountsChanged: () -> Unit = {},
    ): CloudDriveViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return CloudDriveViewModel(
            api = api,
            accounts = accounts,
            scope = CoroutineScope(dispatcher + SupervisorJob()),
            ioDispatcher = dispatcher,
            linkLibrary = linkLibrary,
            multiAccountProvider = multiAccountProvider,
            multiSourceProvider = multiSourceProvider,
        ).also { it.onAccountsChanged = onAccountsChanged }
    }

    private fun file(name: String, size: Long = 1024L) = ShareFile(
        fid = "f-$name",
        fname = name,
        fsize = size,
        isdir = false,
        pdirFid = "0",
        fidToken = "token-$name",
    )

    private fun folder(name: String, fid: String = "dir-1", parent: String = "0") = ShareFile(
        fid = fid,
        fname = name,
        fsize = 0,
        isdir = true,
        pdirFid = parent,
        fidToken = "token-dir",
    )

    private class FakeApi(
        delegate: CloudDriveApi = CloudDriveService(),
    ) : CloudDriveApi by delegate {
        val dirs = mutableMapOf<String, List<ShareFile>>()
        var sessionCalls = 0
            private set
        var linkCalls = 0
            private set
        var quotaCalls = 0
            private set
        var lastSessionCredential: String = ""
            private set
        var lastSessionLink: String = ""
            private set
        var lastListCredential: String = ""
            private set
        var quotaResult: CloudQuota? = null
        /** 这些平台模拟配额请求抛异常：验证单个平台失败不影响其它卡片 */
        var quotaFailurePlatforms: Set<SharePlatform> = emptySet()
        /** 非空时挂住配额请求：验证重复刷新会取消上一轮 */
        var quotaGate: CompletableDeferred<CloudQuota?>? = null

        /**
         * 中文口令解析结果：null = 「不是口令」。默认 null，避免类委托把请求
         * 转发给真实 CloudDriveService 而打到星海 / 迅雷的第三方接口。
         */
        var commandResult: Result<String>? = null
        var commandCalls = 0
            private set

        /** 非空时模拟「平台转存兜底建了临时目录」 */
        var cleanupDirFidToReturn: String? = null

        /** 记录 cleanupTempDir 的调用参数：platform / dirFid / credential */
        val cleanups = mutableListOf<Triple<SharePlatform, String, String>>()

        /** P3 · C1：记录每次取链实际用的凭证，验证按文件轮换与清理口径一致 */
        val linkCredentials = mutableListOf<String>()
        /** C2 测试：让每个账号返回不同的 URL + Cookie，才能验证「源与头成对」 */
        var perCredentialLinks: Boolean = false
        /** C2 测试：这些账号取链时抛异常，验证单条失败不影响主链 */
        var linkFailures: Set<String> = emptySet()

        override suspend fun createSession(
            link: String,
            passcode: String?,
            credential: String,
            platform: SharePlatform,
        ): ShareSession {
            sessionCalls++
            lastSessionCredential = credential
            lastSessionLink = link
            return ShareSession("abc123", "stoken", "测试分享")
        }

        override suspend fun resolveCommand(text: String): Result<String>? {
            commandCalls++
            return commandResult
        }

        override suspend fun listFiles(
            session: ShareSession,
            dirFid: String,
            credential: String,
            platform: SharePlatform,
        ): List<ShareFile> {
            lastListCredential = credential
            return dirs[dirFid].orEmpty()
        }

        override suspend fun downloadLink(
            session: ShareSession,
            file: ShareFile,
            credential: String,
            platform: SharePlatform,
        ): CloudLink {
            linkCalls++
            linkCredentials += credential
            if (credential in linkFailures) throw IllegalStateException("账号取链失败: $credential")
            return CloudLink(
                url = if (perCredentialLinks) {
                    "https://cdn.example.com/${file.fname}?cred=$credential"
                } else {
                    "https://cdn.example.com/${file.fname}"
                },
                filename = file.fname,
                size = file.fsize,
                cookie = if (perCredentialLinks) "cookie=$credential" else "",
                referer = "https://pan.quark.cn/",
                cleanupDirFid = cleanupDirFidToReturn,
                platform = SharePlatform.QUARK,
            )
        }

        /** 屏蔽真实配额网络请求 */
        override suspend fun quota(platform: SharePlatform, credential: String): CloudQuota? {
            quotaCalls++
            quotaGate?.await()
            if (platform in quotaFailurePlatforms) {
                throw IllegalStateException("配额接口失败: ${platform.name}")
            }
            return quotaResult
        }

        override suspend fun cleanupTempDir(
            platform: SharePlatform,
            dirFid: String,
            credential: String,
        ) {
            cleanups += Triple(platform, dirFid, credential)
        }

        /**
         * 类委托会把接口默认方法也转发给真实 [CloudDriveService]；这里显式调用
         * [collectTree]，让递归走本假实现的 listFiles，测试不发真实网络请求。
         */
        override suspend fun collectFiles(
            session: ShareSession,
            dirFid: String,
            credential: String,
            platform: SharePlatform,
            relativeDir: String,
        ): CloudTreeResult = collectTree(session, dirFid, credential, platform, relativeDir)
    }
}

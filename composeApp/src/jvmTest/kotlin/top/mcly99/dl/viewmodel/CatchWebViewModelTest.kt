package top.mcly99.dl.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import top.mcly99.dl.engine.web.FetchedPage
import top.mcly99.dl.engine.web.MediaSizeProbe
import top.mcly99.dl.engine.web.MediaCandidate
import top.mcly99.dl.engine.web.MediaKind
import top.mcly99.dl.engine.web.SizeProbeTarget
import top.mcly99.dl.engine.web.WebPageFetcher
import top.mcly99.dl.engine.web.browser.BrowserCapture
import top.mcly99.dl.engine.web.browser.BrowserCaptureFailure
import top.mcly99.dl.engine.web.browser.BrowserCaptureResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [CatchWebViewModel] 状态机单测。
 *
 * 网络与体积探测都换成假实现（或注入可控调度器），只验证「抓取中取消」「全部下载跳过已加入」
 * 这类纯状态逻辑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatchWebViewModelTest {

    private val pageUrl = "https://page.example.com/watch/1"

    private val twoVideos = """
        <html><body>
        <video src="https://cdn.example.com/v/a.mp4"></video>
        <video src="https://cdn.example.com/v/b.mp4"></video>
        </body></html>
    """.trimIndent()

    @Test
    fun submit_while_idle_starts_loading_and_second_submit_cancels() = runTest {
        val vm = viewModel()
        vm.updateUrl(pageUrl)

        vm.submit()
        assertIs<CatchWebState.Loading>(vm.state)

        // 同一个按钮第二次点击 = 取消（抓取中允许中断）
        vm.submit()
        assertEquals(CatchWebState.Idle, vm.state)
    }

    @Test
    fun download_all_skips_already_added_results() = runTest {
        val vm = viewModel()
        vm.updateUrl(pageUrl)
        vm.fetch()
        advanceUntilIdle()

        val results = assertIs<CatchWebState.Success>(vm.state).results
        assertEquals(2, results.size)

        val added = mutableListOf<String>()
        vm.onDownload = { added += it.url }
        vm.downloadAll()
        assertEquals(results.map { it.url }, added)

        vm.markAdded(results.first().url)
        vm.downloadAll()
        // 再点一次只补剩下的，不会把已加入的又下一遍
        assertEquals(listOf(results[1].url), added.drop(2))
    }

    @Test
    fun size_probe_keeps_only_successes_and_list_unchanged() = runTest {
        val probe = object : MediaSizeProbe() {
            override suspend fun probe(targets: List<SizeProbeTarget>, concurrency: Int): Map<String, Long> =
                targets.mapNotNull { target ->
                    // 第一条探得到、第二条失败：失败项不进 sizes，也不影响列表
                    target.url.takeIf { it.endsWith("a.mp4") }?.let { it to 1_234L }
                }.toMap()
        }
        val vm = viewModel(sizeProbe = probe)
        vm.updateUrl(pageUrl)
        vm.fetch()
        advanceUntilIdle()

        val results = assertIs<CatchWebState.Success>(vm.state).results
        assertEquals(2, results.size)
        assertEquals(mapOf("https://cdn.example.com/v/a.mp4" to 1_234L), vm.sizes)
        assertTrue(vm.state is CatchWebState.Success)
    }


    @Test
    fun remove_candidate_drops_the_row_and_forgets_added_state() = runTest {
        val vm = viewModel()
        vm.updateUrl(pageUrl)
        vm.fetch()
        advanceUntilIdle()

        val results = assertIs<CatchWebState.Success>(vm.state).results
        val first = results.first()
        vm.markAdded(first.url)
        vm.removeCandidate(first.url)

        val left = assertIs<CatchWebState.Success>(vm.state).results
        assertEquals(listOf(results[1].url), left.map { it.url })
        // 已加入记录也要清掉：否则重抓同一条会一直显示「已加入」（v6-P2-2）
        assertTrue(first.url !in vm.addedUrls)
    }

    @Test
    fun removing_every_candidate_falls_back_to_empty_state() = runTest {
        val vm = viewModel()
        vm.updateUrl(pageUrl)
        vm.fetch()
        advanceUntilIdle()

        assertIs<CatchWebState.Success>(vm.state).results.forEach { vm.removeCandidate(it.url) }
        // 删光后回到「没找到」，而不是留一个空列表的 Success（后者会让「全部下载」按钮孤立出现）
        assertEquals(CatchWebState.Empty, vm.state)
    }

    @Test
    fun remove_candidate_with_unknown_url_keeps_state_untouched() = runTest {
        val vm = viewModel()
        vm.updateUrl(pageUrl)
        vm.fetch()
        advanceUntilIdle()

        val before = assertIs<CatchWebState.Success>(vm.state).results
        vm.removeCandidate("https://cdn.example.com/v/not-there.mp4")
        assertEquals(before, assertIs<CatchWebState.Success>(vm.state).results)
    }

    @Test
    fun deep_capture_merges_network_hits_and_carries_cookie() = runTest {
        val intercepted = MediaCandidate(
            url = "https://cdn.example.com/hls/real.m3u8?wsSecret=x",
            kind = MediaKind.M3u8,
            name = "",
            source = MediaCandidate.Source.Network,
            referer = pageUrl,
        )
        val vm = viewModel(
            capture = FakeCapture(
                BrowserCaptureResult(
                    candidates = listOf(intercepted),
                    cookies = mapOf("cdn.example.com" to "sid=abc"),
                )
            ),
            deepCatch = true,
        )
        vm.updateUrl(pageUrl)
        vm.fetch()
        advanceUntilIdle()

        val results = assertIs<CatchWebState.Success>(vm.state).results
        // 静态 2 条 + 拦截 1 条，且 m3u8 排在最前（按 kind 排序）
        assertEquals(3, results.size)
        assertEquals(MediaKind.M3u8, results.first().kind)
        // Cookie 必须跟着候选走，否则「能播不能下」（v6-P1-3）
        assertEquals("sid=abc", results.first().cookie)
        assertNull(vm.notice)
    }

    @Test
    fun deep_capture_without_browser_keeps_static_results_and_sets_notice() = runTest {
        val vm = viewModel(
            capture = FakeCapture(BrowserCaptureResult(failure = BrowserCaptureFailure.NoBrowser)),
            deepCatch = true,
        )
        vm.updateUrl(pageUrl)
        vm.fetch()
        advanceUntilIdle()

        // 降级不是失败：静态结果照常展示，只多一条提示
        assertEquals(2, assertIs<CatchWebState.Success>(vm.state).results.size)
        assertEquals(CatchWebNotice.NoBrowser, vm.notice)
    }

    @Test
    fun deep_capture_is_skipped_when_disabled() = runTest {
        val capture = FakeCapture(
            BrowserCaptureResult(
                candidates = listOf(
                    MediaCandidate("https://cdn.example.com/hls/x.m3u8", MediaKind.M3u8, "", MediaCandidate.Source.Network)
                )
            )
        )
        val vm = viewModel(capture = capture, deepCatch = false)
        vm.updateUrl(pageUrl)
        vm.fetch()
        advanceUntilIdle()

        assertEquals(0, capture.calls)
        assertEquals(2, assertIs<CatchWebState.Success>(vm.state).results.size)
    }

    @Test
    fun deep_capture_linkage_error_degrades_instead_of_crashing() = runTest {
        // 打包版精简 JDK 时 java.net.http 整个缺失，CDP 会抛 NoClassDefFoundError。
        // Error 不是 Exception：不接住就会冒泡到全局未捕获处理器，整个应用弹窗报错。
        val vm = viewModel(capture = ThrowingCapture(), deepCatch = true)
        vm.updateUrl(pageUrl)
        vm.fetch()
        advanceUntilIdle()

        // 静态抓到的两条照常展示，深度抓取只降级成一条提示
        assertEquals(2, assertIs<CatchWebState.Success>(vm.state).results.size)
        assertEquals(CatchWebNotice.DeepCatchFailed, vm.notice)
    }

    // ---------------------------------------------------------------- 辅助

    private fun TestScope.viewModel(
        sizeProbe: MediaSizeProbe = MediaSizeProbe(),
        capture: BrowserCapture? = null,
        deepCatch: Boolean = false,
    ): CatchWebViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return CatchWebViewModel(
            fetcher = FakeFetcher(twoVideos, pageUrl),
            sizeProbe = sizeProbe,
            sizeProbeEnabled = { true },
            browserCapture = capture,
            deepCatchEnabled = { deepCatch },
            scope = CoroutineScope(dispatcher + SupervisorJob()),
            ioDispatcher = dispatcher,
            // 提取也走测试调度器：否则 `advanceUntilIdle()` 看不到解析完成，
            // 又得退回 sleep 轮询（T7：这个文件原先是唯一靠 Thread.sleep 兜底的）
            extractDispatcher = dispatcher,
        )
    }

    /** 假浏览器：不起进程，只回放给定的拦截结果 */
    private class FakeCapture(private val result: BrowserCaptureResult) : BrowserCapture() {
        var calls = 0
            private set

        override fun capture(pageUrl: String): BrowserCaptureResult {
            calls++
            return result
        }

        override fun cancel() = Unit
    }

    /** 模拟打包版缺少 java.net.http 模块：深度抓取直接抛 Error 而不是返回失败结果 */
    private class ThrowingCapture : BrowserCapture() {
        override fun capture(pageUrl: String): BrowserCaptureResult =
            throw NoClassDefFoundError("java/net/http/HttpClient")

        override fun cancel() = Unit
    }

    private class FakeFetcher(private val html: String, private val finalUrl: String) : WebPageFetcher() {
        override suspend fun fetch(rawUrl: String, referer: String?): FetchedPage =
            FetchedPage(finalUrl = finalUrl, html = html, charset = "UTF-8")
    }
}

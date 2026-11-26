package top.mcly99.dl.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.mcly99.dl.engine.web.FetchFailure
import top.mcly99.dl.engine.web.FetchedPage
import top.mcly99.dl.engine.web.HtmlMediaExtractor
import top.mcly99.dl.engine.web.HttpProbe
import top.mcly99.dl.engine.web.MediaCandidate
import top.mcly99.dl.engine.web.MediaSizeProbe
import top.mcly99.dl.engine.web.SizeProbeTarget
import top.mcly99.dl.engine.web.WebPageFetcher
import top.mcly99.dl.engine.web.browser.BrowserCapture
import top.mcly99.dl.engine.web.browser.BrowserCaptureFailure
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger

/**
 * 「抓网页」的状态机。
 *
 * `Empty` 与 `Failed` 分开：一个「抓到了页面但里面没有媒体地址」，
 * 一个「根本没抓到页面」，给用户的建议完全不同（前者要提示 JS 渲染，后者要提示网络/地址）。
 */
sealed interface CatchWebState {
    data object Idle : CatchWebState
    data object Loading : CatchWebState

    /** @property pageUrl 重定向后的最终地址，也是下载/播放要用的 Referer */
    data class Success(val pageUrl: String, val results: List<MediaCandidate>) : CatchWebState

    data object Empty : CatchWebState
    data class Failed(
        val reason: FailureReason,
        val detail: String? = null,
        val httpCode: Int? = null,
    ) : CatchWebState
}

/** 失败原因；每种给不同文案，不统一成「抓取失败」 */
enum class FailureReason {
    InvalidUrl,
    NotHtml,
    NetworkError,
    HttpError,
    Unknown,
}

/**
 * 抓取过程中需要额外告诉用户的事（不阻断结果）。
 *
 * 目前只有「深度抓取」一种来源：它是可选增强，失败时必须**安静降级**到静态结果，
 * 而不是把整次抓取判为失败 —— 用户看到的应该是「没抓到那条地址」，不是「抓取失败」。
 */
enum class CatchWebNotice {
    /** 机器上没有 Chrome / Edge / Chromium */
    NoBrowser,

    /** 浏览器起来了但没抓到东西（导航失败、超时、被拦） */
    DeepCatchFailed,
}

/**
 * 「抓网页」视图的业务状态。
 *
 * 只做三件事：保存输入的网址、把抓取结果转成 [CatchWebState]、把「下载」转发出去。
 * 真正的下载任务由 [AppViewModel] 通过 [onDownload] 注入 —— 这里不持有下载引擎，
 * 否则会出现两条添加任务的路径，日后很容易不一致。
 *
 * @param proxyProvider 抓取时用的代理；与下载共用设置页里的同一份配置
 * @param sizeProbe 并发探测文件大小用；功能开关见 [sizeProbeEnabled]
 * @param sizeProbeEnabled 是否在抓取成功后探测文件大小（设置项，默认关闭 —— 一个页面
 *   可能有几十条结果，每条一次请求，慢的时候要十几秒）
 * @param browserCapture 深度抓取（本机 Chromium + CDP）；为 null 或开关关闭时走静态链路
 * @param deepCatchEnabled 设置项：是否启用深度抓取
 * @param scope 协程作用域；测试注入可控调度器
 * @param ioDispatcher 阻塞 IO 用的调度器；测试注入可控调度器
 * @param extractDispatcher CPU 密集的 HTML 解析用的调度器。默认 `Dispatchers.Default`，
 *   与 IO 分开是为了「解析几十个视频标签」不占满 IO 线程。测试里也换成测试调度器，
 *   否则抓取完成时机对 `advanceUntilIdle()` 不可见，只能靠 sleep 轮询。
 */
class CatchWebViewModel(
    proxyProvider: () -> java.net.Proxy? = { null },
    private val fetcher: WebPageFetcher = WebPageFetcher(proxyProvider = proxyProvider),
    private val sizeProbe: MediaSizeProbe = MediaSizeProbe(HttpProbe(proxyProvider = proxyProvider)),
    private val sizeProbeEnabled: () -> Boolean = { false },
    private val browserCapture: BrowserCapture? = null,
    private val deepCatchEnabled: () -> Boolean = { false },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val extractDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** 顶栏输入框的内容；单一真源，HeaderBar 不自己 remember 一份 */
    var url by mutableStateOf("")
        private set

    var state by mutableStateOf<CatchWebState>(CatchWebState.Idle)
        private set

    /** 已加入下载的地址：同一行不重复添加，按钮显示「已加入」 */
    var addedUrls by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 地址 → 文件字节数；只在开启「探测文件大小」时才有内容，探不到的条目不在其中 */
    var sizes by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    /** 深度抓取的降级提示；为 null 表示一切正常 */
    var notice by mutableStateOf<CatchWebNotice?>(null)
        private set

    /** 由 [AppViewModel] 注入：把候选真正交给下载引擎 */
    var onDownload: (MediaCandidate) -> Unit = {}

    private var job: Job? = null

    private var sizeJob: Job? = null

    val busy: Boolean get() = state is CatchWebState.Loading

    fun updateUrl(value: String) {
        url = value
    }

    /**
     * 顶栏按钮的唯一入口：空闲时开始抓取，抓取中则取消。
     *
     * 一个按钮两种语义，是为了让「抓取中允许取消」不需要再加一个按钮 ——
     * 长页面抓取十几秒时，用户最想点的就是同一个按钮。
     */
    fun submit() {
        if (busy) cancel() else fetch()
    }

    fun fetch() {
        val input = url.trim()
        if (input.isEmpty()) return

        job?.cancel()
        // 上一轮可能还阻塞在 HttpURLConnection 读上：协程取消叫不醒它，直接断连接
        fetcher.cancelAll()
        browserCapture?.cancel()
        sizeJob?.cancel()
        state = CatchWebState.Loading
        addedUrls = emptySet()
        sizes = emptyMap()
        notice = null

        job = scope.launch {
            try {
                val page = withContext(ioDispatcher) { fetcher.fetch(input) }
                val results = withContext(extractDispatcher) {
                    HtmlMediaExtractor.extract(page.html, page.finalUrl)
                }
                // 主页面结果先出：iframe 最坏要等几十秒，已经拿到的结果不该陪跑
                if (results.isNotEmpty()) state = CatchWebState.Success(page.finalUrl, results)

                // 主页面已有可直接播放的地址时不再跟 iframe：最常见的情况，省掉最多 3 个请求
                val nested = if (results.any { it.kind.canPlay }) {
                    emptyList()
                } else {
                    withContext(ioDispatcher) { crawlFrames(page) }
                }
                var merged = merge(results, nested)

                // 深度抓取：静态链路对「播放器完全由 JS 构建」的站点是 0 分，
                // 只有真的把页面跑一遍、从网络层捞请求才有机会拿到（v6-P1-3）
                if (deepCatchEnabled()) {
                    val captured = captureWithBrowser(page.finalUrl)
                    if (captured.isNotEmpty()) merged = merge(merged, captured)
                }

                state = if (merged.isEmpty()) {
                    CatchWebState.Empty
                } else {
                    CatchWebState.Success(page.finalUrl, merged)
                }
                if (merged.isNotEmpty() && sizeProbeEnabled()) probeSizes(merged)
            } catch (e: CancellationException) {
                // 取消不是失败：交给 cancel() 把状态复位
                throw e
            } catch (e: FetchFailure) {
                state = CatchWebState.Failed(
                    reason = e.toReason(),
                    detail = e.message,
                    httpCode = (e as? FetchFailure.HttpError)?.code,
                )
            } catch (e: Exception) {
                AppLogger.warn("CatchWebViewModel", "抓取页面失败: ${e.message}", e)
                state = CatchWebState.Failed(FailureReason.Unknown, e.message)
            }
        }
    }

    /**
     * 主页面结果在前、iframe 与深度抓取的在后；同一地址只留一条。
     *
     * 合并规则不是「留第一条」也不是「留有 cookie 的那条」，而是**各取所长**：
     * 名称 / 来源取先出现的那条（静态那条带页面上的真实标题），`cookie` 取有值的那条 ——
     * 深度抓到的地址常带会话签名，缺了 Cookie 下载就 403，但它的名称只能是 `index`。
     */
    private fun merge(main: List<MediaCandidate>, nested: List<MediaCandidate>): List<MediaCandidate> =
        (main + nested)
            .groupBy { it.url }
            .map { (_, same) ->
                val first = same.first()
                val name = first.name.ifBlank {
                    same.map { it.name }.firstOrNull { it.isNotBlank() }.orEmpty()
                }
                val cookie = same.mapNotNull { it.cookie }.firstOrNull { it.isNotBlank() }
                first.copy(name = name, cookie = cookie)
            }
            .sortedBy { it.kind.ordinal }
            .take(AppConstants.CATCH_WEB_MAX_RESULTS)

    /**
     * 深度抓取：用本机 Chromium 内核打开页面，从网络层捞 m3u8 / mp4。
     *
     * 失败一律降级（返回空列表 + 设置 [notice]），**不**影响已经拿到的静态结果 ——
     * 深度抓取是可选增强，机器上没装 Chrome/Edge 是常态，不该因此让整次抓取失败。
     */
    private suspend fun captureWithBrowser(pageUrl: String): List<MediaCandidate> {
        val capture = browserCapture ?: return emptyList()
        val result = try {
            withContext(ioDispatcher) { capture.capture(pageUrl) }
        } catch (e: LinkageError) {
            // 运行时镜像缺 java.net.http 时抛的是 NoClassDefFoundError（Error 而非 Exception），
            // fetch() 里的 catch (e: Exception) 接不住。深度抓取是可选增强，降级即可，
            // 不能让它把整个应用带进全局未捕获异常弹窗。
            AppLogger.warn("CatchWebViewModel", "深度抓取运行时依赖缺失: ${e.javaClass.name}: ${e.message}", e)
            notice = CatchWebNotice.DeepCatchFailed
            return emptyList()
        }
        when (result.failure) {
            null -> Unit
            BrowserCaptureFailure.NoBrowser -> {
                notice = CatchWebNotice.NoBrowser
                return emptyList()
            }
            else -> {
                AppLogger.debug("CatchWebViewModel", "深度抓取降级: ${result.failure} ${result.detail.orEmpty()}")
                notice = CatchWebNotice.DeepCatchFailed
                return emptyList()
            }
        }
        if (result.candidates.isEmpty()) {
            notice = CatchWebNotice.DeepCatchFailed
            return emptyList()
        }
        return result.candidates.map { it.copy(cookie = result.cookieFor(it.url)) }
    }

    /**
     * 并发探测文件大小（设置项开启时才走这里）。
     *
     * 结果随探随到：先探完的条目先显示大小，不必等全部完成 —— 几十条结果时
     * 「一次性刷出来」会让用户以为功能没生效。
     */
    private fun probeSizes(results: List<MediaCandidate>) {
        sizeJob?.cancel()
        sizeJob = scope.launch {
            val targets = results.map { SizeProbeTarget(it.url, it.referer) }
            // 逐批推进，每批完成就更新一次，UI 才有「逐渐出现」的观感。
            // 分批由这里控制，`probe()` 不再二次切分（否则「并发数由谁决定」会变得含糊）
            targets.chunked(AppConstants.SIZE_PROBE_CONCURRENCY).forEach { batch ->
                val found = withContext(ioDispatcher) { sizeProbe.probe(batch, concurrency = batch.size) }
                if (found.isNotEmpty()) sizes = sizes + found
            }
        }
    }

    /**
     * 跟随一层 iframe。
     *
     * 很多站点的播放器在独立 iframe 里，主页面一条媒体地址都没有；不跟这一步，
     * 这类站点会全部显示「没找到」。**只跟一层**：再往深就是爬虫了，收益和风险都不对等。
     *
     * 单个 iframe 失败只记 debug 日志：广告 iframe 返回 403/404 是常态，
     * 弹一个错误给用户纯属噪音。
     */
    private suspend fun crawlFrames(page: FetchedPage): List<MediaCandidate> {
        val frames = HtmlMediaExtractor.extractFrames(page.html, page.finalUrl)
        if (frames.isEmpty()) return emptyList()
        return coroutineScope {
            frames.map { frame ->
                async(ioDispatcher) {
                    try {
                        // 嵌入播放器常校验 Referer：不带主页面地址会被 403
                        val sub = fetcher.fetch(frame, referer = page.finalUrl)
                        HtmlMediaExtractor.extract(sub.html, sub.finalUrl)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.debug("CatchWebViewModel", "iframe 抓取失败: $frame (${e.message})")
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
    }

    /** 取消当前抓取并回到初始态（已输入的内容保留） */
    fun cancel() {
        job?.cancel()
        job = null
        // 断开在途连接：否则后台 IO 线程还会跑满超时，再点抓取就叠加一批请求
        fetcher.cancelAll()
        // 深度抓取起的是真浏览器进程，必须显式杀掉，否则它会一直跑到自己的超时
        browserCapture?.cancel()
        sizeJob?.cancel()
        sizeJob = null
        sizes = emptyMap()
        notice = null
        state = CatchWebState.Idle
    }

    /** 行内「下载」按钮 */
    fun download(candidate: MediaCandidate) {
        onDownload(candidate)
    }

    /**
     * 「全部下载」：把还没加入的条目依次入队。
     *
     * 跳过已加入的（按钮上写着「已加入」的那些），否则重复点一次就会多出一批
     * 同名任务 —— 下载引擎会给同名文件加序号，用户看到的是「怎么下了两份」。
     */
    fun downloadAll() {
        val results = (state as? CatchWebState.Success)?.results ?: return
        results.filter { it.url !in addedUrls }.forEach(onDownload)
    }

    /** 下载任务创建成功后由 [AppViewModel] 回调 */
    fun markAdded(url: String) {
        addedUrls = addedUrls + url
    }

    /**
     * 从候选列表移除一条（行尾的 X）。
     *
     * 语义是「移除候选」，**不是**取消下载：已加入的条目移除后，任务列表里那条任务照旧跑，
     * 所以按钮文案用「移除」而不是「删除」。删到一条不剩时回到 [CatchWebState.Empty]，
     * 用户可以直接重抓（v6-P2-2）。
     */
    fun removeCandidate(url: String) {
        val current = state as? CatchWebState.Success ?: return
        val left = current.results.filterNot { it.url == url }
        if (left.size == current.results.size) return
        state = if (left.isEmpty()) CatchWebState.Empty else current.copy(results = left)
        addedUrls = addedUrls - url
        sizes = sizes - url
    }

    fun shutdown() {
        sizeJob?.cancel()
        fetcher.cancelAll()
        browserCapture?.cancel()
        scope.cancel()
    }

    private fun FetchFailure.toReason(): FailureReason = when (this) {
        is FetchFailure.InvalidUrl -> FailureReason.InvalidUrl
        is FetchFailure.NotHtml -> FailureReason.NotHtml
        is FetchFailure.HttpError -> FailureReason.HttpError
        is FetchFailure.NetworkError -> FailureReason.NetworkError
    }
}

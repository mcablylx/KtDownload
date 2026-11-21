package top.mcly99.dl.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.mcly99.dl.engine.player.PlaybackEngine
import top.mcly99.dl.engine.player.PlaybackError
import top.mcly99.dl.engine.player.PlaybackPhase
import top.mcly99.dl.engine.player.PlaybackRequest
import top.mcly99.dl.engine.player.PlaybackState
import top.mcly99.dl.engine.player.QualityOption
import top.mcly99.dl.engine.player.QualityOptions
import top.mcly99.dl.engine.player.VideoPlayer
import top.mcly99.dl.engine.player.VlcOptions
import top.mcly99.dl.engine.player.VlcRuntime
import top.mcly99.dl.engine.web.HttpProbe
import top.mcly99.dl.engine.web.MediaCandidate
import top.mcly99.dl.engine.web.MediaKind
import top.mcly99.dl.utils.AppLogger
import java.net.Proxy

/**
 * 在线观看（m3u8 / mp4）的状态与生命周期。
 *
 * 播放器**懒创建**：第一次点「播放」才加载 libvlc。没装/加载失败时把原因放进
 * [PlaybackState.error]，界面显示「播放内核不可用」，下载功能完全不受影响。
 *
 * 关闭播放页只 `stop()` 不清场，播放器实例保留 —— 同一个进程里反复开关不必反复
 * 加载 native 库（加载一次约 1~2 秒）；真正的释放放在 [shutdown]。
 *
 * m3u8 会额外做一次**清晰度探测**：libvlc 的 adaptive 模块只会按带宽自动选，
 * 没有给宿主程序留选择接口，要选清晰度只能自己拉一次主播放列表（见 [QualityOptions]）。
 * 探测是异步的、失败静默，绝不拖慢起播。
 *
 * @param probe 拉主播放列表用；与抓网页共用同一份代理设置
 * @param scope 协程作用域；测试注入可控调度器
 * @param ioDispatcher 加载 libvlc / 网络探测用的调度器；测试注入可控调度器
 * @param engineFactory 创建播放器；默认走 [VlcRuntime]，测试替换成假实现
 */
class PlayerViewModel(
    proxyProvider: () -> Proxy? = { null },
    private val probe: HttpProbe = HttpProbe(proxyProvider = proxyProvider),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val engineFactory: (
        onState: (PlaybackState) -> Unit,
        onFrame: (ImageBitmap?) -> Unit,
    ) -> PlaybackEngine? = ::createVideoEngine,
) {
    /** 播放页是否打开（打开时盖住整个主界面） */
    var open by mutableStateOf(false)
        private set

    var state by mutableStateOf(PlaybackState())
        private set

    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    /**
     * 可选清晰度；空列表表示**不给下拉**（不是主播放列表 / 只有一档 / 探测失败）。
     */
    var qualities by mutableStateOf<List<QualityOption>>(emptyList())
        private set

    /** 当前选中的档位地址；null 表示「自动」（即用户点的那条原始地址） */
    var selectedQualityUrl by mutableStateOf<String?>(null)
        private set

    /** 播放区域像素尺寸（UI 上报）；0 表示未知，引擎按 1080p 封顶 */
    private var viewportWidth = 0
    private var viewportHeight = 0

    @Volatile
    private var player: PlaybackEngine? = null

    /** 单飞的内核初始化：连点「播放」只加载一次 libvlc */
    private var engineInit: Deferred<PlaybackEngine?>? = null

    /** 内核不可用的原因；成功创建后为 null */
    var engineError by mutableStateOf<String?>(null)
        private set

    /** 用户点的那条请求（主列表地址）；切清晰度后仍然保留，用于切回「自动」 */
    private var origin: PlaybackRequest? = null

    /** 当前实际在播的请求（可能已经是某个档位的子列表地址） */
    private var active: PlaybackRequest? = null

    private var activeCachingMs = 1_000

    private var qualityJob: Job? = null

    /** 由 UI 调：播放一条抓取结果 */
    fun play(candidate: MediaCandidate, displayName: String) {
        val request = PlaybackRequest.of(candidate, displayName)
        origin = request
        selectedQualityUrl = null
        clearQualities()
        start(request, VlcOptions.cachingFor(candidate.kind)) {
            // 内核不可用时不白费一次网络请求：确认起播成功后才探清晰度
            if (candidate.kind == MediaKind.M3u8) probeQualities(request)
        }
    }

    /** 直接播一个 [PlaybackRequest]（不探清晰度）；主代码只走上面那条，测试用它构造场景 */
    internal fun play(request: PlaybackRequest, cachingMs: Int = 1_000) {
        origin = request
        selectedQualityUrl = null
        clearQualities()
        start(request, cachingMs)
    }

    /**
     * 切换清晰度。
     *
     * 位置保留到秒级：换档后从原位置接着播，而不是回到第 0 秒 —— 用户拖到 40 分钟
     * 发现画质不行，换档后从头开始是最让人恼火的交互。
     */
    fun selectQuality(option: QualityOption) {
        val base = origin ?: return
        if (option.url == active?.url) return
        selectedQualityUrl = option.url
        // 子列表里没有 #EXT-X-MEDIA：独立音轨要一起带过去，否则切档后只有画面没有声音
        start(
            base.copy(url = option.url, audioUrl = option.audioUrl, startMs = resumePosition()),
            activeCachingMs,
        )
    }

    /** 失败页的「重试」：用当前请求重新起播；内核不可用时也会重新搜索 libvlc */
    fun retry() {
        val request = active ?: return
        start(request, activeCachingMs)
    }

    fun setVolume(volume: Int) {
        player?.setVolume(volume)
    }

    fun toggleMute() {
        player?.toggleMute()
    }

    /**
     * 由 UI 调：播放区域尺寸变化。
     *
     * 引擎按这个尺寸做整数倍抽取，1080p 源在小窗口里不必每帧转换原分辨率。
     * 引擎还没创建时先缓存，起播前补上（见 [start]）。
     */
    fun setViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == viewportWidth && height == viewportHeight) return
        viewportWidth = width
        viewportHeight = height
        player?.setViewport(width, height)
    }

    /** 切回「自动」：交回原始主列表地址，让 libvlc 按带宽自己挑 */
    fun selectAutoQuality() {
        val base = origin ?: return
        if (selectedQualityUrl == null) return
        selectedQualityUrl = null
        start(base.copy(startMs = resumePosition()), activeCachingMs)
    }

    fun togglePause() {
        player?.togglePause()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    /** 关闭播放页：停止播放并清空画面，播放器实例保留 */
    fun close() {
        qualityJob?.cancel()
        qualityJob = null
        player?.stop()
        open = false
        frame = null
        // 音量是播放器级设置，关页面不该把用户调好的值清掉
        state = PlaybackState(volume = state.volume, muted = state.muted)
        clearQualities()
        origin = null
        active = null
    }

    /** 应用退出时释放 native 资源 */
    fun shutdown() {
        qualityJob?.cancel()
        scope.cancel()
        player?.release()
        player = null
        engineInit = null
        VlcRuntime.release()
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 起播。
     *
     * libvlc 首次加载要 1~2 秒，放在 IO 线程上、用 [engineInit] 单飞：
     * 界面先打开播放页显示不确定态进度条，连点也只加载一次。
     *
     * @param onReady 真正开始播之后才执行（例如清晰度探测）
     */
    private fun start(request: PlaybackRequest, cachingMs: Int, onReady: (() -> Unit)? = null) {
        active = request
        activeCachingMs = cachingMs
        open = true
        state = PlaybackState(
            phase = PlaybackPhase.Opening,
            url = request.url,
            title = request.title,
            buffering = true,
            volume = state.volume,
            muted = state.muted,
        )
        val init = engineInit ?: scope.async(ioDispatcher) { createEngine() }.also { engineInit = it }
        scope.launch {
            val engine = init.await()
            if (engine == null) {
                // 失败不缓存：用户装好 VLC 后再点「重试」应当有机会成功
                engineInit = null
                if (active?.url != request.url) return@launch
                state = PlaybackState(
                    phase = PlaybackPhase.Failed,
                    url = request.url,
                    title = request.title,
                    error = PlaybackError.EngineUnavailable,
                    volume = state.volume,
                    muted = state.muted,
                )
                frame = null
                return@launch
            }
            // 期间又点了别的：这个请求已经过时，别让后到的 init 覆盖它
            if (active?.url != request.url) return@launch
            engine.setViewport(viewportWidth, viewportHeight)
            engine.play(request, cachingMs)
            onReady?.invoke()
        }
    }

    /** 播完后再切档从头开始，而不是停在片尾 */
    private fun resumePosition(): Long =
        if (state.phase == PlaybackPhase.Ended) 0L else state.timeMs.coerceAtLeast(0L)

    /**
     * 拉主播放列表 → 解析清晰度。全程静默失败。
     *
     * 只在**起播之后**才有意义，但它不阻塞起播：libvlc 已经在播原始地址了，
     * 这里只是把「还有哪些档位」补进下拉。慢个一两秒不影响观看。
     */
    private fun probeQualities(request: PlaybackRequest) {
        qualityJob?.cancel()
        qualityJob = scope.launch {
            // 探测也要带 Cookie：签名列表缺会话一样是 403，结果就是「有地址却没有清晰度下拉」（v7-P1-1）
            val probed = withContext(ioDispatcher) {
                probe.fetchTextWithUrl(request.url, request.referer, cookie = request.cookie)
            }
            if (probed == null) {
                AppLogger.debug("PlayerViewModel", "主播放列表拉取失败，不给清晰度下拉: ${request.url}")
                return@launch
            }
            val options = withContext(Dispatchers.Default) {
                // 相对档位必须基于重定向后的最终地址解析
                QualityOptions.fromMaster(probed.text, probed.finalUrl)
            }
            // 期间可能已经切了档位或关了播放页：结果只对同一个 origin 生效
            if (origin?.url == request.url) {
                qualities = options
                AppLogger.debug("PlayerViewModel", "清晰度档位 ${options.size} 个: ${request.url}")
            }
        }
    }

    private fun clearQualities() {
        qualityJob?.cancel()
        qualityJob = null
        qualities = emptyList()
    }

    /** 在 IO 线程上创建播放器；失败只记录原因，不影响下载等其余功能 */
    private fun createEngine(): PlaybackEngine? {
        val engine = runCatching { engineFactory({ state = it }, { frame = it }) }
            .onFailure { AppLogger.warn("PlayerViewModel", "创建播放器失败: ${it.message}", it) }
            .getOrNull()
        if (engine == null) {
            engineError = VlcRuntime.lastError() ?: "未找到播放内核"
            AppLogger.warn("PlayerViewModel", "播放内核不可用: $engineError")
        } else {
            engineError = null
            player = engine
        }
        return engine
    }
}

/** 默认播放器工厂：加载 libvlc 并创建真实播放器；不可用时返回 null */
private fun createVideoEngine(
    onState: (PlaybackState) -> Unit,
    onFrame: (ImageBitmap?) -> Unit,
): PlaybackEngine? =
    VlcRuntime.factory()?.let { factory ->
        VideoPlayer(factory = factory, onState = onState, onFrame = onFrame)
    }

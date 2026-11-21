package top.mcly99.dl.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import top.mcly99.dl.engine.player.PlaybackEngine
import top.mcly99.dl.engine.player.PlaybackError
import top.mcly99.dl.engine.player.PlaybackPhase
import top.mcly99.dl.engine.player.PlaybackRequest
import top.mcly99.dl.engine.player.PlaybackState
import top.mcly99.dl.engine.player.QualityOption
import top.mcly99.dl.engine.web.MediaCandidate
import top.mcly99.dl.engine.web.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [PlayerViewModel] 状态机单测。
 *
 * 真正的播放依赖 native libvlc，这里用 [FakeEngine] 替身，只验证「切档位置」「单飞初始化」
 * 这类纯状态逻辑 —— 它们一旦写错只能靠人工反复点。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private val masterUrl = "https://cdn.example.com/hls/master.m3u8"

    @Test
    fun select_quality_from_ended_restarts_from_zero() = runTest {
        val engine = FakeEngine()
        val vm = viewModel(engine)

        vm.play(PlaybackRequest(url = masterUrl))
        advanceUntilIdle()
        engine.emit(PlaybackState(phase = PlaybackPhase.Ended, lengthMs = 90_000, timeMs = 90_000))

        vm.selectQuality(option(url = "v/720/index.m3u8"))
        advanceUntilIdle()

        assertEquals(0L, engine.requests.last().startMs)
        assertEquals("v/720/index.m3u8", engine.requests.last().url)
    }

    @Test
    fun select_quality_while_playing_keeps_position_and_audio_track() = runTest {
        val engine = FakeEngine()
        val vm = viewModel(engine)

        vm.play(PlaybackRequest(url = masterUrl))
        advanceUntilIdle()
        engine.emit(PlaybackState(phase = PlaybackPhase.Playing, lengthMs = 1_800_000, timeMs = 40_000))

        vm.selectQuality(option(url = "v/1080/index.m3u8", audioUrl = "audio/cn.m3u8"))
        advanceUntilIdle()

        val request = engine.requests.last()
        assertEquals(40_000L, request.startMs)
        // 子列表里没有 #EXT-X-MEDIA：切档必须把独立音轨一起带过去
        assertEquals("audio/cn.m3u8", request.audioUrl)
    }

    @Test
    fun select_auto_quality_is_noop_when_already_auto() = runTest {
        val engine = FakeEngine()
        val vm = viewModel(engine)

        vm.play(PlaybackRequest(url = masterUrl))
        advanceUntilIdle()
        val before = engine.requests.size

        vm.selectAutoQuality()
        advanceUntilIdle()

        assertEquals(before, engine.requests.size)
    }

    @Test
    fun retry_recreates_engine_after_init_failure() = runTest {
        val engine = FakeEngine()
        var attempts = 0
        val vm = viewModel(engine) { onState ->
            attempts++
            if (attempts == 1) null else engine.also { it.attach(onState) }
        }

        vm.play(PlaybackRequest(url = masterUrl))
        advanceUntilIdle()
        assertIs<PlaybackError.EngineUnavailable>(vm.state.error)

        vm.retry()
        advanceUntilIdle()

        // 失败结果不缓存：装好 VLC 后重试应当真的重新加载
        assertEquals(1, engine.requests.size)
    }

    // ---------------------------------------------------------------- 候选 → 请求

    @Test
    fun play_candidate_keeps_cookie_on_every_request() = runTest {
        // v7-P1-1：深度抓取抓到的签名地址把会话绑在 Cookie 里，播放侧漏了它就只能 403。
        // 这里同时覆盖「首次起播」和「切档」两条路径 —— 切档是 copy(base) 出来的，
        // 一旦哪天有人改成重新构造 PlaybackRequest，Cookie 会静默丢掉。
        // 用 Mp4 候选是为了不触发清晰度探测（那会真的发 HTTP 请求）
        val engine = FakeEngine()
        val vm = viewModel(engine)

        vm.play(candidate(cookie = "sid=abc"), "第 3 集")
        advanceUntilIdle()
        engine.emit(PlaybackState(phase = PlaybackPhase.Playing, lengthMs = 90_000, timeMs = 5_000))

        assertEquals("sid=abc", engine.requests.last().cookie)

        vm.selectQuality(option(url = "v/720/index.m3u8"))
        advanceUntilIdle()

        assertEquals("sid=abc", engine.requests.last().cookie)
    }

    // ---------------------------------------------------------------- 辅助

    private fun TestScope.viewModel(
        engine: FakeEngine,
        factory: ((PlaybackState) -> Unit) -> PlaybackEngine? = { onState ->
            engine.also { it.attach(onState) }
        },
    ): PlayerViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return PlayerViewModel(
            scope = CoroutineScope(dispatcher + SupervisorJob()),
            ioDispatcher = dispatcher,
            engineFactory = { onState, _ -> factory(onState) },
        )
    }

    private class FakeEngine : PlaybackEngine {
        val requests = mutableListOf<PlaybackRequest>()
        private var onState: ((PlaybackState) -> Unit)? = null

        fun attach(callback: (PlaybackState) -> Unit) {
            onState = callback
        }

        fun emit(state: PlaybackState) {
            onState?.invoke(state)
        }

        override fun play(request: PlaybackRequest, cachingMs: Int) {
            requests += request
        }

        override fun togglePause() = Unit

        override fun seekTo(positionMs: Long) = Unit

        override fun setVolume(volume: Int) = Unit

        override fun toggleMute() = Unit

        override fun stop() = Unit

        override fun release() = Unit
    }

    private fun option(url: String, audioUrl: String? = null) = QualityOption(
        url = url,
        label = "1080p",
        detail = null,
        height = 1080,
        bandwidth = 4_500_000,
        audioUrl = audioUrl,
    )

    private fun candidate(
        cookie: String? = null,
        referer: String? = "https://page.example.com/p/3",
    ) = MediaCandidate(
        url = "https://cdn.example.com/v/1080/index.mp4",
        kind = MediaKind.Mp4,
        name = "第 3 集",
        source = MediaCandidate.Source.Attribute,
        referer = referer,
        cookie = cookie,
    )
}

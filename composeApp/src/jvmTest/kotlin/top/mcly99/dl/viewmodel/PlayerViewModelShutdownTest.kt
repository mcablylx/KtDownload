package top.mcly99.dl.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import top.mcly99.dl.engine.player.PlaybackEngine
import top.mcly99.dl.engine.player.PlaybackRequest
import top.mcly99.dl.testutil.awaitStable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P2-02 回归：shutdown 与「正在进行的 libvlc 初始化」之间的时序。
 *
 * `engineFactory` 里的 native 加载是 1~2 秒的阻塞调用，协程取消打断不了它。旧实现
 * `cancel → release → VlcRuntime.release()`，而 `createEngine()` 可能在这之后才把
 * `player = engine` 写回来，留下一个指向已释放 runtime 的引擎。
 */
class PlayerViewModelShutdownTest {

    @Test
    fun engine_created_after_shutdown_is_released_not_published() = runBlocking {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val releases = AtomicInteger(0)
        val engine = RecordingEngine { releases.incrementAndGet() }
        val vm = PlayerViewModel(
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
            ioDispatcher = Dispatchers.IO,
            engineFactory = { _, _ ->
                started.countDown()
                proceed.await(5, TimeUnit.SECONDS)   // 模拟不响应取消的 native 阻塞调用
                engine
            },
        )
        try {
            vm.play(PlaybackRequest(url = "https://cdn.example.com/v.mp4"), 1_000)
            assertTrue(started.await(5, TimeUnit.SECONDS), "内核初始化没有开始")
            vm.shutdown()
            proceed.countDown()
            withTimeoutOrNull(3_000) {
                while (releases.get() == 0) delay(20)
            }
            assertEquals(
                1,
                releases.get(),
                "shutdown 之后才创建出的引擎必须被释放，且不能再挂到 player 字段上",
            )
        } finally {
            proceed.countDown()
        }
    }

    @Test
    fun play_after_shutdown_does_not_start_a_new_init() = runBlocking {
        val attempts = AtomicInteger(0)
        val vm = PlayerViewModel(
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
            ioDispatcher = Dispatchers.IO,
            engineFactory = { _, _ -> attempts.incrementAndGet(); null },
        )
        vm.shutdown()
        vm.play(PlaybackRequest(url = "https://cdn.example.com/v.mp4"), 1_000)
        awaitStable(300, describe = { "shutdown 之后不应再发起内核初始化" }) {
            attempts.get() > 0
        }
        assertEquals(0, attempts.get())
    }

    @Test
    fun shutdown_is_idempotent() {
        val vm = PlayerViewModel(
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
            engineFactory = { _, _ -> null },
        )
        vm.shutdown()
        vm.shutdown()
    }

    private class RecordingEngine(private val onRelease: () -> Unit) : PlaybackEngine {
        override fun play(request: PlaybackRequest, cachingMs: Int) = Unit
        override fun togglePause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setVolume(volume: Int) = Unit
        override fun toggleMute() = Unit
        override fun stop() = Unit
        override fun release() = onRelease()
    }
}

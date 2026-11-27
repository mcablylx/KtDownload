package top.mcly99.dl.engine.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P2-02 回归：播放器状态的 read-copy-write 并发更新。
 *
 * 旧实现是普通 `var current` + `update { it.copy(...) }`：libvlc 回调线程与 UI 线程
 * 同时读到同一个旧值再各自写回，后写的整条覆盖先写的（`buffering` 抹掉刚到的
 * `lengthMs` 等）。这里用真线程并发断言「不丢增量、不丢字段」。
 */
class PlaybackStateHolderTest {

    @Test
    fun concurrent_increments_never_lose_an_update() {
        val holder = PlaybackStateHolder()
        val threads = 8
        val perThread = 400
        runConcurrently(threads) {
            repeat(perThread) { holder.update { it.copy(timeMs = it.timeMs + 1) } }
        }
        assertEquals(
            (threads * perThread).toLong(),
            holder.value.timeMs,
            "CAS 循环下每次自增都必须保留",
        )
    }

    @Test
    fun concurrent_updates_to_different_fields_both_survive() {
        val holder = PlaybackStateHolder()
        runConcurrently(2) { index ->
            repeat(500) { i ->
                if (index == 0) holder.update { it.copy(timeMs = i + 1L) }
                else holder.update { it.copy(lengthMs = i + 1L) }
            }
        }
        // 两个字段各自被更新过 500 次；无锁 read-copy-write 会让其中一个被反复抹成 0
        assertTrue(holder.value.timeMs > 0, "timeMs 被另一个线程的 copy 覆盖")
        assertTrue(holder.value.lengthMs > 0, "lengthMs 被另一个线程的 copy 覆盖")
    }

    @Test
    fun emit_replaces_the_whole_state() {
        val holder = PlaybackStateHolder(PlaybackState(timeMs = 42, lengthMs = 999))
        holder.emit(PlaybackState(phase = PlaybackPhase.Ended, timeMs = 7))
        assertEquals(PlaybackPhase.Ended, holder.value.phase)
        assertEquals(7L, holder.value.timeMs)
        assertEquals(0L, holder.value.lengthMs)
    }

    private fun runConcurrently(threads: Int, block: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        try {
            repeat(threads) { index ->
                pool.submit {
                    start.await()
                    block(index)
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(20, TimeUnit.SECONDS), "并发更新未在 20 秒内结束")
        } finally {
            pool.shutdownNow()
        }
    }
}

package top.mcly99.dl.engine.player

import java.util.concurrent.atomic.AtomicReference

/**
 * P2-02：播放器状态的并发容器。
 *
 * [VideoPlayer] 的状态由 libvlc 的回调线程（`opening` / `playing` / `timeChanged` /
 * `lengthChanged` / `buffering` 可能并发到来）与 UI 线程（`play` / `stop` / `setVolume` /
 * `toggleMute`）共同更新。原来是普通 `var current` 配合 `update { it.copy(...) }` 的
 * read-copy-write：两个线程同时读到同一个旧值、各自基于它 copy 再写回，后写的会整条
 * 覆盖先写的（例如 `buffering` 把刚到的 `lengthMs` 抹掉），而且没有内存可见性保证。
 *
 * 这里把「基于旧值计算新值」收敛成 [AtomicReference.updateAndGet] 的 CAS 循环 ——
 * 冲突时重算而不是覆盖；[emit] 的整体替换也走同一个原子引用。
 */
internal class PlaybackStateHolder(initial: PlaybackState = PlaybackState()) {

    private val ref = AtomicReference(initial)

    val value: PlaybackState get() = ref.get()

    /** CAS 循环：并发更新不会互相丢失字段。返回值是本次成功写入的状态。 */
    fun update(block: (PlaybackState) -> PlaybackState): PlaybackState =
        ref.updateAndGet(block)

    /** 整体替换（起播 / 停止），不依赖旧值。 */
    fun emit(next: PlaybackState): PlaybackState {
        ref.set(next)
        return next
    }
}

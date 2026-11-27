package top.mcly99.dl.testutil

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * P2-28：负向观察窗口工具自身的回归测试。
 *
 * [awaitStable] / [awaitStableSuspend] 用来替代测试里「固定 Thread.sleep 后断言没变化」
 * 的裸轮询，必须同时保证两件事：窗口内反复检查、且一旦出现禁止状态立即失败。
 * 窗口取极小值，测试不依赖真实墙钟耗时。
 */
class AwaitStableTest {

    @Test
    fun returns_when_condition_never_appears() {
        var polls = 0
        awaitStable(windowMs = 60, intervalMs = 10, describe = { "禁止状态" }) {
            polls++
            false
        }
        assertTrue(polls >= 2, "负向窗口应反复检查，而不是只看一次")
    }

    @Test
    fun fails_as_soon_as_condition_appears() {
        assertStableFailure {
            awaitStable(windowMs = 5_000, intervalMs = 10, describe = { "禁止状态" }) { true }
        }
    }

    @Test
    fun suspend_variant_returns_when_condition_never_appears() = runBlocking {
        var polls = 0
        awaitStableSuspend(windowMs = 60, intervalMs = 10, describe = { "禁止状态" }) {
            polls++
            false
        }
        assertTrue(polls >= 2, "suspend 负向窗口也应反复检查")
    }

    @Test
    fun suspend_variant_fails_as_soon_as_condition_appears() = runBlocking {
        assertStableFailure {
            awaitStableSuspend(windowMs = 5_000, intervalMs = 10, describe = { "禁止状态" }) { true }
        }
    }

    /** 不绑定具体异常类型（kotlin.test.fail 由当前 asserter 决定），只锁定语义与信息 */
    private inline fun assertStableFailure(block: () -> Unit) {
        val result = runCatching(block)
        assertTrue(result.isFailure, "条件成立时负向窗口必须失败")
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty().contains("观察窗口内出现了不该发生的状态"),
            "失败信息应说明是负向观察窗口被破坏，实际: ${result.exceptionOrNull()?.message}",
        )
    }
}

package top.mcly99.dl.testutil

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import top.mcly99.dl.model.DownloadStatus
import kotlin.test.fail

/**
 * 测试超时的唯一来源。
 *
 * 以前每个测试文件各写一个 `30_000L` / `60_000L`，慢机器（尤其 CI 上跑真 socket 的
 * 集成测试）超时就只能改代码重新编译。现在统一在这里定义，用
 * `-Dktdl.testScale=3` 整体放大即可，业务断言不受影响。
 *
 * 只放大**等待**，不放宽断言：超时说明「没等到预期状态」，与「状态算错了」是两件事。
 */
object TestTimeouts {

    /** 放大倍数；非法值（负数、非数字）按 1 处理，避免一个笔误把超时变成 0 */
    private val scale: Double =
        System.getProperty("ktdl.testScale")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0

    fun scaled(baseMs: Long): Long = (baseMs * scale).toLong()

    /** 进程内的短动作：起一个本地 HTTP 服务、删一个临时文件 */
    val SHORT: Long = scaled(3_000)

    /** 常规等待：状态流转、目录写入 */
    val MEDIUM: Long = scaled(10_000)

    /** 真下载/真传输：要跑满本地回环的几十 KB 数据 */
    val LONG: Long = scaled(30_000)

    /** 多线程续传这类要反复暂停恢复的用例 */
    val SLOW: Long = scaled(60_000)

    /** 内嵌浏览器：解包内核 + 起 CEF 子进程，慢机器上几十秒起步 */
    val BROWSER: Long = scaled(180_000)
}

/** 下载任务的终态：不会再自己变化，等到它就说明这一轮结束 */
val DownloadStatus.isTerminal: Boolean
    get() = this == DownloadStatus.COMPLETED ||
        this == DownloadStatus.FAILED ||
        this == DownloadStatus.CANCELLED

/**
 * 阻塞式轮询，直到 [condition] 为真。
 *
 * 用在**测试方法本身不是 suspend**（普通 `@Test`）且等的是外部资源（文件被删、
 * 目录出现、子进程退出）的场景 —— 这些动作不在测试调度器里，`advanceUntilIdle`
 * 看不到它们，只能真等。
 *
 * 超时信息里带 [describe] 的现场，失败时不必再猜「卡在哪一步」。
 */
fun awaitUntil(
    timeoutMs: Long = TestTimeouts.MEDIUM,
    intervalMs: Long = 20,
    describe: () -> String,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(intervalMs)
    }
    // 再判一次：最后一轮 sleep 结束时条件可能刚好满足
    if (condition()) return
    fail("等待超时（${timeoutMs}ms）：${describe()}")
}

/** [awaitUntil] 的 suspend 版本：不占线程，可被协程取消打断 */
suspend fun awaitUntilSuspend(
    timeoutMs: Long = TestTimeouts.MEDIUM,
    intervalMs: Long = 20,
    describe: () -> String,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        delay(intervalMs)
    }
    if (condition()) return
    fail("等待超时（${timeoutMs}ms）：${describe()}")
}

/**
 * **负向观察窗口**：在 [windowMs] 内反复检查 [condition]，一旦成立立即 [fail]；
 * 窗口走完始终不成立则正常返回。
 *
 * 用于「取消后不应再发第二次请求」「暂停后调度器不应补位」这类断言：
 * 换成 `awaitUntil` 会立即返回而丢掉观察窗口，换成固定 `Thread.sleep` 又没有超时体系。
 * 窗口统一走 [TestTimeouts.scaled]，可被 `-Dktdl.testScale` 放大（只放大等待，不放宽断言）。
 */
fun awaitStable(
    windowMs: Long = TestTimeouts.scaled(800),
    intervalMs: Long = 20,
    describe: () -> String,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + windowMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) fail("观察窗口内出现了不该发生的状态：${describe()}")
        Thread.sleep(intervalMs)
    }
    if (condition()) fail("观察窗口内出现了不该发生的状态：${describe()}")
}

/** [awaitStable] 的 suspend 版本：不占线程，可被协程取消打断 */
suspend fun awaitStableSuspend(
    windowMs: Long = TestTimeouts.scaled(900),
    intervalMs: Long = 20,
    describe: () -> String,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + windowMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) fail("观察窗口内出现了不该发生的状态：${describe()}")
        delay(intervalMs)
    }
    if (condition()) fail("观察窗口内出现了不该发生的状态：${describe()}")
}

/**
 * 等 [StateFlow] 的当前值满足 [predicate]。
 *
 * 事件驱动的核心：`first {}` 挂起在收集上，状态一变就被唤醒，不做 sleep 轮询，
 * 也不会因为轮询间隔而误判「还没到」。引擎类测试的 `awaitTerminal` / `awaitStatus`
 * 原来在 5 个文件里各写一遍 `withTimeout + first`，统一到这里。
 *
 * 注意超时抛的是 [kotlin.test.fail] 而不是 `TimeoutCancellationException`：
 * 后者在报告里只有一句「Timed out waiting for」，看不出等的是哪个任务、当前是什么状态。
 */
suspend fun <T> StateFlow<T>.awaitValue(
    timeoutMs: Long = TestTimeouts.LONG,
    describe: () -> String,
    predicate: (T) -> Boolean,
): T = try {
    withTimeout(timeoutMs) { first(predicate) }
} catch (e: TimeoutCancellationException) {
    fail("等待超时（${timeoutMs}ms）：${describe()}")
}

/**
 * 等列表里出现第一个满足 [predicate] 的元素，并把它返回。
 *
 * 只关心「有没有出现」的调用方可以忽略返回值；需要断言那一条的具体字段
 * （例如终态任务的 `errorMessage`）时才有用。
 */
suspend fun <T> StateFlow<List<T>>.awaitElement(
    timeoutMs: Long = TestTimeouts.LONG,
    describe: () -> String,
    predicate: (T) -> Boolean,
): T = awaitValue(timeoutMs, describe) { tasks -> tasks.any(predicate) }.first(predicate)

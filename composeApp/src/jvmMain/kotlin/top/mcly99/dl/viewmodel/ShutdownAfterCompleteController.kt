package top.mcly99.dl.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.mcly99.dl.model.DownloadStatus
import top.mcly99.dl.model.DownloadTask
import top.mcly99.dl.utils.AppLogger
import top.mcly99.dl.utils.requestSystemShutdown

/**
 * 「下载完成后关机」的常驻状态机（自研，非移植）。
 *
 * 对齐上游 v1.3.4 的判定口径（commit `3700c1c`）：
 * 1. **必须真的跑过任务**：开关打开但从没有下载中的任务，不触发，避免「勾上开关就关机」；
 * 2. **队列排空**：没有下载中 / 排队中 / 合并中的任务，且没有被用户主动暂停的任务；
 * 3. **60 秒倒计时可取消**：期间新增任务、暂停开关、点「取消关机」都会中止；
 * 4. **与页面无关**：控制器挂在 [AppViewModel] 常驻作用域，切到网盘页 / 最小化到托盘照样生效。
 *
 * 与上游的刻意差异：上游调用 `DesktopActions.shutdownComputer(seconds)` 下发系统级定时关机，
 * 再靠取消命令撤销；下游把倒计时留在应用内，归零时才发一次性立即关机。这样即使倒计时
 * 期间应用崩溃，也不会留下一个没人能取消的系统关机计划。
 */
class ShutdownAfterCompleteController(
    private val scope: CoroutineScope,
    private val shutdownAction: () -> Unit = { requestSystemShutdown() },
    private val countdownSeconds: Int = 60,
) {
    /** 开关状态（由设置持久化驱动，UI 不直接改） */
    var enabled by mutableStateOf(false)
        private set

    /** 剩余秒数；null = 未在倒计时。AppNew 用它决定是否显示确认弹窗 */
    var countdown by mutableStateOf<Int?>(null)
        private set

    /** 本次开关周期里是否真的观察到过「有任务在跑」 */
    private var armed = false
    private var countdownJob: Job? = null

    fun applyEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) reset()
    }

    /** 任务列表每次变化（含进度刷新）都会调进来；重复调用不会重启倒计时 */
    fun onTasksChanged(tasks: List<DownloadTask>) {
        if (!enabled) {
            reset()
            return
        }
        val hasActive = tasks.any { it.status in ACTIVE_STATUSES }
        if (hasActive) {
            // 用户又加了任务 / 重试了失败任务：撤销倒计时，重新武装
            stopCountdown()
            armed = true
            return
        }
        // 旧实现要求「列表非空」，于是用户手动清空列表后反而不关机（D 延伸）。
        // 真正要判断的是「没有排队中的任务」：列表空了也就没有东西可等了。
        val queueDrained = tasks.none { it.status == DownloadStatus.PAUSED }
        if (armed && queueDrained && countdown == null) startCountdown()
    }

    /** 用户点「取消关机」：解除武装，避免下一次任务列表刷新又立刻触发 */
    fun cancel() {
        stopCountdown()
        armed = false
    }

    /** 用户点「立即关机」 */
    fun shutdownNow() {
        stopCountdown()
        armed = false
        trigger()
    }

    /** 应用退出：绝不能把关机动作带出进程 */
    fun dispose() {
        enabled = false
        reset()
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            try {
                for (left in countdownSeconds downTo 1) {
                    countdown = left
                    delay(1_000L)
                }
                countdown = null
                armed = false
                trigger()
            } catch (_: CancellationException) {
                countdown = null
            }
        }
    }

    private fun stopCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        countdown = null
    }

    private fun reset() {
        stopCountdown()
        armed = false
    }

    private fun trigger() {
        AppLogger.info("ShutdownAfterComplete", "队列已排空且倒计时结束，发起系统关机")
        runCatching { shutdownAction() }
            .onFailure { e -> AppLogger.warn("ShutdownAfterComplete", "关机动作失败: ${e.message}", e) }
    }

    private companion object {
        /** MERGING 也要算「还在忙」：分片没合并完就关机等于白下 */
        val ACTIVE_STATUSES = setOf(
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.MERGING,
        )
    }
}

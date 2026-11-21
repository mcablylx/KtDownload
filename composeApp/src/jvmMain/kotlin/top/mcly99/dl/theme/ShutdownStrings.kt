package top.mcly99.dl.theme

/**
 * 「下载完成后关机」独立文案表。
 *
 * 不往 [UiStrings] 里加字段：P1 时它的构造签名已经越过 JVM 255 参数槽，
 * 再加字段会在运行时抛 `ClassFormatError`（见 N-10）。所有新模块的文案都单独成表。
 */
data class ShutdownStrings(
    val title: String,
    val desc: String,
    val risk: String,
    val dialogTitle: String,
    val dialogMessage: (Int) -> String,
    val cancel: String,
    val shutdownNow: String,
)

fun shutdownStrings(language: String): ShutdownStrings =
    if (language.startsWith("en", ignoreCase = true)) en() else zh()

private fun zh() = ShutdownStrings(
    title = "下载完成后关机",
    desc = "队列里的任务全部跑完（没有下载中 / 排队中 / 已暂停）后，先倒计时 60 秒再关机，期间可以随时取消。",
    risk = "只会在这次真的跑过下载后触发；切到其它页面或最小化到托盘同样生效。关机需要系统权限，权限不足时只写日志。",
    dialogTitle = "即将关机",
    dialogMessage = { "下载任务已全部结束，将在 $it 秒后关闭计算机。取消后不会再次自动触发。" },
    cancel = "取消关机",
    shutdownNow = "立即关机",
)

private fun en() = ShutdownStrings(
    title = "Shut down when downloads finish",
    desc = "Once the queue drains (no downloading, queued or paused tasks), a 60-second countdown starts and can be cancelled at any time.",
    risk = "It only fires after downloads actually ran, and keeps working on other screens or while minimized to the tray. Shutting down needs OS permission; failures are only logged.",
    dialogTitle = "Shutting down soon",
    dialogMessage = { "All downloads have finished. This computer will shut down in $it seconds. Cancelling will not re-arm it." },
    cancel = "Cancel shutdown",
    shutdownNow = "Shut down now",
)

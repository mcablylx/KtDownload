package top.mcly99.dl.utils

/**
 * 「下载完成后关机」唯一的系统出口。
 *
 * 倒计时由 [top.mcly99.dl.viewmodel.ShutdownAfterCompleteController] 自己管，
 * 这里只发一条**立即生效**的系统关机请求：不调用 Windows 的 `shutdown /s /t 60`，
 * 因为那种系统级定时关机还得再发一条取消命令，一旦应用在倒计时期间崩溃就没人取消了。
 * 自研倒计时的好处是「应用没了 = 关机也不会发生」。
 *
 * 全程非阻塞：`ProcessBuilder.start()` 立即返回，退出码用 `onExit()` 异步观察，
 * 否则在主线程同步等待会把界面冻住几秒（而这时机器可能正在关机）。
 * 权限不足 / 系统不支持时只写日志，绝不抛到 UI 线程。
 */
fun requestSystemShutdown(): Boolean {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val candidates: List<List<String>> = when {
        os.contains("win") -> listOf(listOf("shutdown", "/s", "/t", "0"))
        os.contains("mac") || os.contains("darwin") -> listOf(
            listOf("osascript", "-e", "tell application \"System Events\" to shut down"),
        )
        else -> listOf(
            listOf("shutdown", "-h", "now"),
            listOf("systemctl", "poweroff"),
        )
    }
    for (command in candidates) {
        val launched = runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            process.onExit().thenAccept { done ->
                val code = runCatching { done.exitValue() }.getOrDefault(-1)
                if (code != 0) {
                    AppLogger.warn("DesktopActions", "系统关机命令返回 $code，可能被权限拒绝: ${command.first()}")
                }
            }
            true
        }.onFailure { e ->
            AppLogger.warn("DesktopActions", "无法发起系统关机 (${command.first()}): ${e.message}", e)
        }.getOrDefault(false)
        if (launched) return true
    }
    return false
}

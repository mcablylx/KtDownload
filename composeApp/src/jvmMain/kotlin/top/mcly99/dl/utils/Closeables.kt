package top.mcly99.dl.utils

import java.io.Closeable

/**
 * 收尾动作的静默执行（P2-E9）。
 *
 * 关闭流 / 断开连接 / 结束进程这些动作失败时**不应该**影响主流程（此时通常已经在
 * 异常分支里），所以异常必须吃掉；但「完全静默」会让排障时看不到「句柄其实没关掉」
 * 这类线索。这里统一在失败时记一条 debug 日志：默认不落盘，需要时开 `KTDL_DEBUG=1`
 * 就能看到真实原因。
 */
internal fun closeQuietly(closeable: Closeable?, tag: String, what: String = "资源") {
    if (closeable == null) return
    try {
        closeable.close()
    } catch (e: Exception) {
        AppLogger.debug(tag, "关闭${what}失败（忽略）: ${e.message}")
    }
}

/**
 * 同上，用于没有实现 [Closeable] 的收尾动作：
 * `HttpURLConnection.disconnect()`、`Process.destroyForcibly()`、`ExecutorService.shutdownNow()`。
 */
internal inline fun ignoreFailure(tag: String, what: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        AppLogger.debug(tag, "$what 失败（忽略）: ${e.message}")
    }
}

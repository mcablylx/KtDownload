/*
 * Compatibility shim for the ported YunX-Desktop cloud-drive resolver:
 * forwards upstream logging into KtDownload's file-based AppLogger.
 */
package top.mcly99.dl.utils

object Log {
    fun d(tag: String, message: String, error: Throwable? = null) = AppLogger.debug(tag, message)
    fun i(tag: String, message: String, error: Throwable? = null) = AppLogger.info(tag, message)
    fun w(tag: String, message: String, error: Throwable? = null) = AppLogger.warn(tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = AppLogger.error(tag, message, error)
}

object DiagnosticLog {
    private const val TAG = "YunX-Network"

    /** Diagnostic network logging is opt-in, same switch as KtDownload debug logs. */
    fun isEnabled(): Boolean = System.getenv("KTDL_DEBUG") == "1"

    fun network(
        method: String,
        url: String,
        code: Int,
        costMs: Long,
        requestBody: String? = null,
        responseBody: String? = null,
        error: String? = null,
    ) {
        if (!isEnabled()) return
        // Never persist query strings: signed share/CDN URLs carry tokens and cookies.
        val safeUrl = url.substringBefore('?')
        val suffix = buildString {
            error?.let { append(" error=$it") }
            requestBody?.let { append(" req=${it.take(512)}") }
            responseBody?.let { append(" resp=${it.take(512)}") }
        }
        AppLogger.debug(TAG, "$method $safeUrl -> $code (${costMs}ms)$suffix")
    }
}

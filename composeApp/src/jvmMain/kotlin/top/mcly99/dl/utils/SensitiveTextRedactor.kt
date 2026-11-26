package top.mcly99.dl.utils

/**
 * 日志与诊断文本的敏感信息脱敏（P1-07）。
 *
 * 走查发现 P1-4 的脱敏只覆盖了「snake_case 请求体」：响应体、异常文本、camelCase 键名
 * （`accessToken` / `captchaToken`）、以及业务日志里直接拼进来的完整 `$url`（带
 * `wsSecret` / `wsTime` / 签名）都原样落盘，而诊断包会把 `app-*.log` 一起收走。
 *
 * 这里提供一处共享实现，两个入口都会过：
 * - [top.mcly99.dl.data.network.DiagnosticNetworkInterceptor] 在记录 request/response/error 前；
 * - [AppLogger] 的 `DailyFileHandler.format`：所有 `debug/info/warn/error`（含异常堆栈）
 *   在写入按天滚动的日志文件前的**最后一道兜底**，以后新增的日志调用点也不会漏。
 *
 * 脱敏是幂等的：重复执行不会把 `***` / `?<redacted>` 再改坏。
 */
object SensitiveTextRedactor {

    /**
     * 键名采用**模糊包含**匹配：只要字段名里出现 token / secret / password / credential /
     * authorization / cookie / session / sign 等词根就视为敏感，不区分大小写。
     * 这样才能覆盖 `access_token`、`accessToken`、`captcha_token`、`captchaToken`、
     * `client_secret`、`refreshToken` 等多种写法，而不是 1.0.3 的精确枚举。
     */
    private const val SECRET_KEY =
        "[A-Za-z0-9_.\\-]*(?:password|passwd|pwd|passcode|token|secret|credential|" +
            "authorization|cookie|session|sekey|bdstoken|stoken|api_?key|sign)[A-Za-z0-9_.\\-]*"

    /** JSON 形态："password":"xxx" / "accessToken":123 */
    private val JSON_SECRET = Regex(
        """("(?:$SECRET_KEY)"\s*:\s*)("(?:[^"\\]|\\.)*"|[\w.+/-]+)""",
        RegexOption.IGNORE_CASE,
    )

    /** form-urlencoded 形态：password=xxx（保留 &/? 分隔符，避免把参数粘连） */
    private val FORM_SECRET = Regex(
        """((?:^|[&?])(?:$SECRET_KEY)=)([^&]*)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 文本中出现的 http(s) 地址：丢掉 query 与 fragment。
     * 直链签名、`wsSecret` / `wsTime`、OAuth code 都在 query 里，本身就是临时凭证。
     *
     * 字符类里**不能**排除 `<` / `>`：脱敏标记 `?<redacted>` 含这两个字符，排除了会让
     * 二次脱敏把已处理的 URL 重新截断、拼出 `?<redacted><redacted>`（幂等性回归）。
     */
    private val URL = Regex("""https?://[^\s"'\\,;)]+""")

    /** 把一段地址脱敏（只留 scheme + host + path）。 */
    fun redactUrl(url: String): String {
        val cut = url.indexOfFirst { it == '?' || it == '#' }
        return if (cut < 0) url else url.take(cut) + "?<redacted>"
    }

    /** 完整脱敏：先打码敏感字段值，再剥掉文本里 URL 的 query/fragment。 */
    fun redact(text: String): String {
        var out = JSON_SECRET.replace(text) { "${it.groupValues[1]}\"***\"" }
        out = FORM_SECRET.replace(out) { "${it.groupValues[1]}***" }
        out = URL.replace(out) { redactUrl(it.value) }
        return out
    }
}

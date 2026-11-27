package top.mcly99.dl.data.network

import org.json.JSONObject

/**
 * P2-06 / P2-07：把「HTTP 层失败」与「业务层失败」收敛成同一条判定。
 *
 * 旧写法 `if (status != 200 && code != 0) throw` 有两个洞：
 * 1. 业务失败常以 **HTTP 200 + code=31001**（登录失效 / 频控）返回，`status != 200` 为 false，
 *    `&&` 整体为 false → 错误被直接放行，上层拿到半截 `data` 或空数组；
 * 2. 失败响应往往**没有** `status` 字段，`optInt` 默认 0，只按 status 判断又会把正常响应判死。
 *
 * 因此：`status` 缺省视为 200，再用 `||` 组合。HTTP 非 2xx 由调用方先判（带状态码抛异常）。
 */
internal fun JSONObject.hasBusinessError(): Boolean =
    optInt("status", 200) != 200 || optInt("code") != 0

/**
 * P2-07：非 2xx 的响应体可能是 HTML 错误页 / 风控页。把它压成一行并截断后再拼进异常消息，
 * 既能定位问题，又不会让整页 HTML 灌进日志（诊断包会带日志入包）。
 */
internal fun bodySnippet(body: String, limit: Int = 200): String {
    val oneLine = body.replace(Regex("\\s+"), " ").trim()
    if (oneLine.isEmpty()) return ""
    val trimmed = if (oneLine.length > limit) oneLine.take(limit) + "…" else oneLine
    return "：$trimmed"
}

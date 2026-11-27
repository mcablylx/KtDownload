package top.mcly99.dl.data.network

/**
 * P2-09：UC「免转存 / 原画预览 / 转码播放」链路的**带原因结果**。
 *
 * 旧实现把整段链路包在 `runCatching { ... }.getOrNull()` 里：超时、403、连接重置、
 * JSON 解析失败与「服务端明确不支持」全被压成 `null`。上层把 null 一律解读成
 * 「不支持免转存」，于是回落去走另一条取链路径 —— 错误被静默吞掉，同一份失败
 * 请求还会再发一遍（请求放大），用户看到的却是与根因无关的报错。
 *
 * 三分法：
 * - [Success]：拿到了可用结果；
 * - [Unsupported]：服务端明确没有这项能力（2xx、无业务错误、但缺 data/url），
 *   这是**唯一**允许回落到其它取链路径的情形；
 * - [Unavailable]：网络 / HTTP / 鉴权 / 解析错误，不是能力缺失，必须把原因抛给上层。
 */
sealed interface UcProbeOutcome<out T> {
    data class Success<T>(val value: T) : UcProbeOutcome<T>

    /** 服务端明确没有该能力或数据；允许上层按既有策略回落 */
    object Unsupported : UcProbeOutcome<Nothing>

    /** 传输层或鉴权失败；[httpCode] 为 null 表示没拿到 HTTP 响应 */
    data class Unavailable(val reason: String, val httpCode: Int? = null) : UcProbeOutcome<Nothing>
}

/** 兼容旧调用点：只有 [UcProbeOutcome.Success] 才给出值，其余一律 null（不再区分原因）。 */
internal fun <T> UcProbeOutcome<T>.valueOrNull(): T? =
    (this as? UcProbeOutcome.Success)?.value

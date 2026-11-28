package top.mcly99.dl.engine

import java.io.IOException

/**
 * 段请求拿到非 2xx 时的异常。**必须带状态码**，因为「重不重试」和「是不是并发限制」
 * 全都取决于码：500/502/503/504 与 408/429 值得退避重试，403/404/410/416 重试多少次都一样。
 */
internal class SegmentHttpException(
    val status: Int,
    message: String,
) : Exception(message)

/**
 * 主机因为我们开了太多连接而拒绝（E 项的控制流）。
 *
 * 它不是失败：段带着游标回池，本连接退出，外层按学到的主机上限定新规划连接数。
 * 用异常是因为它要从 `transferSegment` 深处跳出来，且必须与其他 IOException 分开处理 ——
 * 退避重试解决不了「开太多」，只会继续撞墙。
 *
 * [observedCap] 是「被拒时能同时工作的连接数」：正常情况下就是当时**正在收数据**的条数
 * （它们证明了自己能工作）；如果一条都还没收到首字节就被拒（并发请求一起到达），
 * 退一步记「已尝试数 − 1」，保证每轮至少缩小一条、能收敛。
 */
internal class ConcurrencyRejected(
    val host: String,
    val observedCap: Int,
    val receivingConnections: Int,
    val status: Int,
) : Exception("主机限并发：HTTP $status（当时 $receivingConnections 条在收数据）")

/** 这些状态码可能是「并发太多」而不是「资源不可用」 */
internal fun isConcurrencyStatus(status: Int): Boolean = status == 403 || status == 429 || status == 503

/**
 * 该错误是否值得重试。
 *
 * - 5xx / 408 / 429：服务端临时问题，退避后大概率能过；
 * - [IOException]：断流、连接重置、读超时 —— 网络抖动的典型表现；
 * - 其余（4xx、协议不符、写入失败）一律不重试：它们不会因为「再试一次」而变好，
 *   重试只是把「立即失败」变成「几秒后失败」。
 */
internal fun isRetryableSegmentFailure(error: Throwable): Boolean = when (error) {
    is SegmentHttpException -> error.status == 408 || error.status == 429 || error.status >= 500
    is IOException -> true
    else -> false
}

/**
 * 重试间隔：**首次立即**，之后指数退避，封顶 [capMs]。
 *
 * 首次不等待是有意为之：断流往往发生在建连瞬间，立刻重连几乎总能成功，白等 500 ms
 * 只会让用户觉得「卡了一下」。从第二次开始才是真的撞到问题，才需要让服务端喘口气。
 *
 * @param failures 已经失败的次数（1 = 第一次刚失败）
 */
internal fun retryBackoffMs(
    failures: Int,
    baseMs: Long = 500L,
    capMs: Long = 8_000L,
): Long {
    if (failures <= 1) return 0L
    val exponent = failures - 2
    if (exponent >= 6) return capMs
    return minOf(capMs, baseMs shl exponent)
}

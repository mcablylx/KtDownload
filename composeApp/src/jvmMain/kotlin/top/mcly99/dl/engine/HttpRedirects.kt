package top.mcly99.dl.engine

import java.io.IOException
import java.net.URI

/**
 * 重定向跟随的公共细节。
 *
 * 为什么需要自己跟随：`HttpURLConnection.instanceFollowRedirects` 在跳转时会**复制用户设置的
 * 请求头**，`Cookie` 也在其中 —— 于是 `.a.com` 的会话会被 302 带到 `.b.com`（CDN 跳 CDN、
 * 或者被劫持的跳转）。只有自己跟随，才有机会在 host 变化时把 Cookie 摘掉（v7-P2-2）。
 *
 * 放在这里而不是各写一遍：`DownloadEngine`、`HlsDownloader`、`HttpProbe` 三处都要用，
 * 而「跳几跳」「哪些状态码算重定向」「host 怎么比」这类规则一旦分叉，排查起来会很痛苦
 * —— 上一轮三条 P1 里两条的根源就是「同一份逻辑复制了四遍」。
 */
internal object HttpRedirects {

    /** 最多跟随几跳；超过按失败处理，避免环形跳转把线程卡死 */
    const val MAX_HOPS = 5

    /** 需要跟随的状态码；303/307/308 与 301/302 在这里不做区分（都不改请求方法） */
    val CODES: Set<Int> = setOf(301, 302, 303, 307, 308)

    /**
     * 解析 `Location`。
     *
     * 必须支持相对地址（`Location: /a/b.m3u8`）：CDN 的 302 里相对写法很常见，
     * 直接当绝对地址用会拼出 `http://host/path/a/b.m3u8` 这种错路径。
     */
    fun resolve(base: String, location: String): String =
        runCatching { URI(base).resolve(location).toString() }
            .getOrElse { throw IOException("无法解析重定向地址：$location") }

    /** host 相同（不区分大小写）；解析失败一律视为「不同」，宁可少带 Cookie */
    fun sameHost(a: String, b: String): Boolean {
        val hostA = runCatching { URI(a).host?.lowercase() }.getOrNull() ?: return false
        val hostB = runCatching { URI(b).host?.lowercase() }.getOrNull() ?: return false
        return hostA == hostB
    }
}

package top.mcly99.dl.engine.web

import top.mcly99.dl.engine.HttpRedirects
import top.mcly99.dl.engine.setSafeHeader
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * 一次成功的文本探测结果。
 *
 * @property text 响应正文（已去 BOM）
 * @property finalUrl 跟随重定向后的最终地址；解析相对路径必须用它
 */
data class ProbedText(val text: String, val finalUrl: String)

/**
 * 针对**媒体地址**（播放列表、视频文件）的轻量 HTTP 探测。
 *
 * 和 [WebPageFetcher] 分开是有原因的：那个类把「不是 HTML 就失败」写进了语义
 * （抓网页场景下这是对的），而这里要读的恰恰是 `application/vnd.apple.mpegurl`
 * 和 `video/mp4`，复用会把两边的判定都搞乱。
 *
 * 两个能力：
 * - [fetchText] 读播放列表文本（清晰度选择器用）
 * - [contentLength] 探文件大小（「探文件大小」开关用，默认关闭）
 *
 * 全部失败都返回 `null` 而不是抛异常：这两个功能都是**锦上添花**，
 * 探不到就不显示，绝不能让播放或列表本身失败。
 *
 * @param proxyProvider 每次请求现取代理（设置页改了代理要立刻生效）
 */
class HttpProbe(
    private val proxyProvider: () -> Proxy? = { null },
    private val userAgent: String = AppConstants.CATCH_WEB_USER_AGENT,
    private val connectTimeoutMs: Int = AppConstants.CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = AppConstants.READ_TIMEOUT_MS,
) {

    /**
     * 读一段文本（播放列表）。
     *
     * m3u8 规范要求 UTF-8，这里不做字符集嗅探 —— 与网页不同，播放列表里
     * 没有 `<meta charset>`，猜错的概率远大于猜对。
     *
     * @param referer 防盗链 `Referer`；主播放列表本身也常校验它
     * @param cookie 会话 `Cookie`（深度抓取带回来的那一份）；缺了它签名列表会 403（v7-P1-1）
     * @return 文本；请求失败、非 2xx 或内容为空时返回 null
     */
    fun fetchText(
        url: String,
        referer: String? = null,
        maxBytes: Int = AppConstants.PLAYER_PLAYLIST_MAX_BYTES,
        cookie: String? = null,
    ): String? = fetchTextWithUrl(url, referer, maxBytes, cookie)?.text

    /**
     * 同 [fetchText]，但把重定向后的**最终地址**一起返回。
     *
     * 清晰度档位里的相对路径必须基于这个地址解析：主列表 302 到另一个目录时，
     * 拿用户输入的原始地址去拼会得到 404。
     */
    fun fetchTextWithUrl(
        url: String,
        referer: String? = null,
        maxBytes: Int = AppConstants.PLAYER_PLAYLIST_MAX_BYTES,
        cookie: String? = null,
    ): ProbedText? {
        val connection = open(url, referer, cookie) ?: return null
        try {
            // 请求方法必须在 rawConnection 里、connect 之前设好。cookie 路径会
            // 在 open() 内手动跟随重定向并读 responseCode，连接此时已建立；
            // 在这里再 setRequestMethod 会抛 Already connected 被吞成 null（P0-1）。
            val code = connection.responseCode
            if (code !in 200..299) {
                AppLogger.debug("HttpProbe", "播放列表请求失败: $code ${redactUrl(url)}")
                return null
            }
            val body = readLimited(connection.inputStream, maxBytes)
            if (body.isEmpty()) return null
            val text = String(body, StandardCharsets.UTF_8).removePrefix("\uFEFF")
            return ProbedText(text, connection.url.toString())
        } catch (e: Exception) {
            // e.message 也可能带完整 URL：交给共享脱敏器兜底，不把签名参数写进日志
            AppLogger.debug(
                "HttpProbe",
                "读取播放列表失败: ${top.mcly99.dl.utils.SensitiveTextRedactor.redact(e.message.orEmpty())} (${redactUrl(url)})",
            )
            return null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /**
     * 探文件字节数。
     *
     * 先 `HEAD`（最省流量），失败再退到 `Range: bytes=0-0` 的 GET：
     * 大量 CDN 直接对 HEAD 返回 403/405，而带 Range 的 GET 反而正常，
     * 且只传 1 个字节。两条路都拿不到就返回 null（大小未知，UI 不显示）。
     *
     * @return 字节数；未知或失败时为 null
     */
    fun contentLength(url: String, referer: String? = null): Long? =
        headLength(url, referer) ?: rangeLength(url, referer)

    // ------------------------------------------------------------------ 内部

    private fun headLength(url: String, referer: String?): Long? {
        val connection = open(url, referer) ?: return null
        try {
            connection.requestMethod = "HEAD"
            val code = connection.responseCode
            if (code !in 200..299) return null
            // contentLengthLong 在缺头时返回 -1
            return connection.contentLengthLong.takeIf { it > 0L }
        } catch (e: Exception) {
            AppLogger.debug("HttpProbe", "HEAD 失败，改试 Range: ${e.message} ($url)")
            return null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun rangeLength(url: String, referer: String?): Long? {
        val connection = open(url, referer) ?: return null
        try {
            connection.requestMethod = "GET"
            connection.setSafeHeader("Range", "bytes=0-0")
            val code = connection.responseCode
            // 206 才有 Content-Range；有的服务器忽略 Range 直接 200 + 完整长度
            if (code == 200) return connection.contentLengthLong.takeIf { it > 0L }
            if (code != 206) return null
            val range = connection.getHeaderField("Content-Range") ?: return null
            return parseTotalFromContentRange(range)
        } catch (e: Exception) {
            AppLogger.debug("HttpProbe", "Range 探测失败: ${e.message} ($url)")
            return null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /**
     * 建连接。
     *
     * 带 `Cookie` 时不交给 JDK 自动跟随：自动重定向会复制请求头，跨域时把会话送到第三方（v7-P2-2）。
     * 探测只读一份播放列表，手动跟随的代价可以忽略。
     */
    private fun open(url: String, referer: String?, cookie: String? = null): HttpURLConnection? = runCatching {
        if (cookie.isNullOrBlank()) {
            rawConnection(url, referer, null, followRedirects = true)
        } else {
            followRedirectsKeepingCookie(url, referer, cookie)
        }
    }.getOrElse {
        AppLogger.debug("HttpProbe", "无法建立连接: ${it.message} ($url)")
        null
    }

    private fun followRedirectsKeepingCookie(url: String, referer: String?, cookie: String): HttpURLConnection {
        var current = url
        var currentCookie: String? = cookie
        repeat(HttpRedirects.MAX_HOPS) {
            val connection = rawConnection(current, referer, currentCookie, followRedirects = false)
            val code = connection.responseCode
            if (code !in HttpRedirects.CODES) return connection
            val location = connection.getHeaderField("Location")
            runCatching { connection.disconnect() }
            if (location.isNullOrBlank()) throw IOException("HTTP $code 但没有 Location 头")
            val next = HttpRedirects.resolve(current, location)
            if (!HttpRedirects.sameHost(current, next)) currentCookie = null
            current = next
        }
        throw IOException("重定向次数超过 ${HttpRedirects.MAX_HOPS} 次：$url")
    }

    private fun rawConnection(
        url: String,
        referer: String?,
        cookie: String?,
        followRedirects: Boolean,
    ): HttpURLConnection {
        // URI.create + toURL：URL(String) 从 JDK 20 起已弃用
        val target = URI.create(url).toURL()
        val proxy = proxyProvider()
        val raw = if (proxy != null) target.openConnection(proxy) else target.openConnection()
        return (raw as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            // 方法必须在建连前定好：cookie 分支会在 open() 内读 responseCode 建立连接。
            // 默认 GET；headLength/rangeLength 在 open() 后、未建连前覆盖为 HEAD/GET。
            requestMethod = "GET"
            instanceFollowRedirects = followRedirects
            useCaches = false
            setSafeHeader("User-Agent", userAgent)
            setSafeHeader("Accept", "*/*")
            // HttpURLConnection 不会自动解压；播放列表/视频本来也不该压缩
            setSafeHeader("Accept-Encoding", "identity")
            referer?.takeIf { it.isNotBlank() }?.let { setSafeHeader("Referer", it) }
            cookie?.takeIf { it.isNotBlank() }?.let { setSafeHeader("Cookie", it) }
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 32 * 1024))
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (total < limit) {
            val read = input.read(chunk)
            if (read < 0) break
            val allowed = minOf(read, limit - total)
            output.write(chunk, 0, allowed)
            total += allowed
        }
        return output.toByteArray()
    }

    companion object {
        /**
         * 从 `Content-Range: bytes 0-0/123456` 里取总长度。
         *
         * 总长可能是 `*`（服务器不知道），这时返回 null。
         */
        fun parseTotalFromContentRange(header: String): Long? {
            val total = header.substringAfterLast('/', "").trim()
            if (total.isEmpty() || total == "*") return null
            return total.toLongOrNull()?.takeIf { it > 0L }
        }
    }
}

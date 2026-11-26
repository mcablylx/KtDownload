package top.mcly99.dl.engine.web

import kotlinx.coroutines.delay
import top.mcly99.dl.engine.setSafeHeader
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * 一次成功抓取的结果。
 *
 * @property finalUrl 跟随重定向后的地址 —— 相对路径必须基于它解析，不是用户输入的地址
 * @property html 解码后的 HTML 文本（超过上限时已截断）
 * @property charset 实际使用的字符集名，出问题时打日志用
 */
data class FetchedPage(
    val finalUrl: String,
    val html: String,
    val charset: String,
)

/**
 * 抓取失败的原因。
 *
 * 分成几类而不是统一一个 `Exception`：UI 要给不同文案，用户才知道是自己输错了、
 * 站点不给抓、还是网络不通。
 */
sealed class FetchFailure(message: String) : Exception(message) {
    class InvalidUrl(input: String) : FetchFailure("不是合法的 http(s) 地址：$input")

    class NotHtml(contentType: String) : FetchFailure("目标不是网页（$contentType）")

    class HttpError(val code: Int, detail: String?) :
        FetchFailure("服务器返回 $code${detail?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}")

    class NetworkError(cause: Throwable) :
        FetchFailure("网络错误：${cause.message ?: cause::class.simpleName}")
}

/**
 * 抓取网页 HTML。
 *
 * 只做一件事：给一个地址，拿回可解析的 HTML 文本。编码、重定向、大小上限、代理都在这里处理，
 * 提取逻辑在 [HtmlMediaExtractor] 里，两者互不知道对方的存在。
 *
 * 用 `HttpURLConnection` 而不是 OkHttp：项目没有 OkHttp 依赖，为这一个请求引入整个库不划算。
 *
 * @param proxyProvider 每次请求时取当前代理（设置页改了代理要立刻生效，不能构造时快照）
 * @param maxBytes 正文上限，超过就截断 —— 目的是不 OOM，不是拒绝服务
 */
open class WebPageFetcher(
    private val proxyProvider: () -> Proxy? = { null },
    private val userAgent: String = AppConstants.CATCH_WEB_USER_AGENT,
    private val maxBytes: Int = AppConstants.CATCH_WEB_MAX_PAGE_BYTES,
) {

    /** 在途连接；[cancelAll] 靠它把阻塞读打断 */
    private val activeConnections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()

    /**
     * 抓取；调用方负责切到 IO 线程。
     *
     * 5xx 会**自动重试一次**：Cloudflare 的 522（连不上源站）与 502/503/504 多半是源站
     * 瞬时超时，隔半秒再请求经常就正常了。4xx 不重试 —— 地址错了、被防盗链挡了，
     * 重试一百次也是同样的结果，只会让用户多等。
     *
     * 连接被重置 / DNS 抖动（`NetworkError`）同样重试一次，但**超时不重试**：
     * 超时说明站点慢或不可达，再来一次只是让用户多等一个超时周期。
     *
     * 重试等待用 `delay` 而不是 `Thread.sleep`：协程取消要能立刻打断，否则用户点了
     * 「取消」后台还会多跑 700ms，再点抓取就叠了一批请求。
     *
     * @param referer 防盗链 `Referer`；跟 iframe 时传主页面地址，嵌入播放器常校验它
     * @throws FetchFailure 四类失败各有对应子类
     */
    open suspend fun fetch(rawUrl: String, referer: String? = null): FetchedPage {
        val url = normalizeInput(rawUrl) ?: throw FetchFailure.InvalidUrl(rawUrl)
        var attempt = 1
        while (true) {
            try {
                return fetchOnce(url, referer)
            } catch (e: FetchFailure.HttpError) {
                if (e.code !in RETRYABLE_STATUS || attempt >= MAX_ATTEMPTS) throw e
                attempt++
                AppLogger.debug(
                    "WebPageFetcher",
                    "服务器返回 ${e.code}，${RETRY_DELAY_MS}ms 后重试（第 $attempt 次）: $url",
                )
                delay(RETRY_DELAY_MS)
            } catch (e: FetchFailure.NetworkError) {
                val timeout = e.cause is SocketTimeoutException
                if (timeout || attempt >= MAX_ATTEMPTS) throw e
                attempt++
                AppLogger.debug(
                    "WebPageFetcher",
                    "网络错误，${RETRY_DELAY_MS}ms 后重试（第 $attempt 次）: $url",
                )
                delay(RETRY_DELAY_MS)
            }
        }
    }

    /**
     * 断开当前所有在途连接。
     *
     * 协程取消只能停在挂起点，阻塞在 `HttpURLConnection` 读上的 IO 线程不会自己醒；
     * 用户点「取消」时由调用方调这里，让卡住的请求立刻失败退出。
     */
    fun cancelAll() {
        activeConnections.forEach { runCatching { it.disconnect() } }
        activeConnections.clear()
    }

    private fun fetchOnce(url: URL, referer: String?): FetchedPage {
        var connection: HttpURLConnection? = null
        try {
            val opened = openConnection(url, referer)
            connection = opened
            activeConnections += opened
            val code = opened.responseCode
            if (code !in 200..299) {
                // 把 CF 的追踪信息记下来：522 = CF 连不上源站，403/503 可能是挑战页，
                // 没有 cf-ray 就只能靠猜「到底谁挡的」
                AppLogger.warn(
                    "WebPageFetcher",
                    "HTTP $code ${connection.responseMessage.orEmpty()} url=$url " +
                        "server=${connection.getHeaderField("Server").orEmpty()} " +
                        "cf-ray=${connection.getHeaderField("cf-ray").orEmpty()} " +
                        "cf-mitigated=${connection.getHeaderField("cf-mitigated").orEmpty()}",
                )
                throw FetchFailure.HttpError(code, connection.responseMessage)
            }
            val contentType = connection.contentType.orEmpty()
            if (contentType.isNotBlank() && !looksLikeHtml(contentType)) {
                throw FetchFailure.NotHtml(contentType)
            }

            // 少数服务器无视 identity 仍然返回 gzip，不兜底的话拿到的是一堆压缩字节，
            // 提取器一条地址也匹配不到，用户只会看到「没找到视频」
            val encoding = connection.getHeaderField("Content-Encoding").orEmpty()
            val stream = connection.inputStream.let { raw ->
                if (encoding.contains("gzip", ignoreCase = true)) GZIPInputStream(raw) else raw
            }
            val body = readLimited(stream, maxBytes)
            val charset = resolveCharset(contentType, body)
            // 用 String(bytes, charset) 而不是严格解码器：截断可能切在多字节字符中间，
            // 严格模式会直接抛异常，而我们要的是「尽量解析」。
            val html = String(body, charset).removePrefix("\uFEFF")
            AppLogger.debug(
                "WebPageFetcher",
                "抓取成功: ${redactUrl(connection.url.toString())} (${body.size} 字节, $charset)",
            )
            return FetchedPage(connection.url.toString(), html, charset.name())
        } catch (e: FetchFailure) {
            throw e
        } catch (e: IOException) {
            throw FetchFailure.NetworkError(e)
        } catch (e: Exception) {
            // URL 构造、非法响应头等运行时异常，一并当作网络失败
            throw FetchFailure.NetworkError(e)
        } finally {
            connection?.let { activeConnections -= it }
            runCatching { connection?.disconnect() }
        }
    }

    // ------------------------------------------------------------------ 请求

    /**
     * 宽松处理用户输入：`example.com/video` 补成 `https://example.com/video`，
     * 免得用户因为少打协议头就得到一个「地址非法」。
     *
     * 但明确是别的协议的（`magnet:` / `ftp:` / `javascript:`）直接拒绝 ——
     * 给它们补上 `https://` 会变成 `https://magnet:?xt=…` 这种能通过 URL 校验的怪东西，
     * 最后报「网络错误」，用户完全看不出是自己贴错了东西。
     */
    private fun normalizeInput(raw: String): URL? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (NON_WEB_SCHEME.containsMatchIn(trimmed)) return null
        val withScheme = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            !trimmed.contains("://") -> "https://$trimmed"
            else -> trimmed
        }
        return try {
            // 不用已弃用的 URL(String)：URI.create 对空格/非法字符更严格，正好帮我们把明显错的输入挡掉
            val url = URI.create(withScheme).toURL()
            if (url.protocol.lowercase() !in SUPPORTED_SCHEMES) return null
            if (url.host.isBlank()) return null
            url
        } catch (_: Exception) {
            null
        }
    }

    private fun openConnection(url: URL, referer: String?): HttpURLConnection {
        val proxy = proxyProvider()
        val raw = if (proxy != null) url.openConnection(proxy) else url.openConnection()
        return (raw as HttpURLConnection).apply {
            connectTimeout = AppConstants.CONNECT_TIMEOUT_MS
            readTimeout = AppConstants.READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setSafeHeader("User-Agent", userAgent)
            setSafeHeader(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )
            setSafeHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            // 主动要 identity：多数站点会照办，省一次解压。万一无视，fetchOnce 里还有 gzip 兜底
            setSafeHeader("Accept-Encoding", "identity")
            referer?.takeIf { it.isNotBlank() }?.let { setSafeHeader("Referer", it) }
        }
    }

    /** 只拦「明确不是网页」的响应；缺 Content-Type 的服务器很多，不能因此判失败 */
    private fun looksLikeHtml(contentType: String): Boolean {
        val mime = contentType.substringBefore(';').trim().lowercase()
        return mime.startsWith("text/") || mime.contains("html") || mime.contains("xml")
    }

    // ------------------------------------------------------------------ 正文

    /**
     * 读到上限就停。
     *
     * 不抛「文件太大」：真实页面 90% 的字节是内联脚本和样式，媒体地址几乎都在前 1 MB，
     * 截断比直接失败有用得多。
     */
    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (total < limit) {
            val read = input.read(chunk)
            if (read < 0) break
            val allowed = minOf(read, limit - total)
            output.write(chunk, 0, allowed)
            total += allowed
        }
        if (total >= limit) {
            AppLogger.warn("WebPageFetcher", "页面超过 $limit 字节，已截断")
        }
        return output.toByteArray()
    }

    // ------------------------------------------------------------------ 编码

    /**
     * 字符集优先级：BOM > 响应头 charset > `<meta charset>` > UTF-8。
     *
     * 真实中文站有大量 GBK / GB2312 页面，且经常只在 meta 里声明 —— 只读响应头会得到乱码，
     * 乱码之后连媒体地址都匹配不上。
     */
    private fun resolveCharset(contentType: String, body: ByteArray): Charset {
        if (body.hasUtf8Bom()) return StandardCharsets.UTF_8
        charsetInContentType(contentType)?.let { return it }
        charsetInMeta(body)?.let { return it }
        return StandardCharsets.UTF_8
    }

    private fun charsetInContentType(contentType: String): Charset? =
        CHARSET_IN_CONTENT_TYPE.find(contentType)?.groupValues?.get(1)?.let(::toCharset)

    private fun charsetInMeta(body: ByteArray): Charset? {
        val head = String(body, 0, minOf(body.size, META_SNIFF_BYTES), StandardCharsets.ISO_8859_1)
        return CHARSET_IN_META.find(head)?.groupValues?.get(1)?.let(::toCharset)
    }

    private fun toCharset(name: String): Charset? = runCatching {
        when (name.trim().lowercase()) {
            // GBK 是 GB2312 的超集，用 GBK 解 GB2312 页面只会更准
            "gb2312", "gb-2312", "gbk" -> Charset.forName("GBK")
            else -> Charset.forName(name.trim())
        }
    }.getOrNull()

    private fun ByteArray.hasUtf8Bom(): Boolean =
        size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()

    private companion object {
        val SUPPORTED_SCHEMES = setOf("http", "https")

        /** 源站瞬时故障，值得再试一次；4xx 是确定性的，不重试 */
        val RETRYABLE_STATUS = 500..599
        const val MAX_ATTEMPTS = 2

        /** 重试间隔：够短不让人等，够长让源站缓过来 */
        const val RETRY_DELAY_MS = 700L

        /** 明确不是网页的协议：命中就判为非法地址，而不是硬补 `https://` */
        val NON_WEB_SCHEME = Regex(
            """^(?:magnet|ftp|ftps|file|data|javascript|blob|mailto|ed2k|thunder|bt):""",
            RegexOption.IGNORE_CASE,
        )

        /** meta charset 只可能在文档头部，扫前 8 KB 足够 */
        const val META_SNIFF_BYTES = 8 * 1024

        val CHARSET_IN_CONTENT_TYPE = Regex("""(?i)charset\s*=\s*["']?([\w.:-]+)""")
        val CHARSET_IN_META = Regex("""(?i)<meta[^>]+charset\s*=\s*["']?([\w.:-]+)""")
    }
}

package top.mcly99.dl.engine.web

import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.isSupportedDownloadUrl
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 从 HTML 文本里提取媒体地址。
 *
 * **纯函数**：不碰网络、不碰文件，签名是 `String -> List<MediaCandidate>`。
 * 这样 90% 的 bug 都能用单测覆盖，不用起服务器。
 *
 * 三条通道：
 * - **A 标签属性**：`src` / `href` / `data-src` / `data-url` …（顺带取锚文本当名称）
 * - **B 引号内绝对地址**：内联脚本里的 `"url":"https://…/index.m3u8"`
 * - **C 引号内相对路径**：`"../v/a.mp4"` / `"/v/a.mp4"`
 *
 * 能力边界（写进 README 与界面提示，这是原理限制不是 bug）：
 * 不执行 JavaScript，运行时拼接、XHR 拉取、`blob:` 地址都抓不到；
 * 纯前端渲染的页面可能一条都抓不到。
 */
object HtmlMediaExtractor {

    /** 单条地址的长度上限；超过基本可以确定是误匹配（例如把整段 base64 当成 URL） */
    private const val MAX_URL_LENGTH = 2048

    /** 锚文本 / 页面标题的长度上限，避免把整段正文当名称 */
    private const val MAX_NAME_LENGTH = 80

    /** `<title>` 只在开头这段范围内找，超大页面不必全量扫 */
    private const val TITLE_SEARCH_LIMIT = 64 * 1024

    /**
     * @param html 服务端返回的 HTML 文本（已解码成 String）
     * @param pageUrl 页面最终地址，用于解析相对路径；解析失败时只接受绝对地址
     * @param maxResults 结果上限，防止一个页面列出几千条把 UI 拖死
     */
    fun extract(
        html: String,
        pageUrl: String,
        maxResults: Int = AppConstants.CATCH_WEB_MAX_RESULTS,
    ): List<MediaCandidate> {
        if (html.isBlank()) return emptyList()

        // 转义还原必须在扫描之前：`"https:\/\/cdn\/a.m3u8"` 里的反斜杠不在候选字符集内，
        // 先扫后还原的话这条地址根本匹配不上（国内站点极常见）。
        val document = unescape(html)
        val base = runCatching { URI(pageUrl.trim()) }.getOrNull()
        val anchorNames = collectAnchorNames(document, base)

        // LinkedHashMap 保证「先到先得 + 保持页面出现顺序」
        val found = LinkedHashMap<String, MediaCandidate>()
        ATTRIBUTE_PATTERN.findAll(document).forEach { match ->
            found.collect(match.groupValues[1], MediaCandidate.Source.Attribute, base)
        }
        // 社交分享卡片：`<meta property="og:video" content="…mp4">` / `twitter:player:stream`
        META_PATTERN.findAll(document).forEach { meta ->
            CONTENT_ATTRIBUTE.find(meta.value)?.let { match ->
                found.collect(match.groupValues[1], MediaCandidate.Source.Attribute, base)
            }
        }
        QUOTED_ABSOLUTE_PATTERN.findAll(document).forEach { match ->
            found.collect(match.groupValues[1], MediaCandidate.Source.InlineScript, base)
        }
        QUOTED_RELATIVE_PATTERN.findAll(document).forEach { match ->
            found.collect(match.groupValues[1], MediaCandidate.Source.RelativePath, base)
        }
        if (found.isEmpty()) return emptyList()

        // sortedBy 是稳定排序：同类型内部保持页面出现顺序
        val ordered = found.values
            .sortedBy { it.kind.ordinal }
            .take(maxResults)

        val pageTitle = pageTitle(document)
        // 每条候选都记住「它来自哪个页面」：下载时要拿它当 Referer，跨域 CDN 认这个
        val referer = pageUrl.trim().takeIf { it.isNotEmpty() }
        return ordered.mapIndexed { index, candidate ->
            candidate.copy(
                name = nameFor(candidate, index, ordered.size, anchorNames, pageTitle),
                referer = referer,
            )
        }
    }

    /**
     * 取页面里的 iframe / frame 地址（**只取一层，不递归**）。
     *
     * 很多站点的播放器放在独立 iframe 里，主页面一条媒体地址都没有；
     * 不做这一步，这类站点会全部显示「没找到」。
     *
     * 页面上广告 / 统计 iframe 往往排在播放器前面，按文档顺序取前几个会被它们占满，
     * 所以先剔除已知的广告域名，再按「像播放器」打分排序（同分保持文档顺序）。
     *
     * @param limit 最多返回几个：页面上大量广告 iframe，全抓既慢又没意义
     */
    fun extractFrames(
        html: String,
        pageUrl: String,
        limit: Int = AppConstants.CATCH_WEB_MAX_FRAMES,
    ): List<String> {
        if (html.isBlank() || limit <= 0) return emptyList()
        val document = unescape(html)
        val base = runCatching { URI(pageUrl.trim()) }.getOrNull()
        val frames = LinkedHashSet<String>()
        FRAME_PATTERN.findAll(document).forEach frame@{ tag ->
            // 一个标签里可能同时有 src（占位）与 data-src（真实地址），逐个看
            FRAME_SRC_ATTRIBUTE.findAll(tag.groupValues[1]).forEach attr@{ attr ->
                val absolute = absoluteUrl(attr.groupValues[1].trim(), base) ?: return@attr
                // about:blank / javascript: 已在 absoluteUrl 里被挡掉
                if (isAdFrame(absolute)) return@attr
                frames += absolute
            }
        }
        return frames
            .map { it to frameScore(it) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    // ------------------------------------------------------------------ 通道收集

    /**
     * 归一化后放进结果表。
     *
     * 顺序很讲究：先解析绝对地址再判协议 —— 相对路径只有拼上 base 之后才知道是不是 http(s)。
     */
    private fun MutableMap<String, MediaCandidate>.collect(
        raw: String,
        source: MediaCandidate.Source,
        base: URI?,
    ) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) return
        val absolute = absoluteUrl(trimmed, base) ?: return
        if (containsKey(absolute)) return
        // 复用既有的协议白名单：挡掉 javascript: / magnet: / data: 等
        if (!isSupportedDownloadUrl(absolute)) return
        val kind = MediaKind.fromUrl(absolute) ?: return
        put(absolute, MediaCandidate(absolute, kind, "", source))
    }

    /**
     * 把候选解析成绝对地址。
     *
     * 返回 `toASCIIString()`：中文路径会被百分号编码，交给 HttpURLConnection / libvlc 都安全。
     */
    private fun absoluteUrl(raw: String, base: URI?): String? {
        val candidate = if (raw.startsWith("//")) "https:$raw" else raw
        return try {
            val escaped = escapeForUri(candidate)
            val resolved = if (base != null) base.resolve(escaped) else URI(escaped)
            val scheme = resolved.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            resolved.toASCIIString()
        } catch (_: Exception) {
            // 仍然无法解析的（畸形地址）直接丢掉
            null
        }
    }

    /**
     * 把 URI 不接受的字符转成百分号编码。
     *
     * `URI` 的构造器很严格：中文、空格、`[` `]` `|` 都会直接抛异常。
     * 但真实页面里「视频/第3集.mp4」「my video.mp4」这类地址很常见，
     * 不做这一步等于把所有中文文件名的媒体都丢掉。
     *
     * `%` 保持原样：已经编码过的 `%2F` 不能被二次编码成 `%252F`。
     */
    private fun escapeForUri(raw: String): String {
        var needsEscape = false
        for (ch in raw) {
            if (ch.code > 0x7F || ch in ILLEGAL_URI_CHARS) {
                needsEscape = true
                break
            }
        }
        if (!needsEscape) return raw

        val builder = StringBuilder(raw.length + 16)
        for (ch in raw) {
            if (ch.code > 0x7F || ch in ILLEGAL_URI_CHARS) {
                for (byte in ch.toString().toByteArray(StandardCharsets.UTF_8)) {
                    builder.append('%').append(HEX[(byte.toInt() shr 4) and 0xF])
                        .append(HEX[byte.toInt() and 0xF])
                }
            } else {
                builder.append(ch)
            }
        }
        return builder.toString()
    }

    /**
     * 收集 `<a href="…">锚文本</a>` 里的名称。
     *
     * 单独扫一遍而不是和属性通道合并：属性通道只需要 URL，
     * 而名称要连带标签正文，两者匹配范围不同，混在一起正则会复杂到没法维护。
     */
    private fun collectAnchorNames(document: String, base: URI?): Map<String, String> {
        val names = HashMap<String, String>()
        ANCHOR_PATTERN.findAll(document).forEach { match ->
            val attributes = match.groupValues[1]
            val href = HREF_ATTRIBUTE.find(attributes)?.groupValues?.get(1) ?: return@forEach
            val text = cleanText(match.groupValues[2]).ifBlank {
                TITLE_ATTRIBUTE.find(attributes)?.groupValues?.get(1)?.let(::cleanText).orEmpty()
            }
            if (text.isBlank()) return@forEach
            val absolute = absoluteUrl(href.trim(), base) ?: return@forEach
            names.putIfAbsent(absolute, text)
        }
        return names
    }

    // ------------------------------------------------------------------ 命名

    /**
     * 名称优先级：锚文本 > 页面标题（多条时带序号）> URL 末段。
     *
     * 拿不到时返回空串，由 UI 兜底成「媒体 N」—— 序号要按**展示顺序**编，
     * 排序发生在提取器里，所以兜底放在 UI 比放这里更准确。
     */
    private fun nameFor(
        candidate: MediaCandidate,
        index: Int,
        total: Int,
        anchorNames: Map<String, String>,
        pageTitle: String?,
    ): String {
        anchorNames[candidate.url]?.takeIf { it.isNotBlank() }?.let { return it }
        if (pageTitle != null) {
            return if (total > 1) "$pageTitle - ${index + 1}" else pageTitle
        }
        return urlName(candidate.url)
    }

    /** URL 末段去掉扩展名并解码：`/hls/index.m3u8` → `index` */
    private fun urlName(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val lastSegment = path.substringAfterLast('/')
        if (lastSegment.isBlank()) return ""
        // URLDecoder 会把 `+` 当空格；路径里的 `+` 是字面量，先转义再解码
        val decoded = runCatching {
            URLDecoder.decode(lastSegment.replace("+", "%2B"), StandardCharsets.UTF_8)
        }.getOrDefault(lastSegment)
        val dot = decoded.lastIndexOf('.')
        val base = if (dot > 0) decoded.substring(0, dot) else decoded
        return base.trim().take(MAX_NAME_LENGTH)
    }

    private fun pageTitle(document: String): String? {
        val head = document.take(TITLE_SEARCH_LIMIT)
        return TITLE_TAG_PATTERN.find(head)
            ?.groupValues?.get(1)
            ?.let(::cleanText)
            ?.takeIf { it.isNotBlank() }
    }

    /** 去掉标签、压缩空白、还原常见实体 */
    private fun cleanText(raw: String): String = raw
        .replace(TAG_PATTERN, " ")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace(WHITESPACE_PATTERN, " ")
        .trim()
        .take(MAX_NAME_LENGTH)

    // ------------------------------------------------------------------ 转义还原

    /**
     * 一次性还原常见的转义写法。
     *
     * 用一个正则扫一遍而不是连续 `replace` 若干次：8 MB 的页面做十几次全量替换太浪费，
     * 而且分开替换容易把已经还原过的内容再处理一次。
     */
    private fun unescape(html: String): String {
        // 快速路径：绝大多数页面没有任何需要还原的转义
        if (!html.contains('\\') && !html.contains("&amp;", true) &&
            !html.contains("&#") && !html.contains("&quot;", true)
        ) {
            return html
        }
        return ESCAPE_PATTERN.replace(html) { match ->
            when (match.value.lowercase()) {
                "&amp;", "&#38;", "&#x26;", "\\u0026", "\\x26" -> "&"
                "&quot;", "&#34;", "&#x22;" -> "\""
                else -> "/"
            }
        }
    }

    // ------------------------------------------------------------------ iframe 打分

    /** URL 里命中这些词的 iframe 更像播放器；命中越多越靠前 */
    private val PLAYER_KEYWORDS = listOf("player", "embed", "play", "vod", "video", "m3u8", "hls")

    /** 已知广告 / 统计域名：这些 iframe 里不可能有正片 */
    private val AD_HOSTS = listOf(
        "googletagmanager", "google-analytics", "googlesyndication", "googleadservices",
        "doubleclick", "amazon-adsystem", "facebook", "cnzz", "hm.baidu", "51.la",
        "taboola", "outbrain", "criteo", "pubmatic", "rubiconproject", "adsrvr",
        "scorecardresearch", "umeng", "growingio", "matomo",
    )

    private fun frameScore(url: String): Int {
        val lower = url.lowercase()
        return PLAYER_KEYWORDS.count { lower.contains(it) }
    }

    /**
     * 只看域名，不看路径 / 查询串：正片地址的 query 里出现 `utm_source=facebook`
     * 这类字样很常见，按整条 URL 过滤会误杀。
     */
    private fun isAdFrame(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        return AD_HOSTS.any { host.contains(it) }
    }

    // ------------------------------------------------------------------ 正则

    /**
     * 标签属性。`\b` 让 `data-src` 也能命中（`-` 是非单词字符，`s` 前仍有边界）。
     *
     * 不逐个写死标签名：真实页面里媒体地址会藏在 `<video>` / `<source>` / `<a>` /
     * `<iframe>` / 各种自定义组件上，认属性名比认标签名覆盖得全。
     */
    private val ATTRIBUTE_PATTERN = Regex(
        """(?i)\b(?:src|href|data-src|data-url|data-video|data-original|data-href|poster)\s*=\s*["']([^"']{1,2048})["']""",
    )

    /** 引号内的绝对地址：内联脚本里的 `"url":"https://…"` */
    private val QUOTED_ABSOLUTE_PATTERN = Regex(
        """["'](https?://[^"'\s<>]{1,2048})["']""",
        RegexOption.IGNORE_CASE,
    )

    /** 引号内的相对路径：`"../v/a.mp4"` / `"/v/a.mp4"` / `"video/index.m3u8"`（末段必须是媒体扩展名） */
    private val QUOTED_RELATIVE_PATTERN = Regex(
        """["']((?:\.\.?/|/)?[^"'\s<>]{1,2048}?\.(?:m3u8|mp4|m4v|mov|flv|mkv|webm)(?:\?[^"'\s<>]*)?)["']""",
        RegexOption.IGNORE_CASE,
    )

    private val ANCHOR_PATTERN = Regex("""(?is)<a\b([^>]{0,2048})>(.{0,1000}?)</a>""")
    private val HREF_ATTRIBUTE = Regex("""(?i)\bhref\s*=\s*["']([^"']+)["']""")
    private val TITLE_ATTRIBUTE = Regex("""(?i)\btitle\s*=\s*["']([^"']*)["']""")

    /** iframe / frame 标签：只取一层，用于「播放器在独立 iframe 里」的站点 */
    private val FRAME_PATTERN = Regex("""(?is)<(?:iframe|frame)\b([^>]{0,2048})>""")

    /**
     * iframe 上的地址属性。
     *
     * 同时认 `src` 与 `data-src`：懒加载播放器常把 `src` 留成占位图，
     * 真实地址在 `data-src` 上。一个标签里有多个属性时逐个尝试，能用的都收。
     */
    private val FRAME_SRC_ATTRIBUTE = Regex(
        """(?i)\b(?:data-)?src\s*=\s*["']([^"']{1,2048})["']""",
    )

    /** `<meta …>` 标签：只在 meta 里认 `content`，否则会把普通文本当 URL */
    private val META_PATTERN = Regex("""(?is)<meta\b[^>]{0,2048}>""")
    private val CONTENT_ATTRIBUTE = Regex("""(?i)\bcontent\s*=\s*["']([^"']{1,2048})["']""")

    private val TITLE_TAG_PATTERN = Regex("""(?is)<title[^>]*>(.*?)</title>""")
    private val TAG_PATTERN = Regex("""<[^>]*>""")
    private val WHITESPACE_PATTERN = Regex("""\s+""")

    private val ESCAPE_PATTERN = Regex(
        """\\u002f|\\u0026|\\x2f|\\x26|\\/|&amp;|&#38;|&#[xX]26;|&#47;|&#[xX]2f;|&quot;|&#34;|&#[xX]22;""",
        RegexOption.IGNORE_CASE,
    )

    /** `URI` 构造器会拒绝的 ASCII 字符（`%` 除外：它承载已有编码） */
    private val ILLEGAL_URI_CHARS = " \"<>\\^`{|}[]".toSet()

    private val HEX = "0123456789ABCDEF".toCharArray()
}

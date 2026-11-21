package top.mcly99.dl.engine.web

import java.net.URI

/**
 * 从网页里抓到的媒体候选的类型。
 *
 * 只区分「能播」与「不能播」两种语义：[canPlay] 是**唯一**的判定入口，
 * UI 不要自己写 `kind == MediaKind.Mp4` 之类的判断 —— 将来放开或收紧播放范围只改这里。
 */
enum class MediaKind {
    M3u8,
    Mp4,

    /** flv / mkv / webm 等：能下载，但本版本不承诺播放 */
    Other,
    ;

    /**
     * 是否给「播放」按钮。
     *
     * libvlc 对 m3u8 与 mp4 都是一次 `media().play(url)`，所以两者都算可播；
     * flv / mkv / webm 技术上 libvlc 多半也能播，但没经过三平台验证，先不承诺。
     */
    val canPlay: Boolean get() = this == M3u8 || this == Mp4

    companion object {
        /**
         * 只认这些扩展名。
         *
         * `.ts` / `.m4s` / `.aac` 是 HLS 分片，一个播放列表就有几百个，列出来只会淹没列表；
         * `.m3u8` 之外的播放列表扩展名（`.m3u`）是本地文件，不在抓取范围内。
         */
        private val BY_EXTENSION: Map<String, MediaKind> = mapOf(
            "m3u8" to M3u8,
            "mp4" to Mp4,
            // ISO-BMFF 家族，libvlc 与 mp4 走同一条解码路径
            "m4v" to Mp4,
            "mov" to Mp4,
            "flv" to Other,
            "mkv" to Other,
            "webm" to Other,
        )

        /** 按 URL 的路径扩展名判定；不是媒体地址时返回 `null` */
        fun fromUrl(url: String): MediaKind? = extensionOf(url)?.let(BY_EXTENSION::get)

        /**
         * 取 URL 路径的最后一段扩展名（小写）。
         *
         * 只看 path，不看 query：`?file=foo.m3u8` 这类查询串不代表响应是播放列表。
         */
        fun extensionOf(url: String): String? {
            // URI.create 而不是已弃用的 URL(String)：前者对非法字符更严格，异常由 runCatching 兜住
            val path = runCatching { URI.create(url).path }.getOrNull() ?: return null
            val lastSegment = path.substringAfterLast('/')
            val dot = lastSegment.lastIndexOf('.')
            // dot <= 0：无扩展名或隐藏文件；dot 在末尾：扩展名为空
            if (dot <= 0 || dot == lastSegment.lastIndex) return null
            val extension = lastSegment.substring(dot + 1).lowercase()
            // 过长的「扩展名」通常是路径里的点，不是文件类型
            return extension.takeIf { it.length <= 8 }
        }
    }
}

/**
 * 一条抓取结果。
 *
 * @property url 绝对地址（已还原转义、已解析相对路径），带 query —— 很多 m3u8 靠 `?sign=` 鉴权
 * @property name 展示名称；提取器拿不到有意义的名称时为空串，由 UI 兜底成「媒体 N」
 * @property source 来自哪条通道，仅用于排查与 UI 上的可信度区分
 * @property referer 发现这条地址的**页面**地址，下载时作为防盗链 `Referer`
 * @property cookie 拦截到的会话 `Cookie`（只有深度抓取会带）。签名地址常绑会话，
 *   不带这份 Cookie 时「能播不能下」——播放器用的是浏览器里那份会话（v6-P1-3）
 */
data class MediaCandidate(
    val url: String,
    val kind: MediaKind,
    val name: String,
    val source: Source,
    val referer: String? = null,
    val cookie: String? = null,
) {
    /**
     * 抓取通道；顺序即可信度：属性 > 内联脚本 > 引号内相对路径 > 网络拦截。
     *
     * [Network] 排在最后，是因为它来自「深度抓取」：地址最真实（就是播放器实际请求的那个），
     * 但也最容易混进广告位。合并去重时以先到者为准，静态通道优先保留。
     */
    enum class Source { Attribute, InlineScript, RelativePath, Network }
}

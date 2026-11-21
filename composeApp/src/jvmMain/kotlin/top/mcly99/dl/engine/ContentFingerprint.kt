package top.mcly99.dl.engine

import java.net.HttpURLConnection

/**
 * 远端内容的「身份指纹」：`ETag` 与 `Last-Modified`。
 *
 * ## 为什么需要它（P0-2）
 *
 * 断点续传的全部假设是「同一个 URL 背后还是同一份字节」。这个假设在三种很常见的场景下
 * 会悄悄失效：网盘里同名文件被覆盖上传、签名直链被重新解析到另一个文件、源站换 CDN 换了版本。
 * 此时发 `Range: bytes=50MB-` 拿回来的是**新文件的后半段**，与本地的前半段拼在一起，
 * 长度恰好等于新总长，所有本地校验都通过 —— 于是把一个字节错位的文件标成「已完成」。
 *
 * 服务端能替我们做这个判断：带着 `If-Range: <指纹>` 请求，实体没变就回 206，变了就回 200 整份。
 * 我们只要记住第一次拿到的指纹、续传时带上，再对 200 做「从头重下」的处理即可。
 *
 * ## 为什么两个字段都留
 *
 * 不是所有服务器都给 `ETag`（部分 CDN / 动态脚本只给 `Last-Modified`），也不是都给
 * `Last-Modified`。只押一个字段等于在另一半服务器上完全没有保护。
 */
internal data class ContentFingerprint(
    val etag: String? = null,
    val lastModified: String? = null,
) {
    /** 至少有一个字段可用；都为空的指纹等于没有指纹 */
    val isKnown: Boolean get() = !etag.isNullOrBlank() || !lastModified.isNullOrBlank()

    /**
     * `If-Range` 的值。
     *
     * 弱校验符（`W/"..."`）**不能**用在 `If-Range` 里：RFC 7233 §3.2 要求强比较，
     * 带了弱 ETag 的 `If-Range` 会被服务端直接忽略（等于什么都没做）。这种情况退到
     * `Last-Modified`（它是强校验的），两者都没有才返回 null。
     */
    val ifRangeValue: String?
        get() = etag?.takeIf { it.isNotBlank() && !it.startsWith("W/", ignoreCase = true) }
            ?: lastModified?.takeIf { it.isNotBlank() }

    /**
     * 写进 `.chunks.plan` 的片段。
     *
     * `|` 是清单的分隔符，`%` 是转义符，ETag 里理论上可能出现任意可见字符，所以两个都要转义 ——
     * 否则一个带 `|` 的 ETag 会把清单切成五段，解析失败后每次都丢弃分片，续传形同虚设。
     */
    fun signature(): String = "${escape(etag.orEmpty())}~${escape(lastModified.orEmpty())}"

    /** 给日志看的人话；不参与任何比较 */
    fun describe(): String = when {
        !isKnown -> "无指纹"
        else -> listOfNotNull(
            etag?.takeIf { it.isNotBlank() }?.let { "ETag=$it" },
            lastModified?.takeIf { it.isNotBlank() }?.let { "Last-Modified=$it" },
        ).joinToString(", ")
    }

    companion object {
        /** 服务器什么都没给（或者还没探测过） */
        val UNKNOWN = ContentFingerprint()

        /** 从 `If-Range` 用不上的原始头里取指纹 */
        fun of(etag: String?, lastModified: String?): ContentFingerprint {
            val e = etag?.trim()?.takeIf { it.isNotEmpty() }
            val m = lastModified?.trim()?.takeIf { it.isNotEmpty() }
            return if (e == null && m == null) UNKNOWN else ContentFingerprint(e, m)
        }

        /** [signature] 的逆运算；格式不认识时返回 null（调用方据此丢弃旧分片） */
        fun parseSignature(raw: String): ContentFingerprint? {
            val parts = raw.split('~')
            if (parts.size != 2) return null
            val etag = unescape(parts[0]).takeIf { it.isNotEmpty() }
            val lastModified = unescape(parts[1]).takeIf { it.isNotEmpty() }
            return if (etag == null && lastModified == null) UNKNOWN else ContentFingerprint(etag, lastModified)
        }

        private fun escape(value: String): String = value
            .replace("%", "%25")
            .replace("|", "%7C")
            .replace("~", "%7E")
            .replace("\r", "%0D")
            .replace("\n", "%0A")

        private fun unescape(value: String): String = buildString(value.length) {
            var i = 0
            while (i < value.length) {
                val ch = value[i]
                if (ch == '%' && i + 2 < value.length) {
                    val code = value.substring(i + 1, i + 3).toIntOrNull(16)
                    if (code != null) {
                        append(code.toChar())
                        i += 3
                        continue
                    }
                }
                append(ch)
                i++
            }
        }
    }
}

/**
 * 远端实体是否**已经换成了另一份内容**。
 *
 * 判定顺序：两边都有 ETag 就比 ETag（最可靠，内容哈希或版本号）；否则比 Last-Modified。
 * 任何一边缺字段都返回 false —— 无法比较时按「没变」处理，也就是维持改动前的行为，
 * 而不是把「不知道」当成「变了」去白白丢掉用户已经下好的几十 GB。
 *
 * 弱校验符的 `W/` 前缀不参与比较：`W/"abc"` 与 `"abc"` 说明服务端只是换了校验强度，
 * 不是换了内容；把它们判成不同会让每次续传都从头开始。
 */
internal fun contentReplacedBy(
    previous: ContentFingerprint?,
    current: ContentFingerprint?,
): Boolean {
    if (previous == null || !previous.isKnown) return false
    if (current == null || !current.isKnown) return false
    val a = previous.etag?.takeIf { it.isNotBlank() }?.removeWeakPrefix()
    val b = current.etag?.takeIf { it.isNotBlank() }?.removeWeakPrefix()
    if (a != null && b != null) return a != b
    val la = previous.lastModified?.takeIf { it.isNotBlank() }
    val lb = current.lastModified?.takeIf { it.isNotBlank() }
    if (la != null && lb != null) return la != lb
    return false
}

private fun String.removeWeakPrefix(): String =
    if (startsWith("W/", ignoreCase = true)) substring(2) else this

/**
 * 从 `Content-Range: bytes 100-199/2000` 里取起始偏移。
 *
 * 206 只说明「服务器接受了 Range」，不说明「从你要的位置开始」：少数服务器（和一些反代）
 * 会忽略 `If-Range` / 把越界区间截断，仍然回 206。不校验起点的话，本地第 50MB 的位置会被
 * 填进服务器从 0 开始给的字节 —— 一样是静默坏数据，只是换了个成因。
 *
 * 格式不认识（例如 `bytes * /2000`）时返回 null：宁可放行，也不把不认识的响应判成错误。
 */
internal fun contentRangeStart(connection: HttpURLConnection): Long? =
    parseContentRangeStart(connection.getHeaderField("Content-Range"))

/** [contentRangeStart] 的纯函数版本，便于单测覆盖各种畸形头 */
internal fun parseContentRangeStart(header: String?): Long? = header
    ?.trim()
    ?.takeIf { it.startsWith("bytes", ignoreCase = true) }
    ?.substringAfter(' ', "")
    ?.substringBefore('-')
    ?.trim()
    ?.toLongOrNull()

/**
 * 分块请求拿到的不是 206 而是 200 —— 说明 `Range` 没生效，或者 `If-Range` 判定实体已变。
 *
 * 两种情况都意味着「这一轮的字节与其它分片不是同一份内容」，必须整轮作废。
 * 单源任务不能像普通失败那样「换个源重试同一片」，所以要一个独立的异常类型来区分。
 */
internal class ContentReplacedException(message: String) : IllegalStateException(message)


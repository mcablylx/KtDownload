package top.mcly99.dl.engine

/**
 * 判断一个地址是不是「带时效签名」的一次性链接。
 *
 * 为什么要单独判：深度抓取/抓网页拿到的 m3u8 长这样
 * `.../index.m3u8?wsSecret=...&wsTime=1793094493&ip=112.112.112.112`，
 * 其中 `wsTime` 是过期时间戳、`ip` 绑客户端、`wsSecret` 绑会话。这类地址**第二天必然 403**，
 * 但错误表现与「防盗链没配对」「网络不通」完全一样，用户只会反复重试（v7-P2-4）。
 *
 * 只做「像不像」，不做解析：参数名五花八门（各家 CDN 自己起名），
 * 而这里唯一的用途是**换一句更准确的话**，判错的代价只是文案不够贴切。
 */
internal fun looksLikeExpiringUrl(url: String): Boolean {
    val lower = url.lowercase()
    return EXPIRING_MARKERS.any { lower.contains(it) }
}

/**
 * 是不是「身份/权限」类的失败。
 *
 * 只看状态码文本：引擎这条链路上的异常来自 `HttpURLConnection.responseCode` 与
 * `HlsDownloader` 的 `httpFailure`，两者都把码写进了 message，没有统一的异常类型可判。
 */
internal fun isAuthFailure(message: String?): Boolean {
    val text = message ?: return false
    return AUTH_STATUS_MARKERS.any { text.contains(it) }
}

/** 身份/权限类状态码；只查子串，误判的代价只是文案更啰嗦 */
private val AUTH_STATUS_MARKERS = listOf("401", "403", "410")

/**
 * 任务需要会话 Cookie，但 Cookie 不落盘时的提示（v8-P0-1）。
 *
 * 为什么不是「继续下载」而是「重新抓取」：带签名的地址本身几分钟就过期，
 * 就算把 Cookie 存下来，重启后也大概率是一个过期的地址 —— 重新抓一次是唯一可靠的路。
 */
internal const val COOKIE_NOT_PERSISTED_MESSAGE =
    "该地址需要会话 Cookie（为安全不写入磁盘）：请回到「抓网页」重新抓取后再下载。"

/**
 * C2 多源任务重启后的提示（P3 后续批次）。
 *
 * 备选直链各自绑定一个账号的会话，而 Cookie 一律不落盘；签名直链本身也有时效。
 * 所以多源任务不跨重启续传：恢复时直接标失败，让用户重新解析生成一组新直链，
 * 而不是拿过期地址继续拼分片（那样会得到一个「下载成功但内容是坏的」文件）。
 */
internal const val MULTI_SOURCE_NOT_PERSISTED_MESSAGE =
    "多账号多源下载需要重新获取直链（凭证与签名地址不写入磁盘）：请回到「云盘」重新解析后再下载。"

/** 各家 CDN 常见的时效签名参数名（小写） */
private val EXPIRING_MARKERS = listOf(
    "wstime=",
    "expires=",
    "expire=",
    "deadline=",
    "auth_key=",
    "authkey=",
    "token=",
    "sign=",
    "signature=",
)

/**
 * 失败时给用户的话。
 *
 * 两种情况分开：带时效签名 + 401/403/410 → 明确让他去重新抓取；
 * 其余保持原始错误（用户拿它去搜、去反馈都用得上）。
 */
internal fun describeDownloadFailure(url: String, rawMessage: String?): String {
    val raw = rawMessage?.takeIf { it.isNotBlank() } ?: "下载失败"
    if (isAuthFailure(raw) && looksLikeExpiringUrl(url)) {
        return "地址可能已过期（带时效签名）：请回到「抓网页」重新抓取后再下载。原始错误：$raw"
    }
    return raw
}

package top.mcly99.dl.utils

/**
 * Cookie 按 name 去重，保留**最后一次**出现的值，输出顺序 = 名字首次出现的顺序。
 *
 * 背景（上游 YunX-Desktop issue #94）：同一个 `STOKEN` 会同时存在于
 * `passport.baidu.com` / `.baidu.com` / `pan.baidu.com` 等多个域及多次登录轮次，
 * 值互不相同。原样拼进一个 `Cookie:` 头会让百度服务端判定鉴权失败，
 * `api/list` 与 `api/gettemplatevariable` 都返回 `errno=-6`。
 *
 * issue 实测证据：保留最后一次出现的值 → errno=0、正常列出 91 个条目；
 * 换成第 1 份旧值 → 立刻复现 -6。因此这里采用 last-wins，
 * 与下游 `XunleiDriveCredential.fromCookiePairs(Map)` 的语义也一致。
 */
internal fun deduplicateCookiePairs(pairs: List<Pair<String, String>>): List<Pair<String, String>> {
    if (pairs.size <= 1) return pairs
    val unique = LinkedHashMap<String, String>(pairs.size)
    pairs.forEach { (name, value) -> unique[name] = value }
    return unique.map { (name, value) -> name to value }
}

/**
 * 对已经保存下来的 `Cookie` 请求头字符串按 name 去重（保留最后一次）。
 *
 * 用于历史脏 Cookie 的兜底：老版本存下的 4 份冲突 `STOKEN` 不能靠重新登录以外的方式
 * 消失，接口在 `errno=-6` 时用本函数清洗后再请求一次即可恢复，不强迫用户重新登录。
 * value 按**第一个** `=` 切分，避免 base64 / URL 编码值里的 `=` 被误当成键名的一部分。
 */
internal fun deduplicateCookieHeader(cookie: String): String {
    if (cookie.isBlank()) return cookie
    val unique = LinkedHashMap<String, String>()
    cookie.split(';').forEach { segment ->
        val part = segment.trim()
        if (part.isEmpty()) return@forEach
        val idx = part.indexOf('=')
        if (idx <= 0) return@forEach
        val name = part.substring(0, idx).trim()
        if (name.isEmpty()) return@forEach
        unique[name] = part.substring(idx + 1).trim()
    }
    return unique.entries.joinToString("; ") { (name, value) -> "$name=$value" }
}

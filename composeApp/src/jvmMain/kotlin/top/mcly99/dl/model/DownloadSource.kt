package top.mcly99.dl.model

/**
 * C2 单文件多源分片：同一个文件的**另一条直链**及其专属请求头。
 *
 * 每条直链都由不同账号取到，签名直链与会话是绑定的：拿 A 账号的 URL 配 B 账号的
 * Cookie，网盘 CDN 会直接 412 / 403。所以这里把 URL 和整套头打包成一个不可拆的单元，
 * 引擎按分片整体切换，绝不允许「只换 URL、沿用主源 Cookie」。
 *
 * 与上游 `DownloadSource(url, headers: Map)` 的区别：下游的下载头是几个具名字段
 * （`DownloadTask.userAgent/origin/secChUa/...`），不是一张 JSON map，因此这里也用
 * 具名字段，避免引入第二套头部表示。
 *
 * **不落盘**：Cookie 是会话凭证，`TaskStore` 只写一个「多源任务」标记，重启后
 * 恢复为失败并提示重新解析（签名直链本身也有时效）。
 */
data class DownloadSource(
    val url: String,
    val referer: String? = null,
    val cookie: String? = null,
    val userAgent: String? = null,
    val origin: String? = null,
    val secChUa: String? = null,
)

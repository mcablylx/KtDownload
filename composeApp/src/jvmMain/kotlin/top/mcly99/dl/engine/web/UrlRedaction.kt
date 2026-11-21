package top.mcly99.dl.engine.web

import java.net.URI

/**
 * 日志用的地址脱敏（v8-P0-2）。
 *
 * 深度抓取拿到的地址常带 `wsSecret` / `wsTime` / `token` 这类签名参数，它们本身就是
 * 临时凭证；原样写进按天滚动的日志，等于把凭证长期留在磁盘上。
 *
 * 日志里只保留「哪个主机、哪个路径」——query 与 fragment 一律丢弃，
 * 因为它们既可能是凭证，也可能是用户隐私（搜索词、播放进度）。
 */
internal fun redactUrl(url: String): String {
    val cut = url.indexOfFirst { it == '?' || it == '#' }
    return if (cut < 0) url else url.take(cut) + "?<redacted>"
}

/**
 * 取主机名用于结构化日志。
 *
 * 解析失败时返回 `-`，**绝不**把整条地址回退进日志——那正好会绕过 [redactUrl]。
 */
internal fun hostOf(url: String): String =
    runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "-"

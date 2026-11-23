/*
 * Adapted from YunX-Desktop — https://github.com/tidain/YunX-Desktop
 * Upstream: main branch @ 00a030f7 (tag v1.3.4, 2026-11-13).
 * License: GNU Affero General Public License v3.0 (AGPL-3.0).
 * Original: ui/jcef/JcefLoginPane.kt (collectCookies) and util/CookieCleaner.kt
 * Changes: added the Xunlei localStorage cookie-bridge reader, dropped the
 *          BrowserCookieImporter dependency (ktdown has no JNA/SQLite account DB).
 */
package top.mcly99.dl.engine.clouddrive.browser

import org.cef.callback.CefCookieVisitor
import org.cef.misc.BoolRef
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import top.mcly99.dl.engine.clouddrive.XunleiDriveCredential
import top.mcly99.dl.utils.deduplicateCookiePairs
import java.util.concurrent.ConcurrentLinkedQueue

/** visitAllCookies 的回调在 CEF IO 线程异步执行，返回后要留一小段窗口让回调写完 */
private const val COOKIE_SETTLE_MS = 400L

/**
 * 读取全局 Cookie 管理器里命中 [domains] 的键值对，并**按 name 去重**（保留最后一次）。
 *
 * 不去重会让同一 name（典型是百度 STOKEN，跨 passport/./pan 多个域各存一份不同值）
 * 被原样拼进同一个 Cookie 头，百度 api/list 直接回 errno=-6（上游 issue #94）。
 */
private fun cookiePairs(domains: List<String>): List<Pair<String, String>> {
    val manager = runCatching { CefCookieManager.getGlobalManager() }.getOrNull() ?: return emptyList()
    val pairs = ConcurrentLinkedQueue<Pair<String, String>>()
    runCatching {
        manager.visitAllCookies(object : CefCookieVisitor {
            override fun visit(cookie: CefCookie, count: Int, total: Int, delete: BoolRef): Boolean {
                val domain = cookie.domain.orEmpty()
                if (domains.any { domain.contains(it) }) {
                    pairs.add((cookie.name.orEmpty()) to (cookie.value.orEmpty()))
                }
                return true
            }
        })
        Thread.sleep(COOKIE_SETTLE_MS)
    }
    return deduplicateCookiePairs(pairs.toList())
}

/**
 * 通用平台的登录态检测：目标域 Cookie 里必需键齐全（或候选键任一命中）才算登录成功，
 * 返回可直接作为 `Cookie` 请求头的整串。与 YunX 的 `collectCookies` 判定一致。
 */
internal fun collectLoginCookies(
    domains: List<String>,
    requiredKeys: List<String>,
    anyOfKeys: List<String>,
): String? = runCatching {
    val all = cookiePairs(domains)
    if (all.isEmpty()) return@runCatching null
    val names = all.map { it.first }.toSet()
    val requiredOk = requiredKeys.all { it in names }
    val anyOk = anyOfKeys.isNotEmpty() && anyOfKeys.any { it in names }
    if (!requiredOk && !anyOk) return@runCatching null
    all.joinToString("; ") { "${it.first}=${it.second}" }
}.getOrNull()

/**
 * 迅雷网页登录：站点把凭据放在 localStorage 而非 Cookie，JCEF 取不到 JS 返回值，
 * 所以由 `COOKIE_BRIDGE_SCRIPT` 把 JSON 分片写进 `yunx_xl_cred_*` Cookie。
 *
 * 分片拼回、URL 解码、deviceid 兜底、裸 access_token 兼容全部收敛在
 * [XunleiDriveCredential.fromCookiePairs] 一处；这里只负责把 CEF 的 Cookie
 * 收集结果交给它，并输出规范 JSON（含 refresh_token，过期后能自动刷新）。
 * 以前另有一个只取 access_token 的 `collectXunleiAccessToken` 兜底入口，
 * 会把 refresh_token 丢掉，已删除。
 */
internal fun collectXunleiCredential(): String? = runCatching {
    val pairs = cookiePairs(listOf("xunlei.com")).toMap()
    if (pairs.isEmpty()) return@runCatching null
    XunleiDriveCredential.fromCookiePairs(pairs)?.takeIf { it.isUsable }?.toJson()
}.getOrNull()

/** 退出登录 / 重新登录前清掉内嵌浏览器的会话，避免旧账号静默登回 */
internal fun clearCookies(domains: List<String>) {
    runCatching {
        val manager = CefCookieManager.getGlobalManager() ?: return
        manager.visitAllCookies(object : CefCookieVisitor {
            override fun visit(cookie: CefCookie, count: Int, total: Int, delete: BoolRef): Boolean {
                val domain = (cookie.domain.orEmpty()).removePrefix(".").lowercase()
                val hit = domains.any { base ->
                    val b = base.removePrefix(".").lowercase()
                    domain == b || domain.endsWith(".$b")
                }
                if (hit) runCatching { delete.set(true) }
                return true
            }
        })
        domains.forEach { d ->
            runCatching { manager.deleteCookies("https://$d", null) }
            runCatching { manager.deleteCookies("http://$d", null) }
        }
    }
}

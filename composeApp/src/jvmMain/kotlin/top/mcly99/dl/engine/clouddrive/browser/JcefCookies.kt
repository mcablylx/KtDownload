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
import top.mcly99.dl.data.network.XunleiWebCredential
import top.mcly99.dl.engine.clouddrive.XunleiDriveCredential
import top.mcly99.dl.utils.deduplicateCookiePairs
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * visitAllCookies 的回调在 CEF IO 线程异步执行，最长等多久：
 * 正常情况靠 CefCookieVisitor 的 `count/total` 判定收齐后立即返回，
 * 这里只是回调缺失/实现异常时的兜底，不再用固定 sleep 赌回调写完（P0-3）。
 */
internal const val COOKIE_VISIT_TIMEOUT_MS = 1500L

/**
 * 域匹配统一口径（P2-E6）：去掉前导 `.`、统一小写，然后「相等或子域」。
 *
 * 旧实现在收集时用 `domain.contains(base)` 做子串匹配，在清理时用精确/后缀匹配，
 * 两处口径不一致：`evil-baidu.com` 会被 `contains("baidu.com")` 误判成目标域，
 * 把一个无关域名的 Cookie 拼进请求头。这里收敛成一份实现，两处共用。
 */
internal fun domainMatches(cookieDomain: String?, baseDomain: String): Boolean {
    val d = cookieDomain.orEmpty().trim().removePrefix(".").lowercase()
    val b = baseDomain.trim().removePrefix(".").lowercase()
    if (d.isEmpty() || b.isEmpty()) return false
    return d == b || d.endsWith(".$b")
}

/**
 * 按 CEF 的 `count/total` 进度同步收集 Cookie 键值对，并**按 name 去重**（保留最后一次）。
 *
 * 把「遍历 + 等待」抽象成 [startVisit]，让它可注入：
 * 生产传入 `manager.visitAllCookies { ... }`，测试传入异步延时回调的假实现，
 * 无需构造 CefCookie。回调里 `count == total - 1` 表示最后一项，收到即 countDown；
 * total<=0（无 Cookie）或回调不来时靠 [timeoutMs] 兜底，绝不卡死。
 *
 * 不去重会让同一 name（典型是百度 STOKEN，跨 passport/./pan 多个域各存一份不同值）
 * 被原样拼进同一个 Cookie 头，百度 api/list 直接回 errno=-6（上游 issue #94）。
 */
internal fun collectCookiePairsBlocking(
    domains: List<String>,
    timeoutMs: Long = COOKIE_VISIT_TIMEOUT_MS,
    startVisit: (emit: (domain: String?, name: String?, value: String?, count: Int, total: Int) -> Unit) -> Unit,
): List<Pair<String, String>> {
    val pairs = ConcurrentLinkedQueue<Pair<String, String>>()
    val done = CountDownLatch(1)
    runCatching {
        startVisit { domain, name, value, count, total ->
            if (domain != null && domains.any { domainMatches(domain, it) }) {
                pairs.add(name.orEmpty() to value.orEmpty())
            }
            if (total > 0 && count >= total - 1) done.countDown()
        }
    }
    // 回调已在 startVisit 内同步跑完时 done 已归零，await 立即返回；
    // 异步回调时等最后一项；回调不来时超时兜底。
    runCatching { done.await(timeoutMs, TimeUnit.MILLISECONDS) }
    return deduplicateCookiePairs(pairs.toList())
}

private fun cookiePairs(domains: List<String>): List<Pair<String, String>> {
    val manager = runCatching { CefCookieManager.getGlobalManager() }.getOrNull() ?: return emptyList()
    return collectCookiePairsBlocking(domains, startVisit = { emit ->
        manager.visitAllCookies(object : CefCookieVisitor {
            override fun visit(cookie: CefCookie, count: Int, total: Int, delete: BoolRef): Boolean {
                emit(cookie.domain, cookie.name, cookie.value, count, total)
                return true
            }
        })
    })
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
                val hit = domains.any { domainMatches(domain, it) }
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

/**
 * P2-15：只删「JS 桥接」写入的那几条 Cookie（`yunx_xl_cred_*`）。
 *
 * 桥接 Cookie 里是明文 refresh_token，只需要活到「JS 写入 → Kotlin 读回」这一瞬。
 * 每次轮询读完立即删除，避免它按 max-age 在浏览器里躺满全程；
 * 退出登录走 [clearCookies] 时会连同域名下所有 Cookie 一起清掉。
 */
internal fun clearXunleiBridgeCookies() {
    runCatching {
        val manager = CefCookieManager.getGlobalManager() ?: return
        manager.visitAllCookies(object : CefCookieVisitor {
            override fun visit(cookie: CefCookie, count: Int, total: Int, delete: BoolRef): Boolean {
                val name = cookie.name.orEmpty()
                if (name.startsWith(XunleiWebCredential.CRED_COOKIE_PREFIX) ||
                    name == XunleiWebCredential.CRED_COOKIE_COUNT
                ) {
                    runCatching { delete.set(true) }
                }
                return true
            }
        })
    }
}

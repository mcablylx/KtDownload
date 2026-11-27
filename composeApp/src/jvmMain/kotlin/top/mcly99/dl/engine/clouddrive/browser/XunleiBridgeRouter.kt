package top.mcly99.dl.engine.clouddrive.browser

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandlerAdapter
import top.mcly99.dl.data.network.XunleiWebCredential
import java.util.concurrent.atomic.AtomicReference

/**
 * P2-15：迅雷网页登录的**内存桥**。
 *
 * 以前登录页把 localStorage 里的 refresh_token 拆片写进 `document.cookie`，
 * Kotlin 再从 CEF CookieManager 读回——明文凭据会按 max-age 躺在浏览器存储里，
 * 也可能被 CEF 日志带出去。JCEF 132 提供 CefMessageRouter：渲染进程调用注入的
 * `window.ktDownloadReadXunleiCredential(payload, ok, fail)`，参数直接回到 JVM 方法，
 * 全程只在内存里走一跳，不落盘、不进 Cookie 头。
 *
 * 一次查询只保留最后一份载荷，Kotlin 轮询读到即消费（`getAndSet(null)`）；
 * 与旧 Cookie 通道不同，这里没有 TTL，浏览器关闭后自然消失。
 */
internal class XunleiBridgeHandler : CefMessageRouterHandlerAdapter() {

    private val pending = AtomicReference<String?>(null)

    /** 轮询侧取走本次载荷；没有新凭据时返回 null（此时才回退读旧 Cookie）。 */
    fun consume(): String? = pending.getAndSet(null)

    /** 把新载荷写入内存；同一时刻只保留最新一份。 */
    fun acceptRequest(url: String?, request: String?): Boolean {
        if (!xunleiBridgeRequestAllowed(url, request)) return false
        pending.set(request)
        return true
    }

    override fun onQuery(
        browser: CefBrowser?,
        frame: CefFrame?,
        queryId: Long,
        request: String?,
        persistent: Boolean,
        callback: CefQueryCallback?,
    ): Boolean {
        // JS 侧已做域名判断，这里再核一次：恶意页面也可能直接调这个全局函数。
        val url = runCatching { browser?.url }.getOrNull()
        if (acceptRequest(url, request)) {
            callback?.success("ok")
        } else {
            callback?.failure(403, "untrusted origin or invalid payload")
        }
        return true
    }

    companion object {
        /** 一份凭据 JSON 远小于此值；设上限防止恶意页面用超长字符串撑爆内存 */
        internal const val MAX_BRIDGE_REQUEST_CHARS = 256 * 1024
    }
}

/** 纯函数形式的准入判断，便于在没有真实 CefBrowser 的环境里做回归。 */
internal fun xunleiBridgeRequestAllowed(url: String?, request: String?): Boolean {
    if (!XunleiWebCredential.isTrustedUrl(url)) return false
    val value = request.orEmpty()
    if (value.isEmpty() || value.length > XunleiBridgeHandler.MAX_BRIDGE_REQUEST_CHARS) return false
    // 凭据桥载荷一定是 localStorage 的 JSON 对象；不是 JSON 的请求一律不收。
    return value.startsWith("{") && value.contains(":")
}

/** 建立消息路由：查询 / 取消函数名与注入脚本严格一致。 */
internal fun createXunleiBridgeRouter(handler: XunleiBridgeHandler): CefMessageRouter =
    CefMessageRouter.create(
        CefMessageRouter.CefMessageRouterConfig(
            XunleiWebCredential.BRIDGE_QUERY_FUNCTION,
            XunleiWebCredential.BRIDGE_CANCEL_FUNCTION,
        ),
        handler,
    )

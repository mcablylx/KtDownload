package top.mcly99.dl.engine

import top.mcly99.dl.data.network.PlatformProxyDetector
import top.mcly99.dl.model.ProxyMode
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * 由设置里的「启用 / 主机 / 端口」三项构造代理。
 *
 * 下载引擎与「抓网页」共用这一处判定：代理规则写两遍，早晚会出现
 * 「下载走代理、抓页面直连」这种查半天才发现的不一致。
 *
 * @return 未启用、主机为空或端口非法时返回 `null`（表示直连）
 */
fun proxyOrNull(enabled: Boolean, host: String, port: Int): Proxy? {
    if (!enabled || host.isBlank() || port !in 1..65535) return null
    return runCatching {
        Proxy(Proxy.Type.HTTP, InetSocketAddress(host.trim(), port))
    }.getOrNull()
}

/**
 * P1：三态代理的**唯一**判定口径。下载引擎、抓网页、在线播放、网盘 API、OkHttp 客户端
 * 都必须经这里，避免「手动代理生效、跟随系统却直连」这类分叉。
 *
 * 每次调用都现取（[systemProxy] 默认带 30 秒缓存），所以运行中开关系统 / VPN 代理能跟随；
 * 调用方应把它包在 lambda 里，而不是在构造时取一次快照。
 *
 * @param systemProxy 可注入的系统代理来源，供单测隔离本机环境
 */
fun resolveProxy(
    mode: ProxyMode,
    host: String,
    port: Int,
    systemProxy: () -> Proxy? = { PlatformProxyDetector.detect() },
): Proxy? = when (mode) {
    ProxyMode.DIRECT -> null
    ProxyMode.MANUAL -> proxyOrNull(true, host, port)
    ProxyMode.SYSTEM -> runCatching { systemProxy() }.getOrNull()
}

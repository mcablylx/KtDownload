package top.mcly99.dl.theme

import top.mcly99.dl.model.ProxyMode

/**
 * 「代理三态 / 系统代理检测 / HTTP/2」文案（P1）。
 *
 * **刻意不并入 [UiStrings]**：它的构造参数已逼近 JVM 方法签名 255 槽上限，
 * 再加一个字段编译能过、运行时却抛 `ClassFormatError: Too many arguments in method signature`。
 * 设置页本身持有 [top.mcly99.dl.model.AppSettings.language]，按它选表即可，
 * 不必给全局 UiStrings 增加一个用不上的槽位。
 */
data class NetworkStrings(
    val proxyMode: String,
    val proxyModeLabel: (ProxyMode) -> String,
    val proxyModeDirectDesc: String,
    val proxyModeManualDesc: String,
    val proxyModeSystemDesc: String,
    val proxyDetecting: String,
    /** 参数：主机、端口、协议名（HTTP / SOCKS） */
    val proxyDetected: (String, Int, String) -> String,
    val proxyNotDetected: String,
    val proxyPacUnsupported: String,
    /** 参数为不可用原因（命令名等，不翻译） */
    val proxyUnavailable: (String) -> String,
    val refreshProxy: String,
    val advancedTitle: String,
    val advancedDesc: String,
    val http2Title: String,
    val http2Desc: String,
)

/** 与 [stringsFor] 的回落规则一致：除 `en-US` 外一律中文 */
fun networkStrings(language: String): NetworkStrings =
    if (language.equals("en-US", ignoreCase = true)) enUSNetwork else zhCNNetwork

private val zhCNNetwork = NetworkStrings(
    proxyMode = "代理模式",
    proxyModeLabel = {
        when (it) {
            ProxyMode.DIRECT -> "直连"
            ProxyMode.MANUAL -> "手动配置"
            ProxyMode.SYSTEM -> "跟随系统"
        }
    },
    proxyModeDirectDesc = "不使用代理，所有请求直接连接。",
    proxyModeManualDesc = "使用下面填写的 HTTP 代理地址与端口。",
    proxyModeSystemDesc = "自动读取 macOS / Windows / Linux 的系统代理，运行中每 30 秒刷新一次。",
    proxyDetecting = "正在检测系统代理…",
    proxyDetected = { host, port, type -> "检测到系统代理：$type $host:$port" },
    proxyNotDetected = "未检测到系统代理，当前按直连处理。",
    proxyPacUnsupported = "系统使用 PAC 自动配置脚本，暂不支持，按直连处理。",
    proxyUnavailable = { "无法读取系统代理（$it），按直连处理。" },
    refreshProxy = "重新检测",
    advancedTitle = "高级网络",
    advancedDesc = "协议协商选项，改动后立即生效，无需重启",
    http2Title = "启用 HTTP/2",
    http2Desc = "允许网盘接口与 HLS 请求协商 HTTP/2；关闭时仅使用 HTTP/1.1",
)

private val enUSNetwork = NetworkStrings(
    proxyMode = "Proxy Mode",
    proxyModeLabel = {
        when (it) {
            ProxyMode.DIRECT -> "Direct"
            ProxyMode.MANUAL -> "Manual"
            ProxyMode.SYSTEM -> "System"
        }
    },
    proxyModeDirectDesc = "No proxy; every request connects directly.",
    proxyModeManualDesc = "Use the HTTP proxy host and port entered below.",
    proxyModeSystemDesc = "Read the macOS / Windows / Linux system proxy; refreshed every 30 seconds.",
    proxyDetecting = "Detecting system proxy…",
    proxyDetected = { host, port, type -> "System proxy detected: $type $host:$port" },
    proxyNotDetected = "No system proxy detected; connecting directly.",
    proxyPacUnsupported = "The system uses a PAC script, which is not supported; connecting directly.",
    proxyUnavailable = { "Cannot read the system proxy ($it); connecting directly." },
    refreshProxy = "Re-detect",
    advancedTitle = "Advanced Network",
    advancedDesc = "Protocol negotiation options apply immediately",
    http2Title = "Enable HTTP/2",
    http2Desc = "Allow cloud APIs and HLS requests to negotiate HTTP/2; HTTP/1.1 only when off",
)

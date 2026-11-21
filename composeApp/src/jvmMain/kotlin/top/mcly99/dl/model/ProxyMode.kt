package top.mcly99.dl.model

/**
 * 网络代理的三态模式（对齐上游 `PROXY_MODE_*` 的语义，但系统代理部分自研跨平台实现）。
 *
 * - [DIRECT]：不使用代理；
 * - [MANUAL]：使用设置页里填写的 [AppSettings.proxyHost] / [AppSettings.proxyPort]；
 * - [SYSTEM]：每次请求现取本机系统代理（macOS `scutil --proxy`、Windows 注册表、
 *   Linux 环境变量 / JVM ProxySelector），结果带 30 秒缓存，运行中开关代理软件也能跟随。
 *
 * 配置文件里保存 [name] 而不是序号，避免以后调整枚举顺序导致用户设置错位。
 */
enum class ProxyMode {
    DIRECT,
    MANUAL,
    SYSTEM,
    ;

    companion object {
        /** 解析持久化值；未知/空值返回 null，由调用方回退到旧布尔配置或默认值 */
        fun fromStored(raw: String?): ProxyMode? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return values().firstOrNull { it.name.equals(value, ignoreCase = true) }
        }
    }
}

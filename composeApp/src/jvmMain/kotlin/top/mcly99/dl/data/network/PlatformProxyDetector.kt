package top.mcly99.dl.data.network

/*
 * 本文件是自研实现，不是 YunX-Desktop 的 AGPL 移植文件。
 * 上游 B:data/network/SystemProxy.kt 只支持 Windows（JNA 读注册表），macOS / Linux 直接返回不可用；
 * 这里按平台重写：macOS scutil --proxy、Windows reg query、Linux 环境变量 / gsettings / JVM ProxySelector。
 */

import top.mcly99.dl.utils.AppLogger
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 读取「跟随系统」模式下本机配置的代理。
 *
 * 设计约束：
 * - 只读，不修改系统设置；PAC 自动配置脚本明确返回 [Inspect.PacUnsupported]，不静默降级；
 * - 探测结果带 30 秒缓存（[current]），下载引擎的每个分片都会现取代理，不能每次都起子进程；
 * - 解析全部是纯函数（[parseScutil] / [parseWindowsRegistry] / [parseProxyUrl] / [parseEnvironment]），
 *   不依赖本机状态即可测试；
 * - 只支持固定 host:port 代理，不支持带账号密码的代理 URL（Credential 会被忽略）。
 */
object PlatformProxyDetector {

    private const val TAG = "SystemProxy"
    private const val CACHE_TTL_MS = 30_000L
    private const val COMMAND_TIMEOUT_SECONDS = 3L

    private const val WINDOWS_REG_KEY =
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

    sealed interface Inspect {
        /** [socks] 为真时构造 `Proxy.Type.SOCKS`，否则 HTTP */
        data class Proxy(val host: String, val port: Int, val socks: Boolean = false) : Inspect

        /** 系统未启用代理 / 未配置代理服务器 */
        object Direct : Inspect

        /** 检测到 PAC 自动配置脚本，本实现不支持 */
        object PacUnsupported : Inspect

        /** 该平台或该命令不可用；[reason] 供设置页展示 */
        data class Unavailable(val reason: String) : Inspect
    }

    private val lock = Any()

    @Volatile
    private var cached: Inspect? = null

    @Volatile
    private var cachedAt: Long = 0L

    /** 丢弃缓存：设置页点「重新检测」、或用户切换代理模式时调用 */
    fun invalidate() {
        synchronized(lock) {
            cached = null
            cachedAt = 0L
        }
    }

    /** 带 TTL 的读取；TTL 内系统代理变化不会立刻跟随，避免每个请求都起子进程 */
    fun current(): Inspect {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < CACHE_TTL_MS) return it }
        return synchronized(lock) {
            cached?.let { if (System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) return@let it else null }
                ?: inspect().also {
                    cached = it
                    cachedAt = System.currentTimeMillis()
                }
        }
    }

    /** 「跟随系统」模式下交给网络层的最终代理；不可用一律按直连 */
    fun detect(): Proxy? = toProxy(current())

    fun toProxy(inspect: Inspect): Proxy? {
        val value = inspect as? Inspect.Proxy ?: return null
        val host = value.host.trim().removeSurrounding("[", "]")
        if (host.isEmpty() || value.port !in 1..65535) return null
        return runCatching {
            Proxy(
                if (value.socks) Proxy.Type.SOCKS else Proxy.Type.HTTP,
                InetSocketAddress(host, value.port),
            )
        }.getOrNull()
    }

    /** 简短描述，用于日志；设置页文案走本地化，不直接用它 */
    fun describe(inspect: Inspect): String = when (inspect) {
        is Inspect.Proxy -> "${if (inspect.socks) "SOCKS" else "HTTP"} ${inspect.host}:${inspect.port}"
        Inspect.Direct -> "direct"
        Inspect.PacUnsupported -> "PAC unsupported"
        is Inspect.Unavailable -> "unavailable: ${inspect.reason}"
    }

    /** 按平台分派；任何异常都在具体探测里降级为直连，不抛到网络层 */
    fun inspect(): Inspect {
        val os = System.getProperty("os.name", "").lowercase()
        val result = when {
            os.contains("mac") || os.contains("darwin") -> inspectMac()
            os.contains("win") -> inspectWindows()
            else -> inspectUnix()
        }
        if (result == Inspect.PacUnsupported) {
            AppLogger.warn(TAG, "系统使用 PAC 自动配置脚本，暂不支持，按直连处理")
        }
        return result
    }

    // ---- macOS：scutil --proxy ----

    private fun inspectMac(): Inspect {
        val output = runCommand(listOf("scutil", "--proxy"))
            ?: return Inspect.Unavailable("scutil --proxy")
        return parseScutil(output)
    }

    private val SCUTIL_LINE = Regex("""^\s*([A-Za-z0-9]+)\s*:\s*(.+?)\s*$""")

    /**
     * 解析 `scutil --proxy` 的字典文本，例如
     * `HTTPEnable : 1` / `HTTPProxy : 127.0.0.1` / `HTTPPort : 7890`，
     * 或 `SOCKSEnable : 1` / `SOCKSProxy : 127.0.0.1` / `SOCKSPort : 7890`。
     * 优先级：HTTP → HTTPS → SOCKS；PAC 优先判定（有脚本就不猜固定地址）。
     */
    fun parseScutil(output: String): Inspect {
        val values = HashMap<String, String>()
        output.lineSequence().forEach { line ->
            val match = SCUTIL_LINE.matchEntire(line) ?: return@forEach
            values[match.groupValues[1]] = match.groupValues[2].trim().trimEnd(',')
        }
        if (values["ProxyAutoConfigEnable"] == "1" ||
            values["ProxyAutoConfigURLString"]?.isNotBlank() == true
        ) {
            return Inspect.PacUnsupported
        }
        return enabled(values, "HTTPEnable", "HTTPProxy", "HTTPPort", 80, socks = false)
            ?: enabled(values, "HTTPSEnable", "HTTPSProxy", "HTTPSPort", 443, socks = false)
            ?: enabled(values, "SOCKSEnable", "SOCKSProxy", "SOCKSPort", 1080, socks = true)
            ?: Inspect.Direct
    }

    private fun enabled(
        values: Map<String, String>,
        flag: String,
        hostKey: String,
        portKey: String,
        defaultPort: Int,
        socks: Boolean,
    ): Inspect.Proxy? {
        if (values[flag] != "1") return null
        val host = values[hostKey]?.trim().orEmpty()
        if (host.isEmpty()) return null
        val port = values[portKey]?.trim()?.toIntOrNull() ?: defaultPort
        if (port !in 1..65535) return null
        return Inspect.Proxy(host, port, socks)
    }

    // ---- Windows：reg query（不引入 JNA） ----

    private fun inspectWindows(): Inspect {
        val enable = readRegistryInt("ProxyEnable") ?: 0
        val server = readRegistryString("ProxyServer")
        val pac = readRegistryString("AutoConfigURL")
        val result = parseWindowsRegistry(enable, server, pac)
        // 注册表没配置时，部分代理软件只写环境变量
        return if (result == Inspect.Direct) parseEnvironment(System.getenv()) ?: Inspect.Direct else result
    }

    /** 解析 Windows「Internet 选项」；`ProxyServer` 支持单值与 `http=...;https=...;socks=...` 分协议形式 */
    fun parseWindowsRegistry(enable: Int, server: String?, autoConfigUrl: String?): Inspect {
        if (!autoConfigUrl.isNullOrBlank()) return Inspect.PacUnsupported
        if (enable != 1) return Inspect.Direct
        val raw = server?.trim().orEmpty()
        if (raw.isEmpty()) return Inspect.Direct
        if (raw.contains('=')) {
            val parts = raw.split(';').mapNotNull { segment ->
                val index = segment.indexOf('=')
                if (index <= 0) null
                else segment.substring(0, index).trim().lowercase() to segment.substring(index + 1).trim()
            }
            listOf("http" to 80, "https" to 443).forEach { (key, port) ->
                parts.firstOrNull { it.first == key }?.second
                    ?.let { parseProxyUrl(it, port) }
                    ?.let { return it }
            }
            parts.firstOrNull { it.first.startsWith("socks") }?.second
                ?.let { parseProxyUrl(it, 1080) }
                // `socks=host:port` 里的值通常不带 scheme，不能让 parseProxyUrl 当成 HTTP
                ?.let { return it.copy(socks = true) }
            return Inspect.Direct
        }
        return parseProxyUrl(raw, 80) ?: Inspect.Direct
    }

    private fun readRegistryString(name: String): String? = readRegistry(name)

    private fun readRegistryInt(name: String): Int? = readRegistry(name)?.let { raw ->
        val value = raw.trim().lowercase()
        if (value.startsWith("0x")) value.removePrefix("0x").toIntOrNull(16)
        else value.toIntOrNull()
    }

    /** `reg query ... /v Name` 输出形如 `    ProxyEnable    REG_DWORD    0x1` */
    private fun readRegistry(name: String): String? {
        val output = runCommand(listOf("reg", "query", WINDOWS_REG_KEY, "/v", name)) ?: return null
        return output.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith(name, ignoreCase = true)) return@mapNotNull null
            trimmed.split(Regex("\\s+"), limit = 3).getOrNull(2)?.trim()
        }.firstOrNull()
    }

    // ---- Linux / 其他：环境变量 → gsettings → JVM ProxySelector ----

    private val ENV_KEYS = listOf(
        "https_proxy", "HTTPS_PROXY",
        "all_proxy", "ALL_PROXY",
        "http_proxy", "HTTP_PROXY",
    )

    fun parseEnvironment(env: Map<String, String>): Inspect? {
        ENV_KEYS.forEach { key -> parseProxyUrl(env[key])?.let { return it } }
        return null
    }

    private fun inspectUnix(): Inspect =
        parseEnvironment(System.getenv())
            ?: inspectGsettings()
            ?: fallbackSelector()

    private fun inspectGsettings(): Inspect? {
        val mode = runCommand(listOf("gsettings", "get", "org.gnome.system.proxy", "mode"))
            ?.trim()?.trim('\'', '"') ?: return null
        if (mode != "manual") return null
        val host = runCommand(listOf("gsettings", "get", "org.gnome.system.proxy.http", "host"))
            ?.trim()?.trim('\'', '"')?.takeIf { it.isNotBlank() } ?: return null
        val port = runCommand(listOf("gsettings", "get", "org.gnome.system.proxy.http", "port"))
            ?.trim()?.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return Inspect.Proxy(host, port)
    }

    /** 仅在 JVM 显式开启 `-Djava.net.useSystemProxies=true` 时兜底，避免影响「直连」模式 */
    private fun fallbackSelector(): Inspect {
        if (System.getProperty("java.net.useSystemProxies") != "true") return Inspect.Direct
        return runCatching {
            ProxySelector.getDefault()
                ?.select(URI("https://www.example.com"))
                .orEmpty()
                .firstOrNull { it.type() != Proxy.Type.DIRECT }
                ?.let { selected ->
                    (selected.address() as? InetSocketAddress)?.let { address ->
                        Inspect.Proxy(
                            address.hostString,
                            address.port,
                            selected.type() == Proxy.Type.SOCKS,
                        ).takeIf { address.port in 1..65535 }
                    }
                } ?: Inspect.Direct
        }.getOrDefault(Inspect.Direct)
    }

    /** 解析 `scheme://host:port` 或 `host:port`；socks* 协议映射为 SOCKS 代理 */
    fun parseProxyUrl(raw: String?, defaultPort: Int = 80): Inspect.Proxy? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withScheme = if ("://" in value) value else "http://$value"
        return runCatching {
            val uri = URI(withScheme)
            val host = uri.host ?: return@runCatching null
            val port = if (uri.port > 0) uri.port else defaultPort
            if (port !in 1..65535) return@runCatching null
            Inspect.Proxy(host, port, uri.scheme.orEmpty().lowercase().startsWith("socks"))
        }.getOrNull()
    }

    private fun runCommand(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()
}

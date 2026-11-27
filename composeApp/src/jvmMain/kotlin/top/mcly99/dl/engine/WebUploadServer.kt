package top.mcly99.dl.engine

import top.mcly99.dl.utils.ignoreFailure
import top.mcly99.dl.utils.closeQuietly
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import top.mcly99.dl.theme.UiStrings
import top.mcly99.dl.theme.zhCN
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** 本机 IPv4 候选地址，附带挑选所需的网卡属性。 */
internal data class LocalIpCandidate(
    val address: Inet4Address,
    val interfaceName: String,
    val isVirtual: Boolean,
    val isPointToPoint: Boolean,
)

private val VIRTUAL_INTERFACE_KEYWORDS = listOf(
    "vpn", "tap", "tun", "tailscale", "zerotier", "wsl", "docker", "vmware",
    "virtualbox", "vethernet", "hyper-v",
)

/** multipart 前导内容允许跳过的行数（正常浏览器为 0，仅用于兼容个别客户端） */
private const val MAX_PREAMBLE_LINES = 64

/** 落盘缓冲：copyPart 逐字节输出，必须用带缓冲的输出流，否则大文件会慢到不可用 */
private const val COPY_BUFFER_SIZE = 64 * 1024

/**
 * 挑选最可能被局域网访问的地址：真实网卡优先，VPN/虚拟网卡最后。
 *
 * ponytail: 命名启发式，不查默认路由；多网卡顺序仍可能不符合预期，
 * 需要时再把全部地址暴露到 UI。
 */
internal fun bestLocalIp(candidates: List<LocalIpCandidate>): Inet4Address? {
    fun score(candidate: LocalIpCandidate): Int {
        val name = candidate.interfaceName.lowercase()
        return when {
            candidate.isVirtual || VIRTUAL_INTERFACE_KEYWORDS.any { it in name } -> 0
            candidate.isPointToPoint -> 1
            candidate.address.isSiteLocalAddress -> 4
            else -> 3
        }
    }
    return candidates.maxByOrNull(::score)?.address
}

/**
 * Web上传服务器
 *
 * 基于 JDK 内置 HttpServer 实现的局域网文件上传服务，浏览器打开访问地址即可拖拽上传。
 *
 * 核心功能：
 * - HTTP服务：`GET /session?token=...` 一次性引导，`GET /` 上传页，`POST /` 接收 multipart
 * - 访问控制：引导令牌 128 bit、每次真正启动/换端口轮转、失败按 IP 限速；校验通过后
 *   只签发 HttpOnly 会话 Cookie 并 302 到无 token 的地址，页面 JS 与上传请求都不再持有
 *   令牌本体（P2-18 收口）
 * - 流式落盘：以固定缓冲区边读边写，不把请求体整体读入内存
 * - 限制保护：请求体上限、单文件上限、单请求文件数、并发上传数
 * - 资源管理：临时文件失败即清理、线程池随服务器停止而释放
 *
 * @property saveDir 上传文件存储目录（默认用户下载目录）
 * @property strings UI字符串资源
 * @property onFileReceived 文件接收完成回调
 * @property accessToken 一次性引导令牌，URL 形如 `http://ip:port/session?token=xxx`；
 *   登录后浏览器地址栏回到 `http://ip:port/`，后续只靠会话 Cookie
 * @property bindHost 监听地址，默认 `0.0.0.0`（全部网卡）；可指定单张网卡 IP 收窄暴露面
 *
 * @see TransferEngine 局域网传输引擎
 * @see TransferViewModel 传输功能ViewModel
 */
class WebUploadServer(
    saveDir: String = System.getProperty("user.home") + "/Downloads",
    /**
     * 上传连接的空闲读超时（毫秒）。
     *
     * 做成参数只为可测：真机用 [AppConstants.WEB_UPLOAD_IDLE_TIMEOUT_MS]（60s），
     * 测试里压到几百毫秒才能在秒级验证「挂死的连接会被断开」。
     */
    private val idleTimeoutMs: Long = AppConstants.WEB_UPLOAD_IDLE_TIMEOUT_MS,
    /**
     * 监听地址。默认 `0.0.0.0` 是「局域网可访问」的前提，不能无条件改掉；
     * P2-18 把它做成可配置项，需要只暴露到某张网卡时由调用方显式传入该网卡 IP。
     */
    private val bindHost: String = "0.0.0.0",
    /** 单调时钟（毫秒），失败限速的窗口判定用；单测可注入假时钟 */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile
    var strings: UiStrings = zhCN()
    @Volatile
    var onFileReceived: (File) -> Unit = {}

    var saveDir: String = saveDir
        set(value) {
            field = value
            File(value).mkdirs()
        }

    /**
     * P2-01：启动 / 停止串行化。
     *
     * `start()` 由 `TransferViewModel` 的 `ioScope`（多线程）调用，`stop()` 在 Main，
     * `setWebServerEnabled` 的防抖重启又是第三个协程 —— 四个生命周期字段原来都是普通
     * 字段且无互斥，「禁用」与「延迟重启」交错时会互相覆盖 server / executor / watchdog。
     */
    private val lifecycleLock = Any()

    @Volatile
    private var server: HttpServer? = null
    @Volatile
    private var executor: ExecutorService? = null

    /** 当前实际监听的端口；未运行时是最后一次成功启动的端口 */
    @Volatile
    var port: Int = AppConstants.DEFAULT_WEB_PORT
        private set

    /**
     * 引导令牌：只在 `GET /session?token=xxx` 用一次，用来换一枚会话 Cookie。
     *
     * P2-18：原来是 `UUID` 前 16 hex = **64 bit**，且进程生命周期内不轮转；
     * 现在改为 128 bit `SecureRandom`，每次真正启动 / 换端口都生成新的一枚。
     * setter 收成私有，外部只能经 [rotateAccessToken] 轮转，避免又退回弱令牌。
     */
    @Volatile
    var accessToken: String = randomToken()
        private set

    /** 令牌轮转回调：让 9877 接收端与 8080 网页上传始终用同一枚凭据（P2-18） */
    @Volatile
    var onTokenRotated: (String) -> Unit = {}

    /** 失败尝试按来源 IP 记录的滑动窗口状态 */
    private val tokenFailures = ConcurrentHashMap<String, TokenFailure>()

    /**
     * P2-18 收口：引导令牌换成的短会话。
     *
     * 会话 id 同样是 128 bit `SecureRandom`；只经 HttpOnly Cookie 传递，不入 URL、
     * 不写进上传页 JS。令牌轮转 / 服务停止时整表清空，旧会话立即失效。
     */
    private val sessions = ConcurrentHashMap<String, Session>()

    private class Session(@Volatile var expiresAtMs: Long)

    private class TokenFailure(
        @Volatile var count: Int,
        @Volatile var windowStartMs: Long,
        @Volatile var blockedUntilMs: Long,
    )

    /** 同时进行的上传请求上限 */
    private val uploadSlots = Semaphore(AppConstants.MAX_CONCURRENT_UPLOADS)

    val isRunning: Boolean get() = server != null

    companion object {
        private const val TOKEN_BYTES = 16                       // 128 bit
        /** 会话 Cookie 名；HttpOnly + SameSite=Lax，局域网 http 场景无法加 Secure */
        internal const val SESSION_COOKIE = "KTD_SESSION"
        /** 会话空闲有效期：12 小时，足够一次局域网传输会话，又不至于长期悬挂 */
        internal const val SESSION_TTL_MS = 12 * 60 * 60 * 1000L
        /** 单个来源 IP 在一个窗口内允许的失败次数 */
        internal const val MAX_TOKEN_FAILURES = 5
        internal const val TOKEN_FAILURE_WINDOW_MS = 60_000L
        internal const val TOKEN_LOCKOUT_MS = 60_000L
        /** 失败记录按来源 IP 计数，上限防止 0.0.0.0 暴露时被大量来源撑大内存 */
        private const val MAX_TRACKED_FAILURE_IPS = 256

        private val TOKEN_RANDOM = SecureRandom()

        private val ILLEGAL_NAME_CHARS = charArrayOf('<', '>', ':', '"', '/', '\\', '|', '?', '*', '\u0000')
        private val WINDOWS_RESERVED_NAMES = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        )

        /** 128 bit 随机令牌（32 位小写 hex）。用 `SecureRandom`，不用 UUID 的时间/版本位。 */
        private fun randomToken(): String {
            val bytes = ByteArray(TOKEN_BYTES)
            TOKEN_RANDOM.nextBytes(bytes)
            return buildString(TOKEN_BYTES * 2) {
                for (b in bytes) {
                    val v = b.toInt() and 0xff
                    append(Character.forDigit(v ushr 4, 16))
                    append(Character.forDigit(v and 0x0f, 16))
                }
            }
        }
    }

    /** 生成并启用一枚新令牌，同时通知接收端同步。返回新令牌。 */
    fun rotateAccessToken(): String {
        val fresh = randomToken()
        accessToken = fresh
        // 旧令牌与旧会话立刻失效；历史失败计数也一起作废，避免旧状态误伤新令牌的合法请求
        tokenFailures.clear()
        sessions.clear()
        onTokenRotated(fresh)
        return fresh
    }

    /** 失败尝试限速：返回剩余锁定毫秒（0 = 未锁定）。 */
    private fun failureLockRemaining(ip: String, now: Long): Long {
        val state = tokenFailures[ip] ?: return 0L
        return (state.blockedUntilMs - now).coerceAtLeast(0L)
    }

    private fun noteTokenFailure(ip: String, now: Long) {
        if (tokenFailures.size > MAX_TRACKED_FAILURE_IPS) {
            tokenFailures.entries.removeAll { (_, s) ->
                s.blockedUntilMs <= now && now - s.windowStartMs >= TOKEN_FAILURE_WINDOW_MS
            }
        }
        // compute 对该 key 持锁，窗口重置 / 计数 / 置锁定都是一个原子步骤
        tokenFailures.compute(ip) { _, existing ->
            val s = existing ?: TokenFailure(0, now, 0L)
            if (now - s.windowStartMs >= TOKEN_FAILURE_WINDOW_MS) {
                s.count = 0
                s.windowStartMs = now
            }
            s.count++
            if (s.count >= MAX_TOKEN_FAILURES) {
                s.blockedUntilMs = now + TOKEN_LOCKOUT_MS
                s.count = 0
                s.windowStartMs = now
            }
            s
        }
    }

    /** 内部：请求被拒绝（4xx） */
    private class Rejected(val status: Int, val code: String) : Exception(code)

    // ------------------------------------------------------------------ 生命周期

    /**
     * 启动（或在换端口时重启）服务。
     *
     * **先在目标端口把新服务起起来，成功了才停旧的**（P1-20）。原先的顺序是「先 stop 再
     * bind」，于是在设置里把端口改成一个被占用的号，就会把原本正常工作的服务停掉、
     * 新服务又起不来 —— 用户看到的是「改个端口，上传功能就没了」。
     *
     * 同一端口重复调用直接返回 true：先停后建会踩 `BindException`（监听中的端口不能被
     * 第二个 socket 抢，`SO_REUSEADDR` 也救不了），而且本来就没有重启的必要。
     *
     * P2-01：整个启动过程与 [stop] 共用 [lifecycleLock] 串行化，避免多个协程同时重建
     * server / executor / watchdog 时互相覆盖。[allowStart] 会在**同一把锁内**再核一次
     * 「这次启动是否仍然被期望」，用来关掉「用户已禁用、但启动请求已派发到 IO 线程」的窗口。
     *
     * @return 目标端口上是否有服务在监听
     */
    fun start(listenPort: Int, allowStart: (() -> Boolean)? = null): Boolean = synchronized(lifecycleLock) {
        if (allowStart != null && !allowStart()) {
            AppLogger.info("WebUpload", "启动请求已过期（服务已被禁用或端口已变更），跳过")
            return@synchronized false
        }
        if (server != null && listenPort == port) {
            AppLogger.info("WebUpload", "服务已在端口 $port 运行，跳过重启")
            return@synchronized true
        }

        var created: HttpServer? = null
        var pool: ExecutorService? = null
        try {
            created = HttpServer.create()
            created.bind(
                if (bindHost.isBlank() || bindHost == "0.0.0.0") InetSocketAddress(listenPort)
                else InetSocketAddress(bindHost, listenPort),
                0,
            )
            created.createContext("/") { exchange ->
                try {
                    when (exchange.requestMethod) {
                        // P2-18：令牌只在这条一次性引导路径出现，成功后 302 到无 token 的 /
                        "GET" -> if (exchange.requestURI.path == "/session") {
                            bootstrapSession(exchange)
                        } else {
                            serveUploadPage(exchange)
                        }
                        "POST" -> handleUpload(exchange)
                        else -> respondJson(exchange, 405, "{\"success\":false,\"error\":\"method_not_allowed\"}")
                    }
                } catch (e: Exception) {
                    AppLogger.warn("WebUpload", "请求处理失败: ${e.message}", e)
                    try { respondJson(exchange, 500, "{\"success\":false,\"error\":\"internal_error\"}") } catch (_: Exception) {}
                }
            }
            pool = Executors.newFixedThreadPool(4)
            created.executor = pool
            // 每次真正启动 / 换端口都轮转令牌：旧地址（含已打开的上传页）立即失效。
            // 必须在 start() 之前，避免新服务有一瞬间还在吃旧令牌。
            rotateAccessToken()
            created.start()
            ensureWatchdog()

            // 新服务已在监听：这时候才摘掉旧的，失败路径不会影响正在服务的实例
            val previousServer = server
            val previousPool = executor
            server = created
            executor = pool
            port = listenPort
            if (previousServer != null) {
                AppLogger.info("WebUpload", "端口切换完成，关闭旧服务")
                // P2-01：HttpServer.stop(0) 不会关已建立的 HttpExchange；切端口时先关掉
                // 属于旧服务的活跃上传，否则旧线程池里的阻塞读会一直挂着。
                closeActiveUploads(previousServer)
                shutdown(previousServer, previousPool)
            }
            AppLogger.info("WebUpload", "服务已启动: $bindHost:$port (本机 IP: ${getLocalIp()})")
            true
        } catch (e: Exception) {
            AppLogger.warn("WebUpload", "启动失败，端口 $listenPort: ${e.message}", e)
            ignoreFailure("WebUploadServer", "停止内嵌服务器") { created?.stop(0) }
            ignoreFailure("WebUploadServer", "关闭线程池") { pool?.shutdownNow() }
            // 旧服务保持原样：这里不动 server / executor / port
            false
        }
    }

    fun stop(): Unit = synchronized(lifecycleLock) {
        val previous = server
        val pool = executor
        server = null
        executor = null
        // P2-01：顺序很关键。原来只 `activeUploads.clear()` + `HttpServer.stop(0)`，
        // 既不关已建立的 HttpExchange（阻塞中的 read 一直占住业务线程），又先把看门狗
        // 关掉、让唯一的兜底也失效。现在先主动关活跃连接，再停监听 / 线程池，最后关看门狗。
        closeActiveUploads(null)
        shutdown(previous, pool)
        watchdog?.shutdownNow()
        watchdog = null
        activeUploads.clear()
        tokenFailures.clear()
        sessions.clear()
        AppLogger.info("WebUpload", "服务已停止")
    }

    /**
     * 启动空闲连接看门狗（已启动则跳过）。
     *
     * 独立于业务线程池：它要做的正是「在业务线程被 socket 卡死时动手关连接」，
     * 和上传共用线程池就会一起卡死。
     */
    private fun ensureWatchdog() {
        if (watchdog != null) return
        val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "web-upload-watchdog").apply { isDaemon = true }
        }
        val started = runCatching {
            scheduler.scheduleWithFixedDelay(
                { runCatching { sweepIdleUploads() }.onFailure { AppLogger.warn("WebUpload", "空闲检查失败: ${it.message}") } },
                watchdogIntervalMs(),
                watchdogIntervalMs(),
                TimeUnit.MILLISECONDS,
            )
        }.isSuccess
        if (!started) {
            AppLogger.warn("WebUpload", "空闲超时看门狗启动失败，长连接可能占住上传线程")
            ignoreFailure("WebUploadServer", "关闭调度器") { scheduler.shutdownNow() }
            return
        }
        watchdog = scheduler
    }

    /** 关闭一个具体的服务实例与其线程池；不影响 [server] 字段，供端口切换时定点回收 */
    private fun shutdown(target: HttpServer?, pool: ExecutorService?) {
        ignoreFailure("WebUploadServer", "停止内嵌服务器") { target?.stop(0) }
        // 关键：释放线程池，否则反复改端口会累积线程
        ignoreFailure("WebUploadServer", "关闭线程池") { pool?.shutdownNow() }
        try { pool?.awaitTermination(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun getLocalIp(): String {
        val candidates = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { nif ->
                runCatching {
                    if (!nif.isUp || nif.isLoopback) return@runCatching emptyList()
                    nif.inetAddresses.toList().mapNotNull { addr ->
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            LocalIpCandidate(
                                address = addr,
                                interfaceName = nif.name.orEmpty(),
                                isVirtual = nif.isVirtual,
                                isPointToPoint = nif.isPointToPoint,
                            )
                        } else null
                    }
                }.getOrDefault(emptyList())
            }
        }.getOrDefault(emptyList())

        bestLocalIp(candidates)?.let { return it.hostAddress ?: "127.0.0.1" }

        try {
            val host = InetAddress.getLocalHost()
            if (!host.isLoopbackAddress) return host.hostAddress ?: "127.0.0.1"
        } catch (_: Exception) {}

        return "127.0.0.1"
    }

    /** 给界面展示用的一次性引导地址；浏览器登录后地址栏会回到无 token 的 `/` */
    fun accessUrl(hostPort: String = "${getLocalIp()}:$port"): String =
        "http://$hostPort/session?token=$accessToken"

    // ------------------------------------------------------------------ HTTP

    private fun respondJson(exchange: HttpExchange, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** 转义成合法 JSON 字符串（含控制字符） */
    private fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun queryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = decode(pair.substringBefore('='))
            val value = decode(pair.substringAfter('=', ""))
            key to value
        }.toMap()
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", " "), "UTF-8") }.getOrDefault(s)

    /** 校验访问令牌；失败时已写出 403 / 429 响应 */
    private fun checkToken(exchange: HttpExchange): Boolean {
        val supplied = queryParams(exchange.requestURI.rawQuery)["token"].orEmpty()
        val ip = runCatching { exchange.remoteAddress?.address?.hostAddress }
            .getOrNull().orEmpty().ifBlank { "unknown" }
        val now = clock()
        val lockMs = failureLockRemaining(ip, now)
        if (lockMs > 0) {
            exchange.responseHeaders.add("Retry-After", ((lockMs + 999) / 1000).toString())
            respondJson(exchange, 429, "{\"success\":false,\"error\":\"too_many_attempts\"}")
            return false
        }
        // P2-18：常量时间比较（令牌定长，长度差异不泄漏内容）；
        // 用 orEmpty() 把「没带 token」和「带错 token」走同一条比较路径，不要提前 return。
        if (MessageDigest.isEqual(
                supplied.toByteArray(Charsets.UTF_8),
                accessToken.toByteArray(Charsets.UTF_8),
            )
        ) {
            // 认证成功即清掉该来源的历史失败，避免用户输错一次旧地址后连正确地址都用不了
            tokenFailures.remove(ip)
            return true
        }
        noteTokenFailure(ip, now)
        respondJson(exchange, 403, "{\"success\":false,\"error\":\"forbidden\"}")
        return false
    }

    /**
     * 一次性引导：query 里的令牌只在这里出现，换到 HttpOnly Cookie 后立刻 302 到 `/`。
     *
     * 这样令牌不会留在地址栏 / 历史 / 上传页 JS 中；后续浏览器与 XHR 都只带会话 Cookie。
     * 局域网 http 无法加 `Secure`，用 `HttpOnly + SameSite=Lax + no-store + Referrer-Policy`
     * 把泄露面收窄；真要公网使用应前置 HTTPS 并把 `bindHost` 收窄到单网卡。
     */
    private fun bootstrapSession(exchange: HttpExchange) {
        if (!checkToken(exchange)) return
        val now = clock()
        pruneSessions(now)
        val id = randomToken()
        sessions[id] = Session(now + SESSION_TTL_MS)
        exchange.responseHeaders.add(
            "Set-Cookie",
            "$SESSION_COOKIE=$id; Path=/; HttpOnly; SameSite=Lax; Max-Age=${SESSION_TTL_MS / 1000}",
        )
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.responseHeaders.add("Referrer-Policy", "no-referrer")
        exchange.responseHeaders.add("Location", "/")
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    /** 清掉过期会话，避免长期运行的表无限增长 */
    private fun pruneSessions(now: Long) {
        sessions.entries.removeAll { (_, session) -> session.expiresAtMs <= now }
    }

    /** 校验会话 Cookie；失败时已写出 403。会话 id 是 128 bit 随机值，不做 URL 回退。 */
    private fun checkSession(exchange: HttpExchange): Boolean {
        val cookie = exchange.requestHeaders.getFirst("Cookie").orEmpty()
        val id = cookie.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$SESSION_COOKIE=") }
            ?.substringAfter('=', "")
            .orEmpty()
        if (id.isBlank()) {
            respondJson(exchange, 403, "{\"success\":false,\"error\":\"forbidden\"}")
            return false
        }
        val now = clock()
        val session = sessions[id]
        if (session == null || session.expiresAtMs <= now) {
            if (session != null) sessions.remove(id)
            respondJson(exchange, 403, "{\"success\":false,\"error\":\"forbidden\"}")
            return false
        }
        session.expiresAtMs = now + SESSION_TTL_MS
        return true
    }

    private fun serveUploadPage(exchange: HttpExchange) {
        if (!checkSession(exchange)) return
        val bytes = buildUploadPage().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        // 防御纵深：令牌虽已从地址栏移除，仍禁止把当前地址当 Referer 外发（P2-18）
        exchange.responseHeaders.add("Referrer-Policy", "no-referrer")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun handleUpload(exchange: HttpExchange) {
        if (!checkSession(exchange)) return

        val contentType = exchange.requestHeaders.getFirst("Content-Type") ?: ""
        val boundary = if (contentType.contains("multipart/form-data")) extractBoundary(contentType) else null
        if (boundary == null) {
            respondJson(exchange, 400, "{\"success\":false,\"error\":\"invalid_request\"}")
            return
        }

        val declaredLength = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: -1L
        if (declaredLength > AppConstants.MAX_REQUEST_BYTES) {
            respondJson(exchange, 413, "{\"success\":false,\"error\":\"payload_too_large\"}")
            return
        }
        if (!uploadSlots.tryAcquire()) {
            respondJson(exchange, 429, "{\"success\":false,\"error\":\"too_many_uploads\"}")
            return
        }

        val saved = mutableListOf<File>()
        // P2-01：记录该请求属于哪个服务实例，换端口时才能只关旧服务的活跃连接
        val session = UploadActivity(ownerOf(exchange), exchange)
        activeUploads.add(session)
        try {
            val files = exchange.requestBody.use { body ->
                parseMultipart(
                    TouchInputStream(body, session),
                    boundary,
                    declaredLength.takeIf { it >= 0 } ?: AppConstants.MAX_REQUEST_BYTES,
                )
            }
            for (f in files) {
                saved.add(f)
                AppLogger.info("WebUpload", "已接收: ${f.name} (${f.length()} bytes)")
                onFileReceived(f)
            }
            val json = saved.joinToString(",", prefix = "{\"success\":true,\"files\":[", postfix = "]}") {
                jsonString(it.name)
            }
            respondJson(exchange, 200, json)
        } catch (e: Rejected) {
            saved.forEach { it.delete() }
            respondJson(exchange, e.status, "{\"success\":false,\"error\":\"${e.code}\"}")
        } catch (e: Exception) {
            // 不回显内部异常信息，避免泄露本机路径等细节
            AppLogger.warn("WebUpload", "上传处理失败: ${e.message}", e)
            saved.forEach { it.delete() }
            try { respondJson(exchange, 500, "{\"success\":false,\"error\":\"internal_error\"}") } catch (_: Exception) {}
        } finally {
            activeUploads.remove(session)
            uploadSlots.release()
            // 尽力读完/关闭请求体，避免连接复用异常
            closeQuietly(exchange.requestBody, "WebUploadServer")
        }
    }

    // ------------------------------------------------------------------ 空闲连接看门狗

    /**
     * 一个进行中的上传请求的活动记录。
     *
     * 只为了回答一个问题：「这条连接还有字节在流动吗」。`HttpServer` 没有提供读超时
     * 参数，端口层面的 `SocketTimeout` 也拿不到，所以只能自己数「最后一次读到数据是
     * 什么时候」，再由看门狗关掉长时间没动静的连接（P1-22）。
     */
    private class UploadActivity(val owner: HttpServer?, val exchange: HttpExchange) {
        @Volatile
        private var lastActivityAt: Long = System.currentTimeMillis()

        fun touch() {
            lastActivityAt = System.currentTimeMillis()
        }

        fun idleMs(now: Long): Long = now - lastActivityAt
    }

    /** 取请求所属的服务实例；自定义/伪造的 HttpExchange 没有上下文时返回 null */
    private fun ownerOf(exchange: HttpExchange): HttpServer? =
        runCatching { exchange.httpContext.server }.getOrNull()

    /**
     * P2-01：主动关闭活跃上传连接，让阻塞中的 `read` 抛异常并释放业务线程。
     *
     * [owner] 为 null 表示关闭全部（[stop]）；否则只关属于该服务实例的连接（换端口时
     * 不能把刚起来的新服务上的上传也掐掉）。`remove` 的原子性保证看门狗的空闲清扫
     * 与这里的停止清扫不会重复关同一个 exchange。
     */
    private fun closeActiveUploads(owner: HttpServer?) {
        for (session in activeUploads) {
            if (owner != null && session.owner != null && session.owner !== owner) continue
            if (!activeUploads.remove(session)) continue
            runCatching { session.exchange.close() }
                .onFailure { AppLogger.warn("WebUpload", "关闭活跃上传连接失败: ${it.message}") }
        }
    }

    /** 套在请求体外层，把每次读取都记成一次活动 */
    private class TouchInputStream(
        input: InputStream,
        private val session: UploadActivity,
    ) : java.io.FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) session.touch() }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) session.touch() }
    }

    private val activeUploads = java.util.concurrent.ConcurrentHashMap.newKeySet<UploadActivity>()

    @Volatile
    private var watchdog: java.util.concurrent.ScheduledExecutorService? = null

    /** 关掉空闲超时的连接：`exchange.close()` 会让阻塞中的 `read` 抛异常并释放线程 */
    private fun sweepIdleUploads() {
        if (activeUploads.isEmpty()) return
        val now = System.currentTimeMillis()
        for (session in activeUploads) {
            val idle = session.idleMs(now)
            if (idle < idleTimeoutMs) continue
            if (!activeUploads.remove(session)) continue
            AppLogger.warn("WebUpload", "上传连接空闲 ${idle}ms，主动断开以免占住线程")
            runCatching { session.exchange.close() }
                .onFailure { AppLogger.warn("WebUpload", "断开空闲连接失败: ${it.message}") }
        }
    }

    /**
     * 看门狗的扫描间隔：超时窗口的 1/4 起步，夹在 50ms~5s 之间。
     *
     * 跟着超时时间走而不是写死 5 秒 —— 否则把超时压到 300ms 的测试要等 5 秒才触发一次检查，
     * 而真机 60s 的超时也不需要每 50ms 去扫一遍。
     */
    private fun watchdogIntervalMs(): Long =
        (idleTimeoutMs / 4).coerceIn(50L, AppConstants.WEB_UPLOAD_WATCHDOG_INTERVAL_MS)

    private fun extractBoundary(contentType: String): String? {
        val idx = contentType.indexOf("boundary=")
        if (idx < 0) return null
        val boundary = contentType.substring(idx + 9).trim().substringBefore(';').trim()
        val unquoted = if (boundary.startsWith("\"") && boundary.endsWith("\""))
            boundary.substring(1, boundary.length - 1) else boundary
        return unquoted.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ multipart 流式解析

    /**
     * 流式解析 multipart/form-data。
     *
     * 工作流程：
     * 1. 跳过 RFC 2046 允许的前导内容，定位首个 `--boundary` 行；
     * 2. 循环读取段头（直到空行），取出 `filename=`；
     * 3. 段内容用「滑动窗口匹配 `\r\n--boundary`」的算法逐缓冲写出，遇分隔符结束；
     * 4. 补读边界行的行尾，若为 `--` 即整个 multipart 结束。
     *
     * 文件内容从不整体驻留内存，最大附加内存为 IO 缓冲 + 窗口 + 回退缓冲。
     *
     * 关键约束：定位边界、读段头、写段内容共用同一条流，**任何提前多读的字节都必须退回**，
     * 否则下一个段的段头会从文件内容中途开始解析，导致 `filename=` 抓不到。
     */
    private fun parseMultipart(rawInput: InputStream, boundary: String, maxTotal: Long): List<File> {
        // 回退缓冲与 copyPart 的单次读取上限一致，保证多读的字节一定能退回
        val input = PushbackInputStream(
            BufferedInputStream(rawInput, AppConstants.WEB_IO_BUFFER_SIZE),
            AppConstants.WEB_IO_BUFFER_SIZE,
        )
        val delimiter = "--$boundary"
        val mark = "\r\n$delimiter".toByteArray(Charsets.ISO_8859_1)
        val results = mutableListOf<File>()
        val totalRead = LongArray(1)

        try {
            // 1) 跳过前导，定位首个边界行
            var boundaryLine: ByteArray
            var skipped = 0
            while (true) {
                boundaryLine = readLine(input, totalRead, maxTotal, AppConstants.MAX_PART_HEADER_BYTES)
                    ?: throw Rejected(400, "invalid_multipart")
                if (boundaryLine.asAscii().startsWith(delimiter)) break
                if (++skipped > MAX_PREAMBLE_LINES) throw Rejected(400, "invalid_multipart")
            }
            // `--boundary--`：没有任何段
            if (boundaryLine.asAscii().trimEnd().endsWith("--")) return results

            // 2) 逐段处理
            while (true) {
                val header = readPartHeader(input, totalRead, maxTotal)
                val fileName = extractFileName(header)
                if (fileName == null) {
                    // 非文件字段：丢弃内容
                    copyPart(input, mark, null, totalRead, maxTotal)
                } else {
                    val safeName = sanitizeFileName(fileName)
                        ?: throw Rejected(400, "invalid_file_name")
                    val dir = File(saveDir).apply { mkdirs() }
                    // 越界校验放在「抢占文件名」之前：`resolveUniqueFile` 会真的创建空文件，
                    // 校验不过时再抛就会在磁盘上留一个 0 字节的残留
                    val candidate = File(dir, safeName)
                    if (!candidate.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                        throw Rejected(400, "invalid_file_name")
                    }
                    val target = resolveUniqueFile(candidate)

                    try {
                        BufferedOutputStream(FileOutputStream(target), COPY_BUFFER_SIZE).use { out ->
                            copyPart(input, mark, out, totalRead, maxTotal)
                        }
                    } catch (e: Exception) {
                        target.delete()
                        throw e
                    }
                    results.add(target)
                    // 段数超限：这个文件也属于本次请求，交给外层统一清理
                    if (results.size > AppConstants.MAX_FILES_PER_REQUEST) throw Rejected(413, "too_many_files")
                }

                // 段内容停在 `--boundary` 之后，这里补读边界行剩余部分
                val tail = readLine(input, totalRead, maxTotal, AppConstants.MAX_PART_HEADER_BYTES)
                    ?: throw Rejected(400, "incomplete_body")
                if (tail.asAscii().trimEnd().endsWith("--")) return results
            }
        } catch (e: Exception) {
            // 解析中途失败：已落盘的文件必须清掉，否则请求失败却在磁盘留下半成品
            results.forEach { it.delete() }
            throw e
        }
    }

    /** 头部/边界行只可能是 ASCII，按 Latin-1 解成字符串做前缀与结尾判断 */
    private fun ByteArray.asAscii(): String = String(this, Charsets.ISO_8859_1)

    /**
     * 读一行，返回去掉行尾（`\r\n` / `\n`）的**原始字节**；流已到末尾且没有任何字节时返回 null。
     *
     * 返回字节而非 String：`Content-Disposition` 里的中文文件名是 UTF-8 原始字节，
     * 逐字节 `toChar()` 会按 Latin-1 解码成乱码，必须留到最后统一按 UTF-8 解码。
     */
    private fun readLine(
        input: InputStream,
        totalRead: LongArray,
        maxTotal: Long,
        maxLength: Int,
    ): ByteArray? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toByteArray()
            totalRead[0]++
            if (totalRead[0] > maxTotal) throw Rejected(413, "payload_too_large")
            if (b == '\n'.code) {
                val bytes = line.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return bytes.copyOf(end)
            }
            line.write(b)
            if (line.size() > maxLength) throw Rejected(400, "header_too_large")
        }
    }

    /**
     * 读一个段的头部：连续读到空行为止，按 UTF-8 解码后返回（每行以 `\n` 结尾）。
     */
    private fun readPartHeader(input: InputStream, totalRead: LongArray, maxTotal: Long): String {
        val header = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input, totalRead, maxTotal, AppConstants.MAX_PART_HEADER_BYTES)
                ?: throw Rejected(400, "invalid_multipart")
            if (line.isEmpty()) return String(header.toByteArray(), Charsets.UTF_8)
            header.write(line)
            header.write('\n'.code)
            if (header.size() > AppConstants.MAX_PART_HEADER_BYTES) throw Rejected(400, "header_too_large")
        }
    }

    /**
     * 把当前段内容写入 [out]（null 表示丢弃），直到遇到 [mark]（`\r\n--boundary`）。
     *
     * 算法：维护一个长度等于 `mark.size` 的滑动窗口。
     * - 窗口未满时只填充；
     * - 填满后若与 `mark` 完全相同，说明内容结束，此时流位置正好停在 `--boundary` 之后，
     *   由调用方继续读边界行行尾；
     * - 否则输出窗口首字节并左移一位，再读入下一个字节。
     *
     * 一次 `read(buf)` 可能把后续段也读进来，返回前必须把多读的部分 `unread` 回去。
     */
    private fun copyPart(
        input: PushbackInputStream,
        mark: ByteArray,
        out: OutputStream?,
        totalRead: LongArray,
        maxTotal: Long,
    ) {
        val window = ByteArray(mark.size)
        var filled = 0
        var written = 0L
        val buf = ByteArray(AppConstants.WEB_IO_BUFFER_SIZE)
        while (true) {
            val n = input.read(buf)
            if (n < 0) throw Rejected(400, "incomplete_body")
            totalRead[0] += n
            if (totalRead[0] > maxTotal) throw Rejected(413, "payload_too_large")

            var i = 0
            while (i < n) {
                window[filled++] = buf[i++]
                if (filled < window.size) continue

                // 窗口已满：判断是否为分隔符
                if (equalsPrefix(window, mark)) {
                    // 多读的字节退回给后续的边界/段头解析，并修正计数避免重复累计
                    val leftover = n - i
                    if (leftover > 0) {
                        input.unread(buf, i, leftover)
                        totalRead[0] -= leftover
                    }
                    return
                }

                // 不是分隔符：输出首字节并左移一位
                out?.write(window[0].toInt() and 0xFF)
                if (++written > AppConstants.MAX_UPLOAD_FILE_BYTES) throw Rejected(413, "file_too_large")
                System.arraycopy(window, 1, window, 0, window.size - 1)
                filled = window.size - 1
            }
        }
    }

    private fun equalsPrefix(a: ByteArray, b: ByteArray): Boolean {
        if (a.size < b.size) return false
        for (k in b.indices) if (a[k] != b[k]) return false
        return true
    }
    private fun extractFileName(header: String): String? {
        val regex = Regex("""filename="([^"]*)"""")
        return regex.find(header)?.groupValues?.getOrNull(1)
    }

    /** 文件名校验与净化（与 TransferEngine 保持同一规则） */
    private fun sanitizeFileName(rawName: String): String? {
        val name = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
        if (name.isEmpty() || name == "." || name == "..") return null
        if (name.length > AppConstants.MAX_FILE_NAME_LENGTH) return null
        if (name.any { it in ILLEGAL_NAME_CHARS }) return null
        if (name.substringBefore('.').uppercase() in WINDOWS_RESERVED_NAMES) return null
        return name
    }

    private fun resolveUniqueFile(file: File): File {
        val base = file.name.substringBeforeLast('.')
        val ext = file.name.substringAfterLast('.', "")
        val extPart = if (ext.isNotEmpty() && ext != file.name) ".$ext" else ""
        var counter = 0
        while (true) {
            val candidate = if (counter == 0) file else File(file.parent, "$base($counter)$extPart")
            // 必须用原子的「抢占式创建」而不只是 `exists()` 判断（P1-21）：
            // 手机上多选几十个文件时浏览器会并发发起多个请求，两个同名文件会在
            // 「都看到不存在」之后同时写同一个路径，落盘结果互相覆盖、长度错乱。
            // createNewFile 底层是 O_CREAT|O_EXCL，只有一个调用者能拿到文件名。
            if (candidate.createNewFile()) return candidate
            if (++counter > AppConstants.MAX_NAME_COLLISION_ATTEMPTS) {
                throw Rejected(409, "too_many_name_conflicts")
            }
        }
    }

    // ------------------------------------------------------------------ 上传页

    private val ip: String get() = getLocalIp()

    private fun buildUploadPage(): String {
        val sb = StringBuilder()
        val s = strings

        sb.append("""<!DOCTYPE html>
<html lang="${s.webUploadHtmlLang}">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${s.webUploadTitle}</title>
<style>
* { margin:0; padding:0; box-sizing:border-box; }
body { font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif; background:#16161A; color:#F2F2F2; min-height:100vh; display:flex; justify-content:center; align-items:center; padding:20px; }
.container { width:100%; max-width:520px; }
h1 { text-align:center; font-size:24px; margin-bottom:4px; color:#34D399; }
.subtitle { text-align:center; font-size:13px; color:#9CA3AF; margin-bottom:24px; }
.drop-zone { border:2px dashed #3F3F46; border-radius:12px; padding:48px 24px; text-align:center; transition:all .2s; background:#1C1C22; cursor:pointer; }
.drop-zone.drag-over { border-color:#34D399; background:#1C1C22; box-shadow:0 0 24px rgba(52,211,153,0.15); }
.drop-zone .icon { font-size:48px; margin-bottom:12px; }
.drop-zone .label { font-size:16px; font-weight:600; margin-bottom:4px; }
.drop-zone .hint { font-size:13px; color:#9CA3AF; }
.file-list { margin-top:16px; }
.file-item { padding:12px 16px; background:#1C1C22; border-radius:8px; margin-bottom:8px; border:1px solid #2A2A32; }
.file-item .top-row { display:flex; align-items:center; justify-content:space-between; margin-bottom:6px; }
.file-name { flex:1; font-size:14px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.file-size { font-size:12px; color:#9CA3AF; margin:0 12px; white-space:nowrap; }
.status { font-size:12px; padding:4px 10px; border-radius:999px; white-space:nowrap; }
.status.uploading { background:rgba(59,130,246,.2); color:#60A5FA; }
.status.done { background:rgba(52,211,153,.2); color:#34D399; }
.status.error { background:rgba(239,68,68,.2); color:#F87171; }
.progress-bar { width:100%; height:6px; background:#2A2A32; border-radius:999px; overflow:hidden; margin-bottom:4px; }
.progress-bar .fill { height:100%; background:#34D399; border-radius:999px; transition:width .1s; width:0%; }
.speed { font-size:11px; color:#6B7280; }
.info { text-align:center; margin-top:24px; font-size:12px; color:#6B7280; }
.info span { color:#9CA3AF; }
.hidden { display:none; }
</style>
</head>
<body>
<div class="container">
<h1>${s.webUploadHeading}</h1>
<p class="subtitle">${s.webUploadSubtitle}</p>
<div class="drop-zone" id="dropZone">
  <div class="icon">&#128228;</div>
  <div class="label">${s.webUploadAreaLabel}</div>
  <div class="hint">${s.webUploadHint}</div>
</div>
<input type="file" id="fileInput" multiple class="hidden">
<div class="file-list" id="fileList"></div>
<div class="info">${s.webUploadConnectedTo}<span>""")

        sb.append(ip)
        sb.append(':')
        sb.append(port.toString())

        sb.append("""</span></div>
</div>
<script>
var dropZone = document.getElementById('dropZone');
var fileInput = document.getElementById('fileInput');
var fileList = document.getElementById('fileList');
// P2-18：登录后靠 HttpOnly 会话 Cookie，页面脚本里不再出现令牌本体
var uploadUrl = '/';

dropZone.addEventListener('click', function(){ fileInput.click(); });
dropZone.addEventListener('dragover', function(e){ e.preventDefault(); dropZone.classList.add('drag-over'); });
dropZone.addEventListener('dragleave', function(){ dropZone.classList.remove('drag-over'); });
dropZone.addEventListener('drop', function(e){ e.preventDefault(); dropZone.classList.remove('drag-over'); handleFiles(e.dataTransfer.files); });
fileInput.addEventListener('change', function(){ handleFiles(fileInput.files); fileInput.value = ''; });

function handleFiles(files) {
  for (var i = 0; i < files.length; i++) uploadFile(files[i]);
}

function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024*1024) return (bytes/1024).toFixed(1) + ' KB';
  if (bytes < 1024*1024*1024) return (bytes/(1024*1024)).toFixed(1) + ' MB';
  return (bytes/(1024*1024*1024)).toFixed(2) + ' GB';
}

function uploadFile(file) {
  var item = document.createElement('div');
  item.className = 'file-item';
  var nameEl = document.createElement('span');
  nameEl.className = 'file-name';
  nameEl.textContent = file.name;
  var sizeEl = document.createElement('span');
  sizeEl.className = 'file-size';
  sizeEl.textContent = formatSize(file.size);
  var statusEl = document.createElement('span');
  statusEl.className = 'status uploading';
  statusEl.textContent = '${s.webUploadStatusUploading}';
  var topRow = document.createElement('div');
  topRow.className = 'top-row';
  topRow.appendChild(nameEl); topRow.appendChild(sizeEl); topRow.appendChild(statusEl);
  var bar = document.createElement('div');
  bar.className = 'progress-bar';
  var fillEl = document.createElement('div');
  fillEl.className = 'fill';
  bar.appendChild(fillEl);
  var speedEl = document.createElement('span');
  speedEl.className = 'speed';
  speedEl.textContent = '${s.webUploadStatusPreparing}';
  item.appendChild(topRow); item.appendChild(bar); item.appendChild(speedEl);
  fileList.prepend(item);

  var fd = new FormData();
  fd.append('file', file);

  var xhr = new XMLHttpRequest();
  var startTime = Date.now();
  var lastBytes = 0;
  var lastTime = startTime;

  xhr.upload.onprogress = function(e) {
    if (!e.lengthComputable) return;
    var pct = Math.round(e.loaded / e.total * 100);
    fillEl.style.width = pct + '%';
    var now = Date.now();
    if (now - lastTime >= 1000) {
      var interval = (now - lastTime) / 1000;
      var instantSpeed = interval > 0 ? ((e.loaded - lastBytes) / interval) : 0;
      speedEl.textContent = formatSize(instantSpeed) + '/s';
      lastBytes = e.loaded;
      lastTime = now;
    }
  };

  function markDone() {
    statusEl.textContent = '${s.webUploadStatusCompleted}';
    statusEl.className = 'status done';
    fillEl.style.width = '100%';
    speedEl.textContent = formatSize(file.size) + ' ${s.webUploadStatusDone}';
  }
  function markError(msg) {
    statusEl.textContent = '${s.webUploadStatusFailed}';
    statusEl.className = 'status error';
    speedEl.textContent = msg || '';
  }

  xhr.onload = function() {
    if (xhr.status === 200) {
      try {
        var data = JSON.parse(xhr.responseText);
        if (data.success) markDone(); else markError(data.error || '');
      } catch (e) { markError(''); }
    } else if (xhr.status === 403) {
      markError('${s.webUploadForbidden}');
    } else if (xhr.status === 413) {
      markError('${s.webUploadTooLarge}');
    } else {
      markError('');
    }
  };

  xhr.onerror = function() { markError(''); };

  xhr.open('POST', uploadUrl, true);
  xhr.send(fd);
}
</script>
</body>
</html>""")
        return sb.toString()
    }
}

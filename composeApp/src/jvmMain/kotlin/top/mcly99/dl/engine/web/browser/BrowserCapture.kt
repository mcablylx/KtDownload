package top.mcly99.dl.engine.web.browser

import top.mcly99.dl.engine.isHttpToken
import top.mcly99.dl.engine.sanitizeHttpHeaderValue
import top.mcly99.dl.engine.web.MediaCandidate
import top.mcly99.dl.engine.web.MediaKind
import top.mcly99.dl.engine.web.hostOf
import top.mcly99.dl.engine.web.redactUrl
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import top.mcly99.dl.utils.SafeDelete
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 用本机 Chromium 内核（Chrome / Edge）做**请求拦截**抓取。
 *
 * ## 为什么需要它
 *
 * 静态抓取只发一次 GET 拿 HTML，不执行 JavaScript。播放器完全由脚本构建的站点，
 * HTML 里一个 `m3u8` 都没有（实测某站点 14 KB 页面里出现 0 次），地址是脚本在运行时
 * 用 `fetch` 换回来的，还可能带有时效签名（`wsSecret` / `wsTime`）与 `ip` 绑定。
 * 这类站点只能靠浏览器真的跑一遍、再从网络层把请求捞出来（v6-P1-3）。
 *
 * ## 怎么做的
 *
 * 1. 起一个**独立的**临时 profile 的无头浏览器（`--remote-debugging-port=0`，
 *    端口由浏览器自己挑，写在 `DevToolsActivePort` 里，避免与用户已有的调试端口打架）；
 * 2. 先停在 `about:blank`，连上 CDP、`Network.enable`，**再** `Page.navigate` ——
 *    顺序反了会漏掉页面加载早期的请求；
 * 3. 监听 `Network.responseReceived`，按 `mimeType` 与扩展名判定 m3u8 / mp4；
 * 4. 最后取 `Network.getAllCookies`：签名地址常绑会话，下载时要复用同一份 Cookie。
 *
 * ## 边界（写进 README 与设置页说明）
 *
 * - 用**用户机器上已有的**浏览器，安装包零增量；机器上没有 Chrome/Edge 时直接降级
 * - 无头模式，不弹窗；单页面、禁扩展、禁下载
 * - 只读：不改请求、不注入脚本
 *
 * @param proxyProvider 走代理时传给浏览器的 `--proxy-server`（只支持 HTTP 代理，不支持认证）。
 *   用 lambda 现取而不是构造时快照：设置页改了代理要立刻生效
 * @param loadTimeoutMs 页面加载 + 抓取的总时限
 * @param settleMs 出现第一条可播地址后再等多久就收工（等后续清晰度列表/分片）
 */
open class BrowserCapture(
    private val proxyProvider: () -> Proxy? = { null },
    private val loadTimeoutMs: Long = AppConstants.BROWSER_CAPTURE_TIMEOUT_MS,
    private val settleMs: Long = AppConstants.BROWSER_SETTLE_MS,
    private val browser: Path? = null,
) {
    private val running = AtomicReference<Process?>(null)

    /**
     * 同一时刻只允许一次抓取。
     *
     * 用户「取消后立刻重抓」时，上一轮可能还阻塞在 `awaitDebugPort` 的轮询里；
     * 两轮同时跑会抢 `running` 这一个引用，谁先结束谁就把对方的进程引用清掉，
     * 于是对方的 `cancel()` 找不到进程，无头浏览器就留在后台了（v7-P2-1）。
     */
    private val busy = AtomicBoolean(false)

    /** 抓取被取消时调用：直接杀浏览器，避免后台留一个无头进程跑满 15 秒 */
    open fun cancel() {
        running.get()?.let { runCatching { it.destroyForcibly() } }
    }

    /**
     * 抓取一次。
     *
     * `open` 是为了让单测能替换成假实现：真跑一次要起浏览器，在 CI 上既慢又不稳。
     */
    open fun capture(pageUrl: String): BrowserCaptureResult {
        if (!busy.compareAndSet(false, true)) {
            // 上一轮还没退出（通常是刚被取消）：这一轮直接放弃，让调用方按「没抓到」处理
            return BrowserCaptureResult(failure = BrowserCaptureFailure.Cancelled)
        }
        try {
            return captureOnce(pageUrl)
        } finally {
            busy.set(false)
        }
    }

    private fun captureOnce(pageUrl: String): BrowserCaptureResult {
        val startedAt = System.currentTimeMillis()
        val executable = browser ?: BrowserLocator.find()
        val info = executable?.let { BrowserLocator.describe(it) }

        // 诊断计数（v8-P1-1）：只用于收尾那一条结构化日志，不参与判定
        var requestsSeen = 0
        var filteredNonMedia = 0

        /**
         * 所有返回点都过这里：补上统计与耗时，并记一条 `capture_finished`。
         *
         * 「用户报抓不到」时，这一条就能区分：浏览器没起来 / 起来了但一个响应都没收到 /
         * 收到了 143 个响应但全被判成非媒体 / 正常抓到。缺了它只能靠猜。
         */
        fun finish(result: BrowserCaptureResult): BrowserCaptureResult {
            val elapsed = System.currentTimeMillis() - startedAt
            val outcome = result.failure?.name?.lowercase()
                ?: if (result.candidates.isEmpty()) "no_candidates" else "success"
            val browserLabel = info?.let { "${it.kind}/${it.version ?: "unknown"}" } ?: "none"
            AppLogger.info(
                "BrowserCapture",
                "capture_finished page_host=${hostOf(pageUrl)} outcome=$outcome" +
                    " requests=$requestsSeen candidates=${result.candidates.size}" +
                    " filtered=$filteredNonMedia elapsed_ms=$elapsed" +
                    " browser=$browserLabel",
            )
            return result.copy(
                stats = CaptureStats(
                    requestsSeen = requestsSeen,
                    candidatesFound = result.candidates.size,
                    filteredNonMedia = filteredNonMedia,
                    elapsedMs = elapsed,
                    browser = info,
                ),
            )
        }

        // 「没装浏览器」也要留一条：这是最常见的降级原因，不能只靠调用方的 debug 日志
        if (executable == null) return finish(BrowserCaptureResult(failure = BrowserCaptureFailure.NoBrowser))

        val profile = runCatching { Files.createTempDirectory("ktdl-cdp-") }.getOrNull()
            ?: return finish(BrowserCaptureResult(failure = BrowserCaptureFailure.LaunchFailed))

        var process: Process? = null
        try {
            val proc = launch(executable, profile)
            process = proc
            running.set(proc)
            // 进程被杀（用户取消）时轮询要立刻退出，不能傻等满超时
            val port = awaitDebugPort(profile, AppConstants.BROWSER_START_TIMEOUT_MS) { proc.isAlive }
                ?: return finish(BrowserCaptureResult(failure = BrowserCaptureFailure.NoDebugPort))
            val wsUrl = awaitPageTarget(port, AppConstants.BROWSER_START_TIMEOUT_MS) { proc.isAlive }
                ?: return finish(BrowserCaptureResult(failure = BrowserCaptureFailure.NoTarget))

            CdpSession(wsUrl).use { session ->
                session.connect()
                session.command("Network.enable")
                session.command("Page.enable")
                val navigated = session.command("Page.navigate", mapOf("url" to pageUrl))
                if (navigated?.obj("error") != null) {
                    return finish(BrowserCaptureResult(failure = BrowserCaptureFailure.NavigateFailed))
                }

                val found = LinkedHashMap<String, MediaCandidate>()
                val deadline = System.currentTimeMillis() + loadTimeoutMs
                var lastFoundAt = 0L

                while (System.currentTimeMillis() < deadline) {
                    if (!proc.isAlive) break
                    val message = session.nextEvent(250)
                    if (message != null && message.str("method") == "Network.responseReceived") {
                        requestsSeen++
                        val candidate = toCandidate(message, pageUrl)
                        if (candidate == null) {
                            filteredNonMedia++
                        } else if (found.putIfAbsent(candidate.url, candidate) == null) {
                            lastFoundAt = System.currentTimeMillis()
                            // 地址里可能带 wsSecret / wsTime，日志只留主机 + 路径（v8-P0-2）
                            AppLogger.debug("BrowserCapture", "拦截到 ${candidate.kind}: ${redactUrl(candidate.url)}")
                        }
                    }
                    // 判断放在循环体而不是「发现新候选」分支里：安静下来后 nextEvent 只会一直超时，
                    // 放在分支里就永远走不到，白等满整个 loadTimeoutMs
                    if (lastFoundAt > 0 &&
                        found.values.any { it.kind.canPlay } &&
                        System.currentTimeMillis() - lastFoundAt >= settleMs
                    ) {
                        break
                    }
                }

                val cookies = collectCookies(session)
                return finish(BrowserCaptureResult(candidates = found.values.toList(), cookies = cookies))
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return finish(BrowserCaptureResult(failure = BrowserCaptureFailure.Cancelled))
        } catch (e: LinkageError) {
            // 打包版漏掉 java.net.http 模块时，构造 HttpClient / CdpSession 会抛
            // NoClassDefFoundError。它是 Error 不是 Exception，下面的 catch 接不住，
            // 会一路冒泡到全局未捕获处理器弹窗；深度抓取只是增强能力，这里降级即可。
            AppLogger.warn("BrowserCapture", "深度抓取运行时依赖缺失: ${e.javaClass.name}: ${e.message}", e)
            return finish(
                BrowserCaptureResult(
                    failure = BrowserCaptureFailure.LaunchFailed,
                    detail = "运行时缺少 java.net.http，请重新打包",
                )
            )
        } catch (e: Exception) {
            AppLogger.warn("BrowserCapture", "深度抓取失败: ${e.message}", e)
            return finish(BrowserCaptureResult(failure = BrowserCaptureFailure.LaunchFailed, detail = e.message))
        } finally {
            // 只清自己那一条：并发时 `set(null)` 会把下一轮刚登记的进程引用抹掉
            running.compareAndSet(process, null)
            runCatching { process?.destroyForcibly() }
            runCatching { process?.waitFor(2, TimeUnit.SECONDS) }
            deleteQuietly(profile)
        }
    }

    // ------------------------------------------------------------------ 启动与发现

    private fun launch(executable: Path, profile: Path): Process {
        val args = mutableListOf(
            executable.toString(),
            "--headless=new",
            "--disable-gpu",
            "--disable-extensions",
            "--disable-background-networking",
            "--disable-sync",
            "--disable-default-apps",
            "--disable-component-update",
            "--no-first-run",
            "--no-default-browser-check",
            "--mute-audio",
            "--hide-scrollbars",
            "--window-size=1280,720",
            // 端口交给浏览器挑：写死 9222 会和用户自己开着的调试端口撞车
            "--remote-debugging-port=0",
            "--user-data-dir=$profile",
            "about:blank",
        )
        proxyProvider()?.let { p ->
            val address = p.address() as? InetSocketAddress
            if (address != null) args.add(1, "--proxy-server=http://${address.hostString}:${address.port}")
        }
        return ProcessBuilder(args)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    /** `DevToolsActivePort` 第一行是端口，第二行是浏览器级 ws 路径 */
    private fun awaitDebugPort(profile: Path, timeoutMs: Long, isAlive: () -> Boolean): Int? {
        val file = profile.resolve("DevToolsActivePort")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // 先看进程：用户点了取消（进程已被杀）时不该继续睡满整个超时
            if (!isAlive()) return null
            if (file.toFile().isFile) {
                val port = runCatching { file.toFile().readLines().firstOrNull()?.trim()?.toInt() }.getOrNull()
                if (port != null && port in 1..65535) return port
            }
            Thread.sleep(80)
        }
        return null
    }

    /** 轮询 `/json/list` 找页面目标的调试地址 */
    private fun awaitPageTarget(port: Int, timeoutMs: Long, isAlive: () -> Boolean): String? {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(2_000))
            .proxy(ProxySelector.of(null))
            .build()
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/json/list"))
            .timeout(Duration.ofMillis(2_000))
            .GET()
            .build()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive()) return null
            val body = runCatching {
                client.send(request, HttpResponse.BodyHandlers.ofString()).body()
            }.getOrNull()
            if (body != null) {
                val targets = runCatching { MiniJson.parse(body).asArray() }.getOrNull()
                val page = targets?.asSequence()
                    ?.mapNotNull { it.asObject() }
                    ?.firstOrNull { it.str("type") == "page" && it.str("webSocketDebuggerUrl") != null }
                page?.str("webSocketDebuggerUrl")?.let { return it }
            }
            Thread.sleep(120)
        }
        return null
    }

    // ------------------------------------------------------------------ 解析

    private fun toCandidate(message: Map<String, Any?>, pageUrl: String): MediaCandidate? {
        val params = message.obj("params") ?: return null
        val response = params.obj("response") ?: return null
        val url = response.str("url") ?: return null
        if (!url.startsWith("http", ignoreCase = true)) return null
        val status = response.num("status")?.toInt() ?: 0
        // 只看成功响应：302 的 Location 会在后续请求里以 200 出现，重复计一次没有意义
        if (status !in 200..299) return null

        val mime = response.str("mimeType")?.lowercase()
        val resourceType = params.str("type")
        val kind = when {
            mime != null && (mime.contains("mpegurl") || mime.contains("x-mpegurl")) -> MediaKind.M3u8
            else -> MediaKind.fromUrl(url)
                ?: if (resourceType.equals("Media", ignoreCase = true)) MediaKind.Other else return null
        }
        return MediaCandidate(
            url = url,
            kind = kind,
            // 名称留空：由 UI 兜底成「媒体 N」。从 CDN 地址末段取名只会得到 `index`、`playlist`
            name = "",
            source = MediaCandidate.Source.Network,
            referer = pageUrl,
        )
    }

    /** `Network.getAllCookies` → `host -> "a=b; c=d"`，按候选地址取用时做后缀匹配 */
    private fun collectCookies(session: CdpSession): Map<String, String> {
        val response = session.command("Network.getAllCookies", timeoutMs = 3_000) ?: return emptyMap()
        val cookies = response.obj("result")?.arr("cookies") ?: return emptyMap()
        val byDomain = HashMap<String, StringBuilder>()
        for (item in cookies) {
            val cookie = item.asObject() ?: continue
            val name = cookie.str("name") ?: continue
            // Cookie 名是外部页面可影响的输入：非法字符没法百分号编码（编了服务端就认不出），
            // 只能整条丢弃，避免拼出畸形 / 可注入的 Cookie 头（P2-E1）
            if (!isHttpToken(name)) continue
            val value = cookie.str("value") ?: continue
            val domain = cookie.str("domain")?.trim()?.lowercase()?.trimStart('.') ?: continue
            val builder = byDomain.getOrPut(domain) { StringBuilder() }
            if (builder.isNotEmpty()) builder.append("; ")
            // CDP 回传的 Cookie 值可能含中文 / emoji，OkHttp 与 HttpURLConnection 都会
            // 在构造请求头时拒绝；入站就按 UTF-8 百分号编码，ASCII Cookie 原样保留。
            builder.append(name).append('=').append(sanitizeHttpHeaderValue(value))
        }
        return byDomain.mapValues { it.value.toString() }
    }

    private fun deleteQuietly(dir: Path) {
        // 与应用内其它递归删除共用同一道危险路径闸门（见 utils/SafeDelete.kt）：
        // 这里删的是本次刚 createTempDirectory 出来的 profile，正常情况下永远能过闸。
        SafeDelete.deleteTree(dir.toFile(), "浏览器临时配置目录")
    }
}

/**
 * 一次抓取的诊断指标（v8-P1-1）。
 *
 * 只回答「过程中发生了什么」：收到多少响应、多少被判成非媒体、耗时多久、用的哪个浏览器。
 * 「为什么失败」仍由 [BrowserCaptureFailure] 回答，两者一起看才够定位。
 */
data class CaptureStats(
    /** 收到的 `Network.responseReceived` 总数（含页面、脚本、图片等所有资源） */
    val requestsSeen: Int = 0,
    /** 其中被判成媒体候选的条数（去重后） */
    val candidatesFound: Int = 0,
    /** 收到了响应但没有被判成媒体候选的条数（非 2xx、非 http、非媒体资源都算） */
    val filteredNonMedia: Int = 0,
    val elapsedMs: Long = 0L,
    val browser: BrowserInfo? = null,
)

/** 一次深度抓取的结果 */
data class BrowserCaptureResult(
    val candidates: List<MediaCandidate> = emptyList(),
    /** 域名（去掉前导点）→ `name=value; ...`，下载时按目标地址取用 */
    val cookies: Map<String, String> = emptyMap(),
    val failure: BrowserCaptureFailure? = null,
    val detail: String? = null,
    val stats: CaptureStats = CaptureStats(),
) {
    /**
     * 取某个媒体地址该带的 Cookie。
     *
     * 按域名**后缀**匹配：Cookie 的 domain 是 `.example.com` 时，`cdn.example.com` 也该带上。
     */
    fun cookieFor(url: String): String? {
        if (cookies.isEmpty()) return null
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return null
        return cookies.entries
            .filter { (domain, _) -> host == domain || host.endsWith(".$domain") }
            // 域名越长越精确，优先用它
            .maxByOrNull { it.key.length }
            ?.value
    }
}

/** 深度抓取为什么没成 —— 每种给不同提示，不要统一成「抓取失败」 */
enum class BrowserCaptureFailure {

    /** 机器上没有 Chrome / Edge / Chromium */
    NoBrowser,

    /** 进程起不来（权限、损坏的安装、被安全软件拦截） */
    LaunchFailed,

    /** 起来了但没写调试端口（大概率是老版本内核不支持 `--remote-debugging-port=0`） */
    NoDebugPort,

    /** 有端口但没有页面目标 */
    NoTarget,

    /** 导航被拒（证书错误、被拦截） */
    NavigateFailed,

    Cancelled,
}

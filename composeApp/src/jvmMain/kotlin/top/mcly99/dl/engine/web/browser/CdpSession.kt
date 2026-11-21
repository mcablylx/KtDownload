package top.mcly99.dl.engine.web.browser

import top.mcly99.dl.utils.AppLogger
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 极简 Chrome DevTools Protocol 客户端（WebSocket + JSON）。
 *
 * 只用两条链路：发命令（`id` + `method` + `params`）、收消息（事件或命令回执）。
 * 之所以不用现成库：`java.net.http.WebSocket` 从 JDK 11 起就有，加一个依赖只为包一层
 * WebSocket 不划算（v6-P1-3）。
 *
 * **线程模型**：JDK 的 WebSocket 回调在它自己的线程上跑，消息统一丢进阻塞队列；
 * 调用方在任意线程 `poll` / `command`。命令与事件混在同一个队列里，`command` 遇到
 * 不匹配的消息会先放进 `deferred`，`nextEvent` 优先取 `deferred`，保证事件不丢。
 *
 * `deferred` 用 `ConcurrentLinkedDeque` 而不是 `ArrayDeque`：类注释承诺「调用方在任意线程」，
 * 那就得真的线程安全 —— 目前只有 `BrowserCapture` 一个单线程调用方，但这个假设不该藏在注释里（v7-P3-3）。
 */
internal class CdpSession(
    private val wsUrl: String,
    private val connectTimeoutMs: Long = 5_000,
) : AutoCloseable {

    private val queue = LinkedBlockingQueue<Map<String, Any?>>()
    private val deferred = ConcurrentLinkedDeque<Map<String, Any?>>()
    private val nextId = AtomicInteger(1)
    private val failure = AtomicReference<String?>(null)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
        // 连本机调试端口不能走系统代理：设置页的代理只用于下载与抓页面
        .proxy(ProxySelector.of(null))
        .build()

    private var socket: WebSocket? = null

    fun connect() {
        val listener = TextListener()
        socket = http.newWebSocketBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .buildAsync(URI(wsUrl), listener)
            .get(connectTimeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * 发一条命令并等它的回执。
     *
     * @return 完整消息（含 `result` / `error`）；超时返回 null，不抛异常 ——
     *   抓取是「能抓多少算多少」，一条命令超时不该让整次抓取失败
     */
    fun command(method: String, params: Map<String, Any?> = emptyMap(), timeoutMs: Long = 5_000): Map<String, Any?>? {
        val ws = socket ?: return null
        val id = nextId.getAndIncrement()
        val payload = MiniJson.stringify(
            buildMap {
                put("id", id)
                put("method", method)
                put("params", params)
            }
        )
        runCatching { ws.sendText(payload, true).join() }.onFailure {
            AppLogger.debug("CdpSession", "发送 $method 失败: ${it.message}")
            return null
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            val message = queue.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
            if (message.int("id") == id) return message
            deferred.addLast(message)
        }
    }

    /** 取下一条事件；[timeoutMs] 内没有就返回 null（调用方据此判断是否到截止时间） */
    fun nextEvent(timeoutMs: Long): Map<String, Any?>? {
        deferred.pollFirst()?.let { return it }
        return queue.poll(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
    }

    override fun close() {
        runCatching { socket?.sendClose(WebSocket.NORMAL_CLOSURE, "bye")?.join() }
        runCatching { socket?.abort() }
        socket = null
    }

    /** 连接层错误（对端关闭、握手失败）的原因；UI 需要区分「没浏览器」和「连不上」 */
    fun lastError(): String? = failure.get()

    private inner class TextListener : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val text = buffer.toString()
                buffer.setLength(0)
                val parsed = runCatching { MiniJson.parse(text) }.getOrNull()
                parsed.asObject()?.let { queue.offer(it) }
            }
            webSocket.request(1)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            failure.set(error.message ?: error.javaClass.simpleName)
            AppLogger.debug("CdpSession", "WebSocket 错误: ${error.message}")
        }
    }
}

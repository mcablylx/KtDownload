/*
 * Adapted from YunX-Desktop — https://github.com/tidain/YunX-Desktop
 * Upstream: main branch @ 00a030f7 (tag v1.3.4, 2026-11-13).
 * Copyright (C) the YunX-Desktop contributors.
 * License: GNU Affero General Public License v3.0 (AGPL-3.0).
 * Original: data/network/GitHubMirror.kt
 * Downstream changes (N-07):
 *   - SettingsRepository (upstream prefs facade) replaced by ktdown's AppContext.miscPrefs shim;
 *   - P2 (2026-11-15): default is AUTO, not DIRECT. Upstream proxied every GitHub download
 *     through a third-party gh-proxy host; ktdown only uses a mirror AFTER a successful
 *     throughput probe. Probing not started / in progress / all failed => GitHub direct.
 *     An explicit "direct" choice is persisted and always wins. Only public download URLs
 *     are mirrored; api.github.com calls are never routed through a third party.
 *   - added setEnabled/setPrefix/clearPrefix + probes() for the downstream settings row;
 *   - added an injectable prober + applyProbeResults() so jvmTest can cover the fallback offline;
 *   - Log shimmed to top.mcly99.dl.utils.Log, User-Agent/tag renamed to MclyDownload.
 */
package top.mcly99.dl.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import top.mcly99.dl.data.AppContext
import top.mcly99.dl.data.ShimPrefs
import top.mcly99.dl.utils.Log
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * GitHub 下载镜像统一入口。
 *
 * 背景：同为 gh-proxy 系的五个镜像实测差异极大（快的 9MB/s、慢的 60KB/s），
 * **延迟最小 ≠ 吞吐最大**（axisnow 64ms 却只有 v4 的 1/8），所以自动选择按「实测下载速度」定序，
 * 而不是纯 ping；ping 只作为展示给用户的参考值。
 *
 * 生效规则（[activePrefix]）：
 * 1. 用户显式选「直连」→ 返回空串，且这个选择会被持久化；
 * 2. 启用后若固定了前缀（预选或自定义）→ 用它；
 * 3. 否则用最近一次**实测成功**的最快镜像；
 * 4. 从未测速 / 正在测速 / 全部镜像测速失败 → 空串（GitHub 直连）。
 *
 * P2 的关键取舍：默认改成 AUTO 会让国内网络开箱即用，但**绝不把未经可用性验证的
 * 第三方地址塞进下载链接**。测速全挂、或服务中途不可用，都会退回直连而不是硬走镜像。
 *
 * 应用内所有走 GitHub 的下载都从这里取前缀，保证「设置里选的镜像」全局一致生效；
 * `api.github.com` 的仓库/Release 元数据请求不经镜像。
 */
object GitHubMirror {

    private const val TAG = "MclyDownload-Mirror"

    private const val KEY_ENABLED = "github_mirror_enabled"
    private const val KEY_PREFIX = "github_mirror_prefix"
    private const val KEY_FASTEST = "github_mirror_fastest"
    private const val KEY_PROBED_AT = "github_mirror_probed_at"

    /** 内置预选镜像前缀池（顺序即设置页展示顺序） */
    val PRESETS: List<String> = listOf(
        "https://v4.gh-proxy.org/",
        "https://v6.gh-proxy.org/",
        "https://gh-proxy.org/",
        "https://cdn.gh-proxy.org/",
        "https://axisnow.gh-proxy.org/"
    )

    /**
     * 测速样本：GyanD/codexffmpeg 的固定版本 Release 资产（约 88MB，支持 Range）。
     * 只需拉它的一个小分片即可量出延迟与吞吐。
     */
    private const val PROBE_URL =
        "https://github.com/GyanD/codexffmpeg/releases/download/7.1/ffmpeg-7.1-essentials_build.zip"

    private const val PROBE_UA = "MclyDownload"

    /** 测速分片大小（256KB）：太小测不准，太大拖慢刷新 */
    private const val PROBE_BYTES = 256L * 1024

    /** 单个镜像的测速硬超时：慢镜像不能拖死整体刷新 */
    private const val PROBE_TIMEOUT_MS = 8_000L

    /** 自动测速结果的有效期：过期后下次启动重新测一次 */
    private const val TTL_MS = 6L * 60 * 60 * 1000

    /**
     * 测速全失败后的重试间隔（P2）：此时正回落直连，网络恢复后应更快重新尝试镜像，
     * 不能让用户等满 6 小时。
     */
    private const val FAILED_TTL_MS = 10L * 60 * 1000

    /**
     * 单个镜像的测速结果。
     * @param pingMs TCP 建连耗时（毫秒）；null = 建连阶段就失败
     * @param speedBps 实测下载速度（字节/秒）；null = 未取到数据（失败 / 超时 / 不支持 Range）
     */
    data class Probe(val prefix: String, val pingMs: Long?, val speedBps: Long?) {
        /** 是否可用（取到了数据） */
        val ok: Boolean get() = speedBps != null
    }

    /** 测速快照 */
    data class Snapshot(
        /** 是否正在测速 */
        val testing: Boolean = false,
        /** 各预选镜像的测速结果（尚未测过时为空） */
        val probes: List<Probe> = emptyList()
    )

    private val _snapshot = MutableStateFlow(Snapshot())

    /** 供设置页实时展示的测速状态/结果 */
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 同一时刻只允许一轮测速在跑 */
    private val refreshing = AtomicBoolean(false)

    /**
     * 测速专用客户端：**禁用空闲连接复用**（maxIdle=0），
     * 否则复用连接时 OkHttp 不触发 connectStart/connectEnd，拿不到本次的 TCP 建连耗时。
     */
    private val baseProbeClient: OkHttpClient by lazy {
        HttpClients.downloadClient().newBuilder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .build()
    }

    /**
     * 下游新增：可替换的偏好存储。默认指向用户的 ~/.mclydownload；
     * jvmTest 注入临时目录，避免测试读写真实用户配置。
     */
    @Volatile
    var prefs: ShimPrefs = AppContext.miscPrefs

    /**
     * 下游新增（P2）：可替换的单镜像探测器。生产走 [probe] 真实测速；jvmTest 注入
     * 纯函数结果，零网络就能覆盖「测速成功 / 全部失败 / 部分失败」三条回落路径。
     */
    @Volatile
    var prober: (String) -> Probe = ::probe

    /** 下游新增（P2）：可替换时钟，jvmTest 用它验证 6h / 10min 两套 TTL，不发真实网络请求。 */
    @Volatile
    var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * 下游新增：镜像开关，**默认开启（AUTO）**。
     *
     * 旧版本用户如果显式选过「直连」，配置里存着 `false`，这里会原样尊重；只有从没
     * 设置过的新用户才走默认 true。默认开启不等于无条件走第三方：[activePrefix] 要求
     * 镜像必须先通过实测，UI 也会显示当前实际生效的是镜像还是直连。
     */
    fun isEnabled(): Boolean = prefs.get(KEY_ENABLED, "true") == "true"

    fun setEnabled(enabled: Boolean) {
        prefs.put(KEY_ENABLED, enabled.toString())
    }

    /** 下游新增：固定前缀；传 null/空串表示「跟随自动测速结果」。 */
    fun setPrefix(prefix: String?) {
        val value = prefix?.trim().orEmpty()
        if (value.isEmpty()) {
            prefs.put(KEY_PREFIX, "")
        } else {
            prefs.put(KEY_PREFIX, value)
        }
    }

    /** 下游新增：当前固定前缀（空串表示未固定）。 */
    fun fixedPrefix(): String = prefs.get(KEY_PREFIX, "").orEmpty()

    /** 下游新增：供设置行展示测速结果。 */
    fun probes(): List<Probe> = snapshot.value.probes

    /** 当前最快的预选镜像：优先用本次测速结果，其次用上次持久化的结果 */
    fun fastestPrefix(): String? =
        snapshot.value.probes.mapNotNull { p -> p.speedBps?.let { bps -> p to bps } }
            .maxByOrNull { it.second }?.first?.prefix
            ?: prefs.get(KEY_FASTEST, null)?.takeIf { it.isNotBlank() }

    /**
     * 当前生效的镜像前缀；空串 = 直连（见类注释的生效规则）。
     *
     * P2：去掉了「没测速结果就回落 `PRESETS.first()`」这条旧规则。上游这么做会让一个
     * 从未验证过的第三方地址直接进入用户下载链接；这里改成只有实测成功的镜像才生效。
     */
    fun activePrefix(): String {
        if (!isEnabled()) return ""
        fixedPrefix().takeIf { it.isNotBlank() }?.let { return it }
        return fastestPrefix().orEmpty()
    }

    /**
     * 启动时调用：AUTO 模式且测速结果过期（或从未测过）时异步刷新一次。
     * 显式直连、或用户已固定某个镜像时不做无谓的对外探测。
     */
    fun ensureFresh() {
        if (shouldRefresh()) refresh()
    }

    /**
     * 下游新增（P2）：把「要不要测速」做成同步纯判定，jvmTest 不用等异步 scope
     * 就能覆盖两套 TTL、显式直连、固定前缀三种跳过条件。
     */
    internal fun shouldRefresh(): Boolean {
        if (!isEnabled() || fixedPrefix().isNotBlank()) return false
        val age = clock() - probedAt()
        // 上一轮全失败时正在回落直连，用短 TTL 让网络恢复后尽快重试
        val ttl = if (lastProbeFailed()) FAILED_TTL_MS else TTL_MS
        return age > ttl
    }

    /** 下游新增（P2）：仅供 jvmTest 清空进程内测速快照，避免用例之间串扰。 */
    internal fun resetForTest() {
        _snapshot.value = Snapshot()
        refreshing.set(false)
    }

    /** 上一次测速是否一个可用镜像都没测出来（P2：决定用短 TTL 还是 6 小时 TTL） */
    fun lastProbeFailed(): Boolean =
        probedAt() > 0L && prefs.get(KEY_FASTEST, "").isNullOrBlank()

    private fun probedAt(): Long =
        prefs.get(KEY_PROBED_AT, "0")?.toLongOrNull() ?: 0L

    /**
     * 触发一次全量测速（并发探测 [PRESETS]），结果写入 [snapshot]，并把最快的一个持久化。
     * 重复调用会被忽略（已在测速中直接返回）。
     */
    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        _snapshot.update { it.copy(testing = true) }
        scope.launch {
            try {
                val results = coroutineScope {
                    PRESETS.map { prefix -> async { prober(prefix) } }.awaitAll()
                }
                applyProbeResults(results)
            } catch (t: Throwable) {
                Log.w(TAG, "镜像测速失败：${t.message}")
                _snapshot.update { it.copy(testing = false) }
            } finally {
                refreshing.set(false)
            }
        }
    }

    /**
     * 下游新增（P2）：同步落库一轮测速结果。
     *
     * 从 [refresh] 的协程里抽出来，jvmTest 注入 [prober] 后可以直接调用它，不必等待
     * 异步 scope 就能断言「全失败 → 直连」「部分成功 → 选最快」「失败用短 TTL」。
     * 全部失败时 `KEY_FASTEST` 写成空串，[activePrefix] 因此返回空串（直连）。
     */
    internal fun applyProbeResults(results: List<Probe>) {
        _snapshot.update { Snapshot(testing = false, probes = results) }
        val fastest = results.mapNotNull { p -> p.speedBps?.let { bps -> p to bps } }
            .maxByOrNull { it.second }?.first
        prefs.put(KEY_FASTEST, fastest?.prefix.orEmpty())
        prefs.put(KEY_PROBED_AT, clock().toString())
        val summary = results.joinToString {
            "${it.prefix} ping=${it.pingMs?.let { ms -> "${ms}ms" } ?: "-"} " +
                "速度=${it.speedBps?.let { bps -> "${bps / 1024}KB/s" } ?: "失败"}"
        }
        Log.i(TAG, "镜像测速：$summary" + if (fastest == null) "；全部失败，回落 GitHub 直连" else "")
    }

    /**
     * 探测单个镜像：
     * - ping = OkHttp EventListener 捕获的 TCP 建连耗时（connectStart → connectEnd）；
     * - 速度 = 拉 [PROBE_BYTES] 分片，用「读到数据的耗时」算字节速率（不含建连，纯吞吐）。
     */
    private fun probe(prefix: String): Probe {
        val connectStartNs = AtomicLong(0L)
        val pingMs = AtomicLong(-1L)
        val client = baseProbeClient.newBuilder()
            .eventListener(object : EventListener() {
                override fun connectStart(
                    call: Call,
                    inetSocketAddress: InetSocketAddress,
                    proxy: Proxy
                ) {
                    connectStartNs.set(System.nanoTime())
                }

                override fun connectEnd(
                    call: Call,
                    inetSocketAddress: InetSocketAddress,
                    proxy: Proxy,
                    protocol: Protocol?
                ) {
                    val started = connectStartNs.get()
                    if (started > 0L) pingMs.set((System.nanoTime() - started) / 1_000_000)
                }
            })
            .build()

        val ping = { pingMs.get().takeIf { it >= 0L } }

        val call = client.newCall(
            Request.Builder()
                .url(prefix + PROBE_URL)
                .header("User-Agent", PROBE_UA)
                .header("Range", "bytes=0-${PROBE_BYTES - 1}")
                .build()
        )
        // 慢镜像不能拖死整体：单次调用硬超时覆盖连接 + 读取全过程
        call.timeout().timeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return try {
            call.execute().use { resp ->
                val headersAt = System.nanoTime()
                val body = resp.body
                if (resp.code != 206 || body == null) {
                    Probe(prefix, ping(), null)
                } else {
                    var read = 0L
                    body.byteStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (read < PROBE_BYTES) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            read += n
                        }
                    }
                    val elapsedMs = (System.nanoTime() - headersAt) / 1_000_000
                    val speed = if (read > 0L && elapsedMs > 0L) read * 1000L / elapsedMs else null
                    Probe(prefix, ping(), speed)
                }
            }
        } catch (t: Throwable) {
            // 建连可能已成功（有 ping），只是读取失败 / 超时
            Probe(prefix, ping(), null)
        }
    }
}

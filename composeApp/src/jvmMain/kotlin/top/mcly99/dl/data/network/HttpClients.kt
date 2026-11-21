/*
 * Ported from YunX-Desktop — https://github.com/tidain/YunX-Desktop
 * Upstream: main branch @ 00a030f7 (tag v1.3.4, 2026-11-13).
 * Copyright (C) the YunX-Desktop contributors.
 * License: GNU Affero General Public License v3.0 (AGPL-3.0).
 * Full text: third-party/yunx-desktop/LICENSE.txt and THIRD-PARTY-NOTICES.md.
 * This file is a derivative work: namespace isolated, app services shimmed.
 */
package top.mcly99.dl.data.network

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 全局 HTTP 客户端管理：
 * - [apiClient]：平台 API（登录/解析/直链）、HLS 下载、更新检查共用，超时宽松；
 * - [downloadClient]：分片下载专用，大 Dispatcher 保障分片并发（默认实例 maxRequestsPerHost=5 会锁死并发）。
 * 所有构建都使用系统证书链和 OkHttp 主机名校验，不提供进程内绕过开关。
 * 所有客户端共用同一 HTTP 协议开关（默认仅 HTTP/1.1）。
 *
 * P1 装配：代理与 HTTP/2 都由设置层注入；代理用 lambda 现取，
 * 「跟随系统」模式下系统代理变化会触发客户端重建（比对的是地址指纹，不是 Proxy.equals，避免 DNS 解析）。
 */
object HttpClients {

    /** 下载客户端排队 Call 上限：分片下载走同步 call.execute()，真实并发受 DownloadManager 的在飞上限约束 */
    private const val MAX_QUEUED_CALLS = 64
    private const val DIRECT_KEY = "DIRECT"

    private val lock = Any()

    @Volatile
    private var apiCache: OkHttpClient? = null

    @Volatile
    private var downloadCache: OkHttpClient? = null

    /** 当前生效的代理来源；lambda 现取，装配方传入「按设置解析」的闭包 */
    @Volatile
    private var proxyProvider: () -> Proxy? = { null }

    /** 缓存构建时所用代理的指纹；与当前解析结果不一致时重建客户端 */
    @Volatile
    private var apiProxyKey: String = DIRECT_KEY

    @Volatile
    private var downloadProxyKey: String = DIRECT_KEY

    /** 是否启用 HTTP/2；false（默认）时所有客户端仅使用 HTTP/1.1 */
    @Volatile
    private var http2Enabled: Boolean = false

    /**
     * 配置全局 HTTP 代理。传 null 表示直连。
     * 传入的 [Proxy] 原样保留（含 SOCKS 类型），调用后客户端缓存失效，下次获取时按新代理重建。
     */
    fun setProxy(proxy: Proxy?) = setProxyProvider { proxy }

    /**
     * 兼容旧调用：host 为 null / 空白或 port 不在 1-65535 时视为直连。
     * 只构造 HTTP 类型代理；SOCKS 请改用 [setProxy]。
     */
    fun setProxy(host: String?, port: Int) {
        val configured = host?.trim()?.takeIf { it.isNotEmpty() && port in 1..65535 }?.let {
            runCatching { Proxy(Proxy.Type.HTTP, InetSocketAddress(it, port)) }.getOrNull()
        }
        setProxy(configured)
    }

    /**
     * 装配「现取」的代理来源。设置页切换代理模式、或系统代理在 30 秒缓存到期后变化时，
     * [apiClient] / [downloadClient] 会比对指纹并重建，无需重启应用。
     */
    fun setProxyProvider(provider: () -> Proxy?) {
        synchronized(lock) {
            proxyProvider = provider
            invalidateClients()
        }
    }

    /**
     * 配置是否启用 HTTP/2。默认关闭：所有客户端仅协商 HTTP/1.1；
     * 开启后允许 ALPN 协商到 h2（理论上更快，实测差异通常不大）。
     * 调用后清空已构建的客户端缓存，使下次获取时按新协议重建，立即生效。
     */
    fun setHttp2Enabled(enabled: Boolean) {
        synchronized(lock) {
            http2Enabled = enabled
            invalidateClients()
        }
    }

    private fun invalidateClients() {
        apiCache = null
        downloadCache = null
        apiProxyKey = DIRECT_KEY
        downloadProxyKey = DIRECT_KEY
    }

    /** 当前应当生效的代理；探测逻辑抛异常时按直连处理，不影响下载主流程 */
    private fun currentProxy(): Proxy? = runCatching { proxyProvider() }.getOrNull()

    /** 字符串指纹，避免 Proxy.equals 比较 InetSocketAddress 时触发 DNS 解析 */
    private fun proxyKey(proxy: Proxy?): String {
        if (proxy == null) return DIRECT_KEY
        val address = proxy.address() as? InetSocketAddress ?: return DIRECT_KEY
        return "${proxy.type()}|${address.hostString}|${address.port}"
    }

    /** 按开关决定 ALPN 协商协议：关闭时仅 HTTP/1.1，开启时允许 h2 */
    private fun currentProtocols(): List<Protocol> =
        if (http2Enabled) listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        else listOf(Protocol.HTTP_1_1)

    /** 普通 API 客户端（各平台 API、HLS、更新检查） */
    fun apiClient(): OkHttpClient {
        val configured = currentProxy()
        val key = proxyKey(configured)
        apiCache?.let { if (apiProxyKey == key) return it }
        synchronized(lock) {
            apiCache?.let { if (apiProxyKey == key) return it }
            return buildApi(configured).also {
                apiCache = it
                apiProxyKey = key
            }
        }
    }

    /** 下载专用客户端：大 Dispatcher + 长超时，不锁死分片并发 */
    fun downloadClient(): OkHttpClient {
        val configured = currentProxy()
        val key = proxyKey(configured)
        downloadCache?.let { if (downloadProxyKey == key) return it }
        synchronized(lock) {
            downloadCache?.let { if (downloadProxyKey == key) return it }
            return buildDownload(configured).also {
                downloadCache = it
                downloadProxyKey = key
            }
        }
    }

    private fun buildApi(configuredProxy: Proxy?): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .protocols(currentProtocols())
            // 诊断模式：记方法/脱敏 URL/状态码/耗时，非 200/206 才带 body 摘要（关着时首行短路，零开销）
            .addInterceptor(DiagnosticNetworkInterceptor())
            .apply {
                // 配置了有效代理时注入；否则保持默认直连，行为与改动前完全一致
                configuredProxy?.let { proxy(it) }
            }
            .build()
    }

    private fun buildDownload(configuredProxy: Proxy?): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            // 排队 Call 上限：分片下载走同步 call.execute()，根本不经过这个队列 —— 真实并发由
            // DownloadManager 的 inflightLimiter 与 chunkIoDispatcher 决定
            maxRequests = MAX_QUEUED_CALLS
            maxRequestsPerHost = MAX_QUEUED_CALLS
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            // 空闲连接池收紧：默认 128 条 × 5 分钟会常驻 socket，下载是突发式，1 分钟足够复用
            .connectionPool(
                ConnectionPool(
                    maxIdleConnections = 8,
                    keepAliveDuration = 1,
                    timeUnit = TimeUnit.MINUTES
                )
            )
            .protocols(currentProtocols())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // 诊断模式：分片请求也记一行（高频，靠 DiagnosticLog 的每秒行数闸门限流）
            .addInterceptor(DiagnosticNetworkInterceptor())
            .apply {
                // 配置了有效代理时注入；否则保持默认直连，行为与改动前完全一致
                configuredProxy?.let { proxy(it) }
            }
            .build()
    }
}

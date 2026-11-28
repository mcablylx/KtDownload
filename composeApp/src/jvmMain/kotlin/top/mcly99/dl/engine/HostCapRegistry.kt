package top.mcly99.dl.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * 每主机连接上限学习（E 项）。
 *
 * 问题：国内网盘直链普遍限制单 IP 并发（常见 2~4 条）。用户把线程数设成 8，
 * 6 条连接被 403/429/503 拒掉，每片各失败一次后整任务报错 —— 用户看到的是
 * 「这个网盘下不了」，其实是「开太多连接了」。
 *
 * 做什么：把「被拒时正有多少条连接在工作」记下来，攒够 [observationsRequired] 次后
 * 作为该主机的上限，重规划 worker 数，让多余连接**不去白试**（对方注释：
 * wasted time, risk of being banned）。
 *
 * 三条判定纪律：
 * 1. **只在 ≥2 条连接同时在工作时才算并发信号**。单连接也失败说明是鉴权/过期/风控，
 *    和并发无关；这种 host 一旦出现单连接失败就永久退出学习（[noteSingleConnectionFailure]），
 *    否则会把「直链过期」误判成「只能开 1 条」，任务越跑越慢还查不出原因。
 * 2. **攒 2 次才生效**。一次瞬时 503 不足以证明是并发限制。
 * 3. **取观测中的最小值**。上限是「能同时工作的条数」，被拒发生在 K+1、K+2… 上；
 *    取最大值永远收敛不了（每轮都按最大的那个开，继续被拒）。两次观测是对
 *    「取最小值容易被一次偶发拉低」的补偿。
 *
 * 遗忘：10 分钟没有新的拒绝就作废。CDN 的限流策略会变（换节点、限流解除），
 * 一条陈旧的上限会让用户永远只能跑 2 条连接。
 */
internal class HostCapRegistry(
    private val now: () -> Long = System::currentTimeMillis,
    private val observationsRequired: Int = 2,
    private val forgetAfterMs: Long = 10 * 60 * 1000L,
) {
    private class Entry {
        val observations = ArrayList<Int>()
        var updatedAt: Long = 0L
        var cap: Int? = null
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /** 该主机在**单连接**情况下也失败过：说明不是并发限制，停止学习 */
    private val notConcurrency = ConcurrentHashMap.newKeySet<String>()

    /**
     * 记一次「疑似并发拒绝」。
     *
     * @param host 主机名（含端口，避免把同一 IP 的不同服务混为一谈）
     * @param observedConnections 被拒时能同时工作的连接数（≥1）
     * @return 生效后的上限；还没攒够观测或该主机已判定为非并发问题时返回 null
     */
    fun observeRejection(host: String, observedConnections: Int): Int? {
        if (host.isBlank()) return null
        if (observedConnections < 1) return null
        if (host in notConcurrency) return null

        val at = now()
        val entry = entries.computeIfAbsent(host) { Entry() }
        synchronized(entry) {
            if (at - entry.updatedAt > forgetAfterMs) {
                entry.observations.clear()
                entry.cap = null
            }
            entry.updatedAt = at
            entry.observations.add(observedConnections)
            if (entry.observations.size >= observationsRequired) {
                // 上限取观测最小值：能同时工作的条数不会超过任何一次「被拒时的并发数」
                entry.cap = entry.observations.min()
            }
            return entry.cap
        }
    }

    /** 单连接就失败过：该主机的问题与并发无关，之后不再学习 */
    fun noteSingleConnectionFailure(host: String) {
        if (host.isNotBlank()) notConcurrency.add(host)
    }

    /** 当前生效的上限；没有学习结果（或已过期）时返回 null */
    fun capFor(host: String): Int? {
        if (host.isBlank() || host in notConcurrency) return null
        val entry = entries[host] ?: return null
        synchronized(entry) {
            if (now() - entry.updatedAt > forgetAfterMs) {
                entries.remove(host)
                return null
            }
            return entry.cap
        }
    }

    /** 该主机是否已被判定为「与并发无关」 */
    fun isNotConcurrency(host: String): Boolean = host in notConcurrency

    fun forget(host: String) {
        entries.remove(host)
    }

    fun clear() {
        entries.clear()
        notConcurrency.clear()
    }
}

/** 从 URL 取「主机:端口」作为学习键；解析失败返回空串（不参与学习） */
internal fun hostKeyOf(url: String): String = runCatching {
    val uri = java.net.URI(url)
    val host = uri.host ?: return ""
    val port = if (uri.port > 0) ":${uri.port}" else ""
    "$host$port"
}.getOrDefault("")

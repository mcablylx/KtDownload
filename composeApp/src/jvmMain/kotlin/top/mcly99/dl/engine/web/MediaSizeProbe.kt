package top.mcly99.dl.engine.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger

/**
 * 一条待探测的媒体地址。
 *
 * @property url 媒体地址
 * @property referer 发现它的页面地址 —— 防盗链站点不带 Referer 会直接 403，探不到大小
 */
data class SizeProbeTarget(val url: String, val referer: String? = null)

/**
 * 并发探测抓取结果的文件大小。
 *
 * 设计上刻意保守：
 * - **默认关闭**（`AppSettings.probeMediaSize`），因为一个页面可能有几十条结果，
 *   每条一次请求，慢的时候要十几秒，还会被 CDN 当成扫描；
 * - 并发数固定为 [AppConstants.SIZE_PROBE_CONCURRENCY]，按批推进而不是一次全发；
 * - 任何一条失败都只是「这条没有大小」，**绝不影响列表本身**。
 */
open class MediaSizeProbe(
    private val probe: HttpProbe = HttpProbe(),
) {

    /**
     * @param targets 待探测的地址
     * @param concurrency 同时进行的请求数；调用方若已分批（为了「随探随到」），
     *   传 `targets.size` 即可，内部不会再切一次
     * @return url → 字节数；探不到的地址不会出现在结果里
     */
    open suspend fun probe(
        targets: List<SizeProbeTarget>,
        concurrency: Int = AppConstants.SIZE_PROBE_CONCURRENCY,
    ): Map<String, Long> {
        if (targets.isEmpty()) return emptyMap()
        val batchSize = concurrency.coerceAtLeast(1)
        return withContext(Dispatchers.IO) {
            targets
                .distinctBy { it.url }
                // 分批发：一次全部 async 出去会瞬间打满连接数，反而更容易被限流
                .chunked(batchSize)
                .flatMap { batch ->
                    batch.map { target ->
                        async { target.url to probe.contentLength(target.url, target.referer) }
                    }.awaitAll()
                }
                .mapNotNull { (url, size) -> size?.let { url to it } }
                .toMap()
                .also { AppLogger.debug("MediaSizeProbe", "探测 ${targets.size} 条，命中 ${it.size} 条") }
        }
    }
}

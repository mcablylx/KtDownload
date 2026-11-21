package top.mcly99.dl.engine.player

import top.mcly99.dl.engine.hls.HlsPlaylist
import top.mcly99.dl.engine.hls.M3u8Parser
import java.util.Locale

/**
 * 一个清晰度档位（播放页下拉里的一行）。
 *
 * @property url 子播放列表地址；选中后直接拿它重新 `play()`
 * @property label 主标签：`1080p`，没有分辨率信息时退化成码率 `4.5 Mbps`
 * @property detail 副标签：`4.5 Mbps · 中文`（码率与独立音轨名，可能为 null）
 * @property height 分辨率高度，用于排序与去重；0 表示列表没声明
 * @property bandwidth 峰值带宽（bit/s）
 * @property audioUrl 独立音轨地址（`#EXT-X-MEDIA` 的 URI）；子列表里没有这段信息，
 *   切档时必须一起交给播放器，否则会只剩画面没有声音
 */
data class QualityOption(
    val url: String,
    val label: String,
    val detail: String?,
    val height: Int,
    val bandwidth: Long,
    val audioUrl: String? = null,
)

/**
 * 从 m3u8 **主播放列表**里解析清晰度档位。
 *
 * 为什么要自己解析：libvlc 的 `adaptive` 模块只会按带宽自动选，没有给宿主程序
 * 留「列出档位 / 指定档位」的接口。要选清晰度只能自己拉一次主列表、解析
 * `#EXT-X-STREAM-INF`，再把选中档位的子列表地址重新交给播放器。
 *
 * 解析本身复用 [M3u8Parser]（同一套 HLS 规则，下载侧已经验证过），
 * 这里只负责「把 variants 变成给人看的选项」这一层纯逻辑，因此可以完全单测。
 */
object QualityOptions {

    /** 下拉里最多列几档：真实站点一般 3~6 档，超过 12 的多半是重复档位 */
    const val MAX_OPTIONS = 12

    /**
     * @param text 主播放列表原文
     * @param masterUrl 主列表自身的地址（用于解析相对路径，也是「自动」对应的地址）
     * @return 按清晰度从高到低排序的档位；**不是主列表、只有一档、解析失败时返回空列表**
     */
    fun fromMaster(text: String, masterUrl: String): List<QualityOption> {
        // 解析失败不是错误：普通媒体列表本来就没有 STREAM-INF，
        // 直播列表还会被 M3u8Parser 直接拒掉 —— 这些情况都只是「没有清晰度可选」
        val playlist = runCatching { M3u8Parser.parse(text, masterUrl) }.getOrNull()
        val master = playlist as? HlsPlaylist.Master ?: return emptyList()
        // 只有一档时给下拉毫无意义，反而让用户以为切换没生效
        if (master.variants.size < 2) return emptyList()

        return master.variants
            .distinctBy { it.uri }
            .map { variant ->
                val height = parseHeight(variant.resolution)
                val bandwidth = formatBandwidth(variant.bandwidth)
                // 独立音轨名是站点给的原文（「国语」「粤语」），直接展示即可
                val audio = master.selectAudio(variant)
                QualityOption(
                    url = variant.uri,
                    label = if (height > 0) "${height}p" else (bandwidth ?: UNKNOWN_LABEL),
                    detail = listOfNotNull(
                        bandwidth.takeIf { height > 0 },
                        audio?.name?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { null },
                    height = height,
                    bandwidth = variant.bandwidth,
                    // URI 已被 M3u8Parser 解析成绝对地址
                    audioUrl = audio?.uri,
                )
            }
            .sortedWith(compareByDescending<QualityOption> { it.height }.thenByDescending { it.bandwidth })
            .take(MAX_OPTIONS)
    }

    /**
     * `1920x1080` → `1080`，取**短边**。
     *
     * 为什么不取字面上的 height：用户说的「1080p / 720p」指的是短边。
     * 竖屏片源会声明成 `1080x1920`，按 height 会得到「1920p」并排到 4K 前面 ——
     * 标签是错的，排序也被打乱。
     */
    fun parseHeight(resolution: String?): Int {
        val value = resolution?.trim()?.lowercase() ?: return 0
        val parts = value.split('x')
        if (parts.size != 2) return 0
        val width = parts[0].toIntOrNull() ?: return 0
        val height = parts[1].toIntOrNull() ?: return 0
        return minOf(width, height).takeIf { it > 0 } ?: 0
    }

    /** `4_500_000` → `4.5 Mbps`；未知或 0 返回 null（不显示「0 Mbps」这种噪音） */
    fun formatBandwidth(bandwidth: Long): String? {
        if (bandwidth <= 0L) return null
        val mbps = bandwidth / 1_000_000.0
        // 低于 1 Mbps 时给一位小数会全是 0.1 的整数倍，改显示 kbps 更好读
        return if (mbps < 1.0) {
            "${(bandwidth / 1000.0).toInt()} kbps"
        } else {
            String.format(Locale.ROOT, "%.1f Mbps", mbps)
        }
    }

    /** 列表既没分辨率也没码率时的兜底标签（正常不会出现，只是别显示空白） */
    private const val UNKNOWN_LABEL = "—"
}

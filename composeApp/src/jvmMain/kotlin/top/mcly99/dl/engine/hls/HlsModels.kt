package top.mcly99.dl.engine.hls

/**
 * HLS（m3u8）解析相关数据模型。
 *
 * 设计原则：**只描述"播放列表说了什么"**，不关心下载与转封装，
 * 便于对 [M3u8Parser] 做纯逻辑单元测试。
 */

/**
 * 主播放列表（master playlist）中的一个清晰度档位。
 *
 * @property uri 媒体播放列表的绝对地址
 * @property bandwidth 峰值带宽（bit/s），用于自动选最高清晰度
 * @property resolution 分辨率，形如 `1920x1080`；未声明时为 null
 * @property codecs 编码描述，如 `avc1.640028,mp4a.40.2`；未声明时为 null
 * @property audioGroup 独立音轨分组（`AUDIO="..."`）；音轨内嵌在视频流中时为 null
 */
data class HlsVariant(
    val uri: String,
    val bandwidth: Long,
    val resolution: String? = null,
    val codecs: String? = null,
    val audioGroup: String? = null,
)

/**
 * 主播放列表中的替代 rendition（`#EXT-X-MEDIA`）。
 *
 * @property type 类型：`AUDIO` / `VIDEO` / `SUBTITLES` / `CLOSED-CAPTIONS`
 * @property groupId 分组 ID，与 `#EXT-X-STREAM-INF` 的 `AUDIO=` 对应
 * @property uri 媒体播放列表地址；内嵌字幕等没有独立地址时为 null
 * @property isDefault 是否 `DEFAULT=YES`
 * @property autoselect 是否 `AUTOSELECT=YES`
 * @property language `LANGUAGE` 属性
 * @property name `NAME` 属性
 */
data class HlsRendition(
    val type: String,
    val groupId: String,
    val uri: String? = null,
    val isDefault: Boolean = false,
    val autoselect: Boolean = false,
    val language: String? = null,
    val name: String? = null,
)

/**
 * 加密信息（来自 `#EXT-X-KEY`）。
 *
 * @property method 加密方法：`NONE` / `AES-128` / `SAMPLE-AES` 等
 * @property uri 密钥文件地址；`NONE` 时为 null
 * @property iv 初始向量（16 字节 hex，不带 `0x` 前缀）；未显式给出时为 null，按规范用分片序号推导
 */
data class HlsKey(
    val method: String,
    val uri: String? = null,
    val iv: String? = null,
) {
    /** 是否为无需处理的明文 */
    val isNone: Boolean get() = method.equals("NONE", ignoreCase = true)

    /** 是否为受支持的 AES-128 加密 */
    val isAes128: Boolean get() = method.equals("AES-128", ignoreCase = true)

    /** 是否为不支持的受保护加密（SAMPLE-AES 等 DRM 场景） */
    val isUnsupported: Boolean get() = !isNone && !isAes128
}

/**
 * 媒体播放列表中的一个分片。
 *
 * @property uri 分片绝对地址
 * @property duration 时长（秒），来自 `#EXTINF`
 * @property sequence 媒体序列号（`#EXT-X-MEDIA-SEQUENCE` 起始值逐片递增），用于推导默认 IV
 * @property byteRange `#EXT-X-BYTERANGE` 指定时只取该区间；否则为 null
 * @property key 该分片生效的密钥；明文时为 null
 */
data class HlsSegment(
    val uri: String,
    val duration: Double,
    val sequence: Int,
    val byteRange: LongRange? = null,
    val key: HlsKey? = null,
)

/**
 * fMP4 初始化段（来自 `#EXT-X-MAP`）。
 *
 * @property uri 初始化段地址
 * @property byteRange 只取部分字节时为非 null
 */
data class HlsMap(
    val uri: String,
    val byteRange: LongRange? = null,
)

/**
 * 解析结果。
 */
sealed interface HlsPlaylist {

    /**
     * 主播放列表：包含多个清晰度档位。
     *
     * @property variants 档位列表，非空
     * @property renditions 替代 rendition（`#EXT-X-MEDIA`），常见为独立音轨
     * @property sessionKey 主列表声明的 `#EXT-X-SESSION-KEY`；媒体列表自己的密钥会覆盖它
     */
    data class Master(
        val variants: List<HlsVariant>,
        val renditions: List<HlsRendition> = emptyList(),
        val sessionKey: HlsKey? = null,
    ) : HlsPlaylist {
        /**
         * 按带宽自动选择最高清晰度。
         * 带宽相同时优先带分辨率信息的档位，再按原始顺序取第一个。
         */
        fun selectHighestBandwidth(): HlsVariant =
            variants.reduce { best, candidate ->
                when {
                    candidate.bandwidth > best.bandwidth -> candidate
                    candidate.bandwidth < best.bandwidth -> best
                    candidate.resolution != null && best.resolution == null -> candidate
                    else -> best
                }
            }

        /**
         * 选中档位对应的独立音轨；音轨内嵌或列表没有声明时返回 null。
         *
         * 优先 `DEFAULT=YES`，其次 `AUTOSELECT=YES`，最后按声明顺序取第一个带地址的音轨。
         */
        fun selectAudio(variant: HlsVariant): HlsRendition? {
            val group = variant.audioGroup ?: return null
            val candidates = renditions.filter {
                it.type.equals("AUDIO", ignoreCase = true) && it.groupId == group && it.uri != null
            }
            return candidates.firstOrNull { it.isDefault }
                ?: candidates.firstOrNull { it.autoselect }
                ?: candidates.firstOrNull()
        }
    }

    /**
     * 媒体播放列表：包含实际分片。
     *
     * @property segments 分片列表（按播放顺序）
     * @property map fMP4 初始化段；TS 流为 null
     * @property targetDuration `#EXT-X-TARGETDURATION`，用于时长估算
     * @property isLive 是否直播：缺少 `#EXT-X-ENDLIST` 即视为直播
     */
    data class Media(
        val segments: List<HlsSegment>,
        val map: HlsMap? = null,
        val targetDuration: Int = 0,
        val isLive: Boolean = false,
    ) : HlsPlaylist {

        /** 总时长（秒） */
        val totalDuration: Double get() = segments.sumOf { it.duration }

        /** 是否为 fMP4 分片（有初始化段） */
        val isFragmentedMp4: Boolean get() = map != null
    }
}

/**
 * 解析失败时抛出的异常。
 *
 * @property reason 面向用户的简短原因
 */
class HlsException(
    val reason: String,
    message: String = reason,
    cause: Throwable? = null,
) : Exception(message, cause)

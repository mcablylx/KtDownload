package top.mcly99.dl.engine.hls

import java.net.URI

/**
 * m3u8（HLS）播放列表解析器。
 *
 * 纯函数实现，**不做任何网络请求**：输入是播放列表文本与它的访问地址，
 * 输出 [HlsPlaylist]。这样解析逻辑可以 100% 单元测试覆盖。
 *
 * 支持：
 * - 主列表（`#EXT-X-STREAM-INF`）与媒体列表自动识别
 * - `#EXT-X-MEDIA`（独立音轨等 rendition）与 `AUDIO=` 分组
 * - `#EXTINF` 时长、`#EXT-X-BYTERANGE` 字节区间（含省略 offset 的续接写法）
 * - `#EXT-X-KEY`（含作用域继承、`METHOD=NONE` 解除加密、显式 `IV`）
 * - `#EXT-X-MAP`（fMP4 初始化段）
 * - `#EXT-X-ENDLIST` 判定直播
 * - 相对 / 绝对 / 协议相对（`//host/path`）地址解析
 *
 * 不支持（解析阶段直接抛 [HlsException]，避免创建必然失败的任务）：
 * - 直播流（无 `#EXT-X-ENDLIST`）
 * - `SAMPLE-AES` 等 DRM 加密
 * - 加密的 `#EXT-X-MAP`（初始化段不支持解密）
 */
object M3u8Parser {

    private const val TAG_M3U8 = "#EXTM3U"
    private const val TAG_STREAM_INF = "#EXT-X-STREAM-INF"
    private const val TAG_MEDIA = "#EXT-X-MEDIA"
    private const val TAG_INF = "#EXTINF"
    private const val TAG_KEY = "#EXT-X-KEY"
    private const val TAG_SESSION_KEY = "#EXT-X-SESSION-KEY"
    private const val TAG_MAP = "#EXT-X-MAP"
    private const val TAG_BYTERANGE = "#EXT-X-BYTERANGE"
    private const val TAG_TARGET_DURATION = "#EXT-X-TARGETDURATION"
    private const val TAG_MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE"
    private const val TAG_ENDLIST = "#EXT-X-ENDLIST"

    /** 单个媒体列表的分片数上限：正常 VOD 远小于此值，防止恶意列表拖垮任务 */
    private const val MAX_SEGMENTS = 10_000

    /**
     * 解析播放列表。
     *
     * @param text 播放列表文本
     * @param baseUrl 播放列表自身的访问地址，用于把相对路径解析为绝对地址
     * @param defaultKey 主列表 `#EXT-X-SESSION-KEY` 声明的默认密钥；媒体列表自己的
     *   `#EXT-X-KEY` 会覆盖它
     * @return [HlsPlaylist.Master] 或 [HlsPlaylist.Media]
     * @throws HlsException 内容非法、为空、为直播流或使用不支持的加密时
     */
    fun parse(text: String, baseUrl: String, defaultKey: HlsKey? = null): HlsPlaylist {
        val lines = normalize(text).lines()
        if (lines.firstOrNull()?.trim() != TAG_M3U8) {
            throw HlsException("不是有效的 m3u8 文件（缺少 #EXTM3U 头）")
        }
        if (lines.any { it.trimStart().startsWith(TAG_STREAM_INF) }) {
            return parseMaster(lines, baseUrl)
        }
        return parseMedia(lines, baseUrl, defaultKey)
    }

    /**
     * 只解析清晰度档位（用于已有主列表时快速取最高档），非主列表返回 null。
     */
    fun parseVariantsOrNull(text: String, baseUrl: String): List<HlsVariant>? {
        val lines = normalize(text).lines()
        if (lines.none { it.trimStart().startsWith(TAG_STREAM_INF) }) return null
        return parseMaster(lines, baseUrl).variants
    }

    // ------------------------------------------------------------------ 主列表

    private fun parseMaster(lines: List<String>, baseUrl: String): HlsPlaylist.Master {
        val variants = mutableListOf<HlsVariant>()
        val renditions = mutableListOf<HlsRendition>()
        var pendingAttrs: Map<String, String>? = null
        var sessionKey: HlsKey? = null

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line.startsWith(TAG_SESSION_KEY)) {
                val attrs = parseAttributes(line.removePrefix(TAG_SESSION_KEY).trim().removePrefix(":"))
                sessionKey = parseKey(attrs, baseUrl, tag = TAG_SESSION_KEY)
                continue
            }
            if (line.startsWith(TAG_STREAM_INF)) {
                val attrText = line.removePrefix(TAG_STREAM_INF).trim()
                pendingAttrs = parseAttributes(attrText.removePrefix(":"))
                continue
            }
            if (line.startsWith(TAG_MEDIA)) {
                val attrs = parseAttributes(line.removePrefix(TAG_MEDIA).trim().removePrefix(":"))
                val type = attrs["TYPE"]?.uppercase()
                val groupId = attrs["GROUP-ID"]
                if (type != null && groupId != null) {
                    renditions.add(
                        HlsRendition(
                            type = type,
                            groupId = groupId,
                            uri = attrs["URI"]?.let { resolveUrl(baseUrl, it) },
                            isDefault = attrs["DEFAULT"].equals("YES", ignoreCase = true),
                            autoselect = attrs["AUTOSELECT"].equals("YES", ignoreCase = true),
                            language = attrs["LANGUAGE"],
                            name = attrs["NAME"],
                        ),
                    )
                }
                continue
            }
            if (line.startsWith("#")) continue

            // 普通行 = 上一行 STREAM-INF 指向的媒体列表
            val attrs = pendingAttrs ?: continue
            pendingAttrs = null
            variants.add(
                HlsVariant(
                    uri = resolveUrl(baseUrl, line),
                    bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                    resolution = attrs["RESOLUTION"],
                    codecs = attrs["CODECS"],
                    audioGroup = attrs["AUDIO"],
                ),
            )
        }

        if (variants.isEmpty()) {
            throw HlsException("主播放列表中没有可用的清晰度")
        }
        return HlsPlaylist.Master(variants, renditions, sessionKey)
    }

    // ------------------------------------------------------------------ 媒体列表

    private fun parseMedia(lines: List<String>, baseUrl: String, defaultKey: HlsKey? = null): HlsPlaylist.Media {
        val segments = mutableListOf<HlsSegment>()
        var currentKey: HlsKey? = defaultKey
        var map: HlsMap? = null
        // 多个不同的初始化段无法与分片正确对应：先记录，解析结束时明确拒绝
        val maps = LinkedHashSet<HlsMap>()
        var targetDuration = 0
        var mediaSequence = 0
        var hasEndList = false
        var pendingDuration: Double? = null
        var pendingByteRange: String? = null
        // 同一资源省略 offset 的 BYTERANGE 需要紧接上一子区间，按 URI 记录上一段结束位置
        val lastRangeEnd = HashMap<String, Long>()

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith(TAG_KEY) -> {
                    val attrs = parseAttributes(line.removePrefix(TAG_KEY).trim().removePrefix(":"))
                    currentKey = parseKey(attrs, baseUrl, tag = TAG_KEY)
                }

                line.startsWith(TAG_MAP) -> {
                    // 初始化段解密未实现：明确拒绝，避免产出无法播放的 MP4
                    if (currentKey != null) {
                        throw HlsException("加密的初始化段（#EXT-X-MAP）暂不支持")
                    }
                    val attrs = parseAttributes(line.removePrefix(TAG_MAP).trim().removePrefix(":"))
                    val uri = attrs["URI"] ?: throw HlsException("#EXT-X-MAP 缺少 URI")
                    map = HlsMap(
                        uri = resolveUrl(baseUrl, uri),
                        byteRange = attrs["BYTERANGE"]?.let { parseByteRange(it, fallbackOffset = 0L) },
                    ).also { maps.add(it) }
                }

                line.startsWith(TAG_INF) -> {
                    val value = line.removePrefix(TAG_INF).removePrefix(":").substringBefore(',')
                    pendingDuration = value.trim().toDoubleOrNull()
                }

                line.startsWith(TAG_BYTERANGE) -> {
                    // offset 可能省略，要等分片 URI 确定后才能按「上一子区间」推算
                    pendingByteRange = line.removePrefix(TAG_BYTERANGE).removePrefix(":").trim()
                }

                line.startsWith(TAG_TARGET_DURATION) -> {
                    targetDuration = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }

                line.startsWith(TAG_MEDIA_SEQUENCE) -> {
                    mediaSequence = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }

                line.startsWith(TAG_ENDLIST) -> {
                    hasEndList = true
                }

                line.startsWith("#") -> {
                    // 其余标签（VERSION / DISCONTINUITY / PLAYLIST-TYPE 等）暂不处理
                }

                else -> {
                    // 普通行 = 分片地址
                    val uri = resolveUrl(baseUrl, line)
                    val byteRange = pendingByteRange?.let { spec ->
                        parseByteRange(spec, fallbackOffset = lastRangeEnd[uri] ?: 0L)?.also { range ->
                            lastRangeEnd[uri] = range.last + 1L
                        }
                    }
                    segments.add(
                        HlsSegment(
                            uri = uri,
                            duration = pendingDuration ?: 0.0,
                            sequence = mediaSequence + segments.size,
                            byteRange = byteRange,
                            key = currentKey,
                        ),
                    )
                    pendingDuration = null
                    pendingByteRange = null
                }
            }
        }

        if (segments.isEmpty()) {
            throw HlsException("播放列表中没有分片")
        }
        if (!hasEndList) {
            throw HlsException("该链接是直播流（缺少 #EXT-X-ENDLIST），暂不支持下载")
        }
        // 带 DISCONTINUITY 的流可能有多个初始化段；当前实现只能拼接一个，明确拒绝避免静默花屏
        if (maps.size > 1) {
            throw HlsException("该视频包含多个初始化段（#EXT-X-MAP），暂不支持下载")
        }
        if (segments.size > MAX_SEGMENTS) {
            throw HlsException("播放列表分片过多（${segments.size} > $MAX_SEGMENTS），暂不支持下载")
        }
        return HlsPlaylist.Media(
            segments = segments,
            map = map,
            targetDuration = targetDuration,
            isLive = false,
        )
    }

    /**
     * 解析 `#EXT-X-KEY` / `#EXT-X-SESSION-KEY` 的属性。
     *
     * 只接受 `METHOD=NONE` 与 `KEYFORMAT=identity` 的 AES-128；FairPlay/Widevine
     * 等 DRM 密钥格式明确拒绝，避免把 DRM key URI 当普通密钥下载。
     *
     * @return 明文时返回 null
     */
    private fun parseKey(attrs: Map<String, String>, baseUrl: String, tag: String): HlsKey? {
        val method = attrs["METHOD"]?.uppercase() ?: "NONE"
        if (method == "NONE") return null
        val keyFormat = attrs["KEYFORMAT"]?.trim()
        if (keyFormat != null && !keyFormat.equals("identity", ignoreCase = true)) {
            throw HlsException("该视频使用 DRM 密钥格式（$keyFormat），暂不支持下载")
        }
        val key = HlsKey(
            method = method,
            uri = attrs["URI"]?.let { resolveUrl(baseUrl, it) },
            iv = attrs["IV"]?.removePrefix("0x")?.removePrefix("0X")?.lowercase(),
        )
        if (key.isUnsupported) {
            throw HlsException("该视频使用受保护的加密方式（${key.method}），暂不支持下载")
        }
        if (key.uri == null) throw HlsException("$tag 缺少密钥地址")
        return key
    }

    // ------------------------------------------------------------------ 工具

    /** 去掉 UTF-8 BOM、统一换行符 */
    internal fun normalize(text: String): String =
        text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')

    /**
     * 解析 HLS 属性列表：`A=1,B="x,y",C=2`。
     *
     * 必须按「引号外的逗号」切分，否则 `CODECS="avc1.64,mp4a.40"` 会被切坏。
     */
    internal fun parseAttributes(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0

        fun flush() {
            val pair = current.toString()
            current.setLength(0)
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val name = pair.substring(0, eq).trim().uppercase()
                val value = pair.substring(eq + 1).trim().removeSurrounding("\"")
                if (name.isNotEmpty()) result[name] = value
            }
        }

        while (index < text.length) {
            val ch = text[index]
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    current.append(ch)
                }
                ch == ',' && !inQuotes -> flush()
                else -> current.append(ch)
            }
            index++
        }
        flush()
        return result
    }

    /**
     * 解析 `#EXT-X-BYTERANGE:n[@o]` 为区间。
     *
     * 规范允许省略 offset，表示「紧接同一资源的上一子区间」；调用方通过 [fallbackOffset] 传入。
     * 既没有 offset 也没有前值时返回 null。
     */
    internal fun parseByteRange(raw: String, fallbackOffset: Long? = null): LongRange? {
        val text = raw.trim().removePrefix(":").trim()
        val parts = text.split('@')
        val length = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val offset = parts.getOrNull(1)?.toLongOrNull() ?: fallbackOffset ?: return null
        if (length <= 0L || offset < 0L) return null
        return offset until (offset + length)
    }

    /**
     * 把相对地址解析成绝对地址。
     *
     * 支持三种形式：
     * - 绝对地址 `https://...`
     * - 协议相对 `//host/path`（继承 baseUrl 的协议）
     * - 相对路径 `1.ts` / `../a/1.ts`
     */
    internal fun resolveUrl(baseUrl: String, reference: String): String {
        val ref = reference.trim()
        if (ref.isEmpty()) return baseUrl
        return runCatching {
            val base = URI(baseUrl)
            base.resolve(URI(ref)).toString()
        }.getOrElse { ref }
    }
}

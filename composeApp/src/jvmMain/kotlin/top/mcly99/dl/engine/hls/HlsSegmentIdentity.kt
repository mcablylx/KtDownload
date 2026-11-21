package top.mcly99.dl.engine.hls

/**
 * HLS 分片的「身份」：判断磁盘上已有的 `seg_XXXXX.ts` 是不是当前播放列表要的那一片。
 *
 * 改动前的续传只看 `seg_XXXXX.done` **是否存在**，等于假设「同一路径 = 同一内容」。
 * 同一条播放列表地址背后换成另一份视频（网盘覆盖上传、重新解析到别的文件）时，
 * 旧分片会被原样复用，合成出来的视频前半段是旧片、后半段是新片 —— 时长对得上、
 * 文件也完整，属于查不出来的坏文件。
 *
 * 这里把 `.done` 从「空标记」改成「身份标记」，记录四样东西：
 *
 * 1. **分片地址去掉 query 后的路径** —— 时效签名每轮都变，比原地址会把续传变成全量重下；
 *    路径则相对稳定（CDN 通常把内容标识放在路径里，`/vod/12345/seg-1.ts`）。
 * 2. **时长**（`#EXTINF`）—— 同一路径下时长变了，说明切片方式变了，内容必然不同。
 * 3. **`#EXT-X-BYTERANGE`** —— 地址不变但区间变了，取到的就是另一段字节。
 * 4. **密钥的方法 / 地址 / IV** —— 密文相同而密钥换了，解出来是垃圾。
 *
 * 比对时再叠一条**本地长度**：磁盘上的文件被外部截断或替换过，长度对不上就重下。
 *
 * ## 已知边界
 *
 * 路径、时长、区间、密钥全都一模一样、而内容被换掉的替换认不出来。要认出来只能逐片
 * 发 HEAD 比对 `ETag`/`Content-Length`，1000 片就是 1000 个额外请求，代价远超收益。
 * 实际中 CDN 的路径几乎总带内容标识，这种替换属于极端情况。
 */
internal object HlsSegmentIdentity {

    /** `.done` 的格式版本。不认识的值一律按「未完成」处理，让这一片重下 */
    const val VERSION = "v1"

    /** 字段分隔符：URL、数字、方法名里都不会出现制表符 */
    private const val FIELD = '\t'

    private const val NO_RANGE = "-"
    private const val NO_KEY = "-"

    /**
     * 去掉时效签名后的地址。
     *
     * 先切 `#`（fragment，客户端从不上行）再切 `?`（query）。
     * 不用 [java.net.URI] 解析：播放列表里常出现未转义的空格与中文，
     * 解析失败时退回朴素截断反而更可靠。
     */
    fun stableUri(uri: String): String =
        uri.trim().substringBefore('#').substringBefore('?')

    /**
     * 一个分片的身份签名（单行，制表符分隔 6 个字段）。
     *
     * 加密分片的 IV 也要算进来：同为 `AES-128`、同一个密钥地址，IV 换了
     * 解出来的明文完全不同（默认 IV 由分片序号推导，序号变则明文变）。
     */
    fun signature(segment: HlsSegment): String = buildString {
        append(stableUri(segment.uri))
        append(FIELD)
        append(segment.duration)
        append(FIELD)
        val range = segment.byteRange
        if (range == null) {
            append(NO_RANGE)
        } else {
            append(range.first).append('-').append(range.last)
        }
        append(FIELD)
        val key = segment.key
        if (key == null || key.isNone) {
            append(NO_KEY).append(FIELD).append(NO_KEY).append(FIELD).append(NO_KEY)
        } else {
            append(key.method)
            append(FIELD)
            append(key.uri?.let(::stableUri) ?: NO_KEY)
            append(FIELD)
            append(key.iv ?: NO_KEY)
        }
    }

    /** `.done` 文件的完整内容：`版本 / 签名 / 落盘长度` */
    fun doneContent(segment: HlsSegment, length: Long): String =
        "$VERSION\n${signature(segment)}\n$length\n"

    /**
     * `.done` 是否还适用于 [segment]。
     *
     * @param doneText `.done` 的内容；文件不存在或读不出来时传 null
     * @param onDiskLength 分片文件当前的实际长度
     * @return true 才能跳过下载
     */
    fun matches(doneText: String?, segment: HlsSegment, onDiskLength: Long): Boolean {
        // 空内容 = 改动前写的空标记，没有任何身份信息，不敢复用
        if (doneText.isNullOrBlank() || onDiskLength <= 0) return false
        val lines = doneText.trim().lines()
        if (lines.size != 3) return false
        if (lines[0].trim() != VERSION) return false
        if (lines[1] != signature(segment)) return false
        return lines[2].trim().toLongOrNull() == onDiskLength
    }
}

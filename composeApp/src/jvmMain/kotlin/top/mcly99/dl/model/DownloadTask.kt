package top.mcly99.dl.model

import top.mcly99.dl.engine.ContentFingerprint
import java.nio.file.Path
import java.time.LocalDateTime

/**
 * 下载任务数据模型
 * 
 * 该数据类封装了下载任务的所有核心信息，包括任务标识、
 * 下载源URL、目标文件路径、进度状态、性能指标等。
 * 是整个下载系统的核心数据载体，被引擎层、ViewModel层和UI层广泛使用。
 * 
 * 主要特性：
 * - 支持断点续传：通过downloadedSize字段记录已下载字节数
 * - 智能进度计算：支持总大小已知和未知两种情况的进度估算
 * - 性能监控：提供实时下载速度、剩余时间等关键性能指标
 * - 文件管理：内置文件大小格式化、时间格式化等实用方法
 * 
 * @property id 任务唯一标识符（UUID）
 * @property url 下载源URL地址
 * @property fileName 目标文件名
 * @property savePath 目标文件完整路径
 * @property totalSize 文件总大小（字节），0表示未知
 * @property downloadedSize 已下载大小（字节）
 * @property status 当前下载状态
 * @property speed 当前下载速度（字节/秒）
 * @property createTime 任务创建时间
 * @property completeTime 任务完成时间（仅COMPLETED状态有效）
 * @property errorMessage 错误信息（仅FAILED状态有效）
 * 
 * @see DownloadStatus 任务状态枚举
 * @see DownloadEngine 下载引擎实现
 * @see DownloadViewModel ViewModel层封装
 */
data class DownloadTask(
    val id: String,
    val url: String,
    val fileName: String,
    val savePath: Path,
    val totalSize: Long = 0L,        // 总大小（字节），0表示未知
    val downloadedSize: Long = 0L,   // 已下载大小（字节）
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val speed: Long = 0L,            // 当前速度（字节/秒）
    val createTime: LocalDateTime = LocalDateTime.now(),
    val completeTime: LocalDateTime? = null,
    val errorMessage: String? = null,
    val threads: Int = 4,            // 单文件下载线程数
    val type: TaskType = TaskType.Http,
    val segmentDone: Int = 0,        // HLS 已完成分片数
    val segmentTotal: Int = 0,       // HLS 总分片数
    /**
     * 防盗链用的 `Referer`（通常是**页面**地址，不是媒体地址）。
     *
     * 抓网页抓到的地址 90% 在跨域 CDN 上，服务端要求 `Referer` 是播放页；不带就是 403。
     * `null` 表示按老行为处理（HLS 回退到用自己的播放列表地址）。
     */
    val referer: String? = null,
    /**
     * 覆盖引擎默认的 `User-Agent`。
     *
     * 网盘 CDN 对 UA 有硬要求：115 要客户端串、迅雷要官方 app 串、百度要 `netdisk` 串，
     * 用浏览器 UA 会被判成「网页端」并降级（拒绝大文件直链 / 回 200 整文件）。
     * `null` 表示沿用引擎按 Referer/Cookie 推断的默认值（抓网页来的地址即浏览器 UA）。
     */
    val userAgent: String? = null,
    /** `Origin` 头：UC、蓝奏优享的 CDN 会校验跨域来源，缺失被限速或 403 */
    val origin: String? = null,
    /** UC 游客链路的 `Sec-Ch-Ua` 客户端提示，与游客 UA 配套 */
    val secChUa: String? = null,
    /**
     * 会话 `Cookie`（只有深度抓取抓到的地址会带）。
     *
     * 带签名（`wsSecret` / `wsTime`）的地址常把会话也绑进去，缺 Cookie 时「浏览器能播、
     * 这里下不动」。`null` 表示不设这个头（与老行为一致）。
     *
     * **不落盘**（v8-P0-1）：重启后恢复的任务只有 [requiresCookie] 标记，`cookie` 一定是 `null`。
     */
    val cookie: String? = null,
    /**
     * 这个任务需要会话 `Cookie` 才能下载。
     *
     * Cookie 本身不写入磁盘（见 [cookie]）。重启后恢复出来的任务靠这个标记判定：
     * 引擎会把它标成失败并提示「回到抓网页重新抓取」，而不是发一个注定 403 的请求。
     */
    val requiresCookie: Boolean = false,
    /**
     * C2 单文件多源分片的备选直链（每条都带自己那份请求头）。
     *
     * **只存在于内存**：[top.mcly99.dl.engine.TaskStore] 不会写 Cookie，
     * 重启后恢复的任务这个字段一定是空的（并会被标成失败、提示重新解析）。
     * 只有大于多线程阈值、且服务端支持 Range 的任务才会真正用到它。
     */
    val altSources: List<DownloadSource> = emptyList(),
    /**
     * 远端实体的 `ETag`（P0-2）：续传前用它判断「同一个 URL 背后还是不是同一份字节」。
     *
     * 没有它的时候，暂停期间远端被覆盖上传，续传会把新文件的后半段拼到旧文件的前半段上，
     * 长度对得上、校验全过，产出的是一个**静默坏文件**。会落盘（不是凭证），
     * 重启后仍能验证；老配置文件没有这个 key，读回 null，行为与改动前一致。
     */
    val etag: String? = null,
    /** 远端实体的 `Last-Modified`；服务器不给 `ETag` 时靠它兜底，语义同 [etag] */
    val lastModified: String? = null,
) {
    /** 把两个字段打包成引擎与清单共用的一份指纹（只在引擎内部用，不对外暴露） */
    internal val fingerprint: ContentFingerprint
        get() = ContentFingerprint.of(etag, lastModified)

    // 下载进度（0.0 - 1.0）；HLS 以分片数量为准
    // 上界钳制：并发分片回写或重试重算时 downloadedSize 可能短暂超过 totalSize，
    // 不钳制会让进度条 / 百分比越过 100%（P2-E8）
    val progress: Double
        get() = when {
            type == TaskType.M3u8 && segmentTotal > 0 ->
                (segmentDone.toDouble() / segmentTotal).coerceIn(0.0, 1.0)
            totalSize > 0 -> (downloadedSize.toDouble() / totalSize).coerceIn(0.0, 1.0)
            else -> 0.0
        }
    
    // 估算进度（用于totalSize未知的情况）
    val estimatedProgress: Double
        get() = if (totalSize > 0) {
            downloadedSize.toDouble() / totalSize
        } else {
            // 当totalSize未知时，使用对数比例来显示相对进度
            // 避免除零错误，同时提供有意义的进度指示
            if (downloadedSize == 0L) 0.0 else {
                val logSize = kotlin.math.log10(downloadedSize.toDouble() + 1.0)
                // 将对数大小映射到0-1范围，最大值设为10MB的对数
                val maxLogSize = kotlin.math.log10(10 * 1024 * 1024.0 + 1.0)
                (logSize / maxLogSize).coerceAtMost(1.0)
            }
        }
    
    // 进度百分比：未完成时最多显示 99%，避免 199/200 被截断成 100%
    val progressPercent: Int
        get() = when {
            progress >= 1.0 -> 100
            progress <= 0.0 -> 0
            else -> (progress * 100).toInt().coerceIn(1, 99)
        }
    
    // 剩余时间（秒），如果速度为0或总大小未知则返回-1
    val remainingTime: Long
        get() = if (speed > 0 && totalSize > 0) {
            (totalSize - downloadedSize) / speed
        } else -1
    
    // 格式化文件大小
    fun formatSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> "${size / (1024 * 1024 * 1024)} GB"
        }
    }
    
    // 格式化时间
    fun formatTime(seconds: Long): String {
        if (seconds < 0) return "未知"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 -> "${hours}时${minutes}分${secs}秒"
            minutes > 0 -> "${minutes}分${secs}秒"
            else -> "${secs}秒"
        }
    }
}

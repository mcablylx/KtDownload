package top.mcly99.dl.utils

/**
 * 格式化工具类
 * 
 * 该工具类提供了一系列用于UI显示的格式化功能，包括文件大小、传输速度、时间间隔等的格式化，
 * 以及URL类型检测和任务ID生成等辅助功能。
 * 
 * 主要功能：
 * - 文件大小格式化：支持B、KB、MB、GB、TB单位自动转换
 * - 传输速度格式化：在文件大小格式化基础上添加"/s"后缀
 * - 时间间隔格式化：支持秒、分钟、小时等不同时间单位的本地化显示
 * - 剩余时间估算：基于当前速度和剩余数据量计算预估时间
 * - URL类型检测：自动识别HTTP、BT、M3U8等不同协议类型的下载链接
 * - 任务ID生成：生成唯一且可排序的任务标识符
 * 
 * 设计特点：
 * - 本地化支持：所有格式化方法都支持多语言字符串资源
 * - 性能优化：避免不必要的对象创建和字符串拼接
 * - 精确计算：使用对数计算确保单位转换的准确性
 * - 用户友好：提供合理的默认参数和错误处理
 * 
 * @see UiStrings UI字符串资源接口
 * @see TaskType 任务类型枚举
 */

import top.mcly99.dl.model.TaskType
import top.mcly99.dl.theme.UiStrings
import top.mcly99.dl.theme.zhCN
import java.util.Locale
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

fun formatBytes(bytes: Long, decimals: Int = 2): String {
    if (bytes == 0L) return "0 B"
    val k = 1024.0
    val sizes = arrayOf("B", "KB", "MB", "GB", "TB")
    val i = floor(ln(bytes.toDouble()) / ln(k)).toInt().coerceIn(0, sizes.lastIndex)
    val value = bytes / k.pow(i)
    return "${String.format(Locale.US, "%.${decimals}f", value)} ${sizes[i]}"
}

fun formatSpeed(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

fun formatDuration(seconds: Double, s: UiStrings = zhCN()): String = when {
    seconds < 60 -> s.timeSeconds(seconds.toInt())
    seconds < 3600 -> {
        s.timeMinutes((seconds / 60).toInt(), (seconds % 60).toInt())
    }
    else -> {
        val hours = (seconds / 3600).toInt()
        val minutes = ((seconds % 3600) / 60).toInt()
        s.timeHours(hours, minutes)
    }
}

fun estimateTimeRemaining(downloaded: Long, total: Long, speed: Long, s: UiStrings = zhCN()): String {
    if (speed == 0L) return s.calculating
    val remaining = (total - downloaded).coerceAtLeast(0)
    val seconds = remaining.toDouble() / speed.toDouble()
    return formatDuration(seconds, s)
}

/** 引擎当前支持的传输层协议；m3u8 也通过 http(s) 获取 */
val SUPPORTED_DOWNLOAD_SCHEMES = setOf("http", "https")

/**
 * 判断链接是否受当前下载引擎支持。
 *
 * HTTP / HTTPS 直链与 m3u8（HLS）均可下载；`magnet:`、`.torrent` 等 BT
 * 链接仍会拒绝，避免创建出必然失败的任务。
 */
fun isSupportedDownloadUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    val scheme = trimmed.substringBefore("://", "").lowercase()
    if (scheme !in SUPPORTED_DOWNLOAD_SCHEMES) return false
    return try {
        val parsed = java.net.URL(trimmed)
        if (parsed.host.isBlank()) return false
        // BT 引擎尚未实现：即使外层是 http(s) 也拒绝
        detectUrlType(trimmed) != TaskType.Bt
    } catch (_: Exception) {
        false
    }
}

fun detectUrlType(url: String): TaskType {
    val trimmed = url.trim()
    val lower = trimmed.lowercase()
    if (lower.startsWith("magnet:")) return TaskType.Bt
    // 只看路径：`?file=foo.m3u8` 这类查询串不代表响应是播放列表
    val path = runCatching { java.net.URL(lower).path }.getOrDefault(lower)
    val extension = path.substringAfterLast('/').substringAfterLast('.', "")
    return when {
        extension == "torrent" -> TaskType.Bt
        extension == "m3u8" -> TaskType.M3u8
        // 已知媒体/压缩包直链即使位于 `/hls/` 目录下也按普通 HTTP 处理
        extension in DIRECT_FILE_EXTENSIONS -> TaskType.Http
        // 保留「HLS 播放页」启发式：`/hls/index.html` 等非媒体扩展名仍视为 M3U8
        path.contains("/hls/") -> TaskType.M3u8
        else -> TaskType.Http
    }
}

/** 已知的媒体/压缩包扩展名：命中时优先按普通直链处理，避免被 `/hls/` 误判 */
private val DIRECT_FILE_EXTENSIONS = setOf(
    "mp4", "mkv", "ts", "flv", "webm", "mov", "avi", "wmv", "m4v", "m4s",
    "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus",
    "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "iso",
    "apk", "exe", "msi", "dmg", "pkg",
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg",
    "pdf", "epub", "txt", "srt", "ass", "vtt",
)


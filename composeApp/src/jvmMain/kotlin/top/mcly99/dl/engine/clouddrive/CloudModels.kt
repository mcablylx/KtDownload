package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.model.ShareFile
import top.mcly99.dl.model.DownloadSource

/** 分享目录里的一个条目；UI 只消费这个投影，不直接依赖移植层模型 */
data class CloudFile(
    val fid: String,
    val name: String,
    val size: Long,
    val isDir: Boolean,
    val pdirFid: String,
    val fidToken: String,
    val modifyTime: String = "",
)

fun ShareFile.toCloudFile(): CloudFile = CloudFile(
    fid = fid,
    name = fname,
    size = fsize,
    isDir = isdir,
    pdirFid = pdirFid,
    fidToken = fidToken,
    modifyTime = modifyTime,
)

fun CloudFile.toShareFile(): ShareFile = ShareFile(
    fid = fid,
    fname = name,
    fsize = size,
    isdir = isDir,
    pdirFid = pdirFid,
    fidToken = fidToken,
    modifyTime = modifyTime,
)

/**
 * 取到的下载直链。
 *
 * @property cookie 下载时必须携带的会话：游客态直链用上游下发的 `__pugs` 等令牌，
 *   登录态用当前账号凭证。缺了它夸克 CDN 返回 412、UC 返回 403。
 * @property referer 网盘 CDN 的防盗链来源；下载器要把它当成 Referer 一并发送。
 * @property userAgent 下载时覆盖引擎默认 UA 的平台专用串（115 客户端、迅雷 app、
 *   百度 netdisk 等）。`null` 表示沿用引擎默认推断，见 [CloudDownloadHeaders]。
 * @property origin 需要校验跨域来源的 CDN（UC / 蓝奏优享）要求的 `Origin` 头。
 * @property secChUa UC 游客链路的 `Sec-Ch-Ua` 客户端提示，与游客 UA 配套。
 * @property relativeDir 「下载整个文件夹」时保留的相对目录层级（以 `/` 分隔），
 *   普通单文件下载为空串。落盘时会在下载根目录下按层级创建子目录。
 * @property cleanupDirFid 转存兜底时创建的临时目录 fid；下载任务进入终态后由
 *   [CloudDriveApi.cleanupTempDir] 连同 [platform] 一起回收，不再残留在用户网盘。
 * @property platform 产出这条直链的网盘，清理临时目录时要按它选中对应的 Repository。
 *   默认 null 只为兼容测试里的手写 CloudLink；生产链路始终由 CloudDriveService 填充。
 */
data class CloudLink(
    val url: String,
    val filename: String,
    val size: Long,
    val cookie: String,
    val referer: String,
    val isHls: Boolean = false,
    val cleanupDirFid: String? = null,
    val userAgent: String? = null,
    val origin: String? = null,
    val secChUa: String? = null,
    val relativeDir: String = "",
    val platform: SharePlatform? = null,
    /**
     * C2 备选直链：由账号池里的其它账号对**同一个文件**各取一条，头与 URL 成对绑定。
     * 只在内存里流转，不会写进直链列表的持久化文件，也不会跟着 Cookie 落盘。
     */
    val altSources: List<DownloadSource> = emptyList(),
)

/**
 * 下载任务该用哪个大小。
 *
 * 列目录接口给的大小就是用户在网盘列表里看到的值，比取直链响应里的 `size` 可靠：
 * 部分平台取链接口会把 `size` 填成占位值（曾实到 239 MB 的文件返回 6.83 KB），
 * 拿它入队会让任务一开始就是 100%、速度 0。只有列表没给有效值时才回落取链值；
 * HLS 是例外 —— 那条链的大小属于转码结果，原文件大小不能代表它。
 */
internal fun preferredDownloadSize(listedSize: Long, linkSize: Long, isHls: Boolean = false): Long =
    if (isHls) {
        // HLS 是转码/分片流，列出的是原文件；这一条链的容量只能信取链结果
        linkSize.takeIf { it > 0 } ?: listedSize.takeIf { it > 0 } ?: 0L
    } else {
        listedSize.takeIf { it > 0 } ?: linkSize.takeIf { it > 0 } ?: 0L
    }

/** 递归展开文件夹后的一个文件；[relativeDir] 是它相对于被下载文件夹的目录层级 */
data class CloudTreeEntry(val file: CloudFile, val relativeDir: String)

/** 递归展开结果；[failedDirs] 记录读取失败、被跳过的目录，不阻断其余文件下载 */
data class CloudTreeResult(
    val entries: List<CloudTreeEntry>,
    val failedDirs: List<String> = emptyList(),
    /** 触到深度 / 文件数上限而提前收尾，UI 需要提示用户只下了一部分 */
    val truncated: Boolean = false,
)

/** 递归展开的保护阈值：防止异常分享把整个应用拖死 */
const val MAX_FOLDER_DEPTH = 24
const val MAX_FOLDER_FILES = 3000

/**
 * 文件夹名来自网盘，可能带路径分隔符或 Windows 非法字符，也可能故意是 `..`。
 * 只保留单段安全名字，禁止路径穿越。
 */
fun sanitizeDirName(raw: String): String {
    val cleaned = raw
        .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
        .replace("..", "_")
        .trim()
        .trimEnd('.', ' ')
        .take(80)
    return if (cleaned.isBlank() || cleaned.trim('_', '.', ' ').isEmpty() ||
        cleaned == "." || cleaned.equals("unnamed", true)
    ) "文件夹" else cleaned
}

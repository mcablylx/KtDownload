package top.mcly99.dl.utils

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.net.URI

/**
 * 剪贴板与链接识别工具。
 *
 * 原先放在 `ui/components/Dialogs.kt`（旧版界面文件），旧界面移除后迁移到这里，
 * 供 `AppNew` 的剪贴板监听与将来的其它入口复用。
 */

/** 支持自动识别的下载协议由 [isSupportedDownloadUrl] 统一维护 */

/**
 * 从系统剪贴板读取一个"看起来像下载链接"的文本。
 *
 * @return 规范化后的链接；剪贴板为空、不是链接或协议不支持时返回 null
 */
fun getClipboardUrl(): String? {
    return try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val contents = clipboard.getContents(null)
        if (contents != null && contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            val text = contents.getTransferData(DataFlavor.stringFlavor) as? String
            text?.trim()?.takeIf { isValidDownloadUrl(it) }
        } else {
            null
        }
    } catch (_: Exception) {
        // 读取剪贴板失败（被占用、无头环境等）时静默忽略
        null
    }
}

/**
 * 判断文本是否是受支持的下载链接（HTTP / HTTPS 直链）。
 *
 * **只有路径末段是已知的下载类文件才认**，光看「协议 + 主机 + 非空路径」是不够的：
 * 那样复制 `https://github.com/u/repo`、复制一篇公众号文章，都会在 2 秒内弹出
 * 「添加下载任务」（P1-12）。用户复制的大多数 URL 是网页，不是文件。
 *
 * 白名单的代价是「没有扩展名的 CDN 直链」不再自动弹出 —— 这个方向的错误无害
 * （用户仍可手动「添加任务」或粘贴到输入框），而误弹是每次复制都会打扰。
 */
fun isValidDownloadUrl(text: String): Boolean {
    if (!isSupportedDownloadUrl(text)) return false
    val path = try {
        // 用 URI 而不是 `URL(String)`：后者在 JDK 20 起被标记过时。能走到这里说明
        // 文本已通过 [isSupportedDownloadUrl]（内部已用 URL 校验过 host），URI 解析失败
        // 只可能是含空格等非法字符，返回 false 即可。
        URI(text.trim()).path
    } catch (_: Exception) {
        return false
    }
    if (path.isBlank()) return false
    // 只看路径末段的扩展名：`a.mp4?token=...` 这种查询串不影响判断
    val name = path.substringAfterLast('/', "")
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext.isNotEmpty() && ext in DOWNLOAD_FILE_EXTENSIONS
}

/**
 * 剪贴板自动弹窗专用的「下载直链」扩展名白名单。
 *
 * 这里比引擎侧的能力判断严格得多，因为两者代价不对称：
 * - 误弹：用户每次复制网页上的图片、文档地址都会被打断，代价高；
 * - 漏弹：用户仍可手动点「添加任务」或把地址粘进输入框，代价低。
 * 因此这里采取「宁可漏弹、不可误弹」。
 *
 * **刻意不含**以下几类，它们在网页浏览中几乎都是「拿来查看」而非「拿来下载」：
 * - 图片：jpg / jpeg / png / gif / webp / bmp / tiff / svg —— 网页插图、头像、图标；
 * - 文档 / 电子书：pdf / epub / mobi / doc / xls / ppt / txt / csv —— 在线预览为主；
 * - 网页脚本：html / htm / php / asp / jsp —— 本身就是网页。
 *
 * 只保留用户复制到剪贴板时基本可以断定「想下载」的大文件类型：
 * 视频、音频、压缩包 / 镜像、安装包、种子与播放列表。
 */
private val DOWNLOAD_FILE_EXTENSIONS = setOf(
    // 视频
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v", "mpg", "mpeg", "rmvb", "m2ts",
    // 音频
    "mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "ape", "wma",
    // 压缩包 / 镜像
    "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "iso", "dmg", "pkg", "img",
    // 安装包
    "exe", "msi", "apk", "ipa", "deb", "rpm", "appimage",
    // 种子 / 播放列表 / 字幕
    "torrent", "m3u8", "m3u", "srt", "ass", "vtt",
)

// ---------------------------------------------------------------- 程序内写入的自登记

/**
 * 最近一次由本应用写入剪贴板的内容。
 *
 * 剪贴板监听每 2 秒读一次系统剪贴板，应用自己「复制分享链接 / 复制下载地址」也会被它读到，
 * 于是用户刚复制完就冒出一个「添加下载任务」（P1-12 的第三个叠加问题）。
 * 写剪贴板一律走 [copyTextToClipboard]，这里记下内容，监听侧遇到相同文本直接跳过。
 *
 * 只记一条：连续复制两次时，前一次已经没有再区分的必要。
 */
@Volatile
private var selfCopiedText: String? = null

/** 统一的「写剪贴板」入口：UI 不要直接操作 `systemClipboard`，否则监听侧无法区分来源 */
fun copyTextToClipboard(text: String) {
    runCatching {
        val selection = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
    }
    selfCopiedText = text.trim()
}

/** 监听侧用：这次读到的内容是不是本应用刚写进去的 */
internal fun isSelfCopied(text: String): Boolean = text.trim() == selfCopiedText

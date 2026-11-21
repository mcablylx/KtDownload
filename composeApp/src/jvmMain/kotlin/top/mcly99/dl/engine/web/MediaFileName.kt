package top.mcly99.dl.engine.web

import top.mcly99.dl.engine.extractFileName
import top.mcly99.dl.engine.sanitizeDownloadFileName

/**
 * 由候选推导下载文件名。
 *
 * 优先用页面上看得见的名称（锚文本 / 标题），因为 URL 末段经常是 `index`、`playlist`、
 * `hd` 这种毫无信息量的东西 —— 直接按 URL 取名，用户下载完根本分不清哪个是第几集。
 *
 * 但名称本身通常没有扩展名（「第 3 集」），所以要从 URL 借一个：
 * - 直链沿用 URL 的扩展名（`.mp4` / `.mkv`）；
 * - **HLS 一律 `.mp4`**，因为合成结果就是 MP4，留着 `.m3u8` 只会让引擎再改一次；
 * - 名称里已经带了同名扩展名（页面写全了）就不重复追加。
 */
fun downloadFileNameFor(candidate: MediaCandidate): String {
    // 已清洗、含扩展名，同时作为兜底
    val urlName = extractFileName(candidate.url)
    val urlExtension = urlName.substringAfterLast('.', "")
    val rawName = candidate.name.takeIf { it.isNotBlank() } ?: urlName
    val base = sanitizeDownloadFileName(rawName, urlName)

    val target = when (candidate.kind) {
        MediaKind.M3u8 -> "mp4"
        else -> urlExtension
    }
    if (target.isBlank()) return base
    if (base.endsWith(".$target", ignoreCase = true)) return base
    // 名称是从 URL 兜底来的（`index.m3u8`）或页面写成了别的扩展名：替换而不是叠加
    if (urlExtension.isNotBlank() && base.endsWith(".$urlExtension", ignoreCase = true)) {
        return base.dropLast(urlExtension.length) + target
    }
    return "$base.$target"
}

package top.mcly99.dl.data.network

import top.mcly99.dl.utils.HostPlatform

/**
 * 一次「新版本可用」的检查结果。
 *
 * [asset] 已按当前平台匹配好，调用方只需下载它，不必再关心 mac/win 差异。
 */
data class UpdateInfo(
    /** Release tag，如 v1.0.1 */
    val version: String,
    /** Release 发布说明（Markdown 原文，可能为 null） */
    val notes: String?,
    /** 当前平台应选中的安装包 */
    val asset: GitHubAsset,
    /** Release 页面地址，供「前往下载」兜底 */
    val releaseUrl: String,
)

/**
 * 从 Release 的 assets 中挑选当前平台的安装包。
 *
 * 命名约定：`KtDownload-<版本>.<ext>`。按平台给出扩展名优先级：
 * - Windows：exe 优先（无需管理员即可运行），其次 msi；
 * - macOS：dmg 优先，其次 pkg；
 * - 其它平台：无可用包，返回 null。
 *
 * 只按**小写扩展名**匹配，避免大小写差异导致漏选；同一扩展名有多个时取第一个
 * （GitHub 返回顺序即上传顺序，通常只有一个）。
 */
fun pickInstaller(assets: List<GitHubAsset>, platform: HostPlatform): GitHubAsset? {
    val extensions = when (platform) {
        HostPlatform.WINDOWS -> listOf("exe", "msi")
        HostPlatform.MACOS -> listOf("dmg", "pkg")
        HostPlatform.UNSUPPORTED -> return null
    }
    for (ext in extensions) {
        assets.firstOrNull { it.name.substringAfterLast('.').lowercase() == ext }
            ?.let { return it }
    }
    return null
}

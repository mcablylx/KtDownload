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
 * 安装包命名约定：`KtDownload-<版本>.<ext>`（版本段可带 `v` 前缀，大小写不敏感）。
 * 同时供 [pickInstaller] 与 [UpdateDownloader] 的落盘文件名校验复用。
 */
internal val INSTALLER_NAME =
    Regex("""^KtDownload-([vV]?[0-9][0-9A-Za-z.+_-]*)\.(exe|msi|dmg|pkg)$""", RegexOption.IGNORE_CASE)

/**
 * 从 Release 的 assets 中挑选当前平台的安装包。
 *
 * 按平台给出扩展名优先级：Windows exe → msi；macOS dmg → pkg；其它平台返回 null。
 *
 * P1-08：只认 `KtDownload-<版本>.<ext>` 命名，避免 Release 里混入的其它 exe/dmg
 * 被「按扩展名取首个」静默下载并执行；传 [version]（Release tag）时还要求版本段一致，
 * tag 与产物名的 `v` 前缀两侧都做规范化（产物 `KtDownload-1.0.3.exe` vs tag `v1.0.3`）。
 */
fun pickInstaller(
    assets: List<GitHubAsset>,
    platform: HostPlatform,
    version: String? = null,
): GitHubAsset? {
    val extensions = when (platform) {
        HostPlatform.WINDOWS -> listOf("exe", "msi")
        HostPlatform.MACOS -> listOf("dmg", "pkg")
        HostPlatform.UNSUPPORTED -> return null
    }
    val expected = version?.trim()?.removePrefix("v")?.removePrefix("V")
    for (ext in extensions) {
        val found = assets.firstOrNull { asset ->
            val match = INSTALLER_NAME.matchEntire(asset.name) ?: return@firstOrNull false
            if (!match.groupValues[2].equals(ext, ignoreCase = true)) return@firstOrNull false
            if (expected == null) return@firstOrNull true
            match.groupValues[1]
                .removePrefix("v").removePrefix("V")
                .equals(expected, ignoreCase = true)
        }
        if (found != null) return found
    }
    return null
}

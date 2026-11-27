package top.mcly99.dl.theme

/**
 * 「自动更新」独立文案表。
 *
 * 不往 [UiStrings] 里加字段：它的构造签名已越过 JVM 255 参数槽，
 * 再加字段会在运行时抛 `ClassFormatError`（见 N-10）。新模块一律单独成表。
 */
data class UpdateStrings(
    val sectionTitle: String,
    val sectionDesc: String,
    val autoCheck: String,
    val autoCheckDesc: String,
    val checkNow: String,
    val checking: String,
    val upToDate: String,
    val newVersionTitle: String,
    val newVersionMessage: (String) -> String,
    val updateNow: String,
    val later: String,
    val cancelDownload: String,
    val openReleasePage: String,
    val downloading: String,
    val downloadFailed: String,
    val openFailed: String,
    /** P1-08：Release 无 sha-256 digest，不允许自动安装 */
    val untrustedRelease: String,
    val updateAvailableBadge: String,
)

fun updateStrings(language: String): UpdateStrings =
    if (language.startsWith("en", ignoreCase = true)) en() else zh()

private fun zh() = UpdateStrings(
    sectionTitle = "自动更新",
    sectionDesc = "启动时检查 GitHub 上的最新版本，发现新版本会在侧边栏提示。",
    autoCheck = "自动检查更新",
    autoCheckDesc = "启动后后台静默检查，失败不会影响使用。",
    checkNow = "检查更新",
    checking = "正在检查…",
    upToDate = "已是最新版本",
    newVersionTitle = "发现新版本",
    newVersionMessage = { "发现新版本 $it，是否下载安装？" },
    updateNow = "立即更新",
    later = "稍后",
    cancelDownload = "取消下载",
    openReleasePage = "前往 Release 页面",
    downloading = "正在下载安装包…",
    downloadFailed = "下载失败，请稍后重试或前往 Release 页面手动下载。",
    openFailed = "无法打开安装包，请在下载目录手动打开。",
    untrustedRelease = "该版本未提供校验摘要（SHA-256），已取消自动安装，请前往 Release 页面手动下载。",
    updateAvailableBadge = "有新版本",
)

private fun en() = UpdateStrings(
    sectionTitle = "Updates",
    sectionDesc = "Check GitHub for the latest version at startup; a badge appears when one is available.",
    autoCheck = "Check for updates automatically",
    autoCheckDesc = "Silent background check at startup; failures never interrupt usage.",
    checkNow = "Check for updates",
    checking = "Checking…",
    upToDate = "You are up to date",
    newVersionTitle = "Update available",
    newVersionMessage = { "Version $it is available. Download and install it now?" },
    updateNow = "Update now",
    later = "Later",
    cancelDownload = "Cancel download",
    openReleasePage = "Open Release page",
    downloading = "Downloading installer…",
    downloadFailed = "Download failed. Try again later or get it from the Release page.",
    openFailed = "Could not open the installer. Please open it from the download folder.",
    untrustedRelease = "This release has no SHA-256 digest; automatic install was cancelled. Please download it from the Release page.",
    updateAvailableBadge = "Update available",
)

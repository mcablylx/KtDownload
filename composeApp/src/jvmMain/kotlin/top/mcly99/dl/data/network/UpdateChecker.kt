package top.mcly99.dl.data.network

import top.mcly99.dl.utils.HostPlatform
import top.mcly99.dl.utils.Version

/**
 * 更新检查：拉取最新正式 Release，判断是否需要更新，并挑好当前平台的安装包。
 *
 * 设计要点：
 * - 只读公开 GitHub API，无需用户配置 Token（有 Token 也只是提高限额）；
 * - 任何失败（网络 / 解析 / 无匹配包）都返回 null，由调用方静默处理，绝不影响主流程；
 * - 版本比较与选包是纯函数（见 Version / pickInstaller），本类只负责编排；
 * - [latestProvider] 以函数注入，便于单元测试不依赖真实网络。
 */
class UpdateChecker(
    private val latestProvider: suspend (owner: String, repo: String) -> GitHubRelease?,
    private val owner: String = "mcablylx",
    private val repo: String = "KtDownload",
    private val platform: HostPlatform = HostPlatform.current(),
) {

    companion object {
        /** 生产环境装配：直接走现成的 GitHubApi.getLatestRelease()。 */
        fun fromApi(
            api: GitHubApi,
            owner: String = "mcablylx",
            repo: String = "KtDownload",
            platform: HostPlatform = HostPlatform.current(),
        ) = UpdateChecker(
            latestProvider = { o, r -> api.getLatestRelease(o, r) },
            owner = owner,
            repo = repo,
            platform = platform,
        )
    }

    /**
     * 检查是否有新版本。
     *
     * @param currentVersion 本地版本，如 "1.0.0"
     * @return 有新版本且找到匹配安装包时返回 [UpdateInfo]，否则 null。
     */
    suspend fun check(currentVersion: String): UpdateInfo? {
        // 不支持的平台直接放弃，避免无谓网络请求
        if (platform == HostPlatform.UNSUPPORTED) return null

        val release = latestProvider(owner, repo) ?: return null
        if (release.tagName.isBlank()) return null
        // 双保险：getLatestRelease 已排除 prerelease/draft，这里再挡一次防止端点行为变化
        if (release.prerelease || release.draft) return null
        if (!Version.isNewer(release.tagName, currentVersion)) return null

        val asset = pickInstaller(release.assets, platform) ?: return null
        return UpdateInfo(
            version = release.tagName,
            notes = release.body,
            asset = asset,
            releaseUrl = "https://github.com/$owner/$repo/releases/tag/${release.tagName}",
        )
    }
}

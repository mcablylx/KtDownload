package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.GitHubApi
import top.mcly99.dl.data.network.GitHubLinkParser
import top.mcly99.dl.data.network.GitHubLinkType
import top.mcly99.dl.data.network.GitHubMirror
import top.mcly99.dl.data.network.GitHubRelease
import top.mcly99.dl.data.network.GitHubRepo
import top.mcly99.dl.data.network.GitHubResponseCache
import top.mcly99.dl.data.network.GitHubTokenStore
import top.mcly99.dl.data.network.GitHubTreeEntry
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.model.ShareFile
import top.mcly99.dl.data.network.model.ShareSession

/**
 * ktdown 自有的 GitHub 浏览胶水层（**不属于** AGPL 移植源码）。
 *
 * 上游把 GitHub 的目录导航写死在 `ResolveViewModel` 里；ktdown 把「仓库树 / Releases /
 * 账号仓库列表」全部投影成 [ShareFile]，于是 [CloudDriveViewModel] 现有的文件列表、
 * 面包屑、搜索、排序、多选、批量取链、文件夹递归下载**一行都不用改**就能服务 GitHub。
 *
 * fid 约定与上游 `ResolveViewModel.loadGitHubDir` 一致（`github:root` / `github:code:<sha>`
 * / `github:releases` / `github:release:<tag>` / `github:account_root` / `github:repo:<fullName>`
 * / `github:file:<path>` / `github:zip` / `github:asset:<url>`），另加两个下游自有值：
 * - `github:auto`：解析入口的根目录，由本次链接类型决定是仓库根还是账号仓库列表；
 * - `github:direct:<url>`：文件直链，映射成「只有一个文件的列表」而非上游的确认弹窗。
 */
class GitHubBrowser(
    private val source: GitHubSource = defaultSource(),
    private val mirrorPrefix: () -> String = { GitHubMirror.activePrefix() },
) {

    /** 上游 [GitHubApi] 的可替换数据源：让 jvmTest 能零网络覆盖目录分发与直链生成。 */
    interface GitHubSource {
        suspend fun getRepo(owner: String, repo: String): GitHubRepo?
        suspend fun getTree(owner: String, repo: String, sha: String): List<GitHubTreeEntry>?
        suspend fun getReleases(owner: String, repo: String, page: Int): List<GitHubRelease>?
        suspend fun getUserType(owner: String): String?
        suspend fun getUserRepos(owner: String, page: Int): List<GitHubRepo>?
        suspend fun getOrgRepos(owner: String, page: Int): List<GitHubRepo>?
    }

    private class ApiSource(private val api: GitHubApi) : GitHubSource {
        override suspend fun getRepo(owner: String, repo: String) = api.getRepo(owner, repo)
        override suspend fun getTree(owner: String, repo: String, sha: String) = api.getTree(owner, repo, sha)
        override suspend fun getReleases(owner: String, repo: String, page: Int) =
            api.getReleases(owner, repo, page)
        override suspend fun getUserType(owner: String) = api.getUserType(owner)
        override suspend fun getUserRepos(owner: String, page: Int) = api.getUserRepos(owner, page)
        override suspend fun getOrgRepos(owner: String, page: Int) = api.getOrgRepos(owner, page)
    }

    private enum class Kind { REPO, ACCOUNT, DIRECT }

    private class Context(
        val kind: Kind,
        val owner: String = "",
        val repo: String = "",
        val repoInfo: GitHubRepo? = null,
        val directUrl: String = "",
        val directName: String = "",
        var ownerType: String? = null,
        val releases: MutableList<GitHubRelease> = mutableListOf(),
        val repos: MutableList<GitHubRepo> = mutableListOf(),
        /** tree sha → 完整仓库内路径；修正 Git Trees API 非 recursive 只返回本层名字的问题 */
        val pathBySha: MutableMap<String, String> = mutableMapOf(),
    )

    private var current: Context? = null

    /** 解析入口：把链接类型收敛成一个伪 [ShareSession]，后续仍走通用的 createSession → listFiles 链路。 */
    suspend fun createSession(link: String): ShareSession {
        val parsed = GitHubLinkParser.parse(link)
            ?: throw IllegalArgumentException("不是可识别的 GitHub 链接")
        GitHubResponseCache.invalidatePrefix("")
        return when (parsed) {
            is GitHubLinkType.Repository -> {
                val info = source.getRepo(parsed.owner, parsed.repo)
                    ?: throw IllegalStateException("无法获取仓库信息：${parsed.owner}/${parsed.repo}")
                val ctx = Context(
                    kind = Kind.REPO,
                    owner = parsed.owner,
                    repo = parsed.repo,
                    repoInfo = info,
                )
                ctx.pathBySha[info.defaultBranch] = ""
                current = ctx
                ShareSession(shareId = info.fullName, stoken = info.defaultBranch, title = info.fullName)
            }
            is GitHubLinkType.Account -> {
                current = Context(kind = Kind.ACCOUNT, owner = parsed.owner)
                ShareSession(shareId = parsed.owner, stoken = "", title = parsed.owner)
            }
            is GitHubLinkType.DirectFile -> {
                current = Context(
                    kind = Kind.DIRECT,
                    directUrl = parsed.url,
                    directName = parsed.fileName,
                )
                ShareSession(shareId = parsed.url, stoken = DIRECT_MARKER, title = parsed.fileName)
            }
        }
    }

    /** 按 fid 分发到对应目录层；与上游 `loadGitHubDir` 同构，但返回普通 [ShareFile] 列表。 */
    suspend fun listFiles(session: ShareSession, dirFid: String): List<ShareFile> {
        val ctx = current ?: throw IllegalStateException("GitHub 会话已失效，请重新解析")
        return when {
            dirFid == AUTO_ROOT -> when (ctx.kind) {
                Kind.REPO -> rootEntries(ctx)
                Kind.ACCOUNT -> accountRepos(ctx, firstPage = true)
                Kind.DIRECT -> listOf(directEntry(ctx))
            }
            dirFid == ROOT -> rootEntries(ctx)
            dirFid.startsWith(CODE_PREFIX) -> codeDir(ctx, dirFid.removePrefix(CODE_PREFIX))
            dirFid == RELEASES -> releases(ctx, firstPage = true)
            dirFid.startsWith(RELEASE_PREFIX) -> releaseAssets(ctx, dirFid.removePrefix(RELEASE_PREFIX))
            dirFid == ACCOUNT_ROOT -> accountRepos(ctx, firstPage = true)
            dirFid.startsWith(MORE_RELEASES) -> releases(
                ctx, firstPage = false, page = dirFid.removePrefix(MORE_RELEASES).toIntOrNull() ?: 2,
            )
            dirFid.startsWith(MORE_ACCOUNT) -> accountRepos(
                ctx, firstPage = false, page = dirFid.removePrefix(MORE_ACCOUNT).toIntOrNull() ?: 2,
            )
            dirFid.startsWith(REPO_PREFIX) -> {
                val fullName = dirFid.removePrefix(REPO_PREFIX)
                val info = source.getRepo(fullName.substringBefore('/'), fullName.substringAfter('/'))
                    ?: throw IllegalStateException("无法打开仓库：$fullName")
                val next = Context(
                    kind = Kind.REPO,
                    owner = info.owner,
                    repo = info.name,
                    repoInfo = info,
                )
                next.pathBySha[info.defaultBranch] = ""
                current = next
                rootEntries(next)
            }
            else -> throw IllegalArgumentException("未知 GitHub 目录：$dirFid")
        }
    }

    /** 根据 fid 生成下载直链；无法识别的目录项直接报错，避免把目录 fid 当 URL 入队。 */
    fun downloadLink(session: ShareSession, file: ShareFile): CloudLink {
        val ctx = current ?: throw IllegalStateException("GitHub 会话已失效，请重新解析")
        val info = ctx.repoInfo
        val rawUrl = when {
            file.fid == ZIP ->
                "https://codeload.github.com/${info!!.owner}/${info.name}/zip/refs/heads/${info.defaultBranch}"
            file.fid.startsWith(FILE_PREFIX) -> {
                val path = file.fid.removePrefix(FILE_PREFIX)
                "https://raw.githubusercontent.com/${info!!.owner}/${info.name}/${info.defaultBranch}/${encodePath(path)}"
            }
            file.fid.startsWith(ASSET_PREFIX) -> file.fid.removePrefix(ASSET_PREFIX)
            file.fid.startsWith(DIRECT_PREFIX) -> file.fid.removePrefix(DIRECT_PREFIX)
            else -> throw IllegalArgumentException("该条目不是可下载文件：${file.fname}")
        }
        val prefix = mirrorPrefix()
        return CloudLink(
            url = if (prefix.isBlank()) rawUrl else prefix + rawUrl,
            filename = file.fname,
            size = file.fsize.coerceAtLeast(0),
            cookie = "",
            referer = "https://github.com/",
            isHls = false,
            platform = SharePlatform.GITHUB,
        )
    }

    fun reset() {
        current = null
    }

    // ---------------------------------------------------------------- 目录层

    private fun rootEntries(ctx: Context): List<ShareFile> {
        val info = ctx.repoInfo ?: throw IllegalStateException("仓库信息缺失")
        return listOf(
            ShareFile(
                fid = CODE_PREFIX + info.defaultBranch,
                fname = "代码",
                fsize = -1,
                isdir = true,
                pdirFid = "",
                fidToken = "",
                modifyTime = info.pushedAt.orEmpty(),
            ),
            ShareFile(
                fid = RELEASES,
                fname = "Releases",
                fsize = -1,
                isdir = true,
                pdirFid = "",
                fidToken = "",
            ),
        )
    }

    private suspend fun codeDir(ctx: Context, sha: String): List<ShareFile> {
        val info = ctx.repoInfo ?: throw IllegalStateException("仓库信息缺失")
        val entries = source.getTree(info.owner, info.name, sha)
            ?: throw IllegalStateException("加载代码目录失败")
        val prefix = ctx.pathBySha[sha].orEmpty()
        val files = mutableListOf<ShareFile>()
        if (sha == info.defaultBranch) {
            files += ShareFile(
                fid = ZIP,
                fname = "下载完整源码 ZIP",
                fsize = -1,
                isdir = false,
                pdirFid = "",
                fidToken = "",
                modifyTime = info.pushedAt.orEmpty(),
            )
        }
        for (e in entries) {
            val name = e.path.substringAfterLast('/')
            // 下游加固：Git Trees API 不带 recursive 时 path 只是本层名字，
            // 直接用它拼 raw 链接会在二级以上子目录里丢掉父路径。
            val fullPath = if (prefix.isEmpty()) e.path else "$prefix/${e.path}"
            if (e.type == "tree") {
                ctx.pathBySha[e.sha] = fullPath
                files += ShareFile(
                    fid = CODE_PREFIX + e.sha,
                    fname = name,
                    fsize = -1,
                    isdir = true,
                    pdirFid = "",
                    fidToken = "",
                )
            } else {
                files += ShareFile(
                    fid = FILE_PREFIX + fullPath,
                    fname = name,
                    fsize = e.size ?: -1,
                    isdir = false,
                    pdirFid = "",
                    fidToken = "",
                )
            }
        }
        return files
    }

    private suspend fun releases(ctx: Context, firstPage: Boolean, page: Int = 1): List<ShareFile> {
        val info = ctx.repoInfo ?: throw IllegalStateException("仓库信息缺失")
        if (firstPage) ctx.releases.clear()
        val batch = source.getReleases(info.owner, info.name, page)
        if (batch == null && page == 1) throw IllegalStateException("加载 Releases 失败")
        if (batch != null) {
            val seen = ctx.releases.mapTo(HashSet()) { it.tagName }
            ctx.releases += batch.filter { seen.add(it.tagName) }
        }
        val files = ctx.releases
            // 草稿不可公开下载；预发布仍可下载，名称上标注
            .filterNot { it.draft }
            .mapTo(mutableListOf()) { rel ->
                ShareFile(
                    fid = RELEASE_PREFIX + rel.tagName,
                    fname = buildString {
                        append(rel.name?.takeIf { it.isNotBlank() } ?: rel.tagName)
                        if (rel.prerelease) append("（预发布）")
                    },
                    fsize = -1,
                    isdir = true,
                    pdirFid = "",
                    fidToken = "",
                    modifyTime = rel.publishedAt.orEmpty(),
                )
            }
        if (batch != null && batch.size == 100) {
            files += moreEntry(MORE_RELEASES + (page + 1))
        }
        return files
    }

    private fun releaseAssets(ctx: Context, tag: String): List<ShareFile> {
        val rel = ctx.releases.firstOrNull { it.tagName == tag }
            ?: throw IllegalStateException("未找到 Release：$tag")
        return rel.assets.map { a ->
            ShareFile(
                fid = ASSET_PREFIX + a.downloadUrl,
                fname = a.name,
                fsize = a.size,
                isdir = false,
                pdirFid = "",
                fidToken = "",
                modifyTime = a.updatedAt.orEmpty(),
            )
        }
    }

    private suspend fun accountRepos(ctx: Context, firstPage: Boolean, page: Int = 1): List<ShareFile> {
        if (firstPage) {
            ctx.repos.clear()
            ctx.ownerType = null
        }
        if (ctx.ownerType == null) ctx.ownerType = source.getUserType(ctx.owner)
        var batch = if (ctx.ownerType == "Organization") {
            source.getOrgRepos(ctx.owner, page)
        } else {
            source.getUserRepos(ctx.owner, page)
        }
        if (batch == null && ctx.ownerType != "Organization") {
            batch = source.getOrgRepos(ctx.owner, page)
        }
        if (batch == null && page == 1) throw IllegalStateException("无法获取 ${ctx.owner} 的仓库列表")
        if (batch != null) {
            val seen = ctx.repos.mapTo(HashSet()) { it.fullName }
            ctx.repos += batch.filter { seen.add(it.fullName) }
        }
        val files = ctx.repos.mapTo(mutableListOf()) { r ->
            ShareFile(
                fid = REPO_PREFIX + r.fullName,
                fname = r.name,
                fsize = -1,
                isdir = true,
                pdirFid = "",
                fidToken = "",
                modifyTime = r.updatedAt.orEmpty(),
            )
        }
        if (batch != null && batch.size == 100) {
            files += moreEntry(MORE_ACCOUNT + (page + 1))
        }
        return files
    }

    private fun directEntry(ctx: Context) = ShareFile(
        fid = DIRECT_PREFIX + ctx.directUrl,
        fname = ctx.directName,
        fsize = -1,
        isdir = false,
        pdirFid = "",
        fidToken = "",
    )

    private fun moreEntry(fid: String) = ShareFile(
        fid = fid,
        fname = "加载更多",
        fsize = -1,
        isdir = true,
        pdirFid = "",
        fidToken = "",
    )

    /** 逐段 URL 编码（保留 `/`）；文件名含 `#`/`?`/空格/中文时避免 URL 被截断。 */
    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    companion object {
        const val AUTO_ROOT = "github:auto"
        /** Session.stoken 标记：本次解析结果是「只有一个文件的直链列表」 */
        const val DIRECT_MARKER = "direct"
        private const val ROOT = "github:root"
        private const val CODE_PREFIX = "github:code:"
        private const val RELEASES = "github:releases"
        private const val RELEASE_PREFIX = "github:release:"
        private const val ACCOUNT_ROOT = "github:account_root"
        private const val MORE_RELEASES = "github:more_rel:"
        private const val MORE_ACCOUNT = "github:more_acct:"
        private const val REPO_PREFIX = "github:repo:"
        private const val FILE_PREFIX = "github:file:"
        private const val ASSET_PREFIX = "github:asset:"
        private const val DIRECT_PREFIX = "github:direct:"
        private const val ZIP = "github:zip"

        private fun defaultSource(): GitHubSource = ApiSource(
            GitHubApi(tokenProvider = { GitHubTokenStore.getToken() }).also { api ->
                // 无效 Token 会让 GitHub 对**所有**请求（含公开仓库）返回 401，
                // 必须清掉才能恢复匿名访问；同时清缓存，避免 1 分钟失败哨兵继续挡路。
                api.onUnauthorized = {
                    GitHubTokenStore.setToken(null)
                    GitHubResponseCache.clear()
                }
            }
        )
    }
}

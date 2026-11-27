package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.engine.sanitizeCredentialForHeaders
import top.mcly99.dl.data.db.XunleiAccountEntity
import top.mcly99.dl.data.network.BaiduApi
import top.mcly99.dl.data.network.C139Api
import top.mcly99.dl.data.network.C139Constants
import top.mcly99.dl.data.network.GuangYaApi
import top.mcly99.dl.data.network.GuangYaDevice
import top.mcly99.dl.data.network.GitHubLinkParser
import top.mcly99.dl.data.network.GitHubMirror
import top.mcly99.dl.data.network.HttpClients
import top.mcly99.dl.data.network.ILanzouApi
import top.mcly99.dl.data.network.LanzouApi
import top.mcly99.dl.data.network.Pan115Api
import top.mcly99.dl.data.network.Pan115Constants
import top.mcly99.dl.data.network.Pan123Api
import top.mcly99.dl.data.network.QuarkApi
import top.mcly99.dl.data.network.QuarkConstants
import top.mcly99.dl.data.network.ShareLinkParser
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.StarSeaApi
import top.mcly99.dl.data.network.UCApi
import top.mcly99.dl.data.network.UCConstants
import top.mcly99.dl.data.network.XunleiApi
import top.mcly99.dl.data.network.XunleiKouling
import top.mcly99.dl.data.network.XunleiWebCredential
import top.mcly99.dl.data.network.WebCaptchaInit
import top.mcly99.dl.data.network.model.DownloadLink
import top.mcly99.dl.data.network.model.ShareFile
import top.mcly99.dl.data.network.model.ShareSession
import top.mcly99.dl.data.repository.BaiduResolveRepository
import top.mcly99.dl.data.repository.C139ResolveRepository
import top.mcly99.dl.data.repository.GuangYaResolveRepository
import top.mcly99.dl.data.repository.ILanzouAccountRepository
import top.mcly99.dl.data.repository.ILanzouResolveRepository
import top.mcly99.dl.data.repository.LanzouResolveRepository
import top.mcly99.dl.data.repository.Pan115ResolveRepository
import top.mcly99.dl.data.repository.Pan123ResolveRepository
import top.mcly99.dl.data.repository.QuarkResolveRepository
import top.mcly99.dl.data.repository.ShareResolveRepository
import top.mcly99.dl.data.repository.UCResolveRepository
import top.mcly99.dl.data.repository.XunleiResolveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap

/** 按「平台 + 凭证」缓存的 Repository 上限；账号轮换场景下防止长期运行无界增长 */
private const val MAX_CACHED_REPOSITORIES = 64

/**
 * 「云盘」工具对解析器的调用契约。
 *
 * 解析能力来自移植的 YunX-Desktop（现位于 `top.mcly99.dl.data.*`，AGPL-3.0），这里只负责
 * 把十套平台差异收敛成 ktdown 自己需要的四个动作，ViewModel 不直接依赖上游类。
 */
interface CloudDriveApi {
    fun parse(text: String): ParsedShareRef?

    /**
     * 中文口令（迅雷 / 星海）→ 真实分享链接；调用方拿到结果后需重新 [parse]。
     *
     * 返回 **null** 表示「这段文本不像可解析的口令」——调用方应直接报「无效链接」，
     * 且实现方不得发起任何网络请求。[Result.failure] 才代表口令服务确实尝试过但失败，
     * 其 message 可直接展示给用户。默认 null 让测试替身无需关心这个非核心能力。
     *
     * 上游把这段逻辑直接写在 `ResolveViewModel.startResolve` 里；ktdown 收敛到 glue 层，
     * 使 ViewModel 不依赖任何上游口令类（D-19 / D-20）。
     */
    suspend fun resolveCommand(text: String): Result<String>? = null

    /** 从分享链接/文案里抽出提取码，用于顶栏输入框自动填充 */
    fun extractPasscode(text: String): String?

    fun platformName(platform: SharePlatform): String

    fun rootDirFid(platform: SharePlatform): String

    suspend fun createSession(link: String, passcode: String?, credential: String, platform: SharePlatform): ShareSession

    suspend fun listFiles(
        session: ShareSession,
        dirFid: String,
        credential: String,
        platform: SharePlatform,
    ): List<ShareFile>

    suspend fun downloadLink(
        session: ShareSession,
        file: ShareFile,
        credential: String,
        platform: SharePlatform,
    ): CloudLink

    /**
     * 「网盘列表」卡片上的空间配额（对齐 YunX 网盘标签页的 `QuotaInlineBar`）。
     * 未配置凭证、平台无配额接口或请求失败时返回 null，绝不抛异常打断 UI。
     * 默认 null 让测试用的假实现无需关心这个非核心能力。
     */
    suspend fun quota(platform: SharePlatform, credential: String): CloudQuota? = null

    /**
     * 下载任务进入终态后，删掉平台在用户网盘里建的临时转存目录。
     *
     * 夸克 / 115 / 光鸭的转存兜底会建 `tr_*` 目录，[CloudLink.cleanupDirFid] 把它带回来；
     * 不清理就会永久污染用户网盘并累积占用空间。默认空实现让测试替身无需关心。
     * 实现方必须吞掉异常：清理失败不应该影响已经下载完的文件。
     */
    suspend fun cleanupTempDir(platform: SharePlatform, dirFid: String, credential: String) {}

    /**
     * 注册「凭证被自动刷新后写回」的落点（迅雷的 access_token 会过期，刷新出的新 token
     * 必须同步到网盘列表，否则卡片上还是旧 token，下次解析又判过期）。
     *
     * P2-10：sink 的第二个参数是**本次请求实际使用的旧凭证**。多账号并发时不能靠
     * ViewModel 的「当前活跃凭证」共享字段去猜账号 —— 两个请求交错就会把 B 刷新出的
     * token 写到 A。由解析层把请求凭证原样带出来，账号池才能精确按旧值定位。
     *
     * 默认空实现：不关心凭证生命周期的平台（以及测试替身）不用实现它。
     */
    fun bindCredentialSink(sink: (SharePlatform, String, String) -> Unit) {}

    /**
     * GitHub 下载镜像（D-18）的可选值：`direct` / `auto` / 具体镜像前缀。
     * 默认空列表让测试替身与未启用 GitHub 的实现不展示设置行。
     */
    fun githubMirrorOptions(): List<String> = emptyList()

    /** 当前生效的镜像选择值。 */
    fun githubMirrorValue(): String = ""

    /** 切换镜像；`auto` 会触发一次异步并发测速。 */
    fun selectGithubMirror(value: String) {}

    /**
     * 递归展开 [dirFid] 下**所有层级**的子目录，返回扁平文件列表并保留目录层级。
     *
     * 这是「下载整个文件夹」的基础：网盘 API 每次只能列一层，必须逐层下发。
     * 默认实现委托给 [collectTree]（只组合 [listFiles]），因此任何平台实现都自动
     * 获得该能力；单个子目录读取失败只记入 [CloudTreeResult.failedDirs]，不阻断其余文件。
     *
     * @param relativeDir 被下载文件夹自身的名字，作为所有条目的路径前缀
     */
    suspend fun collectFiles(
        session: ShareSession,
        dirFid: String,
        credential: String,
        platform: SharePlatform,
        relativeDir: String = "",
    ): CloudTreeResult = collectTree(session, dirFid, credential, platform, relativeDir)
}

/**
 * 递归展开目录树的通用实现，只依赖 [CloudDriveApi.listFiles]。
 *
 * 单独抽成扩展函数有两个好处：测试可以只替换 `listFiles` 就覆盖完整递归逻辑；
 * 类委托（`by delegate`）的实现也能显式调用它，避免委托到真实网络实现。
 */
suspend fun CloudDriveApi.collectTree(
    session: ShareSession,
    dirFid: String,
    credential: String,
    platform: SharePlatform,
    relativeDir: String = "",
): CloudTreeResult {
    val entries = mutableListOf<CloudTreeEntry>()
    val failedDirs = mutableListOf<String>()
    val visited = HashSet<String>()
    var truncated = false

    suspend fun walk(fid: String, prefix: String, depth: Int) {
        if (depth > MAX_FOLDER_DEPTH) {
            truncated = true
            return
        }
        if (entries.size >= MAX_FOLDER_FILES) {
            truncated = true
            return
        }
        // 同一 fid 只走一次：服务端偶发返回自引用目录时避免死循环
        if (!visited.add(fid)) return
        val children = try {
            listFiles(session, fid, credential, platform)
        } catch (e: Exception) {
            failedDirs += prefix.ifBlank { "根目录" }
            return
        }
        for (child in children) {
            if (entries.size >= MAX_FOLDER_FILES) {
                truncated = true
                return
            }
            if (child.isdir) {
                val name = sanitizeDirName(child.fname)
                walk(child.fid, if (prefix.isBlank()) name else "$prefix/$name", depth + 1)
            } else {
                entries += CloudTreeEntry(child.toCloudFile(), prefix)
            }
        }
    }

    walk(dirFid, relativeDir.trim().trim('/'), 0)
    return CloudTreeResult(entries, failedDirs, truncated)
}

/** 上游 ParsedShare 的轻量投影，避免 UI 层依赖 AGPL 命名空间 */
data class ParsedShareRef(val shareId: String, val passcode: String?, val platform: SharePlatform)

/** GitHub 下载镜像设置项的取值；UI 只认这两个哨兵，其余一律当作具体镜像前缀。 */
object GitHubMirrorChoice {
    const val DIRECT = "direct"
    const val AUTO = "auto"
}

/**
 * 云盘解析器实现。
 *
 * 平台 Repository 按「平台 + 凭证」缓存：`LanzouResolveRepository` 会在内存里缓存分享页，
 * 每次重建会丢掉这个缓存，导致进子目录时报「会话已失效」；按 key 缓存既保住这份缓存，
 * 又让不同账号的并发请求各自持有**不可变凭证快照**（P2-10）。
 *
 * 旧实现把凭证放在服务级 `@Volatile var credential` 里，由 `useCredential` 在每次
 * `createSession` / `listFiles` / `downloadLink` 开头覆盖，而 Repository 构造时的
 * tokenProvider / accountProvider 闭包都读它：并发「浏览 + 批量取链」会串号。
 * 现在凭证只走方法参数，不共享可变字段。
 */
class CloudDriveService(
    /**
     * GitHub 浏览胶水层可注入：测试用 FakeSource 覆盖目录分发与直链生成，零真实网络。
     * 上游把这段导航状态写在 ResolveViewModel 里；ktdown 收敛成独立对象（D-18）。
     */
    val githubBrowser: GitHubBrowser = GitHubBrowser(),
    /**
     * 取链 Repository 的测试注入点（D-48）：生产为 null，走 [createRepository]。
     * 只有替换掉这一层，「游客 / 免转存 / 转存回落」三条路径才能不发真实网络请求地断言。
     */
    private val repositoryOverride: ((SharePlatform) -> ShareResolveRepository)? = null,
) : CloudDriveApi {

    /** 刷新出的新凭证要写回哪儿（由 ViewModel 接到网盘列表的账号卡上） */
    @Volatile
    private var credentialSink: ((SharePlatform, String, String) -> Unit)? = null

    /** P2-10：缓存键带上凭证，两个账号的并发请求不会共用同一个 Repository 实例 */
    private data class RepositoryKey(val platform: SharePlatform, val credential: String)

    private val repositories = ConcurrentHashMap<RepositoryKey, ShareResolveRepository>()
    private val repositoryOrder = java.util.concurrent.ConcurrentLinkedQueue<RepositoryKey>()
    private val repositoryLock = Any()

    override fun bindCredentialSink(sink: (SharePlatform, String, String) -> Unit) {
        credentialSink = sink
    }

    /**
     * 用 refresh_token 换新 access_token（迅雷）。
     *
     * 网页登录拿到的 token 只能用**网页** client_id 刷新（[XunleiWebCredential.AUTH_TYPE]），
     * 用 App 通道那一套会被直接拒，表现就是「刚登录就过期」。
     * 刷新成功后立即写回账号池：不写回的话，账号卡片上仍是旧 token，下一次请求又会
     * 被判过期。P2-10：写回时把**本次请求用的旧凭证**一并带出，账号池按旧值定位账号；
     * 不再经服务级共享字段中转，避免并发时串号。
     *
     * @return 新 (access_token, refresh_token)；没有 refresh_token 或服务端拒绝时 null
     */
    private suspend fun refreshXunleiToken(
        api: XunleiApi,
        deviceIdHint: String,
        requestCredential: String,
    ): Pair<String, String>? {
        val current = XunleiDriveCredential.parse(requestCredential) ?: return null
        if (current.refreshToken.isBlank()) return null
        val device = deviceIdHint.ifBlank { current.deviceId }
        val fresh = api.refreshToken(current.refreshToken, device, XunleiWebCredential.AUTH_TYPE) ?: return null
        val next = current.copy(
            accessToken = fresh.first,
            // 服务端偶尔只回 access_token：那就沿用旧 refresh_token，别把它清空
            refreshToken = fresh.second.ifBlank { current.refreshToken },
        )
        val json = next.toJson()
        // P2-10：不再写 `credential = json`。旧凭证由调用链显式传入，刷新结果只经 sink 落库。
        credentialSink?.invoke(SharePlatform.XUNLEI, requestCredential, json)
        return fresh.first to next.refreshToken
    }

    /**
     * 一次性注入固定代理（保留给只想给云盘链路指定代理的场景）。
     * 应用主路径走 [HttpClients.setProxyProvider] 的现取 lambda，这样「跟随系统」也能生效；
     * 这里原样传入 [Proxy]，SOCKS 类型不会再被拆成 host/port 时丢失。
     */
    fun applyProxy(proxy: Proxy?) {
        HttpClients.setProxy(proxy)
    }

    override fun parse(text: String): ParsedShareRef? {
        ShareLinkParser.parse(text)?.let { return ParsedShareRef(it.shareId, it.pwd, it.platform) }
        // D-18：GitHub 链接走独立的浏览链路，但仍从同一个解析入口进入，
        // 这样口令兜底、结果区展示、返回输入态等通用逻辑完全不用分叉。
        GitHubLinkParser.parse(text)?.let {
            return ParsedShareRef(text.trim(), null, SharePlatform.GITHUB)
        }
        return null
    }

    /**
     * 中文口令兜底：既不是分享链接、也不是 GitHub 链接时，**先迅雷、迅雷没给出可用链接再问星海**
     * （对齐上游 `ResolveViewModel.startResolve:902-925`）。两个服务共用同一段原文，成功即止；
     * 都失败时把后一次（星海）的原因带出去，而不是只丢一句「无法识别」。
     *
     * ★ 下游加固：上游 `StarSeaApi.looksLikeCommand` 只拦 URL 与超长文本，任何一段纯英文
     *   文案都会被发到第三方服务。这里额外要求文本含汉字（中文口令必然含汉字，与
     *   `XunleiKouling.looksLikeKouling` 的判定一致），避免把无关正文当口令外发。
     */
    override suspend fun resolveCommand(text: String): Result<String>? = withContext(Dispatchers.IO) {
        if (parse(text) != null) return@withContext Result.success(text)
        val hasHan = text.any { it.code in 0x4E00..0x9FFF }
        val tryXunlei = XunleiKouling.looksLikeKouling(text)
        val tryStarSea = hasHan && StarSeaApi.looksLikeCommand(text)
        if (!tryXunlei && !tryStarSea) return@withContext null

        var effectiveLink = text
        var lastError: Throwable? = null
        if (tryXunlei) {
            // 口令解析不需要登录态：单独构造一个不带凭证 provider 的仓库，避免污染 repositories 缓存
            val repo = XunleiResolveRepository(
                api = XunleiApi(),
                accountProvider = { null },
                deviceIdProvider = { null },
                captchaProvider = { null },
            )
            repo.resolveKouling(XunleiKouling.normalize(text))
                .onSuccess { effectiveLink = it }
                .onFailure { lastError = it }
        }
        if (parse(effectiveLink) == null && tryStarSea) {
            StarSeaApi.resolveToShareLink(text)
                .onSuccess { effectiveLink = it }
                .onFailure { lastError = it }
        }
        if (parse(effectiveLink) != null) {
            Result.success(effectiveLink)
        } else {
            Result.failure(lastError ?: IllegalStateException("无法识别中文口令"))
        }
    }

    override fun githubMirrorOptions(): List<String> =
        listOf(GitHubMirrorChoice.DIRECT, GitHubMirrorChoice.AUTO) + GitHubMirror.PRESETS

    override fun githubMirrorValue(): String = when {
        !GitHubMirror.isEnabled() -> GitHubMirrorChoice.DIRECT
        GitHubMirror.fixedPrefix().isBlank() -> GitHubMirrorChoice.AUTO
        else -> GitHubMirror.fixedPrefix()
    }

    override fun selectGithubMirror(value: String) {
        when (value) {
            GitHubMirrorChoice.DIRECT -> {
                GitHubMirror.setEnabled(false)
                GitHubMirror.setPrefix(null)
            }
            GitHubMirrorChoice.AUTO -> {
                GitHubMirror.setEnabled(true)
                GitHubMirror.setPrefix(null)
                // 立刻测一轮：AUTO 在测出可用镜像前回落直连，测速完成才会切换生效
                GitHubMirror.refresh()
            }
            else -> {
                GitHubMirror.setEnabled(true)
                GitHubMirror.setPrefix(value)
            }
        }
    }

    override fun extractPasscode(text: String): String? {
        for (regex in URL_PASSCODE_PATTERNS) {
            regex.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return TEXT_PASSCODE_PATTERN.find(text)?.groupValues?.getOrNull(1)
    }

    override fun platformName(platform: SharePlatform): String = when (platform) {
        SharePlatform.QUARK -> "夸克网盘"
        SharePlatform.UC -> "UC 网盘"
        SharePlatform.XUNLEI -> "迅雷网盘"
        SharePlatform.BAIDU -> "百度网盘"
        SharePlatform.C139 -> "中国移动云盘"
        SharePlatform.PAN123 -> "123 云盘"
        SharePlatform.PAN115 -> "115 网盘"
        SharePlatform.GUANGYA -> "光鸭云盘"
        SharePlatform.ILANZOU -> "蓝奏云优享版"
        SharePlatform.LANZOU -> "蓝奏云"
        SharePlatform.GITHUB -> "GitHub"
    }

    override fun rootDirFid(platform: SharePlatform): String = when (platform) {
        SharePlatform.UC -> UCConstants.DEFAULT_PDIR_FID
        SharePlatform.XUNLEI -> "0"
        SharePlatform.BAIDU -> ""
        SharePlatform.C139 -> "0"
        SharePlatform.PAN123 -> "0"
        SharePlatform.PAN115 -> Pan115Constants.ROOT_CID
        SharePlatform.GUANGYA -> ""
        SharePlatform.ILANZOU -> ""
        SharePlatform.LANZOU -> ""
        // GitHubBrowser 按链接类型自行决定真正根节点（仓库根 / 账号仓库列表 / 单文件）
        SharePlatform.GITHUB -> GitHubBrowser.AUTO_ROOT
        else -> QuarkConstants.DEFAULT_PDIR_FID
    }

    override suspend fun createSession(
        link: String,
        passcode: String?,
        credential: String,
        platform: SharePlatform,
    ): ShareSession = withContext(Dispatchers.IO) {
        if (platform == SharePlatform.GITHUB) {
            githubBrowser.createSession(link)
        } else {
            repository(platform, credential).createSession(link, passcode, credential).getOrThrow()
        }
    }

    override suspend fun listFiles(
        session: ShareSession,
        dirFid: String,
        credential: String,
        platform: SharePlatform,
    ): List<ShareFile> = withContext(Dispatchers.IO) {
        if (platform == SharePlatform.GITHUB) {
            githubBrowser.listFiles(session, dirFid)
        } else {
            repository(platform, credential).listFiles(session, dirFid, credential).getOrThrow()
        }
    }

    /**
     * 取直链：与上游一致的三条路径。
     * 1. 未填凭证 → 游客取链（夸克/UC/蓝奏等匿名可用，夸克约 50MB 上限）；
     * 2. 有凭证 → 先「免转存取链」（默认实现会回落到普通取链），不在网盘留临时目录；
     * 3. 夸克免转存失败（个别分享类型服务端要求先转存）→ 回退「建唯一临时目录 → 转存 → 取链」。
     */
    override suspend fun downloadLink(
        session: ShareSession,
        file: ShareFile,
        credential: String,
        platform: SharePlatform,
    ): CloudLink = withContext(Dispatchers.IO) {
        if (platform == SharePlatform.GITHUB) {
            // GitHub 不需要登录态取链：文件 fid 本身就是 raw / codeload / asset URL，
            // 走网盘 Repository 只会得到一张无意义的临时转存目录。
            return@withContext githubBrowser.downloadLink(session, file)
        }
        val repo = repository(platform, credential)
        val link: DownloadLink = if (credential.isBlank()) {
            repo.getGuestShareDownloadLink(session, file).getOrThrow()
        } else {
            val noSave = repo.getShareDownloadLinkWithoutSave(session, file, credential)
            // 回落范围由 Repository 自己声明，而不是在上层硬编码「夸克 / 百度」白名单：
            // 覆写过免转存的平台才有第二次机会；对走默认委托的平台，再调一次
            // getShareDownloadLink 只是把同一个失败请求重发一遍（甚至重复转存）。
            if (noSave.isSuccess || !repo.supportsSeparateNoSaveLink) {
                noSave.getOrThrow()
            } else {
                repo.getShareDownloadLink(session, file, credential).getOrElse { fallback ->
                    throw IllegalStateException(
                        "${noSave.exceptionOrNull()?.message ?: "免转存取链失败"}；" +
                            "转存取链也失败：${fallback.message}",
                    )
                }
            }
        }
        // 下载这次请求的头单独算：取链拿到的只是一个签名 URL，真正的防盗链 / 限速 /
        // 会话校验都发生在下载请求上。逐平台差异集中收在 CloudDownloadHeaders 里，
        // 这里只负责搬运，避免「取链接口对了、下载还是 403」。详见 D-P0 修复说明。
        val headers = CloudDownloadHeaders.of(
            platform = platform,
            credential = credential,
            guestCookie = link.guestCookie,
            downloadUrl = link.downloadUrl,
        )
        CloudLink(
            url = link.downloadUrl,
            filename = link.filename.ifBlank { file.fname },
            // 列表里的 fsize 是用户看到的大小，优先于取链响应：后者可能只是占位值
            size = preferredDownloadSize(file.fsize, link.size, link.isHls),
            // 迅雷的「凭证」是 access_token/refresh_token 的 JSON，塞进 Cookie 头毫无意义
            // （直链自带签名参数），策略层已经把它排除；其余平台按各自的规则给。
            cookie = headers.cookie.orEmpty(),
            referer = headers.referer.orEmpty(),
            userAgent = headers.userAgent,
            origin = headers.origin,
            secChUa = headers.secChUa,
            isHls = link.isHls,
            cleanupDirFid = link.cleanupDirFid,
            // 下载完成后按这个平台找到对应 Repository 去清理临时转存目录
            platform = platform,
        )
    }

    /**
     * 空间配额：覆盖移植层提供接口的 9 个平台。
     *
     * 前 6 个只要一份 Cookie / token；迅雷、光鸭、蓝奏优享版还需要设备标识或 uuid，
     * 由 [CloudCredentialFields] 从结构化凭证里取，字段不齐就安静跳过（返回 null，不报错）。
     * 蓝奏云官方没有配额接口，不查。
     */
    override suspend fun quota(platform: SharePlatform, credential: String): CloudQuota? =
        withContext(Dispatchers.IO) {
            if (credential.isBlank() || !CloudDriveCatalog.supportsCredential(platform)) return@withContext null
            val info = runCatching {
                when (platform) {
                    SharePlatform.QUARK -> QuarkApi().getQuota(credential)
                    SharePlatform.UC -> UCApi().getQuota(credential)
                    SharePlatform.BAIDU -> BaiduApi().getQuota(credential)
                    SharePlatform.C139 -> C139Api().getQuota(credential)
                    SharePlatform.PAN123 -> Pan123Api().getQuota(credential)
                    SharePlatform.PAN115 -> Pan115Api().getQuota(credential)
                    SharePlatform.XUNLEI -> {
                        val cred = XunleiDriveCredential.parse(credential)?.takeIf { it.isUsable }
                        cred?.let { XunleiApi().getQuota(it.accessToken, it.deviceId, it.captchaToken) }
                    }
                    SharePlatform.GUANGYA -> {
                        val f = CloudCredentialFields.parse(credential)
                        if (f.accessToken.isBlank() || f.deviceId.isBlank() || f.deviceSign.isBlank()) null
                        else GuangYaApi().getQuota(f.accessToken, GuangYaDevice(f.deviceId, f.deviceSign))
                    }
                    SharePlatform.ILANZOU -> {
                        val f = CloudCredentialFields.parse(credential)
                        if (f.appToken.isBlank() || f.uuid.isBlank()) null
                        else ILanzouApi().getQuota(f.appToken, f.uuid)
                    }
                    else -> null
                }
            }.getOrNull() ?: return@withContext null
            if (info.total <= 0L) return@withContext null
            CloudQuota(info.used, info.total)
        }

    override suspend fun cleanupTempDir(
        platform: SharePlatform,
        dirFid: String,
        credential: String,
    ) {
        if (dirFid.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching { repository(platform, credential).cleanupTempDir(dirFid, credential) }
                .onFailure { e ->
                    top.mcly99.dl.utils.AppLogger.warn(
                        "CloudDriveService",
                        "清理 ${platform.name} 临时转存目录失败（dirFid=$dirFid）: ${e.message}", e,
                    )
                }
        }
    }

    /**
     * P4「我的网盘」复用同一批 Repository 来做「自己文件取直链 / 分享转存」。
     *
     * P2-10：凭证**按请求显式传入**，缓存键是「平台 + 凭证」，构造闭包捕获的是不可变
     * 快照，不再有服务级可变字段。不同账号并发取链各用各的实例，从根上消除串号。
     * 仅同模块可见，不改变 [CloudDriveApi] 契约。
     */
    internal fun repository(platform: SharePlatform, credential: String): ShareResolveRepository {
        // 入站清洗一次：Cookie 可能带中文 / 控制字符，OkHttp 的 header() 会直接抛异常；
        // 迅雷的结构化 JSON 凭证由 sanitizeCredentialForHeaders 放行，不会被编码坏。
        val key = RepositoryKey(platform, sanitizeCredentialForHeaders(credential).trim())
        repositories[key]?.let { return it }
        return synchronized(repositoryLock) {
            repositories[key]?.let { return@synchronized it }
            val created = repositoryOverride?.invoke(platform)
                ?: createRepository(platform, key.credential)
            repositories[key] = created
            repositoryOrder.add(key)
            // 账号轮换会累积 key；按插入顺序淘汰最旧的，避免长期运行内存无界增长。
            // 正常场景每个平台只有「游客 + 主账号 + 少量轮换账号」几个 key，不会触发。
            while (repositoryOrder.size > MAX_CACHED_REPOSITORIES) {
                val oldest = repositoryOrder.poll() ?: break
                if (oldest != key) repositories.remove(oldest)
            }
            created
        }
    }

    private fun createRepository(platform: SharePlatform, credential: String): ShareResolveRepository = when (platform) {
        SharePlatform.QUARK -> QuarkResolveRepository(QuarkApi())
        SharePlatform.UC -> UCResolveRepository(UCApi())
        SharePlatform.BAIDU -> BaiduResolveRepository(BaiduApi())
        SharePlatform.C139 -> C139ResolveRepository(C139Api())
        SharePlatform.PAN115 -> Pan115ResolveRepository(Pan115Api())
        SharePlatform.LANZOU -> LanzouResolveRepository(LanzouApi())
        SharePlatform.PAN123 -> Pan123ResolveRepository(Pan123Api()) { credential.takeIf { it.isNotBlank() } }
        SharePlatform.GUANGYA -> GuangYaResolveRepository(GuangYaApi()) { credential.takeIf { it.isNotBlank() } }
        SharePlatform.ILANZOU -> ILanzouResolveRepository(ILanzouApi(), ILanzouAccountRepository())
        // 迅雷：凭证是「access_token + refresh_token + device_id」三件套，
        // 光有 Cookie 串走不通（接口只认 Bearer），所以先经 [XunleiDriveCredential] 收敛。
        // P2-10：这里捕获的是本 Repository 自己的凭证快照；换账号会走到新的缓存 key，
        // 刷新出的新 token 经 credentialSink 写回账号池，下一次请求自然用新 key。
        SharePlatform.XUNLEI -> {
            val xunleiApi = XunleiApi()
            fun tokens(): XunleiDriveCredential? = XunleiDriveCredential.parse(credential)

            // 网页 token 的 captcha 只能用网页 9 盐现签，App 通道的签名会被判 client_id not match
            // P2-10：tokens() 解析的是本次 Repository 自己的凭证快照，不是共享可变字段
            xunleiApi.webCaptchaRefresher = { deviceId, action ->
                val id = deviceId.ifBlank { tokens()?.deviceId.orEmpty() }
                when (val result = xunleiApi.initWebCaptcha(id, action)) {
                    is WebCaptchaInit.Ok -> result.captcha
                    is WebCaptchaInit.ClientIdMismatch -> {
                        top.mcly99.dl.utils.AppLogger.warn(
                            "CloudDrive/Xunlei",
                            "网页 captcha init 返回 client_id 不匹配，需重新登录迅雷网页版" +
                                "（${top.mcly99.dl.data.network.XunleiWebSignConfig.diagnostics()}）",
                        )
                        null
                    }
                    is WebCaptchaInit.Failed -> {
                        top.mcly99.dl.utils.AppLogger.warn(
                            "CloudDrive/Xunlei",
                            "网页 captcha init 失败: ${result.reason}" +
                                "（${top.mcly99.dl.data.network.XunleiWebSignConfig.diagnostics()}）",
                        )
                        null
                    }
                }
            }
            // 请求侧 401 / unauthenticated 的兜底刷新（与仓库层的主动刷新同一条路）
            xunleiApi.refreshTokenProvider = { deviceId -> refreshXunleiToken(xunleiApi, deviceId, credential) }

            XunleiResolveRepository(
                api = xunleiApi,
                accountProvider = { tokens()?.accessToken?.takeIf { it.isNotBlank() } },
                deviceIdProvider = { tokens()?.deviceId?.takeIf { it.isNotBlank() } },
                captchaProvider = { null },
                refreshProvider = { refreshXunleiToken(xunleiApi, "", credential) },
            ).apply {
                // 多账号下载（实验性 C1/C2）传给仓库的是目标账号的「凭证原文」（结构化 JSON /
                // Cookie 串），不是裸 access_token。这里直接解析出该账号自己的三件套，
                // 让 identityFor / 配额 / 转存都用它；解析不到时仓库层回退主账号，行为不变（P1-03）。
                accountByTokenProvider = { raw ->
                    XunleiDriveCredential.parse(raw)?.takeIf { it.isUsable }?.let { cred ->
                        XunleiAccountEntity(
                            accessToken = cred.accessToken,
                            refreshToken = cred.refreshToken,
                            deviceId = cred.deviceId,
                            captchaToken = cred.captchaToken,
                            userId = cred.userId,
                        )
                    }
                }
            }
        }
        // GitHub 不经由 ShareResolveRepository：createSession / listFiles / downloadLink
        // 三处都已分流到 GitHubBrowser，这里只作为防御性兜底。
        SharePlatform.GITHUB -> throw IllegalStateException("GitHub 不经过网盘 Repository")
    }

    /**
     * 文件名带防盗链要求的 Referer；下载器需要一并带上，否则 CDN 返回 403。
     *
     * 实际口径在 [CloudDownloadHeaders.refererFor]，这里只做转发 —— 两张表分开维护
     * 迟早会漂移（旧实现里 123 的值就和常量表不一致）。
     */
    fun refererFor(platform: SharePlatform): String = CloudDownloadHeaders.refererFor(platform)

    private companion object {
        val URL_PASSCODE_PATTERNS = listOf(
            Regex("""[?&]pwd=([A-Za-z0-9_-]{4,16})""", RegexOption.IGNORE_CASE),
            Regex("""[?&](?:passcode|password|pass|code)=([A-Za-z0-9_-]{4,16})""", RegexOption.IGNORE_CASE),
        )
        val TEXT_PASSCODE_PATTERN = Regex("""(?:提取码|访问码|密码)[：:]\s*([A-Za-z0-9]{4,8})""")
    }
}

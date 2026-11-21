package top.mcly99.dl.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import top.mcly99.dl.data.network.BaiduConstants
import top.mcly99.dl.data.network.GitHubTokenStore
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.model.ShareSession
import top.mcly99.dl.model.DownloadSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.mcly99.dl.engine.clouddrive.CloudDriveAccountStore
import top.mcly99.dl.engine.clouddrive.CloudDriveApi
import top.mcly99.dl.engine.clouddrive.CloudDriveCatalog
import top.mcly99.dl.engine.clouddrive.CloudDriveService
import top.mcly99.dl.engine.clouddrive.GitHubBrowser
import top.mcly99.dl.engine.clouddrive.GitHubMirrorChoice
import top.mcly99.dl.engine.clouddrive.PendingCleanup
import top.mcly99.dl.engine.clouddrive.TempDirCleanupStore
import top.mcly99.dl.engine.clouddrive.CloudFile
import top.mcly99.dl.engine.clouddrive.CloudLink
import top.mcly99.dl.engine.clouddrive.CloudLinkEntry
import top.mcly99.dl.engine.clouddrive.CloudLinkLibrary
import top.mcly99.dl.engine.clouddrive.CloudQuota
import top.mcly99.dl.engine.clouddrive.MAX_FOLDER_FILES
import top.mcly99.dl.engine.clouddrive.XunleiDriveCredential
import top.mcly99.dl.engine.clouddrive.sanitizeDirName
import top.mcly99.dl.engine.clouddrive.toCloudFile
import top.mcly99.dl.engine.clouddrive.toShareFile
import top.mcly99.dl.theme.CloudDriveStrings
import top.mcly99.dl.theme.MultiAccountStrings
import top.mcly99.dl.theme.multiAccountStrings
import top.mcly99.dl.theme.zhCN
import top.mcly99.dl.utils.AppLogger
import java.util.concurrent.ConcurrentHashMap

/** 「云盘」页面状态机；与抓网页一样区分「没解析」「解析中」「解析成功」「失败」 */
sealed interface CloudDriveState {
    data object Idle : CloudDriveState
    data object Loading : CloudDriveState
    data class Success(val resolution: CloudResolution) : CloudDriveState
    data class Failed(val message: String) : CloudDriveState
}

data class CloudResolution(
    val link: String,
    val platform: SharePlatform,
    val platformName: String,
    val title: String,
    val passcode: String,
    val fileCount: Int,
    val totalSize: Long,
)

/** 解析输入区弹出的链接库类别（D-29） */
enum class LinkPicker { HISTORY, BOOKMARKS }

/**
 * 「云盘」视图状态。
 *
 * 上半块「解析结果」= 分享信息 + 文件树 + 直链（对齐 YunX 的解析页）；
 * 下半块「网盘列表」= 每个网盘一张账号卡片（对齐 YunX 的「网盘」标签页）。
 *
 * 解析能力由 [CloudDriveApi] 提供（移植的 YunX-Desktop 解析器），下载任务仍由
 * [AppViewModel] 通过 [onDownload] 注入 —— 本类不持有下载引擎，避免第二条入队路径。
 *
 * 凭证按平台存在 [accounts] 里，解析时自动带入对应平台的 Cookie / 令牌，
 * 不再使用全局单份凭证（上游也是每个网盘一份账号）。
 *
 * @param scope 协程作用域；测试注入可控调度器
 * @param ioDispatcher 阻塞 IO 调度器；测试注入可控调度器
 * @param stringsProvider 文案现取：切换界面语言后提示立即跟着变，不缓存旧语言
 * @param multiAccountProvider C1 开关现取（设置页改完立即生效，无需重启）
 * @param multiSourceProvider C2 开关现取；调用方保证它已包含「C1 必须同时开启」的判断
 * @param accountStringsProvider P3 多账号文案；独立语言表，不给 UiStrings 加槽位
 */
class CloudDriveViewModel(
    private val api: CloudDriveApi = CloudDriveService(),
    private val accounts: CloudDriveAccountStore = CloudDriveAccountStore.shared,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val stringsProvider: () -> CloudDriveStrings = { zhCN().cloudDriveStrings },
    private val linkLibrary: CloudLinkLibrary = CloudLinkLibrary(),
    private val multiAccountProvider: () -> Boolean = { false },
    private val multiSourceProvider: () -> Boolean = { false },
    private val accountStringsProvider: () -> MultiAccountStrings = { multiAccountStrings("zh-CN") },
    /** 待清理临时目录的落盘清单；测试注入临时路径，避免读写用户目录 */
    private val cleanupStore: TempDirCleanupStore = TempDirCleanupStore(),
) {
    private fun t(): CloudDriveStrings = stringsProvider()

    /**
     * 账号池变化通知（登录 / 登出 / 增删账号 / 切主账号 / token 刷新写回）。
     *
     * 账号池是共享的普通 MutableList，不是 Compose state：「我的网盘」页读同一份池子，
     * 但本类写入后那边不会自动重算登录态。由 AppViewModel 把 [MyDriveViewModel.onAccountsChanged]
     * 挂到这里，默认空实现让单元测试不受影响。
     */
    var onAccountsChanged: () -> Unit = {}

    /** P3 多账号文案按当前语言现取 */
    val accountStrings: MultiAccountStrings get() = accountStringsProvider()

    /**
     * 当前批量取链正在使用的凭证。解析层刷新 token 时 `credentialSink` 只带「平台 + 新值」，
     * 靠它才能把新 token 写回**轮换到的那个账号**，而不是一律写回主账号。
     */
    @Volatile
    private var activeCredential: String = ""
    /**
     * 解析层自动刷新出的新凭证要写回「网盘列表」卡片。
     *
     * 迅雷的 access_token 只有几小时，过期后由解析层用 refresh_token 换新的；
     * 不写回的话卡片上还是旧 token，下一次解析又会被判过期（用户看到的就是
     * 「明明登录了却一直提示重新登录」）。
     */
    init {
        api.bindCredentialSink { platform, value ->
            val expected = activeCredential.takeIf { it.isNotBlank() }
                ?: accounts.credentialFor(platform)
            accounts.updateCredential(platform, expected, value)
            onAccountsChanged()
        }
        // GitHub Token 是网盘列表里的 GITHUB 卡片凭证；让移植层的 GitHubTokenStore
        // 读写同一个内存实例，避免「磁盘清了、内存还留着失效 Token」导致持续 401。
        GitHubTokenStore.tokenReader = {
            accounts.credentialFor(SharePlatform.GITHUB).takeIf { it.isNotBlank() }
        }
        GitHubTokenStore.tokenWriter = { token ->
            accounts.set(SharePlatform.GITHUB, token.orEmpty())
            true
        }
    }

    // ---- 解析历史 / 收藏（D-29，ktdown 自有本地存储，不来自上游）----
    // 注意：这些状态在声明处直接取一次存储快照，不要在 init {} 里刷新 ——
    // Kotlin 让 init 与属性初始化按源码顺序交错执行，提前写会让 mutableStateOf
    // 的 delegate 还没构造好就触发 setValue。

    /** 最近解析成功的链接，按时间倒序 */
    var linkHistory by mutableStateOf(linkLibrary.history())
        private set

    /** 用户手动收藏的链接，按时间倒序 */
    var bookmarks by mutableStateOf(linkLibrary.bookmarks())
        private set

    /** 非 null 时显示「历史 / 收藏」选择弹窗 */
    var linkPicker by mutableStateOf<LinkPicker?>(null)
        private set

    private fun refreshLinkLibrary() {
        linkHistory = linkLibrary.history()
        bookmarks = linkLibrary.bookmarks()
    }

    fun openLinkPicker(which: LinkPicker) {
        refreshLinkLibrary()
        linkPicker = which
    }

    fun closeLinkPicker() {
        linkPicker = null
    }

    /** 点历史/收藏项：回填输入框后直接重新解析，复用完整既有链路 */
    fun reuseEntry(entry: CloudLinkEntry) {
        linkPicker = null
        // 先清掉上一条链接的提取码（含用户手输的），再走标准自动填充：
        // 直接赋值 url 会绕过 updateUrl，既漏掉新链接自带的 pwd，也会带上旧码。
        updatePasscode("")
        updateUrl(entry.url)
        resolve()
    }

    fun removeHistoryEntry(entry: CloudLinkEntry) {
        linkLibrary.removeHistory(entry.url)
        refreshLinkLibrary()
    }

    fun removeBookmarkEntry(entry: CloudLinkEntry) {
        linkLibrary.removeBookmark(entry.url)
        refreshLinkLibrary()
    }

    fun clearLinkHistory() {
        linkLibrary.clearHistory()
        refreshLinkLibrary()
    }

    fun clearAllBookmarks() {
        linkLibrary.clearBookmarks()
        refreshLinkLibrary()
    }

    /** 当前解析结果是否已收藏 */
    fun isCurrentBookmarked(): Boolean {
        val link = (state as? CloudDriveState.Success)?.resolution?.link ?: return false
        return bookmarks.any { it.url.equals(link, ignoreCase = true) }
    }

    /** 结果区星标：收藏 / 取消收藏当前解析出的链接 */
    fun toggleCurrentBookmark() {
        val resolution = (state as? CloudDriveState.Success)?.resolution ?: return
        linkLibrary.toggleBookmark(
            url = resolution.link,
            title = resolution.title,
            platform = resolution.platform,
        )
        refreshLinkLibrary()
    }

    /** 顶栏「解析地址」输入框；单一真源 */
    var url by mutableStateOf("")
        private set

    /** 顶栏「提取码（可选）」输入框；链接里带 pwd 时自动填充 */
    var passcode by mutableStateOf("")
        private set

    var state by mutableStateOf<CloudDriveState>(CloudDriveState.Idle)
        private set

    /** 当前目录文件列表（解析结果区内） */
    var files by mutableStateOf<List<CloudFile>>(emptyList())
        private set

    /** 面包屑路径（分享根目录不计入） */
    var pathNames by mutableStateOf<List<String>>(emptyList())
        private set

    /** 已勾选的文件 fid；目录不参与勾选 */
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 已取到的直链（解析结果区展示） */
    var links by mutableStateOf<List<CloudLink>>(emptyList())
        private set

    /** 已加入下载的地址 */
    var addedUrls by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 当前目录内的文件名筛选（只过滤展示，不重新请求网络） */
    var searchQuery by mutableStateOf("")
        private set

    /** 当前目录的排序方式 */
    var sortMode by mutableStateOf(CloudFileSort.NAME)
        private set

    /** 搜索 + 排序后的文件列表；目录恒置顶，避免被大小排序混进文件堆 */
    val visibleFiles: List<CloudFile>
        get() {
            val query = searchQuery.trim()
            val filtered = if (query.isEmpty()) files
            else files.filter { it.name.contains(query, ignoreCase = true) }
            val sorted = when (sortMode) {
                CloudFileSort.NAME -> filtered.sortedBy { it.name.lowercase() }
                CloudFileSort.SIZE -> filtered.sortedByDescending { it.size }
                CloudFileSort.TYPE -> filtered.sortedBy { it.name.substringAfterLast('.', "").lowercase() }
            }
            return sorted.sortedByDescending { it.isDir }
        }

    /** 非阻断提示：取链结果、登录引导等 */
    var notice by mutableStateOf<String?>(null)
        private set

    /** 目录切换 / 批量取链进行中（不覆盖解析结果） */
    var working by mutableStateOf(false)
        private set

    // ------------------------------------------------------------ 网盘列表（平台卡片）

    /** 正在编辑凭证的平台；null = 编辑器收起（登录 Screen 的手工粘贴区也复用它） */
    var editingPlatform by mutableStateOf<SharePlatform?>(null)
        private set

    /** 非 null 时主界面切换成该网盘的网页登录 Screen（应用内嵌 Chromium） */
    var loginPlatform by mutableStateOf<SharePlatform?>(null)
        private set

    /** 凭证编辑框内容 */
    var credentialDraft by mutableStateOf("")
        private set

    // ---- P3 多账号管理面板 ----

    /** 非 null 时展开该平台的账号列表（设主 / 改备注 / 退出 / 添加） */
    var accountPanelPlatform by mutableStateOf<SharePlatform?>(null)
        private set

    var accountList by mutableStateOf(emptyList<CloudAccountView>())
        private set

    /** 「添加账号」模式：本次登录成功后新建一条非主账号，而不是覆盖主账号 */
    var addingAccount by mutableStateOf(false)
        private set

    /** 正在改备注的账号 id；null = 没有在编辑 */
    var renamingAccountId by mutableStateOf<String?>(null)
        private set

    var remarkDraft by mutableStateOf("")
        private set

    /** 最近一次解析的平台，卡片上打「当前解析」标记 */
    var activePlatform by mutableStateOf<SharePlatform?>(null)
        private set

    /** 卡片两列 / 单列（对齐上游网盘页的「四宫格 / 列表」切换） */
    var gridMode by mutableStateOf(true)
        private set

    /** 各平台空间配额；只有配置过凭证且接口支持的平台才有值 */
    var quotas by mutableStateOf<Map<SharePlatform, CloudQuota>>(emptyMap())
        private set

    var quotaLoading by mutableStateOf(false)
        private set

    /** GitHub 下载镜像当前取值；默认 `auto`（实测最快，失败回落直连，P2） */
    var mirrorChoice by mutableStateOf(api.githubMirrorValue())
        private set

    /** 镜像设置项（值 → 本地化标签）；空列表时 UI 不展示这一行 */
    val mirrorOptions: List<Pair<String, String>>
        get() = api.githubMirrorOptions().map { value ->
            value to when (value) {
                GitHubMirrorChoice.DIRECT -> t().githubMirrorDirect
                GitHubMirrorChoice.AUTO -> t().githubMirrorAuto
                else -> value
            }
        }

    fun selectMirror(value: String) {
        api.selectGithubMirror(value)
        mirrorChoice = api.githubMirrorValue()
        val label = mirrorOptions.firstOrNull { it.first == mirrorChoice }?.second ?: mirrorChoice
        notice = t().githubMirrorChanged(label)
    }

    /**
     * 由 [AppViewModel] 注入：把直链交给下载引擎，并**返回任务 id**。
     * 拿到 id 才能把「任务进入终态」和「这条直链产生的临时转存目录」关联起来清理。
     * 返回 null 表示入队失败（或测试里不关心清理）。
     */
    var onDownload: (CloudLink) -> String? = { null }

    /** taskId → 该任务产生的所有云端临时目录；C2 下每个账号可能各建一个 tr_* */
    private val pendingCleanups = ConcurrentHashMap<String, List<CleanupTarget>>()

    /**
     * 直链 → 取链时用的那份凭证（只在内存，绝不落盘）。
     *
     * C1 轮换下一个文件可能由次账号取链，「重新下载」要清的是**那个账号**网盘里的 `tr_*` 目录。
     * 不记录的话只能退回到主账号凭证，清理必然 404 且没有重试入口（P1-17）。
     * 存在这里而不是 `CloudLink` 上：`CloudLink` 会进直链列表、可能被日志打印，
     * 而它本来就有「不携带凭证」的设计约定。清理完成后即移除，不会无限增长。
     */
    private val credentialByUrl = ConcurrentHashMap<String, String>()

    private var session: ShareSession? = null
    private var platform: SharePlatform = SharePlatform.QUARK
    private var currentDirFid: String = "0"
    private val dirStack = ArrayDeque<String>()

    // 解析与「列目录 / 批量取链」用两条独立的 Job：否则用户在解析途中点一下
    // 目录或面包屑，后台任务会把解析协程 cancel 掉，state 永远停在 Loading。
    private var resolveJob: Job? = null
    private var workJob: Job? = null
    private var quotaJob: Job? = null

    /** 上一次由链接自动填入的提取码；用户手改后失效，避免后续输入覆盖用户输入 */
    private var autoFilledPasscode: String? = null

    val busy: Boolean get() = state is CloudDriveState.Loading || working

    fun updateUrl(value: String) {
        url = value
        val detected = api.extractPasscode(value)
        when {
            detected != null && (passcode.isBlank() || passcode == autoFilledPasscode) -> {
                passcode = detected
                autoFilledPasscode = detected
            }
            // 链接里的提取码被删掉了：只清掉自己填过的那份，不动用户手输
            detected == null && autoFilledPasscode != null && passcode == autoFilledPasscode -> {
                passcode = ""
                autoFilledPasscode = null
            }
        }
    }

    fun updatePasscode(value: String) {
        passcode = value
        if (value != autoFilledPasscode) autoFilledPasscode = null
    }

    // ------------------------------------------------------------ 平台卡片操作

    fun isConfigured(platform: SharePlatform): Boolean = accounts.isConfigured(platform)

    fun maskedCredential(platform: SharePlatform): String = accounts.masked(platform)

    // ------------------------------------------------------------ P3 多账号管理

    /** 卡片副标题用：该平台一共登录了几个账号 */
    fun accountCount(platform: SharePlatform): Int = accounts.accountCount(platform)

    fun openAccountPanel(platform: SharePlatform) {
        accountPanelPlatform = platform
        renamingAccountId = null
        remarkDraft = ""
        addingAccount = false
        refreshAccountList()
    }

    fun closeAccountPanel() {
        accountPanelPlatform = null
        accountList = emptyList()
        renamingAccountId = null
        remarkDraft = ""
        addingAccount = false
    }

    /**
     * 添加账号：走同一个应用内登录页，但成功后新建一条非主账号。
     * 上游在添加前会清 JCEF 登录态；ktdown 在登录页给出「清除登录态」按钮与文案提示，
     * 否则内嵌浏览器会自动登回当前账号，加进来的还是同一个。
     */
    fun startAddAccount(platform: SharePlatform) {
        if (platform == SharePlatform.GITHUB) {
            openPlatform(platform)
            return
        }
        addingAccount = true
        accountPanelPlatform = platform
        openLogin(platform)
        refreshAccountList()
    }

    fun setPrimaryAccount(id: String) {
        val p = accountPanelPlatform ?: return
        accounts.setPrimary(p, id)
        quotas = quotas - p
        refreshAccountList()
        onAccountsChanged()
        notice = accountStrings.primaryChanged(labelOf(id))
    }

    fun beginRename(id: String) {
        val p = accountPanelPlatform ?: return
        renamingAccountId = id
        remarkDraft = accounts.accountById(p, id)?.remark.orEmpty()
    }

    fun updateRemarkDraft(value: String) {
        remarkDraft = value
    }

    fun commitRename() {
        val p = accountPanelPlatform ?: return
        val id = renamingAccountId ?: return
        accounts.rename(p, id, remarkDraft)
        renamingAccountId = null
        remarkDraft = ""
        refreshAccountList()
    }

    fun removeAccount(id: String) {
        val p = accountPanelPlatform ?: return
        val label = labelOf(id)
        accounts.remove(p, id)
        quotas = quotas - p
        refreshAccountList()
        notice = accountStrings.accountRemoved(label)
    }

    private fun labelOf(id: String): String {
        val p = accountPanelPlatform ?: return accountStrings.unnamedAccount
        val row = accounts.accountById(p, id) ?: return accountStrings.unnamedAccount
        return row.remark.ifBlank { row.nickname.ifBlank { accountStrings.unnamedAccount } }
    }

    private fun refreshAccountList() {
        val p = accountPanelPlatform
        if (p == null) {
            accountList = emptyList()
            return
        }
        accountList = accounts.accountsFor(p).map { row ->
            CloudAccountView(
                id = row.id,
                label = row.remark.ifBlank { row.nickname.ifBlank { accountStrings.unnamedAccount } },
                masked = accounts.maskCredential(row),
                isPrimary = row.isPrimary,
                remark = row.remark,
            )
        }
    }

    /** 点卡片：展开该网盘的凭证编辑器（GitHub 卡片的可选 Token 也走这里） */
    fun openPlatform(platform: SharePlatform) {
        editingPlatform = platform
        credentialDraft = accounts.credentialFor(platform)
    }

    fun closeEditor() {
        editingPlatform = null
        credentialDraft = ""
    }

    fun updateCredentialDraft(value: String) {
        credentialDraft = value
    }

    fun saveCredential() {
        val p = editingPlatform ?: return
        accounts.set(p, credentialDraft)
        quotas = quotas - p
        val name = CloudDriveCatalog.of(p)?.name ?: p.name
        notice = if (accounts.isConfigured(p)) {
            t().credentialSaved(name)
        } else {
            t().credentialEmpty(name)
        }
        closeEditor()
        refreshAccountList()
        onAccountsChanged()
        // 手工粘贴也是在登录 Screen 里完成的，保存后回到云盘页
        closeLogin()
    }

    // ------------------------------------------------------------ 网页登录 Screen

    /** 点网盘卡片：进入该网盘的应用内网页登录页（不跳外部浏览器） */
    fun openLogin(platform: SharePlatform) {
        // GitHub 没有网盘登录页：即便别处误调，也只展开凭证编辑器，绝不初始化 JCEF。
        // 这是 ViewModel 层的防御，UI 层同样把 GitHub 卡片路由到 openPlatform。
        if (platform == SharePlatform.GITHUB) {
            openPlatform(platform)
            return
        }
        loginPlatform = platform
        // 登录页底部「手工粘贴」复用同一份草稿状态
        editingPlatform = platform
        credentialDraft = accounts.credentialFor(platform)
        notice = null
    }

    fun closeLogin() {
        loginPlatform = null
        addingAccount = false
        closeEditor()
    }

    /** 内嵌浏览器检测到登录态后回调：把凭证写进对应平台并返回云盘页 */
    fun saveLoginCookie(cookie: String): Boolean {
        val p = loginPlatform ?: return false
        if (cookie.isBlank()) return false
        // 迅雷：网页登录给到的是 Cookie 桥分片 / localStorage 原文，先收敛成
        // 「access_token + refresh_token + device_id」再落库；收敛不出来说明这次
        // 根本没拿到登录态（页面还停在登录页 / 桥接脚本没跑），直接判失败更诚实
        val stored = if (p == SharePlatform.XUNLEI) {
            val cred = XunleiDriveCredential.parse(cookie)
            if (cred?.isUsable != true) {
                AppLogger.warn(
                    "CloudDrive",
                    "迅雷凭证解析失败：没有 access_token（cookieKeys=${BaiduConstants.cookieKeys(cookie)}）",
                )
                return false
            }
            cred.toJson()
        } else {
            cookie
        }
        // 对齐上游 resolveAccountSlot：日常重新登录覆盖主账号，只有「添加账号」
        // 才新建一条非主账号，否则每次会话过期重登都会堆出一个失效账号。
        val wasAdding = addingAccount
        if (wasAdding) {
            accounts.addAccount(p, stored)
        } else {
            accounts.set(p, stored)
        }
        quotas = quotas - p
        // 只打键名（不含值）：转存恒失败时要能一眼看出登录态缺了哪一块
        AppLogger.info(
            "CloudDrive",
            "保存登录态 platform=${p.name} adding=$wasAdding cookieKeys=${BaiduConstants.cookieKeys(stored)}",
        )
        notice = when {
            p == SharePlatform.BAIDU && !BaiduConstants.hasStoken(cookie) -> t().baiduStokenMissing
            wasAdding -> accountStrings.accountAdded(CloudDriveCatalog.of(p)?.name ?: p.name)
            else -> t().loginSuccess(CloudDriveCatalog.of(p)?.name ?: p.name)
        }
        closeLogin()
        refreshAccountList()
        refreshQuotas()
        onAccountsChanged()
        return true
    }

    /** 登录页「退出登录」：清掉本平台凭证与浏览器会话 */
    fun logout(platform: SharePlatform) {
        accounts.clear(platform)
        quotas = quotas - platform
        credentialDraft = ""
        refreshAccountList()
        onAccountsChanged()
        notice = t().loggedOut(CloudDriveCatalog.of(platform)?.name ?: platform.name)
    }

    fun clearCredential() {
        val p = editingPlatform ?: return
        accounts.clear(p)
        quotas = quotas - p
        credentialDraft = ""
        refreshAccountList()
        onAccountsChanged()
        notice = t().credentialCleared(CloudDriveCatalog.of(p)?.name ?: p.name)
    }

    fun toggleGridMode() {
        gridMode = !gridMode
    }

    /** 并发刷新所有已配置、且移植层有配额接口的平台；单个失败不影响其它卡片 */
    fun refreshQuotas() {
        quotaJob?.cancel()
        val targets = accounts.configuredPlatforms()
            .filter { CloudDriveCatalog.of(it)?.supportsQuota == true }
        if (targets.isEmpty()) {
            quotas = emptyMap()
            quotaLoading = false
            return
        }
        quotaLoading = true
        quotaJob = scope.launch {
            val result = java.util.concurrent.ConcurrentHashMap<SharePlatform, CloudQuota>()
            try {
                withContext(ioDispatcher) {
                    coroutineScope {
                        targets.forEach { p ->
                            launch {
                                val q = runCatching { api.quota(p, accounts.credentialFor(p)) }.getOrNull()
                                if (q != null) result[p] = q
                            }
                        }
                    }
                }
                quotas = result.toMap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn("CloudDriveViewModel", "网盘配额刷新失败: ${e.message}", e)
            } finally {
                quotaLoading = false
            }
        }
    }

    // ------------------------------------------------------------ 解析 / 下载

    /** 顶栏按钮：空闲时解析，进行中则取消 */
    fun submit() {
        if (busy) cancel() else resolve()
    }

    fun resolve() {
        val input = url.trim()
        if (input.isEmpty()) return
        // D-18：GitHub 链接现在由 CloudDriveApi.parse 收敛成 GITHUB 平台，
        // 与网盘分享走同一条 createSession → listFiles → downloadLink 链路，不再硬拦截。
        val direct = api.parse(input)

        // 同步切到 Loading：不让「协程还没被调度」这个窗口里的点击漏进后台任务
        resolveJob?.cancel()
        workJob?.cancel()
        working = false
        state = CloudDriveState.Loading
        session = null
        files = emptyList()
        links = emptyList()
        selected = emptySet()
        searchQuery = ""
        sortMode = CloudFileSort.NAME
        notice = null
        addedUrls = emptySet()
        dirStack.clear()
        pathNames = emptyList()
        closeEditor()
        resolveJob = scope.launch {
            try {
                // ① 中文口令兜底：本地认不出链接时，先让 glue 层把口令换成真实分享链接；
                //    成功后提取码 / 登录态 / 列目录 / 取链全部复用既有链路（D-19 / D-20）。
                var effectiveLink = input
                var fromCommand = false
                if (direct == null) {
                    notice = t().commandResolving
                    val command = withContext(ioDispatcher) { api.resolveCommand(input) }
                    if (command == null) {
                        notice = null
                        state = CloudDriveState.Failed(t().invalidShareLink)
                        return@launch
                    }
                    if (command.isFailure) {
                        notice = null
                        val reason = command.exceptionOrNull()?.message ?: t().resolveFailed
                        state = CloudDriveState.Failed(t().commandResolveFailed(reason))
                        return@launch
                    }
                    effectiveLink = command.getOrThrow()
                    fromCommand = true
                }
                val parsed = api.parse(effectiveLink) ?: run {
                    notice = null
                    state = CloudDriveState.Failed(t().invalidShareLink)
                    return@launch
                }

                // ② 平台相关的准备工作必须等口令解析完再做：口令原文本身不含平台信息
                val root = api.rootDirFid(parsed.platform)
                val cred = accounts.credentialFor(parsed.platform)
                currentDirFid = root
                activePlatform = parsed.platform
                val s = withContext(ioDispatcher) {
                    api.createSession(effectiveLink, passcode.takeIf { it.isNotBlank() }, cred, parsed.platform)
                }
                val list = withContext(ioDispatcher) {
                    api.listFiles(s, root, cred, parsed.platform)
                }
                session = s
                platform = parsed.platform
                currentDirFid = root
                dirStack.clear()
                pathNames = emptyList()
                files = list.map { it.toCloudFile() }
                val resolvedTitle = s.title.ifBlank { parsed.shareId }
                state = CloudDriveState.Success(
                    CloudResolution(
                        // 展示口令换出来的真实链接，便于复制 / 重新解析
                        link = effectiveLink,
                        platform = parsed.platform,
                        platformName = api.platformName(parsed.platform),
                        title = resolvedTitle,
                        passcode = passcode,
                        fileCount = list.size,
                        totalSize = list.sumOf { it.fsize },
                    ),
                )
                // D-29：只记录解析成功的链接。口令场景存的是换出来的真实链接，
                // 下次从历史复用时不再请求第三方口令服务。写入失败不影响本次解析结果。
                runCatching {
                    linkLibrary.recordResolved(effectiveLink, resolvedTitle, parsed.platform)
                    refreshLinkLibrary()
                }
                if (fromCommand) notice = t().commandResolved
                // 上游对文件直链弹确认框；ktdown 没有那个弹窗，铺成单文件列表并给出去向提示
                if (parsed.platform == SharePlatform.GITHUB && s.stoken == GitHubBrowser.DIRECT_MARKER) {
                    notice = t().githubDirectFileHint
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn("CloudDriveViewModel", "云盘解析失败: ${e.message}", e)
                notice = null
                val fallback = if (activePlatform == SharePlatform.GITHUB) t().githubLinkHint else t().resolveFailed
                state = CloudDriveState.Failed(e.message ?: fallback)
            }
        }
    }

    /** 进入子目录；与上游一致：父目录 fid 入栈，目标 fid 直接用 file.fid */
    fun openFolder(file: CloudFile) {
        if (busy || !file.isDir) return
        val s = session ?: return
        dirStack.addLast(currentDirFid)
        pathNames = pathNames + file.name
        selected = emptySet()
        loadDirectory(s, file.fid)
    }

    fun goUp() {
        if (busy || dirStack.isEmpty()) return
        val s = session ?: return
        val parent = dirStack.removeLast()
        pathNames = pathNames.dropLast(1)
        selected = emptySet()
        loadDirectory(s, parent)
    }

    /** 点击面包屑回到第 level 层；level=0 回到分享根目录（只发一次请求） */
    fun goToLevel(level: Int) {
        if (busy) return
        val s = session ?: return
        if (level < 0 || level >= pathNames.size) return
        var target = currentDirFid
        repeat(pathNames.size - level) {
            if (dirStack.isNotEmpty()) target = dirStack.removeLast()
        }
        pathNames = pathNames.take(level)
        selected = emptySet()
        loadDirectory(s, target)
    }

    fun toggleSelect(file: CloudFile) {
        if (file.isDir) return
        selected = if (file.fid in selected) selected - file.fid else selected + file.fid
    }

    fun toggleSelectAll() {
        // 跟着搜索 / 排序后的可见项走，避免「筛出 3 个却全选了 300 个」
        val candidates = visibleFiles.filterNot { it.isDir }.map { it.fid }
        selected = if (candidates.isNotEmpty() && candidates.all { it in selected }) {
            emptySet()
        } else {
            candidates.toSet()
        }
    }

    /** 行内「下载」：取直链并直接交给下载引擎 */
    fun downloadFile(file: CloudFile) {
        if (busy || file.isDir) return
        fetchAndDownload(listOf(file to ""))
    }

    /**
     * 文件夹行的「下载」：递归展开该文件夹下所有层级的子目录，把每个文件按
     * 原有目录层级取链入队。网盘 API 一次只能列一层，展开在 [api] 的
     * `collectFiles` 里完成；单个子目录失败只跳过，不阻断其余文件。
     */
    fun downloadFolder(folder: CloudFile) {
        if (busy || !folder.isDir) return
        val s = session ?: return
        val cred = accounts.credentialFor(platform)
        workJob?.cancel()
        working = true
        notice = null
        workJob = scope.launch {
            try {
                val tree = withContext(ioDispatcher) {
                    api.collectFiles(s, folder.fid, cred, platform, sanitizeDirName(folder.name))
                }
                if (tree.entries.isEmpty()) {
                    notice = buildString {
                        append(t().folderEmpty(folder.name))
                        if (tree.failedDirs.isNotEmpty()) append("；${t().subDirsFailed(tree.failedDirs.size)}")
                    }
                    return@launch
                }
                notice = t().folderExpanded(folder.name, tree.entries.size)
                val summary = fetchEntries(
                    s,
                    tree.entries.map { it.file to it.relativeDir },
                    cred,
                )
                notice = buildString {
                    append(t().folderResult(folder.name, tree.entries.size))
                    append("；")
                    append(summaryMessage(summary))
                    if (tree.failedDirs.isNotEmpty()) append("；${t().subDirsFailed(tree.failedDirs.size)}")
                    if (tree.truncated) append("；${t().truncatedHint(MAX_FOLDER_FILES)}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn("CloudDriveViewModel", "展开文件夹失败 ${folder.name}: ${e.message}", e)
                notice = t().folderDownloadFailed(e.message.orEmpty())
            } finally {
                working = false
            }
        }
    }

    /** 「下载选中」：整批取链，单条失败不阻断其余条目 */
    fun downloadSelected() {
        if (busy) return
        val targets = files.filterNot { it.isDir }.filter { it.fid in selected }
        if (targets.isEmpty()) {
            notice = t().selectFilesFirst
            return
        }
        fetchAndDownload(targets.map { it to "" })
    }

    /** 解析结果区标题栏的「下载」图标：把当前目录里所有文件批量取链下载 */
    fun downloadAll() {
        if (busy) return
        val targets = files.filterNot { it.isDir }
        if (targets.isEmpty()) {
            notice = t().noFilesToDownload
            return
        }
        fetchAndDownload(targets.map { it to "" })
    }

    /** 解析结果区行内「重新下载」：复用已有直链 */
    fun downloadLink(link: CloudLink) {
        val taskId = onDownload(link)
        if (taskId == null) {
            // 入队被拒（协议不支持 / 地址为空）不能标「已加入」：按钮会变灰、用户也没法重试
            notice = t().summaryEmpty
            return
        }
        // 凭证优先用「取链那一刻」的那份：C1 轮换下这条直链可能是次账号取的，
        // 而 tr_* 临时目录就建在那个账号的网盘里。用主账号去清只会在自己的空间里 404，
        // 目录永久残留（P1-17）。只有在拿不到历史凭证时才退回主账号。
        val cred = credentialByUrl[link.url] ?: accounts.credentialFor(link.platform ?: platform)
        registerCleanup(taskId, link, cred)
        markAdded(link.url)
    }

    fun markAdded(url: String) {
        addedUrls = addedUrls + url
    }

    // ------------------------------------------------------------ 临时转存目录清理

    /**
     * 登记「这个下载任务对应哪个云端临时目录」。
     *
     * 凭证在这里快照下来：清理发生在任务终态，那时用户可能已经退出登录，
     * 现取就变成空串了。凭证只存在于内存与加密配置文件，不进 [CloudLink]，
     * 免得被直链列表展示或写进日志。
     */
    private fun registerCleanup(taskId: String, link: CloudLink, cred: String) {
        val dirFid = link.cleanupDirFid ?: return
        val p = link.platform ?: platform
        if (cred.isBlank()) {
            AppLogger.warn("CloudDriveViewModel", "跳过清理 ${p.name} 临时目录：没有可用凭证")
            return
        }
        // merge 而不是覆盖：C2 下主链与每条备选链都可能各自建了 tr_* 目录，
        // 只登记一条会在其它账号的网盘里留下垃圾（上游 C2 完全没处理这件事）。
        val accountId = accountIdOf(p, cred)
        pendingCleanups.merge(
            taskId,
            listOf(CleanupTarget(p, dirFid, cred, link.url, accountId)),
        ) { old, added -> old + added }
        // 同时落盘：退出应用时引擎把进行中的任务改成 PAUSED（不是终态），不会触发
        // onDownloadTasksChanged，内存清单随进程消失，这些 tr_* 目录就永远没人清了（P1-18）
        cleanupStore.add(PendingCleanup(p, dirFid, accountId))
    }

    /** 凭证 → 账号 id；只用于把清理任务落到「哪个账号的网盘」，找不到就记成主账号 */
    private fun accountIdOf(platform: SharePlatform, credential: String): String =
        accounts.accountsFor(platform)
            .firstOrNull { it.credential.trim() == credential.trim() }
            ?.id
            .orEmpty()

    private fun credentialForCleanup(entry: PendingCleanup): String =
        if (entry.accountId.isBlank()) {
            accounts.credentialFor(entry.platform)
        } else {
            accounts.accountById(entry.platform, entry.accountId)?.credential.orEmpty()
        }

    /**
     * 启动后补一次上次没做完的临时目录清理（P1-18）。
     *
     * 失败不重试、不阻塞启动：清不掉的那个目录下次启动还会再试一次，
     * 账号被删掉的则直接丢弃 —— 留着凭证或 id 也没有任何清理可能，只会让清单无限增长。
     */
    fun resumePendingCleanups() {
        val entries = cleanupStore.load()
        if (entries.isEmpty()) return
        scope.launch {
            for (entry in entries) {
                val cred = credentialForCleanup(entry)
                if (cred.isBlank()) {
                    AppLogger.info(
                        "CloudDriveViewModel",
                        "放弃清理 ${entry.platform.name} 临时目录 ${entry.dirFid}：对应账号已不存在",
                    )
                    cleanupStore.remove(entry)
                    continue
                }
                runCatching {
                    withContext(ioDispatcher) { api.cleanupTempDir(entry.platform, entry.dirFid, cred) }
                }.onSuccess {
                    cleanupStore.remove(entry)
                }.onFailure { e ->
                    AppLogger.warn(
                        "CloudDriveViewModel",
                        "补清理 ${entry.platform.name} 临时目录失败: ${e.message}", e,
                    )
                }
            }
        }
    }

    /**
     * 记住「这条直链是哪个账号取的」。
     *
     * 只记需要清理临时目录的链 —— 其余直链没有账号归属问题，记了也只是让内存随下载次数增长。
     * 凭证本身就存在 [credentialByUrl] 里，不进 [CloudLink]（那边有「不携带凭证」的约定）。
     */
    private fun rememberCredential(link: CloudLink, cred: String) {
        if (link.cleanupDirFid.isNullOrBlank() || cred.isBlank()) return
        credentialByUrl[link.url] = cred
    }

    /**
     * 由 [AppViewModel] 在任务列表变化时调用。
     *
     * 上游只在「下载完成」时清理；这里**进入任一终态或从任务列表消失都清理**：
     * 失败 / 取消 / 删除同样会在用户网盘留下 `tr_*` 目录，而那条直链已经不会再用了，
     * 没有保留价值。清理失败只记日志，绝不影响已下载的文件。
     *
     * @param knownIds 当前所有任务 id（不在这里面的登记项视为「任务已删除」）
     * @param settledIds 已进入终态（完成 / 失败 / 取消）的任务 id
     */
    fun onDownloadTasksChanged(knownIds: Set<String>, settledIds: Set<String>) {
        if (pendingCleanups.isEmpty()) return
        val due = pendingCleanups.keys.filter { it !in knownIds || it in settledIds }
        for (id in due) {
            val targets = pendingCleanups.remove(id) ?: continue
            for (target in targets) {
                scope.launch {
                    runCatching {
                        withContext(ioDispatcher) {
                            api.cleanupTempDir(target.platform, target.dirFid, target.credential)
                        }
                    }.onFailure { e ->
                        AppLogger.warn(
                            "CloudDriveViewModel",
                            "清理 ${target.platform.name} 临时转存目录失败: ${e.message}", e,
                        )
                    }
                    // 无论成败都不再需要这条映射：失败时目录已经不在任务生命周期内，
                    // 留着凭证只是白白多存一份敏感数据
                    if (target.url.isNotBlank()) credentialByUrl.remove(target.url)
                    // 清理过了（成功或失败）就从落盘清单里摘掉；失败的下次启动会再试
                    cleanupStore.remove(PendingCleanup(target.platform, target.dirFid, target.accountId))
                }
            }
        }
    }

    /** @param targets 「文件 → 相对目录」；普通单文件下载的相对目录为空串 */
    private fun fetchAndDownload(targets: List<Pair<CloudFile, String>>) {
        val s = session ?: return
        val cred = accounts.credentialFor(platform)
        workJob?.cancel()
        working = true
        notice = null
        workJob = scope.launch {
            try {
                notice = summaryMessage(fetchEntries(s, targets, cred))
            } finally {
                working = false
            }
        }
    }

    /**
     * 逐条取链；单条失败只记数，不打断整批。
     *
     * P3 · C1：开启开关且该平台有多个账号时，按文件序号轮换凭证池（主账号在 index 0）。
     * **取链与清理始终用同一份凭证** —— 直链签名绑定取链那一刻的 Cookie / token，
     * 轮换后若清理还用主账号，会在别人的网盘里找不到临时目录。
     * 单条失败不换账号重试（对齐上游），避免一个失效账号把整批拖成 N 倍请求。
     */
    private suspend fun fetchEntries(
        s: ShareSession,
        targets: List<Pair<CloudFile, String>>,
        cred: String,
    ): FetchSummary {
        val pool = credentialRotation(cred)
        var ok = 0
        var failed = 0
        var firstError: String? = null
        try {
            for ((index, target) in targets.withIndex()) {
                val (file, relativeDir) = target
                val fileCred = pool[index % pool.size]
                activeCredential = fileCred
                try {
                    val primary = withContext(ioDispatcher) {
                        api.downloadLink(s, file.toShareFile(), fileCred, platform)
                    }.copy(relativeDir = relativeDir)
                    // C2：把备选直链挂到 CloudLink 上，再由 onDownload 原样传给引擎。
                    // 上游的 pendingAltLinks 就是漏了「传参」这一步，altSources 恒为空、功能空转。
                    val prepared = withAltSources(s, file, fileCred, primary, pool)
                    val link = prepared.link
                    // 记下「这条直链是谁取的」：轮换出来的次账号也要能正确清理自己的 tr_* 目录（P1-17）
                    rememberCredential(link, fileCred)
                    prepared.cleanups.forEach { (altLink, altCred) -> rememberCredential(altLink, altCred) }
                    links = (listOf(link) + links).distinctBy { it.url }
                    val taskId = onDownload(link)
                    if (taskId == null) {
                        // 引擎拒收（协议不支持 / 地址为空）不能算成功：以前这里只跳过清理登记，
                        // 随后照样 markAdded + ok++，UI 报「成功 N 个」、直链按钮变「已加入」不可重试，
                        // 而下载队列里什么都没有（P1-16）。
                        failed++
                        if (firstError == null) firstError = "下载引擎拒绝加入队列"
                        AppLogger.warn("CloudDriveViewModel", "入队被下载引擎拒绝: ${file.name}")
                        continue
                    }
                    registerCleanup(taskId, link, fileCred)
                    // 每条备选链若也建了 tr_* 临时目录，按「取链时那个账号」分别清理
                    prepared.cleanups.forEach { (altLink, altCred) ->
                        registerCleanup(taskId, altLink, altCred)
                    }
                    markAdded(link.url)
                    ok++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    if (firstError == null) firstError = e.message
                    AppLogger.warn("CloudDriveViewModel", "取链失败 ${file.name}: ${e.message}", e)
                }
            }
        } finally {
            activeCredential = ""
        }
        return FetchSummary(ok, failed, firstError)
    }

    /**
     * C2：用账号池里的其它账号，对**同一个文件**各取一条直链。
     *
     * 三条纪律：
     * - 每条直链带自己那份请求头（`CloudDownloadHeaders.of` 按凭证算），绝不「换 URL 不换 Cookie」；
     * - 单个备选账号失败只跳过它，不影响主链入队（对齐上游 C1 的单条失败策略）；
     * - 备选链若也建了 `tr_*` 临时目录，把「链接 + 凭证」成对返回给调用方登记清理。
     *
     * 平台白名单与上游实际代码一致（QUARK / UC / XUNLEI / BAIDU，上游注释漏写了百度）。
     * 其它平台要么直链不绑定账号（139 / 123 / 光鸭 / 蓝奏），要么一个文件走不了多账号，
     * 强行多源只会拿到不同副本，交给引擎的 `Content-Range` 校验去兜底不如一开始就不做。
     */
    private suspend fun withAltSources(
        s: ShareSession,
        file: CloudFile,
        primaryCred: String,
        primary: CloudLink,
        pool: List<String>,
    ): AltSourcesResult {
        if (!multiSourceProvider() || platform !in MULTI_SOURCE_PLATFORMS || pool.size < 2) {
            return AltSourcesResult(primary, emptyList())
        }
        val sources = mutableListOf<DownloadSource>()
        val cleanups = mutableListOf<Pair<CloudLink, String>>()
        try {
            for (altCred in pool) {
                if (altCred == primaryCred || altCred.isBlank()) continue
                // 解析层刷新 token 时按「本次取链的凭证」写回对应账号（P3 的 activeCredential）
                activeCredential = altCred
                val alt = runCatching {
                    withContext(ioDispatcher) {
                        api.downloadLink(s, file.toShareFile(), altCred, platform)
                    }
                }.getOrElse { e ->
                    AppLogger.warn("CloudDriveViewModel", "C2 备选直链失败 ${file.name}: ${e.message}", e)
                    continue
                }
                if (alt.url.isBlank() || alt.url == primary.url) continue
                sources += DownloadSource(
                    url = alt.url,
                    referer = alt.referer.takeIf { it.isNotBlank() },
                    cookie = alt.cookie.takeIf { it.isNotBlank() },
                    userAgent = alt.userAgent,
                    origin = alt.origin,
                    secChUa = alt.secChUa,
                )
                if (!alt.cleanupDirFid.isNullOrBlank()) cleanups += alt to altCred
            }
        } finally {
            // 主链后续若触发 token 刷新，仍要写回主账号，不能停在最后一个备选账号上
            activeCredential = primaryCred
        }
        return AltSourcesResult(primary.copy(altSources = sources), cleanups)
    }

    /** C1 凭证池：主账号优先、去空去重；开关关 / 游客态 / GitHub / 单账号时退回原来那一条 */
    private fun credentialRotation(fallback: String): List<String> {
        if (!multiAccountProvider()) return listOf(fallback)
        // 游客链路必须继续用空凭证：不能把「无需登录」的分享强行变成登录态取链
        if (fallback.isBlank()) return listOf(fallback)
        // GitHub Token 只提升 API 限额，不参与网盘取链轮换
        if (platform == SharePlatform.GITHUB) return listOf(fallback)
        return accounts.allCredentials(platform).ifEmpty { listOf(fallback) }
    }

    private fun summaryMessage(summary: FetchSummary): String = buildString {
        if (summary.ok > 0) append(t().summaryOk(summary.ok))
        if (summary.failed > 0) {
            if (isNotEmpty()) append("，")
            append(t().summaryFailedCount(summary.failed))
            // 服务端返回的错误原文不翻译，保留排查线索
            summary.firstError?.let { append("（$it）") }
        }
        if (summary.ok == 0 && summary.failed > 0 && !accounts.isConfigured(platform)) {
            append("；${t().summaryNeedLogin}")
        }
    }.ifBlank { t().summaryEmpty }

    private fun loadDirectory(s: ShareSession, dirFid: String) {
        val cred = accounts.credentialFor(platform)
        workJob?.cancel()
        working = true
        workJob = scope.launch {
            try {
                val list = withContext(ioDispatcher) {
                    api.listFiles(s, dirFid, cred, platform)
                }
                currentDirFid = dirFid
                files = list.map { it.toCloudFile() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn("CloudDriveViewModel", "目录加载失败: ${e.message}", e)
                notice = t().directoryLoadFailed(e.message.orEmpty())
            } finally {
                working = false
            }
        }
    }

    // ------------------------------------------------------------ 搜索 / 排序 / 清空

    fun updateSearchQuery(value: String) {
        searchQuery = value
    }

    fun applySortMode(mode: CloudFileSort) {
        sortMode = mode
    }

    /** 排序按钮：名称 → 大小 → 类型 循环 */
    fun cycleSortMode() {
        sortMode = when (sortMode) {
            CloudFileSort.NAME -> CloudFileSort.SIZE
            CloudFileSort.SIZE -> CloudFileSort.TYPE
            CloudFileSort.TYPE -> CloudFileSort.NAME
        }
    }

    /** 只清直链条：文件树、选中项与后台任务都不动 */
    fun clearLinks() {
        links = emptyList()
        addedUrls = emptySet()
    }

    /** 清空整个解析结果并回到初始态（顶栏输入保留），不打断正在跑的取链任务 */
    fun clearResults() {
        state = CloudDriveState.Idle
        files = emptyList()
        links = emptyList()
        addedUrls = emptySet()
        selected = emptySet()
        pathNames = emptyList()
        dirStack.clear()
        session = null
        searchQuery = ""
        sortMode = CloudFileSort.NAME
        notice = null
        activePlatform = null
    }

    /** 取消并回到初始态（输入内容保留） */
    fun cancel() {
        resolveJob?.cancel()
        workJob?.cancel()
        resolveJob = null
        workJob = null
        working = false
        notice = null
        clearLinks()
        searchQuery = ""
        state = CloudDriveState.Idle
    }

    fun shutdown() {
        resolveJob?.cancel()
        workJob?.cancel()
        quotaJob?.cancel()
        pendingCleanups.clear()
        scope.cancel()
    }

}

/** 当前目录文件列表的排序方式 */
enum class CloudFileSort { NAME, SIZE, TYPE }

/** P3 账号管理面板的 UI 投影；**不带凭证原文**，只给掩码与备注 */
data class CloudAccountView(
    val id: String,
    val label: String,
    val masked: String,
    val isPrimary: Boolean,
    val remark: String,
)

private data class FetchSummary(val ok: Int, val failed: Int, val firstError: String?)

/** C2 预取结果；cleanups 是「备选链 → 取链凭证」的成对清单，清理时不能拆开 */
private data class AltSourcesResult(
    val link: CloudLink,
    val cleanups: List<Pair<CloudLink, String>>,
)

/** C2 覆盖平台：与上游 `ResolveViewModel.multiSourcePlatforms` 的实际集合一致（含百度） */
private val MULTI_SOURCE_PLATFORMS = setOf(
    SharePlatform.QUARK,
    SharePlatform.UC,
    SharePlatform.XUNLEI,
    SharePlatform.BAIDU,
)

private data class CleanupTarget(
    val platform: SharePlatform,
    val dirFid: String,
    val credential: String,
    /** 对应直链：清理完成后从「直链 → 凭证」映射里移除，避免内存随下载次数增长 */
    val url: String = "",
    /** 建目录时用的账号 id，用于把落盘清单里的同一条目摘掉 */
    val accountId: String = "",
)

package top.mcly99.dl.viewmodel

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import top.mcly99.dl.data.SettingsRepository
import top.mcly99.dl.data.network.GitHubApi
import top.mcly99.dl.data.network.GitHubMirror
import top.mcly99.dl.data.network.HttpClients
import top.mcly99.dl.data.network.UpdateCancelled
import top.mcly99.dl.data.network.UpdateChecker
import top.mcly99.dl.data.network.UpdateDownloader
import top.mcly99.dl.data.network.UpdateInfo
import top.mcly99.dl.data.network.UpdateInstaller
import top.mcly99.dl.engine.extractFileName
import top.mcly99.dl.engine.resolveProxy
import top.mcly99.dl.engine.sanitizeDownloadFileName
import top.mcly99.dl.engine.clouddrive.CloudDriveService
import top.mcly99.dl.engine.clouddrive.CloudDriveAccountStore
import top.mcly99.dl.engine.clouddrive.DefaultMyDriveApi
import top.mcly99.dl.viewmodel.MyDriveViewModel
import top.mcly99.dl.engine.clouddrive.CloudLink
import top.mcly99.dl.engine.clouddrive.sanitizeDirName
import top.mcly99.dl.engine.web.MediaCandidate
import top.mcly99.dl.engine.web.browser.BrowserCapture
import top.mcly99.dl.engine.web.downloadFileNameFor
import top.mcly99.dl.model.AppSettings
import top.mcly99.dl.model.BatchActionsEnabled
import top.mcly99.dl.model.DownloadStatus
import top.mcly99.dl.model.batchActionsEnabled
import top.mcly99.dl.model.UiDevice
import top.mcly99.dl.model.UiTask
import top.mcly99.dl.model.UiTransfer
import top.mcly99.dl.model.ViewType
import top.mcly99.dl.model.toUiDevice
import top.mcly99.dl.model.toUiTask
import top.mcly99.dl.model.toUiTransfer
import top.mcly99.dl.theme.UiStrings
import top.mcly99.dl.theme.multiAccountStrings
import top.mcly99.dl.theme.myDriveStrings
import top.mcly99.dl.theme.TransferExtraStrings
import top.mcly99.dl.theme.transferExtraStrings
import top.mcly99.dl.theme.stringsFor
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import top.mcly99.dl.utils.HostPlatform
import top.mcly99.dl.utils.formatBytes
import top.mcly99.dl.APP_VERSION
import top.mcly99.dl.utils.getClipboardUrl
import top.mcly99.dl.utils.isSelfCopied
import java.net.Proxy
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用级 ViewModel（P2-1）。
 *
 * 职责：
 * - 持有 [DownloadViewModel] / [TransferViewModel]
 * - 设置状态、持久化与下发
 * - 剪贴板轮询、系统通知编排
 * - 把引擎模型转换为 UI 模型并缓存（P2-2 / P2-3）
 *
 * `AppNew` 只负责布局与事件绑定，不再包含业务逻辑。
 */
class AppViewModel {
    val downloads = DownloadViewModel()
    val transfer = TransferViewModel()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** 下载完成后关机：常驻状态机，不随下载页销毁（与上游 1.3.4 同一口径） */
    val shutdownAfterComplete = ShutdownAfterCompleteController(scope)

    /** 已通知过的 (任务, 终态) 组合；任务离开终态时移除，重试后可再次通知 */
    private val notifiedTasks = ConcurrentHashMap.newKeySet<Pair<String, DownloadStatus>>()

    /** 由 AppNew 注入最新的通知回调（避免 remember 捕获旧 lambda） */
    var onNotification: (title: String, message: String) -> Unit = { _, _ -> }

    /**
     * 统一的对外通知出口：受「显示通知」开关约束。
     *
     * 以前只有 [observeNotifications] 里挡了开关，其余 5 处（抓网页入队失败、云盘入队失败、
     * 批量入队失败、清理分片结果）都直接调 [onNotification] —— 用户关掉通知照样被打扰（P1-11）。
     * 任何新增的通知都必须走这里，不要直接调 [onNotification]。
     */
    private fun notify(title: String, message: String) {
        if (!settings.showNotifications) return
        onNotification(title, message)
    }

    /**
     * 应用内一次性提示（几秒后自动消失）。
     *
     * 存在的理由：有些结果只适合「就地告诉用户」，而系统通知在 macOS 上依赖托盘气泡、
     * 用户还可能主动关掉通知 —— 那些场景下没有第二条反馈通道就等于没反馈（P1-11）。
     */
    var toast by mutableStateOf<String?>(null)
        private set

    private var toastJob: Job? = null

    fun showToast(message: String) {
        toast = message
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(AppConstants.TOAST_VISIBLE_MS)
            toast = null
        }
    }

    /**
     * 任务入队失败的统一反馈（P2-20）。
     *
     * 抓网页 / 云盘直链 / 手动添加这三处入队失败以前只调 [notify]，而 [notify] 受
     * 「显示通知」开关约束 —— 关掉通知后填错保存路径、点「添加任务」，既没有任务
     * 也没有任何提示，看起来像按钮失灵。与 [cleanupTaskSegments] 一样走双通道：
     * 系统通知按开关，应用内提示始终可见。
     */
    private fun reportEnqueueFailure(e: Throwable, fallback: String) {
        val message = enqueueFailureMessage(e, fallback)
        notify(strings.notifyDownloadFailed, message)
        showToast(message)
    }

    var settings by mutableStateOf(loadInitialSettings())
        private set

    // ---------- 自动更新 ----------

    private val updateChecker = UpdateChecker.fromApi(GitHubApi())
    private val updateDownloader = UpdateDownloader()

    /** 发现新版本时填充，驱动「发现新版本」对话框；null 表示不显示。 */
    var updateInfo by mutableStateOf<UpdateInfo?>(null)
        private set

    /** 是否正在下载更新包。 */
    var updateDownloading by mutableStateOf(false)
        private set

    /** 下载进度 0f..1f（细粒度，负数表示总量未知）。 */
    var updateProgress by mutableStateOf(0f)
        private set

    /** 检查更新的一次性状态（UI 层映射成文案）。 */
    var updateStatus by mutableStateOf<UpdateStatus?>(null)
        private set

    enum class UpdateStatus { CHECKING, UP_TO_DATE, DOWNLOAD_FAILED, OPEN_FAILED, UNTRUSTED_RELEASE }

    private var updateStatusJob: Job? = null

    /** 侧边栏版本处是否显示「有新版本」角标。 */
    var updateAvailable by mutableStateOf(false)
        private set

    /** 由 AppNew 注入的退出应用回调（Windows 启动安装器后需要干净退出）。 */
    var onExit: () -> Unit = {}

    /**
     * 所有网络链路共用的代理来源：三态（直连 / 手动 / 跟随系统）只在请求发生时解析，
     * 不缓存成构造时的快照。把规则收敛到一处，才不会出现「下载走代理、抓页面直连」。
     */
    private fun currentNetworkProxy(): Proxy? =
        resolveProxy(settings.proxyMode, settings.proxyHost, settings.proxyPort)

    /**
     * 「抓网页」视图状态。
     *
     * 代理用 lambda 现取而不是构造时快照：设置页改了代理要立刻生效。
     * 探测文件大小的开关同理 —— 设置里一勾就立刻生效，不必重启。
     */
    val catchWeb = CatchWebViewModel(
        proxyProvider = { currentNetworkProxy() },
        sizeProbeEnabled = { settings.probeMediaSize },
        // 深度抓取：起本机 Chromium 走 CDP。代理用 lambda 现取，与下载共用同一份设置
        browserCapture = BrowserCapture(
            proxyProvider = { currentNetworkProxy() },
        ),
        deepCatchEnabled = { settings.deepCatchEnabled },
    )

    /**
     * 网盘账号池：**全应用唯一一份**。
     *
     * 云盘页、我的网盘、GitHub Token、设置页的备份导出都指同一个实例；
     * 各自新建实例会互相覆盖落盘快照（见 [CloudDriveAccountStore.shared] 注释）。
     */
    private val cloudAccounts = CloudDriveAccountStore.shared

    /** 云盘解析器：与下载、抓网页共用同一份代理设置 */
    private val cloudDriveService = CloudDriveService()
    val cloudDrive = CloudDriveViewModel(
        api = cloudDriveService,
        accounts = cloudAccounts,
        // lambda 现取：设置页切换语言后，网盘提示立刻跟着变
        stringsProvider = { strings.cloudDriveStrings },
        // P3：C1 开关与账号文案都按当前设置现取，改完立即生效
        multiAccountProvider = { settings.multiAccountDownload },
        // C2 依附 C1：两个开关都开才预取备选直链（设置页与存储层还各有一道保险）
        multiSourceProvider = { settings.multiAccountDownload && settings.multiSourceDownload },
        accountStringsProvider = { multiAccountStrings(settings.language) },
    ).also { vm ->
        // 上次退出时没来得及清的 tr_* 临时目录（引擎把进行中的任务改成了 PAUSED，不是终态，
        // 所以那次不会触发清理）。放在构造之后调，别塞进 CloudDriveViewModel 的 init ——
        // 它依赖的属性按源码顺序初始化，init 里发起协程会读到还没构造好的字段（P1-18）。
        vm.resumePendingCleanups()
    }

    /**
     * P4「我的网盘」：与解析页并列，共用同一个账号池实例。
     * 迅雷 token 自动刷新后写回账号池，避免「卡片显示已登录但列目录一直 401」。
     */
    val myDriveAccounts: CloudDriveAccountStore get() = cloudAccounts
    val myDrive = MyDriveViewModel(
        api = DefaultMyDriveApi(onCredentialRefreshed = { platform, value ->
            cloudAccounts.updateCredential(platform, cloudAccounts.credentialFor(platform), value)
        }),
        accounts = cloudAccounts,
        stringsProvider = { myDriveStrings(settings.language) },
    )

    /** 在线观看：懒加载 libvlc，不点播放不占资源；清晰度探测与抓网页共用代理 */
    val player = PlayerViewModel(
        proxyProvider = { currentNetworkProxy() },
    )

    var currentView by mutableStateOf(ViewType.All)
        private set
    var addDialogOpen by mutableStateOf(false)
        private set
    var searchQuery by mutableStateOf("")
        private set

    /** 切换主视图（单向数据流：状态只能经方法修改） */
    fun switchView(view: ViewType) {
        currentView = view
    }

    /** 打开/关闭「添加任务」对话框 */
    fun openAddDialog() {
        addDialogOpen = true
    }

    fun closeAddDialog() {
        addDialogOpen = false
    }

    /** 更新搜索关键字 */
    fun updateSearchQuery(query: String) {
        searchQuery = query
    }

    val strings: UiStrings
        get() = stringsFor(settings.language)

    /** 传输界面的补充文案（不在 [UiStrings] 里，见 TransferExtraStrings 的说明） */
    val transferExtra: TransferExtraStrings
        get() = transferExtraStrings(settings.language)

    // ---- UI 模型缓存：只在引擎列表变化时重建，不在每次重组时 map ----

    val uiTasks: List<UiTask> by derivedStateOf {
        downloads.tasks.map { it.toUiTask() }
    }

    val uiDevices: List<UiDevice> by derivedStateOf {
        transfer.devices.map { device ->
            device.toUiDevice(online = transfer.deviceOnline[device.id])
        }
    }

    val uiTransfers: List<UiTransfer> by derivedStateOf {
        transfer.transferTasks.map { it.toUiTransfer() }
    }

    val visibleTasks: List<UiTask> by derivedStateOf {
        val keyword = searchQuery.trim()
        if (keyword.isEmpty()) {
            uiTasks
        } else {
            uiTasks.filter { task ->
                task.name.contains(keyword, ignoreCase = true) ||
                    task.url.contains(keyword, ignoreCase = true) ||
                    task.savePath.contains(keyword, ignoreCase = true)
            }
        }
    }

    val totalTaskCount: Int by derivedStateOf { uiTasks.size }
    val activeTaskCount: Int by derivedStateOf { uiTasks.count { it.isActiveTask } }
    val completedTaskCount: Int by derivedStateOf {
        uiTasks.count { it.status == DownloadStatus.COMPLETED }
    }
    /** 顶栏批量按钮的可用状态；口径见 [batchActionsEnabled]（P2-23） */
    val batchActions: BatchActionsEnabled by derivedStateOf { batchActionsEnabled(uiTasks) }
    val totalDownloadSpeed: Long by derivedStateOf {
        uiTasks.filter { it.status == DownloadStatus.DOWNLOADING }.sumOf { it.speed }
    }

    init {
        transfer.languageStrings = strings
        // 「抓网页」/「云盘」只负责找到地址，入队仍走下载这条唯一路径
        catchWeb.onDownload = ::addCatchWebDownload
        cloudDrive.onDownload = ::addCloudDriveDownload
        myDrive.onDownload = ::addCloudDriveDownload
        // 云盘页登录/登出后，「我的网盘」页共用同一账号池但不是 Compose state，
        // 靠这条通知重算登录态并刷新当前目录（未登录 error → 文件列表）
        cloudDrive.onAccountsChanged = myDrive::onAccountsChanged
        applySettingsToServices(settings, null)
        observeSettingsPersistence()
        observeClipboard()
        observeNotifications()
        observeCloudDriveCleanups()
        observeShutdownAfterComplete()
        // 默认 AUTO：启动时按 TTL 测速一次；显式选「直连」或已固定镜像时不会发探测请求
        GitHubMirror.ensureFresh()

        if (settings.autoStart) downloads.startAll()
    }

    /**
     * 抓网页结果里点「播放」。
     *
     * 名称兜底放在这里而不是播放器里：播放器只管「给我地址和标题」，
     * 不该知道「页面没给名称时用 URL 末段」这种展示规则。
     */
    fun playCandidate(candidate: MediaCandidate) {
        val title = candidate.name.ifBlank { extractFileName(candidate.url) }
        player.play(candidate, title)
    }

    /**
     * 抓网页结果里点「下载」。
     *
     * 三件事都和手动添加任务不同：
     * - **文件名**用页面上看到的名称（「第 3 集」），而不是 URL 末段的 `index`；
     * - **Referer** 带上发现它的页面地址，否则跨域 CDN 会 403；
     * - 保存目录显式拼好：`addTask` 只有在路径是**已存在的目录**时才当目录用。
     */
    private fun addCatchWebDownload(candidate: MediaCandidate) {
        runCatching {
            val dir = Paths.get(settings.downloadPath)
            runCatching { dir.toFile().mkdirs() }
            val target = if (dir.toFile().isDirectory) dir.resolve(downloadFileNameFor(candidate)) else null
            downloads.addDownload(
                url = candidate.url,
                path = target,
                threads = settings.defaultThreads,
                referer = candidate.referer,
                // 深度抓取带回来的会话：签名地址缺它就会 403（v6-P1-3）
                cookie = candidate.cookie,
            )
        }
            .onSuccess { catchWeb.markAdded(candidate.url) }
            .onFailure { e ->
                AppLogger.warn("AppViewModel", "抓网页任务入队失败: ${e.message}", e)
                reportEnqueueFailure(e, candidate.url)
            }
    }

    /**
     * 云盘直链入队。
     *
     * 与手动添加任务不同：必须把网盘返回的 Referer 与会话 Cookie 带上，
     * 否则 CDN 防盗链会直接 403 / 412；文件名用分享里的真实名称而不是 URL 末段。
     */
    private fun addCloudDriveDownload(link: CloudLink): String? {
        return runCatching {
            val dir = Paths.get(settings.downloadPath)
            runCatching { dir.toFile().mkdirs() }
            // 「下载整个文件夹」：按分享里的目录层级在下载目录下建子目录，
            // 单文件下载的 relativeDir 为空，行为与原来完全一致。
            val targetDir = link.relativeDir
                .split('/')
                .filter { it.isNotBlank() }
                .fold(dir) { acc, segment -> acc.resolve(sanitizeDirName(segment)) }
            if (targetDir != dir) runCatching { targetDir.toFile().mkdirs() }
            val target = if (targetDir.toFile().isDirectory) {
                targetDir.resolve(sanitizeDownloadFileName(link.filename, extractFileName(link.url)))
            } else {
                null
            }
            downloads.addDownload(
                url = link.url,
                path = target,
                threads = settings.defaultThreads,
                referer = link.referer,
                cookie = link.cookie.takeIf { it.isNotBlank() },
                // 平台专用 UA / Origin：不带的话 115、迅雷、百度、UC、蓝奏优享会被 CDN
                // 按「网页端」处理（拒绝大文件直链 / 限速 / 403）。具体口径见 CloudDownloadHeaders。
                userAgent = link.userAgent,
                origin = link.origin,
                secChUa = link.secChUa,
                // 分享列表里本来就有大小，直接带进任务：探测 HEAD 对夸克这类 CDN 不可靠，
                // 不传的话任务会长时间停在「0 B」、进度 0%
                totalSize = link.size,
                // C2：备选直链随主链一起入队；上游的 pendingAltLinks 就是漏了这一步才空转
                altSources = link.altSources,
            )
        }
            .onSuccess { cloudDrive.markAdded(link.url) }
            .onFailure { e ->
                AppLogger.warn("AppViewModel", "云盘任务入队失败: ${e.message}", e)
                reportEnqueueFailure(e, link.filename)
            }
            // 返回 taskId：CloudDriveViewModel 要用它把「任务终态」与
            // 「这条直链在用户网盘里建的临时转存目录」关联起来清理
            .getOrNull()
    }

    /**
     * 监听下载任务终态，回收网盘转存兜底留下的临时目录。
     *
     * 与通知编排共用同一个 snapshotFlow 语义，但单独一条收集链：清理是网络 IO，
     * 不该被「用户关掉系统通知」这种展示设置影响。
     */
    private fun observeCloudDriveCleanups() {
        scope.launch {
            snapshotFlow { downloads.tasks }.collect { tasks ->
                val knownIds = tasks.mapTo(HashSet()) { it.id }
                val settledIds = tasks.mapNotNullTo(HashSet()) { task ->
                    task.id.takeIf { task.status in TERMINAL_DOWNLOAD_STATUSES }
                }
                cloudDrive.onDownloadTasksChanged(knownIds, settledIds)
            }
        }
    }

    private companion object {
        val TERMINAL_DOWNLOAD_STATUSES = setOf(
            DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED,
        )
    }

    private fun loadInitialSettings(): AppSettings {
        // 设置只有一个真源：配置文件。引擎的当前值由 DownloadViewModel.init 的 loadSettings()
        // 从同一个文件注入，这里不再用引擎值反写（v4-P2-5：避免依赖初始化顺序）。
        return SettingsRepository.load()
    }

    /**
     * 开关 + 任务列表共用一条收集链：设置改完立即生效，任务每次变化（含进度刷新）
     * 都重新判定；这两份状态必须来自同一次快照，否则「刚勾上开关」可能漏掉已排空的队列。
     */
    private fun observeShutdownAfterComplete() {
        scope.launch {
            snapshotFlow { settings.shutdownAfterComplete to downloads.tasks }
                .collect { (enabled, tasks) ->
                    shutdownAfterComplete.applyEnabled(enabled)
                    shutdownAfterComplete.onTasksChanged(tasks)
                }
        }
    }

    /** 设置防抖写盘协程：shutdown 要先 cancelAndJoin 再同步落盘（P1-06） */
    private var settingsPersistenceJob: Job? = null

    private fun observeSettingsPersistence() {
        settingsPersistenceJob = scope.launch {
            // collectLatest + delay 代替 debounce，避免 FlowPreview 注解污染
            snapshotFlow { settings }.collectLatest { latest ->
                delay(AppConstants.SETTINGS_SAVE_DEBOUNCE_MS)
                // 写文件是阻塞 IO，不能在 Main 上做（v7-P3-1）；防抖只降低了频率，不改变线程归属
                withContext(Dispatchers.IO) { SettingsRepository.save(latest) }
            }
        }
    }

    private fun observeClipboard() {
        scope.launch {
            var lastClipboardUrl = ""
            while (isActive) {
                // AWT 剪贴板是同步阻塞调用：剪贴板被其它进程占用（Office 复制大对象、
                // 剪贴板管理器、远程桌面）时可能卡住。这里在 Main 作用域里跑，
                // 必须切到 IO，否则每 2 秒阻塞一次 Compose 的主调度线程（v4-P2-6）。
                val url = withContext(Dispatchers.IO) { getClipboardUrl() }
                if (url != null && url != lastClipboardUrl) {
                    lastClipboardUrl = url
                    // 三个条件缺一不可（P1-12）：
                    // · 开关打开时才弹；
                    // · 关闭期间也更新基线 —— 否则用户一打开开关，剪贴板里躺着的老链接会立刻弹；
                    // · 跳过本应用自己刚写进去的地址（复制分享链接不该被自己当成新链接）。
                    if (settings.clipboardWatch && !isSelfCopied(url)) openAddDialog()
                }
                delay(AppConstants.CLIPBOARD_POLL_MS)
            }
        }
    }

    private fun observeNotifications() {
        scope.launch {
            snapshotFlow { downloads.tasks }.collect { tasks ->
                // 移除已删除任务的记录，避免集合无限增长
                val ids = tasks.mapTo(HashSet()) { it.id }
                notifiedTasks.removeIf { it.first !in ids }
                if (!settings.showNotifications) return@collect

                val s = strings
                for (task in tasks) {
                    val completedKey = task.id to DownloadStatus.COMPLETED
                    val failedKey = task.id to DownloadStatus.FAILED
                    when (task.status) {
                        DownloadStatus.COMPLETED -> {
                            notifiedTasks.remove(failedKey)
                            if (notifiedTasks.add(completedKey)) {
                                notify(s.notifyDownloadComplete, task.fileName)
                            }
                        }
                        DownloadStatus.FAILED -> {
                            notifiedTasks.remove(completedKey)
                            if (notifiedTasks.add(failedKey)) {
                                notify(s.notifyDownloadFailed, task.errorMessage ?: task.fileName)
                            }
                        }
                        else -> {
                            // 离开终态（重试/重新入队）：允许下次完成或失败再次通知
                            notifiedTasks.remove(completedKey)
                            notifiedTasks.remove(failedKey)
                        }
                    }
                }
            }
        }
    }

    /** 设置页回调：更新状态并立即下发到引擎/服务 */
    fun updateSettings(newSettings: AppSettings) {
        val old = settings
        if (old == newSettings) return
        settings = newSettings
        applySettingsToServices(newSettings, old)
    }

    private fun applySettingsToServices(newSettings: AppSettings, old: AppSettings?) {
        if (old == null || old.downloadPath != newSettings.downloadPath) {
            runCatching { downloads.changeDownloadPath(Paths.get(newSettings.downloadPath)) }
                .onFailure { AppLogger.warn("AppViewModel", "下载目录无效: ${newSettings.downloadPath}", it) }
            transfer.saveDir = newSettings.downloadPath
        }
        if (old == null || old.maxConcurrent != newSettings.maxConcurrent) {
            downloads.changeMaxConcurrent(newSettings.maxConcurrent)
        }
        if (old == null || old.defaultThreads != newSettings.defaultThreads) {
            downloads.changeDownloadThreads(newSettings.defaultThreads)
        }
        if (old == null || old.speedLimit != newSettings.speedLimit) {
            downloads.applySpeedLimit(newSettings.speedLimit)
        }
        if (old == null ||
            old.proxyMode != newSettings.proxyMode ||
            old.proxyHost != newSettings.proxyHost ||
            old.proxyPort != newSettings.proxyPort
        ) {
            // 一个 lambda 供给全部链路：HttpClients 按地址指纹重建 OkHttp 客户端，
            // DownloadEngine 每个请求现取；「跟随系统」在系统代理变化后也能跟上。
            val provider: () -> Proxy? = { currentNetworkProxy() }
            HttpClients.setProxyProvider(provider)
            downloads.applyProxyProvider(provider)
        }
        if (old == null || old.http2Enabled != newSettings.http2Enabled) {
            // 启动时（old == null）按持久化值装配，运行中设置页切换也立即生效
            HttpClients.setHttp2Enabled(newSettings.http2Enabled)
        }
        if (old == null || old.keepHlsSegments != newSettings.keepHlsSegments) {
            downloads.applyKeepHlsSegments(newSettings.keepHlsSegments)
        }
        if (old == null || old.lanDiscoveryEnabled != newSettings.lanDiscoveryEnabled) {
            transfer.setDiscoveryEnabled(newSettings.lanDiscoveryEnabled)
        }
        if (old == null ||
            old.lanServerEnabled != newSettings.lanServerEnabled ||
            old.lanServerPort != newSettings.lanServerPort
        ) {
            transfer.setWebServerEnabled(newSettings.lanServerEnabled, newSettings.lanServerPort)
        }
        if (old == null || old.language != newSettings.language) {
            transfer.languageStrings = stringsFor(newSettings.language)
        }
    }

    fun onLanServerEnabled(enabled: Boolean) {
        updateSettings(settings.copy(lanServerEnabled = enabled))
    }

    fun sendToDevice(deviceId: String) {
        val device = transfer.devices.find { it.id == deviceId } ?: return
        transfer.sendFileToDevice(device)
    }

    fun sendFolderToDevice(deviceId: String) {
        val device = transfer.devices.find { it.id == deviceId } ?: return
        transfer.sendFolderToDevice(device)
    }

    // ---- 传输历史：取消 / 移除 / 清空 / 打开接收目录（P1-23 之前这些能力全部没有入口）----

    fun cancelTransfer(taskId: String) = transfer.cancelTask(taskId)

    fun removeTransfer(taskId: String) = transfer.removeTask(taskId)

    fun clearFinishedTransfers() = transfer.clearFinished()

    fun openReceiveFolder() {
        if (!transfer.openReceivedFilesLocation()) {
            showToast(transferExtra.openReceiveDirFailed)
        }
    }

    fun addDownloads(urls: List<String>, customName: String?, savePath: String, threads: Int) {
        urls.forEachIndexed { index, url ->
            runCatching {
                val dir = Paths.get(savePath)
                if (dir.toFile().exists() && !dir.toFile().isDirectory) {
                    throw IllegalArgumentException("保存路径不是文件夹：$savePath")
                }
                if (!dir.toFile().mkdirs() && !dir.toFile().isDirectory) {
                    throw IllegalStateException("无法创建保存目录：$savePath")
                }
                // 目录先建好，引擎只把已存在的目录当作目录；自定义名称同样要清洗
                val path = if (index == 0 && !customName.isNullOrBlank()) {
                    dir.resolve(sanitizeDownloadFileName(customName, extractFileName(url)))
                } else {
                    dir
                }
                downloads.addDownload(url, path, threads)
            }.onFailure { e ->
                reportEnqueueFailure(e, url)
            }
        }
    }

    fun pauseTask(id: String) = downloads.pauseDownload(id)
    fun resumeTask(id: String) = downloads.resumeDownload(id)
    fun retryTask(id: String) = downloads.startDownload(id)

    /**
     * 任务卡片「×」：默认只移除记录，不碰磁盘文件。
     *
     * 已完成且文件还在时会置 `pendingRemoveTask`，由 UI 弹出二选一确认（P0-1）。
     */
    fun removeTask(id: String) = downloads.requestRemoveTask(id)

    /** 待确认移除的任务（已完成 + 文件仍在时才有值） */
    val pendingRemoveTask: UiTask? get() = downloads.pendingRemove?.toUiTask()

    fun cancelRemoveTask() = downloads.cancelPendingRemove()

    /** 「仅移除任务」：保留磁盘上的成品 */
    fun removeTaskKeepingFile() = downloads.confirmRemoveKeepingFile()

    /** 「移除并删除文件」：用户明确选择连成品一起删 */
    fun removeTaskWithFile() = downloads.confirmRemoveWithFile()

    fun openTaskFolder(id: String) = downloads.openFileLocation(id)

    /** 清理 HLS 残留分片（失败/降级完成时的那份 `{name}.hls`），顺手告诉用户释放了多少 */
    fun cleanupTaskSegments(id: String) {
        val freed = downloads.cleanupHlsSegments(id)
        val message = if (freed > 0) {
            strings.notifySegmentsCleaned(formatBytes(freed))
        } else {
            strings.notifyNoSegments
        }
        // 两条通道一起走：系统通知受开关约束，而应用内提示始终可见。
        // 以前只发系统通知 —— 关掉通知开关、或 macOS 上托盘气泡不显示时，
        // 用户点完「清理分片」完全没有反馈（P1-11）。
        notify(strings.cleanupSegments, message)
        showToast(message)
    }
    fun startAll() = downloads.startAll()
    fun pauseAll() = downloads.pauseAll()
    fun clearCompleted() = downloads.clearCompleted()

    /** 全部删除（已完成 + 未完成）；UI 侧必须先弹确认框再调用 */
    fun removeAllTasks() = downloads.removeAllTasks()

    fun openLogDirectory(): Boolean = AppLogger.openLogDirectory()

    // ---------- 自动更新逻辑 ----------

    /**
     * 检查更新。
     *
     * @param silent true 表示启动时的后台静默检查：不弹「已是最新」提示，
     *   只在发现新版本时显示对话框与侧边栏角标；false 表示用户手动点击，任何结果都给反馈。
     */
    /** 单飞：连点「检查更新」或启动静默检查并发时，后一次先取消前一次，避免结果互相覆盖（P1-6） */
    private var updateCheckJob: kotlinx.coroutines.Job? = null

    fun checkForUpdate(silent: Boolean = false) {
        if (updateDownloading) return
        updateCheckJob?.cancel()
        updateCheckJob = scope.launch {
            if (!silent) showUpdateStatus(UpdateStatus.CHECKING)
            val info = runCatching { updateChecker.check(APP_VERSION) }
                .onFailure { AppLogger.warn("Update", "检查更新失败: ${it.message}", it) }
                .getOrNull()
            if (!isActive) return@launch // 已被更新的检查取代，不要写回状态
            if (info != null) {
                updateInfo = info
                updateAvailable = true
                if (!silent) clearUpdateStatus() // 弹窗接管提示，避免停留在「正在检查…」
            } else if (!silent) {
                showUpdateStatus(UpdateStatus.UP_TO_DATE)
            }
        }
    }

    /** 下载并安装当前 [updateInfo] 对应的安装包。 */
    fun downloadAndInstall() {
        val info = updateInfo ?: return
        if (updateDownloading) return
        // P1-08：digest 是唯一的独立信任锚。旧 Release 没有 digest，
        // 只回退 size 校验不足以挡住「同 size 的伪造包」被直接执行，一律不自动安装；
        // 仍能发现新版本 / 看更新说明，由弹窗「前往 Release 页面」手动下载兜底。
        val digest = info.asset.sha256OrNull()
        if (digest.isNullOrBlank()) {
            AppLogger.warn("Update", "Release ${info.version} 未提供 sha-256 digest，拒绝自动安装")
            showUpdateStatus(UpdateStatus.UNTRUSTED_RELEASE)
            return
        }
        updateDownloading = true
        updateProgress = 0f
        scope.launch {
            try {
                val file = updateDownloader.download(
                    asset = info.asset,
                    onProgress = { p -> updateProgress = p.fraction },
                    expectedSha256 = digest,
                )
                // 安装前重新读盘校验：覆盖 Files.move 之后到 ProcessBuilder.start 之前的 TOCTOU
                if (!updateDownloader.verify(file, info.asset, digest)) {
                    AppLogger.warn("Update", "安装前二次校验失败，拒绝启动安装器: ${file.fileName}")
                    updateDownloading = false
                    showUpdateStatus(UpdateStatus.DOWNLOAD_FAILED)
                    return@launch
                }
                AppLogger.info("Update", "下载完成，准备打开: ${file.fileName}")
                updateInfo = null
                updateDownloading = false
                val ok = UpdateInstaller.open(file, HostPlatform.current(), onExit)
                if (!ok) showUpdateStatus(UpdateStatus.OPEN_FAILED)
            } catch (e: UpdateCancelled) {
                AppLogger.info("Update", "用户取消下载")
                updateDownloading = false
            } catch (e: Exception) {
                AppLogger.warn("Update", "下载更新失败: ${e.message}", e)
                updateDownloading = false
                showUpdateStatus(UpdateStatus.DOWNLOAD_FAILED)
            }
        }
    }

    /** 用户点「稍后」：关闭对话框但保留角标，下次启动仍会提示。 */
    fun dismissUpdateDialog() {
        updateInfo = null
    }

    /** 取消正在进行的下载。 */
    fun cancelUpdateDownload() {
        updateDownloader.cancel()
    }

    fun clearUpdateStatus() {
        updateStatus = null
    }

    private fun showUpdateStatus(status: UpdateStatus) {
        updateStatusJob?.cancel()
        updateStatus = status
        updateStatusJob = scope.launch {
            delay(AppConstants.TOAST_VISIBLE_MS)
            updateStatus = null
        }
    }

    /** 启动时调用：尊重设置开关，延迟几秒后台静默检查。 */
    fun startAutoUpdateCheckIfEnabled() {
        if (!settings.autoCheckUpdate) return
        scope.launch {
            delay(AppConstants.UPDATE_CHECK_STARTUP_DELAY_MS)
            checkForUpdate(silent = true)
        }
    }

    fun shutdown() {
        // 先取消并等待防抖写盘协程：cancel 无法中断已进入 Dispatchers.IO 的阻塞 save，
        // 只有 join 才能保证下面这次同步写是「最后一次」，不会与旧写交错（P1-06）。
        runCatching {
            runBlocking { settingsPersistenceJob?.cancelAndJoin() }
        }.onFailure { e ->
            AppLogger.warn("AppViewModel", "等待设置防抖写盘结束失败: ${e.message}", e)
        }
        // 界面销毁时把最后一次设置同步落盘，避免 debounce 中的修改丢失
        updateDownloader.cancel()
        runCatching { SettingsRepository.save(settings) }
        catchWeb.shutdown()
        cloudDrive.shutdown()
        myDrive.shutdown()
        player.shutdown()
        transfer.cleanup()
        downloads.shutdown()
        // 应用退出不能把倒计时/关机动作带到进程外
        shutdownAfterComplete.dispose()
        scope.cancel()
    }
}

/**
 * 入队失败提示文案：优先用异常自带的说明（例如「保存路径不是文件夹」），
 * 异常没有 message 时退回链接 / 文件名，保证双通道里至少有一条能定位问题（P2-20）。
 * 抽成纯函数便于单测，不依赖 ViewModel 实例。
 */
internal fun enqueueFailureMessage(e: Throwable, fallback: String): String =
    e.message?.takeIf { it.isNotBlank() } ?: fallback

package top.mcly99.dl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.mcly99.dl.model.ViewType
import top.mcly99.dl.theme.FlowDownColors
import top.mcly99.dl.theme.FlowDownTheme
import top.mcly99.dl.theme.LocalStrings
import top.mcly99.dl.theme.LocalTaskActionStrings
import top.mcly99.dl.theme.myDriveStrings
import top.mcly99.dl.theme.taskActionStrings
import top.mcly99.dl.theme.removeTaskStrings
import top.mcly99.dl.theme.shutdownStrings
import top.mcly99.dl.theme.updateStrings
import top.mcly99.dl.utils.AppLogger
import top.mcly99.dl.ui.components.AddTaskDialog
import top.mcly99.dl.ui.components.CatchWebHeaderState
import top.mcly99.dl.ui.components.CatchWebScreen
import top.mcly99.dl.ui.components.CloudDriveHeaderState
import top.mcly99.dl.ui.components.CloudDriveLoginScreen
import top.mcly99.dl.ui.components.CloudDriveScreen
import top.mcly99.dl.ui.components.ConfirmDialog
import top.mcly99.dl.ui.components.HeaderBar
import top.mcly99.dl.ui.components.MyDriveScreen
import top.mcly99.dl.ui.components.PlayerScreen
import top.mcly99.dl.ui.components.RemoveTaskDialog
import top.mcly99.dl.ui.components.SettingsScreen
import top.mcly99.dl.ui.components.ShutdownCountdownDialog
import top.mcly99.dl.ui.components.SidebarPanel
import top.mcly99.dl.ui.components.SidebarStats
import top.mcly99.dl.ui.components.StatusFooter
import top.mcly99.dl.ui.components.TaskListPanel
import top.mcly99.dl.ui.components.ToastBar
import top.mcly99.dl.ui.components.TransferScreen
import top.mcly99.dl.ui.components.UpdateDialog
import top.mcly99.dl.viewmodel.AppViewModel
import top.mcly99.dl.viewmodel.LinkPicker

/**
 * 应用主界面（P2-1：只保留布局与事件绑定）。
 *
 * 业务状态集中在 [AppViewModel]：下载/传输子 ViewModel、设置持久化、
 * 剪贴板监听、通知与引擎下发。这里不再直接操作引擎或读写设置文件。
 */
@Composable
fun AppNew(
    onMinimizeToTrayChange: (Boolean) -> Unit = {},
    onNotification: (String, String) -> Unit = { _, _ -> },
    onExit: () -> Unit = {},
    /** 运行时语言上报：窗口标题 / 托盘在 AppNew 之外，拿不到这里的 settings（P2-22） */
    onLanguageChange: (String) -> Unit = {},
) {
    val viewModel = remember { AppViewModel() }

    SideEffect {
        // 每次重组都同步最新回调，避免 remember 捕获旧 lambda
        viewModel.onNotification = onNotification
        viewModel.onExit = onExit
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.shutdown() }
    }

    val settings = viewModel.settings
    val strings = viewModel.strings
    // P4 文案独立选表，不占 UiStrings 构造槽位
    val myDrive = myDriveStrings(settings.language)
    val taskActions = taskActionStrings(settings.language)
    val shutdownText = shutdownStrings(settings.language)
    val removeTaskText = removeTaskStrings(settings.language)
    val updateText = updateStrings(settings.language)

    // 启动后延迟做一次后台更新检查（开关在 VM 内判断），只在发现新版本时打扰用户
    LaunchedEffect(Unit) {
        viewModel.startAutoUpdateCheckIfEnabled()
    }

    // 把 VM 的更新状态枚举映射成当前语言的文案
    val updateStatusText = when (viewModel.updateStatus) {
        AppViewModel.UpdateStatus.CHECKING -> updateText.checking
        AppViewModel.UpdateStatus.UP_TO_DATE -> updateText.upToDate
        AppViewModel.UpdateStatus.DOWNLOAD_FAILED -> updateText.downloadFailed
        AppViewModel.UpdateStatus.OPEN_FAILED -> updateText.openFailed
        AppViewModel.UpdateStatus.UNTRUSTED_RELEASE -> updateText.untrustedRelease
        null -> null
    }

    // 「全部删除」确认框的开关：放 UI 层而不是 VM，弹窗只是瞬时交互状态，不需要持久化
    var clearAllConfirmOpen by remember { mutableStateOf(false) }

    // 顶栏的「抓网页」参数：用 remember 保住 lambda 的引用相等，
    // 否则每次重组都新建对象，HeaderBar 永远无法跳过重组
    val catchWebHeader = remember(viewModel.catchWeb.url, viewModel.catchWeb.busy) {
        CatchWebHeaderState(
            url = viewModel.catchWeb.url,
            busy = viewModel.catchWeb.busy,
            onUrlChange = viewModel.catchWeb::updateUrl,
            onSubmit = viewModel.catchWeb::submit,
        )
    }

    // 云盘顶栏：URL / 提取码 / 解析中都要触发重组，其余字段用 remember 保住 lambda 引用
    val cloudDriveHeader = remember(
        viewModel.cloudDrive.url,
        viewModel.cloudDrive.passcode,
        viewModel.cloudDrive.busy,
    ) {
        CloudDriveHeaderState(
            url = viewModel.cloudDrive.url,
            passcode = viewModel.cloudDrive.passcode,
            busy = viewModel.cloudDrive.busy,
            onUrlChange = viewModel.cloudDrive::updateUrl,
            onPasscodeChange = viewModel.cloudDrive::updatePasscode,
            onSubmit = viewModel.cloudDrive::submit,
            onShowHistory = { viewModel.cloudDrive.openLinkPicker(LinkPicker.HISTORY) },
            onShowBookmarks = { viewModel.cloudDrive.openLinkPicker(LinkPicker.BOOKMARKS) },
        )
    }

    LaunchedEffect(settings.minimizeToTray) {
        onMinimizeToTrayChange(settings.minimizeToTray)
    }

    // 窗口标题与托盘菜单定义在 main.kt（AppNew 之外），拿不到这里的 settings。
    // 把运行时语言上报出去，切语言时它们才会跟着变（P2-22）。
    LaunchedEffect(settings.language) {
        onLanguageChange(settings.language)
    }

    FlowDownTheme(theme = settings.theme) {
        CompositionLocalProvider(
            LocalStrings provides strings,
            LocalTaskActionStrings provides taskActions,
        ) {
            // 外层 Box 是必须的：播放页要盖住整个 Row（含侧栏），两者得有共同父布局
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize().background(FlowDownColors.Background)) {
                    SidebarPanel(
                        currentView = viewModel.currentView,
                        onViewChange = { viewModel.switchView(it) },
                        stats = SidebarStats(
                            total = viewModel.totalTaskCount,
                            downloading = viewModel.activeTaskCount,
                            completed = viewModel.completedTaskCount,
                        ),
                        myDriveLabel = myDrive.title,
                        updateAvailable = viewModel.updateAvailable,
                    )

                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        HeaderBar(
                            currentView = viewModel.currentView,
                            searchQuery = viewModel.searchQuery,
                            onSearchChange = { viewModel.updateSearchQuery(it) },
                            onAddClick = { viewModel.openAddDialog() },
                            onStartAll = viewModel::startAll,
                            onPauseAll = viewModel::pauseAll,
                            onClearCompleted = viewModel::clearCompleted,
                            onClearAll = { clearAllConfirmOpen = true },
                            hasTasks = viewModel.totalTaskCount > 0,
                            canStartAll = viewModel.batchActions.startAll,
                            canPauseAll = viewModel.batchActions.pauseAll,
                            canClearCompleted = viewModel.batchActions.clearCompleted,
                            totalDownloadSpeed = viewModel.totalDownloadSpeed,
                            totalUploadSpeed = viewModel.transfer.totalUploadSpeed,
                            catchWeb = catchWebHeader,
                            cloudDrive = cloudDriveHeader,
                            myDriveTitle = myDrive.title,
                        )

                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (viewModel.currentView) {
                                ViewType.CatchWeb -> CatchWebScreen(
                                    state = viewModel.catchWeb.state,
                                    addedUrls = viewModel.catchWeb.addedUrls,
                                    sizes = viewModel.catchWeb.sizes,
                                    notice = viewModel.catchWeb.notice,
                                    onDownload = viewModel.catchWeb::download,
                                    onPlay = viewModel::playCandidate,
                                    onRemove = { viewModel.catchWeb.removeCandidate(it.url) },
                                    onDownloadAll = viewModel.catchWeb::downloadAll,
                                    onCancel = viewModel.catchWeb::cancel,
                                    modifier = Modifier.fillMaxSize(),
                                )

                                ViewType.CloudDrive -> CloudDriveScreen(
                                    vm = viewModel.cloudDrive,
                                    modifier = Modifier.fillMaxSize(),
                                )

                                ViewType.MyDrive -> MyDriveScreen(
                                    vm = viewModel.myDrive,
                                    strings = myDrive,
                                    onLogin = viewModel.cloudDrive::openLogin,
                                    modifier = Modifier.fillMaxSize(),
                                )

                                ViewType.Transfer -> TransferScreen(
                                    devices = viewModel.uiDevices,
                                    transfers = viewModel.uiTransfers,
                                    settings = settings,
                                    onLanServerEnabled = viewModel::onLanServerEnabled,
                                    onSendToDevice = viewModel::sendToDevice,
                                    onSendFolderToDevice = viewModel::sendFolderToDevice,
                                    onCancelTransfer = viewModel::cancelTransfer,
                                    onRemoveTransfer = viewModel::removeTransfer,
                                    onClearFinished = viewModel::clearFinishedTransfers,
                                    onOpenReceiveDir = viewModel::openReceiveFolder,
                                    webServerRunning = viewModel.transfer.webServerRunning,
                                    webServerError = viewModel.transfer.webServerError,
                                    webServerIp = viewModel.transfer.webServerIp,
                                    webServerUrl = viewModel.transfer.webServerUrl,
                                    webServerPort = viewModel.transfer.webServerPort,
                                    discoveryRunning = viewModel.transfer.discoveryRunning,
                                    fileServerRunning = viewModel.transfer.fileServerRunning,
                                    extra = viewModel.transferExtra,
                                    modifier = Modifier.fillMaxSize(),
                                )

                                ViewType.Settings -> SettingsScreen(
                                    settings = settings,
                                    onUpdate = viewModel::updateSettings,
                                    onCheckUpdate = { viewModel.checkForUpdate(silent = false) },
                                    updateStatusText = updateStatusText,
                                    accountStore = viewModel.myDriveAccounts,
                                    modifier = Modifier.fillMaxSize(),
                                )

                                else -> TaskListPanel(
                                    currentView = viewModel.currentView,
                                    tasks = viewModel.visibleTasks,
                                    onPause = viewModel::pauseTask,
                                    onResume = viewModel::resumeTask,
                                    onRetry = viewModel::retryTask,
                                    onRemove = viewModel::removeTask,
                                    onOpenFolder = viewModel::openTaskFolder,
                                    onCleanupSegments = viewModel::cleanupTaskSegments,
                                    searchKeyword = viewModel.searchQuery,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        StatusFooter(
                            maxConcurrent = settings.maxConcurrent,
                            defaultThreads = settings.defaultThreads,
                            stats = SidebarStats(
                                total = viewModel.totalTaskCount,
                                downloading = viewModel.activeTaskCount,
                                completed = viewModel.completedTaskCount,
                            ),
                        )
                    }
                }

                // 播放页盖住整个界面（含侧栏与顶栏），Esc 返回；不做独立窗口，
                // 否则任务栏会多一个条目，还要同步两边的关闭状态
                if (viewModel.player.open) {
                    PlayerScreen(
                        state = viewModel.player.state,
                        frame = viewModel.player.frame,
                        onClose = viewModel.player::close,
                        onTogglePause = viewModel.player::togglePause,
                        onSeek = viewModel.player::seekTo,
                        modifier = Modifier.fillMaxSize(),
                        qualities = viewModel.player.qualities,
                        selectedQualityUrl = viewModel.player.selectedQualityUrl,
                        onSelectQuality = viewModel.player::selectQuality,
                        onSelectAutoQuality = viewModel.player::selectAutoQuality,
                        onRetry = viewModel.player::retry,
                        onVolumeChange = viewModel.player::setVolume,
                        onToggleMute = viewModel.player::toggleMute,
                        onViewportChange = viewModel.player::setViewport,
                    )
                }

                // 网盘登录页也盖住整个界面：内嵌 Chromium 在软件内部打开，
                // 不新建窗口、不跳外部浏览器（与 YunX 的做法一致）
                viewModel.cloudDrive.loginPlatform?.let { platform ->
                    CloudDriveLoginScreen(
                        platform = platform,
                        vm = viewModel.cloudDrive,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                AddTaskDialog(
                    open = viewModel.addDialogOpen,
                    onDismiss = { viewModel.closeAddDialog() },
                    defaultSavePath = settings.downloadPath,
                    defaultThreads = settings.defaultThreads,
                    onAdd = viewModel::addDownloads,
                )

                // 顶栏「全部删除」的二次确认：连未完成的任务一起清，误触代价太大，
                // 所以不在 HeaderBar 内部直接删，而是先问一遍
                ConfirmDialog(
                    open = clearAllConfirmOpen,
                    title = strings.deleteAllStrings.title,
                    message = strings.deleteAllStrings.message(viewModel.totalTaskCount),
                    confirmText = strings.deleteAllStrings.confirm,
                    dismissText = strings.dialogCancel,
                    onConfirm = viewModel::removeAllTasks,
                    onDismiss = { clearAllConfirmOpen = false },
                )

                // 应用内一次性提示（清理分片结果等）：系统通知可被关掉、macOS 上还依赖
                // 托盘气泡是否可见，没有这条通道就等于「点了没反应」（P1-11）
                ToastBar(
                    message = viewModel.toast,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                )

                // 任务卡片「×」的确认框：只有「已完成且成品文件还在」时才会出现，
                // 问的是「只移除记录」还是「连文件一起删」。默认动作是保留文件（P0-1）。
                val pendingRemove = viewModel.pendingRemoveTask
                RemoveTaskDialog(
                    open = pendingRemove != null,
                    taskName = pendingRemove?.name.orEmpty(),
                    strings = removeTaskText,
                    onKeepFile = viewModel::removeTaskKeepingFile,
                    onDeleteFile = viewModel::removeTaskWithFile,
                    onDismiss = viewModel::cancelRemoveTask,
                )

                // 完成后关机的倒计时常驻 AppViewModel：放在最外层布局里，
                // 切到网盘/设置页或最小化到托盘时依然会显示并生效
                ShutdownCountdownDialog(
                    open = viewModel.shutdownAfterComplete.countdown != null,
                    seconds = viewModel.shutdownAfterComplete.countdown ?: 0,
                    strings = shutdownText,
                    onCancel = viewModel.shutdownAfterComplete::cancel,
                    onShutdownNow = viewModel.shutdownAfterComplete::shutdownNow,
                )

                // 发现新版本：展示版本号与 Release 说明，下载时显示进度
                UpdateDialog(
                    open = viewModel.updateInfo != null,
                    info = viewModel.updateInfo,
                    strings = updateText,
                    downloading = viewModel.updateDownloading,
                    progress = viewModel.updateProgress,
                    onUpdate = viewModel::downloadAndInstall,
                    onLater = viewModel::dismissUpdateDialog,
                    onCancel = viewModel::cancelUpdateDownload,
                    onOpenRelease = {
                        viewModel.updateInfo?.releaseUrl?.let { url ->
                            runCatching {
                                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                            }.onFailure { e ->
                                AppLogger.warn("Update", "打开 Release 页面失败: ${e.message}", e)
                            }
                        }
                    },
                )
            }
        }
    }
}

@Preview
@Composable
private fun PreviewFlowDownApp() {
    FlowDownTheme {
        Box(Modifier.size(1280.dp, 800.dp)) {
            AppNew()
        }
    }
}

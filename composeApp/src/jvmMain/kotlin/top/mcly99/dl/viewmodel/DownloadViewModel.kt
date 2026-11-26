package top.mcly99.dl.viewmodel

import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import top.mcly99.dl.data.SettingsRepository
import top.mcly99.dl.engine.DownloadEngine
import top.mcly99.dl.engine.DownloadOperations
import top.mcly99.dl.engine.resolveProxy
import top.mcly99.dl.model.AppSettings
import top.mcly99.dl.model.DownloadTask
import top.mcly99.dl.model.DownloadSource
import top.mcly99.dl.model.DownloadStatus
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import java.awt.Desktop
import java.net.Proxy
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 下载管理器 ViewModel
 * 
 * 该ViewModel是下载功能的业务逻辑层，负责协调UI层与下载引擎之间的数据流，
 * 提供状态管理、业务逻辑处理和用户交互响应。
 * 
 * 主要职责：
 * - 状态管理：维护下载任务列表、配置参数等可观察状态
 * - 业务逻辑：封装下载操作（添加、开始、暂停、取消等）
 * - 配置管理：持久化和加载用户设置
 * - 数据转换：将引擎层数据转换为UI友好的格式
 * - 生命周期管理：处理ViewModel生命周期事件
 * 
 * 架构模式：
 * - MVVM架构：遵循Model-View-ViewModel设计模式
 * - 响应式编程：使用Compose StateFlow和mutableStateOf实现响应式UI更新
 * - 协程集成：使用kotlinx.coroutines进行异步操作管理
 * 
 * @property tasks 所有下载任务列表（可观察状态）
 * @property downloadPath 下载目录路径（可观察状态）
 * @property maxConcurrentDownloads 最大并发下载数（可观察状态）
 * @property downloadThreadsPerFile 单文件下载线程数（可观察状态）
 * @property availableThreadOptions 可用的线程数选项列表
 * @property engine 下载引擎实例
 * 
 * @see DownloadEngine 下载引擎实现
 * @see DownloadTask 下载任务数据模型
 * @see AppNew 应用入口点
 */
class DownloadViewModel(
    /**
     * 下载引擎。生产用 [DownloadEngine]，测试注入纯内存假实现（P3-T2 窄接口 [DownloadOperations]）。
     *
     * 这里不直接依赖 1900 行的 [DownloadEngine]：VM 只用到状态流、增删任务、并发/限速配置
     * 这 20 个成员，收在接口里才能不起网络、不落磁盘地测「×」按钮、全部开始这些状态逻辑。
     */
    private val engine: DownloadOperations = DownloadEngine(),
    /**
     * ViewModel 自有作用域，关闭时统一取消，避免 MainScope 泄漏。
     * 测试注入 `StandardTestDispatcher` 的作用域，`advanceUntilIdle()` 才能推进状态收集。
     */
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    /**
     * 设置读取入口。默认读用户配置；测试注入固定值，避免单测读写 `~/.mclydownload/`
     * 并让「按配置注入引擎」这件事可断言。
     */
    private val settingsLoader: () -> AppSettings = { SettingsRepository.load() },
) {

    // 所有下载任务列表
    var tasks by mutableStateOf<List<DownloadTask>>(emptyList())
        private set
    
    // 设置项以「引擎 = 运行时真源、AppSettings = 持久化真源」为准，ViewModel 只做只读透出。
    // 这里原本各自持有一份 mutableStateOf 副本，同一配置在 AppSettings / ViewModel / Engine
    // 存了三份、靠双向同步维持一致（v4-P2-5）。UI 读的是 AppViewModel.settings（Compose 状态），
    // 所以去掉这些副本不会丢失重组。
    val downloadPath: Path get() = engine.defaultDownloadPath
    val maxConcurrentDownloads: Int get() = engine.maxConcurrentDownloads
    val downloadThreadsPerFile: Int get() = engine.downloadThreadsPerFile
    val speedLimit: Long get() = engine.speedLimit
    val proxyEnabled: Boolean get() = engine.proxyEnabled
    val keepHlsSegments: Boolean get() = engine.keepHlsSegments

    // 可用的线程数选项
    val availableThreadOptions = listOf(1, 2, 4, 8, 16, 32, AppConstants.MAX_THREADS)
    
    init {
        // 加载保存的设置
        loadSettings()

        // 恢复上次会话未完成的任务（下载中的会以暂停状态恢复）
        engine.restoreTasks()

        // P2-3：直接收集引擎的 StateFlow，不再每次推送后全量拉取
        engine.taskState
            .onEach { tasks = it }
            .launchIn(scope)
    }

    /**
     * 释放资源：取消收集协程并关闭下载引擎（断开连接、取消任务）
     */
    fun shutdown() {
        try {
            engine.shutdown()
        } catch (e: Exception) {
            AppLogger.warn("DownloadViewModel", "关闭下载引擎失败: ${e.message}", e)
        }
        scope.cancel()
    }
    
    /**
     * 添加下载任务
     *
     * @param referer 防盗链 `Referer`；抓网页抓到的地址要带页面地址，否则跨域 CDN 会 403
     * @param totalSize 调用方已知的文件大小（云盘分享列表里有）；0 表示交给引擎探测
     */
    fun addDownload(
        url: String,
        path: Path? = null,
        threads: Int = downloadThreadsPerFile,
        referer: String? = null,
        cookie: String? = null,
        totalSize: Long = 0L,
        userAgent: String? = null,
        origin: String? = null,
        secChUa: String? = null,
        /** C2 备选直链；由云盘页在取链时按账号生成，其它入口保持默认空列表 */
        altSources: List<DownloadSource> = emptyList(),
    ): String {
        return engine.addTask(
            url = url,
            savePath = path,
            threads = threads,
            referer = referer,
            cookie = cookie,
            totalSize = totalSize,
            userAgent = userAgent,
            origin = origin,
            secChUa = secChUa,
            altSources = altSources,
        )
    }
    
    /**
     * 开始下载
     */
    fun startDownload(taskId: String) {
        engine.startDownload(taskId)
    }
    
    /**
     * 暂停下载
     */
    fun pauseDownload(taskId: String) {
        engine.pauseDownload(taskId)
    }
    
    /**
     * 继续下载
     */
    fun resumeDownload(taskId: String) {
        engine.startDownload(taskId)
    }
    
    /**
     * 取消下载
     */
    fun cancelDownload(taskId: String) {
        engine.cancelDownload(taskId)
    }
    
    /**
     * 等待用户决定「是否连文件一起删」的任务；null 表示没有待确认的移除。
     *
     * 任务卡片上的「×」以前直接调 `removeTask(id)`，而引擎默认 `deleteFile = true`，
     * 一次误点就把成品删了，且失败只写日志（P0-1）。现在默认「只移除记录」，
     * 只有「已完成 + 文件还在磁盘上」这一种情况才需要问 —— 那时两种结果对用户都有意义。
     */
    var pendingRemove by mutableStateOf<DownloadTask?>(null)
        private set

    /**
     * 点「×」的入口。
     *
     * - 未完成（下载中 / 排队 / 暂停 / 失败 / 已取消）：直接移除记录，**连残片一起清掉**。
     *   半截文件与 `.partN` / `{name}.hls` 都不是用户要的成品；如果只删记录，
     *   列表里看不见了、磁盘上却留着几十上百 MB 的垃圾，用户再也找不到清理入口。
     * - 已完成且文件还在：记下待确认任务，由 UI 弹框让用户选「仅移除」还是「连文件一起删」。
     * - 已完成但文件已经被用户手动删了：没有可问的，直接移除记录。
     */
    fun requestRemoveTask(taskId: String) {
        val task = engine.getTask(taskId) ?: return
        // 只在「已完成」时才查磁盘：未完成的任务不管文件在不在都由 RemoveDecision 直接连残片清掉，
        // 省掉一次无意义的 stat（任务多时这里是 UI 线程上的串行调用）
        val fileExists = task.status == DownloadStatus.COMPLETED &&
            runCatching { task.savePath.toFile().exists() }.getOrDefault(false)
        when (val decision = RemoveDecision.of(task.status, fileExists)) {
            RemoveDecision.None -> Unit
            is RemoveDecision.RemoveNow -> engine.removeTask(taskId, deleteFile = decision.deleteFile)
            RemoveDecision.AskUser -> pendingRemove = task
        }
    }

    fun cancelPendingRemove() {
        pendingRemove = null
    }

    /** 「仅移除任务」：只删列表记录，磁盘上的成品保留。 */
    fun confirmRemoveKeepingFile() {
        val task = pendingRemove ?: return
        pendingRemove = null
        engine.removeTask(task.id, deleteFile = false)
    }

    /** 「移除并删除文件」：用户在这里明确选择了连成品一起删。 */
    fun confirmRemoveWithFile() {
        val task = pendingRemove ?: return
        pendingRemove = null
        engine.removeTask(task.id, deleteFile = true)
    }

    /**
     * 删除任务。
     *
     * `deleteFile` 默认 **false**：与「清理已完成」「全部删除」保持同一语义（都只删记录）。
     * 需要连文件一起删时走 [confirmRemoveWithFile]，不要在这里给默认值加 `true`。
     */
    fun removeTask(taskId: String, deleteFile: Boolean = false) {
        engine.removeTask(taskId, deleteFile = deleteFile)
    }
    
    /**
     * 更改下载目录
     */
    fun changeDownloadPath(newPath: Path) {
        engine.defaultDownloadPath = newPath
        runCatching { newPath.toFile().mkdirs() }
    }
    
    /**
     * 更改最大并发数
     */
    fun changeMaxConcurrent(max: Int) {
        engine.maxConcurrentDownloads = max
        // 调大之后必须立刻补位：调度器的触发点原本都在任务事件上，改设置不在其中，
        // 用户把 1 改成 8 只会看到排队任务一动不动（P1-6）。
        engine.requestDispatch()
    }
    
    /**
     * 更改下载线程数
     */
    fun changeDownloadThreads(threads: Int) {
        if (threads in availableThreadOptions) {
            engine.downloadThreadsPerFile = threads
            // 持久化统一由 AppNew 的设置快照（SettingsRepository）负责，
            // 这里不再单独写盘，避免两处写入互相覆盖（P1-10）。
        }
    }
    
    /**
     * 从本地文件加载设置
     *
     * 这是「配置文件 → 引擎」的唯一入口：`AppViewModel.settings` 随后直接读同一个文件，
     * 不再用引擎值反写设置（v4-P2-5）。
     */
    private fun loadSettings() {
        try {
            val settings = settingsLoader()
            if (settings.defaultThreads in availableThreadOptions) {
                engine.downloadThreadsPerFile = settings.defaultThreads
            } else {
                AppLogger.warn(
                    "DownloadViewModel",
                    "配置里的 defaultThreads=${settings.defaultThreads} 不在可选项 $availableThreadOptions 中，沿用默认值 ${engine.downloadThreadsPerFile}",
                )
            }
            if (settings.maxConcurrent > 0) {
                engine.maxConcurrentDownloads = settings.maxConcurrent
            }
            // 恢复用户自定义的下载目录（否则启动时会被引擎默认目录覆盖，看起来像"设置丢失"）
            settings.downloadPath.takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { Paths.get(raw) }.getOrNull()?.let { path ->
                    engine.defaultDownloadPath = path
                    runCatching { path.toFile().mkdirs() }
                }
            }
            // 限速与代理也在这里注入引擎
            applySpeedLimit(settings.speedLimit)
            // 单独的 ViewModel 也要能按三态配置工作；AppViewModel 稍后会换成现取 lambda
            applyProxy(resolveProxy(settings.proxyMode, settings.proxyHost, settings.proxyPort))
            applyKeepHlsSegments(settings.keepHlsSegments)
        } catch (e: Exception) {
            AppLogger.warn("DownloadViewModel", "读取设置失败: ${e.message}", e)
        }
    }

    /** 应用全局下载限速（0 = 不限速） */
    fun applySpeedLimit(bytesPerSecond: Long) {
        engine.speedLimit = bytesPerSecond.coerceAtLeast(0L)
    }

    /** 注入固定代理（一次性快照） */
    fun applyProxy(proxy: Proxy?) {
        engine.proxyProvider = { proxy }
    }

    /**
     * 注入「现取」的代理来源。系统代理模式下，每个请求都会经这里重新解析，
     * 运行中开关系统 / VPN 代理也能跟随。
     */
    fun applyProxyProvider(provider: () -> Proxy?) {
        engine.proxyProvider = provider
    }

    /** HLS 合成成功后是否保留分片 */
    fun applyKeepHlsSegments(enabled: Boolean) {
        engine.keepHlsSegments = enabled
    }
    
    /**
     * 清理已完成的任务（只移除列表项，保留已下载文件）
     */
    fun clearCompleted() {
        tasks.filter { it.status == DownloadStatus.COMPLETED }
            .forEach { engine.removeTask(it.id, deleteFile = false) }
    }

    /**
     * 删除全部任务（已完成 + 未完成），供顶栏「全部删除」调用。
     *
     * 与 [clearCompleted] 同一套语义：只移除任务记录，已完成的成品文件保留在磁盘上。
     * 实现放在引擎侧（`removeAllTasks`）而不是这里遍历 `tasks`：`tasks` 是 UI 侧过滤后的
     * 结果（受搜索/视图筛选影响），拿它删会漏掉被隐藏的任务，与「全部」这个承诺不符。
     */
    fun removeAllTasks() {
        engine.removeAllTasks(deleteFile = false)
    }
    
    /**
     * 全部暂停
     */
    fun pauseAll() {
        // 交给引擎一次性处理，而不是 here 过滤后逐个 pause：
        // ① 用 UI 的 tasks 会漏掉被搜索/筛选隐藏的任务（与「全部」不符）；
        // ② 必须覆盖 QUEUED，否则刚空出的名额立刻被排队任务占走，等于没停（P1-1）。
        engine.pauseAllDownloads()
    }
    
    /**
     * 全部开始（排队中 + 已暂停；失败任务需手动重试）
     */
    fun startAll() {
        // 用引擎快照而不是 UI 状态：AppViewModel 初始化时 StateFlow 可能还没完成第一次收集，
        // 此时 tasks 为空会导致恢复的任务没有按 autoStart 设置启动。
        engine.taskState.value.filter {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.PAUSED
        }.forEach { engine.startDownload(it.id) }
    }
    
    /**
     * 打开文件所在位置
     */
    fun openFileLocation(taskId: String) {
        val task = tasks.find { it.id == taskId } ?: return
        val file = task.savePath.toFile()
        
        if (Desktop.isDesktopSupported()) {
            try {
                if (file.exists()) {
                    // 打开文件所在文件夹并选中文件
                    Desktop.getDesktop().browseFileDirectory(file)
                } else {
                    // 如果文件不存在，打开下载目录
                    val downloadDir = task.savePath.parent
                    if (downloadDir != null && downloadDir.toFile().exists()) {
                        Desktop.getDesktop().open(downloadDir.toFile())
                    }
                }
            } catch (e: Exception) {
                // 如果 browseFileDirectory 不支持，尝试打开父目录
                try {
                    val downloadDir = task.savePath.parent
                    if (downloadDir != null && downloadDir.toFile().exists()) {
                        Desktop.getDesktop().open(downloadDir.toFile())
                    }
                } catch (e2: Exception) {
                    AppLogger.warn("DownloadViewModel", "打开下载目录失败: ${e2.message}", e2)
                }
            }
        }
    }

    /**
     * 清理某个 HLS 任务残留的分片目录，返回释放的字节数（0 表示没有可清理的）。
     *
     * 为什么由 VM 转发而不是让 UI 直接删目录：分片目录名（`{name}.hls`）的规则在引擎侧，
     * UI 不该知道这个约定（v7-P2-3）。
     */
    fun cleanupHlsSegments(taskId: String): Long = engine.cleanupHlsSegments(taskId)
}

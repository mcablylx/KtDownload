package top.mcly99.dl.viewmodel

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import top.mcly99.dl.engine.TransferEngine
import top.mcly99.dl.engine.WebUploadServer
import top.mcly99.dl.model.NetworkDevice
import top.mcly99.dl.model.TransferDirection
import top.mcly99.dl.model.TransferStatus
import top.mcly99.dl.model.TransferTask
import top.mcly99.dl.model.keepingUnfinished
import top.mcly99.dl.theme.UiStrings
import top.mcly99.dl.theme.zhCN
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import top.mcly99.dl.utils.NativeFileDialog
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


/**
 * 文件传输 ViewModel
 * 
 * 该ViewModel是局域网文件传输功能的业务逻辑层，负责协调UI层与传输引擎之间的数据流，
 * 提供设备管理、文件传输控制、Web上传服务管理和状态监控。
 * 
 * 主要职责：
 * - 设备管理：发现、监控和管理局域网内可用设备
 * - 文件传输：封装发送文件到目标设备的业务逻辑
 * - Web服务管理：启动/停止嵌入式Web上传服务器
 * - 状态同步：实时更新传输任务进度和设备在线状态
 * - UI适配：将引擎层数据转换为Compose友好的可观察状态
 * 
 * 架构特点：
 * - 双协程作用域：Main作用域处理UI更新，IO作用域处理后台任务
 * - 响应式设计：使用mutableStateOf实现Compose状态驱动
 * - 资源管理：智能任务生命周期管理和连接池管理
 * - 错误处理：全面的异常捕获和用户友好的错误提示
 * 
 * @property engine 局域网传输引擎实例
 * @property webServer Web上传服务器实例
 * @property devices 已发现设备列表（可观察状态）
 * @property transferTasks 传输任务列表（可观察状态）
 * @property localDeviceName 本机设备名称（可观察状态）
 * @property webServerRunning Web服务器运行状态（可观察状态）
 * @property webServerIp Web服务器IP地址（可观察状态）
 * @property webServerPort Web服务器端口（可观察状态）
 * @property languageStrings UI语言字符串资源
 * 
 * @see TransferEngine 局域网传输引擎
 * @see WebUploadServer Web上传服务器
 * @see NetworkDevice 设备信息模型
 * @see TransferTask 传输任务模型
 */
class TransferViewModel {

    companion object {
        /** 传输历史保留上限；超出后优先裁剪已结束的记录 */
        private const val MAX_TRANSFER_HISTORY = 200
    }
    private val engine = TransferEngine()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeTransfers = ConcurrentHashMap<String, Job>()
    private var pendingRestart: Job? = null

    /** 设备在线状态（由最后一次广播时间推导），供 UI 映射时使用 */
    var deviceOnline by mutableStateOf<Map<String, Boolean>>(emptyMap())
        private set

    // 网页上传服务器
    private val webServer = WebUploadServer()

    var saveDir: String
        get() = webServer.saveDir
        set(value) {
            webServer.saveDir = value
            engine.receiveDir = File(value)
        }

    /** 网页上传的完整访问地址（带访问令牌）；服务未运行时为空串 */
    var webServerUrl by mutableStateOf("")
        private set

    /** 局域网设备发现 / 文件接收是否可用（端口被占用时会降级为 false） */
    var discoveryRunning by mutableStateOf(false)
        private set

    var fileServerRunning by mutableStateOf(false)
        private set

    var languageStrings: UiStrings = zhCN()
        set(value) {
            field = value
            webServer.strings = value
        }

    init {
        webServer.onFileReceived = { file ->
            recordReceivedFile(
                file = file,
                deviceId = "web",
                deviceName = languageStrings.transferApp,
                ipAddress = webServer.getLocalIp(),
                port = webServerPort,
            )
        }
        // 局域网直接发送的文件同样进入传输列表（此前只创建 Web 上传任务）
        engine.onFileReceived = { file, remoteIp ->
            val ip = remoteIp.ifBlank { webServer.getLocalIp() }
            recordReceivedFile(
                file = file,
                deviceId = "lan_$ip",
                deviceName = ip,
                ipAddress = ip,
                port = AppConstants.TRANSFER_PORT,
            )
        }
    }

    /** 接收完成的任务只记录到列表；回调可能来自 IO 线程，统一切回 Main 更新状态 */
    private fun recordReceivedFile(
        file: File,
        deviceId: String,
        deviceName: String,
        ipAddress: String,
        port: Int,
    ) {
        scope.launch {
            val task = TransferTask(
                id = "${deviceId}_${UUID.randomUUID().toString().take(8)}",
                fileName = file.name,
                fileSize = file.length(),
                targetDevice = NetworkDevice(
                    id = deviceId,
                    name = deviceName,
                    ipAddress = ipAddress,
                    port = port,
                ),
                status = TransferStatus.COMPLETED,
                progress = 1f,
                transferredSize = file.length(),
                direction = TransferDirection.Receive,
            )
            appendTask(task)
        }
    }

    var devices by mutableStateOf<List<NetworkDevice>>(emptyList())
        private set

    var transferTasks by mutableStateOf<List<TransferTask>>(emptyList())
        private set

    var localDeviceName by mutableStateOf("")
        private set

    var webServerRunning by mutableStateOf(false)
        private set

    /**
     * 当前所有发送任务的速度总和（字节/秒），供顶栏展示。
     * 由 [sendFileToDevice] 的进度回调实时更新。
     */
    var totalUploadSpeed by mutableStateOf(0L)
        private set

    private val uploadSpeeds = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun refreshUploadSpeed() {
        totalUploadSpeed = uploadSpeeds.values.sum()
    }

    var webServerError by mutableStateOf(false)
        private set

    var webServerIp by mutableStateOf("")
        private set

    var webServerPort by mutableStateOf(AppConstants.DEFAULT_WEB_PORT)
        private set

    init {
        val (_, name) = engine.getLocalDeviceInfo()
        localDeviceName = name

        engine.receiveDir = File(webServer.saveDir)
        engine.start()
        refreshDevices()
    }

    /** 启停设备发现（对应设置中的「设备发现」开关） */
    fun setDiscoveryEnabled(enabled: Boolean) {
        engine.setDiscoveryEnabled(enabled)
        discoveryRunning = engine.discoveryRunning
        if (!enabled) devices = emptyList()
    }

    fun setWebServerEnabled(enabled: Boolean, port: Int) {
        // 取消之前的延迟重启
        pendingRestart?.cancel()
        pendingRestart = null

        webServerPort = port
        engine.advertisedWebPort = port
        if (!enabled) {
            webServer.stop()
            webServerRunning = false
            webServerUrl = ""
            return
        }

        // 立即更新 IP 显示
        webServerIp = webServer.getLocalIp()
        webServerError = false

        // 端口校验：无效端口不启动
        if (port < 1 || port > 65535) return

        if (!webServerRunning) {
            // 首次启动：立即执行
            startServer(port)
        } else {
            // 端口变更：3 秒防抖后重启
            pendingRestart = scope.launch {
                delay(AppConstants.WEB_PORT_RESTART_DEBOUNCE_MS)
                startServer(port)
                pendingRestart = null
            }
        }
    }

    /**
     * 启动 / 换端口重启上传服务。
     *
     * 失败时**不能一律当作「服务已停」**：`WebUploadServer.start` 会先在新端口把服务起起来，
     * 成功了才关旧的，所以换端口失败时旧服务仍在原端口正常工作（P1-20）。
     * 这时界面上要如实显示「还在旧端口跑」并给出错误提示，而不是把地址和运行状态一起清空。
     */
    private fun startServer(port: Int) {
        webServerError = false
        engine.advertisedWebPort = port
        ioScope.launch {
            val ok = webServer.start(port)
            // 失败时回滚到「实际在监听的端口」，别让「设备发现」继续广播一个没人监听的端口
            val activePort = webServer.port
            engine.advertisedWebPort = activePort
            scope.launch {
                webServerRunning = webServer.isRunning
                webServerError = !ok
                webServerPort = activePort
                webServerUrl = if (webServer.isRunning) webServer.accessUrl() else ""
                if (!ok) {
                    if (webServer.isRunning) {
                        AppLogger.warn(
                            "TransferViewModel",
                            "端口 $port 启动失败，服务继续在旧端口 $activePort 运行",
                        )
                    } else {
                        AppLogger.warn("TransferViewModel", "Web 服务启动失败，端口 $port")
                    }
                } else {
                    AppLogger.info("TransferViewModel", "Web 服务已启动，端口 $activePort")
                }
            }
        }
    }

    private fun refreshDevices() {
        scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val latest = engine.discoveredDevices.values
                    .sortedByDescending { it.lastSeen }
                // 在线状态由“最后广播时间”推导：超过软超时先标灰，超过硬超时由引擎移除。
                // 设备停止广播后 lastSeen 不再变化，需在这里主动发布一次，避免 UI 一直显示在线。
                val online = latest.associate { it.id to (now - it.lastSeen < AppConstants.DEVICE_OFFLINE_TIMEOUT_MS) }
                if (latest != devices) devices = latest
                if (online != deviceOnline) deviceOnline = online
                // 端口占用导致降级时，把状态同步到界面
                if (discoveryRunning != engine.discoveryRunning) discoveryRunning = engine.discoveryRunning
                if (fileServerRunning != engine.fileServerRunning) fileServerRunning = engine.fileServerRunning
                delay(AppConstants.TRANSFER_REFRESH_INTERVAL_MS)
            }
        }
    }

    fun sendFileToDevice(targetDevice: NetworkDevice) {
        val file = NativeFileDialog.chooseFile(languageStrings.chooseFileToSend) ?: return
        startTransfer(targetDevice, file.name, file.length()) { taskId, onProgress ->
            engine.sendFile(file, targetDevice, taskId, onProgress)
        }
    }

    /** 发送整个文件夹（含所有子文件夹与文件），接收端还原目录结构 */
    fun sendFolderToDevice(targetDevice: NetworkDevice) {
        val path = NativeFileDialog.chooseDirectory(languageStrings.chooseFolder) ?: return
        val folder = File(path)
        // 枚举目录必须在 IO 线程完成（大目录要数秒），所以任务先以「统计中」入列表；
        // sizePreparing 让 UI 显示「正在统计文件…」而不是误报「0 B / 0 B」（v4-P3-3）。
        startTransfer(targetDevice, folder.name, 0L, sizePreparing = true) { taskId, onProgress ->
            val files = engine.collectFolderFiles(folder)
            val totalSize = files.sumOf { f -> f.file.length() }
            // 回填大小并同时清掉统计标记：这两个字段必须在同一次 updateTask 里改，
            // 否则会出现「已知大小但仍显示统计中」的中间帧。
            withContext(Dispatchers.Main) {
                updateTask(taskId) { it.copy(fileSize = totalSize, sizePreparing = false) }
            }
            engine.sendFiles(files, targetDevice, taskId, onProgress)
        }
    }

    private fun startTransfer(
        targetDevice: NetworkDevice,
        fileName: String,
        fileSize: Long,
        sizePreparing: Boolean = false,
        sender: suspend (taskId: String, onProgress: (Float, Long, Long) -> Unit) -> Boolean,
    ) {
        val task = TransferTask(
            fileName = fileName,
            fileSize = fileSize,
            targetDevice = targetDevice,
            sizePreparing = sizePreparing,
        )

        appendTask(task)

        // 先登记再启动，避免极短任务在 finally 移除之后才执行到 activeTransfers[id] = job
        val transferJob = ioScope.launch(start = CoroutineStart.LAZY) {
            try {
                val success = sender(task.id) { progress, speed, sent ->
                    uploadSpeeds[task.id] = speed
                    scope.launch {
                        // 使用引擎给出的真实字节数，而不是按比例换算。
                        // 取消/完成后可能还有已排队的进度 launch 在执行，绝不能把
                        // CANCELLED/FAILED/COMPLETED 终态改回 TRANSFERRING（P1-E2）。
                        updateTask(task.id) { cur ->
                            if (cur.status == TransferStatus.CANCELLED ||
                                cur.status == TransferStatus.FAILED ||
                                cur.status == TransferStatus.COMPLETED
                            ) cur else cur.copy(
                                progress = progress,
                                speed = speed,
                                transferredSize = sent,
                                status = if (progress >= 1f) TransferStatus.COMPLETED
                                        else TransferStatus.TRANSFERRING
                            )
                        }
                        refreshUploadSpeed()
                    }
                }

                scope.launch {
                    updateTask(task.id) {
                        it.copy(
                            status = if (success) TransferStatus.COMPLETED
                                    else TransferStatus.FAILED,
                            progress = if (success) 1.0f else it.progress,
                            speed = 0L,
                            errorMessage = if (!success) languageStrings.transferFailedOrCancelled else null
                        )
                    }
                }
            } catch (e: CancellationException) {
                // 用户主动取消：状态已由 cancelTask() 设置，这里只做清理
            } catch (e: Exception) {
                AppLogger.warn("TransferViewModel", "传输失败: ${e.message}", e)
                scope.launch {
                    updateTask(task.id) {
                        it.copy(
                            status = TransferStatus.FAILED,
                            speed = 0L,
                            errorMessage = languageStrings.transferError(e.message ?: "")
                        )
                    }
                }
            } finally {
                activeTransfers.remove(task.id)
                uploadSpeeds.remove(task.id)
                scope.launch {
                    refreshUploadSpeed()
                    // 枚举中途失败 / 取消时 sizePreparing 会残留为 true，
                    // 导致失败态仍显示「正在统计文件…」，这里兜底清掉（v4-P3-3）。
                    updateTask(task.id) { if (it.sizePreparing) it.copy(sizePreparing = false) else it }
                }
            }
        }

        activeTransfers[task.id] = transferJob
        transferJob.start()
    }

    /**
     * 追加一条传输记录，并把历史裁剪到上限（v4-P2-7）。
     *
     * 传输历史只在用户手动删除时才缩减，长会话会累积到几百条：既拖慢渲染，也让每次进度回调的
     * 列表复制变贵。这里只裁剪**已结束**的记录，进行中的任务永远保留。
     */
    private fun appendTask(task: TransferTask) {
        val next = transferTasks + task
        if (next.size <= MAX_TRANSFER_HISTORY) {
            transferTasks = next
            return
        }
        val overflow = next.size - MAX_TRANSFER_HISTORY
        val dropped = HashSet<String>(overflow * 2)
        for (candidate in next) {
            if (dropped.size >= overflow) break
            if (candidate.status != TransferStatus.WAITING && candidate.status != TransferStatus.TRANSFERRING) {
                dropped.add(candidate.id)
            }
        }
        transferTasks = if (dropped.isEmpty()) next else next.filterNot { it.id in dropped }
    }

    /**
     * 就地更新单个任务。
     *
     * v4-P2-7：原先每次进度回调（200ms）都 `map` 出一份新列表，N 个并发传输就是每 200ms
     * O(N²) 的元素复制 + N 次整列重组。这里改成「定位下标 → 只替换一个元素」。
     * 注意必须生成新 List 实例，否则 Compose 观察不到变化。
     */
    private fun updateTask(taskId: String, update: (TransferTask) -> TransferTask) {
        val index = transferTasks.indexOfFirst { it.id == taskId }
        if (index < 0) return
        val current = transferTasks[index]
        val next = update(current)
        if (next === current) return
        val copy = transferTasks.toMutableList()
        copy[index] = next
        transferTasks = copy
    }

    fun cancelTask(taskId: String) {
        AppLogger.debug("TransferViewModel", "取消传输任务: $taskId")
        // 先关闭 Socket，让阻塞中的 write 立即返回，再取消协程
        engine.cancelSend(taskId)
        activeTransfers.remove(taskId)?.cancel()
        uploadSpeeds.remove(taskId)
        transferTasks = transferTasks.map {
            if (it.id == taskId && it.status == TransferStatus.TRANSFERRING) {
                it.copy(status = TransferStatus.CANCELLED, speed = 0L)
            } else it
        }
        refreshUploadSpeed()
    }

    fun removeTask(taskId: String) {
        transferTasks = transferTasks.filter { it.id != taskId }
    }

    /**
     * 清空已结束的记录（完成 / 失败 / 已取消）。
     *
     * 只清 `COMPLETED` 是不够的：传输失败和取消的记录同样不会自己消失，
     * 攒多了整列都是「已取消」，而用户找不到任何清掉它们的手段。
     * 进行中的任务一律保留 —— 它们还在动，不该被顺手抹掉。
     */
    fun clearFinished() {
        transferTasks = transferTasks.keepingUnfinished()
    }

    /**
     * 在系统文件管理器里打开接收目录。
     *
     * @return 是否成功打开。调用方据此提示，而不是点了没反应（原先失败时静默返回）。
     */
    fun openReceivedFilesLocation(): Boolean {
        val file = File(engine.receiveDir.path)
        if (!file.isDirectory) return false
        return runCatching { java.awt.Desktop.getDesktop().open(file) }
            .onFailure { AppLogger.warn("TransferViewModel", "打开接收目录失败: ${it.message}") }
            .isSuccess
    }

    fun cleanup() {
        pendingRestart?.cancel()
        webServer.stop()
        engine.stop()
        scope.cancel()
        ioScope.cancel()
    }
}

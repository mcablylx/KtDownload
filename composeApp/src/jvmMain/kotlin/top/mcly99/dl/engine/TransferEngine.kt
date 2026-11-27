package top.mcly99.dl.engine

import top.mcly99.dl.utils.closeQuietly
import kotlinx.coroutines.*
import top.mcly99.dl.model.NetworkDevice
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.security.MessageDigest

/** 待发送文件；[remotePath] 是相对发送根的路径（`/` 分隔），接收端据此还原目录结构 */
data class OutgoingFile(val file: File, val remotePath: String)

/**
 * 局域网文件传输引擎
 * 
 * 该引擎实现了局域网内的点对点文件传输功能，采用UDP广播发现+TCP文件传输的混合架构，
 * 提供快速、可靠的局域网文件共享能力。
 * 
 * 核心特性：
 * - 设备自动发现：基于UDP广播协议，支持跨子网设备发现
 * - 文件传输：基于TCP连接，支持大文件分块传输
 * - 实时状态监控：提供设备在线状态、传输进度、速度等实时指标
 * - 资源管理：智能连接池和缓冲区管理，优化内存使用
 * - 错误恢复：网络中断自动重连和断点续传支持
 * 
 * 架构设计：
 * - 发现层：UDP广播（端口9876）用于设备发现
 * - 传输层：TCP连接（端口9877）用于文件数据传输
 * - 状态层：内存映射设备列表和超时管理
 * 
 * @property discoveredDevices 已发现的设备列表（线程安全）
 * @property onDeviceDiscovered 设备发现回调
 * @property onDeviceLost 设备离线回调
 * @property onFileReceived 文件接收完成回调
 * @property localDeviceId 本机设备唯一标识
 * @property localDeviceName 本机设备友好名称
 * @property advertisedWebPort 广告的Web服务端口
 * 
 * @see NetworkDevice 设备信息模型
 * @see TransferTask 传输任务模型
 * @see TransferViewModel 传输功能ViewModel
 */
class TransferEngine(
    /** 文件传输端口（协议参数，双方一致）；测试可传入临时端口 */
    private val transferPort: Int = AppConstants.TRANSFER_PORT,
) {
    /**
     * P2-03：启停串行化 + 可重建的生命周期作用域。
     *
     * 原来 `if (job?.isActive != true) startXxx()` 是 check-then-act：两个线程同时看到
     * 「没在跑」会各自 launch 一个 Job，后者覆盖字段，前者留下的监听 socket 再也关不掉。
     * 所有读写都收进 [lifecycleLock]；[stop] 则整批 cancel [lifecycleJob] 并重建作用域，
     * 这样 accept 子协程、广播/监听子协程、以及旧 Job 的 finally 都不会把新 start 的状态
     * 覆盖回去（用 `scope === myScope` 做发布守卫）。
     */
    private val lifecycleLock = Any()
    private var lifecycleJob = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.IO + lifecycleJob)
    private var discoveryJob: Job? = null
    private var serverJob: Job? = null
    
    /** 线程安全：广播监听协程写入、UI 线程读取 */
    val discoveredDevices: MutableMap<String, NetworkDevice> = ConcurrentHashMap()
    val onDeviceDiscovered: (NetworkDevice) -> Unit = {}
    val onDeviceLost: (String) -> Unit = {}
    /** 文件接收完成回调：(文件, 发送方 IP)；供 ViewModel 在构造后注入 */
    var onFileReceived: (File, String) -> Unit = { _, _ -> }

    /** 正在进行的发送任务：taskId -> Socket，用于取消时真正断开阻塞中的 IO */
    private val activeSockets = ConcurrentHashMap<String, Socket>()

    /**
     * 局域网接收的配对 token（P1-09）。
     *
     * 空串 = 不启用认证（保持旧协议，供未接线的场景与旧版本互操作）；
     * 生产由 [top.mcly99.dl.viewmodel.TransferViewModel] 注入 WebUploadServer 的同源 token，
     * 于是 9877 接收与 8080 网页上传由同一枚凭据保护，发送端 sendFiles 自动带 AUTH| 首行，
     * 用户无需手输配对码。注入后旧版发送端（无 AUTH）会被拒绝 —— 这是默认严格的安全取舍。
     * P2-18：网页上传服务启动/换端口时会轮转令牌，ViewModel 通过
     * `WebUploadServer.onTokenRotated` 回调把新值同步到这里，两端始终一致。
     */
    @Volatile
    var accessToken: String = ""

    /** 接收并发上限，避免任意主机用大量连接打满线程/磁盘 */
    private val transferSemaphore = Semaphore(AppConstants.MAX_CONCURRENT_TRANSFERS)

    /** 已 accept 的连接：stop() 时显式关闭，不再只靠 soTimeout 兜底 */
    private val acceptedSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    private var localDeviceId: String = java.util.UUID.randomUUID().toString()
    private var localDeviceName: String = "Device-${localDeviceId.take(6)}"

    /** 当前占用的端口/套接字，stop() 时用于立即打断阻塞调用 */
    @Volatile
    private var discoverySocket: DatagramSocket? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    /** 设备发现是否成功启动（端口被占用时为 false，不影响本机其它功能） */
    @Volatile
    var discoveryRunning: Boolean = false
        private set

    /** 文件接收服务是否成功启动（端口被占用时为 false） */
    @Volatile
    var fileServerRunning: Boolean = false
        private set

    var advertisedWebPort: Int = AppConstants.DEFAULT_WEB_PORT

    /** 接收目录（由 ViewModel 注入，与设置中的下载路径保持一致） */
    @Volatile
    var receiveDir: File = File(System.getProperty("user.home"), "Downloads")
        set(value) {
            field = value
            value.mkdirs()
        }
    
    companion object {
        private val ILLEGAL_NAME_CHARS = charArrayOf('<', '>', ':', '"', '/', '\\', '|', '?', '*', '\u0000')
        private val WINDOWS_RESERVED_NAMES = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        )
    }
    
    /**
     * 启动引擎（开始发现设备和接收文件）
     */
    fun start() {
        // P2-03：check-then-act 必须在同一把锁里完成，否则并发 start 会互相覆盖 Job 引用
        synchronized(lifecycleLock) {
            if (discoveryJob?.isActive != true) startDiscovery()
            if (serverJob?.isActive != true) startFileServer()
        }
    }

    /** 设备发现开关（对应设置中的「设备发现」），变化时即时启停 UDP 发现 */
    @Volatile
    var discoveryEnabled: Boolean = true
        private set

    /**
     * 启停设备发现。关闭时停止 UDP 广播与监听并清空设备列表；
     * 开启时重新绑定端口。（文件接收服务不受此开关影响）
     */
    fun setDiscoveryEnabled(enabled: Boolean) {
        synchronized(lifecycleLock) {
            if (discoveryEnabled == enabled && (enabled == (discoveryJob?.isActive == true))) {
                return@synchronized
            }
            discoveryEnabled = enabled
            if (enabled) {
                start()
            } else {
                discoveryJob?.cancel()
                discoveryJob = null
                runCatching { discoverySocket?.close() }
                discoverySocket = null
                discoveryRunning = false
                discoveredDevices.keys.toList().forEach { id ->
                    discoveredDevices.remove(id)
                    onDeviceLost(id)
                }
            }
        }
    }

    /**
     * 停止引擎：取消监听的同时关闭 socket，确保阻塞中的 accept()/receive() 立即返回。
     *
     * P2-03：不再只 cancel 两个根 Job —— 它们派生出的 accept 子协程、广播/监听子协程
     * 与旧 Job 的 finally 都可能在 stop 之后才写状态。这里整批 cancel [lifecycleJob]
     * 并重建作用域；`startDiscovery` / `startFileServer` 用 scope 快照做发布守卫。
     */
    fun stop() {
        synchronized(lifecycleLock) {
            discoveryJob?.cancel()
            serverJob?.cancel()
            discoveryJob = null
            serverJob = null
            lifecycleJob.cancel()
            lifecycleJob = SupervisorJob()
            scope = CoroutineScope(Dispatchers.IO + lifecycleJob)
            closeQuietly(discoverySocket, "TransferEngine")
            discoverySocket = null
            closeQuietly(serverSocket, "TransferEngine")
            serverSocket = null
            discoveryRunning = false
            fileServerRunning = false
            // P1-09 / P2-03：已 accept 的连接此前一律不关，只靠 soTimeout 兜底；现在显式关闭
            acceptedSockets.forEach { closeQuietly(it, "TransferEngine") }
            acceptedSockets.clear()
        }
    }
    
    /**
     * 获取本机信息
     */
    fun getLocalDeviceInfo(): Pair<String, String> {
        return Pair(localDeviceId, localDeviceName)
    }
    
    /**
     * 启动UDP广播发现设备
     */
    private fun startDiscovery() {
        if (!discoveryEnabled) {
            discoveryRunning = false
            return
        }
        // P2-03：捕获本次启动的作用域快照，stop() 会重建 scope；旧 Job 结束后不得把
        // 新作用域的 discoverySocket / discoveryRunning 覆盖掉。
        val myScope = scope
        discoveryJob = myScope.launch {
            // 先绑定端口：失败时只停用设备发现，不能让整个应用崩溃
            val socket = try {
                DatagramSocket(AppConstants.DISCOVERY_PORT).apply { reuseAddress = true }
            } catch (e: Exception) {
                synchronized(lifecycleLock) { if (scope === myScope) discoveryRunning = false }
                AppLogger.warn("TransferEngine", "设备发现不可用：UDP ${AppConstants.DISCOVERY_PORT} 绑定失败(${e.message})，功能降级运行", e)
                return@launch
            }
            val published = synchronized(lifecycleLock) {
                if (scope !== myScope) false
                else {
                    discoverySocket = socket
                    discoveryRunning = true
                    true
                }
            }
            if (!published) {
                // stop 已经发生：这个 socket 不属于新作用域，直接回收
                closeQuietly(socket, "TransferEngine")
                return@launch
            }
            try {
                // 启动广播线程
                launch { broadcastPresence() }
                // 启动监听线程（复用已绑定的 socket，避免重复端口占用失败）
                launch { listenForDevices(socket) }
                // 定期检查设备超时
                while (isActive) {
                    checkDeviceTimeout()
                    delay(AppConstants.BROADCAST_INTERVAL_MS)
                }
            } finally {
                synchronized(lifecycleLock) {
                    if (scope === myScope) {
                        discoverySocket = null
                        discoveryRunning = false
                    }
                }
                closeQuietly(socket, "TransferEngine")
            }
        }
    }
    
    /**
     * 广播本机存在。
     *
     * 不再只发往 `255.255.255.255`：那样在有 VPN / Hyper-V / WSL 虚拟网卡的机器上
     * 只会走默认路由，常常"看不到对方"。这里遍历所有活跃的多播网卡，
     * 对每块网卡使用其**定向广播地址**(如 192.168.1.255)分别发送，
     * 再补发一次全局广播兼容特殊网络。
     */
    private suspend fun broadcastPresence() {
        val socket = DatagramSocket()
        socket.broadcast = true

        try {
            while (currentCoroutineContext().isActive) {
                val message = "DISCOVERY|${AppConstants.DISCOVERY_PROTOCOL_VERSION}|$localDeviceId|$localDeviceName|$advertisedWebPort"
                val data = message.toByteArray(Charsets.UTF_8)

                val interfaces = runCatching {
                    java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                }.getOrDefault(emptyList())

                var sent = 0
                for (nif in interfaces) {
                    val usable = runCatching {
                        nif.isUp && !nif.isLoopback && nif.supportsMulticast()
                    }.getOrDefault(false)
                    if (!usable) continue
                    for (addr in nif.interfaceAddresses) {
                        // 只处理 IPv4 且有广播地址的网卡
                        val broadcast = addr.broadcast ?: continue
                        if (addr.address !is java.net.Inet4Address) continue
                        runCatching {
                            socket.send(DatagramPacket(data, data.size, broadcast, AppConstants.DISCOVERY_PORT))
                            sent++
                        }
                    }
                }

                // 兜底：没有任何定向广播地址时发全局广播
                if (sent == 0) {
                    runCatching {
                        val address = InetAddress.getByName("255.255.255.255")
                        socket.send(DatagramPacket(data, data.size, address, AppConstants.DISCOVERY_PORT))
                    }
                }

                delay(AppConstants.BROADCAST_INTERVAL_MS)
            }
        } finally {
            socket.close()
        }
    }
    
    /**
     * 监听其他设备广播
     */
    private suspend fun listenForDevices(socket: DatagramSocket) {
        try {
            while (currentCoroutineContext().isActive) {
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: java.net.SocketException) {
                    if (!currentCoroutineContext().isActive || socket.isClosed) return else throw e
                }
                
                val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (message.startsWith("DISCOVERY|")) {
                    val parts = message.split("|")
                    // 兼容旧格式 DISCOVERY|id|name|port 与新格式 DISCOVERY|v1|id|name|port
                    val base = if (parts.getOrNull(1) == AppConstants.DISCOVERY_PROTOCOL_VERSION) 2 else 1
                    if (parts.size > base + 1) {
                        val deviceId = parts[base]
                        val deviceName = parts[base + 1]
                        val devicePort = parts.getOrNull(base + 2)?.toIntOrNull()
                            ?: AppConstants.DEFAULT_WEB_PORT
                        val ipAddress = packet.address.hostAddress ?: ""

                        // 忽略自己
                        if (deviceId != localDeviceId) {
                            val device = NetworkDevice(
                                id = deviceId,
                                name = deviceName,
                                ipAddress = ipAddress,
                                port = devicePort,
                                lastSeen = System.currentTimeMillis()
                            )
                            
                            val isNew = !discoveredDevices.containsKey(deviceId)
                            discoveredDevices[deviceId] = device
                            
                            if (isNew) {
                                onDeviceDiscovered(device)
                            }
                        }
                    }
                }
            }
        } finally {
            socket.close()
        }
    }
    
    /**
     * 检查设备超时
     */
    private fun checkDeviceTimeout() {
        val now = System.currentTimeMillis()
        val lostDevices = mutableListOf<String>()
        
        discoveredDevices.forEach { (id, device) ->
            if (now - device.lastSeen > AppConstants.DEVICE_TIMEOUT_MS) {
                lostDevices.add(id)
            }
        }
        
        lostDevices.forEach { id ->
            discoveredDevices.remove(id)
            onDeviceLost(id)
        }
    }
    
    /**
     * 启动文件接收服务器
     */
    private fun startFileServer() {
        // P2-03：同 startDiscovery，用作用域快照做发布守卫
        val myScope = scope
        serverJob = myScope.launch {
            // 端口占用时只停用文件接收，不影响界面与其它功能
            val ss = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(transferPort), 50)
                }
            } catch (e: Exception) {
                synchronized(lifecycleLock) { if (scope === myScope) fileServerRunning = false }
                AppLogger.warn("TransferEngine", "文件接收不可用：TCP ${AppConstants.TRANSFER_PORT} 绑定失败(${e.message})，功能降级运行", e)
                return@launch
            }
            val published = synchronized(lifecycleLock) {
                if (scope !== myScope) false
                else {
                    serverSocket = ss
                    fileServerRunning = true
                    true
                }
            }
            if (!published) {
                closeQuietly(ss, "TransferEngine")
                return@launch
            }
            try {
                while (currentCoroutineContext().isActive) {
                    val clientSocket = try {
                        ss.accept()
                    } catch (e: java.io.IOException) {
                        if (!currentCoroutineContext().isActive || ss.isClosed) break else throw e
                    }
                    if (!transferSemaphore.tryAcquire()) {
                        AppLogger.warn("TransferEngine", "接收并发已达上限，拒绝新连接")
                        closeQuietly(clientSocket, "TransferEngine")
                        continue
                    }
                    acceptedSockets.add(clientSocket)
                    launch {
                        try {
                            handleIncomingFile(clientSocket)
                        } finally {
                            acceptedSockets.remove(clientSocket)
                            transferSemaphore.release()
                        }
                    }
                }
            } finally {
                synchronized(lifecycleLock) {
                    if (scope === myScope) {
                        serverSocket = null
                        fileServerRunning = false
                    }
                }
                closeQuietly(ss, "TransferEngine")
            }
        }
    }
    
    /**
     * 处理接收到的文件。
     *
     * 一次连接内可连续接收多个文件（发送文件夹时），每个文件是一段
     * `FILE|<相对路径>|<字节数>\n` 头 + 定长数据；连接关闭即结束。
     * 按声明长度精确读取，因此旧版单文件发送端（发完直接关闭）仍然兼容。
     */
    private suspend fun handleIncomingFile(socket: Socket) {
        val remoteIp = runCatching { socket.inetAddress?.hostAddress.orEmpty() }.getOrDefault("")
        try {
            socket.sendBufferSize = AppConstants.SOCKET_BUFFER_SIZE
            socket.receiveBufferSize = AppConstants.SOCKET_BUFFER_SIZE

            // 使用单一 BufferedInputStream 读取元数据和文件内容，避免 BufferedReader
            // 预读导致文件数据丢失
            val bufferedInput = BufferedInputStream(socket.getInputStream(), AppConstants.TRANSFER_BUFFER_SIZE)

            // P1-09：启用认证时，连接首行必须是 AUTH|<token>。校验放在任何 mkdirs / 写盘
            // 之前，且认证阶段单独设短超时，防止未认证连接靠 30s soTimeout 长期挂住。
            val requiredToken = accessToken
            if (requiredToken.isNotBlank()) {
                socket.soTimeout = AppConstants.TRANSFER_AUTH_TIMEOUT_MS
                val authLine = readHeaderLine(bufferedInput)
                // P2-18：与网页上传共用同一枚 128 bit 令牌，比较也统一走常量时间，
                // 避免接收端留一个比 WebUploadServer 更弱的旁路
                val expected = "AUTH|$requiredToken".toByteArray(Charsets.UTF_8)
                if (authLine == null || !MessageDigest.isEqual(
                        authLine.toByteArray(Charsets.UTF_8), expected
                    )
                ) {
                    AppLogger.warn("TransferEngine", "拒绝接收：配对校验未通过（来源 $remoteIp）")
                    return
                }
            }
            socket.soTimeout = AppConstants.SOCKET_TIMEOUT_MS

            val dir = receiveDir.apply { mkdirs() }
            val buffer = ByteArray(AppConstants.TRANSFER_BUFFER_SIZE)
            // 同一连接（同一批发送）内顶层文件夹只解析一次，否则第二个文件会因
            // “同名文件夹已存在”被另存为 xxx(1)
            val folderNames = HashMap<String, String>()
            // 一条连接内的累计字节与文件数：防止「大量小文件」绕开单文件上限
            var connectionBytes = 0L
            var fileCount = 0

            while (currentCoroutineContext().isActive) {
                val fileInfo = readHeaderLine(bufferedInput) ?: break

                if (!fileInfo.startsWith("FILE|")) {
                    return
                }
                if (++fileCount > AppConstants.MAX_TRANSFER_FILES_PER_CONNECTION) {
                    AppLogger.warn("TransferEngine", "拒绝接收：单连接文件数超过上限")
                    return
                }

                val parts = fileInfo.split("|")
                val rawName = parts.getOrNull(1) ?: ""
                val fileSize = parts.getOrNull(2)?.toLongOrNull() ?: -1L
                if (fileSize < 0 || fileSize > AppConstants.MAX_TRANSFER_FILE_BYTES ||
                    connectionBytes + fileSize > AppConstants.MAX_TRANSFER_REQUEST_BYTES
                ) {
                    AppLogger.warn(
                        "TransferEngine",
                        "拒绝接收：文件大小无效或超出上限（声明 $fileSize，累计 $connectionBytes）",
                    )
                    return
                }
                connectionBytes += fileSize

                // 安全：逐段校验相对路径，防止 ../ 目录穿越、保留设备名与非法字符
                val segments = sanitizeRelativePath(rawName)
                if (segments == null) {
                    AppLogger.warn("TransferEngine", "拒绝接收：非法文件路径 '${rawName.take(64)}'")
                    return
                }

                val outputFile = resolveReceiveTarget(dir, segments, folderNames)
                // P1-09：以接收目录真实路径为根做 confined 校验，并拒绝中间路径的符号链接
                if (!isWithinReceiveRoot(dir, outputFile)) {
                    AppLogger.warn("TransferEngine", "拒绝接收：目标路径越出接收目录")
                    return
                }

                receivePayload(bufferedInput, outputFile, fileSize, buffer)

                AppLogger.info("TransferEngine", "文件接收完成: ${outputFile.relativeTo(dir).path} ($fileSize bytes)")
                onFileReceived(outputFile, remoteIp)
            }

            bufferedInput.close()
        } catch (e: Exception) {
            AppLogger.warn("TransferEngine", "接收文件失败: ${e.message}", e)
        } finally {
            closeQuietly(socket, "TransferEngine")
        }
    }

    /**
     * P1-09：以接收目录的**真实路径**为根做 confined 校验，并拒绝中间路径上的符号链接。
     * 旧的 `canonicalPath.startsWith` 只做字符串前缀比较，接收目录里预置一个指向外部的
     * 软链目录即可绕过；这里跟随 `toRealPath()` 后逐段检查。
     */
    private fun isWithinReceiveRoot(root: File, target: File): Boolean {
        val rootAbs = root.toPath().toAbsolutePath().normalize()
        val rootReal = runCatching { rootAbs.toRealPath() }.getOrDefault(rootAbs)
        val parentAbs = target.parentFile?.toPath()?.toAbsolutePath()?.normalize() ?: return false
        val parentReal = runCatching { parentAbs.toRealPath() }.getOrNull() ?: return false
        if (!parentReal.startsWith(rootReal)) return false
        var cursor: java.nio.file.Path? = parentAbs
        while (cursor != null && cursor.startsWith(rootAbs) && cursor != rootAbs) {
            if (Files.isSymbolicLink(cursor)) return false
            cursor = cursor.parent
        }
        return true
    }

    /** 读取一行元数据（UTF-8）；EOF 或超长时返回 null */
    private fun readHeaderLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) return bytes.toByteArray().toString(Charsets.UTF_8)
            if (bytes.size() >= AppConstants.MAX_HEADER_LENGTH) {
                AppLogger.warn("TransferEngine", "拒绝接收：元数据头过长")
                return null
            }
            bytes.write(b)
        }
    }

    /** 按声明长度精确读取一个文件；不完整时删除半截文件并抛异常 */
    private fun receivePayload(input: InputStream, outputFile: File, fileSize: Long, buffer: ByteArray) {
        outputFile.parentFile?.mkdirs()
        var remaining = fileSize
        var completed = false
        try {
            // use{} 保证正常/异常路径都关流（含 FileOutputStream 构造成功后 write 抛错）
            BufferedOutputStream(FileOutputStream(outputFile), AppConstants.TRANSFER_BUFFER_SIZE).use { output ->
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n == -1) throw EOFException("连接提前关闭，文件不完整")
                    output.write(buffer, 0, n)
                    remaining -= n
                }
                output.flush()
            }
            completed = true
        } finally {
            if (!completed) runCatching { outputFile.delete() } // 不留下半截文件（P2-5）
        }
    }

    /**
     * 文件名校验与净化：只保留最后一段、拒绝保留设备名与非法字符
     * @return 安全文件名；非法时返回 null
     */
    fun sanitizeFileName(rawName: String): String? {
        val name = rawName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        if (name.isEmpty() || name == "." || name == "..") return null
        if (name.any { it in ILLEGAL_NAME_CHARS }) return null
        val base = name.substringBefore('.').uppercase()
        if (name.length > AppConstants.MAX_FILE_NAME_LENGTH) return null
        if (base in WINDOWS_RESERVED_NAMES) return null
        return name
    }

    /**
     * 校验并拆分相对路径：逐段净化，拒绝 `..`/`.`/空段与超深层级。
     * @return 安全的路径段；非法时返回 null
     */
    fun sanitizeRelativePath(rawPath: String): List<String>? {
        val segments = rawPath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.size > AppConstants.MAX_PATH_SEGMENTS) return null
        if (segments.any { it == "." || it == ".." }) return null
        return segments.map { sanitizeFileName(it) ?: return null }
    }

    /**
     * 计算文件的落盘位置并创建所在目录。
     * 多段路径视为文件夹传输：顶层文件夹在本连接内只做一次同名避让。
     */
    private fun resolveReceiveTarget(
        dir: File,
        segments: List<String>,
        folderNames: MutableMap<String, String>,
    ): File {
        if (segments.size == 1) return resolveUniqueFile(File(dir, segments[0]))

        val top = folderNames.getOrPut(segments[0]) { resolveUniqueDirName(dir, segments[0]) }
        var parent = File(dir, top)
        for (i in 1 until segments.size - 1) parent = File(parent, segments[i])
        parent.mkdirs()
        return resolveUniqueFile(File(parent, segments.last()))
    }

    /** 同名文件夹自动追加 (1)、(2) 后缀（文件夹名里的点不当作扩展名） */
    private fun resolveUniqueDirName(parent: File, name: String): String {
        if (!File(parent, name).exists()) return name
        var counter = 1
        while (File(parent, "$name($counter)").exists()) counter++
        return "$name($counter)"
    }

    /**
     * 同名文件自动追加 (1)、(2) 后缀
     */
    private fun resolveUniqueFile(file: File): File {
        if (!file.exists()) return file
        val base = file.name.substringBeforeLast('.', file.name)
        val ext = if (file.name.contains('.')) "." + file.name.substringAfterLast('.') else ""
        var counter = 1
        while (true) {
            val candidate = File(file.parentFile, "$base($counter)$ext")
            if (!candidate.exists()) return candidate
            counter++
        }
    }
    
    /**
     * 取消正在发送的任务（关闭 Socket，使阻塞中的 write 立即抛异常退出）
     */
    fun cancelSend(taskId: String) {
        activeSockets.remove(taskId)?.let {
            closeQuietly(it, "TransferEngine")
        }
    }

    /**
     * 递归收集文件夹下的所有普通文件（不跟随符号链接目录，避免环）。
     * remotePath 以所选文件夹名开头，接收端据此还原目录结构。
     */
    fun collectFolderFiles(folder: File): List<OutgoingFile> {
        if (!folder.isDirectory) return emptyList()
        val root = folder.toPath()
        val entries = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .map { OutgoingFile(it.toFile(), "${folder.name}/${root.relativize(it).toString().replace('\\', '/')}") }
                .iterator()
                .asSequence()
                .toList()
        }
        // 协议头用 | 和换行分隔，含这些字符的路径无法安全表达（Windows 本就不允许）；
        // Unix 上极少见，跳过并记录，避免接收端解析错位
        val safe = entries.filter { e -> e.remotePath.none { it == '|' || it == '\n' || it == '\r' } }
        if (safe.size != entries.size) {
            AppLogger.warn("TransferEngine", "跳过 ${entries.size - safe.size} 个文件名含协议保留字符的文件")
        }
        return safe
    }

    /**
     * 发送单个文件到目标设备（支持取消）
     *
     * @param taskId 任务 ID，用于 cancelSend() 断开连接；为空时自动生成
     * @param onProgress (进度 0..1, 瞬时速度 字节/秒, 已发送字节)
     */
    suspend fun sendFile(
        file: File,
        targetDevice: NetworkDevice,
        taskId: String = java.util.UUID.randomUUID().toString(),
        onProgress: (Float, Long, Long) -> Unit
    ): Boolean = sendFiles(listOf(OutgoingFile(file, file.name)), targetDevice, taskId, onProgress)

    /**
     * 在一条连接内连续发送多个文件（发送文件夹时使用）。
     *
     * 协议：每个文件为 `FILE|<相对路径>|<字节数>\n` + 定长数据，全部发完关闭连接。
     * 接收端按声明长度精确读取，因此单文件场景与旧协议互相兼容。
     *
     * @param taskId 任务 ID，用于 cancelSend() 断开连接；为空时自动生成
     * @param onProgress (进度 0..1, 瞬时速度 字节/秒, 已发送字节)
     */
    suspend fun sendFiles(
        files: List<OutgoingFile>,
        targetDevice: NetworkDevice,
        taskId: String = java.util.UUID.randomUUID().toString(),
        onProgress: (Float, Long, Long) -> Unit
    ): Boolean {
        var socket: Socket? = null
        var isCancelled = false
        val totalSize = files.sumOf { it.file.length() }
        val buffer = ByteArray(AppConstants.TRANSFER_BUFFER_SIZE)

        return try {
            // 先建空 Socket 并登记，再做带超时的 connect：
            // 1) `Socket(ip, port)` 没有连接超时，对端关机要等系统 SYN 重试（20~130s）才报错；
            // 2) 先登记才能让「连接中」的取消生效 —— cancelSend 会关掉这个还没连上的 socket，
            //    阻塞中的 connect 随即抛异常退出。
            val s = Socket()
            socket = s
            activeSockets[taskId] = s
            s.connect(InetSocketAddress(targetDevice.ipAddress, transferPort), AppConstants.SOCKET_CONNECT_TIMEOUT_MS)
            // 优化：设置Socket缓冲区大小和TCP参数，提升传输性能
            s.sendBufferSize = AppConstants.SOCKET_BUFFER_SIZE
            s.receiveBufferSize = AppConstants.SOCKET_BUFFER_SIZE
            s.tcpNoDelay = true  // 禁用Nagle算法，减少延迟

            val outputStream = s.getOutputStream()
            val writer = BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8))
            // P1-09：与接收端同源的配对 token；为空时保持旧协议（兼容未启用认证的接收端）
            if (accessToken.isNotBlank()) {
                writer.write("AUTH|${accessToken}\n")
                writer.flush()
            }

            var totalBytes = 0L
            var lastSpeedUpdate = System.currentTimeMillis()
            var lastBytes = 0L

            AppLogger.info("TransferEngine", "开始发送: ${files.size} 个文件, 共 $totalSize bytes")

            for (entry in files) {
                // 取消检查：协程被取消、或 Socket 已被 cancelSend() 关闭
                if (!currentCoroutineContext().isActive || s.isClosed) {
                    isCancelled = true
                    break
                }

                // 元信息用 UTF-8 写入，避免中文文件名乱码
                writer.write("FILE|${entry.remotePath}|${entry.file.length()}\n")
                writer.flush()

                FileInputStream(entry.file).use { inputStream ->
                    val bufferedInput = BufferedInputStream(inputStream, AppConstants.TRANSFER_BUFFER_SIZE)
                    while (true) {
                        val bytesRead = bufferedInput.read(buffer)
                        if (bytesRead == -1) break
                        if (!currentCoroutineContext().isActive || s.isClosed) {
                            isCancelled = true
                            break
                        }

                        outputStream.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead

                        // 每隔一定时间更新进度和速度（瞬时速度 = 本区间增量 / 区间时长）
                        val now = System.currentTimeMillis()
                        if (now - lastSpeedUpdate >= AppConstants.SPEED_UPDATE_INTERVAL_MS) {
                            val interval = (now - lastSpeedUpdate).coerceAtLeast(1L)
                            val speed = (totalBytes - lastBytes) * 1000L / interval
                            val progress = if (totalSize > 0) totalBytes.toFloat() / totalSize else 1f
                            onProgress(progress.coerceIn(0f, 1f), speed, totalBytes)
                            lastSpeedUpdate = now
                            lastBytes = totalBytes
                        }
                    }
                }
                if (isCancelled) break
            }

            if (isCancelled) {
                AppLogger.debug("TransferEngine", "传输已取消: ${files.size} 个文件")
                s.close()
                return false
            }

            // 确保所有数据都已发送
            outputStream.flush()
            onProgress(1f, 0L, totalBytes)
            s.close()

            AppLogger.info("TransferEngine", "发送完成: ${files.size} 个文件 ($totalBytes bytes)")
            true

        } catch (e: java.nio.channels.ClosedByInterruptException) {
            AppLogger.debug("TransferEngine", "传输被中断")
            closeQuietly(socket, "TransferEngine")
            false
        } catch (e: CancellationException) {
            // 协程取消：正常流程，不打印堆栈
            closeQuietly(socket, "TransferEngine")
            throw e
        } catch (e: Exception) {
            AppLogger.warn("TransferEngine", "发送文件失败: ${e.message}", e)
            closeQuietly(socket, "TransferEngine")
            false
        } finally {
            activeSockets.remove(taskId)
        }
    }
}

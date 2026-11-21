package top.mcly99.dl.model

/**
 * 局域网设备信息数据模型
 * 
 * 该数据类用于封装局域网内网络设备（如路由器、NAS、智能电视等）的基本信息，
 * 主要用于局域网文件传输、设备发现和网络服务管理功能。
 * 
 * 设计目标：
 * - 支持多协议设备发现（UDP广播、mDNS、SSDP等）
 * - 提供设备连接状态监控
 * - 支持设备分组和标签管理
 * - 兼容不同网络环境（IPv4/IPv6混合网络）
 * 
 * @property id 设备唯一标识符（UUID格式）
 * @property name 设备友好名称（用户可读）
 * @property ipAddress 设备IP地址（支持IPv4和IPv6格式）
 * @property port 设备服务端口（默认9876用于设备发现）
 * @property lastSeen 最后在线时间戳（毫秒）
 * 
 * @see TransferEngine 局域网传输引擎实现
 * @see TransferViewModel 传输功能ViewModel
 */
data class NetworkDevice(
    val id: String,
    val name: String,
    val ipAddress: String,
    val port: Int,
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * 文件传输任务状态
 */
enum class TransferStatus {
    WAITING,      // 等待传输
    TRANSFERRING, // 传输中
    COMPLETED,    // 已完成
    FAILED,       // 失败
    CANCELLED     // 已取消
}

/**
 * 文件传输任务
 */
data class TransferTask(
    val id: String = java.util.UUID.randomUUID().toString(),
    val fileName: String,
    val fileSize: Long,
    val targetDevice: NetworkDevice,
    val status: TransferStatus = TransferStatus.WAITING,
    val progress: Float = 0f,
    val speed: Long = 0L,
    val transferredSize: Long = 0L,
    val direction: TransferDirection = TransferDirection.Send,
    val errorMessage: String? = null,
    /**
     * 总大小仍在统计中（v4-P3-3）。
     *
     * 文件夹发送需要先枚举目录才能算出总字节数，大目录会耗时数秒。这期间 `fileSize` 为 0，
     * 但 0 本身也是合法值（空文件夹），所以不能靠 `fileSize == 0L` 判断，必须有显式标记。
     */
    val sizePreparing: Boolean = false,
    val startTime: Long = System.currentTimeMillis()
) {
    fun formatSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / 1024 / 1024} MB"
            else -> "${size / 1024 / 1024 / 1024} GB"
        }
    }
}

/** 是否已经结束：完成 / 失败 / 已取消，都不会再自己变化 */
val TransferTask.isFinished: Boolean
    get() = status == TransferStatus.COMPLETED ||
        status == TransferStatus.FAILED ||
        status == TransferStatus.CANCELLED

/**
 * 只保留仍在进行（等待 / 传输中）的记录 —— 「清空已结束」的判定规则。
 *
 * 抽成纯函数是为了能直接测：传输历史的过滤逻辑原先写在 ViewModel 里，而 ViewModel
 * 构造就会启动 UDP 发现，测试里没法单独验证。规则本身错了（比如把「失败」当成
 * 进行中也一并清掉）不会崩，只会让用户丢失刚刚失败、准备重试的记录。
 */
fun List<TransferTask>.keepingUnfinished(): List<TransferTask> = filter { !it.isFinished }

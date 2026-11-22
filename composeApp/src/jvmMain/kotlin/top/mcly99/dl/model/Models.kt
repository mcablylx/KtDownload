package top.mcly99.dl.model

/**
 * UI 模型集合（P2-2）。
 *
 * 引擎内部使用 `DownloadTask` / `TransferTask` / `NetworkDevice`；
 * UI 只消费 `UiTask` / `UiTransfer` / `UiDevice`。转换集中在
 * `model/Mappers.kt`，并由 `AppViewModel` 的 `derivedStateOf` 缓存，
 * 不再在每次重组时重建整份列表。
 *
 * 旧版并行的 `DownloadTaskNew` / `TransferTaskNew` / `Device` / `TransferStatusNew`
 * 已删除，避免两套状态枚举互相映射（例如 CANCELLED 被误显示为失败）。
 */

enum class ViewType {
    All,
    Downloading,
    Completed,
    Transfer,
    /** 抓网页：输入网址 → 列出页面里的 m3u8 / mp4 → 下载或播放 */
    CatchWeb,
    /** 云盘：粘贴分享链接 → 浏览文件树 → 取直链下载 */
    CloudDrive,
    /** 我的网盘：管理自己网盘里的文件（P4） */
    MyDrive,
    Settings,
}

/** UI 展示用的协议类型；引擎当前只接受 HTTP/HTTPS */
enum class TaskType {
    Http,
    Bt,
    M3u8,
}

enum class TransferDirection {
    Send,
    Receive,
}

enum class DeviceType {
    Desktop,
    Mobile,
    Tablet,
}

/**
 * 面向 UI 的下载任务快照。
 *
 * @property status 直接复用引擎状态，避免再做一套枚举映射
 */
data class UiTask(
    val id: String,
    val name: String,
    val url: String,
    val type: TaskType,
    val status: DownloadStatus,
    val progress: Int,
    val size: Long,
    val downloaded: Long,
    val speed: Long,
    val threads: Int,
    val createdAtEpochMs: Long,
    val completedAtEpochMs: Long? = null,
    val savePath: String,
    val error: String? = null,
    val segmentDone: Int = 0,
    val segmentTotal: Int = 0,
) {
    /** 进行中 = 下载中 + 校验合并中 + 暂停 + 排队（侧边栏与列表共用口径） */
    val isActiveTask: Boolean
        get() = status == DownloadStatus.DOWNLOADING ||
            status == DownloadStatus.MERGING ||
            status == DownloadStatus.PAUSED ||
            status == DownloadStatus.QUEUED

    val isFinished: Boolean
        get() = status == DownloadStatus.COMPLETED ||
            status == DownloadStatus.FAILED ||
            status == DownloadStatus.CANCELLED
}

data class UiDevice(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int,
    val type: DeviceType,
    val online: Boolean,
    val lastSeenEpochMs: Long,
)

data class UiTransfer(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val transferred: Long,
    val speed: Long,
    val direction: TransferDirection,
    val deviceId: String,
    val deviceName: String,
    val status: TransferStatus,
    val progress: Int,
    /** 总大小仍在统计中；为 true 时 `fileSize` 无意义（v4-P3-3） */
    val sizePreparing: Boolean = false,
) {
    val isActive: Boolean
        get() = status == TransferStatus.WAITING || status == TransferStatus.TRANSFERRING
}

data class AppSettings(
    val downloadPath: String = "/Downloads",
    val maxConcurrent: Int = 3,
    val defaultThreads: Int = 8,
    val speedLimit: Long = 0L,
    val autoStart: Boolean = true,
    val clipboardWatch: Boolean = true,
    val showNotifications: Boolean = true,
    val minimizeToTray: Boolean = true,
    val language: String = "zh-CN",
    val theme: String = "dark",
    /**
     * 网络代理模式。旧的 `proxyEnabled: Boolean` 已于 P1 换成三态；
     * [SettingsRepository] 负责把旧配置文件里的布尔值迁移成 MANUAL / DIRECT。
     */
    val proxyMode: ProxyMode = ProxyMode.DIRECT,
    val proxyHost: String = "",
    val proxyPort: Int = 1080,
    /**
     * 允许 OkHttp 客户端通过 ALPN 协商 HTTP/2。默认关闭（仅 HTTP/1.1）：
     * 上游实验页同样默认关闭，且部分网盘 CDN 在 h2 下会重置流。
     * 只作用于 [top.mcly99.dl.data.network.HttpClients] 管理的客户端，
     * 下载引擎的 HttpURLConnection 不走这条路径。
     */
    val http2Enabled: Boolean = false,
    /**
     * 多账号下载（实验性 C1）：默认关。
     *
     * 开启后，同一平台登录了多个账号时，批量下载按文件轮换账号取链（主账号优先），
     * 用来绕开单账号的并发数 / 限速；每个文件仍然只用一个账号。开关关闭或只有一个
     * 账号时行为与之前完全一致。上游同样是默认关的实验开关。
     */
    val multiAccountDownload: Boolean = false,
    /**
     * 单文件多源分片（实验性 C2）：默认关，**且依赖 [multiAccountDownload]（C1）**。
     *
     * 开启后，批量下载时会对夸克 / UC / 迅雷 / 百度这几个平台的同一文件，用账号池里的
     * 其它账号各取一条直链；引擎按分片轮转选源，某片失败时换下一个源重试。
     * 只对内置分片下载器（大文件 + 支持 Range）生效，小文件 / HLS 仍是单源。
     *
     * 上游同样默认关，但它的 `pendingAltLinks` 从未传给 `enqueueDownload`，功能是空转的；
     * 下游在实现时补上了这根线。关掉 C1 会联动关掉本开关（设置页与存储层各有一道保险）。
     */
    val multiSourceDownload: Boolean = false,
    /**
     * 下载完成后关机（对齐上游 v1.3.4）：默认关。
     *
     * 只有「真的跑过下载 → 队列排空且没有主动暂停的任务」才进入 60 秒倒计时，
     * 期间可取消；状态机常驻 AppViewModel，切页面 / 托盘等待同样生效。
     */
    val shutdownAfterComplete: Boolean = false,
    val lanServerEnabled: Boolean = true,
    val lanServerPort: Int = 8080,
    val lanDiscoveryEnabled: Boolean = true,
    /** HLS 合成成功后保留 `*.hls` 分片目录，默认清理 */
    val keepHlsSegments: Boolean = false,
    /**
     * 抓取后并发探测每个媒体地址的文件大小，在结果列表里显示体积。
     *
     * 默认关闭：一个页面可能有几十条结果，每条一次请求，慢的时候要十几秒。
     */
    val probeMediaSize: Boolean = false,
    /**
     * 深度抓取：用本机 Chromium 内核（Chrome / Edge）打开页面并监听网络请求。
     *
     * 默认关闭，三个原因：
     * - 每次抓取要多等 5~15 秒（要等播放器真正发出请求）；
     * - 目标页面会被当普通网页完整执行（含广告、统计脚本），用户应当知情；
     * - 机器上没有 Chrome / Edge 时不可用，需要能一键退回静态抓取。
     *
     * 开启后，静态抓取拿不到地址的站点（播放器完全由 JavaScript 构建）才有机会抓到。
     */
    val deepCatchEnabled: Boolean = false,
    /**
     * 自动检查更新：默认开。启动时后台静默检查 GitHub 上的最新 Release，
     * 有新版本时在侧边栏版本处显示角标；检查失败完全静默，不影响使用。
     */
    val autoCheckUpdate: Boolean = true,
)

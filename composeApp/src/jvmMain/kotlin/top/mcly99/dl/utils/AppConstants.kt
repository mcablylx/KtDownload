package top.mcly99.dl.utils

import java.nio.file.Path
import java.nio.file.Paths

/**
 * 全局常量。
 *
 * P2-5：原先 9876/9877 在两个文件各写一份，10MB/2s/10s/30s/512KB/2MB 等数字
 * 散落在引擎各处。集中到这里后，协议与性能参数只有一个来源，调整时不必再全局搜索。
 *
 * 说明：局域网发现/传输端口属于双方必须一致的协议参数，暂不做成用户可改设置；
 * 网页上传端口已经在 [top.mcly99.dl.model.AppSettings.lanServerPort] 中可配置。
 */
object AppConstants {

    // ---- 局域网协议 ----
    const val DISCOVERY_PORT = 9876
    const val TRANSFER_PORT = 9877
    const val DISCOVERY_PROTOCOL_VERSION = "v1"
    const val BROADCAST_INTERVAL_MS = 2_000L
    const val DEVICE_OFFLINE_TIMEOUT_MS = 10_000L
    const val DEVICE_TIMEOUT_MS = 30_000L
    const val DISCOVERY_BUFFER_SIZE = 1024
    const val SOCKET_TIMEOUT_MS = 30_000

    /**
     * 发送端建立 TCP 连接的时限。
     *
     * `Socket(ip, port)` 这个构造器**没有**连接超时，对端关机 / 被防火墙 DROP 时要等
     * 系统默认的 SYN 重试（Linux 约 130s、Windows 约 21s）才报错，期间任务卡在「传输中」
     * 且取消都不好使（P1-23）。局域网内 5 秒足够完成三次握手，超了就是不通。
     */
    const val SOCKET_CONNECT_TIMEOUT_MS = 5_000
    const val TRANSFER_BUFFER_SIZE = 512 * 1024
    const val SOCKET_BUFFER_SIZE = 2 * 1024 * 1024
    const val SPEED_UPDATE_INTERVAL_MS = 200L

    // ---- 下载引擎 ----
    const val MULTI_THREAD_THRESHOLD_BYTES = 10L * 1024 * 1024
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 30_000
    const val SINGLE_BUFFER_SIZE = 1024 * 1024
    const val CHUNK_BUFFER_SIZE = 1024 * 1024
    const val MERGE_BUFFER_SIZE = 1024 * 1024
    const val IO_POLL_MS = 300L
    const val OFFSET_FLUSH_MS = 1_000L
    const val PROGRESS_PUBLISH_INTERVAL_MS = 200L
    const val MAX_THREADS = 64
    const val TASK_PERSIST_DEBOUNCE_MS = 2_000L
    /** 下载文件名上限（为同名后缀与完整路径留出余量） */
    const val MAX_DOWNLOAD_FILE_NAME_LENGTH = 180

    // ---- Web 上传 ----
    const val DEFAULT_WEB_PORT = 8080
    const val MAX_REQUEST_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_UPLOAD_FILE_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_FILES_PER_REQUEST = 64
    const val MAX_CONCURRENT_UPLOADS = 4
    const val MAX_PART_HEADER_BYTES = 16 * 1024
    const val WEB_IO_BUFFER_SIZE = 256 * 1024
    const val WEB_PORT_RESTART_DEBOUNCE_MS = 3_000L

    /**
     * 上传连接的**空闲**读超时（不是总时长）。
     *
     * 手机上拖一个大文件、中途切后台/断网，socket 不会立刻发 RST，于是服务端那条
     * 阻塞的 `read` 会一直挂着 —— 线程池只有 4 条线程，攒够 4 个这样的连接，
     * 之后所有上传都会 429（P1-22）。
     *
     * 用「多久没读到字节」而不是「整个请求用了多久」：大文件在慢速 WiFi 上合法地传
     * 十几分钟是正常的，按总时长杀会误伤；而只要字节还在来，就说明连接还活着。
     */
    const val WEB_UPLOAD_IDLE_TIMEOUT_MS = 60_000L

    /** 空闲检查的扫描间隔：够及时释放线程，又不至于每秒唤醒一次 */
    const val WEB_UPLOAD_WATCHDOG_INTERVAL_MS = 5_000L

    /** 同名文件改名的最大尝试次数（`a.txt` → `a(1).txt` …），防止目录里同名副本过多时空转 */
    const val MAX_NAME_COLLISION_ATTEMPTS = 1_000

    // ---- 抓网页 ----
    /** 抓取页面的正文上限；超过截断而不是失败（媒体地址几乎都在前 1 MB） */
    const val CATCH_WEB_MAX_PAGE_BYTES = 8 * 1024 * 1024

    /** 单个页面最多列出多少条媒体地址，防止长列表把 UI 拖死 */
    const val CATCH_WEB_MAX_RESULTS = 200

    /**
     * 最多跟随几个 iframe。
     *
     * 很多站点的播放器在独立 iframe 里（主页面一条媒体地址都没有），
     * 但页面上同时挂着大量广告 iframe —— 抓全部既慢又没意义，取前几个就够。
     */
    const val CATCH_WEB_MAX_FRAMES = 3

    /**
     * 抓取用的 User-Agent。
     *
     * 不用 `KtDownload/x.y.z`：不少站点对非浏览器 UA 直接返回简化页或空壳，
     * 那样连静态的媒体地址都拿不到。
     */
    const val CATCH_WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // ---- 在线播放 ----
    /**
     * 帧回调转 ImageBitmap 的帧率上限。
     *
     * 每帧要把整幅画面从 int[] 搬到 byte[]（1080p 约 8 MB），不设上限时高码率源
     * 会把 CPU 全花在搬运上；30fps 对「预览」够用，且明显降低卡顿。
     */
    const val PLAYER_MAX_FPS = 30

    /**
     * 拉取主播放列表（清晰度列表）的正文上限。
     *
     * 主列表只有几十行（每档 2 行），1 MB 已是极端富裕；设上限是为了防止
     * 把某个大文件当播放列表读进内存。
     */
    const val PLAYER_PLAYLIST_MAX_BYTES = 1024 * 1024

    // ---- 抓网页：探测文件大小 ----
    /**
     * 并发探测文件大小的连接数。
     *
     * 默认关闭这个功能（见 `AppSettings.probeMediaSize`）：一个页面可能有几十条结果，
     * 每条一次 HEAD，串行要十几秒，并发太高又容易被 CDN 判成扫描。4 是折中值。
     */
    const val SIZE_PROBE_CONCURRENCY = 4

    // ---- 抓网页：深度抓取（内嵌 Chromium / CDP）----
    /**
     * 等浏览器写出 `DevToolsActivePort` 与页面目标的时限。
     *
     * 冷启动的 Chrome 在机械盘上要 2~4 秒，杀毒软件首扫时更久；10 秒足够，
     * 再长就是浏览器起不来了，不如早点降级回静态抓取。
     */
    const val BROWSER_START_TIMEOUT_MS = 10_000L

    /**
     * 页面加载 + 拦截的总时限。
     *
     * 目标站点的播放器通常 `load` 之后才去换签名地址，太短会「浏览器起来了但什么都没抓到」；
     * 15 秒是「够慢站点起播」与「用户等得住」的折中。抓到可播地址后会提前收工（见 settle 逻辑）。
     */
    const val BROWSER_CAPTURE_TIMEOUT_MS = 15_000L

    /** 出现第一条可播地址后再等多久收工：等后续的清晰度列表与分片请求落地 */
    const val BROWSER_SETTLE_MS = 3_000L


    // ---- 安全限制 ----
    const val MAX_HEADER_LENGTH = 8 * 1024
    const val MAX_FILE_NAME_LENGTH = 200
    /** 接收时允许的最大目录层级（防止对端构造超深路径） */
    const val MAX_PATH_SEGMENTS = 64

    // ---- UI / 生命周期 ----
    const val SETTINGS_SAVE_DEBOUNCE_MS = 500L
    const val CLIPBOARD_POLL_MS = 2_000L

    /** 应用内一次性提示的停留时间：够读完一句「已清理 XX MB」，又不至于挡住操作 */
    const val TOAST_VISIBLE_MS = 3_000L
    const val TRANSFER_REFRESH_INTERVAL_MS = 1_000L
    const val PORT_INPUT_REVERT_MS = 3_000L
    const val COPY_FEEDBACK_MS = 1_500L
    const val TRAY_MENU_DEBOUNCE_MS = 300L

    /**
     * 从托盘唤起窗口后，等窗口真正显示出来再置前的延时。
     *
     * 隐藏 → 显示时立刻 `toFront()` 会作用在还没 `setVisible(true)` 的窗口上，等于没调用；
     * 80ms 足够 AWT 把显示请求交给窗口管理器，又不至于让人感觉到迟滞。
     */
    const val WINDOW_RAISE_DELAY_MS = 80L
    const val MAX_SPEED_LIMIT_BYTES = 100L * 1024 * 1024

    /** 配置、任务与日志的统一根目录 */
    val APP_DATA_DIR: Path = Paths.get(System.getProperty("user.home"), ".mclydownload")

    val LOG_DIR: Path = APP_DATA_DIR.resolve("logs")

    /** 日志保留天数；启动及跨天滚动时清理更早的日志 */
    const val LOG_RETENTION_DAYS = 30L
}

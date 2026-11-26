package top.mcly99.dl.engine

import kotlinx.coroutines.flow.StateFlow
import top.mcly99.dl.model.DownloadSource
import top.mcly99.dl.model.DownloadTask
import java.net.Proxy
import java.nio.file.Path

/**
 * [DownloadViewModel] 真正依赖的下载能力窄接口（P3-T2）。
 *
 * 为什么不让 ViewModel 直接依赖 [DownloadEngine]：那个类是近 1900 行的实现体，
 * 里面有 HTTP 收发、分块合并、ffmpeg 合成、任务持久化。单测只要碰到它就必须准备
 * 真实网络与磁盘，结果就是「核心 ViewModel 一条测试都没有」。这里只圈出 ViewModel
 * 用到的 20 个成员，测试实现一个纯内存的假引擎即可。
 *
 * 约定：
 * - **只放 ViewModel 用到的成员**。加新成员前先问「UI 状态机真的需要它吗」，
 *   否则接口会慢慢长回 [DownloadEngine] 的形状，窄接口就白做了。
 * - [taskState] 的语义是「引擎当前的全量任务快照」，由引擎负责节流，ViewModel 只订阅。
 * - 所有 `remove*` / `pause*` 只作用于引擎里的**全部**任务，不受 UI 搜索/筛选影响。
 *
 * @see DownloadEngine 生产实现
 * @see top.mcly99.dl.viewmodel.DownloadViewModel
 */
interface DownloadOperations {
    /** 全部任务的只读快照流；引擎在进度变化时节流推送 */
    val taskState: StateFlow<List<DownloadTask>>

    /** 最大并发下载数（同时下载的文件数） */
    var maxConcurrentDownloads: Int

    /** 单文件下载线程数 */
    var downloadThreadsPerFile: Int

    /** 默认下载目录；新建任务未指定路径时落在这里 */
    var defaultDownloadPath: Path

    /** 全局下载限速（字节/秒），0 = 不限速 */
    var speedLimit: Long

    /** 当前生效代理的现取来源；系统代理模式下每次请求都重新解析 */
    var proxyProvider: () -> Proxy?

    /** 当前是否有代理生效（供 UI / 诊断读取） */
    val proxyEnabled: Boolean

    /** HLS 合成成功后是否保留 `*.hls` 分片目录 */
    var keepHlsSegments: Boolean

    /** 恢复上次会话的任务（下载中的以暂停态恢复） */
    fun restoreTasks()

    /** 关闭引擎：断开连接、取消任务 */
    fun shutdown()

    /**
     * 添加下载任务，返回任务 id。
     *
     * @param threads 单文件线程数；默认取当前配置 [downloadThreadsPerFile]
     * @throws IllegalArgumentException 链接为空、BT 或协议不受支持
     */
    fun addTask(
        url: String,
        savePath: Path? = null,
        threads: Int = downloadThreadsPerFile,
        referer: String? = null,
        cookie: String? = null,
        userAgent: String? = null,
        origin: String? = null,
        secChUa: String? = null,
        totalSize: Long = 0L,
        altSources: List<DownloadSource> = emptyList(),
    ): String

    /** 开始（或继续）某个任务 */
    fun startDownload(taskId: String)

    /** 暂停某个任务，已写入的分块保留 */
    fun pauseDownload(taskId: String)

    /** 暂停全部任务，含排队中的 */
    fun pauseAllDownloads(): Int

    /** 取消某个任务并清理残片 */
    fun cancelDownload(taskId: String)

    /** 按 id 取任务快照；不存在返回 null */
    fun getTask(taskId: String): DownloadTask?

    /** 移除单个任务；[deleteFile] 为 true 时连成品文件一起删 */
    fun removeTask(taskId: String, deleteFile: Boolean = false)

    /** 移除全部任务，返回移除数量 */
    fun removeAllTasks(deleteFile: Boolean = false): Int

    /** 请求一次调度（改并发上限后必须调，否则排队任务不会补位） */
    fun requestDispatch()

    /** 清理某个 HLS 任务残留的分片目录，返回释放的字节数 */
    fun cleanupHlsSegments(taskId: String): Long
}

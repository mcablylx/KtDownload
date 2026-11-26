package top.mcly99.dl.testutil

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.mcly99.dl.engine.DownloadOperations
import top.mcly99.dl.model.DownloadSource
import top.mcly99.dl.model.DownloadStatus
import top.mcly99.dl.model.DownloadTask
import java.net.Proxy
import java.nio.file.Path
import java.nio.file.Paths

/**
 * [DownloadOperations] 的纯内存假实现（P3-T2）。
 *
 * 不碰网络、不碰磁盘、不起协程：只把 ViewModel 的调用记下来，供断言。
 * 任务列表由 [setTasks] 直接替换 —— 真引擎里是下载进度推上来的，这里只关心
 * 「ViewModel 拿到这份快照后做了什么」。
 */
class FakeDownloadOperations : DownloadOperations {

    private val _taskState = MutableStateFlow<List<DownloadTask>>(emptyList())
    override val taskState: StateFlow<List<DownloadTask>> = _taskState.asStateFlow()

    override var maxConcurrentDownloads: Int = 3
    override var downloadThreadsPerFile: Int = 8
    override var defaultDownloadPath: Path = Paths.get("build", "fake-downloads")
    override var speedLimit: Long = 0L
    override var proxyProvider: () -> Proxy? = { null }
    override var keepHlsSegments: Boolean = false

    override val proxyEnabled: Boolean get() = proxyProvider() != null

    /** addTask 的入参快照 */
    data class AddedTask(
        val id: String,
        val url: String,
        val savePath: Path?,
        val threads: Int,
        val referer: String?,
        val cookie: String?,
        val totalSize: Long,
        val altSources: List<DownloadSource>,
    )

    data class RemovedTask(val id: String, val deleteFile: Boolean)

    val added = mutableListOf<AddedTask>()
    val started = mutableListOf<String>()
    val paused = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    val removed = mutableListOf<RemovedTask>()
    val cleanedHlsSegments = mutableListOf<String>()

    var restoreCalls = 0
    var shutdownCalls = 0
    var pauseAllCalls = 0
    var removeAllCalls = 0
    var dispatchRequests = 0
        private set

    fun setTasks(tasks: List<DownloadTask>) {
        _taskState.value = tasks
    }

    /** 从假引擎里推一份新快照，模拟下载进度/状态变化 */
    fun emit(tasks: List<DownloadTask>) = setTasks(tasks)

    override fun restoreTasks() {
        restoreCalls++
    }

    override fun shutdown() {
        shutdownCalls++
    }

    override fun addTask(
        url: String,
        savePath: Path?,
        threads: Int,
        referer: String?,
        cookie: String?,
        userAgent: String?,
        origin: String?,
        secChUa: String?,
        totalSize: Long,
        altSources: List<DownloadSource>,
    ): String {
        val id = "fake-${added.size + 1}"
        added += AddedTask(id, url, savePath, threads, referer, cookie, totalSize, altSources)
        return id
    }

    override fun startDownload(taskId: String) {
        started += taskId
    }

    override fun pauseDownload(taskId: String) {
        paused += taskId
    }

    override fun pauseAllDownloads(): Int {
        pauseAllCalls++
        val ids = _taskState.value.filter {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
        }.map { it.id }
        paused += ids
        return ids.size
    }

    override fun cancelDownload(taskId: String) {
        cancelled += taskId
    }

    override fun getTask(taskId: String): DownloadTask? =
        _taskState.value.firstOrNull { it.id == taskId }

    override fun removeTask(taskId: String, deleteFile: Boolean) {
        removed += RemovedTask(taskId, deleteFile)
        _taskState.value = _taskState.value.filterNot { it.id == taskId }
    }

    override fun removeAllTasks(deleteFile: Boolean): Int {
        removeAllCalls++
        val count = _taskState.value.size
        _taskState.value = emptyList()
        return count
    }

    override fun requestDispatch() {
        dispatchRequests++
    }

    override fun cleanupHlsSegments(taskId: String): Long {
        cleanedHlsSegments += taskId
        return 1024L
    }
}

package top.mcly99.dl.engine

import top.mcly99.dl.utils.ignoreFailure
import top.mcly99.dl.utils.closeQuietly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.mcly99.dl.APP_VERSION
import top.mcly99.dl.engine.hls.FfmpegMuxer
import top.mcly99.dl.engine.hls.HlsDownloader
import top.mcly99.dl.engine.hls.HlsMuxResult
import top.mcly99.dl.engine.web.redactUrl
import top.mcly99.dl.model.DownloadSource
import top.mcly99.dl.model.DownloadStatus
import top.mcly99.dl.model.DownloadTask
import top.mcly99.dl.model.TaskType
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import top.mcly99.dl.utils.SafeDelete
import top.mcly99.dl.utils.detectUrlType
import top.mcly99.dl.utils.formatBytes
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 下载引擎 - 负责管理所有下载任务。
 *
 * 架构：
 * - **状态流**：对外暴露只读 [taskState]（`StateFlow<List<DownloadTask>>`），
 *   不再「每次推送后让 ViewModel 全量拉取」；进度发布做了 200ms 节流。
 * - **状态机**：所有状态迁移经 [DownloadTaskStateMachine]。
 * - **调度**：`activeJobs` 的「检查—启动—登记」由 [dispatchLock] 串行化。
 * - **可中断**：保存 [HttpURLConnection] / [FileChannel] 引用，暂停/取消时立即断开。
 * - **断点续传**：多线程模式用单文件 `name.part` + 段表快照 `name.segments`。
 * - **IO**：各连接按绝对偏移写同一个文件，完成即改名，没有合并步骤。
 */
class DownloadEngine(
    /** 测试可关闭任务持久化，避免集成测试污染用户的 `~/.mclydownload/tasks.properties` */
    private val taskPersistenceEnabled: Boolean = true,
    /** 多线程阈值可注入；生产使用 [AppConstants.MULTI_THREAD_THRESHOLD_BYTES] */
    private val multiThreadThresholdBytes: Long = AppConstants.MULTI_THREAD_THRESHOLD_BYTES,
    /**
     * 初始分段粒度可注入。
     *
     * 生产取 4 MiB：12 MiB 的文件只切 3 段，不会被 64 线程设置切成 64 片 × 187 KiB。
     * 测试用小值（64 KiB）才能在几 MB 的样本上验证「多段并行 + 切分 + 续传」的真实行为。
     */
    private val segmentBytes: Long = SegmentTuning.MIN_SEGMENT_BYTES,
) : DownloadOperations {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 收尾清理协程专用作用域（P1-05）。
     *
     * 「取消/删除任务后删 `.partN` / `.offset` / `{name}.hls` / `deleteFile`」这些承诺
     * 不能挂在 [scope] 上：`shutdown()` 末尾会 `scope.cancel()`，用户取消任务后立刻关应用
     * 时清理协程会被一起取消，残片留在磁盘上、Windows 下还占着文件句柄。
     * 这里用独立的 SupervisorJob，只有 [shutdown] 在 join 完成后才取消它。
     */
    private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 写盘专用调度器：**并发度为 1**（F 项）。
     *
     * 所有连接的分块写都提交到这里串行执行。不是为了「省线程」，而是为了两件事：
     * 1. NTFS 上一次 flush 会挂住该文件的所有写，多个线程同时写同一个句柄只会互相拖垮；
     * 2. 串行之后，某条连接在等盘时，其它连接仍能在自己的线程上读网络（各自持有缓冲），
     *    「盘慢」表现为整体变慢，而不是所有连接一起断速。
     */
    private val diskDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** 写盘耗时统计：给慢连接判据提供「是不是盘在拖后腿」的证据（F 项） */
    private val writeStats = WriteStats(SegmentTuning.DISK_STALL_THRESHOLD_NANOS)

    /** 主机连接上限学习表（E 项）：进程级共享，同一网盘不同任务能互相对照 */
    private val hostCaps = HostCapRegistry()

    /** 任务表：写操作统一由 [stateLock] 保护 */
    private val taskMap = ConcurrentHashMap<String, DownloadTask>()
    private val stateLock = Any()

    private val _taskState = MutableStateFlow<List<DownloadTask>>(emptyList())
    override val taskState: StateFlow<List<DownloadTask>> = _taskState.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()

    /**
     * 已取消但可能仍在收尾的协程（P1-04）。
     *
     * `pauseDownload` / `cancelDownload` 只是 `cancel()`，协程要过一会儿才真正退出
     * （关文件、写 `.offset`）；`removeTask` 还要再起一个删分片的协程。用户「暂停 → 立刻继续」
     * 时新协程会和它们同时写同一个 `.partN`，读到错位的 offset，最后报「分块长度不符」。
     *
     * ★ 一个 taskId 可能**同时有多个**收尾协程（连点「取消→开始→取消→开始」）：
     * 1.0.3 用单值 `ConcurrentHashMap<String, Job>` 覆盖，旧 job 永久失去 join 点。
     * 现在改为「每任务一个 Job 列表 + 全部待清理 Job 集合」，启动前逐一 join，
     * 且要求 [cleaningPaths] 里的路径都释放后才允许复用同名文件。
     */
    private val stoppingJobs = ConcurrentHashMap<String, MutableList<Job>>()

    /** 全部进行中的收尾协程，供 [shutdown] 统一 join（P1-05）。 */
    private val pendingCleanups = CopyOnWriteArrayList<Job>()

    /** 正在被清理协程占用的目标路径：清理完成前 [addTask] 不能复用（P1-04）。
     *  internal：同模块测试可直接构造「清理中」状态做确定性断言。 */
    internal val cleaningPaths: MutableSet<Path> = ConcurrentHashMap.newKeySet()

    /** 登记一个收尾 Job：同 taskId 用 compute 原子追加，完成后自动移除。 */
    private fun trackStopping(taskId: String, job: Job) {
        stoppingJobs.compute(taskId) { _, list ->
            (list ?: CopyOnWriteArrayList()).apply { add(job) }
        }
        job.invokeOnCompletion {
            stoppingJobs.compute(taskId) { _, list ->
                list?.apply { remove(job) }?.takeIf { it.isNotEmpty() }
            }
        }
    }

    /** 取某个任务当前所有待 join 的收尾协程快照（CopyOnWriteArrayList 保证并发安全）。 */
    private fun stoppingSnapshot(taskId: String): List<Job> =
        stoppingJobs[taskId]?.toList().orEmpty()

    /**
     * 在不可取消的 [cleanupScope] 上启动一个收尾清理协程，并登记到
     * [stoppingJobs] / [pendingCleanups] / [cleaningPaths]。
     *
     * @param interruptedJob 被 [interrupt] 取消的下载协程，清理前先 join 它（句柄关完才能删）
     * @param path 本次清理占用的目标文件路径；清理完成后释放
     */
    private fun launchCleanup(
        taskId: String,
        interruptedJob: Job?,
        path: Path,
        block: suspend () -> Unit,
    ) {
        cleaningPaths.add(path.toAbsolutePath().normalize())
        val job = cleanupScope.launch {
            try {
                interruptedJob?.let { runCatching { it.join() } }
                block()
            } finally {
                cleaningPaths.remove(path.toAbsolutePath().normalize())
            }
        }
        trackStopping(taskId, job)
        pendingCleanups.add(job)
        job.invokeOnCompletion { pendingCleanups.remove(job) }
    }

    /** 调度锁：保证「检查并发上限 → 启动 → 登记」是原子操作 */
    private val dispatchLock = Mutex()

    /** 任务 -> 当前连接，用于暂停/取消时断开阻塞读 */
    private val taskConnections = ConcurrentHashMap<String, HttpURLConnection>()

    /**
     * 任务 -> 当前打开的文件句柄，取消时先关闭以免 Windows 下文件被占用。
     *
     * 值必须是线程安全的：多线程下载时 8 个分块协程会并发 [CopyOnWriteArrayList.add]（每个分块开自己的
     * 句柄）与 [CopyOnWriteArrayList.remove]（分块下完就关）。用普通 ArrayList 时可能丢元素 ——
     * 漏登记的句柄在暂停/取消时关不掉，Windows 上文件被占用删不掉；也可能数组越界让任务直接失败。
     *
     * 另一个坑在取值方式：`getOrPut` 不是原子操作，两个协程同时取会各建一个列表、丢掉其中一个，
     * 所以下面一律用 `computeIfAbsent`。
     */
    private val taskFiles = ConcurrentHashMap<String, CopyOnWriteArrayList<Closeable>>()

    /** 任务 -> 正在运行的 ffmpeg 进程，暂停/取消时强制结束 */
    private val taskProcesses = ConcurrentHashMap<String, Process>()

    /** HLS 合成器：延迟创建，避免应用启动时就探测 ffmpeg */
    private val hlsMuxer by lazy { FfmpegMuxer() }

    /** 最近一次计算的瞬时速度（避免重复发送相同的 speed） */
    private val lastSpeed = ConcurrentHashMap<String, Long>()

    /** 进度发布节流：高频分块回调不需要每 300ms × N 次都重建列表 */
    private val lastPublishedAt = AtomicLong(0L)

    // 最大并发下载数（同时下载的文件数）；UI 线程写入，调度线程读取
    @Volatile
    override var maxConcurrentDownloads = 3

    // 单文件下载线程数（多线程加速）；UI 线程写入，工作线程读取
    // 默认值与 AppSettings.defaultThreads 保持一致（v4-P2-5：原先 4 / 8 两处不一致）
    @Volatile
    override var downloadThreadsPerFile = 8

    // 默认下载目录
    override var defaultDownloadPath: Path = initializeDefaultDownloadPath()

    /**
     * 全局下载限速（字节/秒）；0 表示不限速。
     * 所有任务的分块线程共享同一个令牌桶，因此限制的是「总带宽」而非单线程。
     */
    @Volatile
    override var speedLimit: Long = 0L
        set(value) {
            field = value.coerceAtLeast(0L)
            rateLimiter.bytesPerSecond = field
        }

    private val rateLimiter = RateLimiter(0L)

    /**
     * 当前生效代理的**现取**来源（P1）。
     *
     * 三态代理统一由 `resolveProxy(mode, host, port)` 解析后注入，引擎不再自己判断
     * 「启用 / 主机 / 端口」：这样系统代理模式下不会在启动时取一次快照就永远不变，
     * 手动代理模式也能在设置页改完立刻生效。
     */
    @Volatile
    override var proxyProvider: () -> java.net.Proxy? = { null }

    /** 当前是否有代理生效（供 UI / 诊断读取） */
    override val proxyEnabled: Boolean get() = currentProxy() != null

    /** HLS 合成成功后是否保留 `*.hls` 分片目录 */
    @Volatile
    override var keepHlsSegments: Boolean = false

    /** 探测逻辑抛异常时按直连处理，不能让一个分片因为代理配置问题整条任务失败 */
    private fun currentProxy(): java.net.Proxy? = runCatching { proxyProvider() }.getOrNull()

    /** 任务持久化的节流协程（@Volatile：addTask / removeTask / shutdown 可能来自不同线程） */
    @Volatile
    private var persistJob: Job? = null

    /** 串行化「cancel + 赋值」，避免 check-then-act 留下无主 Job（P2-05） */
    private val persistLock = Any()

    /**
     * 请求持久化任务列表（2 秒节流，避免频繁写盘）
     */
    private fun requestPersist() {
        if (!taskPersistenceEnabled) return
        synchronized(persistLock) {
            persistJob?.cancel()
            persistJob = scope.launch {
                delay(AppConstants.TASK_PERSIST_DEBOUNCE_MS)
                persistNow()
            }
        }
    }

    private fun persistNow() {
        if (!taskPersistenceEnabled || !scope.isActive) return
        TaskStore.save(taskMap.values.toList())
    }

    /**
     * 加载上次会话保存的任务（在构造后立即调用）。
     *
     * 这里不自动调用 dispatch：是否自动开始由用户的 `autoStart` 设置决定，
     * 由 [top.mcly99.dl.viewmodel.DownloadViewModel.startAll] 触发。
     */
    override fun restoreTasks() {
        if (!taskPersistenceEnabled) return
        val restored = TaskStore.load()
        if (restored.isEmpty()) return
        synchronized(stateLock) {
            restored.forEach {
                // 目标目录可能已被删除（例如移动硬盘未挂载），这里补建，避免恢复后写入失败
                runCatching { it.savePath.parent?.toFile()?.mkdirs() }
                taskMap[it.id] = it
            }
            publishLocked(force = true)
        }
        AppLogger.info("DownloadEngine", "已恢复 ${restored.size} 个任务")
    }

    companion object {
        /** 416 Range Not Satisfiable；HttpURLConnection 没有对应常量 */
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /**
         * 下载过程中发现远端内容变了时，允许的**重下一次**的次数。
         *
         * 只给一次机会：探测与分块请求之间隔了几百毫秒，正常服务器上几乎不可能变；
         * 真的变了说明文件正在被持续写入，无限重试只会白烧带宽。
         */
        private const val MAX_CONTENT_RESTARTS = 1
    }

    // ------------------------------------------------------------------ 任务管理

    /**
     * 添加下载任务。
     *
     * 参数默认值由 [DownloadOperations] 声明（override 处不能再写一遍），含义也写在那里：
     * - `referer` 防盗链页面地址，跨域 CDN 缺了就是 403；
     * - `cookie` 深度抓取带回来的会话，带签名的地址缺了会「浏览器能播、这里下不动」；
     * - `userAgent` 覆盖默认 UA，网盘 CDN 对平台串有硬要求（115 / 迅雷 / 百度）；
     * - `origin` / `secChUa` 对应 UC、蓝奏优享的跨域校验与游客链路；
     * - `totalSize` 调用方已知的文件大小，0 表示交给引擎探测（夸克类 CDN 的 HEAD 常被 403）；
     * - `altSources` C2 备选直链，空列表 = 老的单源行为。
     *
     * @throws IllegalArgumentException 链接为空、BT 或协议不受支持
     */
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
        val trimmedUrl = url.trim()
        require(trimmedUrl.isNotEmpty()) { "下载链接不能为空" }
        val type = detectUrlType(trimmedUrl)
        require(type != TaskType.Bt) { "暂不支持 BT 任务，请使用 HTTP / HTTPS 直链或 m3u8 链接" }
        require(isSupportedUrl(trimmedUrl)) {
            val scheme = trimmedUrl.substringBefore("://", "").ifBlank { "未知协议" }
            "暂不支持该协议（$scheme），目前仅支持 HTTP / HTTPS 直链与 m3u8"
        }

        val id = UUID.randomUUID().toString()
        val fileName = extractFileName(trimmedUrl)

        // 处理保存路径：目录则与文件名组合，否则视为完整文件路径
        val resolvedPath = resolveTaskPath(savePath, fileName, defaultDownloadPath)
        val path = if (type == TaskType.M3u8) ensureHlsOutputPath(resolvedPath) else resolvedPath

        // 选名与入表必须在同一临界区：否则两个线程并发添加同名文件时，
        // 都会在对方入表前看到「路径空闲」，最终写同一个目标文件（P2-E2）
        val task = synchronized(stateLock) {
            // HLS 的 `{name}.hls` 目录也是任务产物：残留目录或已排队任务占用同名路径时同样递增序号，
            // 否则不同视频的分片会被 `.done` 标记混拼（v3-P1-2）
            val finalPath = handleDuplicateFile(path) { candidate ->
                (type == TaskType.M3u8 && hlsSegmentDir(candidate.toFile()).exists()) ||
                    taskMap.values.any { it.savePath == candidate } ||
                    // P1-04：删除/取消任务的清理协程可能还在删 `.partN` / `.offset`，
                    // 此时复用同名路径会让新任务的分片被旧协程删掉。清理完成前一律递增序号。
                    cleaningPaths.contains(candidate.toAbsolutePath().normalize())
            }
            DownloadTask(
                id = id,
                url = trimmedUrl,
                fileName = finalPath.fileName.toString(),
                savePath = finalPath,
                status = DownloadStatus.QUEUED,
                threads = threads.coerceIn(1, AppConstants.MAX_THREADS),
                type = type,
                totalSize = totalSize.coerceAtLeast(0L),
                referer = referer?.takeIf { it.isNotBlank() },
                cookie = cookie?.takeIf { it.isNotBlank() },
                userAgent = userAgent?.takeIf { it.isNotBlank() },
                origin = origin?.takeIf { it.isNotBlank() },
                secChUa = secChUa?.takeIf { it.isNotBlank() },
                requiresCookie = !cookie.isNullOrBlank(),
                // 去掉空地址 / 与主源重复的条目；按 URL 去重，避免同一账号被取链两次时
                // 分片轮转退化成「一半分片都打同一个源」。
                altSources = altSources
                    .filter { it.url.isNotBlank() && it.url != trimmedUrl }
                    .distinctBy { it.url },
            ).also {
                taskMap[id] = it
                publishLocked(force = true)
            }
        }
        task.savePath.parent?.toFile()?.mkdirs()

        requestPersist()
        AppLogger.debug("DownloadEngine", "任务入队: ${task.fileName}")

        // 交给调度器：有空位就直接开始，否则排队等待补位
        scope.launch { dispatch() }
        return id
    }

    /** 当前引擎支持的下载协议 */
    private fun isSupportedUrl(url: String): Boolean =
        top.mcly99.dl.utils.isSupportedDownloadUrl(url)

    /**
     * 删掉某个 HLS 任务残留的分片目录，返回释放的字节数。
     *
     * 只删 `{name}.hls`，不动最终 mp4 —— 失败与降级完成时那个 mp4 可能不存在或就是用户要的（v7-P2-3）。
     * 任务不存在、不是 HLS、目录已经不在了都返回 0，调用方据此给「没有可清理的分片」。
     */
    override fun cleanupHlsSegments(taskId: String): Long {
        val task = getTask(taskId) ?: return 0L
        if (task.type != TaskType.M3u8) return 0L
        val dir = hlsSegmentDir(task.savePath.toFile())
        if (!dir.exists()) return 0L
        val bytes = runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            .getOrDefault(0L)
        val deleted = SafeDelete.deleteTree(dir, "HLS 分片")
        if (!deleted) {
            AppLogger.warn("DownloadEngine", "删除分片目录失败: $dir")
            return 0L
        }
        AppLogger.info("DownloadEngine", "已清理分片: $dir (${formatBytes(bytes)})")
        return bytes
    }

    /**
     * 开始/继续下载
     */
    override fun startDownload(taskId: String) {
        val task = synchronized(stateLock) { taskMap[taskId] } ?: return
        if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.MERGING) return
        // 重启后 Cookie 不在了（v8-P0-1）：直接给出明确原因，不发一个注定 403 的请求。
        // 恢复出来的任务本来就是 FAILED，这里再拦一次是因为 UI 的「继续」按钮不看状态。
        if (task.requiresCookie && task.cookie.isNullOrBlank()) {
            updateTask(taskId, errorMessage = COOKIE_NOT_PERSISTED_MESSAGE, force = true)
            AppLogger.info("DownloadEngine", "任务需要会话 Cookie，已拒绝继续: ${task.fileName}")
            return
        }
        // 统一置为排队，交给调度器；避免并发上限判断的竞态
        updateTask(taskId, event = DownloadEvent.ENQUEUE, errorMessage = null)
        scope.launch { dispatch() }
    }

    /**
     * 暂停下载：断开连接 → 取消协程 → 标记暂停，已下载分片保留
     */
    override fun pauseDownload(taskId: String) {
        pauseInternal(taskId)
        scope.launch { dispatch() }
    }

    /**
     * 暂停全部可暂停的任务（进行中 + 排队中），返回实际暂停的数量。
     *
     * 不能简单地 `forEach { pauseDownload(it) }`：后者每停一个就 `dispatch()` 一次，
     * 刚空出来的名额会被「下一个还没轮到暂停的 QUEUED 任务」立刻占走 —— 用户看到的是
     * 「全部暂停」之后下载根本没停，只是换了一批任务在跑（P1-1）。所以这里先把所有目标
     * 一次性置为 PAUSED，最后只调度一次（此时队列已空，不会补位）。
     */
    override fun pauseAllDownloads(): Int {
        val ids = synchronized(stateLock) {
            taskMap.values
                .filter {
                    it.status == DownloadStatus.DOWNLOADING ||
                        it.status == DownloadStatus.MERGING ||
                        it.status == DownloadStatus.QUEUED
                }
                .map { it.id }
        }
        ids.forEach { pauseInternal(it) }
        if (ids.isNotEmpty()) scope.launch { dispatch() }
        return ids.size
    }

    /** 暂停的公共部分：[pauseDownload] 与 [pauseAllDownloads] 共用，不含调度。 */
    private fun pauseInternal(taskId: String) {
        interrupt(taskId)?.let { trackStopping(taskId, it) }
        activeJobs.remove(taskId)
        updateTask(taskId, event = DownloadEvent.PAUSE, speed = 0L)
    }

    /**
     * 外部条件变化后主动补位排队任务。
     *
     * 目前只有「并发上限调大」用它：`dispatch()` 的触发点原本只有入队 / 完成 / 失败 /
     * 暂停 / 取消 / 删除，不含设置变更，用户把并发从 1 改成 8 后排队任务要等下一次
     * 事件才会动，看起来像设置没生效（P1-6）。
     */
    override fun requestDispatch() {
        scope.launch { dispatch() }
    }

    /**
     * 取消下载：断开连接、取消协程，清理临时分片和未完成的目标文件。
     *
     * 已完成的任务不删除文件；暂停仍走 [pauseDownload]，保留断点。
     */
    override fun cancelDownload(taskId: String) {
        val job = interrupt(taskId)
        job?.let { trackStopping(taskId, it) }
        activeJobs.remove(taskId)
        val task = synchronized(stateLock) { taskMap[taskId] }
        updateTask(taskId, event = DownloadEvent.CANCEL, speed = 0L)
        scope.launch { dispatch() }
        if (task == null) return

        // 等下载协程真正退出后再删文件：Windows 下文件句柄未关闭时 delete() 会失败。
        // 走 launchCleanup（独立不可取消作用域 + 登记 stoppingJobs / cleaningPaths），
        // 保证「取消后立刻关应用」时这段清理仍会跑完（P1-05），且同名文件不会被立刻重加（P1-04）。
        launchCleanup(taskId, job, task.savePath) {
            // 期间可能已被重试或删除，任务不在 CANCELLED 状态时不要再动目标文件
            val current = synchronized(stateLock) { taskMap[taskId] }
            if (current == null || current.status != DownloadStatus.CANCELLED) return@launchCleanup

            val target = current.savePath.toFile()
            cleanupParts(target)
            if (current.type == TaskType.M3u8) {
                SafeDelete.deleteTree(hlsSegmentDir(target), "HLS 分片")
            }
            try {
                if (target.exists() && !target.delete()) {
                    AppLogger.warn("DownloadEngine", "取消后删除未完成文件失败: ${current.savePath}")
                }
            } catch (e: Exception) {
                AppLogger.warn("DownloadEngine", "取消后删除未完成文件失败 ${current.savePath}: ${e.message}", e)
            }
        }
    }

    /**
     * 删除任务。
     *
     * @param deleteFile 为 true 时同时删除磁盘上的成品文件。
     *
     * **默认 false 是刻意的**：`removeTask` 是「把任务从列表里拿掉」，不是「删文件」。
     * 原来默认 true，UI 上一个「×」按钮就静默删了下载好的文件（P0-1）。要删文件请显式传 true，
     * 并且由 UI 先问用户一句。临时文件（`.partN` / `.offset` / `{name}.hls`）不受这个参数影响，
     * 它们不是用户的成品，一律清理。
     */
    override fun removeTask(taskId: String, deleteFile: Boolean) {
        val job = interrupt(taskId)
        job?.let { trackStopping(taskId, it) }
        activeJobs.remove(taskId)
        val task = synchronized(stateLock) {
            val removed = taskMap.remove(taskId)
            if (removed != null) publishLocked(force = true)
            removed
        }
        lastSpeed.remove(taskId)
        task?.let { removed ->
            // 与 cancelDownload 同构：先等下载协程真正退出再删文件。
            // interrupt() 只是 cancel + disconnect，协程可能还停在 read() 或 catch 里，
            // 立刻 cleanupParts 会与它的收尾写盘撞车，把 `.part*` / `.offset` 重建出来（v6-P1-2）。
            // 这里额外登记 cleaningPaths：删除分片下载中的任务后立刻重加同名文件时，
            // addTask 会看到路径仍被占用并递增序号，不再复用同一组 `.partN`（P1-04）。
            launchCleanup(taskId, job, removed.savePath) {
                try {
                    val file = removed.savePath.toFile()
                    cleanupParts(file)
                    // `.hls` 分片目录不是用户的成品，是这次任务自建的中间产物：
                    // 必须无条件清理，否则任务记录一删，cleanupHlsSegments(taskId) 也失去入口，
                    // 分片目录（峰值可达成品的两倍）就永远留在磁盘上 —— 「全部删除」的
                    // 确认文案里写着「未完成的临时分片会被清理」，却被 deleteFile=false 挡掉了（P1-2）。
                    // 唯一例外：用户显式开了「保留分片」且任务已成功合成，那时分片是他要的产物。
                    val keepSegments = keepHlsSegments && removed.status == DownloadStatus.COMPLETED
                    if (removed.type == TaskType.M3u8 && !keepSegments) {
                        SafeDelete.deleteTree(hlsSegmentDir(file), "HLS 分片")
                    }
                    if (deleteFile && file.exists()) file.delete()
                } catch (e: Exception) {
                    AppLogger.warn("DownloadEngine", "删除文件失败 ${removed.savePath}: ${e.message}", e)
                }
            }
        }
        requestPersist()
        scope.launch { dispatch() }
    }

    override fun getTask(taskId: String): DownloadTask? = synchronized(stateLock) { taskMap[taskId] }

    /**
     * 删除全部任务（已完成 + 进行中 + 未完成）。
     *
     * 逐个走 [removeTask]：进行中的任务会先 `interrupt` 再等协程退出，临时分片随之清理，
     * 因此这里不需要另写一套收尾逻辑，也不会绕过已有的写盘竞态保护。
     *
     * @param deleteFile 为 true 时连磁盘文件一起删；「全部删除」传 false，避免误点清空下载目录
     * @return 实际移除的任务数
     */
    override fun removeAllTasks(deleteFile: Boolean): Int {
        // 先取快照再删：removeTask 内部会改 taskMap，边遍历边删会抛 ConcurrentModificationException
        val ids = synchronized(stateLock) { taskMap.keys.toList() }
        ids.forEach { removeTask(it, deleteFile) }
        return ids.size
    }

    /**
     * 调度器：在并发上限内启动所有 QUEUED 任务。
     * 由 [dispatchLock] 串行化，任务结束后再次调用即可自动补位。
     */
    private suspend fun dispatch() {
        dispatchLock.withLock {
            if (!scope.isActive) return
            val waiting = taskMap.values
                .filter { it.status == DownloadStatus.QUEUED && !activeJobs.containsKey(it.id) }
                .sortedBy { it.createTime }
            for (task in waiting) {
                if (activeJobs.size >= maxConcurrentDownloads) break
                launchTask(task.id)
            }
        }
    }

    /** 必须在 [dispatchLock] 内调用 */
    private fun launchTask(taskId: String) {
        val task = taskMap[taskId] ?: return
        // 先登记 Job，保证 activeJobs.size 反映真实占用
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // 上一个协程（暂停/取消/删除的那个）可能还在写 `.offset`、关句柄、删 `.partN`：
            // 等**全部**收尾协程退出再接手，否则两个协程同时写同一个 `.partN`，
            // 续传偏移与文件长度会错位（P1-04：单值覆盖会漏掉旧 job）。
            stoppingSnapshot(taskId).forEach { previous ->
                runCatching { previous.join() }
            }
            downloadFile(taskId)
        }
        activeJobs[taskId] = job
        updateTask(taskId, event = DownloadEvent.START, errorMessage = null, speed = 0L)
        job.start()
        AppLogger.debug("DownloadEngine", "开始任务: ${task.fileName}")
    }

    /** 断开连接 + 取消协程 */
    private fun interrupt(taskId: String): Job? {
        // 先断开 HTTP 连接，让阻塞中的 read() 立即返回异常。
        // 分块连接使用 "$taskId#$index" 作为 key，必须一并断开，否则暂停/取消
        // 只能取消协程，阻塞在 socket read 上的分块线程仍会占用连接。
        taskConnections.entries
            .filter { it.key == taskId || it.key.startsWith("$taskId#") }
            .forEach { (key, connection) ->
                taskConnections.remove(key)
                ignoreFailure("DownloadEngine", "断开连接") { connection.disconnect() }
            }
        taskFiles.remove(taskId)?.forEach {
            closeQuietly(it, "DownloadEngine")
        }
        taskProcesses.remove(taskId)?.let { process ->
            ignoreFailure("DownloadEngine", "结束进程") { process.destroyForcibly() }
        }
        val job = activeJobs.remove(taskId)
        job?.cancel()
        return job
    }

    // ------------------------------------------------------------------ 下载主流程

    private suspend fun downloadFile(taskId: String) {
        val task = getTask(taskId) ?: return
        // 在挂起点显式取当前协程 Job：避免 finally 中解析到外层 CoroutineScope 的 coroutineContext
        val selfJob = currentCoroutineContext()[Job]
        try {
            task.savePath.parent?.toFile()?.mkdirs()
            if (task.type == TaskType.M3u8) {
                downloadHls(taskId, task)
                return
            }
            val probe = probeRemote(task)
            // 顺序不能反：探测失败时若用探测结果（-1）覆盖已有值，本来知道大小的云盘任务
            // 会被打回「0 B」，进度条与速度一起归零
            val totalSize = probe.size.takeIf { it > 0 } ?: task.totalSize.takeIf { it > 0 } ?: 0L
            if (totalSize > 0 && totalSize != task.totalSize) updateTask(taskId, totalSize = totalSize)

            // P0-2：先确认「远端还是原来那份」，再决定要不要接着用本地半成品
            val restart = reconcileFingerprint(taskId, task, probe.fingerprint)
            val current = task.copy(
                totalSize = totalSize.coerceAtLeast(0L),
                etag = if (probe.fingerprint.isKnown) probe.fingerprint.etag else task.etag,
                lastModified = if (probe.fingerprint.isKnown) probe.fingerprint.lastModified else task.lastModified,
            )

            if (totalSize <= 0) {
                AppLogger.info("DownloadEngine", "未知文件大小，单线程下载: ${task.fileName}")
                // 明确传入未知大小，避免用上次运行残留的 totalSize 做校验
                downloadSingleThread(taskId, current.copy(totalSize = 0L), restart = restart)
                complete(taskId, null)
                return
            }

            // 空间预检放在「决定单线程还是多线程」之前：两条路径写的是同一块磁盘，
            // 而稀疏预扩展会立刻按最终长度占位，所以越早拦住越划算。
            // restart 时旧临时文件马上会被删掉，不能拿它的长度抵扣。
            val targetFile = current.savePath.toFile()
            val spaceIssue = DiskSpaceGuard.check(
                target = targetFile,
                totalSize = totalSize,
                alreadyOnDisk = if (restart) 0L else DiskSpaceGuard.existingBytes(targetFile),
            )
            if (spaceIssue != null) {
                AppLogger.warn("DownloadEngine", "拒绝开始下载：$spaceIssue（${task.fileName}）")
                updateTask(taskId, event = DownloadEvent.FAIL, errorMessage = spaceIssue, speed = 0L)
                return
            }

            // 小文件本来就走单线程，连「支不支持分段」都不必问：懒求值，
            // 否则每次小文件下载都白多一个 Range 请求（这个断言在集成测试里钉死）
            if (totalSize < multiThreadThresholdBytes) {
                AppLogger.debug("DownloadEngine", "单线程下载: ${task.fileName} ($totalSize bytes)")
                downloadSingleThread(taskId, current, restart = restart)
                complete(taskId, totalSize)
                return
            }

            // 探测里已经发过 `Range: bytes=0-0` 就直接用那个结论：再发一遍是纯粹重复的
            // 一个 RTT + 一次建连（旧实现最多 3 个请求定方案，现在最多 2 个）。
            val canRange = probe.rangeSupported ?: supportsRangeRequests(task.url, task.auth())
            if (!canRange) {
                AppLogger.debug("DownloadEngine", "服务端不支持分段，单线程下载: ${task.fileName}")
                downloadSingleThread(taskId, current, restart = restart)
                complete(taskId, totalSize)
                return
            }

            val threadCount = minOf(task.threads, initialSegmentCount(totalSize, task.threads, segmentBytes))
            AppLogger.debug("DownloadEngine", "多线程下载 x$threadCount: ${task.fileName} ($totalSize bytes)")
            downloadMultiThread(taskId, current, totalSize)
            complete(taskId, totalSize)
        } catch (e: CancellationException) {
            // 正常取消/暂停：状态已由 pause/cancel 设置，这里只清理
            throw e
        } catch (e: Exception) {
            // 暂停/取消是「先 disconnect 再 cancel」：阻塞在 read 上的协程拿到的是
            // `SocketException` / `IOException: stream is closed`，根本轮不到 CancellationException。
            // 这条异常如果晚于用户的下一次「继续」落地，就会把刚起来的下载标成失败 ——
            // 现象就是「暂停一下再继续，任务莫名其妙红了」。
            // 判定方式不看任务状态（它随时可能被新协程改成 DOWNLOADING），而是看
            // 「这条失败所属的协程还是不是当前这个任务的主人」：
            // 暂停/取消会把它从 activeJobs 里摘掉，新一轮会换成新的 Job。
            if (activeJobs[taskId] !== selfJob) {
                AppLogger.debug(
                    "DownloadEngine",
                    "上一轮协程的失败已作废（任务已被暂停/取消/重启）: ${task.fileName} ${e.message}",
                )
                return
            }
            AppLogger.warn("DownloadEngine", "任务失败 (${task.fileName}): ${e.message}", e)
            // 带时效签名的地址过期时换一句人话：否则用户看到的就是「HTTP 403」，只会反复重试（v7-P2-4）
            updateTask(
                taskId,
                event = DownloadEvent.FAIL,
                errorMessage = describeDownloadFailure(task.url, e.message),
                speed = 0L,
            )
        } finally {
            taskConnections.remove(taskId)
            taskFiles.remove(taskId)?.forEach { closeQuietly(it, "DownloadEngine") }
            // 只有真正结束时才移出 activeJobs 并补位；暂停/取消已在外部移除
            if (selfJob != null && activeJobs[taskId] == selfJob) {
                activeJobs.remove(taskId)
                // 用引擎作用域继续调度排队任务（被取消的子协程不能用来 launch）
                scope.launch { dispatch() }
            }
        }
    }

    /**
     * HLS 任务：分片下载（[DownloadStatus.DOWNLOADING]）→ ffmpeg/fMP4 合成（[DownloadStatus.MERGING]）。
     *
     * 没有可用 ffmpeg 且快路径不适用时，按计划降级为「完成（附警告）」，分片保留在
     * `{name}.hls` 目录，用户可手动转换；ffmpeg 存在但失败/超时则按失败处理，分片同样保留。
     */
    private suspend fun downloadHls(taskId: String, task: DownloadTask) {
        val output = task.savePath.toFile()
        val segmentDir = hlsSegmentDir(output)
        // 一个采样器管整条任务：8 并发分片各算各的会得到 8 个互不相干的瞬时值
        val hlsSpeed = ByteRateSampler()
        var lastByteEmit = 0L
        val downloader = HlsDownloader(
            concurrency = task.threads,
            rateLimiter = rateLimiter,
            proxy = currentProxy(),
            // 页面地址优先（跨域 CDN 认的是播放页）；没有页面地址时退回老行为：用播放列表自己的地址
            referer = task.referer ?: task.url,
            cookie = task.cookie,
            // 网盘任务带平台专用 UA；抓网页任务继续用浏览器 UA；手动粘贴的保持 KtDownload
            userAgent = task.userAgent,
            origin = task.origin,
            // 有页面地址 = 抓网页来的任务，UA 也伪装成浏览器；手动粘贴的地址保持 KtDownload（v7-P1-2）
            browserUserAgent = task.referer != null,
        )

        val result = downloader.download(
            playlistUrl = task.url,
            outputDir = segmentDir,
            onProgress = { done, total ->
                updateTask(taskId, segmentDone = done, segmentTotal = total)
            },
            // HLS 以前只有片数、没有字节数，UI 干脆把速度行整段隐藏了（v6-P1-1）。
            // 现在分片写入时会回传累计字节，这里折算成 B/s 写进任务。
            onBytes = { bytes ->
                val now = System.currentTimeMillis()
                if (now - lastByteEmit >= AppConstants.IO_POLL_MS) {
                    lastByteEmit = now
                    updateTask(taskId, downloadedSize = bytes, speed = hlsSpeed.sample(bytes, now))
                }
            },
            onConnection = { key, connection ->
                // 复用 interrupt() 的前缀匹配：暂停/取消时立即断开 HLS 阻塞读
                val mapKey = "$taskId#hls:$key"
                if (connection == null) {
                    taskConnections.remove(mapKey)
                } else {
                    taskConnections[mapKey] = connection
                }
            },
        )

        val segmentCount = result.segments.size + result.audioSegments.size
        updateTask(
            taskId,
            segmentDone = segmentCount,
            segmentTotal = segmentCount,
            event = DownloadEvent.MERGE,
        )
        if (getTask(taskId)?.status != DownloadStatus.MERGING) return

        // 合成会把分片再复制一份成 mp4，**峰值占用是文件的两倍**。磁盘不够时提前失败并说清楚，
        // 比让 ffmpeg 写到一半报一个看不懂的错要好（v7-P2-3）
        val segmentBytes = result.segments.sumOf { it.length() } +
            (result.initSegment?.length() ?: 0L) +
            result.audioSegments.sumOf { it.length() } +
            (result.audioInitSegment?.length() ?: 0L)
        val usable = output.parentFile?.usableSpace ?: Long.MAX_VALUE
        if (segmentBytes > 0 && usable < (segmentBytes * 1.05).toLong()) {
            updateTask(
                taskId,
                speed = 0L,
                event = DownloadEvent.FAIL,
                errorMessage = "磁盘空间不足：合成需要约 ${formatBytes(segmentBytes)}，" +
                    "当前剩余 ${formatBytes(usable)}；分片已保留：${result.directory}",
            )
            lastSpeed.remove(taskId)
            return
        }

        // 分片目录由引擎在确认 COMPLETE 后清理：暂停/取消落在 mux 成功与状态更新之间时，
        // 分片仍需保留，避免恢复任务时重新下载。
        val mux = hlsMuxer.mux(result, output, keepSegments = true) { process ->
            if (process == null) {
                taskProcesses.remove(taskId)
            } else {
                taskProcesses[taskId] = process
            }
        }
        when (mux) {
            is HlsMuxResult.Mp4 -> {
                val size = mux.file.length()
                updateTask(
                    taskId,
                    totalSize = size,
                    downloadedSize = size,
                    speed = 0L,
                    event = DownloadEvent.COMPLETE,
                )
                if (!keepHlsSegments && getTask(taskId)?.status == DownloadStatus.COMPLETED) {
                    SafeDelete.deleteTree(result.directory, "HLS 分片")
                }
            }
            is HlsMuxResult.Failed -> updateTask(
                taskId,
                speed = 0L,
                event = DownloadEvent.FAIL,
                errorMessage = "${mux.reason}；分片已保留：${mux.directory}",
            )
            is HlsMuxResult.SegmentsOnly -> updateTask(
                taskId,
                speed = 0L,
                event = DownloadEvent.COMPLETE,
                errorMessage = "${mux.reason}；分片已保留：${mux.directory}",
            )
        }
        lastSpeed.remove(taskId)
    }

    private fun complete(taskId: String, totalSize: Long?) {
        val task = getTask(taskId) ?: return
        // 被暂停/取消的任务不应被标记完成
        if (task.status != DownloadStatus.DOWNLOADING && task.status != DownloadStatus.MERGING) return
        // 已知大小时用调用方给的值；大小未知（HEAD 探测失败、chunked 响应）时用落盘长度兜底，
        // 否则完成的任务会一直显示「139 MB / 0 B」——文件明明下完了，UI 说大小是 0
        val onDisk = runCatching { task.savePath.toFile().length() }.getOrDefault(0L)
        // 一个字节都没落盘却要标完成：未知大小时（HEAD 探测失败 / chunked / 服务端回 200 空 body）
        // 以前会直接进 COMPLETED，UI 显示 100%，用户打开是个 0 字节空文件（P1-8）。
        // 宁可报失败让人重试，也不能把空文件当成果交付。
        if (onDisk <= 0L) {
            AppLogger.warn("DownloadEngine", "没有写入任何数据，标记失败: ${task.fileName}")
            updateTask(taskId, event = DownloadEvent.FAIL, errorMessage = EMPTY_DOWNLOAD_MESSAGE, speed = 0L)
            lastSpeed.remove(taskId)
            return
        }
        val finalTotal = totalSize?.takeIf { it > 0 } ?: onDisk.takeIf { it > 0 } ?: task.totalSize
        updateTask(
            taskId,
            totalSize = finalTotal.takeIf { it > 0 },
            downloadedSize = totalSize ?: onDisk.takeIf { it > 0 } ?: task.downloadedSize,
            speed = 0L,
            event = DownloadEvent.COMPLETE,
        )
        lastSpeed.remove(taskId)
    }

    /**
     * 一次 HTTP 请求要带上的身份信息。
     *
     * 打包成一个对象而不是给 [newConnection] 加五个参数：探测大小、续传、分片三条路径
     * 都要原样透传同一份头，散着传很容易漏掉其中一条（旧实现里 UA 只有引擎自己推断的
     * 那一份，网盘的平台专用 UA 根本到不了请求上）。
     */
    private data class RequestAuth(
        val referer: String? = null,
        val cookie: String? = null,
        val userAgent: String? = null,
        val origin: String? = null,
        val secChUa: String? = null,
    )

    /** 把任务上的头取成 [RequestAuth]；字段为空时保持引擎原有的推断行为 */
    private fun DownloadTask.auth(): RequestAuth = RequestAuth(
        referer = referer?.takeIf { it.isNotBlank() },
        cookie = cookie?.takeIf { it.isNotBlank() },
        userAgent = userAgent?.takeIf { it.isNotBlank() },
        origin = origin?.takeIf { it.isNotBlank() },
        secChUa = secChUa?.takeIf { it.isNotBlank() },
    )

    /**
     * C2 的源列表：主源（任务自己的 URL + 头）排在最前，其余按账号池顺序跟上。
     *
     * 返回 `(url, RequestAuth)` 对而不是两个平行列表，是为了从类型上杜绝
     * 「URL 换了、请求头没换」——这是多源下载最容易踩、也最难排查的错。
     */
    private fun DownloadTask.downloadSources(): List<Pair<String, RequestAuth>> = buildList {
        add(url to auth())
        altSources.forEach { source ->
            add(
                source.url to RequestAuth(
                    referer = source.referer?.takeIf { it.isNotBlank() },
                    cookie = source.cookie?.takeIf { it.isNotBlank() },
                    userAgent = source.userAgent?.takeIf { it.isNotBlank() },
                    origin = source.origin?.takeIf { it.isNotBlank() },
                    secChUa = source.secChUa?.takeIf { it.isNotBlank() },
                ),
            )
        }
    }

    /**
     * 建立连接。
     *
     * 请求方法（HEAD）和 `Range` 必须**在这里**传进来，不能拿回 connection 之后再
     * `setRequestProperty`：带 Cookie 时 [followRedirectsKeepingCookie] 为了判断是否需要
     * 丢会话已经读过 `responseCode`，连接早就是 established 状态了，再设头会抛
     * `IllegalStateException: Already connected` —— 表现就是「取不到文件大小」和
     * 「续传任务一启动就失败」。
     *
     * @param auth 这一次请求要带上的身份信息（Referer / Cookie / UA / Origin）
     * @param method 请求方法，默认 GET
     * @param range `Range` 请求头的值（如 `bytes=0-0`）；为 null 不设
     * @param ifRange `If-Range` 的值（内容指纹）：与 `Range` 配合，实体变了服务端就回 200 整份，
     *   而不是回一段属于**另一份文件**的 206 —— 断点续传唯一的服务端侧防线（P0-2）
     */
    private fun newConnection(
        url: String,
        auth: RequestAuth = RequestAuth(),
        method: String = "GET",
        range: String? = null,
        ifRange: String? = null,
    ): HttpURLConnection =
        if (auth.cookie.isNullOrBlank()) {
            // 没有会话要保护，交给 JDK 自动跟随（少一次往返）
            rawConnection(url, auth, followRedirects = true, method = method, range = range, ifRange = ifRange)
        } else {
            followRedirectsKeepingCookie(url, auth, method, range, ifRange)
        }

    /**
     * 手动跟随重定向，**跨域时丢掉 Cookie**（v7-P2-2）。
     *
     * 为什么不能交给 JDK：`instanceFollowRedirects` 会复制用户设置的请求头，`Cookie` 也在其中。
     * 于是 `.a.com` 的会话会被 302 带到 `.b.com`（CDN 跳 CDN、或者被劫持的跳转）。
     * 只有自己跟随，才有机会在 host 变化时把 Cookie 摘掉。
     *
     * 代价：只在**确实带 Cookie**的请求上多一次往返，而且只有真的 302 才会多。
     * 手动添加的任务（无 Cookie）完全走老路径，行为不变。
     */
    private fun followRedirectsKeepingCookie(
        url: String,
        auth: RequestAuth,
        method: String = "GET",
        range: String? = null,
        ifRange: String? = null,
    ): HttpURLConnection {
        var current = url
        var currentCookie: String? = auth.cookie
        repeat(HttpRedirects.MAX_HOPS) {
            val connection = rawConnection(
                current,
                auth.copy(cookie = currentCookie),
                followRedirects = false,
                method = method,
                range = range,
                ifRange = ifRange,
            )
            val code = try {
                connection.responseCode
            } catch (e: Exception) {
                runCatching { connection.disconnect() }
                throw e
            }
            if (code !in HttpRedirects.CODES) return connection
            val location = connection.getHeaderField("Location")
            runCatching { connection.disconnect() }
            if (location.isNullOrBlank()) throw IOException("HTTP $code 但没有 Location 头")
            val next = HttpRedirects.resolve(current, location)
            // host 变了就不再带会话：跨域重定向本来就不该继承 Cookie
            if (!HttpRedirects.sameHost(current, next)) currentCookie = null
            current = next
        }
        throw IOException("重定向次数超过 ${HttpRedirects.MAX_HOPS} 次：$url")
    }

    private fun rawConnection(
        url: String,
        auth: RequestAuth,
        followRedirects: Boolean,
        method: String = "GET",
        range: String? = null,
        ifRange: String? = null,
    ): HttpURLConnection {
        val proxy = currentProxy()
        val connection = if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()
        return (connection as HttpURLConnection).apply {
            connectTimeout = AppConstants.CONNECT_TIMEOUT_MS
            readTimeout = AppConstants.READ_TIMEOUT_MS
            instanceFollowRedirects = followRedirects
            // 方法 / Range 只有在未连接时才能设置，这里是最早的时机
            requestMethod = method
            range?.takeIf { it.isNotBlank() }?.let { setSafeHeader("Range", it) }
            // If-Range 只在同时有 Range 时才有意义；单独发会被服务端忽略（RFC 7233 §3.2）
            ifRange?.takeIf { it.isNotBlank() && !range.isNullOrBlank() }?.let { setSafeHeader("If-Range", it) }
            setSafeHeader("User-Agent", auth.userAgent ?: userAgentFor(auth.referer, auth.cookie))
            auth.referer?.takeIf { it.isNotBlank() }?.let { setSafeHeader("Referer", it) }
            auth.cookie?.takeIf { it.isNotBlank() }?.let { setSafeHeader("Cookie", it) }
            // 网盘 CDN 的跨域校验：UC 的 OSS 与蓝奏优享都要求 Origin 与 Referer 同源
            auth.origin?.takeIf { it.isNotBlank() }?.let { setSafeHeader("Origin", it) }
            auth.secChUa?.takeIf { it.isNotBlank() }?.let { setSafeHeader("Sec-Ch-Ua", it) }
        }
    }

    /**
     * 是否用浏览器 UA。
     *
     * 抓网页来的地址（带 referer）伪装成浏览器：源站常按 UA 做防盗链，自报下载器会被判非浏览器
     * （v7-P1-2）。手动添加的任务保留 `KtDownload/x.y.z` —— 服务端日志里能看出是哪个客户端。
     *
     * 网盘任务不靠这里推断：它们在 [RequestAuth.userAgent] 里带着平台专用串（115 客户端、
     * 迅雷 app、百度 netdisk），由 [top.mcly99.dl.engine.clouddrive.CloudDownloadHeaders] 决定。
     */
    private fun userAgentFor(referer: String?, cookie: String?): String =
        if (referer != null || cookie != null) AppConstants.CATCH_WEB_USER_AGENT else "KtDownload/$APP_VERSION"

    /** 包装输入流以应用全局限速（未设置限速时原样返回） */
    private fun limited(input: java.io.InputStream, isActive: () -> Boolean): java.io.InputStream =
        if (rateLimiter.enabled) ThrottledInputStream(input, rateLimiter, isActive) else input

    /**
     * 远端探测结果：总大小 + 内容指纹 + **是否支持 Range**。
     *
     * 三件事尽量在**同一次**探测里拿到：大小决定分片规划，指纹决定「规划里的这份是不是同一份」，
     * Range 支持决定走多连接还是单连接。分开探测不仅多一次往返，而且几次响应之间远端仍可能
     * 变化，拿到的组合自相矛盾。
     *
     * [rangeSupported] 为 null 表示这次探测没发过带 Range 的请求（只发了 HEAD），
     * 调用方仍需自行判断；非 null 时就是 `Range: bytes=0-0` 那一次的真实结论，
     * 不能再发一遍 —— 这正是「首包即探测」要省掉的那个重复 RTT。
     */
    private data class RemoteProbe(
        val size: Long,
        val fingerprint: ContentFingerprint,
        val rangeSupported: Boolean? = null,
    )

    /**
     * 探测远端：总大小与内容指纹。
     *
     * 先发 HEAD（正规做法、不耗流量）；夸克 / 部分 CDN 对 HEAD 直接 403/405，或者回 200 但
     * 没有 `Content-Length`（chunked），这时退化成「GET + `Range: bytes=0-0`」，
     * 从 `Content-Range: bytes 0-0/<总长>` 里读总长。两条都不行才返回 -1（大小未知）。
     *
     * 调用方已经给出大小（网盘列表里的 fsize）时，它是用户看到的值，不能让一个孤证的
     * HEAD `Content-Length` 单独推翻：网盘 CDN 常对 HEAD 回一张几 KB 的错误页，
     * 旧逻辑据此把 239 MB 的任务改成 6.83 KB，进度直接 100%、速度采样归零。
     * 只有 HEAD 与 Range 两个独立探测**一致**地给出另一个长度，才认为远端真的换了文件。
     * 老版本已落盘的错误任务（已下字节 > 记录的总长）走单独的修复分支，用 Range 结果纠正。
     */
    private suspend fun probeRemote(task: DownloadTask): RemoteProbe = withContext(Dispatchers.IO) {
        val knownSize = task.totalSize.takeIf { it > 0 }
        val head = headProbe(task.url, task.auth())

        if (knownSize == null) {
            if (head != null && head.size > 0) return@withContext head
            val ranged = rangeProbe(task.url, task.auth())
            return@withContext when {
                ranged == null -> head ?: RemoteProbe(-1L, ContentFingerprint.UNKNOWN)
                head == null -> ranged
                // HEAD 只给了指纹没给长度：长度用 Range 的，指纹谁有就用谁的
                else -> RemoteProbe(
                    ranged.size,
                    ranged.fingerprint.takeIf { it.isKnown } ?: head.fingerprint,
                    ranged.rangeSupported,
                )
            }
        }

        // 老版本把「6.83 KB 错误页长度」写进了任务文件，而磁盘上早已下了几 MB。
        // 这种自相矛盾的任务不能继续信任 HEAD（它还会说 6.83 KB），必须靠 Range 纠正。
        val onDisk = runCatching { task.savePath.toFile().length() }.getOrDefault(0L)
        val observedBytes = maxOf(task.downloadedSize, onDisk)
        if (observedBytes > knownSize) {
            val ranged = rangeProbe(task.url, task.auth())
            val remoteSize = ranged?.size?.takeIf { it > 0 } ?: head?.size?.takeIf { it > 0 } ?: -1L
            // 已经落盘的字节数也是证据：远端就算继续撒谎，总长也不可能小于它
            val repaired = maxOf(remoteSize, observedBytes).takeIf { it > 0 } ?: -1L
            AppLogger.warn(
                "DownloadEngine",
                "任务记录的 $knownSize 字节小于已落盘 $observedBytes 字节（HEAD=$remoteSize），" +
                    "修正总长为 $repaired: ${task.fileName}",
            )
            return@withContext RemoteProbe(
                repaired,
                ranged?.fingerprint?.takeIf { it.isKnown }
                    ?: head?.fingerprint?.takeIf { it.isKnown }
                    ?: ContentFingerprint.UNKNOWN,
                ranged?.rangeSupported,
            )
        }

        if (head != null && head.size > 0 && head.size == knownSize) return@withContext head
        val ranged = rangeProbe(task.url, task.auth())
        val headSize = head?.size?.takeIf { it > 0 }
        val rangedSize = ranged?.size?.takeIf { it > 0 }
        val headFingerprint = head?.fingerprint?.takeIf { it.isKnown } ?: ContentFingerprint.UNKNOWN
        val rangedFingerprint = ranged?.fingerprint?.takeIf { it.isKnown } ?: ContentFingerprint.UNKNOWN
        when {
            rangedSize == knownSize -> RemoteProbe(
                knownSize,
                rangedFingerprint.takeIf { it.isKnown } ?: headFingerprint,
                ranged.rangeSupported,
            )
            headSize != null && headSize == rangedSize -> {
                AppLogger.info(
                    "DownloadEngine",
                    "HEAD 与 Range 均报告远端长度为 $headSize（入队时为 $knownSize），按远端新长度下载: ${task.fileName}",
                )
                RemoteProbe(
                    headSize,
                    headFingerprint.takeIf { it.isKnown } ?: rangedFingerprint,
                    ranged.rangeSupported,
                )
            }
            else -> {
                if (headSize != null || rangedSize != null) {
                    AppLogger.info(
                        "DownloadEngine",
                        "探测长度 HEAD=$headSize / Range=$rangedSize 与入队长度 $knownSize 不一致，" +
                            "保留入队值，避免错误页覆盖真实大小: ${task.fileName}",
                    )
                }
                // 长度可疑时指纹也多半来自同一张错误页，不能拿它判「内容已变」；
                // 但 Range 支持与否是协议层结论，与内容真伪无关，可以放心沿用
                RemoteProbe(knownSize, ContentFingerprint.UNKNOWN, ranged?.rangeSupported)
            }
        }
    }

    /** HEAD 探测：拿到 `Content-Length` 和指纹，两者都没有时返回 null */
    private fun headProbe(urlString: String, auth: RequestAuth): RemoteProbe? {
        var connection: HttpURLConnection? = null
        return try {
            connection = newConnection(urlString, auth, method = "HEAD")
            connection.connect()
            val size = connection.contentLengthLong
            val fingerprint = fingerprintOf(connection)
            if (size <= 0 && !fingerprint.isKnown) null else RemoteProbe(size, fingerprint)
        } catch (e: Exception) {
            AppLogger.debug("DownloadEngine", "HEAD 探测失败: ${e.message}")
            null
        } finally {
            ignoreFailure("DownloadEngine", "断开连接") { connection?.disconnect() }
        }
    }

    /** `Range: bytes=0-0` 探测：优先读 `Content-Range` 的总长，其次 `Content-Length` */
    private fun rangeProbe(urlString: String, auth: RequestAuth): RemoteProbe? {
        var connection: HttpURLConnection? = null
        return try {
            connection = newConnection(urlString, auth, range = "bytes=0-0")
            connection.connect()
            val code = connection.responseCode
            val fingerprint = fingerprintOf(connection)
            val size = when {
                code == HttpURLConnection.HTTP_PARTIAL ->
                    connection.getHeaderField("Content-Range")
                        ?.substringAfterLast('/')
                        ?.trim()
                        ?.toLongOrNull()
                        ?: -1L
                code == HttpURLConnection.HTTP_OK -> connection.contentLengthLong
                else -> -1L
            }
            // 请求本身就是 `Range: bytes=0-0`：回 206 就说明支持分段，回 200 就是不支持。
            // 这个结论顺手带走，调用方不必再发一次同样的请求（「首包即探测」）。
            val rangeSupported = code == HttpURLConnection.HTTP_PARTIAL
            if (size <= 0 && !fingerprint.isKnown) null
            else RemoteProbe(size, fingerprint, rangeSupported)
        } catch (e: Exception) {
            AppLogger.debug("DownloadEngine", "Range 探测失败: ${e.message}")
            null
        } finally {
            ignoreFailure("DownloadEngine", "关闭输入流") { connection?.inputStream?.close() }
            ignoreFailure("DownloadEngine", "断开连接") { connection?.disconnect() }
        }
    }

    /** 从响应头取内容指纹；两个头都没有时返回 [ContentFingerprint.UNKNOWN] */
    private fun fingerprintOf(connection: HttpURLConnection): ContentFingerprint =
        ContentFingerprint.of(
            etag = runCatching { connection.getHeaderField("ETag") }.getOrNull(),
            lastModified = runCatching { connection.getHeaderField("Last-Modified") }.getOrNull(),
        )

    /**
     * 磁盘上是否已经有这个任务的数据。
     *
     * 只有「有半成品」时才需要验指纹：全新任务没有任何可以被污染的字节，
     * 服务器又经常对同一份内容给出不同的 ETag（多节点 CDN），无谓的比较只会把
     * 正常的下载判成「内容变了」。
     */
    private fun hasLocalBytes(task: DownloadTask): Boolean {
        val file = task.savePath.toFile()
        if (file.exists() && file.length() > 0) return true
        return partialDownloadFile(file).exists()
    }

    /**
     * 比对探测到的指纹与任务上记录的指纹，远端换了文件就丢掉本地半成品（P0-2）。
     *
     * 为什么要**丢弃**而不是「继续」：能确认内容已经变了，本地那半截就是另一份文件的字节，
     * 拼上去必错；为什么也不「原地覆盖」：长度可能不同，分片规划也得重来，删掉重下是最短路径。
     *
     * @return true = 已丢弃本地数据，本次必须从 0 开始
     */
    private fun reconcileFingerprint(
        taskId: String,
        task: DownloadTask,
        probed: ContentFingerprint,
    ): Boolean {
        val stored = task.fingerprint
        if (!probed.isKnown) {
            // 服务器这次没给任何校验头：没有可比的东西，维持改动前的行为（直接续传），
            // 但留一条日志 —— 事后出了坏文件，至少能看出这条路没有防护
            if (stored.isKnown) {
                AppLogger.debug("DownloadEngine", "远端本次未提供指纹，跳过内容校验: ${task.fileName}")
            }
            return false
        }
        if (!hasLocalBytes(task)) {
            // 全新任务：把指纹记下来供下次续传比对
            if (probed != stored) updateTask(taskId, fingerprint = probed)
            return false
        }
        if (!contentReplacedBy(stored, probed)) {
            if (probed != stored) updateTask(taskId, fingerprint = probed)
            return false
        }
        AppLogger.warn(
            "DownloadEngine",
            "远端内容已变化（旧 ${stored.describe()} / 新 ${probed.describe()}），" +
                "丢弃已下载数据重下: ${task.fileName}",
        )
        cleanupDownloadArtifacts(task.savePath.toFile())
        // force：downloadedSize 归零必须落盘，否则重启后 UI 还显示旧的进度
        updateTask(taskId, downloadedSize = 0L, fingerprint = probed, force = true)
        return true
    }

    private suspend fun supportsRangeRequests(urlString: String, auth: RequestAuth = RequestAuth()): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = newConnection(urlString, auth, range = "bytes=0-0")
            connection.connect()
            connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        } catch (e: Exception) {
            false
        } finally {
            ignoreFailure("DownloadEngine", "断开连接") { connection?.disconnect() }
            ignoreFailure("DownloadEngine", "关闭输入流") { connection?.inputStream?.close() }
        }
    }

    /**
     * 单线程下载（不支持 Range 或小文件），支持按本地已有长度续传。
     *
     * P2-6：使用 [FileChannel] + 1MB 缓冲，替代 8KB 的 RandomAccessFile 写入。
     *
     * P0-2：续传时带上 `If-Range`（内容指纹）。服务端判定实体已变会回 200 + 整份内容，
     * 这里就自然退化成「从头写」——同一份代码路径既处理「服务器不支持 Range」，
     * 也处理「远端换了文件」，不需要另写一套重启逻辑。
     *
     * @param restart 调用方已确认内容变了并丢弃了旧数据：即使磁盘上还有字节也不许再续
     */
    private suspend fun downloadSingleThread(
        taskId: String,
        task: DownloadTask,
        restart: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val file = task.savePath.toFile()
        // 线程数改到 1 / 服务端不再支持 Range 时，旧的多线程中间产物已经用不上，
        // 留着既占空间又会误导下次续传，直接清掉。
        if (segmentPlanFile(file).exists() || partialDownloadFile(file).exists()) {
            cleanupDownloadArtifacts(file)
        }
        var startByte = when {
            restart -> 0L
            file.exists() -> file.length()
            else -> 0L
        }
        val totalSize = task.totalSize
        val sources = task.downloadSources()

        // 单线程路径同样轮转多源（P2-E3）：旧实现只认 task.url，多账号场景下主链接
        // 过期（403/410）就整任务失败，而分片路径早已换源重试。这里只在「还没写任何字节」
        // 的阶段换源，落盘之后仍固定在当前源，避免半截文件拼错。
        val opened = openSingleSource(taskId, sources, startByte, task.fingerprint.ifRangeValue)
        var source = opened.third
        var connection = opened.first
        var code = opened.second

        // P2-04：openSingleSource 成功返回后，直到 FileChannel 打开、数据读完之间还有一串可能抛异常
        // 的步骤（restartFromZero 重连、fingerprintOf、FileChannel.open/truncate、磁盘满……）。
        // 旧实现只在读写阶段有 try/finally，早期异常时这条 HttpURLConnection 既不 disconnect
        // 也不从 taskConnections 移除，socket 会一直占着。这里统一由外层 finally 收口。
        try {
            /** 放弃续传：断开当前连接，从 0 重开一条不带 Range 的 */
            fun restartFromZero(reason: String) {
                AppLogger.info("DownloadEngine", "$reason，从头重下: ${task.fileName}")
                ignoreFailure("DownloadEngine", "断开连接") { connection.disconnect() }
                startByte = 0L
                connection = newConnection(source.first, source.second)
                taskConnections[taskId] = connection
                connection.connect()
                code = connection.responseCode
            }

            if (code == HTTP_RANGE_NOT_SATISFIABLE) {
                // 416：本地文件已达/超过远端长度。恰好相等说明上次已下完但状态没来得及落库。
                if (totalSize > 0 && startByte == totalSize) return@withContext
                // 否则（远端变小、本地文件异常）放弃续传，从头重下
                restartFromZero("本地偏移超出远端长度（416）")
            }

            // 206 但起点不是我们要的位置：少数服务器会忽略 If-Range、或把越界区间截断后照旧回 206。
            // 把从 0 开始的字节写进 startByte 处，就是一份静默错位的文件，只能从头重下。
            if (code == HttpURLConnection.HTTP_PARTIAL && startByte > 0) {
                val reportedStart = contentRangeStart(connection)
                if (reportedStart != null && reportedStart != startByte) {
                    restartFromZero("响应起点 $reportedStart 与请求的 $startByte 不符")
                }
            }

            if (code == HttpURLConnection.HTTP_OK && startByte > 0) {
                // 200 + 我们带了 Range，只有两种可能：服务器不支持续传，或 If-Range 判定内容已变。
                // 两种都从头写；连接本身可以直接用，它给的就是完整内容。
                AppLogger.info(
                    "DownloadEngine",
                    "服务端返回 200（不支持续传或内容已变），从头写: ${task.fileName}",
                )
                startByte = 0L
            } else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IllegalStateException("HTTP $code")
            }

            // 这次响应头里的指纹最权威（探测与这次请求之间还可能变），顺手更新记录的指纹。
            // 只在真的变化时才写，避免每个任务都多一次落盘。
            val responseFingerprint = fingerprintOf(connection)
            if (responseFingerprint.isKnown && responseFingerprint != task.fingerprint) {
                updateTask(taskId, fingerprint = responseFingerprint)
            }

            // 响应头里就有远程长度：200 时是总长，206 时是本段长度（总长 - startByte）。
            // HEAD 探测失败（夸克等 CDN 对 HEAD 回 403）时，这里是唯一能拿到真实大小的机会；
            // 不补的话任务会一直显示「139 MB / 0 B」，进度恒 0%、速度恒 0。
            val remoteLength = runCatching { connection.contentLengthLong }.getOrDefault(-1L)
            val resolvedTotal = when {
                totalSize > 0 -> totalSize
                code == HttpURLConnection.HTTP_PARTIAL && remoteLength > 0 -> startByte + remoteLength
                code == HttpURLConnection.HTTP_OK && remoteLength > 0 -> remoteLength
                else -> 0L
            }
            if (resolvedTotal > 0 && resolvedTotal != task.totalSize) {
                updateTask(taskId, totalSize = resolvedTotal)
            }

            val channel = FileChannel.open(
                file.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            taskFiles.computeIfAbsent(taskId) { CopyOnWriteArrayList() }.add(channel)
            // 丢弃上次中断后残留的尾部，确保从 offset 开始的文件长度与 offset 一致
            channel.truncate(startByte)
            channel.position(startByte)

            // tracker 只在本次下载内使用，直接构造即可（v4-P2-1：原先进全局注册表但从不读回）
            val tracker = DownloadProgressTracker(resolvedTotal, 1)
            tracker.update(0, startByte)

            // 限速器的阻塞等待无法感知协程取消，这里把当前 Job 传进去，取消后立即退出
            val selfJob = currentCoroutineContext()[Job]
            var written = startByte
            var lastEmit = 0L
            try {
                limited(connection.inputStream) { selfJob?.isActive != false }.use { input ->
                    val buffer = ByteBuffer.allocate(AppConstants.SINGLE_BUFFER_SIZE)
                    val array = buffer.array()
                    while (true) {
                        // 显式用 currentCoroutineContext()：类内有同名 scope 成员
                        currentCoroutineContext().ensureActive()
                        val n = input.read(array, 0, array.size)
                        if (n < 0) break
                        buffer.clear()
                        buffer.limit(n)
                        while (buffer.hasRemaining()) channel.write(buffer)
                        written += n
                        tracker.update(0, written)
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= AppConstants.IO_POLL_MS) {
                            // 用 tracker 的结果而不是裸 written：进度与速度必须基于同一口径。
                            // 否则服务器多给了字节时会出现「3.39 MB / 6.83 KB + 0 B/s」。
                            emitProgress(taskId, tracker.totalDone(), tracker.sampleSpeed(now))
                            lastEmit = now
                        }
                    }
                }
                // 已知总大小时校验落盘长度，避免服务器提前断开却把任务标记为完成
                if (resolvedTotal > 0 && written != resolvedTotal) {
                    throw IllegalStateException("下载不完整：已写入 $written / $resolvedTotal 字节")
                }
                emitProgress(taskId, tracker.totalDone(), 0L)
            } finally {
                closeQuietly(channel, "DownloadEngine")
                taskFiles[taskId]?.remove(channel)
            }
        } finally {
            releaseSingleConnection(taskId, connection)
        }
    }

    /**
     * P2-04：单线程路径的连接收口。建连成功后到数据读完之间的**任何**退出路径
     * （416 提前返回、restartFromZero 重连失败、FileChannel.open/truncate 抛异常、
     * 磁盘满、主动取消、正常完成）都必须经过这里：先从登记表移除，再断开 socket。
     *
     * 抽成 internal 方法是为了让「异常路径真的断连」可以用假连接做确定性单测，
     * 而不是依赖真实 HttpServer 的 socket 状态。
     */
    internal fun releaseSingleConnection(taskId: String, connection: HttpURLConnection) {
        taskConnections.remove(taskId)
        ignoreFailure("DownloadEngine", "断开连接") { connection.disconnect() }
    }

    /**
     * 单线程路径的建连 + 状态判定，失败时按 [sources] 顺序换源（P2-E3）。
     *
     * 只有「连接异常」与「不可用的 HTTP 状态」会换源；416 属于合法响应（本地偏移已到位），
     * 交给调用方判断。全部源都不可用时抛最后一次的错误，错误文案与旧实现一致（`HTTP 403`）。
     *
     * @return `(connection, responseCode, 实际生效的源)`
     */
    private fun openSingleSource(
        taskId: String,
        sources: List<Pair<String, RequestAuth>>,
        startByte: Long,
        ifRange: String?,
    ): Triple<HttpURLConnection, Int, Pair<String, RequestAuth>> {
        var lastError: Exception? = null
        sources.forEachIndexed { index, source ->
            val conn = newConnection(
                source.first,
                source.second,
                range = if (startByte > 0) "bytes=$startByte-" else null,
                ifRange = if (startByte > 0) ifRange else null,
            )
            taskConnections[taskId] = conn
            try {
                conn.connect()
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_OK ||
                    code == HttpURLConnection.HTTP_PARTIAL ||
                    code == HTTP_RANGE_NOT_SATISFIABLE
                ) {
                    return Triple(conn, code, source)
                }
                lastError = IllegalStateException("HTTP $code")
                if (index < sources.lastIndex) {
                    AppLogger.warn(
                        "DownloadEngine",
                        "源返回 HTTP $code，换下一个源: ${redactUrl(source.first)}",
                    )
                }
            } catch (e: Exception) {
                lastError = e
                if (index < sources.lastIndex) {
                    AppLogger.warn(
                        "DownloadEngine",
                        "源连接失败，换下一个源: ${redactUrl(source.first)}（${e.message}）",
                    )
                }
            }
            ignoreFailure("DownloadEngine", "断开连接") { conn.disconnect() }
            taskConnections.remove(taskId)
        }
        throw lastError ?: IllegalStateException("没有可用的下载源")
    }

    /**
     * 多线程下载：**一个 `.part` 文件 + 一张可切分的段表**。
     *
     * 与旧模型的三处差别：
     * - 不再有 N 个 `.partN` 与最后的合并步骤：各连接按绝对偏移写同一个文件，完成即改名。
     *   省掉一整遍读写、峰值磁盘占用减半、也不会卡在 `MERGING` 99%。
     * - 段不再是「一次切死」的静态区间：先下完的连接会去切剩余最多的那段（[SegmentTable]），
     *   总耗时从 `max(各片)` 靠近 `avg(各片)`。
     * - 连接明显慢于基准时会被踢掉重连（[SlowConnectionDetector]），而不是拖着整任务走完。
     *
     * 断点续传靠 `.segments` 快照：记录「哪些区间还没下完、各自写到哪」。
     * 快照不可信（长度或指纹变了、内容损坏、老版本没有）就整份丢弃重下。
     */
    private suspend fun downloadMultiThread(
        taskId: String,
        task: DownloadTask,
        totalSize: Long,
    ) = withContext(Dispatchers.IO) {
        val target = task.savePath.toFile()
        val partial = partialDownloadFile(target)
        val store = SegmentPlanStore(segmentPlanFile(target))
        val sources = task.downloadSources()
        var fingerprint = task.fingerprint
        var ifRange = fingerprint.ifRangeValue
        var attempt = 0

        while (true) {
            // 偏移写之前必须先按最终长度预留并尽量标稀疏：NTFS 上「扩展后往远处写」
            // 会同步零填充中间的空洞（2 GiB 偏移写 1 MiB 实测 4.3 s）。
            SparseFileSupport.prepare(partial, totalSize)
            val table = openSegmentTable(task, totalSize, fingerprint, store)
            if (table.resumed) {
                AppLogger.info(
                    "DownloadEngine",
                    "续传：剩余 ${table.pendingCount} 段 / 已完成 ${table.totalDone()}B: ${target.name}",
                )
                if (table.totalDone() > 0) updateTask(taskId, downloadedSize = table.totalDone())
            } else {
                cleanupLegacyChunkArtifacts(target)
                AppLogger.info("DownloadEngine", "新建段表 ${table.pendingCount} 段: ${target.name}")
            }

            val channel = FileChannel.open(partial.toPath(), StandardOpenOption.WRITE)
            taskFiles.computeIfAbsent(taskId) { CopyOnWriteArrayList() }.add(channel)
            try {
                if (!table.isComplete()) {
                    downloadSegments(
                        taskId, sources, channel, table, store, totalSize, ifRange, task.threads,
                    )
                }
                // 段表空了但字节数不够，说明有段被无声跳过：宁可失败，也不交付半份文件
                if (table.totalDone() != totalSize) {
                    throw IllegalStateException(
                        "分段下载结束后字节数不符: ${table.totalDone()} != $totalSize",
                    )
                }
                // 改名之后文件就是成品了，必须先把脏页刷出去
                runCatching { channel.force(false) }
                closeQuietly(channel, "DownloadEngine")
                taskFiles[taskId]?.remove(channel)

                // 状态机语义保留：UI 仍会看到「校验/合并中」，只是这一步现在只剩一次 rename
                updateTask(taskId, event = DownloadEvent.MERGE)
                if (getTask(taskId)?.status != DownloadStatus.MERGING) return@withContext
                finishPartial(partial, target, totalSize)
                store.delete()
                emitProgress(taskId, totalSize, 0L)
                return@withContext
            } catch (e: ContentReplacedException) {
                cleanupDownloadArtifacts(target)
                if (++attempt > MAX_CONTENT_RESTARTS) {
                    throw IllegalStateException(
                        "远端内容在下载过程中反复变化，已放弃：请稍后重试，或确认文件不再被覆盖上传",
                        e,
                    )
                }
                // 旧的 If-Range 指向的是上一份内容，不清掉的话重试时每段都会再拿到 200
                val reprobed = probeRemote(task)
                if (reprobed.size > 0 && reprobed.size != totalSize) {
                    throw IllegalStateException(
                        "远端内容在下载过程中被替换，长度已从 $totalSize 变成 ${reprobed.size}，请重试",
                        e,
                    )
                }
                fingerprint = reprobed.fingerprint.takeIf { it.isKnown } ?: ContentFingerprint.UNKNOWN
                ifRange = fingerprint.ifRangeValue
                // force：下载量归零与指纹更换都必须落盘
                updateTask(taskId, downloadedSize = 0L, fingerprint = fingerprint, force = true)
                AppLogger.warn(
                    "DownloadEngine",
                    "远端内容在下载过程中变化，丢弃段表重下（第 ${attempt + 1} 次）: ${target.name}",
                )
            } finally {
                closeQuietly(channel, "DownloadEngine")
                taskFiles[taskId]?.remove(channel)
            }
        }
    }

    /**
     * 取段表：快照可信就接着下，否则新建。
     *
     * 「可信」= 版本、总长、内容指纹、区间自洽性全部通过（见 [SegmentPlanStore.load]）。
     * 线程数不参与判断：段数不再由线程数决定，用户中途改线程数不该让已有进度作废。
     */
    private fun openSegmentTable(
        task: DownloadTask,
        totalSize: Long,
        fingerprint: ContentFingerprint,
        store: SegmentPlanStore,
    ): SegmentTable {
        val restored = store.load(totalSize, fingerprint)
        if (restored != null) {
            return SegmentTable(totalSize, fingerprint, restored, resumed = true)
        }
        val count = initialSegmentCount(totalSize, task.threads, segmentBytes)
        return SegmentTable(totalSize, fingerprint, buildSegments(totalSize, count), resumed = false)
    }

    /**
     * 拉起 worker 池跑完段表。
     *
     * 两层结构：
     * - **外层按主机上限重规划**（E 项）：服务器只允许 2 条连接时，多余的 worker 被拒后
     *   自行退出、段回池，外层用学到的上限重新拉起更少的 worker，任务继续跑而不是整任务报错。
     * - **worker 数 = min(线程数, 学到的主机上限, 剩余段数)**：线程数是用户意图，
     *   主机上限是服务端事实，段数是工作量。
     */
    private suspend fun downloadSegments(
        taskId: String,
        sources: List<Pair<String, RequestAuth>>,
        channel: FileChannel,
        table: SegmentTable,
        store: SegmentPlanStore,
        totalSize: Long,
        ifRange: String?,
        threads: Int,
    ) {
        val host = hostKeyOf(sources.first().first)
        // 整条链路一个采样器：各连接各算各的会得到 N 个互不相干的瞬时值
        val linkSampler = ByteRateSampler()
        val bestConnectionRate = AtomicLong(0L)
        val activeWorkers = AtomicInteger(0)
        // 「正在收数据」的连接数：学主机上限时要的是这个，而不是「已启动的 worker 数」。
        // 被拒的连接也算在 activeWorkers 里但不在这个计数里，用它当观测值会永远学不出上限。
        val receivingConnections = AtomicInteger(0)
        val lastPersistAt = AtomicLong(0L)

        suspend fun persistSnapshot() {
            // 先把脏页刷出去，再写快照：快照里的游标必须对应「确实在盘上」的字节。
            // 顺序反过来，掉电后续传会从空洞中间接着写，交付一份长度正确、内容错位的文件
            // （追加写模型有 part.length() 兜底，单文件偏移写没有这个信号，只能靠这道顺序）。
            // force 与 write 共用单写线程：同一句柄上两种落盘节奏串行，不会互相插队。
            withContext(diskDispatcher) { runCatching { channel.force(false) } }
            store.save(table)
        }

        suspend fun persistIfDue() {
            val now = System.currentTimeMillis()
            val previous = lastPersistAt.get()
            if (now - previous < SegmentTuning.PERSIST_INTERVAL_MS) return
            // CAS 抢到的那个 worker 独占这次刷盘，其余继续下
            if (!lastPersistAt.compareAndSet(previous, now)) return
            persistSnapshot()
        }

        suspend fun runWorkerPool(workerCount: Int) {
            coroutineScope {
                repeat(workerCount) { workerId ->
                    launch {
                        activeWorkers.incrementAndGet()
                        try {
                            var lastEnd = -1L
                            while (true) {
                                val segment = table.claim(workerId, lastEnd) ?: break
                                try {
                                    downloadSegment(
                                        taskId, workerId, sources, segment, channel, table,
                                        totalSize, ifRange, linkSampler, bestConnectionRate,
                                        activeWorkers, receivingConnections,
                                    )
                                    table.release(segment)
                                } catch (e: SlowConnectionShed) {
                                    // 段回池：游标保留，下一个（或重建后的）连接从这里接着写
                                    table.noteShed(segment)
                                    table.abandon(segment)
                                    AppLogger.debug(
                                        "DownloadEngine",
                                        "连接明显偏慢，重建：段 ${segment.start}-${segment.cursor - 1} " +
                                            "（第 ${segment.shedCount} 次）",
                                    )
                                    continue
                                } catch (e: ConcurrencyRejected) {
                                    // 不是失败：主机限制并发。段回池、本连接退出，等外层按新上限重规划
                                    table.abandon(segment)
                                    val learned = hostCaps.observeRejection(e.host, e.observedCap)
                                    AppLogger.info(
                                        "DownloadEngine",
                                        "主机限并发（HTTP ${e.status}，当时 ${e.receivingConnections} 条在收数据）" +
                                            (learned?.let { "，上限记为 $it" } ?: "，待更多观测") +
                                            ": $host",
                                    )
                                    break
                                }
                                lastEnd = segment.end
                                persistIfDue()
                            }
                        } finally {
                            activeWorkers.decrementAndGet()
                        }
                    }
                }
            }
        }

        try {
            var replans = 0
            while (!table.isComplete()) {
                val cap = hostCaps.capFor(host)
                val planned = minOf(threads, cap ?: threads, table.pendingCount).coerceAtLeast(1)
                if (replans > 0) {
                    AppLogger.info(
                        "DownloadEngine",
                        "按主机上限重规划：$planned 条连接（学到 ${cap ?: "未知"}，剩余 ${table.pendingCount} 段）: $host",
                    )
                }
                val before = table.totalDone()
                runWorkerPool(planned)
                if (table.isComplete()) break

                // 两种情况都值得再来一轮：
                // 1. 有字节进展（上限可能刚被学到，剩下的段交给更少的连接）；
                // 2. 一个字节都没动，但上限刚变小 —— 并发请求一起到达时可能全部被拒，
                //    这一轮本来就下不了任何东西，缩容后的下一轮才是真正能跑的那轮。
                val learnedNow = hostCaps.capFor(host)
                val capShrank = learnedNow != null && learnedNow < planned
                if (table.totalDone() > before || capShrank) {
                    if (++replans > SegmentTuning.MAX_CONNECTION_REPLANS) {
                        throw IllegalStateException(
                            "连接上限重规划次数过多，仍有 ${table.pendingCount} 段未完成",
                        )
                    }
                    continue
                }
                throw IllegalStateException(
                    "所有连接都被拒绝或失败，仍有 ${table.pendingCount} 段未完成" +
                        (cap?.let { "（主机上限 $it）" } ?: ""),
                )
            }
        } finally {
            // 暂停/取消/失败时也必须落一次：旧实现靠 persistChunkOffset 保证「每次暂停不丢进度」，
            // 只靠周期性快照的话，暂停前那几百毫秒就白下了 —— 续传要从更早的位置重来。
            // 这里先 force 再写，保证快照里的游标不会领先于真正落盘的字节。
            //
            // **必须 NonCancellable**：暂停/取消时本协程已经是取消状态，
            // `withContext(diskDispatcher)` 一进去就会抛 CancellationException，
            // 快照直接丢掉 —— 现象就是「暂停后继续，进度从 0 重来」。
            withContext(NonCancellable + diskDispatcher) {
                runCatching { channel.force(false) }
                store.save(table)
            }
        }
    }

    /**
     * 下一段活：多源轮转 + 每个源多轮重试（H 项）。
     *
     * 旧实现每个源只试一次：单源任务遇一次瞬时 500 / 连接重置，整任务就红，用户得手动点「继续」。
     * 现在按「首次立即、之后指数退避」重试 [SegmentTuning.RETRY_ROUNDS_PER_SOURCE] 轮，
     * 且**不重置已落盘字节** —— 游标跟着段走，重试天然从断点继续。
     *
     * 三条不重试的红线：
     * - `ContentReplacedException`：远端换了内容，重试只会读到另一份文件的字节；
     * - `CancellationException` / `SlowConnectionShed` / `ConcurrencyRejected`：控制流，不是失败；
     * - 4xx（除 408/429）：直链过期、404、416 这类重试再多次也一样，试完所有源就抛。
     */
    private suspend fun downloadSegment(
        taskId: String,
        workerId: Int,
        sources: List<Pair<String, RequestAuth>>,
        segment: DownloadSegment,
        channel: FileChannel,
        table: SegmentTable,
        totalSize: Long,
        ifRange: String?,
        linkSampler: ByteRateSampler,
        bestConnectionRate: AtomicLong,
        activeWorkers: AtomicInteger,
        receivingConnections: AtomicInteger,
    ) {
        if (segment.finished) return
        // 按段起点取模，稳定地把不同段分散到不同源；单源任务恒为 0，行为与改动前一致。
        // 除数必须用**实际分段粒度**而不是固定常量：测试会把粒度注入成 32 KiB，
        // 用 4 MiB 当除数的话所有段的 seed 都是 0，备选源一条都不会被用到。
        val seed = ((segment.start / segmentBytes.coerceAtLeast(1L)) % sources.size).toInt()
        // 跨源长度校验只在真的多源时做（单源任务保持原行为）
        val expectedTotal = if (sources.size > 1) totalSize else -1L
        val totalAttempts = sources.size * SegmentTuning.RETRY_ROUNDS_PER_SOURCE
        var failures = 0
        var lastError: Exception? = null

        while (failures < totalAttempts) {
            currentCoroutineContext().ensureActive()
            val (url, auth) = sources[(seed + failures) % sources.size]
            try {
                transferSegment(
                    taskId, workerId, url, auth, segment, channel, table,
                    expectedTotal, ifRange, linkSampler, bestConnectionRate,
                    activeWorkers, receivingConnections,
                )
                if (!segment.finished) {
                    throw IllegalStateException(
                        "段 ${segment.start}-${segment.end} 未写完: cursor=${segment.cursor}",
                    )
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ContentReplacedException) {
                // 内容变了，换源重试没有意义：立刻终止整轮
                throw e
            } catch (e: SlowConnectionShed) {
                throw e
            } catch (e: ConcurrencyRejected) {
                throw e
            } catch (e: Exception) {
                lastError = e
                failures++
                val retryable = isRetryableSegmentFailure(e)
                // 不可重试的错误至少把所有源都试一遍（可能是这个源过期了，另一个还好），
                // 全试完就立刻抛，不要按轮次空耗
                if (!retryable && failures >= sources.size) throw e
                if (failures >= totalAttempts) break
                val waitMs = if (retryable) retryBackoffMs(failures) else 0L
                AppLogger.warn(
                    "DownloadEngine",
                    "段 ${segment.start} 第 $failures 次失败" +
                        (if (waitMs > 0) "，${waitMs} ms 后重试" else "，立即换源重试") +
                        ": ${e.message}",
                )
                if (waitMs > 0) delay(waitMs)
            }
        }
        throw lastError ?: IllegalStateException("段 ${segment.start} 没有可用下载源")
    }

    /**
     * 跑一条连接：从段的游标处请求到段的终点，按绝对偏移写入同一个文件。
     *
     * 段可能在传输途中被别的 worker 切走（`segment.end` 被调小），因此每读一块都要
     * 重新判断还能写多少；超出新终点的字节直接丢弃，由拿到新段的那条连接重新请求。
     */
    private suspend fun transferSegment(
        taskId: String,
        workerId: Int,
        url: String,
        auth: RequestAuth,
        segment: DownloadSegment,
        channel: FileChannel,
        table: SegmentTable,
        /** 跨源长度校验用的总长；<= 0 表示不校验 */
        expectedTotal: Long,
        ifRange: String?,
        linkSampler: ByteRateSampler,
        bestConnectionRate: AtomicLong,
        activeWorkers: AtomicInteger,
        receivingConnections: AtomicInteger,
    ) {
        if (segment.finished) return
        val connStart = segment.cursor
        val requestEnd = segment.end
        // If-Range 只在**本段已有字节**时发：新段没有旧字节可被污染，
        // 而「内容没变但每次 ETag 都不同」的怪服务器上，多发一个头只会让新任务无谓失败。
        val chunkIfRange = ifRange.takeIf { connStart > segment.start }
        // Range 必须在建连时给：带 Cookie 的连接在跟随重定向阶段就已经 established
        val connection = newConnection(
            url, auth, range = "bytes=$connStart-$requestEnd", ifRange = chunkIfRange,
        )
        val key = "$taskId#$workerId"
        taskConnections[key] = connection

        val detector = SlowConnectionDetector()
        val startedAt = System.currentTimeMillis()
        var bytesThisConnection = 0L
        var writeNanos = 0L
        var lastShedCheck = startedAt
        var lastEmit = 0L
        /** 是否已经计入「正在收数据」的连接数：只有真正进到读循环才计，退出时按此归还 */
        var receiving = false

        try {
            connection.connect()
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                runCatching { connection.disconnect() }
                // 带着 If-Range 却拿到整份内容：服务端明确判定实体已经变了
                if (chunkIfRange != null) {
                    throw ContentReplacedException(
                        "段 ${segment.start} 收到 HTTP 200：If-Range 判定远端内容已变化",
                    )
                }
                // 没带 If-Range 却回 200：服务端忽略了 Range。重试不会变好，直接交给上层换源/失败
                throw SegmentHttpException(code, "段请求失败: HTTP $code（服务端忽略了 Range）")
            }
            if (code != HttpURLConnection.HTTP_PARTIAL) {
                runCatching { connection.disconnect() }
                // 403 / 429 / 503 有两种完全不同的成因：直链过期（重试无用）与并发超限
                // （少开几条就好）。靠「当时还有别的连接在工作吗」来分：有，就先当作并发，
                // 让外层按学到的上限重规划；只有它一条还失败，那才是鉴权/过期，记下来
                // 以后不再把这个 host 的失败当并发信号（E 项）。
                if (isConcurrencyStatus(code)) {
                    val host = hostKeyOf(url)
                    val active = activeWorkers.get()
                    val receivingNow = receivingConnections.get()
                    if (active >= 2) {
                        // 观测值取「正在收数据的连接数」：它们证明了自己能工作。
                        // 一条都没收到首字节就被拒（并发请求同时到达）时退一步记「尝试数 − 1」，
                        // 保证每轮至少缩一条连接，能收敛。
                        val observed = if (receivingNow > 0) receivingNow else (active - 1).coerceAtLeast(1)
                        throw ConcurrencyRejected(host, observed, receivingNow, code)
                    }
                    hostCaps.noteSingleConnectionFailure(host)
                }
                throw SegmentHttpException(code, "段请求失败: HTTP $code")
            }
            // 206 只说明「接受了 Range」，不说明「从我要的位置开始」：
            // 忽略 If-Range / 截断区间的服务器照样回 206，把那批字节写进去就是静默错位
            val reportedStart = contentRangeStart(connection)
            if (reportedStart != null && reportedStart != connStart) {
                runCatching { connection.disconnect() }
                throw IllegalStateException("段起始 $reportedStart 与请求的 $connStart 不符")
            }
            if (expectedTotal > 0L) {
                val reportedTotal = connection.getHeaderField("Content-Range")
                    ?.substringAfterLast('/')
                    ?.toLongOrNull()
                if (reportedTotal != null && reportedTotal > 0L && reportedTotal != expectedTotal) {
                    runCatching { connection.disconnect() }
                    throw IllegalStateException(
                        "备选直链长度与任务不一致: $reportedTotal != $expectedTotal",
                    )
                }
            }

            // 从这里起算「正在收数据」：主机上限学习拿这个数当「能同时工作的条数」的证据
            receivingConnections.incrementAndGet()
            receiving = true

            val selfJob = currentCoroutineContext()[Job]
            limited(connection.inputStream) { selfJob?.isActive != false }.use { input ->
                val buffer = ByteBuffer.allocate(AppConstants.CHUNK_BUFFER_SIZE)
                val array = buffer.array()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    // 段被切走 / 已写完：立刻收手，剩下的交给新段重新请求
                    if (segment.finished) break
                    val n = input.read(array, 0, array.size)
                    if (n < 0) break

                    val capacity = segment.end - segment.cursor + 1
                    if (capacity <= 0L) break
                    val toWrite = minOf(n.toLong(), capacity).toInt()

                    val writeStartedAt = System.nanoTime()
                    buffer.clear()
                    buffer.limit(toWrite)
                    var written = 0
                    while (buffer.hasRemaining()) {
                        // 绝对定位写：多个 worker 写同一个文件互不干扰，也不动 channel 的 position。
                        // 统一提交到单写线程（F 项）：盘慢时排队只发生在写这一侧，
                        // 别的连接还能继续读网络，不会「一条在 flush、八条全断速」。
                        val at = segment.cursor
                        val w = withContext(diskDispatcher) { channel.write(buffer, at) }
                        if (w <= 0) throw IllegalStateException("写入磁盘失败")
                        segment.cursor += w
                        table.addBytes(w.toLong())
                        written += w
                    }
                    val elapsedWrite = System.nanoTime() - writeStartedAt
                    writeNanos += elapsedWrite
                    // 全局写耗时：慢连接判据靠它区分「网络慢」与「盘慢」（后者不该剔除）
                    writeStats.record(elapsedWrite)
                    bytesThisConnection += written

                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= AppConstants.IO_POLL_MS) {
                        emitProgress(taskId, table.totalDone(), linkSampler.sample(table.totalDone(), now))
                        lastEmit = now
                    }
                    if (now - lastShedCheck >= SegmentTuning.SHED_CHECK_INTERVAL_MS) {
                        lastShedCheck = now
                        val elapsed = (now - startedAt).coerceAtLeast(1L)
                        val rate = bytesThisConnection * 1000L / elapsed
                        // 只把「跑够宽限期」的连接算进单连接上限，否则首包延迟会压出一个假基准
                        if (elapsed >= 2_000L && rate > 0L) {
                            bestConnectionRate.accumulateAndGet(rate) { a, b -> maxOf(a, b) }
                        }
                        val sample = SlowConnectionDetector.Sample(
                            elapsedMs = elapsed,
                            bytesReceived = bytesThisConnection,
                            remainingBytes = segment.remaining,
                            linkBytesPerSec = linkSampler.sample(table.totalDone(), now),
                            activeConnections = activeWorkers.get(),
                            bestConnectionBytesPerSec = bestConnectionRate.get(),
                            writeWaitRatio = writeStats.ratioFor(writeNanos, elapsed),
                            shedCount = segment.shedCount,
                            diskRecentlyStalled = writeStats.stalledRecently(now),
                        )
                        if (detector.shouldShed(sample)) throw SlowConnectionShed()
                    }
                }
            }
        } finally {
            if (receiving) receivingConnections.decrementAndGet()
            taskConnections.remove(key)
            ignoreFailure("DownloadEngine", "断开连接") { connection.disconnect() }
        }
    }

    /** 收尾：校验长度后把 `.part` 改名成目标文件（没有合并步骤） */
    private suspend fun finishPartial(partial: File, target: File, totalSize: Long) {
        val actual = partial.length()
        if (actual != totalSize) {
            partial.delete()
            throw IllegalStateException("下载结束但文件大小不符: $actual != $totalSize")
        }
        try {
            Files.move(
                partial.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // 部分网络盘 / 跨卷挂载不支持原子移动，退回普通替换
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        cleanupLegacyChunkArtifacts(target)
    }

    // ------------------------------------------------------------------ 状态与进度

    private suspend fun emitProgress(taskId: String, downloaded: Long, speed: Long) {
        val prev = lastSpeed.put(taskId, speed)
        val task = getTask(taskId)
        if (prev == speed && task?.downloadedSize == downloaded) return
        updateTask(taskId, downloadedSize = downloaded, speed = speed)
    }

    /**
     * 唯一的状态/进度更新入口。
     *
     * @param event 非空时通过状态机计算新状态；进度更新传 null
     */
    private fun updateTask(
        taskId: String,
        totalSize: Long? = null,
        downloadedSize: Long? = null,
        speed: Long? = null,
        event: DownloadEvent? = null,
        errorMessage: String? = null,
        segmentDone: Int? = null,
        segmentTotal: Int? = null,
        /** 探测到的远端内容指纹；非 null 时整体覆盖任务上记录的指纹（P0-2） */
        fingerprint: ContentFingerprint? = null,
        /** 只改错误文案（例如「需要重新抓取」）时也要立刻推给 UI */
        force: Boolean = false,
    ) {
        var forcePublish = force || event != null || totalSize != null || fingerprint != null
        synchronized(stateLock) {
            val task = taskMap[taskId] ?: return
            if (segmentTotal != null && segmentTotal != task.segmentTotal) forcePublish = true
            val newStatus = event?.let { DownloadTaskStateMachine.nextStatus(task.status, it) } ?: task.status
            val updated = task.copy(
                totalSize = totalSize ?: task.totalSize,
                downloadedSize = downloadedSize ?: task.downloadedSize,
                speed = speed ?: task.speed,
                status = newStatus,
                segmentDone = segmentDone ?: task.segmentDone,
                segmentTotal = segmentTotal ?: task.segmentTotal,
                // 整份覆盖而不是逐字段合并：新的响应没给 ETag 时，旧的 ETag 已经不能代表
                // 当前实体了，留着只会在下一次续传时拿它去比对，得出错误结论
                etag = if (fingerprint != null) fingerprint.etag else task.etag,
                lastModified = if (fingerprint != null) fingerprint.lastModified else task.lastModified,
                errorMessage = when {
                    newStatus == DownloadStatus.FAILED -> errorMessage ?: task.errorMessage
                    // HLS 降级完成时用 errorMessage 携带警告；普通完成则清空旧错误
                    newStatus == DownloadStatus.COMPLETED -> errorMessage
                    event != null -> null
                    else -> errorMessage ?: task.errorMessage
                },
                completeTime = when {
                    newStatus == DownloadStatus.COMPLETED -> java.time.LocalDateTime.now()
                    event != null -> null
                    else -> task.completeTime
                },
            )
            taskMap[taskId] = updated
            publishLocked(force = forcePublish)
        }
        // 进度类更新（只有大小/速度变化）不触发写盘，只在状态或文件信息变化时持久化
        if (forcePublish) requestPersist()
    }

    /** 必须在 [stateLock] 内调用；非强制发布时隔 200ms 合并一次 */
    private fun publishLocked(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublishedAt.get() < AppConstants.PROGRESS_PUBLISH_INTERVAL_MS) return
        lastPublishedAt.set(now)
        _taskState.value = taskMap.values.sortedWith(
            compareBy<DownloadTask> { it.createTime }.thenBy { it.id }
        )
    }

    /**
     * 清理任务对应的所有临时文件。
     *
     * 包含单文件的 `.part`、段表快照 `.segments`、旧模型（≤1.0.4）的 `.partN` /
     * `.partN.offset` / `.chunks.plan` / `.merging`，以及 HLS 合成的临时输出。
     * 旧模型的残片必须一并清：升级前暂停的任务会在磁盘上留下它们，
     * 不清的话「删任务后还留着 xxx.part3.offset」的老现象会复活（v6-P1-2）。
     */
    private fun cleanupParts(target: File) {
        cleanupDownloadArtifacts(target)
        File(target.parentFile, "${target.name}.tmp").delete()
    }

    // ------------------------------------------------------------------ 工具

    private fun initializeDefaultDownloadPath(): Path {
        val os = System.getProperty("os.name").lowercase()
        return if (os.contains("win")) {
            val dDrive = Paths.get("D:\\download")
            if (dDrive.toFile().exists()) dDrive else Paths.get(System.getProperty("user.home"), "Downloads")
        } else {
            Paths.get(System.getProperty("user.home"), "Downloads")
        }
    }

    /**
     * 关闭引擎：取消所有任务与协程作用域。
     *
     * ★ 顺序（P1-05）：先断开连接让阻塞中的 read() 退出，再**等待所有收尾清理协程跑完**，
     * 最后才 cancel。1.0.3 直接 `scope.cancel()`，而清理协程也挂在 `scope` 上，
     * 结果「取消任务 / 移除并删除文件后立刻关应用」时 `.partN` / `.offset` / `{name}.hls`
     * 残留、`deleteFile=true` 的承诺落空。清理协程现已迁到独立的 [cleanupScope]。
     */
    override fun shutdown() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        stoppingJobs.clear()
        taskConnections.values.forEach { ignoreFailure("DownloadEngine", "断开连接") { it.disconnect() } }
        taskConnections.clear()
        taskProcesses.values.forEach { ignoreFailure("DownloadEngine", "结束进程") { it.destroyForcibly() } }
        taskProcesses.clear()
        taskFiles.values.flatten().forEach { closeQuietly(it, "DownloadEngine") }
        taskFiles.clear()
        // 等收尾清理协程跑完：它们会 join 已取消的下载协程再删残片。带超时上限，
        // 避免某个卡在 IO 的清理协程把退出流程无限期堵住；超时后由 cleanupScope.cancel() 兜底。
        runBlocking {
            withTimeoutOrNull(SHUTDOWN_CLEANUP_TIMEOUT_MS) {
                pendingCleanups.toList().joinAll()
            }
            // 节流写盘协程可能已进入不可中断的阻塞 IO：先 cancel 并 join，
            // 再同步落盘，避免两次写盘交错（AtomicPropsFile 兜底文件不损坏，join 保证顺序）。
            withTimeoutOrNull(SHUTDOWN_CLEANUP_TIMEOUT_MS) {
                persistJob?.cancelAndJoin()
            }
        }
        // 退出前把任务写盘（节流协程已 join，这里同步落盘是最后一次）
        synchronized(stateLock) {
            taskMap.entries.forEach { (id, task) ->
                if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.MERGING) {
                    taskMap[id] = task.copy(status = DownloadStatus.PAUSED, speed = 0L)
                }
            }
            publishLocked(force = true)
        }
        if (taskPersistenceEnabled) TaskStore.save(taskMap.values.toList())
        scope.cancel()
        cleanupScope.cancel()
        AppLogger.info("DownloadEngine", "下载引擎已关闭")
    }
}

/**
 * 任务结束时磁盘上一个字节都没有时的报错（P1-8）。
 *
 * 触发面比想象中宽：带时效签名的直链过期后，服务端可能回 200 + 空 body 而不是 403；
 * HEAD 探测失败导致总大小未知时，整条链路上没有任何地方会校验「到底写出了多少」。
 * 以前这类任务会被标成 COMPLETED、进度 100%，用户双击打开是个空文件。
 */
internal const val EMPTY_DOWNLOAD_MESSAGE =
    "没有下载到任何数据（服务器返回空内容或写入失败）：请检查链接是否有效，或重新解析后重试。"

/**
 * [DownloadEngine.shutdown] 等待收尾清理协程的上限（P1-05）。
 * 正常清理（关句柄 + 删几个分片文件）在毫秒级；设 5s 是为了不让卡在不可中断 IO 的
 * 极少数情况把整个退出流程堵死。超时后清理作用域会被 cancel，残片留到下次启动处理。
 */
private const val SHUTDOWN_CLEANUP_TIMEOUT_MS = 5_000L

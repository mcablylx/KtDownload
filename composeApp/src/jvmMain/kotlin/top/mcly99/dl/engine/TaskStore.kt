package top.mcly99.dl.engine

import top.mcly99.dl.model.DownloadStatus
import top.mcly99.dl.model.DownloadTask
import top.mcly99.dl.model.TaskType
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import top.mcly99.dl.utils.AtomicPropsFile
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDateTime
import java.util.Properties

/**
 * 下载任务的本地持久化。
 *
 * - 文件位置:`~/.mclydownload/tasks.properties`
 * - 只保存恢复下载所必需的最小字段;
 * - 写入采用「临时文件 + 原子替换」,避免崩溃时留下半截文件;
 * - 读取失败的条目会被跳过并记录日志,不会影响其它任务。
 *
 * 注意:`java.util.Properties#store` 按 ISO-8859-1 转义输出,中文文件名
 * 读回后仍旧正确(以 `\uXXXX` 形式存储),无需额外处理。
 */
object TaskStore {

    private val storePath = AppConstants.APP_DATA_DIR.resolve("tasks.properties")

    private const val KEY_COUNT = "task.count"

    /** 一次最多持久化的任务数,防止异常场景下文件无限膨胀 */
    private const val MAX_PERSISTED = 500

    /** 目录权限只收紧一次（Windows 不支持 POSIX 权限，抛异常即跳过） */
    private var dirHardened = false

    /**
     * 保存任务列表。
     *
     * 只保存 `QUEUED` / `DOWNLOADING` / `MERGING` / `PAUSED` / `FAILED` 的任务;
     * 已完成与已取消的任务不恢复(文件已存在或被用户丢弃)。
     */
    fun save(tasks: Collection<DownloadTask>) {
        try {
            writeTasksTo(storePath, tasks)
            hardenPermissions()
        } catch (e: Exception) {
            AppLogger.warn("TaskStore", "保存任务失败: ${e.message}", e)
        }
    }

    /** 测试注入点：把任务写到指定路径；唯一 tmp / 互斥 / 回退由 [AtomicPropsFile] 保证。 */
    internal fun writeTasksTo(target: Path, tasks: Collection<DownloadTask>) {
        AtomicPropsFile.write(target, encode(tasks), "KtDownload download tasks")
    }

    /**
     * 收紧任务文件的权限（v8-P0-1 的纵深防御）。
     *
     * 目录 `0700`、文件 `0600`：即使将来又有人往里写敏感字段，同机其他用户也读不到。
     * Windows 不支持 POSIX 权限，`runCatching` 直接跳过。
     */
    private fun hardenPermissions() {
        if (!dirHardened) {
            dirHardened = true
            runCatching {
                Files.setPosixFilePermissions(storePath.parent, PosixFilePermissions.fromString("rwx------"))
            }
        }
        // 每次 save 都用新文件替换：新文件继承的是 umask（常见 0644），必须重新收紧
        runCatching {
            Files.setPosixFilePermissions(storePath, PosixFilePermissions.fromString("rw-------"))
        }
    }

    /**
     * 把任务列表转成待落盘的 `Properties`。
     *
     * 与 [decode] 成对抽出，是为了让「存进去的字段能不能原样读回来」这件事
     * 可以在不碰磁盘、不污染用户目录的前提下被单测钉住（v8 把 Cookie 改为「只记需求、不记值」时尤其需要）。
     */
    internal fun encode(tasks: Collection<DownloadTask>): Properties {
        val persistable = tasks
            .filter { it.status in PERSISTABLE_STATUS }
            .sortedBy { it.createTime }
            .takeLast(MAX_PERSISTED)
        val props = Properties()
        props.setProperty(KEY_COUNT, persistable.size.toString())
        persistable.forEachIndexed { index, task ->
            val p = "task.$index."
            props.setProperty("${p}id", task.id)
            props.setProperty("${p}url", task.url)
            props.setProperty("${p}fileName", task.fileName)
            props.setProperty("${p}savePath", task.savePath.toString())
            props.setProperty("${p}totalSize", task.totalSize.toString())
            props.setProperty("${p}downloadedSize", task.downloadedSize.toString())
            props.setProperty("${p}threads", task.threads.toString())
            props.setProperty("${p}type", task.type.name)
            props.setProperty("${p}segmentDone", task.segmentDone.toString())
            props.setProperty("${p}segmentTotal", task.segmentTotal.toString())
            props.setProperty("${p}status", task.status.name)
            props.setProperty("${p}createTime", task.createTime.toString())
            task.errorMessage?.let { props.setProperty("${p}errorMessage", it) }
            // 新增的 key 对老配置文件是「读不到 → 用默认值」，天然向后兼容
            task.referer?.let { props.setProperty("${p}referer", it) }
            // UA / Origin / Sec-Ch-Ua 不是凭证，落盘没有安全问题；不存的话，
            // 139 / 123 / 光鸭 / 蓝奏这类「不带 Cookie」的网盘任务重启后会丢掉平台专用 UA，
            // 续传时被 CDN 判成网页端。它们只在内存里跟着 Cookie 一起丢（带 Cookie 的任务本来就不恢复）。
            task.userAgent?.let { props.setProperty("${p}userAgent", it) }
            task.origin?.let { props.setProperty("${p}origin", it) }
            task.secChUa?.let { props.setProperty("${p}secChUa", it) }
            // 内容指纹（P0-2）：不落盘的话，重启后恢复的任务在续传时没有任何依据判断
            // 远端是否换了文件 —— 而那正是「静默拼出一个坏文件」最容易发生的时刻（隔了一夜）。
            // 它只是响应的校验头，不是凭证，落盘没有安全问题。
            task.etag?.takeIf { it.isNotBlank() }?.let { props.setProperty("${p}etag", it) }
            task.lastModified?.takeIf { it.isNotBlank() }?.let { props.setProperty("${p}lastModified", it) }
            // 会话 Cookie **不落盘**（v8-P0-1）：
            // 它是会话凭证，明文写进 ~/.mclydownload/tasks.properties 等于把浏览器 Cookie
            // 复制一份到磁盘（POSIX 上默认 0644，同机其他用户可读）。而带签名的地址
            // （`wsTime` 约 5 分钟过期）本来就不支持跨重启续传，存 Cookie 也救不回来。
            // 只留一个「这个任务需要 Cookie」的标记：恢复时明确失败，而不是发注定 403 的请求。
            if (task.requiresCookie || !task.cookie.isNullOrBlank()) {
                props.setProperty("${p}cookieRequired", "1")
            }
            // C2：备选直链本身不落盘（含各账号的 Cookie），只留一个标记，
            // 让恢复时明确失败并提示重新解析，而不是拿过期直链继续拼分片。
            if (task.altSources.isNotEmpty()) {
                props.setProperty("${p}multiSource", "1")
            }
        }
        return props
    }

    /**
     * 读取任务列表。
     *
     * 恢复规则:
     * - 需要会话 Cookie 的任务 → `FAILED`（Cookie 不落盘，没有凭证继续下载只会 403）；
     * - 原来处于 `DOWNLOADING` / `MERGING` 的任务 → `PAUSED`，避免启动时静默占用带宽；
     * - HLS 的 `QUEUED` → `PAUSED`，避免启动后立刻拉取大量分片。
     *
     * 用户点击继续或开启"启动时自动开始"后可恢复下载（需要 Cookie 的除外，会提示重新抓取）。
     */
    fun load(): List<DownloadTask> = readTasksFrom(storePath)

    /** 测试注入点：从指定路径读取；「读时写」与写共用同一把可重入锁，不会自锁死。 */
    internal fun readTasksFrom(target: Path): List<DownloadTask> {
        val file = target.toFile()
        if (!file.exists()) return emptyList()
        return try {
            val props = AtomicPropsFile.read(target)
            val restored = decode(props)
            // v8-P0-1：旧版本曾把会话 Cookie 明文写盘。读到旧文件就立刻重写一次，
            // 把凭证从磁盘上擦掉（`writeTasksTo` 只写 `cookieRequired`，不再写 `cookie`）。
            if (hasPersistedCookie(props)) {
                writeTasksTo(target, restored)
                AppLogger.info("TaskStore", "已清除旧任务文件中的会话 Cookie（改为只记录 Cookie 需求）")
            }
            restored
        } catch (e: Exception) {
            AppLogger.warn("TaskStore", "读取任务失败，已忽略损坏的任务文件: ${e.message}", e)
            runCatching {
                file.copyTo(File(file.parentFile, "tasks.properties.bak"), overwrite = true)
            }.onFailure { AppLogger.warn("TaskStore", "备份损坏任务文件失败", it) }
            emptyList()
        }
    }

    /** 旧版本落过盘的任务会带 `task.N.cookie`；只用于启动时的一次性擦除 */
    internal fun hasPersistedCookie(props: Properties): Boolean =
        props.stringPropertyNames().any { it.endsWith(".cookie") }

    /** [encode] 的逆运算：单条损坏只跳过该条，不影响其它任务。 */
    internal fun decode(props: Properties): List<DownloadTask> {
        val count = props.getProperty(KEY_COUNT)?.toIntOrNull() ?: return emptyList()
        return (0 until count.coerceAtMost(MAX_PERSISTED)).mapNotNull { index ->
            runCatching { parseTask(props, index) }
                .onFailure { AppLogger.warn("TaskStore", "跳过损坏的任务条目 #$index", it) }
                .getOrNull()
        }
    }

    private fun parseTask(props: Properties, index: Int): DownloadTask? {
        val p = "task.$index."
        val url = props.getProperty("${p}url") ?: return null
        val savePath = props.getProperty("${p}savePath") ?: return null
        val id = props.getProperty("${p}id") ?: return null
        val statusName = props.getProperty("${p}status") ?: DownloadStatus.PAUSED.name
        // 兼容 1.1.0 及更早版本保存的 WAITING；新版本使用 QUEUED
        val stored = if (statusName == "WAITING") {
            DownloadStatus.QUEUED
        } else {
            runCatching { DownloadStatus.valueOf(statusName) }.getOrDefault(DownloadStatus.PAUSED)
        }
        val type = props.getProperty("${p}type")
            ?.let { runCatching { TaskType.valueOf(it) }.getOrNull() }
            ?: TaskType.Http
        // Cookie 不落盘（v8-P0-1）：老文件里的 `cookie` 一律丢弃。
        // 老文件只要出现过 `cookie`，就说明这个任务当初需要会话 —— 迁移时同样标成「需要重新抓取」，
        // 否则升级后点「继续」会得到一个没有凭证的 403。
        val requiresCookie = props.getProperty("${p}cookieRequired") == "1" ||
            props.getProperty("${p}cookie") != null
        // C2：多源任务的备选直链没落盘，恢复出来一律是单源（且主链多半已过期）
        val multiSourceRevoked = props.getProperty("${p}multiSource") == "1"
        // 重启后不自动续跑:正在下载/合并的恢复为暂停。
        // HLS 即使处于排队也恢复为暂停，避免启动后立刻重新拉取大量分片。
        // 需要 Cookie 的任务直接恢复为失败：没有凭证，继续下载只会得到一个 403。
        val status = when {
            requiresCookie || multiSourceRevoked -> DownloadStatus.FAILED
            stored == DownloadStatus.DOWNLOADING || stored == DownloadStatus.MERGING -> DownloadStatus.PAUSED
            type == TaskType.M3u8 && stored == DownloadStatus.QUEUED -> DownloadStatus.PAUSED
            else -> stored
        }
        return DownloadTask(
            id = id,
            url = url,
            fileName = props.getProperty("${p}fileName") ?: File(savePath).name,
            savePath = Paths.get(savePath),
            totalSize = props.getProperty("${p}totalSize")?.toLongOrNull() ?: 0L,
            downloadedSize = props.getProperty("${p}downloadedSize")?.toLongOrNull() ?: 0L,
            status = status,
            threads = props.getProperty("${p}threads")?.toIntOrNull() ?: 4,
            type = type,
            segmentDone = props.getProperty("${p}segmentDone")?.toIntOrNull() ?: 0,
            segmentTotal = props.getProperty("${p}segmentTotal")?.toIntOrNull() ?: 0,
            createTime = props.getProperty("${p}createTime")
                ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                ?: LocalDateTime.now(),
            errorMessage = when {
                requiresCookie -> COOKIE_NOT_PERSISTED_MESSAGE
                multiSourceRevoked -> MULTI_SOURCE_NOT_PERSISTED_MESSAGE
                else -> props.getProperty("${p}errorMessage")
            },
            // 老配置文件里没有这个 key：读回 null，按原行为（HLS 用播放列表地址）处理
            referer = props.getProperty("${p}referer"),
            userAgent = props.getProperty("${p}userAgent"),
            origin = props.getProperty("${p}origin"),
            secChUa = props.getProperty("${p}secChUa"),
            // 会话 Cookie 从不读回：旧版本写下的值也在这里被丢弃（v8-P0-1）
            cookie = null,
            requiresCookie = requiresCookie,
            etag = props.getProperty("${p}etag")?.takeIf { it.isNotBlank() },
            lastModified = props.getProperty("${p}lastModified")?.takeIf { it.isNotBlank() },
        )
    }

    /** 需要跨会话恢复的状态 */
    private val PERSISTABLE_STATUS = setOf(
        DownloadStatus.QUEUED,
        DownloadStatus.DOWNLOADING,
        DownloadStatus.MERGING,
        DownloadStatus.PAUSED,
        DownloadStatus.FAILED,
    )
}

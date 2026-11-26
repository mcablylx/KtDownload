package top.mcly99.dl.utils

import java.io.BufferedWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * 应用日志。
 *
 * P2-7：打包版 `console = false`，原先的 `println` / `printStackTrace` 会全部丢失。
 * 现在统一写入 `~/.mclydownload/logs/app-YYYY-MM-DD.log`，按天滚动；
 * 调试日志使用 [debug]（`FINE`），默认不输出，设置环境变量 `KTDL_DEBUG=1` 可开启。
 */
object AppLogger {

    private const val TAG = "KtDownload"

    private val logger: Logger = Logger.getLogger("top.mcly99.dl").apply {
        useParentHandlers = false
        level = if (System.getenv("KTDL_DEBUG") == "1") Level.ALL else Level.INFO
        addHandler(DailyFileHandler(AppConstants.LOG_DIR).apply { level = Level.ALL })
    }

    fun debug(tag: String, message: String) {
        logger.fine("[$tag] $message")
    }

    fun info(tag: String, message: String) {
        logger.info("[$tag] $message")
    }

    fun warn(tag: String, message: String, error: Throwable? = null) {
        logger.log(Level.WARNING, "[$tag] $message", error)
    }

    fun error(tag: String, message: String, error: Throwable? = null) {
        logger.log(Level.SEVERE, "[$tag] $message", error)
    }

    /** 打开日志目录；系统不支持或目录创建失败时返回 false */
    fun openLogDirectory(): Boolean = runCatching {
        Files.createDirectories(AppConstants.LOG_DIR)
        if (!java.awt.Desktop.isDesktopSupported()) return false
        val desktop = java.awt.Desktop.getDesktop()
        if (!desktop.isSupported(java.awt.Desktop.Action.OPEN)) return false
        desktop.open(AppConstants.LOG_DIR.toFile())
        true
    }.getOrElse {
        warn(TAG, "无法打开日志目录: ${it.message}", it)
        false
    }

    val logDirectory: Path get() = AppConstants.LOG_DIR
}

/**
 * 删除 [directory] 中超过 [retentionDays] 天的 `app-YYYY-MM-DD.log`。
 *
 * 只处理符合命名规则的文件，其他内容一律不动；目录不存在时直接返回。
 */
internal val APP_LOG_NAME = Regex("""app-\d{4}-\d{2}-\d{2}\.log""")

internal fun pruneOldLogs(
    directory: Path,
    today: LocalDate = LocalDate.now(),
    retentionDays: Long = AppConstants.LOG_RETENTION_DAYS,
) {
    if (!Files.isDirectory(directory)) return
    val cutoff = today.minusDays(retentionDays)
    runCatching {
        Files.list(directory).use { paths ->
            paths.filter { path ->
                val name = path.fileName.toString()
                if (!APP_LOG_NAME.matches(name)) return@filter false
                val date = runCatching {
                    LocalDate.parse(name.removePrefix("app-").removeSuffix(".log"))
                }.getOrNull()
                date != null && date.isBefore(cutoff)
            }.forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }
}

/**
 * 按天滚动的文件 Handler。
 *
 * 不依赖 `java.util.logging.FileHandler` 的 `%g` 编号滚动（那是按大小），
 * 而是在每次写入时检查日期：跨天就关闭旧文件、打开新文件。
 */
private class DailyFileHandler(private val directory: Path) : Handler() {

    private val lock = Any()
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private var currentDay: LocalDate? = null
    private var writer: BufferedWriter? = null

    override fun publish(record: LogRecord) {
        if (!isLoggable(record)) return
        synchronized(lock) {
            try {
                val target = writerFor(LocalDate.now())
                target.write(format(record))
                target.newLine()
                target.flush()
            } catch (e: Exception) {
                // 日志本身失败不能再抛出，否则会掩盖真正的业务异常
                System.err.println("[AppLogger] 写入日志失败: ${e.message}")
            }
        }
    }

    private fun writerFor(day: LocalDate): BufferedWriter {
        val existing = writer
        if (existing != null && day == currentDay) return existing
        closeWriter()
        Files.createDirectories(directory)
        // 首次写入或跨天时顺带清理过期日志，避免磁盘无限增长
        pruneOldLogs(directory, day)
        currentDay = day
        writer = Files.newBufferedWriter(
            directory.resolve("app-$day.log"),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.WRITE,
        )
        return writer!!
    }

    private fun format(record: LogRecord): String {
        val timestamp = java.time.Instant.ofEpochMilli(record.millis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(dateTimeFormatter)
        // P1-07：落盘前统一脱敏。日志会被诊断包原样收走，业务日志里常有带签名的完整 URL、
        // 异常堆栈里也可能带 token；在这里兜底能保证以后新增的调用点也不会漏。
        val message = SensitiveTextRedactor.redact(record.message.orEmpty())
        val base = "$timestamp ${record.level.name.padEnd(7)} $message"
        val error = record.thrown ?: return base
        val stack = StringWriter()
        error.printStackTrace(PrintWriter(stack))
        return buildString {
            append(base)
            append('\n')
            append(SensitiveTextRedactor.redact(stack.toString().trimEnd()))
        }
    }

    private fun closeWriter() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        currentDay = null
    }

    override fun flush() {
        synchronized(lock) {
            try {
                writer?.flush()
            } catch (_: Exception) {
            }
        }
    }

    override fun close() {
        synchronized(lock) { closeWriter() }
    }
}

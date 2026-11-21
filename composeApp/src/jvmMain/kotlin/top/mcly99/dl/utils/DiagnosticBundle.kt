package top.mcly99.dl.utils

import top.mcly99.dl.APP_VERSION
import top.mcly99.dl.model.AppSettings
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断包（GAP 2.8 的「完整诊断包」）。
 *
 * 排查线上问题的痛点从来不是「没有日志」，而是**收集环节**：用户要找日志目录、
 * 翻到对应日期、还要说明系统版本和设置，来回几轮就把问题拖没了。
 * 这里一键打成一个 zip：
 *
 * - `environment.txt`：OS / 架构 / JDK / 内存 / 时区 / 应用版本，不含任何凭证与环境变量；
 * - `settings.txt`：设置项脱敏快照（家目录替换成 `~`，代理只记地址不记任何凭据）；
 * - `logs/`：最近 [LOG_DAYS] 天的 `app-*.log`，以及 `jcef.log`（内嵌浏览器内核日志）；
 * - `crash/`：启动时归档的 `hs_err_pid*.log` / `replay_pid*.log`；
 * - `manifest.txt`：本次打包了哪些文件、明确声明「不含网盘凭证」。
 *
 * **绝不打包**：`cloud-credentials.*` / `cloud-accounts.properties` / 任务存储里的
 * Cookie（本来就不落盘）。诊断包是要发给别人看的，凭证进包等于泄露账号。
 */
object DiagnosticBundle {

    /** 只收最近几天的日志：30 天全打进去会让包变成几十 MB */
    const val LOG_DAYS = 7L

    private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** 诊断包文件名，例如 `kt-diagnostics-20261115-203412.zip` */
    fun defaultFileName(now: Instant = Instant.now()): String {
        val stamp = FILE_TIME.format(now.atZone(ZoneId.systemDefault()))
        return "kt-diagnostics-$stamp.zip"
    }

    /**
     * 生成诊断包。
     *
     * @param target 目标 zip 路径（父目录会被创建）
     * @param now 注入时间便于测试；同时用于挑选「最近 N 天」的日志
     * @return 实际写出的 zip 路径
     */
    fun export(
        target: Path,
        settings: AppSettings,
        accountCounts: Map<String, Int> = emptyMap(),
        appVersion: String = APP_VERSION,
        logDir: Path = AppConstants.LOG_DIR,
        dataDir: Path = AppConstants.APP_DATA_DIR,
        now: Instant = Instant.now(),
        environment: String = environmentSnapshot(appVersion, dataDir, logDir),
    ): Path {
        val parent = target.toAbsolutePath().parent
        if (parent != null) Files.createDirectories(parent)

        val recentLogs = collectRecentLogs(logDir, now)
        val crashLogs = collectCrashLogs(dataDir)

        val manifest = buildString {
            appendLine("KtDownload diagnostics bundle")
            appendLine("version=$appVersion")
            appendLine("created=${Instant.ofEpochMilli(now.toEpochMilli())}")
            appendLine("logDays=$LOG_DAYS")
            appendLine("accounts=${accountCounts.entries.joinToString { "${it.key}=${it.value}" }}")
            appendLine("files:")
            appendLine("  environment.txt")
            appendLine("  settings.txt")
            recentLogs.forEach { appendLine("  logs/${it.fileName}") }
            crashLogs.forEach { appendLine("  crash/${it.fileName}") }
            appendLine()
            appendLine("This bundle contains NO cloud-drive credentials, cookies or tokens.")
        }

        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            zip.put("manifest.txt", manifest)
            zip.put("environment.txt", environment)
            zip.put("settings.txt", settingsSnapshot(settings))
            for (log in recentLogs) zip.putFile(log, "logs/${log.fileName}")
            for (log in crashLogs) zip.putFile(log, "crash/${log.fileName}")
        }
        return target
    }

    /**
     * 环境快照：**只放静态的系统信息**。
     *
     * 刻意不 dump 环境变量：里面经常有 `*_TOKEN` / `*_PROXY` / CI 注入的密钥，
     * 而诊断包大概率会被贴到 issue 里。
     */
    fun environmentSnapshot(appVersion: String, dataDir: Path, logDir: Path): String = buildString {
        appendLine("app=$appVersion")
        appendLine("os=${System.getProperty("os.name").orEmpty()}")
        appendLine("osVersion=${System.getProperty("os.version").orEmpty()}")
        appendLine("osArch=${System.getProperty("os.arch").orEmpty()}")
        appendLine("javaVersion=${System.getProperty("java.version").orEmpty()}")
        appendLine("javaVendor=${System.getProperty("java.vendor").orEmpty()}")
        appendLine("jvm=${System.getProperty("java.vm.name").orEmpty()} ${System.getProperty("java.vm.version").orEmpty()}")
        appendLine("availableProcessors=${Runtime.getRuntime().availableProcessors()}")
        appendLine("maxMemoryMB=${Runtime.getRuntime().maxMemory() / (1024 * 1024)}")
        appendLine("locale=${java.util.Locale.getDefault()}")
        appendLine("timezone=${ZoneId.systemDefault()}")
        appendLine("fileEncoding=${System.getProperty("file.encoding").orEmpty()}")
        appendLine("dataDir=${redactHome(dataDir)}")
        appendLine("logDir=${redactHome(logDir)}")
        appendLine("workingDir=${redactHome(java.nio.file.Paths.get(System.getProperty("user.dir").orEmpty()))}")
        appendLine("crashArchive=$CRASH_DIR_NAME")
    }

    /**
     * 设置快照：只写诊断需要的字段，且家目录脱敏。
     *
     * 这里**故意不输出**代理完整 URL 之外的任何授权信息 —— 目前 AppSettings 里也不存
     * 代理口令，但按字段手写可以避免以后新增敏感字段时被 `toString()` 顺带带出去。
     */
    fun settingsSnapshot(settings: AppSettings): String = buildString {
        appendLine("downloadPath=${redactHome(java.nio.file.Paths.get(settings.downloadPath))}")
        appendLine("maxConcurrent=${settings.maxConcurrent}")
        appendLine("defaultThreads=${settings.defaultThreads}")
        appendLine("speedLimit=${settings.speedLimit}")
        appendLine("proxyMode=${settings.proxyMode}")
        appendLine("proxy=${settings.proxyHost}:${settings.proxyPort}")
        appendLine("http2Enabled=${settings.http2Enabled}")
        appendLine("multiAccountDownload=${settings.multiAccountDownload}")
        appendLine("multiSourceDownload=${settings.multiSourceDownload}")
        appendLine("shutdownAfterComplete=${settings.shutdownAfterComplete}")
        appendLine("lanServerEnabled=${settings.lanServerEnabled}")
        appendLine("lanServerPort=${settings.lanServerPort}")
        appendLine("keepHlsSegments=${settings.keepHlsSegments}")
        appendLine("probeMediaSize=${settings.probeMediaSize}")
        appendLine("deepCatchEnabled=${settings.deepCatchEnabled}")
        appendLine("theme=${settings.theme}")
        appendLine("language=${settings.language}")
    }

    /** 崩溃日志归档目录：与主日志同级，诊断包会整个收走 */
    fun crashDir(dataDir: Path): Path = dataDir.resolve("logs").resolve(CRASH_DIR_NAME)

    // ------------------------------------------------------------ 内部

    private fun collectRecentLogs(logDir: Path, now: Instant): List<Path> {
        if (!Files.isDirectory(logDir)) return emptyList()
        val today = LocalDate.ofInstant(now, ZoneId.systemDefault())
        val cutoff = today.minusDays(LOG_DAYS)
        val dated = runCatching {
            Files.list(logDir).use { stream ->
                stream.filter { path ->
                    val name = path.fileName?.toString().orEmpty()
                    val date = parseLogDate(name)
                    date != null && !date.isBefore(cutoff)
                }.toList()
            }
        }.getOrDefault(emptyList()).sortedBy { it.fileName.toString() }

        // jcef.log 不是按天的，单独收；它记的是内嵌浏览器内核的启动/崩溃信息
        val jcef = logDir.resolve("jcef.log").takeIf { Files.isRegularFile(it) }
        return if (jcef == null) dated else dated + jcef
    }

    private fun collectCrashLogs(dataDir: Path): List<Path> {
        val dir = crashDir(dataDir)
        if (!Files.isDirectory(dir)) return emptyList()
        return runCatching {
            Files.list(dir).use { stream -> stream.filter { CrashLogs.isCrashLog(it) }.toList() }
        }.getOrDefault(emptyList()).sortedBy { it.fileName.toString() }
    }

    private fun parseLogDate(name: String): LocalDate? {
        if (!name.matches(Regex("""app-\d{4}-\d{2}-\d{2}\.log"""))) return null
        return runCatching {
            LocalDate.parse(name.removePrefix("app-").removeSuffix(".log"))
        }.getOrNull()
    }

    /** 家目录替换成 `~`：路径结构对排障有用，用户名没必要外泄 */
    internal fun redactHome(path: Path): String {
        val home = System.getProperty("user.home").orEmpty()
        val text = path.toString()
        if (home.isNotBlank() && text.startsWith(home)) return "~" + text.removePrefix(home)
        return text
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun ZipOutputStream.putFile(file: Path, name: String) {
        runCatching {
            putNextEntry(ZipEntry(name))
            Files.newInputStream(file).use { input -> input.copyTo(this) }
            closeEntry()
        }.onFailure {
            // 单个文件读失败（被占用 / 权限）不该让整个诊断包失败
            runCatching { closeEntry() }
            AppLogger.warn("DiagnosticBundle", "打包 $name 失败: ${it.message}")
        }
    }

    private const val CRASH_DIR_NAME = "crash"
}

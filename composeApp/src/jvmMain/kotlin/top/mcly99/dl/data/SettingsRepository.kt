package top.mcly99.dl.data

import top.mcly99.dl.model.AppSettings
import top.mcly99.dl.model.ProxyMode
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import java.io.File
import java.util.Properties

/**
 * 应用设置的唯一读写入口。
 *
 * 历史背景（已解决，保留以便理解为什么只允许有一个入口）：更早的版本里设置存在两套互不兼容的
 * 读写逻辑——`DownloadViewModel.saveSettings()` 只写 `downloadThreads` / `maxConcurrent`，
 * 而 `MockData` 里另有 `loadAppSettings()` / `saveAppSettings()` 读写完整键集，
 * 两者互相覆盖，导致「改了设置重启后丢失」。现在两者都已并入本对象，`MockData` 不再转发。
 *
 * 职责边界（v4-P2-5）：
 * - 本对象是**持久化**真源：`AppSettings` ↔ `config.properties`；
 * - `DownloadEngine` 的字段是**运行时**真源，由 `DownloadViewModel.loadSettings()` 从这里注入；
 * - `DownloadViewModel` 不再持有第三份副本，只做只读透出。
 *
 * P1：代理从布尔开关升级为三态，新增 HTTP/2 开关。[fromProperties] / [toProperties] 是
 * 不碰磁盘的纯函数，配置迁移规则与往返一致性由 `SettingsRepositoryTest` 锁定。
 */
object SettingsRepository {

    val configPath = AppConstants.APP_DATA_DIR.resolve("config.properties")

    private const val KEY_DOWNLOAD_PATH = "downloadPath"
    private const val KEY_MAX_CONCURRENT = "maxConcurrent"
    private const val KEY_DEFAULT_THREADS = "defaultThreads"
    private const val KEY_SPEED_LIMIT = "speedLimit"
    private const val KEY_AUTO_START = "autoStart"
    private const val KEY_CLIPBOARD_WATCH = "clipboardWatch"
    private const val KEY_SHOW_NOTIFICATIONS = "showNotifications"
    private const val KEY_MINIMIZE_TO_TRAY = "minimizeToTray"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_THEME = "theme"
    /** 旧版布尔开关：只写不再读，保留是为了降级到旧版本时代理行为不丢失 */
    private const val KEY_PROXY_ENABLED = "proxyEnabled"
    private const val KEY_PROXY_MODE = "proxyMode"
    private const val KEY_PROXY_HOST = "proxyHost"
    private const val KEY_PROXY_PORT = "proxyPort"
    private const val KEY_HTTP2_ENABLED = "http2Enabled"
    private const val KEY_MULTI_ACCOUNT = "multiAccountDownload"
    /** C2：必须依附于 C1，见 [AppSettings.multiSourceDownload] */
    private const val KEY_MULTI_SOURCE = "multiSourceDownload"
    /** 下载完成后关机：默认关，写进配置以便重启后保留用户选择 */
    private const val KEY_SHUTDOWN_AFTER_COMPLETE = "shutdownAfterComplete"
    private const val KEY_LAN_SERVER_ENABLED = "lanServerEnabled"
    private const val KEY_LAN_SERVER_PORT = "lanServerPort"
    private const val KEY_LAN_DISCOVERY_ENABLED = "lanDiscoveryEnabled"
    private const val KEY_KEEP_HLS_SEGMENTS = "keepHlsSegments"
    private const val KEY_PROBE_MEDIA_SIZE = "probeMediaSize"
    private const val KEY_DEEP_CATCH = "deepCatchEnabled"
    /** 自动检查更新：默认开，旧配置没有此键时按开启处理 */
    private const val KEY_AUTO_CHECK_UPDATE = "autoCheckUpdate"

    /**
     * 读取设置；文件不存在或损坏时返回默认值。
     */
    fun load(defaults: AppSettings = AppSettings()): AppSettings {
        val file = configPath.toFile()
        if (!file.exists()) return defaults
        return try {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            fromProperties(props, defaults)
        } catch (e: Exception) {
            // 配置损坏：备份原文件后重建，避免用户每次启动都失败且丢失现场
            AppLogger.warn("SettingsRepository", "读取配置失败，已备份并回退默认值: ${e.message}", e)
            runCatching {
                file.copyTo(File(file.parentFile, "config.properties.bak"), overwrite = true)
            }.onFailure { backupError ->
                AppLogger.warn("SettingsRepository", "备份损坏配置失败: ${backupError.message}", backupError)
            }
            defaults
        }
    }

    /**
     * 保存设置（原子写入，避免崩溃时留下半截文件）。
     */
    fun save(settings: AppSettings) {
        try {
            configPath.parent.toFile().mkdirs()
            val props = toProperties(settings)
            val tmp = File(configPath.parent.toFile(), "config.properties.tmp")
            tmp.outputStream().use { props.store(it, "MclyDownload Configuration") }
            java.nio.file.Files.move(
                tmp.toPath(),
                configPath,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: Exception) {
            AppLogger.warn("SettingsRepository", "保存配置失败: ${e.message}", e)
        }
    }

    /** 纯解析：从 properties 还原设置，不读磁盘，便于验证旧配置迁移。 */
    internal fun fromProperties(props: Properties, defaults: AppSettings = AppSettings()): AppSettings {
        // C2 依附 C1：即使配置文件被手工改成「C1 关 / C2 开」，这里也强制降级为关
        val c1 = props.boolOr(KEY_MULTI_ACCOUNT, defaults.multiAccountDownload)
        return AppSettings(
        downloadPath = props.prop(KEY_DOWNLOAD_PATH) ?: defaults.downloadPath,
        maxConcurrent = props.intOr(KEY_MAX_CONCURRENT, defaults.maxConcurrent),
        defaultThreads = props.intOr(KEY_DEFAULT_THREADS, defaults.defaultThreads),
        speedLimit = props.longOr(KEY_SPEED_LIMIT, defaults.speedLimit),
        autoStart = props.boolOr(KEY_AUTO_START, defaults.autoStart),
        clipboardWatch = props.boolOr(KEY_CLIPBOARD_WATCH, defaults.clipboardWatch),
        showNotifications = props.boolOr(KEY_SHOW_NOTIFICATIONS, defaults.showNotifications),
        minimizeToTray = props.boolOr(KEY_MINIMIZE_TO_TRAY, defaults.minimizeToTray),
        language = props.prop(KEY_LANGUAGE) ?: defaults.language,
        theme = props.prop(KEY_THEME) ?: defaults.theme,
        // P1：新键优先；旧配置只有 proxyEnabled=true/false，迁移成 MANUAL / DIRECT
        proxyMode = ProxyMode.fromStored(props.prop(KEY_PROXY_MODE))
            ?: when (props.prop(KEY_PROXY_ENABLED)?.toBooleanStrictOrNull()) {
                true -> ProxyMode.MANUAL
                false -> ProxyMode.DIRECT
                null -> defaults.proxyMode
            },
        proxyHost = props.prop(KEY_PROXY_HOST) ?: defaults.proxyHost,
        proxyPort = props.intOr(KEY_PROXY_PORT, defaults.proxyPort),
        http2Enabled = props.boolOr(KEY_HTTP2_ENABLED, defaults.http2Enabled),
        // P3：实验性 C1 开关，旧配置没这个键时保持默认关
        multiAccountDownload = c1,
        multiSourceDownload = c1 && props.boolOr(KEY_MULTI_SOURCE, defaults.multiSourceDownload),
        shutdownAfterComplete = props.boolOr(KEY_SHUTDOWN_AFTER_COMPLETE, defaults.shutdownAfterComplete),
        lanServerEnabled = props.boolOr(KEY_LAN_SERVER_ENABLED, defaults.lanServerEnabled),
        lanServerPort = props.intOr(KEY_LAN_SERVER_PORT, defaults.lanServerPort),
        lanDiscoveryEnabled = props.boolOr(KEY_LAN_DISCOVERY_ENABLED, defaults.lanDiscoveryEnabled),
        keepHlsSegments = props.boolOr(KEY_KEEP_HLS_SEGMENTS, defaults.keepHlsSegments),
        probeMediaSize = props.boolOr(KEY_PROBE_MEDIA_SIZE, defaults.probeMediaSize),
        deepCatchEnabled = props.boolOr(KEY_DEEP_CATCH, defaults.deepCatchEnabled),
        autoCheckUpdate = props.boolOr(KEY_AUTO_CHECK_UPDATE, defaults.autoCheckUpdate),
    )
    }

    /** 纯序列化：保存时额外写旧版 proxyEnabled 键，降级回旧版本仍能用手动代理。 */
    internal fun toProperties(settings: AppSettings): Properties = Properties().apply {
        setProperty(KEY_DOWNLOAD_PATH, settings.downloadPath)
        setProperty(KEY_MAX_CONCURRENT, settings.maxConcurrent.toString())
        setProperty(KEY_DEFAULT_THREADS, settings.defaultThreads.toString())
        setProperty(KEY_SPEED_LIMIT, settings.speedLimit.toString())
        setProperty(KEY_AUTO_START, settings.autoStart.toString())
        setProperty(KEY_CLIPBOARD_WATCH, settings.clipboardWatch.toString())
        setProperty(KEY_SHOW_NOTIFICATIONS, settings.showNotifications.toString())
        setProperty(KEY_MINIMIZE_TO_TRAY, settings.minimizeToTray.toString())
        setProperty(KEY_LANGUAGE, settings.language)
        setProperty(KEY_THEME, settings.theme)
        setProperty(KEY_PROXY_MODE, settings.proxyMode.name)
        setProperty(KEY_PROXY_ENABLED, (settings.proxyMode == ProxyMode.MANUAL).toString())
        setProperty(KEY_PROXY_HOST, settings.proxyHost)
        setProperty(KEY_PROXY_PORT, settings.proxyPort.toString())
        setProperty(KEY_HTTP2_ENABLED, settings.http2Enabled.toString())
        setProperty(KEY_MULTI_ACCOUNT, settings.multiAccountDownload.toString())
        setProperty(KEY_MULTI_SOURCE, (settings.multiAccountDownload && settings.multiSourceDownload).toString())
        setProperty(KEY_SHUTDOWN_AFTER_COMPLETE, settings.shutdownAfterComplete.toString())
        setProperty(KEY_LAN_SERVER_ENABLED, settings.lanServerEnabled.toString())
        setProperty(KEY_LAN_SERVER_PORT, settings.lanServerPort.toString())
        setProperty(KEY_LAN_DISCOVERY_ENABLED, settings.lanDiscoveryEnabled.toString())
        setProperty(KEY_KEEP_HLS_SEGMENTS, settings.keepHlsSegments.toString())
        setProperty(KEY_PROBE_MEDIA_SIZE, settings.probeMediaSize.toString())
        setProperty(KEY_DEEP_CATCH, settings.deepCatchEnabled.toString())
        setProperty(KEY_AUTO_CHECK_UPDATE, settings.autoCheckUpdate.toString())
    }

    private fun Properties.prop(key: String): String? = getProperty(key)

    private fun Properties.intOr(key: String, def: Int): Int =
        getProperty(key)?.toIntOrNull() ?: def

    private fun Properties.longOr(key: String, def: Long): Long =
        getProperty(key)?.toLongOrNull() ?: def

    private fun Properties.boolOr(key: String, def: Boolean): Boolean =
        getProperty(key)?.toBooleanStrictOrNull() ?: def
}

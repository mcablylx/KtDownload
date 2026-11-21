/*
 * Adapted from YunX-Desktop — https://github.com/tidain/YunX-Desktop
 * Upstream: main branch @ 00a030f7 (tag v1.3.4, 2026-11-13).
 * License: GNU Affero General Public License v3.0 (AGPL-3.0).
 * Original: desktop/src/main/kotlin/top/mcly99/dl/ui/jcef/JcefHolder.kt
 * Changes: ktdown uses AppConstants.APP_DATA_DIR instead of YunX AppContext,
 *          drops crash-quarantine/JcefNativesInstaller (natives jar ships on the classpath),
 *          never downloads the Chromium bundle implicitly (opt-in only), reports stage/progress.
 * Full license text: third-party/yunx-desktop/LICENSE.txt
 */
package top.mcly99.dl.engine.clouddrive.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.CefBuildInfo
import me.friwi.jcefmaven.EnumPlatform
import me.friwi.jcefmaven.EnumProgress
import me.friwi.jcefmaven.impl.step.fetch.PackageClasspathStreamer
import org.cef.CefApp
import org.cef.CefSettings
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import java.io.File

/**
 * 全局内嵌 Chromium（JCEF）持有者。
 *
 * 与 YunX-Desktop 一致：在后台线程初始化，不阻塞窗口显示；
 * CEF 消息循环由内部 Swing Timer 在 AWT EDT 上泵动，与调用线程无关。
 *
 * 与上游的关键差异：**内核随包分发，运行时零下载**。
 * `composeApp/build.gradle.kts` 会按构建机平台引入对应版本的
 * `jcef-natives-<platform>`（Windows / macOS amd64 / macOS arm64 / Linux），
 * jcefmaven 只需把它解包到 [bundleDir]；如果某个平台确实没有随包 natives，
 * 也绝不偷偷联网下载——必须由用户显式点「下载内核」（`allowDownload = true`）。
 * 这样既保住「软件内登录」的易用性，又不会出现「打开登录页就卡十几分钟」。
 */
object JcefHolder {
    private const val TAG = "CloudDrive-JCEF"

    /** jcefmaven 的安装/启动阶段，UI 据此显示可读进度 */
    enum class Stage { LOCATING, DOWNLOADING, EXTRACTING, INSTALLING, INITIALIZING }

    @Volatile
    private var cefApp: CefApp? = null

    private val lock = Any()
    private var initThread: Thread? = null

    /** Compose 可观察：CEF 初始化成功后为 true */
    var browserReady by mutableStateOf(false)
        private set

    /** 初始化失败原因；成功或未开始时为 null */
    var initFailure by mutableStateOf<String?>(null)
        private set

    /** 当前阶段；未开始或已就绪时为 null */
    var stage by mutableStateOf<Stage?>(null)
        private set

    /** 下载进度 0f..1f；无估算时为 -1f */
    var progress by mutableStateOf(-1f)
        private set

    fun app(): CefApp? = cefApp

    fun isAvailable(): Boolean = browserReady

    private fun bundleDir(): File = AppConstants.APP_DATA_DIR.resolve("jcef/bundle").toFile()

    /**
     * 本机是否已具备内核、初始化时不需要联网。
     *
     * 两种情况：之前已装好（`install.lock` 存在），或当前平台的 natives
     * 随包分发在 classpath 上（打包版，解包即用）。
     */
    fun hasLocalKernel(): Boolean = isKernelInstalled(bundleDir()) || nativesOnClasspath()

    /** 纯函数，便于单测：jcefmaven 安装完成后会在 installDir 写入 install.lock */
    internal fun isKernelInstalled(dir: File): Boolean = File(dir, "install.lock").isFile

    /**
     * 当前平台的 natives 是否在 classpath 上（即随包分发）。
     *
     * 直接问 jcefmaven 自己：它按 `/jcef-natives-<platform>-<tag>.tar.gz` 查找资源。
     * 比按 `os.name` 猜平台准——换了架构、或者某平台没打包时不会误判。
     */
    internal fun nativesOnClasspath(): Boolean = runCatching {
        val stream = PackageClasspathStreamer.streamNatives(
            CefBuildInfo.fromClasspath(),
            EnumPlatform.getCurrentPlatform(),
        ) ?: return@runCatching false
        stream.close()
        true
    }.getOrDefault(false)

    /**
     * 后台初始化。
     *
     * @param allowDownload 是否允许联网下载 Chromium 内核。默认 false：
     *   本机没有内核时直接返回，不产生任何网络请求，也不会卡住界面。
     */
    fun initInBackground(allowDownload: Boolean = false) {
        if (!allowDownload && !hasLocalKernel()) {
            AppLogger.info(TAG, "本机无内嵌浏览器内核，跳过自动初始化（需显式下载）")
            return
        }
        synchronized(lock) {
            if (browserReady || initThread?.isAlive == true) return
            initFailure = null
            stage = Stage.LOCATING
            progress = -1f
            initThread = Thread({ initBlocking(allowDownload) }, "JCEF-Init").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun initBlocking(allowDownload: Boolean) {
        var lastError: Throwable? = null
        for (attempt in 1..2) {
            try {
                if (attempt > 1) Thread.sleep(800L)
                val base = AppConstants.APP_DATA_DIR.resolve("jcef").toFile()
                base.mkdirs()
                val builder = CefAppBuilder()
                builder.setInstallDir(File(base, "bundle"))
                builder.setProgressHandler { event, fraction ->
                    stage = when (event) {
                        EnumProgress.LOCATING -> Stage.LOCATING
                        EnumProgress.DOWNLOADING -> Stage.DOWNLOADING
                        EnumProgress.EXTRACTING -> Stage.EXTRACTING
                        EnumProgress.INSTALL -> Stage.INSTALLING
                        else -> Stage.INITIALIZING
                    }
                    progress = fraction
                    if (event == EnumProgress.DOWNLOADING) {
                        AppLogger.info(
                            TAG,
                            "正在下载内核 ${if (fraction >= 0f) "${(fraction * 100).toInt()}%" else ""}",
                        )
                    }
                }
                val settings = builder.getCefSettings()
                settings.locale = "zh-CN"
                settings.windowless_rendering_enabled = false
                // CEF 要求 cache_path 必须是 root_cache_path 的子目录
                settings.root_cache_path = File(base, "profile").absolutePath
                settings.cache_path = File(base, "profile/cache").absolutePath
                settings.log_file = File(base, "jcef.log").absolutePath
                settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING
                cefApp = builder.build()
                browserReady = true
                initFailure = null
                stage = null
                progress = -1f
                AppLogger.info(
                    TAG,
                    "内嵌浏览器已就绪 (attempt $attempt, 本地内核=${hasLocalKernel()}, 允许下载=$allowDownload)",
                )
                return
            } catch (t: Throwable) {
                lastError = t
                AppLogger.warn(TAG, "内嵌浏览器初始化失败 (#$attempt): ${t.message}", t)
            }
        }
        stage = null
        progress = -1f
        initFailure = lastError?.message ?: "内嵌浏览器初始化失败"
    }

    /** 进程退出前尽力释放；由 main() 的 shutdown hook 调用 */
    fun disposeQuietly() {
        runCatching { cefApp?.dispose() }
            .onFailure { AppLogger.warn(TAG, "CefApp dispose 失败: ${it.message}", it) }
        cefApp = null
    }
}

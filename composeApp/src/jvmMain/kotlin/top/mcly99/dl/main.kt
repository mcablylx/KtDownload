package top.mcly99.dl

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ktdownload.composeapp.generated.resources.Res
import ktdownload.composeapp.generated.resources.img_log
import org.jetbrains.compose.resources.painterResource
import top.mcly99.dl.engine.clouddrive.browser.JcefHolder
import top.mcly99.dl.data.SettingsRepository
import top.mcly99.dl.theme.stringsFor
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import top.mcly99.dl.utils.CrashLogs
import top.mcly99.dl.utils.DiagnosticBundle
import top.mcly99.dl.utils.TrayHelper
import java.awt.Dimension
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JOptionPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 为 Swing/AWT 组件显式指定一款支持中文的字体。
 *
 * JBR 运行时里默认逻辑字体在部分环境（尤其是打包版的精简模块集）下没有中文字形回退，
 * 表现为托盘菜单、JOptionPane、JFileChooser 里的中文全部显示成方块。
 */
private fun initSwingFonts() {
    val candidates = listOf(
        "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC",
        "Noto Sans CJK SC", "Source Han Sans SC", "SimHei",
    )
    val available = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        .availableFontFamilyNames.toSet()
    val family = candidates.firstOrNull { it in available } ?: return
    val base = javax.swing.UIManager.getFont("Label.font") ?: java.awt.Font(family, java.awt.Font.PLAIN, 12)
    val font = java.awt.Font(family, base.style, base.size)
    javax.swing.UIManager.getDefaults().apply {
        keys().asSequence()
            .filterIsInstance<String>()
            .filter { it.endsWith(".font") }
            .forEach { put(it, font) }
    }
}

/** 退出流程已开始：此时未捕获异常只写日志，不再弹原生错误框（对齐上游 1.3.4） */
private val shuttingDown = AtomicBoolean(false)

/** 单实例锁的获取结果 */
private sealed interface InstanceLock {
    class Acquired(val channel: FileChannel) : InstanceLock
    object AlreadyRunning : InstanceLock
    object Unavailable : InstanceLock
}

/**
 * 用 `APP_DATA_DIR/app.lock` 的排他文件锁保证只运行一个实例。
 *
 * 锁随进程退出由操作系统释放；锁文件创建失败时放行启动，避免误伤可用性。
 */
private fun acquireInstanceLock(): InstanceLock = try {
    Files.createDirectories(AppConstants.APP_DATA_DIR)
    val channel = FileChannel.open(
        AppConstants.APP_DATA_DIR.resolve("app.lock"),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
    )
    val lock = try {
        channel.tryLock()
    } catch (_: OverlappingFileLockException) {
        null
    }
    if (lock == null) {
        channel.close()
        InstanceLock.AlreadyRunning
    } else {
        InstanceLock.Acquired(channel)
    }
} catch (e: Exception) {
    AppLogger.warn("Main", "单实例锁不可用，继续启动: ${e.message}", e)
    InstanceLock.Unavailable
}

fun main() {
    // JDK 的 HttpURLConnection 默认把 `Origin` 当成「受限头」**静默丢弃**（还有 Host /
    // Connection 等）。网盘 CDN（UC、蓝奏优享）按跨域来源校验下载请求，没有这个头会被
    // 限速或 403。这个开关只在 HttpURLConnection 类初始化时读一次，因此必须放在任何
    // 网络请求之前 —— main 的第一行是唯一安全的位置。
    System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
    AppLogger.info("Main", "应用启动 v$APP_VERSION")
    AppLogger.info(
        "Diagnostic",
        "diagnostic_session_start version=$APP_VERSION os=${System.getProperty("os.name")} " +
            "jdk=${System.getProperty("java.version")}",
    )
    harvestJvmCrashLogs()
    // 全局未捕获异常处理：因为 console=false，异常会静默崩溃，这里弹窗提示并写入日志
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        // 退出阶段的窗口/CEF 释放异常只是收尾噪音，弹框会闪一下且用户无从处理
        if (!shuttingDown.get()) try {
            // Error 的 message 常常只有一个内部类名（NoClassDefFoundError 就是
            // "java/net/http/HttpClient"），不带类名根本没法定位，堆栈只在日志里。
            val detail = buildString {
                append(e.javaClass.name)
                e.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            }
            JOptionPane.showMessageDialog(
                null,
                "应用程序发生错误:\n$detail",
                "KtDownload 错误",
                JOptionPane.ERROR_MESSAGE
            )
        } catch (_: Exception) {}
        AppLogger.error("Main", "未捕获异常", e)
    }

    try {
        // Windows 下必须先启用系统 L&F 再初始化字体，否则托盘/对话框会回落到
        // 不含中文字形的默认字体，中文显示为方块
        javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName())
        initSwingFonts()
    } catch (e: Exception) {
        AppLogger.warn("Main", "初始化系统外观失败", e)
    }

    val instanceLock = acquireInstanceLock()
    if (instanceLock is InstanceLock.AlreadyRunning) {
        try {
            JOptionPane.showMessageDialog(
                null,
                "KtDownload 已在运行，请检查系统托盘。",
                "KtDownload",
                JOptionPane.INFORMATION_MESSAGE,
            )
        } catch (_: Exception) {}
        return
    }

    // 内嵌 Chromium 有常驻子进程，退出前要显式释放 CefApp，否则缓存目录会被锁住
    Runtime.getRuntime().addShutdownHook(Thread({
        shuttingDown.set(true)
        // 日志里「有 session_start 没 session_end」= 上次被硬崩 / 强杀带走
        runCatching { AppLogger.info("Diagnostic", "diagnostic_session_end") }
        runCatching { JcefHolder.disposeQuietly() }
    }, "JCEF-Shutdown"))

    try {
        runUi()
    } finally {
        (instanceLock as? InstanceLock.Acquired)?.channel?.close()
    }
}

/**
 * 收集 JVM 硬崩溃留痕（对齐上游 v1.3.4 的排障项）。
 *
 * `hs_err_pid*.log` 是 JVM 崩溃时唯一留下的现场，默认落在进程工作目录。这里做两件事：
 * 1. 把关键行（信号、问题帧、命令行）写进主日志，下次启动一眼能看出「上次是不是崩了」；
 * 2. 把文件**复制**一份到 `logs/crash/`，供「导出诊断包」整体收走。
 *
 * 源文件不移动、不删除：用户可能还要按原路径把它贴进 issue；移动失败时也不会让
 * 排障材料直接消失。给 JVM 传 `-XX:ErrorFile` 更彻底，但那个参数在应用代码之前就被读取，
 * 打包版无法在构建时知道用户的数据目录（它也不展开 `~` / 环境变量），因此改为启动归档。
 */
private fun harvestJvmCrashLogs() {
    val roots = listOf(
        runCatching { Paths.get(System.getProperty("user.dir")) }.getOrNull(),
        AppConstants.APP_DATA_DIR,
    ).filterNotNull()
    val archived = CrashLogs.archive(roots, DiagnosticBundle.crashDir(AppConstants.APP_DATA_DIR))
    for (file in archived) {
        AppLogger.warn("Diagnostic", "归档 JVM 崩溃留痕 ${file.fileName}: ${CrashLogs.summarize(file)}")
    }
    if (archived.isNotEmpty()) {
        AppLogger.warn("Diagnostic", "本次归档 ${archived.size} 份崩溃日志，可在诊断包中一并导出")
    }
}

/**
 * 把主窗口从托盘/任务栏叫回来：解除最小化 → 置前 → 抢焦点。
 *
 * Compose 的 `Window(visible = ...)` 只管「显示/隐藏」，对**已显示但最小化**的窗口完全无效
 * （可见性本来就是 true，状态不变也就不会 recompose）。而托盘菜单里的「显示主窗口」正是
 * 这种场景：用户点了最小化按钮，或者从托盘藏过一次又恢复。因此这里直接操作 AWT 窗口。
 *
 * 每一步都单独 `runCatching`：`toFront()` 在部分 Linux 窗口管理器上会抛
 * `HeadlessException`/`SecurityException`，不能让它把「解除最小化」也一并带走。
 */
private fun raiseWindow(window: java.awt.Window?) {
    if (window == null) return
    runCatching { window.isVisible = true }
        .onFailure { AppLogger.warn("Main", "窗口显示失败: ${it.message}") }
    if (window is java.awt.Frame) {
        runCatching {
            // 只有点了最小化才需要动 extendedState：对正常窗口强行赋值 NORMAL 会把它从
            // 最大化状态拽回来（Windows 上表现为「点一下托盘，最大化没了」）。
            if (window.extendedState and java.awt.Frame.ICONIFIED != 0) {
                window.extendedState = java.awt.Frame.NORMAL
            }
        }.onFailure { AppLogger.warn("Main", "解除窗口最小化失败: ${it.message}") }
    }
    runCatching { window.toFront() }
        .onFailure { AppLogger.warn("Main", "窗口置前失败: ${it.message}") }
    runCatching { window.requestFocus() }
        .onFailure { AppLogger.warn("Main", "窗口抢焦点失败: ${it.message}") }
}

private fun runUi() {
    application {
        val icon = painterResource(Res.drawable.img_log)
        val initialSettings = remember { SettingsRepository.load() }
        val strings = remember(initialSettings.language) { stringsFor(initialSettings.language) }

        var windowVisible by remember { mutableStateOf(true) }
        var minimizeToTray by remember { mutableStateOf(initialSettings.minimizeToTray) }
        // 托盘是否真的装上了。只看 `TrayHelper.isSupported` 不够：`SystemTray.add` 仍会失败，
        // 那种情况下再「关闭即藏」就等于把应用藏进一个没有入口的地方（P1-13）。
        var trayReady by remember { mutableStateOf(false) }
        // 窗口引用供托盘唤起使用：`onShow` 定义在 Window 之外，拿不到 `window`
        var appWindow by remember { mutableStateOf<java.awt.Window?>(null) }

        LaunchedEffect(Unit) {
            try {
                // 在协程里读托盘图标：TrayHelper 内部不再 runBlocking（v4-P3-1）
                val trayIconBytes = withContext(Dispatchers.IO) {
                    runCatching { Res.readBytes("drawable/img_logo.png") }.getOrNull()
                }
                trayReady = TrayHelper.setup(
                    onShow = {
                        windowVisible = true
                        // 立即置前一次：窗口「已显示但被最小化」时 `windowVisible` 不变，
                        // 下面那个 LaunchedEffect 不会重跑，只能在这里处理（P1-14）。
                        raiseWindow(appWindow)
                    },
                    onExit = ::exitApplication,
                    showWindowLabel = strings.trayShowWindow,
                    exitLabel = strings.trayExit,
                    tooltip = strings.trayTooltip,
                    iconBytes = trayIconBytes,
                )
                if (!trayReady) {
                    AppLogger.warn("Main", "托盘图标未能安装，关闭窗口将直接退出")
                }
            } catch (e: Exception) {
                // 托盘不可用时优雅降级
                AppLogger.warn("Main", "系统托盘不可用: ${e.message}", e)
                trayReady = false
            }
        }

        // 从托盘「藏起来」再叫回来：窗口从不可见变可见时，Compose 的 visible 只负责显示，
        // 不负责把窗口抬到最前（macOS 上会被别的应用盖住）。等窗口真正显示出来再置前一次。
        LaunchedEffect(windowVisible) {
            if (!windowVisible) return@LaunchedEffect
            kotlinx.coroutines.delay(AppConstants.WINDOW_RAISE_DELAY_MS)
            raiseWindow(appWindow)
        }

        Window(
            onCloseRequest = {
                if (minimizeToTray && trayReady) {
                    windowVisible = false
                } else {
                    try {
                        TrayHelper.remove()
                    } catch (_: Exception) {}
                    exitApplication()
                }
            },
            title = strings.windowTitle,
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
            icon = icon,
            visible = windowVisible,
        ) {
            // 让托盘能拿到窗口：`SideEffect` 每帧组合成功后执行，窗口实例变化时自动跟上
            androidx.compose.runtime.SideEffect { appWindow = window }
            window.minimumSize = Dimension(900, 700)
            AppNew(
                onMinimizeToTrayChange = { minimizeToTray = it },
                onNotification = { title, message ->
                    try {
                        TrayHelper.notify(title, message)
                    } catch (e: Exception) {
                        AppLogger.warn("Main", "发送系统通知失败: ${e.message}", e)
                    }
                },
            )
        }
    }
}

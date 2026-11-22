package top.mcly99.dl.data.network

import top.mcly99.dl.utils.AppLogger
import top.mcly99.dl.utils.HostPlatform
import java.awt.Desktop
import java.nio.file.Path

/**
 * 打开下载好的安装包，交给系统安装。应用不做静默安装。
 *
 * - macOS：用 Desktop.open 打开 dmg（Finder 挂载，用户拖入应用程序）；
 * - Windows：ProcessBuilder 启动 exe/msi 安装向导，随后退出自身——
 *   不退出的话安装程序可能因「文件被占用」或旧版本仍在运行而失败。
 *
 * 退出自身通过 [onExit] 回调（来自 main.kt 的 exitApplication），
 * 这样会走 ShutdownHook（释放 JCEF）与单实例锁释放，绕开 System.exit 才好。
 */
object UpdateInstaller {

    /**
     * 打开安装包。
     *
     * @param onExit Windows 下启动安装器后调用，用于干净退出应用；macOS 下不调用。
     * @return 是否成功发起安装。
     */
    fun open(file: Path, platform: HostPlatform, onExit: () -> Unit): Boolean = runCatching {
        if (!java.nio.file.Files.isRegularFile(file)) {
            AppLogger.warn("Update", "安装包不存在: $file")
            return false
        }
        when (platform) {
            HostPlatform.MACOS -> {
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    AppLogger.warn("Update", "当前环境不支持 Desktop.open，无法打开 dmg")
                    return false
                }
                Desktop.getDesktop().open(file.toFile())
                AppLogger.info("Update", "已打开 dmg: ${file.fileName}")
            }
            HostPlatform.WINDOWS -> {
                ProcessBuilder(file.toString()).start()
                AppLogger.info("Update", "已启动安装器: ${file.fileName}")
                // 给安装器一点时间拿到文件句柄，再退出自身
                Thread {
                    Thread.sleep(800)
                    onExit()
                }.apply { isDaemon = true }.start()
            }
            HostPlatform.UNSUPPORTED -> {
                AppLogger.warn("Update", "不支持的平台，无法打开安装包")
                return false
            }
        }
        true
    }.onFailure { e ->
        AppLogger.warn("Update", "打开安装包失败: ${e.message}", e)
    }.getOrDefault(false)
}

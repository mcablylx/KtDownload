package top.mcly99.dl.engine.player

import top.mcly99.dl.utils.AppLogger
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy
import java.nio.file.Path

/**
 * libvlc 运行时：**懒初始化** + 退出时释放。
 *
 * 不点「播放」就完全不加载 native 库 —— 不增加启动时间，也没有常驻内存开销。
 * 初始化失败（缺库、架构不匹配、被杀软拦）只把原因记下来，由 UI 提示，
 * 不影响下载、传输等其余功能。
 *
 * 单例是刻意的：`NativeDiscovery` 内部用静态标记记住「已经找到过」，
 * 每次播放都新建一个 [MediaPlayerFactory] 既浪费（一个 factory 就是一个 libvlc 实例）
 * 又容易在释放时把 native 状态搞乱。
 */
object VlcRuntime {

    @Volatile
    private var factory: MediaPlayerFactory? = null

    @Volatile
    private var failure: String? = null

    /** 已定位到的 libvlc 目录，供设置页/日志展示 */
    @Volatile
    var libraryDirectory: Path? = null
        private set

    /** 取得（必要时创建）播放器工厂；不可用时返回 null，原因见 [lastError] */
    @Synchronized
    fun factory(): MediaPlayerFactory? {
        factory?.let { return it }
        // 失败结果不缓存：用户可能刚装完 VLC 或重新打包，重试应当有机会成功
        failure = null

        val directory = VlcLocator.locate()
        if (directory == null) {
            failure = "未找到 libvlc（${VlcLocator.coreLibraryName}）"
            return null
        }

        return runCatching {
            val discovery = NativeDiscovery(discoveryStrategy(directory))
            check(discovery.discover()) { "libvlc 无法加载：$directory" }
            libraryDirectory = directory
            MediaPlayerFactory(discovery, VlcOptions.factoryArgs()).also { factory = it }
        }.onFailure { e ->
            failure = e.message ?: e::class.simpleName ?: "libvlc 初始化失败"
            AppLogger.error("VlcRuntime", "初始化 libvlc 失败: $directory", e)
        }.getOrNull()
    }

    /** 最近一次失败原因；成功时为 null */
    fun lastError(): String? = failure

    /** 应用退出时调用：释放 libvlc 实例，避免残留进程 */
    @Synchronized
    fun release() {
        runCatching { factory?.release() }
            .onFailure { AppLogger.warn("VlcRuntime", "释放 libvlc 失败: ${it.message}", it) }
        factory = null
    }

    private fun discoveryStrategy(directory: Path): NativeDiscoveryStrategy =
        if (System.getProperty("os.name", "").lowercase().contains("mac")) {
            MacVlcDiscovery(directory)
        } else {
            BundledVlcDiscovery(directory)
        }

    /**
     * Windows / Linux：libvlc 会按自身所在目录去找 `plugins/`，所以只需告诉 vlcj 目录即可。
     *
     * `onSetPluginPath` 返回 true 表示「已经处理」—— Windows 上本来就不需要设置
     * `VLC_PLUGIN_PATH`，返回 true 只是让 vlcj 跳过后续的默认策略。
     */
    private class BundledVlcDiscovery(private val directory: Path) : NativeDiscoveryStrategy {
        override fun supported(): Boolean = true
        override fun discover(): String = directory.toString()
        override fun onFound(path: String): Boolean = true
        override fun onSetPluginPath(path: String): Boolean = true
    }

    /**
     * macOS：必须先把 `libvlccore` 强制加载，再设 `VLC_PLUGIN_PATH`，否则 libvlc 起不来。
     * 这段照抄 vlcj 自带的 `OsxNativeDiscoveryStrategy` 思路，只把目录换成随包资源。
     */
    private class MacVlcDiscovery(private val directory: Path) : uk.co.caprica.vlcj.factory.discovery.strategy
    .BaseNativeDiscoveryStrategy(
        arrayOf("libvlc\\.dylib", "libvlccore\\.dylib"),
        arrayOf("%s/plugins"),
    ) {
        override fun supported(): Boolean = true

        override fun discoveryDirectories(): List<String> = listOf(directory.toString())

        override fun onFound(path: String): Boolean {
            // Apple Silicon 上 libvlc 会因 libvlccore 未加载而失败，必须先手动加载一次
            com.sun.jna.NativeLibrary.addSearchPath(
                uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil.getLibVlcCoreLibraryName(),
                path,
            )
            com.sun.jna.NativeLibrary.getInstance(
                uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil.getLibVlcCoreLibraryName(),
            )
            return true
        }

        override fun setPluginPath(pluginPath: String): Boolean =
            uk.co.caprica.vlcj.binding.lib.LibC.INSTANCE.setenv(PLUGIN_ENV_NAME, pluginPath, 1) == 0
    }
}

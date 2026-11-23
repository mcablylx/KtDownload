package top.mcly99.dl.engine.player

import top.mcly99.dl.utils.AppLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * libvlc 原生库定位。
 *
 * 查找顺序（首个命中即返回）：
 * 1. 系统属性 `ktdownload.vlc` 指向的目录（测试或高级用户覆盖）
 * 2. 安装包内的应用资源根目录：`$compose.application.resources.dir`
 *    —— `vlcSetup` 插件把 libvlc 直接放在 app/resources 根下（不是子目录）
 * 3. 开发环境：从工作目录向上找仓库里的 `resources/<平台>/`
 *    （`gradlew run` 不会注入 `compose.application.resources.dir`，与 [top.mcly99.dl.utils.FfmpegLocator] 同理）
 * 4. 系统已安装的 VLC（用户自己装过 VLC 时，开发期无需先跑打包任务）
 *
 * 全部失败返回 `null`，调用方据此把「播放」降级成提示，而不是崩溃。
 */
object VlcLocator {

    private val isWindows: Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")

    private val isMac: Boolean =
        System.getProperty("os.name", "").lowercase().contains("mac")

    /** 当前平台的核心库文件名；它是「这个目录里确实有 libvlc」的判据 */
    val coreLibraryName: String = when {
        isWindows -> "libvlc.dll"
        isMac -> "libvlc.dylib"
        else -> "libvlc.so"
    }

    /** 开发期 resources 下的平台目录名，与 `vlcSetup` 插件的输出路径保持一致 */
    val platformDirectoryName: String = when {
        isWindows -> "windows"
        isMac -> "macos"
        else -> "linux"
    }

    @Volatile
    private var cached: Path? = null

    /** 测试钩子：替换真实查找，便于构造「先找不到、后来找到」；生产环境始终为 null */
    internal var finder: (() -> Path?)? = null

    /** 定位 libvlc 所在目录（即包含 [coreLibraryName] 的目录）；找不到返回 null */
    fun locate(): Path? {
        // 只缓存成功：用户可能刚装完 VLC 或把 libvlc 解压到 resources/，
        // 下次点播放要能重新搜到，而不是被一次失败永久钉死。
        cached?.let { return it }
        // 双检：并发首次定位只搜一轮文件系统，避免重复遍历目录
        synchronized(this) {
            cached?.let { return it }
            // 钩子返回 null 也要算「没找到」：不能用 `?.invoke() ?: search()`，
            // 那样会把测试里的「这次找不到」回退成真实查找
            val hook = finder
            val found = if (hook != null) hook() else search()
            if (found == null) {
                AppLogger.info("VlcLocator", "未找到 libvlc（$coreLibraryName），在线观看不可用")
                return null
            }
            cached = found
            AppLogger.info("VlcLocator", "libvlc 目录: $found")
            return found
        }
    }

    fun isAvailable(): Boolean = locate() != null

    /** 清空缓存：用户中途装了 VLC 或重新打包后无需重启即可重试 */
    fun reset() {
        cached = null
    }

    // ------------------------------------------------------------------ 查找

    private fun search(): Path? {
        // 1. 系统属性覆盖
        System.getProperty("ktdownload.vlc")?.let { override ->
            if (override.isNotBlank()) {
                directoryWithCore(Paths.get(override))?.let { return it }
            }
        }

        // 2. 安装包内的 app resources 根目录
        System.getProperty("compose.application.resources.dir")?.let { resources ->
            directoryWithCore(Paths.get(resources))?.let { return it }
        }

        // 3. 开发环境：从工作目录向上找 resources/<平台>/
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            directoryWithCore(dir.toPath().resolve("resources").resolve(platformDirectoryName))
                ?.let { return it }
            dir = dir.parentFile
        }

        // 4. 系统安装的 VLC
        return systemLocations().firstNotNullOfOrNull { directoryWithCore(it) }
    }

    /**
     * macOS 上核心库在 `VLC.app/Contents/MacOS/lib` 而不是 `Contents/MacOS`，
     * 所以这里额外补了 `/lib` 这一层。
     */
    private fun systemLocations(): List<Path> = when {
        isWindows -> listOf(
            Paths.get("C:\\Program Files\\VideoLAN\\VLC"),
            Paths.get("C:\\Program Files (x86)\\VideoLAN\\VLC"),
        )

        isMac -> listOf(
            Paths.get("/Applications/VLC.app/Contents/MacOS/lib"),
            Paths.get("/Applications/VLC.app/Contents/MacOS"),
            Paths.get(System.getProperty("user.home", ""), "Applications/VLC.app/Contents/MacOS/lib"),
        )

        else -> listOf(
            Paths.get("/usr/lib/x86_64-linux-gnu"),
            Paths.get("/usr/lib/aarch64-linux-gnu"),
            Paths.get("/usr/lib64"),
            Paths.get("/usr/lib"),
        )
    }

    /** 目录里同时有核心库才算命中：只看目录存在会把空目录当成可用 */
    private fun directoryWithCore(path: Path): Path? = runCatching {
        if (!Files.isDirectory(path)) return@runCatching null
        if (!Files.isRegularFile(path.resolve(coreLibraryName))) return@runCatching null
        path.toAbsolutePath().normalize()
    }.getOrNull()
}

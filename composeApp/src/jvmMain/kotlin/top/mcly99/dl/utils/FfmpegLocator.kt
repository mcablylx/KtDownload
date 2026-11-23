package top.mcly99.dl.utils

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * ffmpeg 可执行文件定位与版本检测。
 *
 * 查找顺序（首个命中即返回）：
 * 1. 系统属性 `ktdownload.ffmpeg`（测试或高级用户可覆盖）
 * 2. 安装包内的应用资源目录：`$compose.application.resources.dir/ffmpeg/ffmpeg[.exe]`
 * 3. 环境变量 `KTDL_FFMPEG` 指向的文件
 * 4. `PATH` 中的 `ffmpeg[.exe]`
 * 5. 常见安装位置（`C:\ffmpeg\bin`、`/usr/local/bin`、Homebrew 路径等）
 *
 * 全部失败时返回 `null`，调用方据此走"无 ffmpeg"降级路径（保存 TS 并提示）。
 *
 * 说明：ffmpeg 以**外部进程**方式调用（ProcessBuilder），不与主程序链接，
 * 因此不构成 LGPL 意义上的衍生作品，详见 `third-party/ffmpeg/SOURCE.txt`。
 */
object FfmpegLocator {

    private val isWindows: Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")

    /** 可执行文件名：Windows 需要 `.exe` 后缀 */
    val executableName: String = if (isWindows) "ffmpeg.exe" else "ffmpeg"

    /** 版本检测结果缓存：避免每次转封装都起一次进程 */
    @Volatile
    private var cachedPath: Path? = null

    @Volatile
    private var cachedVersion: String? = null

    /** 是否已经完成过一次探测（用于区分"未探测"与"探测后无可用"） */
    @Volatile
    private var probed = false

    /** 最近一次探测时间：负结果按 [NEGATIVE_CACHE_TTL_MS] 过期，用户中途安装 ffmpeg 无需重启 */
    @Volatile
    private var probedAtMs = 0L

    /**
     * 定位 ffmpeg 可执行文件。
     *
     * @return 可执行文件的绝对路径；找不到或不可执行时返回 null
     */
    fun locate(): Path? {
        cachedPath?.let { return it }
        // 负结果只在 TTL 内缓存，避免用户按文档安装 ffmpeg 后必须重启应用
        if (probed && System.currentTimeMillis() - probedAtMs < NEGATIVE_CACHE_TTL_MS) {
            return null
        }
        // 双检：并发首次调用（转封装线程 + 设置页探测）只走一次查找与起进程
        synchronized(this) {
            cachedPath?.let { return it }
            if (probed && System.currentTimeMillis() - probedAtMs < NEGATIVE_CACHE_TTL_MS) {
                return null
            }
            val found = searchCandidates()
            cachedPath = found
            if (found != null) {
                cachedVersion = probeVersion(found)
                // 探测到版本才算真正可用
                if (cachedVersion == null) {
                    cachedPath = null
                }
            }
            probed = true
            probedAtMs = System.currentTimeMillis()
            return cachedPath
        }
    }

    /**
     * 返回 ffmpeg 版本号（如 `8.1.3`）；不可用或解析失败返回 null。
     */
    fun version(): String? {
        locate()
        return cachedVersion
    }

    fun isAvailable(): Boolean = locate() != null

    /**
     * 随安装包分发的 ffmpeg 目录（内含 `LICENSE.txt`，即 LGPL 3.0 全文）。
     *
     * 用于设置页的「开源许可」入口；开发环境回退到仓库内的 `resources/common/ffmpeg`。
     */
    fun licenseDirectory(): Path? {
        val candidates = buildList {
            System.getProperty("compose.application.resources.dir")?.let {
                add(Paths.get(it, "ffmpeg"))
            }
            add(Paths.get("resources", "common", "ffmpeg"))
            add(Paths.get("..", "resources", "common", "ffmpeg"))
        }
        return candidates.firstOrNull { Files.isDirectory(it) }?.toAbsolutePath()?.normalize()
    }

    /** 清空缓存，便于测试或用户手动指定新路径后重新探测 */
    fun reset() {
        cachedPath = null
        cachedVersion = null
        probed = false
        probedAtMs = 0L
    }

    // ------------------------------------------------------------------ 查找

    private fun searchCandidates(): Path? {
        // 1. 系统属性覆盖
        System.getProperty("ktdownload.ffmpeg")?.let { override ->
            fileIfExecutable(Paths.get(override))?.let { return it }
        }

        // 2. 安装包内的应用资源目录（Compose 打包后注入）
        System.getProperty("compose.application.resources.dir")?.let { resourcesDir ->
            val candidate = Paths.get(resourcesDir, "ffmpeg", executableName)
            fileIfExecutable(candidate)?.let { return it }
        }

        // 2b. 开发环境：从工作目录向上查找仓库内的 resources/<platform>/ffmpeg/
        // IDEA / gradlew :run 时 compose.application.resources.dir 未注入，
        // 但 ffmpeg 已在 resources/{windows,macos}/ffmpeg/ 下，向上找即可命中。
        val platformDir = if (isWindows) "windows" else "macos"
        var devDir: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (devDir != null) {
            val candidate = java.io.File(devDir, "resources/$platformDir/ffmpeg/$executableName")
            if (candidate.isFile) {
                fileIfExecutable(candidate.toPath())?.let { return it }
            }
            devDir = devDir.parentFile
        }

        // 3. 环境变量
        System.getenv("KTDL_FFMPEG")?.let { env ->
            if (env.isNotBlank()) fileIfExecutable(Paths.get(env))?.let { return it }
        }

        // 4. PATH
        val fromPath = System.getenv("PATH").orEmpty()
            .split(java.io.File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { dir -> runCatching { Paths.get(dir, executableName) }.getOrNull() }
            .firstNotNullOfOrNull { fileIfExecutable(it) }
        if (fromPath != null) return fromPath

        // 5. 常见安装位置
        return fallbackLocations().firstNotNullOfOrNull { fileIfExecutable(it) }
    }

    private fun fallbackLocations(): List<Path> {
        val candidates = mutableListOf<Path>()
        if (isWindows) {
            listOf("C:\\ffmpeg\\bin", "D:\\ffmpeg\\bin", "C:\\Program Files\\ffmpeg\\bin").forEach { dir ->
                candidates.add(Paths.get(dir, executableName))
            }
            // winget / scoop / chocolatey 的常见位置
            System.getenv("LOCALAPPDATA")?.let {
                candidates.add(Paths.get(it, "Microsoft", "WinGet", "Links", executableName))
            }
            System.getenv("USERPROFILE")?.let {
                candidates.add(Paths.get(it, "scoop", "shims", executableName))
            }
            candidates.add(Paths.get("C:\\ProgramData\\chocolatey\\bin", executableName))
        } else {
            candidates.add(Paths.get("/usr/local/bin", executableName))
            candidates.add(Paths.get("/usr/bin", executableName))
            candidates.add(Paths.get("/opt/homebrew/bin", executableName))
            candidates.add(Paths.get("/opt/local/bin", executableName))
        }
        return candidates
    }

    private fun fileIfExecutable(path: Path): Path? = runCatching {
        if (!Files.isRegularFile(path)) return@runCatching null
        if (!Files.isReadable(path)) return@runCatching null
        // macOS/Linux 上丢失 +x 时会被选中却跑不起来，导致静默降级；这里提前排除
        if (!isWindows && !Files.isExecutable(path)) return@runCatching null
        path.toAbsolutePath().normalize()
    }.getOrNull()

    // ------------------------------------------------------------------ 版本

    /**
     * 执行 `ffmpeg -version` 并解析首行版本号。
     * 输出首行形如：`ffmpeg version 8.1.3-full_build-www.gyan.dev Copyright (c) ...`
     */
    private fun probeVersion(ffmpeg: Path): String? = probe(ffmpeg, PROBE_TIMEOUT_MS)

    /**
     * 带超时的版本探测：stdout 由独立线程持续排空，主线程只负责等待。
     *
     * 旧实现先 `readText()` 再 `waitFor()`，进程挂起时超时永远不会生效。
     * 公开为 internal 便于用短超时做单测。
     */
    internal fun probe(ffmpeg: Path, timeoutMs: Long): String? = runCatching {
        val process = ProcessBuilder(ffmpeg.toString(), "-version")
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        val drainer = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4_096)
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        synchronized(output) { output.appendRange(buffer, 0, n) }
                    }
                }
            }
        }, "ktdown-ffmpeg-probe").apply {
            isDaemon = true
            start()
        }

        val finished = process.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            drainer.join(1_000)
            return@runCatching null
        }
        drainer.join(1_000)
        if (process.exitValue() != 0) return@runCatching null
        parseVersion(synchronized(output) { output.toString() })
    }.getOrNull()

    /**
     * 从 `ffmpeg -version` 输出中解析版本号。
     * 公开为 internal 便于单元测试。
     */
    internal fun parseVersion(output: String): String? {
        val firstLine = output.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        // 匹配 `ffmpeg version 8.1.3...` 或 `ffmpeg version n8.1.3...` 或 `ffmpeg version git-2024-...`
        val match = VERSION_PATTERN.find(firstLine) ?: return null
        return match.groupValues[1]
    }

    private val VERSION_PATTERN = Regex("""ffmpeg version\s+n?([0-9][0-9A-Za-z.\-]*)""")

    /** `ffmpeg -version` 超时：挂起的进程不能占住 mux 线程 */
    private const val PROBE_TIMEOUT_MS = 10_000L

    /** 探测失败的缓存时间：到期后会重新查找，用户中途安装 ffmpeg 无需重启 */
    private const val NEGATIVE_CACHE_TTL_MS = 60_000L
}

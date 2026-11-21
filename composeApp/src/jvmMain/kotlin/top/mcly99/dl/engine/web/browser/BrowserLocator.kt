package top.mcly99.dl.engine.web.browser

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 一个可用的浏览器：路径 + 种类 + 版本（版本探测失败时为 `null`） */
data class BrowserInfo(
    val path: Path,
    val kind: String,
    val version: String?,
)

/**
 * 找本机可用的 Chromium 内核浏览器。
 *
 * 为什么不用 Playwright / JCEF 自带内核：那两条路要么给安装包加 100+ MB（driver + Chromium），
 * 要么首次运行还要再下 150 MB。用用户机器上已有的 Chrome / Edge（Windows 上 Edge 是系统自带）
 * 走 CDP，功能一样、包体零增量（v6-P1-3 的选型结论）。
 *
 * 判定顺序：
 * 1. 系统属性 `-Dktdownload.browser=<可执行文件>`（测试与排障用，优先级最高）
 * 2. 环境变量 `KTDL_BROWSER`
 * 3. 各平台常见安装路径（Chrome → Edge → Brave → Chromium）
 * 4. `PATH` 里的 `google-chrome` / `chromium` / `msedge`
 *
 * **判据是文件存在且可执行**，不看目录 —— 只看目录会把「装了一半」的路径当成可用。
 *
 * 版本探测（v8-P1-2）只用于**诊断**：日志里记下 `Chrome/132.0.6834.83`，
 * 用户报「抓不到」时才能区分「站点变了」和「浏览器太旧」。不在这里做版本门禁 ——
 * 门槛值会随 CDP 行为变化，写死一个数字反而会误伤能用的旧版本。
 */
object BrowserLocator {

    /** 常见路径的展示用描述，UI 提示「装了哪些浏览器才支持深度抓取」时用得上 */
    val searchPaths: List<String> by lazy { candidatePaths().map { it.toString() } }

    /** 探测结果按路径缓存：抓取热路径上不会反复起 `--version` 进程 */
    private val infoCache = ConcurrentHashMap<Path, BrowserInfo>()

    fun find(): Path? {
        overridePath()?.let { if (isExecutable(it)) return it }
        return candidatePaths().firstOrNull { isExecutable(it) }
    }

    /** 探测某个浏览器路径的种类与版本；同一条路径只探测一次 */
    fun describe(path: Path): BrowserInfo = infoCache.computeIfAbsent(path) { p ->
        BrowserInfo(path = p, kind = kindOf(p), version = detectVersion(p))
    }

    private fun overridePath(): Path? {
        val prop = System.getProperty("ktdownload.browser")?.trim()
        if (!prop.isNullOrEmpty()) return Paths.get(prop)
        val env = System.getenv("KTDL_BROWSER")?.trim()
        if (!env.isNullOrEmpty()) return Paths.get(env)
        return null
    }

    private fun isExecutable(path: Path): Boolean =
        runCatching { Files.isRegularFile(path) && Files.isExecutable(path) }.getOrDefault(false)

    /** 从路径猜种类，仅用于日志与 UI 展示，判错不影响功能 */
    private fun kindOf(path: Path): String {
        val text = path.toString().lowercase()
        return when {
            text.contains("msedge") || (text.contains("microsoft") && text.contains("edge")) -> "Edge"
            text.contains("brave") -> "Brave"
            text.contains("chromium") -> "Chromium"
            text.contains("chrome") -> "Chrome"
            else -> path.fileName?.toString() ?: "unknown"
        }
    }

    /**
     * 版本来源有两个：
     * 1. Windows 的 `Application/<版本号>/` 目录（Chrome / Edge / Brave 都是这个布局）；
     * 2. 其它平台跑一次 `--version`。
     *
     * Windows 上不跑 `--version`：部分版本会顺手拉起一个窗口，而不是只打印版本。
     */
    private fun detectVersion(path: Path): String? {
        versionFromInstallDir(path)?.let { return it }
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        return if (isWindows) null else versionFromCommand(path)
    }

    private val VERSION_DIR = Regex("""^\d+\.\d+\.\d+\.\d+$""")
    private val VERSION_TEXT = Regex("""(\d+\.\d+\.\d+(?:\.\d+)?)""")

    private fun versionFromInstallDir(path: Path): String? {
        val dir = path.parent ?: return null
        val versions = runCatching {
            Files.list(dir).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .map { it.fileName.toString() }
                    .filter { VERSION_DIR.matches(it) }
                    .toList()
            }
        }.getOrNull() ?: return null
        return versions.maxWithOrNull(Comparator { a, b -> compareVersions(a, b) })
    }

    private fun compareVersions(a: String, b: String): Int {
        val left = a.split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val result = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (result != 0) return result
        }
        return 0
    }

    private fun versionFromCommand(path: Path): String? = runCatching {
        val process = ProcessBuilder(path.toString(), "--version")
            .redirectErrorStream(true)
            .start()
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) return@runCatching null
            val text = process.inputStream.bufferedReader().use { it.readText() }
            VERSION_TEXT.find(text)?.groupValues?.get(1)
        } finally {
            runCatching { process.destroyForcibly() }
        }
    }.getOrNull()

    private fun candidatePaths(): List<Path> {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> windowsPaths()
            os.contains("mac") -> macPaths()
            else -> linuxPaths()
        }
    }

    private fun windowsPaths(): List<Path> {
        val programFiles = listOfNotNull(
            System.getenv("ProgramFiles"),
            System.getenv("ProgramFiles(x86)"),
            System.getenv("ProgramW6432"),
        ).distinct()
        val localAppData = System.getenv("LocalAppData")?.takeIf { it.isNotBlank() }
        val paths = mutableListOf<Path>()
        programFiles.forEach { root ->
            paths.add(Paths.get(root, "Google", "Chrome", "Application", "chrome.exe"))
            paths.add(Paths.get(root, "Microsoft", "Edge", "Application", "msedge.exe"))
            paths.add(Paths.get(root, "BraveSoftware", "Brave-Browser", "Application", "brave.exe"))
            paths.add(Paths.get(root, "Chromium", "Application", "chrome.exe"))
        }
        localAppData?.let {
            paths.add(Paths.get(it, "Google", "Chrome", "Application", "chrome.exe"))
            paths.add(Paths.get(it, "Microsoft", "Edge", "Application", "msedge.exe"))
            paths.add(Paths.get(it, "BraveSoftware", "Brave-Browser", "Application", "brave.exe"))
        }
        return paths
    }

    private fun macPaths(): List<Path> = listOf(
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
        "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
        "/Applications/Chromium.app/Contents/MacOS/Chromium",
    ).map { Paths.get(it) } + fromPathEnv(listOf("google-chrome", "chromium", "microsoft-edge"))

    private fun linuxPaths(): List<Path> = listOf(
        "/usr/bin/google-chrome",
        "/usr/bin/google-chrome-stable",
        "/usr/bin/chromium",
        "/usr/bin/chromium-browser",
        "/usr/bin/microsoft-edge",
        "/snap/bin/chromium",
    ).map { Paths.get(it) } + fromPathEnv(listOf("google-chrome", "chromium", "chromium-browser", "microsoft-edge"))

    /** 扫 `PATH`：Windows 上补 `.exe`，其它平台原样 */
    private fun fromPathEnv(names: List<String>): List<Path> {
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val dirs = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparatorChar)
            .filter { it.isNotBlank() }
        val result = mutableListOf<Path>()
        for (dir in dirs) {
            for (name in names) {
                result.add(Paths.get(dir, if (isWindows) "$name.exe" else name))
            }
        }
        return result
    }
}

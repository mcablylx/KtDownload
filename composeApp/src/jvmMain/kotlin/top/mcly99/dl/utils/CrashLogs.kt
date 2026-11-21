package top.mcly99.dl.utils

import java.nio.file.Files
import java.nio.file.Path

/**
 * JVM 崩溃留痕（`hs_err_pid*.log` / `replay_pid*.log`）的归档。
 *
 * 为什么是「启动时复制」而不是给 JVM 传 `-XX:ErrorFile`：
 * 那个参数在应用代码跑起来之前就被读取了，打包版无法在构建时知道最终用户的数据目录
 * （`-XX:ErrorFile` 也不展开 `~` 与环境变量），硬编码只会把文件写到不可预期的位置。
 * 所以改成「启动时把散落在工作目录 / 数据目录里的崩溃日志**复制**一份到
 * `logs/crash/`」：源文件一个字节都不动（用户可能还要自己交 issue），
 * 归档目录则会被诊断包整体收走。
 *
 * 复制而不是移动的三个理由：
 * 1. 用户/支持人员按原始路径找文件是习惯动作；
 * 2. 移动失败（只读目录、文件被占用）会让「收集诊断」变成「数据消失」；
 * 3. 同一份崩溃日志可能同时出现在两个来源目录里，移动会互相打架。
 */
object CrashLogs {

    /** 崩溃日志文件名前缀；`replay_pid*.log` 是 JIT 重放文件，排障同样有用 */
    private val PREFIXES = listOf("hs_err_pid", "replay_pid")

    /**
     * 扫描 [sources]（只扫一层，不递归）里的崩溃日志，复制到 [archiveDir]。
     *
     * @return 本次**新归档**的文件（已存在的同名同大小文件会跳过，避免每次启动都重写）
     */
    fun archive(sources: List<Path>, archiveDir: Path): List<Path> {
        val archived = mutableListOf<Path>()
        val candidates = sources.filter { Files.isDirectory(it) }.flatMap { dir ->
            runCatching {
                Files.list(dir).use { stream ->
                    stream.filter { isCrashLog(it) }.toList()
                }
            }.getOrDefault(emptyList())
        }
        if (candidates.isEmpty()) return archived

        runCatching { Files.createDirectories(archiveDir) }.getOrElse { return archived }
        for (source in candidates) {
            val target = archiveDir.resolve(source.fileName.toString())
            val size = runCatching { Files.size(source) }.getOrNull() ?: continue
            val already = runCatching {
                Files.exists(target) && Files.size(target) == size
            }.getOrDefault(false)
            if (already) continue
            runCatching {
                Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                // Path 本身实现 Iterable<Path>，`+=` 会与 addAll 重载冲突，必须显式 add
                archived.add(target)
            }
        }
        return archived
    }

    fun isCrashLog(path: Path): Boolean {
        val name = path.fileName?.toString().orEmpty()
        if (!name.endsWith(".log")) return false
        return PREFIXES.any { name.startsWith(it) }
    }

    /**
     * 从崩溃日志里挑出对定位最有用的几行：JVM 摘要（`#` 开头）、信号信息、
     * 问题栈帧与命令行。整份日志动辄上千行，全塞进主日志只会把它撑爆。
     */
    fun summarize(path: Path, maxLines: Int = 6): String = runCatching {
        Files.readAllLines(path).asSequence()
            .map { it.trim() }
            .filter { line ->
                line.startsWith("# ") || line.startsWith("siginfo:") ||
                    line.contains("Problematic frame:") || line.startsWith("Command Line:")
            }
            .take(maxLines)
            .joinToString(" | ")
    }.getOrDefault("")
}

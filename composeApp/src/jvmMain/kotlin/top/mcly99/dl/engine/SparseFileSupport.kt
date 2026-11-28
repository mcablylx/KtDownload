package top.mcly99.dl.engine

import top.mcly99.dl.utils.AppLogger
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * 单文件偏移写的前置条件：**先按最终长度预留，再标稀疏**。
 *
 * 改成「一个文件 + 各连接按绝对偏移写」之后，文件必然会被从中间写起。NTFS 的坑在这里：
 * 文件被扩展后往很远处写，系统会把中间的空洞**同步零填充**（Mister DM 实测：2 GiB 偏移
 * 处写 1 MiB 耗时 4.3 s，且写入量翻倍）。症状是「第一次下载正常，续传或大文件慢得离谱」，
 * 而且看起来像网络问题。
 *
 * [RandomAccessFile.setLength] 只把 EOF 推过去（VDL 不推进），本身很快；真正的零填充发生
 * 在随后的「远处写」。所以扩展之后必须让文件带上稀疏属性，NTFS 才不会去物化那些空洞。
 *
 * - Windows：`FSCTL_SET_SPARSE` 需要文件句柄，用 JNA 调 DeviceIoControl 得引 native 映射；
 *   这里改用系统自带的 `fsutil sparse setflag`，一次任务一条子进程（几十毫秒），失败可降级。
 * - Linux / macOS：`truncate` 扩展天然产生稀疏文件（ext4 / APFS），不需要额外动作。
 *
 * 标记失败不会影响正确性，只是丢掉「远距离写不物化空洞」这个优化，因此降级为记一条日志。
 */
internal object SparseFileSupport {

    /** fsutil 极少数情况下会挂住（磁盘/安全软件），超时就不再等它 */
    private const val FSUtil_TIMEOUT_MS = 5_000L

    private val isWindows: Boolean by lazy {
        System.getProperty("os.name").orEmpty().lowercase().contains("win")
    }

    /**
     * 把 [file] 预留到 [totalSize] 字节，并尽量标记为稀疏文件。
     *
     * @return 是否成功标记为稀疏；false 只是少了一个优化，写入仍然正确
     */
    fun prepare(file: File, totalSize: Long): Boolean {
        RandomAccessFile(file, "rw").use { it.setLength(totalSize) }
        if (!isWindows) return true
        return markSparseOnWindows(file)
    }

    /**
     * Windows 上的稀疏标记。`fsutil` 缺失、超时或返回非 0 都只记日志，
     * 由调用方接受「首次远距离写可能要填零」的代价。
     */
    internal fun markSparseOnWindows(file: File): Boolean {
        val fsutil = fsutilExecutable() ?: run {
            AppLogger.debug("SparseFileSupport", "未找到 fsutil.exe，跳过稀疏标记: ${file.name}")
            return false
        }
        return runCatching {
            val process = ProcessBuilder(fsutil.absolutePath, "sparse", "setflag", file.absolutePath)
                .redirectErrorStream(true)
                // fsutil 在中文系统上按 GBK 输出，读它没有意义，直接丢弃避免管道占满
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!process.waitFor(FSUtil_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                AppLogger.warn("SparseFileSupport", "fsutil 超时，放弃稀疏标记: ${file.name}")
                return false
            }
            val ok = process.exitValue() == 0
            if (!ok) {
                AppLogger.warn("SparseFileSupport", "fsutil 标记稀疏失败（exit=${process.exitValue()}）: ${file.name}")
            }
            ok
        }.getOrElse { error ->
            AppLogger.warn("SparseFileSupport", "标记稀疏异常，按非稀疏继续: ${file.name}", error)
            false
        }
    }

    private fun fsutilExecutable(): File? {
        val root = System.getenv("SystemRoot")?.takeIf { it.isNotBlank() } ?: "C:\\Windows"
        val candidates = listOf(
            File(root, "System32\\fsutil.exe"),
            File(root, "Sysnative\\fsutil.exe"),
        )
        return candidates.firstOrNull { it.isFile }
    }
}

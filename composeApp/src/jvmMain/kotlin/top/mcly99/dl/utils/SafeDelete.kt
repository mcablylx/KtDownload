package top.mcly99.dl.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * 递归删除的唯一入口 + 危险路径闸门。
 *
 * ## 为什么需要这道闸
 *
 * 上游 YunX-Desktop 出过一起真实事故：用户在「设置 → 储存目录」把下载缓存放在
 * F 盘，之后改到 E 盘；应用启动时会把「旧位置」当成自己的旧数据递归清理，
 * 结果把 F 盘上的内容整个清空。上游 v1.3.4 的修复说明原文是：
 * 「缓存 / 数据目录设成磁盘根目录会搬走整块盘：源为盘符根（如 `D:\`）或用户主目录时，
 * 只迁移本应用自己的条目；启动时的旧目录清理也会拒绝删除盘符根、当前数据目录的祖先，
 * 以及混有用户文件的目标。」
 *
 * 本项目**没有**那条具体路径：[AppConstants.APP_DATA_DIR] 是硬编码的
 * `~/.mclydownload`，设置里只有一个 `downloadPath`（只用于决定文件落盘位置，
 * 从不整目录删除），也没有「储存目录迁移 / 启动清理旧目录」这两件事。
 * 换句话说，现在是「因为没做那两个功能所以免疫」，而不是「做对了所以免疫」。
 *
 * 只要以后有人把可配置缓存目录或迁移功能加回来，同样的坑就会重演。因此这里把
 * 全仓的递归删除收口成一个函数，统一过闸，并且给出可解释的拒绝理由。
 *
 * ## 闸门规则（任一命中即拒绝）
 *
 * 1. 目标没有名字 —— 盘符根（`F:\`）、POSIX 根（`/`）都属于这一类；
 * 2. 目标没有父目录 —— 同上，双保险（不同 JDK 对 `C:` 与 `C:\` 的 `name`/`parent` 处理有差异）；
 * 3. 用户主目录在目标内部 —— 删它等于删家目录；
 * 4. 应用数据目录在目标内部 —— 删它等于删自己的配置、账号池、任务列表；
 * 5. 待删条目数超过 [MAX_ENTRIES] —— 正常的 HLS 分片目录最多几千个文件，
 *    上万说明路径算错了，宁可失败也不赌。
 *
 * 判定用的是 `toRealPath()`（跟随符号链接），所以「指向家目录的快捷方式」也会被拒；
 * 拒绝只发生在真正存在且命中的路径上，目标不存在时静默返回，不刷日志。
 */
internal object SafeDelete {

    private const val TAG = "SafeDelete"

    /**
     * 单次递归删除允许的条目上限。
     *
     * 参照：2 小时视频按 6 秒一片是 1200 个分片；就算 1 秒一片也只有 7200。
     * 5 万留了足够余量，同时能拦住「本来要删 `xxx.hls` 结果指向了整块盘」这类错算。
     */
    const val MAX_ENTRIES = 50_000

    /**
     * 判断目标能否被递归删除；能删返回 `null`，不能删返回中文理由。
     *
     * [home] / [dataDir] 可注入，便于在测试里构造「家目录 / 数据目录」的假路径。
     */
    fun refusalReason(
        target: File,
        home: File = File(System.getProperty("user.home").orEmpty()),
        dataDir: File = AppConstants.APP_DATA_DIR.toFile(),
    ): String? {
        if (target.name.isBlank()) {
            return "目标路径没有名字（盘符根 / 文件系统根）"
        }
        if (target.absoluteFile.parentFile == null) {
            return "目标是根目录，没有父目录"
        }
        val real = realOrAbsolute(target)
        if (real.parent == null) {
            return "目标是根目录，没有父目录"
        }

        val homeReal = realOrAbsolute(home)
        if (homeReal.startsWith(real)) {
            return "目标包含用户主目录"
        }
        val dataReal = realOrAbsolute(dataDir)
        if (dataReal.startsWith(real)) {
            return "目标包含应用数据目录"
        }
        return null
    }

    /**
     * 递归删除 [target]，返回是否真的删掉了。
     *
     * [label] 只用于日志（如「HLS 分片」），方便事后从日志判断是谁想删什么。
     * 目标不存在、被闸门拒绝、删除途中抛异常都返回 `false`，**永不抛到调用方**：
     * 这些调用点大多在下载协程的收尾路径上，抛出去会把已完成的任务搞成失败。
     */
    fun deleteTree(
        target: File,
        label: String,
        maxEntries: Int = MAX_ENTRIES,
    ): Boolean {
        if (!target.exists()) return false

        val reason = refusalReason(target)
        if (reason != null) {
            AppLogger.warn(TAG, "拒绝删除${label}：$reason（${target.absolutePath}）")
            return false
        }

        val entries = runCatching { countEntries(target, maxEntries.toLong() + 1L) }.getOrDefault(0L)
        if (entries > maxEntries) {
            AppLogger.warn(
                TAG,
                "拒绝删除${label}：待删条目数 ${entries}+ 超过上限 $maxEntries，路径可能算错" +
                    "（${target.absolutePath}）",
            )
            return false
        }

        return runCatching { target.deleteRecursively() }
            .onFailure { AppLogger.warn(TAG, "删除${label}失败: ${target.absolutePath}", it) }
            .getOrDefault(false)
    }

    /** 统计目录下条目数，最多数到 [limit] 就停，避免为了判定而遍历整块盘。 */
    private fun countEntries(target: File, limit: Long): Long =
        Files.walk(target.toPath()).use { stream -> stream.limit(limit).count() }

    /**
     * 尽量解析符号链接；解析不了（路径不存在 / 无权限）就退化成绝对规范路径。
     *
     * 两者混用时前缀比较仍然成立：解析成功的路径一定是解析失败那条的真实路径。
     */
    private fun realOrAbsolute(file: File): Path {
        val path = file.toPath().toAbsolutePath().normalize()
        return runCatching { path.toRealPath() }.getOrDefault(path)
    }
}

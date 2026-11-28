package top.mcly99.dl.engine

import top.mcly99.dl.utils.AppLogger
import top.mcly99.dl.utils.formatBytes
import java.io.File
import java.nio.file.FileStore
import java.nio.file.Files

/**
 * 下载开始前的磁盘空间预检。
 *
 * 目的不是「一定会写满」，而是把两种**注定失败**的下载挡在第一个字节之前：
 *
 * 1. 目标盘余量不够。旧实现要下到一半盘满才报 `IOException: There is not enough space on the disk`，
 *    用户看到的是失败的任务和一堆 0 字节残片，还得自己收拾；
 * 2. 目标盘是 FAT32，而文件超过 4 GiB。这种情况更糟：FAT32 单文件上限 `4 GiB − 1`，
 *    写到最后 4 GiB 处才报错，前面几十 GB 全白下。
 *
 * 容错原则与 [top.mcly99.dl.data.repository.TransferSpaceGuard] 一致：大小未知、
 * 磁盘信息查不到（UNC 路径 / 权限不足）时一律放行，只记日志 —— 宁可少拦一次，
 * 也不能因为查不到余量把正常下载拦死。
 *
 * 注意与 [SparseFileSupport] 的配合：Windows 上预扩展会先 `setLength` 到完整长度，
 * 非稀疏文件会**立刻**占满空间。所以这里按「整份文件」判断，而不是按「当前还没写的部分」，
 * 否则预扩展那一步就会失败。
 */
internal object DiskSpaceGuard {

    /** FAT32 单文件上限：4 GiB − 1 字节（`0xFFFFFFFF`） */
    const val FAT32_MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024 - 1

    /**
     * 余量：目录项、段表快照、NTFS 元数据都要占一点。
     *
     * 用固定值而不是百分比：100 GB 的文件按 5% 算就是 5 GB，用户会莫名其妙被拦。
     */
    const val HEADROOM_BYTES = 8L * 1024 * 1024

    /**
     * 检查目标位置能否容纳这份下载。
     *
     * @param target 最终文件路径（父目录必须已存在；不存在时向上找最近的存在目录取磁盘信息）
     * @param totalSize 远端声明的文件总长；<=0 表示未知 → 直接放行
     * @param alreadyOnDisk 本地已经下好、可以复用的字节数（续传时抵扣）；0 表示从零开始
     * @return 面向用户的失败原因；null 表示可以继续
     */
    fun check(target: File, totalSize: Long, alreadyOnDisk: Long = existingBytes(target)): String? {
        if (totalSize <= 0L) return null

        val store = fileStoreOf(target)
        if (store == null) {
            AppLogger.debug("DiskSpaceGuard", "无法确定目标磁盘信息，跳过空间预检: ${target.absolutePath}")
            return null
        }
        // Java 的 getUsableSpace() 在 Kotlin 里是属性，不是方法
        val usable = try {
            store.usableSpace
        } catch (e: Exception) {
            AppLogger.debug("DiskSpaceGuard", "读取可用空间失败，跳过空间预检: ${e.message}")
            return null
        }
        val fsType = try {
            store.type()
        } catch (_: Exception) {
            ""
        }
        return evaluate(totalSize, alreadyOnDisk, usable, fsType)
    }

    /**
     * 纯判定逻辑，便于单测：不碰文件系统。
     *
     * @param usable 目标磁盘可用字节数；<=0 视为「查询不到」，放行
     * @param fsType [FileStore.type] 的返回值，如 `ntfs` / `fat32` / `ext4`；空串表示未知
     */
    fun evaluate(totalSize: Long, alreadyOnDisk: Long, usable: Long, fsType: String): String? {
        if (totalSize <= 0L) return null

        // 先判文件系统：余量再大，FAT32 也写不下 4 GiB 以上的文件。
        // 必须放在余量判断之前，否则一个「余量充足但格式不对」的盘会被放行。
        if (isFat32(fsType) && totalSize > FAT32_MAX_FILE_BYTES) {
            return "目标磁盘是 FAT32，单个文件不能超过 ${formatBytes(FAT32_MAX_FILE_BYTES)}；" +
                "这份文件有 ${formatBytes(totalSize)}，请改存到 NTFS / exFAT 磁盘"
        }

        if (usable <= 0L) return null

        // 续传抵扣：已经落盘的部分不用再占空间；预扩展会按最终长度占位，所以抵的是「已有数据」
        val needed = (totalSize - alreadyOnDisk.coerceAtLeast(0L)).coerceAtLeast(0L)
        // 一个字节都不用再写（本地已经是完整的）：余量再小也不该拦，否则「最后 1 KiB 下完」
        // 这类任务会因为余量不足被误判
        if (needed == 0L) return null
        if (needed + HEADROOM_BYTES <= usable) return null

        return "磁盘空间不足：还需要 ${formatBytes(needed)}，当前可用 ${formatBytes(usable)}" +
            "（预留 ${formatBytes(HEADROOM_BYTES)} 余量）"
    }

    /** 本地可复用的字节数：就是临时文件的长度；没有临时文件时为 0 */
    fun existingBytes(target: File): Long =
        runCatching { partialDownloadFile(target).length() }.getOrDefault(0L)

    private fun isFat32(fsType: String): Boolean =
        fsType.equals("fat32", ignoreCase = true) ||
            // 部分平台把 FAT 系列统一报成 "fat"
            fsType.equals("fat", ignoreCase = true)

    /**
     * 取目标路径所在磁盘的信息。
     *
     * 目标目录可能还没建（`mkdirs()` 失败或尚未执行），此时按父目录逐级上找，
     * 直到找到一个存在的祖先 —— 它所在的磁盘就是将来文件所在的磁盘。
     */
    private fun fileStoreOf(target: File): FileStore? {
        var probe: File? = target.absoluteFile.parentFile
        while (probe != null) {
            if (probe.exists()) {
                return runCatching { Files.getFileStore(probe.toPath()) }.getOrNull()
            }
            probe = probe.parentFile
        }
        return null
    }
}

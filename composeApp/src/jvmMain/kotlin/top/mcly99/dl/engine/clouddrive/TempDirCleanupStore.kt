package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** 一条待清理的云端临时目录（转存下载时建的 `tr_*`）。 */
data class PendingCleanup(
    val platform: SharePlatform,
    val dirFid: String,
    /**
     * 建这个目录时用的账号 id；空串 = 该平台主账号。
     *
     * **只存 id 不存凭证**：账号池本身已经是加密落盘的，清理时按 id 现取即可。
     * 把凭证再抄一份到这个明文清单里，等于给「凭证不落盘」这条规矩开了个后门。
     */
    val accountId: String = "",
)

/**
 * 待清理临时目录的落盘清单（P1-18）。
 *
 * 为什么需要它：`pendingCleanups` 原来只在内存里，而退出应用时引擎把进行中的任务改成
 * `PAUSED`（不是终态），不会触发清理；重启后这份清单已经没了，用户网盘里的 `tr_*`
 * 空目录就再也没人回收 —— 每次「转存下载」失败或中断都会留下一个。
 *
 * 只记 `平台 + 目录 id + 账号 id` 三个字段，用制表符分隔的纯文本：
 * - 不含凭证、不含 URL（直链带签名，落盘没有意义还有风险）；
 * - 写入是「临时文件 + 原子替换」，权限 `0600`（Windows 不支持则跳过）；
 * - 解析失败的行直接跳过，不让一条坏行毁掉整份清单。
 */
class TempDirCleanupStore(
    private val file: Path = AppConstants.APP_DATA_DIR.resolve("pending_cleanups.txt"),
) {
    private val lock = Any()

    fun load(): List<PendingCleanup> = synchronized(lock) { readAll() }

    fun add(entry: PendingCleanup) {
        if (entry.dirFid.isBlank()) return
        synchronized(lock) {
            val rows = readAll().toMutableList()
            if (entry in rows) return
            rows += entry
            // 上限只是防御：正常情况一天也就几条，超过说明清理一直失败在堆积
            writeAll(if (rows.size > MAX_ENTRIES) rows.takeLast(MAX_ENTRIES) else rows)
        }
    }

    fun remove(entry: PendingCleanup) {
        synchronized(lock) {
            val rows = readAll().toMutableList()
            if (!rows.remove(entry)) return
            writeAll(rows)
        }
    }

    fun size(): Int = synchronized(lock) { readAll().size }

    private fun readAll(): List<PendingCleanup> {
        if (!Files.isRegularFile(file)) return emptyList()
        return runCatching {
            Files.readAllLines(file).mapNotNull { line ->
                if (line.isBlank() || line.startsWith('#')) return@mapNotNull null
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val platform = runCatching { SharePlatform.valueOf(parts[0].trim()) }.getOrNull()
                    ?: return@mapNotNull null
                val dirFid = parts[1].trim()
                if (dirFid.isEmpty()) return@mapNotNull null
                PendingCleanup(platform, dirFid, parts[2].trim())
            }
        }.onFailure {
            AppLogger.warn("TempDirCleanupStore", "读取待清理清单失败: ${it.message}", it)
        }.getOrDefault(emptyList())
    }

    private fun writeAll(rows: List<PendingCleanup>) {
        runCatching {
            file.parent?.toFile()?.mkdirs()
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            val body = buildString {
                append("# KtDownload pending temp-dir cleanups: platform<TAB>dirFid<TAB>accountId\n")
                rows.forEach { append("${it.platform.name}\t${it.dirFid}\t${it.accountId}\n") }
            }
            Files.write(tmp, body.toByteArray(Charsets.UTF_8))
            harden(tmp)
            // 先试原子替换；某些文件系统（网络盘 / FAT）不支持，退回普通替换
            runCatching {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.onFailure {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
            harden(file)
        }.onFailure {
            AppLogger.warn("TempDirCleanupStore", "写入待清理清单失败: ${it.message}", it)
        }
    }

    /** 目录条目里是网盘 id，不是机密；但收紧到 0600 的成本接近零，顺手做掉 */
    private fun harden(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }
    }

    private companion object {
        const val MAX_ENTRIES = 500
    }
}

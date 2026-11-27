package top.mcly99.dl.utils

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap

/**
 * P2-16：权限收紧必须跨平台**如实**生效，不能静默假装成功。
 *
 * - POSIX（macOS / Linux）：目录 0700、文件 0600；失败记 warn。
 * - Windows：没有 POSIX 权限，旧实现 `runCatching { setPosixFilePermissions }` 抛
 *   `UnsupportedOperationException` 后被静默吞掉 —— 对外宣称 0600，实际是 no-op。
 *   这里改为**只读检查 ACL**：若 Everyone / Users / Authenticated Users 可读，就明确告警
 *   「Windows 下无 POSIX 0600，请确认用户目录权限」；不主动改 ACL，避免把管理员锁在门外。
 * - 其它文件系统：同样明确告警。
 *
 * 同一路径只告警一次，避免每次 save 都刷日志。
 */
internal object FilePermissions {

    private val warned = ConcurrentHashMap.newKeySet<String>()

    fun hardenDirectory(path: Path) = harden(path, directory = true)

    fun hardenFile(path: Path) = harden(path, directory = false)

    private fun harden(path: Path, directory: Boolean) {
        if (!Files.exists(path)) return
        val store = runCatching { Files.getFileStore(path) }.getOrNull()
        if (store?.supportsFileAttributeView("posix") == true) {
            val perms = if (directory) "rwx------" else "rw-------"
            runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms)) }
                .onFailure { warnOnce(path, "POSIX 权限设置失败（$perms）: ${it.message}") }
            return
        }
        if (store?.supportsFileAttributeView("acl") == true) {
            runCatching { inspectAcl(path) }
                .onFailure { warnOnce(path, "无法读取 ACL，按系统默认权限处理: ${it.message}") }
            return
        }
        warnOnce(path, "当前文件系统不支持 POSIX / ACL 权限，无法保证 0600")
    }

    /** 只读检查，不改 ACL：把「实际上谁可读」如实写进日志 */
    private fun inspectAcl(path: Path) {
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            ?: return warnOnce(path, "当前文件系统没有 ACL 视图，无法确认凭证文件权限")
        val exposed = view.acl
            .filter { it.type() == AclEntryType.ALLOW }
            .map { it.principal().name.lowercase() }
            .filter(::isBroadPrincipal)
            .distinct()
        if (exposed.isNotEmpty()) {
            warnOnce(
                path,
                "Windows 无 POSIX 0600：该路径对 ${exposed.joinToString()} 可读，" +
                    "请确认用户目录权限或改用加密卷",
            )
        }
    }

    private fun isBroadPrincipal(name: String): Boolean =
        name == "everyone" ||
            name == "users" ||
            name.endsWith("\\users") ||
            name.endsWith("\\authenticated users")

    private fun warnOnce(path: Path, message: String) {
        val key = path.toAbsolutePath().normalize().toString()
        if (warned.add(key)) AppLogger.warn("FilePermissions", message)
    }
}

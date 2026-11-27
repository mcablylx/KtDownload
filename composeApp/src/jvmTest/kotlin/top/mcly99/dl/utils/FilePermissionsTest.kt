package top.mcly99.dl.utils

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P2-16 契约：
 * - POSIX 上文件必须真的变成 `rw-------`、目录 `rwx------`（不是「调用过就算」）；
 * - Windows / 不支持 POSIX 的文件系统上不抛异常（改由 ACL 检查 + 告警，见实现注释）；
 * - 不存在的路径静默跳过，重复调用幂等。
 */
class FilePermissionsTest {

    @Test
    fun missing_path_is_ignored_without_throwing() {
        val dir = Files.createTempDirectory("ktdl-perm-missing")
        try {
            FilePermissions.hardenFile(dir.resolve("not-exists.properties"))
            FilePermissions.hardenDirectory(dir.resolve("not-exists-dir"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun posix_file_becomes_owner_only_and_directory_becomes_0700() {
        val dir = Files.createTempDirectory("ktdl-perm-posix")
        try {
            assumeTrue(
                "当前文件系统不支持 POSIX 权限（Windows），跳过精确权限断言",
                Files.getFileStore(dir).supportsFileAttributeView("posix"),
            )
            val file = dir.resolve("secret.properties").also { Files.write(it, byteArrayOf(1)) }

            FilePermissions.hardenFile(file)
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))

            FilePermissions.hardenDirectory(dir)
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun hardening_is_idempotent_and_never_throws() {
        val dir = Files.createTempDirectory("ktdl-perm-idem")
        try {
            val file = dir.resolve("a.properties").also { Files.write(it, byteArrayOf(2)) }
            repeat(3) {
                FilePermissions.hardenFile(file)
                FilePermissions.hardenDirectory(dir)
            }
            assertTrue(Files.exists(file), "收紧权限不应影响文件本身")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

package top.mcly99.dl.utils

import org.junit.Assume.assumeTrue
import top.mcly99.dl.model.AppSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2-14 回归：诊断链路的脱敏键名与落盘权限。
 *
 * P1-07 已经把键名改成模糊包含匹配、响应体/error/日志落盘前统一走
 * [SensitiveTextRedactor]；本条剩下的是「脱敏只是尽力而为，日志与诊断包本身
 * 仍应是仅当前用户可读」。这里断言权限收紧真的接上了，并用源码契约锁住接线。
 */
class DiagnosticPermissionTest {

    @Test
    fun exported_bundle_is_tightened_to_owner_only_on_posix() {
        val dir = Files.createTempDirectory("ktdl-diag-perm")
        try {
            val target = dir.resolve("bundle.zip")
            DiagnosticBundle.export(
                target = target,
                settings = AppSettings(),
                logDir = dir,
                dataDir = dir,
                now = Instant.now(),
            )
            assertTrue(Files.isRegularFile(target), "诊断包应已写出")
            ZipFile(target.toFile()).use { zip ->
                assertTrue(zip.getEntry("manifest.txt") != null, "诊断包必须含 manifest")
            }
            val posix = Files.getFileStore(target).supportsFileAttributeView("posix")
            assumeTrue("当前文件系统不支持 POSIX 权限（Windows），跳过精确权限断言", posix)
            assertEquals(
                "rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(target)),
                "诊断包含日志原文，必须收紧到仅属主可读",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun log_directory_file_and_bundle_all_route_through_file_permissions() {
        val logger = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/utils/AppLogger.kt")
        assertTrue(logger.contains("FilePermissions.hardenDirectory(directory)"), "日志目录未收紧")
        assertTrue(logger.contains("FilePermissions.hardenFile(logFile)"), "日志文件未收紧")

        val bundle = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/utils/DiagnosticBundle.kt")
        assertTrue(bundle.contains("FilePermissions.hardenFile(target)"), "诊断包 zip 未收紧")
    }

    @Test
    fun diagnostic_interceptor_redacts_response_bodies_and_camel_case_keys() {
        val interceptor = sourceOf(
            "composeApp/src/jvmMain/kotlin/top/mcly99/dl/data/network/DiagnosticNetworkInterceptor.kt",
        )
        assertTrue(interceptor.contains("redactSensitiveBody(it)"), "非 2xx 响应体必须过脱敏")
        assertTrue(
            interceptor.contains("SensitiveTextRedactor.redact(t.message"),
            "异常文本必须过脱敏",
        )

        val redacted = SensitiveTextRedactor.redact(
            """{"client_secret":"CS","sign":"SIG","Set-Cookie":"COOKIE=1","captchaToken":"CT"}""",
        )
        listOf("CS", "SIG", "COOKIE=1", "CT").forEach { secret ->
            assertFalse(redacted.contains(secret), "敏感键名模糊匹配漏掉了 $secret：$redacted")
        }
    }

    private fun sourceOf(relative: String): String =
        listOf(File(relative), File("../$relative"), File("../../$relative"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("找不到源码 $relative（工作目录 ${System.getProperty("user.dir")}）")
}

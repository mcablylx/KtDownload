package top.mcly99.dl.utils

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.mcly99.dl.model.AppSettings
import top.mcly99.dl.model.ProxyMode

/**
 * 诊断包：该打进去的都打进去，**不该打进去的一个都不能有**。
 *
 * 第二条比第一条重要：诊断包是用来发给别人的，凭证 / Cookie 一旦进包就是账号泄露。
 */
class DiagnosticBundleTest {

    private val now: Instant = LocalDate.of(2026, 11, 15)
        .atTime(12, 0)
        .atZone(ZoneId.systemDefault())
        .toInstant()

    private fun withTempDirs(block: (Path, Path) -> Unit) {
        val base = Files.createTempDirectory("kt-diag-test")
        try {
            val logDir = base.resolve("logs")
            val dataDir = base.resolve("data")
            Files.createDirectories(logDir)
            Files.createDirectories(dataDir)
            block(logDir, dataDir)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun the_bundle_contains_environment_settings_recent_logs_and_crash_traces() = withTempDirs { logDir, dataDir ->
        val today = logDir.resolve("app-2026-11-15.log")
        val recent = logDir.resolve("app-2026-11-12.log")
        val stale = logDir.resolve("app-2026-11-01.log")
        Files.writeString(today, "[INFO] today\n")
        Files.writeString(recent, "[INFO] three days ago\n")
        Files.writeString(stale, "[INFO] two weeks ago\n")
        Files.writeString(logDir.resolve("jcef.log"), "[JCEF] start\n")
        Files.writeString(logDir.resolve("unrelated.txt"), "not a log\n")

        val crashDir = DiagnosticBundle.crashDir(dataDir)
        Files.createDirectories(crashDir)
        Files.writeString(crashDir.resolve("hs_err_pid4242.log"), "# A fatal error has been detected\n")

        val target = dataDir.resolve("diag.zip")
        val result = DiagnosticBundle.export(
            target = target,
            settings = AppSettings(proxyMode = ProxyMode.MANUAL, proxyHost = "127.0.0.1", proxyPort = 7890),
            accountCounts = mapOf("QUARK" to 2, "UC" to 1),
            appVersion = "1.5.5",
            logDir = logDir,
            dataDir = dataDir,
            now = now,
            environment = "app=1.5.5\nos=TestOS\n",
        )

        assertEquals(target, result)
        val entries = ZipFile(result.toFile()).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }
        assertTrue("manifest.txt" in entries)
        assertTrue("environment.txt" in entries)
        assertTrue("settings.txt" in entries)
        assertTrue("logs/app-2026-11-15.log" in entries)
        assertTrue("logs/app-2026-11-12.log" in entries, "7 天内的日志都要收")
        assertTrue("logs/jcef.log" in entries, "内嵌浏览器日志要收")
        assertTrue("crash/hs_err_pid4242.log" in entries)
        assertFalse("logs/app-2026-11-01.log" in entries, "超过 7 天的日志不进包")
        assertFalse(entries.any { it.contains("unrelated") }, "非日志文件不该被打包")

        val manifest = ZipFile(result.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry("manifest.txt")).bufferedReader().readText()
        }
        assertTrue(manifest.contains("QUARK=2"))
        assertTrue(manifest.contains("NO cloud-drive credentials"), "凭证文件仍应明确排除")
        // P1-07：声明必须与实现相符——日志确实会进包，要如实提示审阅
        assertTrue(manifest.contains("Application logs"), "manifest 必须说明日志会随包分发")
        assertTrue(manifest.contains("Review the logs before sharing"), "manifest 必须提示发送前审阅")
        assertTrue(manifest.contains("again at export time"), "manifest 要如实说明导出时再脱敏")
    }

    @Test
    fun every_log_line_is_redacted_again_when_the_bundle_is_exported() = withTempDirs { logDir, dataDir ->
        // 模拟 jcef / JVM 直写：绕开 AppLogger，落盘时没有任何脱敏
        Files.writeString(
            logDir.resolve("app-2026-11-15.log"),
            listOf(
                "[INFO] plain line",
                "[INFO] {\"accessToken\":\"AT-SECRET-1\",\"fileName\":\"a.mp4\"}",
                "[INFO] GET https://pan.example.com/api?token=URLSECRET&t=1",
                "[INFO] password=hunter2&user=alice",
            ).joinToString("\r\n"),
        )
        Files.writeString(logDir.resolve("jcef.log"), "[JCEF] captchaToken=CT-SECRET-2\n")
        val crashDir = DiagnosticBundle.crashDir(dataDir)
        Files.createDirectories(crashDir)
        Files.writeString(crashDir.resolve("hs_err_pid1.log"), "[err] api_key=CRASH-SECRET\n")

        val target = dataDir.resolve("diag.zip")
        DiagnosticBundle.export(
            target = target,
            settings = AppSettings(),
            logDir = logDir,
            dataDir = dataDir,
            now = now,
            environment = "app=test\n",
        )

        ZipFile(target.toFile()).use { zip ->
            fun entryText(name: String): String =
                zip.getInputStream(zip.getEntry(name)).bufferedReader(Charsets.UTF_8).readText()

            // 回归：曾因 Path 实现 Iterable 而被拆成 logs/Users/... 一堆空条目
            val stray = zip.entries().asSequence().map { it.name }
                .filter { it.startsWith("logs/") && it.count { c -> c == '/' } > 1 }.toList()
            assertTrue(stray.isEmpty(), "日志条目不应把绝对路径拆成多段: $stray")

            val app = entryText("logs/app-2026-11-15.log")
            assertTrue(app.contains("plain line"), "普通日志行不应被破坏")
            assertFalse(app.contains("AT-SECRET-1"))
            assertFalse(app.contains("URLSECRET"))
            assertFalse(app.contains("hunter2"))
            assertTrue(app.contains("accessToken\":\"***"), app)
            assertTrue(app.contains("?<redacted>"), app)

            val jcef = entryText("logs/jcef.log")
            assertTrue(jcef.isNotBlank(), "jcef entry should not be blank: [$jcef]")
            assertFalse(jcef.contains("CT-SECRET-2"))
            assertTrue(jcef.contains("captchaToken=***"), jcef)

            val crash = entryText("crash/hs_err_pid1.log")
            assertFalse(crash.contains("CRASH-SECRET"), "崩溃日志同样经过脱敏")
            assertTrue(crash.contains("api_key=***"), crash)
        }
    }

    @Test
    fun credentials_are_never_included_even_though_they_live_in_the_data_dir() = withTempDirs { logDir, dataDir ->
        // 数据目录里真实的凭证文件：诊断包只能收日志，不能整目录打包
        Files.writeString(dataDir.resolve("cloud-credentials.properties"), "QUARK=v1:aaa:bbb\n")
        Files.writeString(dataDir.resolve("cloud-credentials.key"), "0123456789abcdef0123456789abcdef")
        Files.writeString(dataDir.resolve("cloud-accounts.properties"), "acct|QUARK|a-1=v2:ccc:ddd\n")

        val target = dataDir.resolve("diag.zip")
        DiagnosticBundle.export(
            target = target,
            settings = AppSettings(),
            logDir = logDir,
            dataDir = dataDir,
            now = now,
            environment = "app=test\n",
        )

        val names = ZipFile(target.toFile()).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }
        assertFalse(names.any { it.contains("cloud-credentials") })
        assertFalse(names.any { it.contains("cloud-accounts") })
    }

    @Test
    fun settings_snapshot_redacts_the_home_directory_and_writes_expected_fields() = withTempDirs { _, _ ->
        val home = System.getProperty("user.home").orEmpty()
        val text = DiagnosticBundle.settingsSnapshot(
            AppSettings(downloadPath = "$home/Downloads", proxyMode = ProxyMode.SYSTEM, http2Enabled = true),
        )
        assertTrue(text.contains("proxyMode=SYSTEM"))
        assertTrue(text.contains("http2Enabled=true"))
        assertFalse(text.contains(home), "家目录要脱敏成 ~")
        assertTrue(text.contains("downloadPath=~/Downloads"))
    }

    @Test
    fun the_environment_snapshot_has_no_credentials_or_environment_variables() = withTempDirs { _, _ ->
        val text = DiagnosticBundle.environmentSnapshot("1.5.5", Path.of("/tmp/data"), Path.of("/tmp/logs"))
        assertTrue(text.contains("app=1.5.5"))
        assertTrue(text.contains("os="))
        // 刻意不 dump 环境变量：里面经常藏着 TOKEN / PROXY
        assertFalse(text.contains("PATH="))
        assertFalse(text.contains("TOKEN"))
    }

    @Test
    fun the_default_file_name_is_sortable_and_carries_no_path_separators() {
        val name = DiagnosticBundle.defaultFileName(now)
        assertTrue(name.startsWith("kt-diagnostics-"))
        assertTrue(name.endsWith(".zip"))
        assertFalse(name.contains("/") || name.contains("\\"))
    }

    @Test
    fun a_missing_log_directory_does_not_break_the_bundle() = withTempDirs { _, dataDir ->
        val target = dataDir.resolve("diag.zip")
        DiagnosticBundle.export(
            target = target,
            settings = AppSettings(),
            logDir = dataDir.resolve("does-not-exist"),
            dataDir = dataDir,
            now = now,
            environment = "app=test\n",
        )
        val names = ZipFile(target.toFile()).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }
        assertTrue("environment.txt" in names)
        assertFalse(names.any { it.startsWith("logs/") })
    }
}

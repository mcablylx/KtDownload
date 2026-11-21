package top.mcly99.dl.utils

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 崩溃留痕归档：只读源文件、复制而不是移动、重复启动不重复写。
 *
 * 这三条都关系到「排障材料会不会因为收集动作本身而消失」。
 */
class CrashLogsTest {

    private fun withTempDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("kt-crash-test")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun crash_logs_are_copied_and_the_sources_are_left_untouched() = withTempDir { base ->
        val source = Files.createDirectories(base.resolve("work"))
        val hotSpot = source.resolve("hs_err_pid1234.log")
        val replay = source.resolve("replay_pid1234.log")
        Files.writeString(hotSpot, "# A fatal error has been detected by the Java Runtime Environment\n")
        Files.writeString(replay, "replay data\n")
        Files.writeString(source.resolve("app.log"), "not a crash log\n")

        val archive = base.resolve("archive")
        val archived = CrashLogs.archive(listOf(source), archive)

        assertEquals(2, archived.size)
        assertTrue(Files.exists(archive.resolve("hs_err_pid1234.log")))
        assertTrue(Files.exists(archive.resolve("replay_pid1234.log")))
        assertTrue(Files.exists(hotSpot), "源文件必须保留：用户可能还要按原路径提交 issue")
        assertFalse(Files.exists(archive.resolve("app.log")), "普通日志不归档")
    }

    @Test
    fun re_running_archival_does_not_duplicate_the_same_file() = withTempDir { base ->
        val source = Files.createDirectories(base.resolve("work"))
        Files.writeString(source.resolve("hs_err_pid1.log"), "crash\n")
        val archive = base.resolve("archive")

        assertEquals(1, CrashLogs.archive(listOf(source), archive).size)
        assertEquals(0, CrashLogs.archive(listOf(source), archive).size, "同名同大小应跳过")
    }

    @Test
    fun a_newer_crash_file_with_the_same_name_replaces_the_archived_copy() = withTempDir { base ->
        val source = Files.createDirectories(base.resolve("work"))
        val file = source.resolve("hs_err_pid1.log")
        Files.writeString(file, "short\n")
        val archive = base.resolve("archive")
        CrashLogs.archive(listOf(source), archive)

        Files.writeString(file, "a much longer crash report\n")
        assertEquals(1, CrashLogs.archive(listOf(source), archive).size)
        assertEquals(
            "a much longer crash report\n",
            Files.readString(archive.resolve("hs_err_pid1.log")),
        )
    }

    @Test
    fun summarize_keeps_only_the_lines_that_help_locate_the_crash() = withTempDir { base ->
        val file = base.resolve("hs_err_pid9.log")
        Files.writeString(
            file,
            """
            # A fatal error has been detected by the Java Runtime Environment
            # SIGSEGV (0xb) at pc=0x0000000100000000
            siginfo: si_signo: 11 (SIGSEGV)
            Java VM: OpenJDK 64-Bit Server VM
            Problematic frame:
            C  [libjvm.dylib+0x1234]
            Command Line: -jar app.jar
            """.trimIndent(),
        )
        val summary = CrashLogs.summarize(file)
        assertTrue(summary.contains("fatal error"))
        assertTrue(summary.contains("siginfo:"))
        assertTrue(summary.contains("Problematic frame:"))
        assertTrue(summary.contains("Command Line:"))
        assertEquals(5, summary.split(" | ").size)
    }

    @Test
    fun unknown_file_names_are_not_treated_as_crash_logs() = withTempDir { base ->
        assertTrue(CrashLogs.isCrashLog(base.resolve("hs_err_pid1.log")))
        assertTrue(CrashLogs.isCrashLog(base.resolve("replay_pid1.log")))
        assertFalse(CrashLogs.isCrashLog(base.resolve("app-2026-11-15.log")))
        assertFalse(CrashLogs.isCrashLog(base.resolve("hs_err_pid1.txt")))
    }

    @Test
    fun a_missing_source_directory_is_not_an_error() = withTempDir { base ->
        assertTrue(CrashLogs.archive(listOf(base.resolve("nope")), base.resolve("archive")).isEmpty())
    }
}

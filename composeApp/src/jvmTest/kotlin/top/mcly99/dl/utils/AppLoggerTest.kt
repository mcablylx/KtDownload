package top.mcly99.dl.utils

import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLoggerTest {

    @Test
    fun prunes_only_expired_daily_logs() {
        val dir = Files.createTempDirectory("ktdown-logs")
        try {
            listOf(
                "app-2026-09-23.log",
                "app-2026-09-24.log",
                "app-2026-10-20.log",
                "app-bad.log",
                "other.txt",
            ).forEach { Files.writeString(dir.resolve(it), "x") }

            pruneOldLogs(dir, today = LocalDate.of(2026, 10, 24), retentionDays = 30)

            assertFalse(Files.exists(dir.resolve("app-2026-09-23.log")), "超过 30 天的日志应删除")
            assertTrue(Files.exists(dir.resolve("app-2026-09-24.log")), "恰好 30 天的日志应保留")
            assertTrue(Files.exists(dir.resolve("app-2026-10-20.log")))
            assertTrue(Files.exists(dir.resolve("app-bad.log")), "不符合命名规则的文件不应删除")
            assertTrue(Files.exists(dir.resolve("other.txt")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun pruning_missing_directory_is_a_noop() {
        pruneOldLogs(
            directory = java.nio.file.Paths.get("build", "definitely-missing-log-dir"),
            today = LocalDate.of(2026, 10, 24),
        )
    }
}

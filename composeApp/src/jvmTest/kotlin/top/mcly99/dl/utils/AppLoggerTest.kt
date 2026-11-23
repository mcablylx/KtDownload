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
    fun pruning_missing_directory_does_not_touch_other_files() {
        // 目录不存在时只能「什么都不做」：构造一个同命名规则、但位于别处的日志，
        // 断言它没有被误删（旧用例只调用不断言，实现改成乱删也能通过 —— P3-T6）
        val dir = Files.createTempDirectory("ktdown-logs-keep")
        try {
            val keep = dir.resolve("app-2020-01-01.log")
            Files.writeString(keep, "x")

            pruneOldLogs(
                directory = dir.resolve("definitely-missing-subdir"),
                today = LocalDate.of(2026, 10, 24),
                retentionDays = 30,
            )

            assertTrue(Files.exists(keep), "目标目录不存在时不得波及其它目录下的文件")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

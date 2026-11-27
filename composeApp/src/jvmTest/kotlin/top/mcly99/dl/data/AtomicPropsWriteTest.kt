package top.mcly99.dl.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import top.mcly99.dl.engine.TaskStore
import top.mcly99.dl.model.AppSettings
import top.mcly99.dl.model.DownloadStatus
import top.mcly99.dl.model.DownloadTask
import top.mcly99.dl.model.ProxyMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P1-06 / P2-05：配置与任务「固定 tmp + 无锁写盘」竞态的回归测试。
 *
 * 通过 `SettingsRepository.writeSettingsTo` / `TaskStore.writeTasksTo` 的 internal 路径注入
 * 临时目录，不碰 `~/.mclydownload`。核心断言是「并发写之后文件仍是某一次完整写入」，
 * 而不是两次写入的字段混合，且不留下任何 `.tmp` 残留。
 */
class AtomicPropsWriteTest {

    @Test
    fun concurrent_settings_writes_never_mix_fields_or_leave_tmp_files() = runBlocking {
        withTempDir("ktdl-settings-") { dir ->
            val target = dir.resolve("config.properties")
            val workers = 8
            val perWorker = 30
            val jobs = (0 until workers).map { worker ->
                launch(Dispatchers.Default) {
                    repeat(perWorker) { n ->
                        val id = worker * perWorker + n
                        SettingsRepository.writeSettingsTo(
                            target,
                            AppSettings(downloadPath = "p-$id", maxConcurrent = id + 1),
                        )
                    }
                }
            }
            jobs.joinAll()

            val loaded = SettingsRepository.readSettingsFrom(target, AppSettings())
            val id = loaded.downloadPath.removePrefix("p-").toInt()
            assertEquals(id + 1, loaded.maxConcurrent, "两次写入的字段不能混合")
            assertNoTmpLeftovers(dir)
        }
    }

    @Test
    fun concurrent_task_writes_keep_a_consistent_snapshot() = runBlocking {
        withTempDir("ktdl-tasks-") { dir ->
            val target = dir.resolve("tasks.properties")
            val jobs = (1..8).map { count ->
                launch(Dispatchers.Default) {
                    repeat(20) {
                        TaskStore.writeTasksTo(target, (1..count).map(::task))
                    }
                }
            }
            jobs.joinAll()

            val restored = TaskStore.readTasksFrom(target)
            val count = restored.size
            assertTrue(count in 1..8, "任务条数必须来自某一次完整写入，实际 $count")
            assertEquals(
                (1..count).map { "id-$it" },
                restored.map { it.id },
                "任务快照必须与某一次写入完全一致，不能两次写入混合",
            )
            assertNoTmpLeftovers(dir)
        }
    }

    @Test
    fun settings_round_trip_through_disk_preserves_unicode_and_legacy_proxy_flag() = runBlocking {
        withTempDir("ktdl-settings-rt-") { dir ->
            val target = dir.resolve("config.properties")
            val original = AppSettings(
                downloadPath = "D:/下载/第 3 集",
                proxyMode = ProxyMode.MANUAL,
                proxyHost = "127.0.0.1",
                proxyPort = 7890,
                maxConcurrent = 7,
            )
            SettingsRepository.writeSettingsTo(target, original)
            SettingsRepository.writeSettingsTo(target, original.copy(maxConcurrent = 9)) // 覆盖写一次

            val props = Properties()
            Files.newInputStream(target).use { props.load(it) }
            assertEquals("true", props.getProperty("proxyEnabled"), "旧版兼容键必须继续写")

            val loaded = SettingsRepository.readSettingsFrom(target, AppSettings())
            assertEquals("D:/下载/第 3 集", loaded.downloadPath)
            assertEquals(ProxyMode.MANUAL, loaded.proxyMode)
            assertEquals(7890, loaded.proxyPort)
            assertEquals(9, loaded.maxConcurrent, "第二次写入必须覆盖第一次")
            assertNoTmpLeftovers(dir)
        }
    }

    @Test
    fun reading_a_legacy_task_file_rewrites_it_without_deadlock_or_tmp_leftovers() = runBlocking {
        withTempDir("ktdl-tasks-legacy-") { dir ->
            val target = dir.resolve("tasks.properties")
            TaskStore.writeTasksTo(target, listOf(task(1)))
            // 模拟旧版本把会话 Cookie 明文写盘
            val props = Properties()
            Files.newInputStream(target).use { props.load(it) }
            props.setProperty("task.0.cookie", "SESSDATA=secret")
            Files.newOutputStream(target).use { props.store(it, "legacy") }

            // load 内部会再调 writeTasksTo：锁必须可重入，否则这里会永久卡住
            val restored = TaskStore.readTasksFrom(target)
            assertEquals(1, restored.size)
            val text = target.toFile().readText()
            assertFalse(text.contains("task.0.cookie="), "旧 Cookie 必须被重写擦除")
            assertNoTmpLeftovers(dir)
        }
    }

    private fun task(n: Int) = DownloadTask(
        id = "id-$n",
        url = "https://cdn.example.com/$n.mp4",
        fileName = "f-$n.mp4",
        savePath = Paths.get("out", "f-$n.mp4"),
        status = DownloadStatus.PAUSED,
        createTime = LocalDateTime.of(2026, 10, 28, 12, 0, 0),
    )

    private fun assertNoTmpLeftovers(dir: Path) {
        val leftovers = dir.toFile().listFiles { _, name -> name.endsWith(".tmp") }.orEmpty()
        assertTrue(leftovers.isEmpty(), "原子写不应留下 tmp 残留: ${leftovers.map { it.name }}")
    }

    private suspend fun withTempDir(prefix: String, block: suspend (Path) -> Unit) {
        val dir = Files.createTempDirectory(prefix)
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

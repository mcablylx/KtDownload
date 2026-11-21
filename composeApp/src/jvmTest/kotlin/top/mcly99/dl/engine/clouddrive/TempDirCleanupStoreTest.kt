package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.SharePlatform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 待清理临时目录的落盘清单（P1-18）。
 *
 * 存在的理由是「进程会退出」：退出时引擎把进行中的任务改成 PAUSED（不是终态），
 * 不会触发清理回调，内存清单随进程消失，网盘里的 `tr_*` 目录就再也回收不了。
 */
class TempDirCleanupStoreTest {

    private lateinit var dir: Path
    private lateinit var file: Path
    private lateinit var store: TempDirCleanupStore

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ktdown-cleanup-test")
        file = dir.resolve("pending_cleanups.txt")
        store = TempDirCleanupStore(file)
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun entries_survive_a_new_instance() {
        store.add(PendingCleanup(SharePlatform.QUARK, "tr_abc", "acct-1"))
        store.add(PendingCleanup(SharePlatform.BAIDU, "tr_def"))

        // 新实例 = 应用重启
        val reopened = TempDirCleanupStore(file).load()
        assertEquals(
            listOf(
                PendingCleanup(SharePlatform.QUARK, "tr_abc", "acct-1"),
                PendingCleanup(SharePlatform.BAIDU, "tr_def", ""),
            ),
            reopened,
        )
    }

    @Test
    fun the_same_directory_is_recorded_only_once() {
        val entry = PendingCleanup(SharePlatform.UC, "tr_same", "acct-2")
        store.add(entry)
        store.add(entry)
        store.add(entry)
        assertEquals(1, store.size())
    }

    @Test
    fun removing_one_entry_keeps_the_others() {
        val a = PendingCleanup(SharePlatform.QUARK, "tr_a", "acct-1")
        val b = PendingCleanup(SharePlatform.QUARK, "tr_b", "acct-1")
        store.add(a)
        store.add(b)

        store.remove(a)

        assertEquals(listOf(b), store.load())
        // 移除不存在的条目不报错、不改文件
        store.remove(PendingCleanup(SharePlatform.QUARK, "tr_zzz", "acct-1"))
        assertEquals(listOf(b), store.load())
    }

    @Test
    fun a_corrupted_line_does_not_destroy_the_whole_list() {
        // 手改坏一行（多一个换行 / 未知平台名），其余条目必须照常读回来
        store.add(PendingCleanup(SharePlatform.QUARK, "tr_good", "acct-1"))
        Files.write(
            file,
            buildString {
                append("# comment\n")
                append("NOT_A_PLATFORM\ttr_bad\tacct\n")
                append("QUARK\n") // 字段不足
                append("QUARK\ttr_good\tacct-1\n")
            }.toByteArray(),
        )

        assertEquals(listOf(PendingCleanup(SharePlatform.QUARK, "tr_good", "acct-1")), store.load())
    }

    @Test
    fun the_file_never_contains_credentials() {
        // 只允许出现「平台 / 目录 id / 账号 id」三列：凭证与直链（带签名）都不许落盘
        store.add(PendingCleanup(SharePlatform.XUNLEI, "tr_x", "acct-9"))
        val text = Files.readString(file)
        assertFalse(text.contains("cookie", ignoreCase = true), "清单里不应出现凭证字样")
        assertTrue(text.contains("XUNLEI\ttr_x\tacct-9"))
        assertTrue(text.lines().first().startsWith("#"), "带一行说明，避免用户误以为是配置")
    }

    @Test
    fun the_store_tolerates_a_missing_file() {
        assertEquals(emptyList(), store.load())
        assertEquals(0, store.size())
    }
}

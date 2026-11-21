package top.mcly99.dl.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 递归删除的危险路径闸门。
 *
 * 背景：上游 YunX-Desktop 曾把缓存目录放在 F 盘、用户改到 E 盘后，启动时的
 * 「旧目录清理」把 F 盘清空（v1.3.4 修复说明：「缓存 / 数据目录设成磁盘根目录
 * 会搬走整块盘……启动时的旧目录清理也会拒绝删除盘符根、当前数据目录的祖先」）。
 * 本项目没有可配置储存目录，但仍要把这道闸单独锁住 —— 否则以后加功能时会重演。
 */
class SafeDeleteTest {

    private fun withTempDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("kt-safedelete-test")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** 当前文件系统根：Unix 是 `/`，Windows 是 `C:\`。避免在测试里写死平台。 */
    private fun fileSystemRoot(): File =
        Paths.get("").toAbsolutePath().root.toFile()

    @Test
    fun deletes_a_plain_subdirectory_and_leaves_siblings_alone() = withTempDir { base ->
        val target = Files.createDirectories(base.resolve("movie.hls"))
        Files.writeString(target.resolve("seg_001.ts"), "a")
        Files.writeString(target.resolve("seg_002.ts"), "b")
        val sibling = Files.writeString(base.resolve("movie.mp4"), "keep me")

        assertTrue(SafeDelete.deleteTree(target.toFile(), "HLS 分片"))

        assertFalse(Files.exists(target))
        assertTrue(Files.exists(sibling), "同级文件不能被牵连")
    }

    @Test
    fun refuses_the_file_system_root() = withTempDir { _ ->
        val root = fileSystemRoot()
        // 只断言「判定」层面拒绝：绝对不能在测试里真的对根目录发起一次递归删除，
        // 万一闸门写错，这一行就是灾难。deleteTree 是否真的走这道判定，
        // 由下面的条目数用例与普通目录用例证明（它们都依赖同一个 refusalReason）。
        val reason = SafeDelete.refusalReason(root)
        assertNotNull(reason, "根目录必须有拒绝理由")
        assertTrue(root.exists(), "测试本身不能动到根目录")
    }

    @Test
    fun refuses_a_target_that_is_the_user_home_directory() = withTempDir { base ->
        val home = Files.createDirectories(base.resolve("home"))
        Files.writeString(home.resolve("重要文件.txt"), "不可删")

        val reason = SafeDelete.refusalReason(home.toFile(), home = home.toFile(), dataDir = base.resolve("data").toFile())

        assertNotNull(reason)
        assertTrue(reason.contains("主目录"), "拒绝理由要说清是家目录，实际：$reason")
    }

    @Test
    fun refuses_an_ancestor_of_the_app_data_dir() = withTempDir { base ->
        // 目标 = 数据目录的上级；删它等于把配置、账号池、任务列表一起删掉
        val parent = Files.createDirectories(base.resolve("parent"))
        val dataDir = Files.createDirectories(parent.resolve("data"))
        Files.writeString(dataDir.resolve("config.properties"), "downloadPath=/tmp")

        val reason = SafeDelete.refusalReason(parent.toFile(), home = base.resolve("home").toFile(), dataDir = dataDir.toFile())
        assertNotNull(reason)
        assertTrue(reason.contains("数据目录"), "拒绝理由要说清会连数据目录一起删，实际：$reason")

        // 数据目录自己的子目录是允许删的（例如 logs/crash 这类），否则诊断包归档会失效
        val child = Files.createDirectories(dataDir.resolve("logs").resolve("crash"))
        assertNull(SafeDelete.refusalReason(child.toFile(), home = base.resolve("home").toFile(), dataDir = dataDir.toFile()))
    }

    @Test
    fun refuses_when_the_entry_count_looks_like_a_miscalculated_path() = withTempDir { base ->
        val target = Files.createDirectories(base.resolve("huge.hls"))
        repeat(3) { i -> Files.writeString(target.resolve("seg_$i.ts"), "x") }

        // walk 会把目录自身算一条，所以这里是 4 条；上限设成 3 必须拒绝
        assertFalse(SafeDelete.deleteTree(target.toFile(), "HLS 分片", maxEntries = 3))
        assertTrue(Files.exists(target), "拒绝后目录必须原样保留")

        // 放宽上限后可以正常删掉，证明拒绝的只是「超限」这一条
        assertTrue(SafeDelete.deleteTree(target.toFile(), "HLS 分片", maxEntries = 4))
        assertFalse(Files.exists(target))
    }

    @Test
    fun a_missing_target_is_a_quiet_no_op() = withTempDir { base ->
        val missing = base.resolve("never-created.hls").toFile()
        assertFalse(SafeDelete.deleteTree(missing, "HLS 分片"))
        assertNull(SafeDelete.refusalReason(missing), "不存在的普通路径不该被当成危险路径")
    }

    @Test
    fun a_normal_subdirectory_has_no_refusal_reason() = withTempDir { base ->
        val target = Files.createDirectories(base.resolve("movie.hls"))
        assertNull(
            SafeDelete.refusalReason(
                target.toFile(),
                home = base.resolve("home").toFile(),
                dataDir = base.resolve("data").toFile(),
            ),
        )
    }

    @Test
    fun max_entries_default_is_generous_enough_for_real_hls_playlists() {
        // 2 小时视频按 1 秒一片是 7200 个文件；留一个数量级的余量
        assertEquals(50_000, SafeDelete.MAX_ENTRIES)
    }
}

package top.mcly99.dl.engine.clouddrive

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.mcly99.dl.data.network.SharePlatform
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * D-29：解析历史 / 链接收藏的本地存储。
 *
 * 全部使用临时目录，不读写真实的 ~/.mclydownload；损坏文件、坏条目、
 * 平台枚举变更都要静默降级，绝不能让「历史写失败」拖垮解析主流程。
 */
class CloudLinkLibraryTest {

    private val dirs = mutableListOf<Path>()
    private var now = 1_000L

    private fun dir(): Path = Files.createTempDirectory("ktdown-links-test").also { dirs.add(it) }
    private fun library(dir: Path) = CloudLinkLibrary(dir) { now++ }
    private fun fileOf(dir: Path): Path = dir.resolve("cloud-links.json")

    @AfterTest
    fun cleanup() {
        dirs.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    @Test
    fun round_trip_persists_history_and_bookmarks() {
        val dir = dir()
        library(dir).apply {
            recordResolved("https://pan.quark.cn/s/a?pwd=1234", "我的分享", SharePlatform.QUARK)
            addBookmark("https://pan.baidu.com/s/1abc", "百度资料", SharePlatform.BAIDU)
        }

        val reopened = CloudLinkLibrary(dir) { 99L }
        val entry = reopened.history().single()
        assertEquals("https://pan.quark.cn/s/a?pwd=1234", entry.url)
        assertEquals("我的分享", entry.title)
        assertEquals(SharePlatform.QUARK, entry.platform)
        assertTrue(entry.savedAt > 0)

        val mark = reopened.bookmarks().single()
        assertEquals("https://pan.baidu.com/s/1abc", mark.url)
        assertEquals("百度资料", mark.title)
        assertEquals(SharePlatform.BAIDU, mark.platform)
    }

    @Test
    fun history_is_deduplicated_newest_first_and_capped_at_30() {
        val dir = dir()
        val lib = library(dir)
        for (i in 0 until 35) {
            lib.recordResolved("https://pan.quark.cn/s/$i", "t$i", SharePlatform.QUARK)
        }
        lib.recordResolved("https://pan.quark.cn/s/34", "又解析了一次", SharePlatform.QUARK)

        val history = lib.history()
        assertEquals(30, history.size)
        assertEquals("https://pan.quark.cn/s/34", history.first().url)
        assertEquals("又解析了一次", history.first().title)
        assertEquals(history.size, history.map { it.url }.toSet().size)
        // 重新打开后上限依然成立
        assertEquals(30, CloudLinkLibrary(dir) { 1L }.history().size)
    }

    @Test
    fun toggle_bookmark_adds_refreshes_then_removes() {
        val dir = dir()
        val lib = library(dir)
        val url = "https://pan.quark.cn/s/bookmark"

        assertTrue(lib.addBookmark(url, "初版标题", null))
        assertFalse(lib.addBookmark(url, "新标题", null))
        assertTrue(lib.isBookmarked(url))
        assertEquals("新标题", lib.bookmarks().single().title)

        assertFalse(lib.toggleBookmark(url, " ignored ", null))
        assertTrue(lib.bookmarks().isEmpty())
        assertFalse(lib.isBookmarked(url))
    }

    @Test
    fun removing_one_entry_keeps_the_rest_and_clearing_everything_deletes_the_file() {
        val dir = dir()
        val lib = library(dir)
        lib.recordResolved("https://pan.quark.cn/s/1", "a", null)
        lib.recordResolved("https://pan.quark.cn/s/2", "b", null)
        lib.addBookmark("https://pan.quark.cn/s/1", "a", null)

        lib.removeHistory("https://pan.quark.cn/s/1")
        assertEquals(listOf("https://pan.quark.cn/s/2"), lib.history().map { it.url })
        lib.removeBookmark("https://pan.quark.cn/s/1")
        assertTrue(lib.bookmarks().isEmpty())
        assertTrue(Files.isRegularFile(fileOf(dir)))

        lib.clearHistory()
        assertTrue(lib.history().isEmpty())
        assertFalse(Files.exists(fileOf(dir)))
    }

    @Test
    fun corrupt_files_and_bad_entries_degrade_gracefully() {
        val dir = dir()
        Files.write(fileOf(dir), "{ definitely not json".toByteArray(StandardCharsets.UTF_8))
        val broken = library(dir)
        assertTrue(broken.history().isEmpty())
        assertTrue(broken.bookmarks().isEmpty())

        // 半损坏：空 url、缺字段、未知平台枚举、非对象元素混在一起
        Files.write(
            fileOf(dir),
            """{"history":[{"url":""},{"url":"https://x.example/s/1","title":"ok","platform":"NO_SUCH","savedAt":5},42],"bookmarks":null}"""
                .toByteArray(StandardCharsets.UTF_8),
        )
        val partial = CloudLinkLibrary(dir) { 2L }
        assertEquals(1, partial.history().size)
        assertEquals("https://x.example/s/1", partial.history().single().url)
        assertNull(partial.history().single().platform)
        assertTrue(partial.bookmarks().isEmpty())

        // 降级后仍可正常写入并覆盖坏文件
        partial.recordResolved("https://x.example/s/2", "fresh", SharePlatform.UC)
        assertEquals(2, CloudLinkLibrary(dir) { 3L }.history().size)
    }

    @Test
    fun blank_urls_are_ignored_without_creating_a_file() {
        val dir = dir()
        val lib = library(dir)
        lib.recordResolved("   ", "t", null)
        assertFalse(lib.addBookmark("", "t", null))
        assertTrue(lib.history().isEmpty())
        assertTrue(lib.bookmarks().isEmpty())
        assertFalse(Files.exists(fileOf(dir)))
    }

    @Test
    fun bookmark_list_is_capped_at_100() {
        val dir = dir()
        val lib = library(dir)
        for (i in 0 until 110) {
            lib.addBookmark("https://pan.quark.cn/s/$i", "t$i", null)
        }
        assertEquals(100, lib.bookmarks().size)
        assertEquals("https://pan.quark.cn/s/109", lib.bookmarks().first().url)
        assertEquals(100, CloudLinkLibrary(dir) { 1L }.bookmarks().size)
    }

    @Test
    fun link_file_is_owner_only_on_posix_filesystems() {
        val dir = dir()
        library(dir).recordResolved("https://pan.quark.cn/s/secret?pwd=abcd", "t", null)
        val file = fileOf(dir)
        if (!Files.getFileStore(file).supportsFileAttributeView("posix")) return

        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file),
        )
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            Files.getPosixFilePermissions(dir),
        )
    }
}

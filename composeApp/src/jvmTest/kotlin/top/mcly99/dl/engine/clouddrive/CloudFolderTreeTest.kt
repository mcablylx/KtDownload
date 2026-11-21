package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.model.ShareFile
import top.mcly99.dl.data.network.model.ShareSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「下载整个文件夹」的递归展开逻辑单测。
 *
 * 用只替换 `listFiles` 的 stub 调 [collectTree]，不发任何真实网络请求，
 * 覆盖多层目录、失败目录跳过、自引用死循环与路径穿越。
 */
class CloudFolderTreeTest {

    private val session = ShareSession("abc123", "stoken", "测试分享")

    @Test
    fun collects_every_level_and_keeps_relative_paths() = runTest {
        val api = TreeStub(
            mapOf(
                "root" to listOf(folder("A", "a"), file("top.mp4")),
                "a" to listOf(folder("B", "b"), file("a.mp4")),
                "b" to listOf(file("b.mp4")),
            ),
        )

        val result = api.collectTree(session, "root", "", SharePlatform.QUARK, "分享")

        assertEquals(3, result.entries.size)
        assertTrue(result.failedDirs.isEmpty())
        assertEquals("分享", result.entries.first { it.file.name == "top.mp4" }.relativeDir)
        assertEquals("分享/A", result.entries.first { it.file.name == "a.mp4" }.relativeDir)
        assertEquals("分享/A/B", result.entries.first { it.file.name == "b.mp4" }.relativeDir)
        assertEquals(3, api.listCalls)
    }

    @Test
    fun failing_subdirectory_is_skipped_without_dropping_other_files() = runTest {
        val api = TreeStub(
            mapOf(
                "root" to listOf(folder("Bad", "bad"), folder("Good", "good"), file("top.mp4")),
                "good" to listOf(file("good.mp4")),
            ),
            failing = setOf("bad"),
        )

        val result = api.collectTree(session, "root", "", SharePlatform.QUARK, "分享")

        assertEquals(listOf("分享/Bad"), result.failedDirs)
        assertEquals(setOf("top.mp4", "good.mp4"), result.entries.map { it.file.name }.toSet())
    }

    @Test
    fun self_referencing_directory_does_not_loop_forever() = runTest {
        val api = TreeStub(
            mapOf("root" to listOf(folder("Loop", "root"))),
        )

        val result = api.collectTree(session, "root", "", SharePlatform.QUARK, "分享")

        assertTrue(result.entries.isEmpty())
        assertEquals(1, api.listCalls)
    }

    @Test
    fun directory_names_cannot_escape_the_download_root() = runTest {
        assertEquals("文件夹", sanitizeDirName(".."))
        assertEquals("a_b", sanitizeDirName("a/b"))
        assertEquals("a_b", sanitizeDirName("a\\b"))
        assertFalse(sanitizeDirName("..\\..\\Windows").contains(".."))
        assertFalse(sanitizeDirName("ev:il").contains(':'))
    }

    @Test
    fun file_cap_stops_runaway_shares() = runTest {
        val many = (1..(MAX_FOLDER_FILES + 20)).map { file("f$it.mp4") }
        val api = TreeStub(mapOf("root" to many))

        val result = api.collectTree(session, "root", "", SharePlatform.QUARK, "分享")

        assertEquals(MAX_FOLDER_FILES, result.entries.size)
        assertTrue(result.truncated, "触到文件数上限要标记截断，UI 才能提示只下了一部分")
    }

    @Test
    fun depth_cap_marks_result_as_truncated() = runTest {
        // 造一条 30 层的目录链，超过 MAX_FOLDER_DEPTH
        val dirs = (0..30).associate { i ->
            "d$i" to listOf(folder("L${i + 1}", "d${i + 1}"))
        }
        val api = TreeStub(dirs)

        val result = api.collectTree(session, "d0", "", SharePlatform.QUARK, "分享")

        assertTrue(result.entries.isEmpty())
        assertTrue(result.truncated)
    }

    // ---------------------------------------------------------------- 辅助

    private fun file(name: String) = ShareFile(
        fid = "f-$name",
        fname = name,
        fsize = 1024L,
        isdir = false,
        pdirFid = "0",
        fidToken = "t",
    )

    private fun folder(name: String, fid: String) = ShareFile(
        fid = fid,
        fname = name,
        fsize = 0,
        isdir = true,
        pdirFid = "0",
        fidToken = "t",
    )

    private class TreeStub(
        private val dirs: Map<String, List<ShareFile>>,
        private val failing: Set<String> = emptySet(),
    ) : CloudDriveApi by CloudDriveService() {
        var listCalls = 0
            private set

        override suspend fun listFiles(
            session: ShareSession,
            dirFid: String,
            credential: String,
            platform: SharePlatform,
        ): List<ShareFile> {
            listCalls++
            if (dirFid in failing) throw IllegalStateException("模拟目录读取失败")
            return dirs[dirFid].orEmpty()
        }
    }
}

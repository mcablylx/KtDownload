package top.mcly99.dl.engine

import top.mcly99.dl.utils.AppConstants
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadFileSupportTest {

    @Test
    fun extracts_file_name_and_ignores_query_and_fragment() {
        assertEquals(
            "file.zip",
            extractFileName("https://example.com/download/file.zip?token=1&x=2#section"),
        )
        assertEquals(
            "no-extension",
            extractFileName("https://example.com/files/no-extension"),
        )
    }

    @Test
    fun falls_back_for_trailing_slash_and_invalid_url() {
        assertEquals("download_123", extractFileName("https://example.com/", fallbackTimeMillis = 123))
        assertEquals("download_456", extractFileName("not a url", fallbackTimeMillis = 456))
        assertEquals("download_789", extractFileName("", fallbackTimeMillis = 789))
    }

    @Test
    fun decodes_percent_escapes_and_keeps_literal_plus() {
        assertEquals(
            "中文 文档.zip",
            extractFileName("https://example.com/%E4%B8%AD%E6%96%87%20%E6%96%87%E6%A1%A3.zip"),
        )
        assertEquals("a+b.txt", extractFileName("https://example.com/a+b.txt"))
    }

    @Test
    fun encoded_slash_cannot_escape_the_target_directory() {
        assertEquals("b.txt", extractFileName("https://example.com/a%2Fb.txt"))
        assertEquals("b.txt", extractFileName("https://example.com/%2E%2E%2Fb.txt"))
    }

    @Test
    fun sanitizes_illegal_and_reserved_names() {
        assertEquals("_CON.txt", extractFileName("https://example.com/CON.txt"))
        assertEquals("a_b_.txt", extractFileName("https://example.com/a%3Ab%3F.txt"))
    }

    @Test
    fun long_names_are_truncated_but_keep_extension() {
        val name = extractFileName("https://example.com/${"a".repeat(300)}.zip")
        assertTrue(name.length <= AppConstants.MAX_DOWNLOAD_FILE_NAME_LENGTH)
        assertTrue(name.endsWith(".zip"))
    }

    @Test
    fun resolves_only_existing_directories_as_directories() {
        val dir = Files.createTempDirectory("ktdown-resolve")
        try {
            val defaultDir = dir.resolve("default")
            assertEquals(defaultDir.resolve("a.bin"), resolveTaskPath(null, "a.bin", defaultDir))
            assertEquals(dir.resolve("a.bin"), resolveTaskPath(dir, "a.bin", defaultDir))

            // 不存在的无点路径按完整文件路径处理，不再被误判为目录
            val filePath = dir.resolve("nodot")
            assertEquals(filePath, resolveTaskPath(filePath, "a.bin", defaultDir))

            filePath.toFile().writeText("x")
            assertEquals(filePath, resolveTaskPath(filePath, "a.bin", defaultDir))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun duplicate_file_appends_incrementing_suffix() {
        val dir = Files.createTempDirectory("ktdown-duplicate")
        try {
            val target = dir.resolve("report.pdf")
            assertEquals(target, handleDuplicateFile(target))

            target.toFile().writeText("first")
            assertEquals(dir.resolve("report(1).pdf"), handleDuplicateFile(target))

            Files.writeString(dir.resolve("report(1).pdf"), "second")
            assertEquals(dir.resolve("report(2).pdf"), handleDuplicateFile(target))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun duplicate_file_without_extension_keeps_plain_name() {
        val dir = Files.createTempDirectory("ktdown-duplicate-noext")
        try {
            val target = dir.resolve("README")
            target.toFile().writeText("first")
            assertEquals(dir.resolve("README(1)"), handleDuplicateFile(target))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun duplicate_hidden_file_does_not_lose_leading_dot() {
        val dir = Files.createTempDirectory("ktdown-duplicate-hidden")
        try {
            val target = dir.resolve(".gitignore")
            target.toFile().writeText("first")
            assertEquals(dir.resolve(".gitignore(1)"), handleDuplicateFile(target))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun chunk_ranges_cover_uneven_size_without_gaps_or_overlap() {
        val ranges = buildChunkRanges(totalSize = 1_000L, requestedThreads = 3)

        assertEquals(3, ranges.size)
        assertEquals(0L, ranges.first().first)
        assertEquals(999L, ranges.last().last)
        ranges.zipWithNext().forEach { (left, right) ->
            assertEquals(left.last + 1L, right.first)
        }
        assertEquals(1_000L, ranges.sumOf { it.last - it.first + 1L })
    }

    @Test
    fun chunk_count_is_capped_when_threads_exceed_file_size() {
        val ranges = buildChunkRanges(totalSize = 3L, requestedThreads = 64)

        assertEquals(2, ranges.size)
        assertTrue(ranges.all { !it.isEmpty() })
        assertEquals(3L, ranges.sumOf { it.last - it.first + 1L })
    }

    @Test
    fun zero_size_has_no_chunks() {
        assertTrue(buildChunkRanges(totalSize = 0L, requestedThreads = 8).isEmpty())
        assertTrue(buildChunkRanges(totalSize = -1L, requestedThreads = 8).isEmpty())
    }

    @Test
    fun large_file_plan_does_not_overflow_int() {
        val totalSize = 5_000_000_000L
        val ranges = buildChunkRanges(totalSize = totalSize, requestedThreads = 1_000)

        assertEquals(AppConstants.MAX_THREADS, ranges.size)
        assertEquals(totalSize, ranges.sumOf { it.last - it.first + 1L })
    }

    @Test
    fun duplicate_check_can_consider_extra_occupancy() {
        val dir = Files.createTempDirectory("ktdown-dup").toFile()
        try {
            val root = dir.toPath()
            val base = root.resolve("movie.mp4")
            val taken = setOf(base, root.resolve("movie(1).mp4"))

            assertEquals(
                root.resolve("movie(2).mp4"),
                handleDuplicateFile(base) { it in taken },
                "占用判断命中的路径也应跳过",
            )
            assertEquals(
                root.resolve("free.mp4"),
                handleDuplicateFile(root.resolve("free.mp4")) { it in taken },
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun hls_output_is_always_mp4_and_segment_dir_drops_extension() {
        assertEquals(Paths.get("/tmp/movie.mp4"), ensureHlsOutputPath(Paths.get("/tmp/movie.m3u8")))
        assertEquals(Paths.get("/tmp/movie.mp4"), ensureHlsOutputPath(Paths.get("/tmp/movie")))
        assertEquals(Paths.get("/tmp/movie.mp4"), ensureHlsOutputPath(Paths.get("/tmp/movie.MKV")))
        // 已经是 mp4 时（含大写后缀）按「原样保留用户文件名」处理，不做大小写归一化
        assertEquals(Paths.get("/tmp/movie.MP4"), ensureHlsOutputPath(Paths.get("/tmp/movie.MP4")))

        assertEquals(
            File("/tmp/movie.hls"),
            hlsSegmentDir(File("/tmp/movie.mp4")),
        )
        assertEquals(
            File("/tmp/movie.hls"),
            hlsSegmentDir(File("/tmp/movie")),
        )
    }

    @Test
    fun segment_dir_of_a_root_target_does_not_fall_back_to_the_process_cwd() {
        // 任务目标若是盘符根 / 文件系统根（name 为空、parentFile 为 null），旧实现会得到
        // File(null, ".hls") —— 即**相对进程工作目录**的 ".hls"，分片会写到与本任务无关的地方，
        // 后续清理也会指错目标。这里锁住「同盘根目录下的 .hls」这个兜底。
        val root = Paths.get("").toAbsolutePath().root.toFile()
        assertEquals(File(root, ".hls"), hlsSegmentDir(root))
    }

    @Test
    fun preview_file_name_matches_what_add_task_would_save() {
        // 添加任务对话框用这个函数做「文件名自动填充」，必须与 addTask 的推导一致（v6-P2-1）
        assertEquals("movie.mp4", previewFileName("https://cdn.example.com/v/movie.mp4"))
        assertEquals("a.mkv", previewFileName("https://cdn.example.com/v/a.mkv?token=1"))
        // HLS 一律显示合成后的 .mp4，不是 .m3u8
        assertEquals("index.mp4", previewFileName("https://cdn.example.com/hls/index.m3u8?wsSecret=x"))
        assertEquals("高清.mp4", previewFileName("https://cdn.example.com/v/%E9%AB%98%E6%B8%85.mp4"))
    }

    @Test
    fun preview_file_name_is_sanitized_like_the_engine() {
        // 保留名与非法字符的清洗规则只能有一份实现
        assertEquals("_CON.txt", previewFileName("https://cdn.example.com/v/CON.txt"))
        assertEquals("a_b.mp4", previewFileName("https://cdn.example.com/v/a:b.mp4"))
    }
}

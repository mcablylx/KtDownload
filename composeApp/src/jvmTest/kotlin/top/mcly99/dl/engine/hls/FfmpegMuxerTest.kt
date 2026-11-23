package top.mcly99.dl.engine.hls

import kotlinx.coroutines.runBlocking
import top.mcly99.dl.utils.FfmpegLocator
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [FfmpegMuxer] 测试。
 *
 * - fMP4 快路径：拼出带 `ftyp`/`moov` 的 MP4，成功后清理分片目录；
 * - 无 ffmpeg：返回 [HlsMuxResult.SegmentsOnly] 并保留分片；
 * - 非法 fMP4 / 分片结构不符：快路径失败后同样降级，临时输出被清理；
 * - ffmpeg 存在但失败/超时：返回 [HlsMuxResult.Failed] 并保留分片（v3-P2-5/P2-6）；
 * - 真实 ffmpeg（存在时）：TS 分片必须能转封装为可播放 MP4，覆盖 v3-P0-1。
 */
class FfmpegMuxerTest {

    private lateinit var tempDir: File

    /** 最小合法 fMP4 头：`ftyp` + `moov` box */
    private val ftypBytes = box("ftyp", "isom".toByteArray() + ByteArray(4))
    private val initData = ftypBytes + box("moov")
    private val segmentData = List(2) { i ->
        box("moof", ByteArray(16) { b -> (i * 31 + b).toByte() }) +
            box("mdat", ByteArray(32) { b -> (i * 17 + b).toByte() })
    }

    @BeforeTest
    fun setUp() {
        // 放在 build/ 下而不是系统临时目录：ffmpeg 子进程在受限环境下可能无法写入 %TEMP%
        val root = File("build/tmp").apply { mkdirs() }
        tempDir = Files.createTempDirectory(root.toPath(), "ktdown-muxer-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun fmp4_fast_path_concatenates_and_removes_segments() = runBlocking {
        val segmentsDir = File(tempDir, "movie.hls")
        val result = resultWith(segmentsDir, initData, segmentData)
        val output = File(tempDir, "movie.mp4")

        val mux = FfmpegMuxer(ffmpegPath = null).mux(result, output)

        assertIs<HlsMuxResult.Mp4>(mux)
        assertEquals(output, mux.file)
        assertContentEquals(initData + segmentData[0] + segmentData[1], output.readBytes())
        assertFalse(segmentsDir.exists(), "默认应清理分片目录")
    }

    @Test
    fun keep_segments_option_preserves_the_work_directory() = runBlocking {
        val segmentsDir = File(tempDir, "keep.hls")
        val result = resultWith(segmentsDir, initData, segmentData)
        val output = File(tempDir, "keep.mp4")

        val mux = FfmpegMuxer(ffmpegPath = null).mux(result, output, keepSegments = true)

        assertIs<HlsMuxResult.Mp4>(mux)
        assertTrue(segmentsDir.exists(), "keepSegments=true 时应保留分片")
    }

    @Test
    fun missing_ffmpeg_keeps_segments_and_reports_reason() = runBlocking {
        val segmentsDir = File(tempDir, "ts.hls")
        val result = resultWith(segmentsDir, init = null, segmentData)
        val output = File(tempDir, "ts.mp4")

        val mux = FfmpegMuxer(ffmpegPath = null).mux(result, output)

        assertIs<HlsMuxResult.SegmentsOnly>(mux)
        assertTrue(mux.reason.contains("ffmpeg"), mux.reason)
        assertEquals(segmentsDir, mux.directory)
        assertTrue(segmentsDir.exists(), "降级时必须保留分片")
        assertFalse(output.exists(), "没有 ffmpeg 时不应产出空 mp4")
        assertFalse(File(tempDir, "ts.mp4.tmp").exists(), "临时输出应被清理")
    }

    @Test
    fun invalid_fmp4_falls_back_without_leaving_temporary_file() = runBlocking {
        val segmentsDir = File(tempDir, "bad.hls")
        // 初始化段不是 ISO BMFF：快路径校验失败，应交给 ffmpeg（此处不可用）
        val result = resultWith(segmentsDir, init = ByteArray(16) { 1 }, segmentData)
        val output = File(tempDir, "bad.mp4")

        val mux = FfmpegMuxer(ffmpegPath = null).mux(result, output)

        assertIs<HlsMuxResult.SegmentsOnly>(mux)
        assertTrue(segmentsDir.exists())
        assertFalse(output.exists())
        assertFalse(File(tempDir, "bad.mp4.tmp").exists())
    }

    @Test
    fun init_without_moov_does_not_take_fast_path() = runBlocking {
        val segmentsDir = File(tempDir, "no-moov.hls")
        // 只有 ftyp、没有 moov：不是可播放的初始化段
        val result = resultWith(segmentsDir, init = ftypBytes, segments = segmentData)
        val output = File(tempDir, "no-moov.mp4")

        val mux = FfmpegMuxer(ffmpegPath = null).mux(result, output)

        assertIs<HlsMuxResult.SegmentsOnly>(mux)
        assertTrue(segmentsDir.exists())
        assertFalse(output.exists())
        assertFalse(File(tempDir, "no-moov.mp4.tmp").exists())
    }

    @Test
    fun ts_segments_with_valid_init_do_not_take_fast_path() = runBlocking {
        val segmentsDir = File(tempDir, "ts-in-fmp4.hls")
        // 初始化段合法，但分片是 TS：直接拼接会得到无法播放的文件
        val tsSegments = List(2) { i ->
            ByteArray(188) { b -> if (b == 0) 0x47 else (i + b).toByte() }
        }
        val result = resultWith(segmentsDir, init = initData, segments = tsSegments)
        val output = File(tempDir, "ts-in-fmp4.mp4")

        val mux = FfmpegMuxer(ffmpegPath = null).mux(result, output)

        assertIs<HlsMuxResult.SegmentsOnly>(mux)
        assertTrue(segmentsDir.exists())
        assertFalse(output.exists(), "分片结构不符时不能走快路径产出成品")
        assertFalse(File(tempDir, "ts-in-fmp4.mp4.tmp").exists())
    }

    @Test
    fun ffmpeg_failure_returns_failed_and_keeps_segments() = runBlocking {
        val segmentsDir = File(tempDir, "fail.hls")
        val result = resultWith(segmentsDir, init = null, segments = segmentData)
        val output = File(tempDir, "fail.mp4")

        val mux = FfmpegMuxer(ffmpegPath = script(
            name = "fake-ffmpeg-fail",
            windows = "@echo off\r\necho fake ffmpeg failure 1>&2\r\nexit /b 1\r\n",
            unix = "#!/bin/sh\necho fake ffmpeg failure >&2\nexit 1\n",
        )).mux(result, output)

        assertIs<HlsMuxResult.Failed>(mux)
        assertTrue(mux.reason.contains("退出码"), mux.reason)
        assertEquals(segmentsDir, mux.directory)
        assertTrue(segmentsDir.exists(), "失败时必须保留分片以便重试")
        assertFalse(output.exists())
        assertFalse(File(tempDir, "fail.mp4.tmp").exists())
    }

    @Test
    fun ffmpeg_timeout_returns_failed() = runBlocking {
        val segmentsDir = File(tempDir, "timeout.hls")
        val result = resultWith(segmentsDir, init = null, segments = segmentData)
        val output = File(tempDir, "timeout.mp4")

        val mux = FfmpegMuxer(
            ffmpegPath = script(
                name = "fake-ffmpeg-sleep",
                windows = "@echo off\r\nping -n 6 127.0.0.1 >nul\r\nexit /b 0\r\n",
                unix = "#!/bin/sh\nsleep 5\nexit 0\n",
            ),
            ffmpegTimeoutMs = 300,
        ).mux(result, output)

        assertIs<HlsMuxResult.Failed>(mux)
        assertTrue(mux.reason.contains("超时"), mux.reason)
        assertTrue(segmentsDir.exists())
        assertFalse(output.exists())
        assertFalse(File(tempDir, "timeout.mp4.tmp").exists())
    }

    @Test
    fun concat_file_escapes_single_quotes_in_paths() {
        val file = File(tempDir, "a'b.ts")
        val expected = file.absolutePath.replace("'", "'\\''")

        val content = FfmpegMuxer(ffmpegPath = null).buildConcatFile(listOf(file))

        assertEquals("file '$expected'\n", content)
    }

    @Test
    fun concat_file_rejects_paths_with_newlines() {
        val muxer = FfmpegMuxer(ffmpegPath = null)

        listOf("bad\nname.ts", "bad\rname.ts").forEach { name ->
            val error = assertFailsWith<HlsException> {
                muxer.buildConcatFile(listOf(File(tempDir, name)))
            }
            assertTrue(error.reason.contains("换行"), error.reason)
        }
    }

    /**
     * v3-P0-1 回归：真实 ffmpeg 必须能把 TS 分片转封装为可播放 MP4。
     *
     * 临时输出名是 `movie.mp4.tmp`，ffmpeg 无法从扩展名推断格式；
     * 命令缺少 `-f mp4` 时会报 `Unable to choose an output format`。
     * 没有 ffmpeg 的环境（如普通 CI verify）自动跳过。
     */
    @Test
    fun real_ffmpeg_muxes_ts_segments_to_playable_mp4() = runBlocking {
        // 早期用 org.junit.Assume 表达「环境不具备则跳过」；统一到 kotlin.test 后
        // 改成提前 return —— 没有 ffmpeg（或缺 lavfi/mpeg4 编码器）时不产生假失败（P3-T4）
        val ffmpegBinary = locateFfmpeg() ?: return@runBlocking
        if (!canGenerateTestClip(ffmpegBinary)) return@runBlocking

        val segmentsDir = File(tempDir, "real.hls").apply { mkdirs() }
        val segments = List(2) { index ->
            File(segmentsDir, "seg_${index.toString().padStart(5, '0')}.ts").also { file ->
                val exit = runProcess(
                    ffmpegBinary,
                    listOf(
                        "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                        "-f", "lavfi", "-i", "testsrc=duration=1:size=64x64:rate=10",
                        "-f", "lavfi", "-i", "sine=duration=1",
                        "-c:v", "mpeg4", "-c:a", "aac",
                        "-f", "mpegts", file.absolutePath,
                    ),
                )
                assertEquals(0, exit, "生成测试 TS 分片失败")
            }
        }

        val media = HlsPlaylist.Media(
            segments = segments.mapIndexed { index, _ ->
                HlsSegment(uri = "", duration = 1.0, sequence = index)
            },
        )
        val result = HlsDownloadResult(
            media = media,
            segments = segments,
            initSegment = null,
            directory = segmentsDir,
        )
        val output = File(tempDir, "real.mp4")

        val mux = FfmpegMuxer(ffmpegPath = ffmpegBinary).mux(result, output)

        assertIs<HlsMuxResult.Mp4>(mux)
        assertTrue(output.length() > 0, "应产出非空 MP4")
        val probeExit = runProcess(
            ffmpegBinary,
            listOf(
                "-nostdin", "-hide_banner", "-loglevel", "error",
                "-i", output.absolutePath,
                "-f", "null", "-",
            ),
        )
        assertEquals(0, probeExit, "合成结果应可被 ffmpeg 读取")
    }

    @Test
    fun fmp4_with_separate_audio_skips_fast_path() = runBlocking {
        val segmentsDir = File(tempDir, "audio-fast.hls")
        val result = resultWith(
            segmentsDir,
            init = initData,
            segments = segmentData,
            audioSegments = listOf(ByteArray(32) { it.toByte() }),
        )
        val output = File(tempDir, "audio-fast.mp4")

        // 直拼 fMP4 会丢掉独立音轨，因此即使初始化段合法也必须交给 ffmpeg
        val mux = FfmpegMuxer(ffmpegPath = null).mux(result, output)

        assertIs<HlsMuxResult.SegmentsOnly>(mux)
        assertFalse(output.exists())
        assertTrue(segmentsDir.exists())
    }

    /**
     * v3-P1-3 回归：视频与独立音轨分属两路输入时，必须用 `-map` 把两者都写进 MP4。
     * 没有 ffmpeg 的环境自动跳过。
     */
    @Test
    fun real_ffmpeg_muxes_video_with_separate_audio_track() = runBlocking {
        val ffmpegBinary = locateFfmpeg() ?: return@runBlocking
        if (!canGenerateTestClip(ffmpegBinary)) return@runBlocking

        val segmentsDir = File(tempDir, "real-audio.hls").apply { mkdirs() }
        val videoSegments = List(2) { index ->
            File(segmentsDir, "seg_${index.toString().padStart(5, '0')}.ts").also { file ->
                assertEquals(
                    0,
                    runProcess(
                        ffmpegBinary,
                        listOf(
                            "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                            "-f", "lavfi", "-i", "testsrc=duration=1:size=64x64:rate=10",
                            "-c:v", "mpeg4", "-an",
                            "-f", "mpegts", file.absolutePath,
                        ),
                    ),
                    "生成视频分片失败",
                )
            }
        }
        val audioDir = File(segmentsDir, "audio").apply { mkdirs() }
        val audioSegments = List(2) { index ->
            File(audioDir, "seg_${index.toString().padStart(5, '0')}.ts").also { file ->
                assertEquals(
                    0,
                    runProcess(
                        ffmpegBinary,
                        listOf(
                            "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                            "-f", "lavfi", "-i", "sine=duration=1",
                            "-c:a", "aac", "-vn",
                            "-f", "mpegts", file.absolutePath,
                        ),
                    ),
                    "生成音轨分片失败",
                )
            }
        }

        val media = HlsPlaylist.Media(
            segments = videoSegments.mapIndexed { index, _ ->
                HlsSegment(uri = "", duration = 1.0, sequence = index)
            },
        )
        val result = HlsDownloadResult(
            media = media,
            segments = videoSegments,
            initSegment = null,
            audioSegments = audioSegments,
            audioInitSegment = null,
            directory = segmentsDir,
        )
        val output = File(tempDir, "real-audio.mp4")

        val mux = FfmpegMuxer(ffmpegPath = ffmpegBinary).mux(result, output)

        assertIs<HlsMuxResult.Mp4>(mux)
        val (_, probeLog) = runProcessCapture(
            ffmpegBinary,
            listOf("-nostdin", "-hide_banner", "-i", output.absolutePath),
        )
        assertTrue(probeLog.contains("Video:"), "合成结果应包含视频流：\n$probeLog")
        assertTrue(probeLog.contains("Audio:"), "合成结果应包含独立音轨：\n$probeLog")
    }

    /**
     * 优先使用 [FfmpegLocator]（PATH / 环境变量 / 安装包资源）；
     * 开发环境把 ffmpeg 放在仓库的 `resources/<平台>/ffmpeg`（`windows` / `macos`），
     * 而 Gradle 测试的工作目录是模块目录，因此需要向上查找仓库根。
     */
    private fun locateFfmpeg(): Path? {
        FfmpegLocator.locate()?.let { return it }
        val platformDir = if (FfmpegLocator.executableName.endsWith(".exe")) "windows" else "macos"
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "resources/$platformDir/ffmpeg/${FfmpegLocator.executableName}")
            if (candidate.isFile) return candidate.toPath()
            dir = dir.parentFile
        }
        return null
    }

    /**
     * 测试素材用 `lavfi` + `mpeg4`/`aac` 现场生成；随包的最小 macOS 构建只保留 `-c copy`
     * 需要的组件，遇到这种 ffmpeg 时跳过真实用例，避免把「缺少编码器」误报成回归。
     */
    private fun canGenerateTestClip(ffmpeg: Path): Boolean {
        val filters = runProcessCapture(ffmpeg, listOf("-hide_banner", "-filters")).second
        if (!filters.contains("testsrc")) return false
        val encoders = runProcessCapture(ffmpeg, listOf("-hide_banner", "-encoders")).second
        return encoders.contains("mpeg4") && encoders.contains(" aac ")
    }

    private fun runProcess(executable: Path, args: List<String>): Int {
        val (exit, log) = runProcessCapture(executable, args)
        if (exit != 0) println(log)
        return exit
    }

    /** @return 退出码 + 合并后的 stdout/stderr（探测流信息时退出码通常为 1，属预期） */
    private fun runProcessCapture(executable: Path, args: List<String>): Pair<Int, String> {
        val process = ProcessBuilder(listOf(executable.toString()) + args)
            .redirectErrorStream(true)
            .start()
        val log = process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() to log
    }

    /** 构造一个 ISO BMFF box：4 字节大小 + 4 字节类型 + 负载。 */
    private fun box(type: String, payload: ByteArray = ByteArray(0)): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf(
            (size ushr 24).toByte(),
            (size ushr 16).toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        ) + type.toByteArray(Charsets.US_ASCII) + payload
    }

    /** 生成假 ffmpeg：Windows 用 .bat，其他平台用可执行 .sh。 */
    private fun script(name: String, windows: String, unix: String): Path {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val file = File(tempDir, if (isWindows) "$name.bat" else "$name.sh")
        file.writeText(if (isWindows) windows else unix)
        if (!isWindows) file.setExecutable(true)
        return file.toPath()
    }

    private fun resultWith(
        directory: File,
        init: ByteArray?,
        segments: List<ByteArray>,
        audioSegments: List<ByteArray> = emptyList(),
        audioInit: ByteArray? = null,
    ): HlsDownloadResult {
        directory.mkdirs()
        val initFile = init?.let { File(directory, "init.mp4").apply { writeBytes(it) } }
        val files = segments.mapIndexed { index, bytes ->
            File(directory, "seg_${index.toString().padStart(5, '0')}.m4s").apply { writeBytes(bytes) }
        }
        val audioDir = File(directory, "audio").apply { mkdirs() }
        val audioInitFile = audioInit?.let { File(audioDir, "init.mp4").apply { writeBytes(it) } }
        val audioFiles = audioSegments.mapIndexed { index, bytes ->
            File(audioDir, "seg_${index.toString().padStart(5, '0')}.m4s").apply { writeBytes(bytes) }
        }
        val media = HlsPlaylist.Media(
            segments = files.mapIndexed { index, _ ->
                HlsSegment(uri = "", duration = 4.0, sequence = index)
            },
        )
        return HlsDownloadResult(
            media = media,
            segments = files,
            initSegment = initFile,
            audioSegments = audioFiles,
            audioInitSegment = audioInitFile,
            directory = directory,
        )
    }
}

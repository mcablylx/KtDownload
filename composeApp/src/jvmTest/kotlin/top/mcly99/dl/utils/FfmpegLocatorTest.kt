package top.mcly99.dl.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FfmpegLocator] 的版本探测测试（v3-P3-2）。
 *
 * 只测 `-version` 探测本身，不依赖机器上是否装了 ffmpeg：
 * 用临时脚本模拟正常输出与挂起两种进程。
 */
class FfmpegLocatorTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        // 与 FfmpegMuxerTest 一致：放在 build/ 下，避免受限环境写不了系统临时目录
        val root = File("build/tmp").apply { mkdirs() }
        tempDir = Files.createTempDirectory(root.toPath(), "ktdown-locator-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun probe_parses_version_from_output() {
        val script = script(
            name = "fake-ffmpeg-ok",
            windows = "@echo off\r\necho ffmpeg version 8.1.3-full_build-www.gyan.dev Copyright (c) 2000-2026\r\nexit /b 0\r\n",
            unix = "#!/bin/sh\necho 'ffmpeg version 8.1.3-full_build-www.gyan.dev Copyright (c) 2000-2026'\nexit 0\n",
        )

        // 版本号解析到 `_` 之前：`8.1.3-full_build-...` → `8.1.3-full`
        assertEquals("8.1.3-full", FfmpegLocator.probe(script, 10_000))
    }

    @Test
    fun probe_returns_null_for_non_zero_exit() {
        val script = script(
            name = "fake-ffmpeg-fail",
            windows = "@echo off\r\nexit /b 1\r\n",
            unix = "#!/bin/sh\nexit 1\n",
        )

        assertNull(FfmpegLocator.probe(script, 10_000))
    }

    /**
     * 旧实现先 `readText()` 再 `waitFor()`，进程不关 stdout 时超时永远不生效。
     * 这里模拟一个挂起的 ffmpeg，确认 [FfmpegLocator.probe] 会在超时后返回。
     */
    @Test
    fun probe_times_out_when_process_hangs() {
        val script = script(
            name = "fake-ffmpeg-hang",
            windows = "@echo off\r\nping -n 6 127.0.0.1 >nul\r\n",
            unix = "#!/bin/sh\nsleep 5\n",
        )

        val startedAt = System.nanoTime()
        val version = FfmpegLocator.probe(script, 300)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertNull(version)
        assertTrue(elapsedMs < 4_000, "探测应在超时后尽快返回，实际 ${elapsedMs}ms")
    }

    /** 平台文件名：Windows 必须是 `ffmpeg.exe`，macOS/Linux 必须是不带后缀的 `ffmpeg`。 */
    @Test
    fun executable_name_matches_platform() {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        assertEquals(if (isWindows) "ffmpeg.exe" else "ffmpeg", FfmpegLocator.executableName)
    }

    /** 系统属性覆盖是查找顺序的第一位，命中后不再走安装包资源/PATH。 */
    @Test
    fun locate_prefers_system_property_override() {
        val script = script(
            name = "fake-ffmpeg-override",
            windows = "@echo off\r\necho ffmpeg version 8.1.3-test\r\nexit /b 0\r\n",
            unix = "#!/bin/sh\necho 'ffmpeg version 8.1.3-test'\nexit 0\n",
        )
        val key = "ktdownload.ffmpeg"
        val previous = System.getProperty(key)
        try {
            System.setProperty(key, script.toAbsolutePath().toString())
            FfmpegLocator.reset()
            assertEquals(script.toAbsolutePath(), FfmpegLocator.locate())
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
            FfmpegLocator.reset()
        }
    }

    /** 生成假 ffmpeg：Windows 用 .bat，其他平台用可执行 .sh。 */
    private fun script(name: String, windows: String, unix: String): Path {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val file = File(tempDir, if (isWindows) "$name.bat" else "$name.sh")
        file.writeText(if (isWindows) windows else unix)
        if (!isWindows) file.setExecutable(true)
        return file.toPath()
    }
}

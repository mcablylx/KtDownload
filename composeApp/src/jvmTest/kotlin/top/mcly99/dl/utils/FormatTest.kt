package top.mcly99.dl.utils

import top.mcly99.dl.model.TaskType
import top.mcly99.dl.theme.enUS
import top.mcly99.dl.theme.zhCN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormatTest {


    @Test
    fun accepts_http_and_https_direct_links() {
        assertTrue(isSupportedDownloadUrl("http://example.com/file.zip"))
        assertTrue(isSupportedDownloadUrl("https://example.com/a/b/file.mp4?token=1&x=2"))
        assertTrue(isSupportedDownloadUrl("  https://example.com/file  "))
    }

    @Test
    fun rejects_non_http_schemes() {
        assertFalse(isSupportedDownloadUrl("magnet:?xt=urn:btih:abcdef"))
        assertFalse(isSupportedDownloadUrl("ftp://example.com/file.zip"))
        assertFalse(isSupportedDownloadUrl("file:///C:/file.zip"))
        assertFalse(isSupportedDownloadUrl(""))
        assertFalse(isSupportedDownloadUrl("not a url"))
        assertFalse(isSupportedDownloadUrl("http:///no-host"))
    }

    @Test
    fun accepts_m3u8_links_but_rejects_bt_links() {
        assertTrue(isSupportedDownloadUrl("https://example.com/movie.m3u8"))
        assertTrue(isSupportedDownloadUrl("https://example.com/movie.m3u8?token=1"))
        assertTrue(isSupportedDownloadUrl("https://example.com/hls/index.html"))
        // BT 引擎未实现：外层协议正确也应拦截
        assertFalse(isSupportedDownloadUrl("https://example.com/file.torrent"))
    }

    @Test
    fun detects_download_types_for_display() {
        assertEquals(TaskType.Http, detectUrlType("https://example.com/file.zip"))
        assertEquals(TaskType.Bt, detectUrlType("magnet:?xt=urn:btih:abcdef"))
        assertEquals(TaskType.Bt, detectUrlType("https://example.com/file.torrent?x=1"))
        assertEquals(TaskType.M3u8, detectUrlType("https://example.com/movie.m3u8?token=1"))
        assertEquals(TaskType.M3u8, detectUrlType("https://example.com/hls/live.m3u8"))
        assertEquals(TaskType.Bt, detectUrlType("https://example.com/FILE.TORRENT?x=1"))
        assertEquals(TaskType.M3u8, detectUrlType("https://example.com/HLS/index.html"))
        // v3-P2-1：路径扩展名优先，`/hls/` 下的直链与查询串中的 `.m3u8` 不应误判
        assertEquals(TaskType.Http, detectUrlType("https://cdn.example.com/hls/movie.mp4"))
        assertEquals(TaskType.Http, detectUrlType("https://example.com/download?file=foo.m3u8"))
        assertEquals(TaskType.Http, detectUrlType("https://cdn.example.com/hls/movie.mp4?token=1"))
        assertEquals(TaskType.M3u8, detectUrlType("https://cdn.example.com/hls/index.m3u8?token=1"))
    }

    @Test
    fun formats_bytes_with_binary_units() {
        assertEquals("0 B", formatBytes(0L))
        assertEquals("1.00 KB", formatBytes(1_024L))
        assertEquals("1.50 KB", formatBytes(1_536L))
        assertEquals("1.00 MB", formatBytes(1_024L * 1_024L))
        assertEquals("1.00 GB", formatBytes(1_024L * 1_024L * 1_024L))
    }

    @Test
    fun formats_duration_in_seconds_minutes_and_hours() {
        assertEquals("59秒", formatDuration(59.9, zhCN()))
        assertEquals("1分0秒", formatDuration(60.0, zhCN()))
        assertEquals("59分59秒", formatDuration(3_599.0, zhCN()))
        assertEquals("1时0分", formatDuration(3_600.0, zhCN()))
        assertEquals("1m0s", formatDuration(60.0, enUS()))
    }

    @Test
    fun estimates_remaining_time_and_handles_zero_speed() {
        assertEquals("计算中...", estimateTimeRemaining(0L, 100L, 0L, zhCN()))
        assertEquals("10秒", estimateTimeRemaining(0L, 100L, 10L, zhCN()))
        assertEquals("0秒", estimateTimeRemaining(100L, 100L, 10L, zhCN()))
        assertEquals("0秒", estimateTimeRemaining(120L, 100L, 10L, zhCN()))
    }
}

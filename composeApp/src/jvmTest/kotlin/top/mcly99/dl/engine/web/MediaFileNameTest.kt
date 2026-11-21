package top.mcly99.dl.engine.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [downloadFileNameFor] 单测。
 *
 * 这里的规则直接影响用户下载目录里的文件名，所以按「页面名称 + URL 扩展名」的组合逐条钉住。
 */
class MediaFileNameTest {

    private fun candidate(
        url: String,
        kind: MediaKind,
        name: String = "",
    ) = MediaCandidate(url, kind, name, MediaCandidate.Source.Attribute)

    @Test
    fun uses_page_name_and_borrows_extension_from_url() {
        val name = downloadFileNameFor(
            candidate("https://cdn.example.com/v/trailer.mp4", MediaKind.Mp4, "预告片"),
        )

        assertEquals("预告片.mp4", name)
    }

    @Test
    fun hls_output_is_always_mp4() {
        val name = downloadFileNameFor(
            candidate("https://cdn.example.com/hls/ep03/index.m3u8", MediaKind.M3u8, "第 3 集"),
        )

        // 合成结果固定是 mp4，不该出现「第 3 集.m3u8」
        assertEquals("第 3 集.mp4", name)
    }

    @Test
    fun keeps_extension_already_present_in_name() {
        assertEquals(
            "第 3 集.mp4",
            downloadFileNameFor(candidate("https://cdn.example.com/a.mp4", MediaKind.Mp4, "第 3 集.mp4")),
        )
    }

    @Test
    fun falls_back_to_url_name_when_page_name_is_blank() {
        assertEquals(
            "index.mp4",
            downloadFileNameFor(candidate("https://cdn.example.com/hls/index.m3u8", MediaKind.M3u8, "")),
        )
    }

    @Test
    fun sanitizes_illegal_characters() {
        val name = downloadFileNameFor(
            candidate("https://cdn.example.com/v/a.mp4", MediaKind.Mp4, """第3集: 大结局?""")
        )

        assertTrue(name.endsWith(".mp4"), "实际: $name")
        assertTrue(':' !in name, "Windows 非法字符应被替换: $name")
        assertTrue('?' !in name, "Windows 非法字符应被替换: $name")
    }

    @Test
    fun keeps_other_kind_extension() {
        assertEquals(
            "花絮.mkv",
            downloadFileNameFor(candidate("https://cdn.example.com/v/extra.mkv", MediaKind.Other, "花絮")),
        )
    }
}

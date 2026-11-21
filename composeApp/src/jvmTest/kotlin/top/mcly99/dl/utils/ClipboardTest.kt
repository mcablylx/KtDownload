package top.mcly99.dl.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipboardTest {

    @Test
    fun ignores_plain_web_pages() {
        // 普通网址不是下载内容，复制它们不该弹「添加任务」
        val pages = listOf(
            "https://github.com/mcablylx/KtDownload",
            "https://www.baidu.com",
            "https://www.zhihu.com/question/123456",
            "https://www.bilibili.com/video/BV1xx411c7mD",
            "https://mp.weixin.qq.com/s/abcdef",
            "https://example.com/news/article.html",
            "https://example.com/path?file=movie.mp4", // 扩展名在查询串里，不是路径
            "https://example.com/a.b.c/file",           // 末段没有扩展名
            "https://example.com/v1.2/api",
            "not a url",
            "",
        )
        pages.forEach { assertFalse(isValidDownloadUrl(it), "不应识别为下载链接: $it") }
    }

    @Test
    fun ignores_web_images_and_documents() {
        // 网页上的图片/文档几乎都是「查看」用途，复制到剪贴板不该误弹
        val assets = listOf(
            "https://cdn.example.com/logo.png",
            "https://cdn.example.com/photo.jpg",
            "https://cdn.example.com/banner.svg",
            "https://example.com/readme.txt",
            "https://example.com/report.pdf",
            "https://example.com/guide.docx",
            "https://example.com/data.csv",
            "https://example.com/book.epub",
        )
        assets.forEach { assertFalse(isValidDownloadUrl(it), "不应识别为下载链接: $it") }
    }

    @Test
    fun accepts_real_download_links() {
        val downloads = listOf(
            "https://example.com/movie.mp4",
            "https://example.com/archive.zip",
            "https://example.com/setup.exe",
            "https://example.com/live.m3u8",
            "https://example.com/album.flac",
            "https://example.com/disk.iso",
            "https://example.com/file.mp4?token=1&x=2", // 查询串不影响路径末段判断
        )
        downloads.forEach { assertTrue(isValidDownloadUrl(it), "应识别为下载链接: $it") }
    }

    @Test
    fun rejects_non_downloadable_schemes() {
        assertFalse(isValidDownloadUrl("magnet:?xt=urn:btih:abcdef"))
        assertFalse(isValidDownloadUrl("ftp://example.com/file.zip"))
        assertFalse(isValidDownloadUrl("file:///C:/file.zip"))
    }
}

package top.mcly99.dl.engine.web.browser

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 深度抓取里那些**不依赖真浏览器**的部分：Cookie 取用规则、浏览器探测的覆盖入口。
 *
 * 真起浏览器的那条链路不做自动化（CI 上没有 Chrome，装了也不该在单测里跑进程），
 * 靠 `KTDL_BROWSER` 手动验；这里只保证「取错 Cookie」和「探测优先级」这两个纯逻辑是对的。
 */
class BrowserCaptureResultTest {

    @AfterTest
    fun clearOverride() {
        System.clearProperty("ktdownload.browser")
    }

    @Test
    fun cookie_is_matched_by_domain_suffix_and_longest_wins() {
        val result = BrowserCaptureResult(
            cookies = mapOf(
                "example.com" to "a=1",
                "cdn.example.com" to "b=2",
            )
        )
        // 精确匹配优先于父域
        assertEquals("b=2", result.cookieFor("https://cdn.example.com/hls/index.m3u8?wsSecret=x"))
        // 父域兜底
        assertEquals("a=1", result.cookieFor("https://example.com/x.mp4"))
        // 后缀不等于子串：`notexample.com` 不能命中 `example.com`
        assertNull(result.cookieFor("https://notexample.com/x.mp4"))
        assertNull(result.cookieFor("https://other.net/x.mp4"))
    }

    @Test
    fun empty_cookie_jar_returns_null() {
        assertNull(BrowserCaptureResult().cookieFor("https://cdn.example.com/a.m3u8"))
    }

    @Test
    fun invalid_url_does_not_throw() {
        assertNull(BrowserCaptureResult(cookies = mapOf("a.com" to "x=1")).cookieFor("not a url"))
    }

    @Test
    fun locator_honors_the_override_property() {
        val exe = Files.createTempFile("ktdl-browser", ".exe").toFile()
        exe.deleteOnExit()
        // Windows 上可执行位由扩展名决定；Linux/macOS 的 CI 上必须显式打上，否则被判成不可执行
        exe.setExecutable(true)
        System.setProperty("ktdownload.browser", exe.absolutePath)
        assertEquals(exe.toPath(), BrowserLocator.find())
    }

    @Test
    fun locator_ignores_override_that_does_not_exist() {
        System.setProperty("ktdownload.browser", "/definitely/not/here/chrome.exe")
        // 找不到时返回 null，或退回到机器上真实存在的浏览器 —— 但绝不能返回那个不存在的路径
        val found = BrowserLocator.find()
        assertTrue(found == null || found.toString() != "/definitely/not/here/chrome.exe")
    }

    @Test
    fun search_paths_are_not_empty_on_any_platform() {
        assertTrue(BrowserLocator.searchPaths.isNotEmpty())
    }
}

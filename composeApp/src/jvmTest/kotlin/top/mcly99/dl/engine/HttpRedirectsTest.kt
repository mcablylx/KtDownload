package top.mcly99.dl.engine

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 重定向跟随的公共规则（v7-P2-2）。
 *
 * 这套规则原来在 `DownloadEngine` / `HlsDownloader` / `HttpProbe` 里各写了一遍，
 * 上一轮两条 P1 的根源就是「同一份逻辑复制了四遍」。抽出来之后这里逐条钉住，
 * 免得下次又分叉。
 */
class HttpRedirectsTest {

    // ---------------------------------------------------------------- 地址解析

    @Test
    fun absolute_location_is_used_as_is() {
        assertEquals(
            "https://b.example.com/x.m3u8",
            HttpRedirects.resolve("https://a.example.com/p/index.m3u8", "https://b.example.com/x.m3u8"),
        )
    }

    @Test
    fun relative_location_resolves_against_current_url() {
        // CDN 的 302 里相对写法很常见；当成绝对地址会拼出 http://host/a/b.m3u8 这种错路径。
        // 语义是「相对当前地址的父路径」，与 RFC 3986 一致
        assertEquals(
            "https://a.example.com/v/720/index.m3u8",
            HttpRedirects.resolve("https://a.example.com/v/index.m3u8", "720/index.m3u8"),
        )
    }

    @Test
    fun root_relative_location_replaces_path() {
        assertEquals(
            "https://a.example.com/other/x.m3u8",
            HttpRedirects.resolve("https://a.example.com/v/index.m3u8", "/other/x.m3u8"),
        )
    }

    @Test
    fun unparsable_location_throws_instead_of_returning_garbage() {
        // 返回一个半截地址，后续会拿它去建连接，报错位置离真正原因很远
        assertFailsWith<IOException> {
            HttpRedirects.resolve("https://a.example.com/x.m3u8", "http://[bad")
        }
    }

    // ---------------------------------------------------------------- host 比较

    @Test
    fun same_host_ignores_case_and_port() {
        assertTrue(HttpRedirects.sameHost("https://A.Example.com/x", "https://a.example.com/y"))
        assertTrue(HttpRedirects.sameHost("http://a.example.com/x", "https://a.example.com:8443/y"))
    }

    @Test
    fun different_hosts_are_detected() {
        assertFalse(HttpRedirects.sameHost("https://a.example.com/x", "https://b.example.com/y"))
    }

    @Test
    fun localhost_and_loopback_are_different_hosts() {
        // 这是 CDN 场景的真实形态：跳转换了 host 就不能带 Cookie。
        // 用 localhost / 127.0.0.1 表达，测试不需要真的联网
        assertFalse(HttpRedirects.sameHost("http://localhost:8080/a", "http://127.0.0.1:8080/a"))
    }

    @Test
    fun unparsable_url_counts_as_different_host() {
        // 解析不出来时宁可少带 Cookie，也不要冒险把会话发给未知主机
        assertFalse(HttpRedirects.sameHost("not a url", "https://a.example.com/x"))
    }

    // ---------------------------------------------------------------- 状态码

    @Test
    fun redirect_codes_are_complete() {
        listOf(301, 302, 303, 307, 308).forEach { assertTrue(it in HttpRedirects.CODES, "应跟随 $it") }
        listOf(200, 304, 400, 403, 500).forEach { assertFalse(it in HttpRedirects.CODES, "不应跟随 $it") }
    }
}

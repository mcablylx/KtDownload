package top.mcly99.dl.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 上游 GitHubLinkParser 的三类分流（直链 > 仓库 > 账号）纯逻辑回归。
 * 这是 D-18 接入解析入口后的第一道防线：识别错了会把仓库链接当无效链接，
 * 或把功能页当账号去打 API。
 */
class GitHubLinkParserTest {

    @Test
    fun repository_root_has_no_subpath() {
        val parsed = GitHubLinkParser.parse("https://github.com/tidain/YunX-Desktop")
        val repo = assertIs<GitHubLinkType.Repository>(parsed)
        assertEquals("tidain", repo.owner)
        assertEquals("YunX-Desktop", repo.repo)
        assertNull(repo.subPath)
    }

    @Test
    fun repository_subpath_drops_query_and_fragment() {
        val parsed = GitHubLinkParser.parse("https://github.com/o/r/tree/main/src?tab=readme#top")
        val repo = assertIs<GitHubLinkType.Repository>(parsed)
        assertEquals("tree/main/src", repo.subPath)
    }

    @Test
    fun link_embedded_in_share_text_is_extracted() {
        val parsed = GitHubLinkParser.parse("这是一个项目 https://github.com/o/r 拿去用，提取码 abcd")
        assertEquals("o", assertIs<GitHubLinkType.Repository>(parsed).owner)
    }

    @Test
    fun account_homepage_is_not_a_repository() {
        val parsed = GitHubLinkParser.parse("https://github.com/octocat/")
        assertEquals("octocat", assertIs<GitHubLinkType.Account>(parsed).owner)
    }

    @Test
    fun special_feature_paths_are_rejected() {
        assertNull(GitHubLinkParser.parse("https://github.com/features"))
        assertNull(GitHubLinkParser.parse("https://github.com/features/actions"))
        assertNull(GitHubLinkParser.parse("https://github.com/trending"))
        assertNull(GitHubLinkParser.parse("https://github.com/orgs/foo/repositories"))
    }

    @Test
    fun raw_and_release_download_urls_are_direct_files() {
        val cases = listOf(
            "https://raw.githubusercontent.com/o/r/main/src/a.txt" to "a.txt",
            "https://github.com/o/r/releases/download/v1.0/app.zip" to "app.zip",
            "https://github.com/o/r/raw/main/docs/说明.md" to "说明.md",
        )
        for ((url, name) in cases) {
            val direct = assertIs<GitHubLinkType.DirectFile>(GitHubLinkParser.parse(url), url)
            assertEquals(url, direct.url)
            assertEquals(name, direct.fileName, url)
        }
    }

    @Test
    fun unrelated_urls_are_rejected() {
        assertNull(GitHubLinkParser.parse("https://pan.quark.cn/s/Abc123"))
        assertNull(GitHubLinkParser.parse("https://example.com/o/r"))
        assertNull(GitHubLinkParser.parse("不是链接"))
    }
}

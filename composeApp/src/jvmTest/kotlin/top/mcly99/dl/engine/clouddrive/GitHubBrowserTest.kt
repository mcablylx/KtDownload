package top.mcly99.dl.engine.clouddrive

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import top.mcly99.dl.data.network.GitHubAsset
import top.mcly99.dl.data.network.GitHubRelease
import top.mcly99.dl.data.network.GitHubRepo
import top.mcly99.dl.data.network.GitHubTreeEntry
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.model.ShareFile
import top.mcly99.dl.data.network.model.ShareSession

/**
 * D-18 glue 层：GitHub 仓库树 / Releases / 账号仓库 → ShareFile 目录 → CloudLink。
 * 全部走 FakeSource，零真实网络，也不碰用户目录。
 */
class GitHubBrowserTest {

    private class FakeSource : GitHubBrowser.GitHubSource {
        var repo = repo("o", "r")
        var ownerType: String? = "User"
        var trees: Map<String, List<GitHubTreeEntry>> = emptyMap()
        var releases: List<GitHubRelease> = emptyList()
        var userRepos: List<GitHubRepo> = emptyList()
        var orgRepos: List<GitHubRepo> = emptyList()
        val calls = mutableListOf<String>()

        override suspend fun getRepo(owner: String, repo: String): GitHubRepo? {
            calls += "repo:$owner/$repo"
            return this.repo.takeIf { it.owner == owner && it.name == repo }
        }

        override suspend fun getTree(owner: String, repo: String, sha: String): List<GitHubTreeEntry>? {
            calls += "tree:$owner/$repo/$sha"
            return trees[sha]
        }

        override suspend fun getReleases(owner: String, repo: String, page: Int): List<GitHubRelease>? {
            calls += "releases:$owner/$repo:$page"
            return releases.takeIf { page == 1 }
        }

        override suspend fun getUserType(owner: String): String? {
            calls += "usertype:$owner"
            return ownerType
        }

        override suspend fun getUserRepos(owner: String, page: Int): List<GitHubRepo>? {
            calls += "userrepos:$owner:$page"
            return userRepos.takeIf { page == 1 }
        }

        override suspend fun getOrgRepos(owner: String, page: Int): List<GitHubRepo>? {
            calls += "orgrepos:$owner:$page"
            return orgRepos.takeIf { page == 1 }
        }
    }

    private lateinit var source: FakeSource
    private var mirror: String = ""
    private lateinit var browser: GitHubBrowser

    @BeforeTest
    fun setUp() {
        source = FakeSource()
        mirror = ""
        browser = GitHubBrowser(source = source, mirrorPrefix = { mirror })
    }

    private fun session() = ShareSession("o/r", "main", "o/r")
    private fun link(file: ShareFile) = browser.downloadLink(session(), file)

    @Test
    fun repository_root_exposes_code_and_releases() = runTest {
        val s = browser.createSession("https://github.com/o/r")
        assertEquals("o/r", s.title)
        assertEquals("main", s.stoken)
        val files = browser.listFiles(s, GitHubBrowser.AUTO_ROOT)
        assertEquals(listOf("代码", "Releases"), files.map { it.fname })
        assertTrue(files.all { it.isdir })
        assertEquals("github:code:main", files.first().fid)
    }

    @Test
    fun nested_tree_paths_keep_parent_directories_in_raw_urls() = runTest {
        source.trees = mapOf(
            "main" to listOf(
                GitHubTreeEntry("src", "tree", "sha-src", null),
                GitHubTreeEntry("README.md", "blob", "sha-readme", 12L),
            ),
            "sha-src" to listOf(
                GitHubTreeEntry("Main.kt", "blob", "sha-main", 42L),
                GitHubTreeEntry("deep", "tree", "sha-deep", null),
            ),
            "sha-deep" to listOf(GitHubTreeEntry("a b.txt", "blob", "sha-a", 7L)),
        )
        browser.createSession("https://github.com/o/r")
        val top = browser.listFiles(session(), "github:code:main")
        assertEquals("github:zip", top.first().fid)
        assertEquals("github:file:README.md", top.last().fid)
        assertEquals(
            "https://raw.githubusercontent.com/o/r/main/README.md",
            link(top.last()).url,
        )

        val src = browser.listFiles(session(), "github:code:sha-src")
        val nested = src.first { it.fname == "Main.kt" }
        assertEquals("github:file:src/Main.kt", nested.fid)
        // 上游直接用 Git Trees 返回的本层名字拼 URL，二级目录会丢掉父路径；这里是下游修正
        assertEquals(
            "https://raw.githubusercontent.com/o/r/main/src/Main.kt",
            link(nested).url,
        )

        val deep = browser.listFiles(session(), "github:code:sha-deep")
        assertEquals(
            "https://raw.githubusercontent.com/o/r/main/src/deep/a%20b.txt",
            link(deep.single()).url,
        )
    }

    @Test
    fun zip_uses_codeload_and_default_branch() = runTest {
        source.trees = mapOf("main" to emptyList())
        browser.createSession("https://github.com/o/r")
        val zip = browser.listFiles(session(), "github:code:main").first()
        assertEquals(
            "https://codeload.github.com/o/r/zip/refs/heads/main",
            link(zip).url,
        )
        assertEquals(SharePlatform.GITHUB, link(zip).platform)
    }

    @Test
    fun releases_skip_drafts_and_expose_assets_as_files() = runTest {
        source.releases = listOf(
            release("v2", prerelease = true),
            release("v1", prerelease = false),
            release("draft-tag", draft = true),
        )
        browser.createSession("https://github.com/o/r")
        val list = browser.listFiles(session(), "github:releases")
        assertEquals(2, list.size)
        assertTrue(list.first().fname.contains("预发布"))
        assertTrue(list.none { it.fname.contains("draft") })

        val assets = browser.listFiles(session(), list.last().fid)
        assertEquals("app.zip", assets.single().fname)
        assertEquals(
            "https://github.com/o/r/releases/download/v1/app.zip",
            link(assets.single()).url,
        )
    }

    @Test
    fun account_list_uses_org_endpoint_and_can_open_a_repo() = runTest {
        source.ownerType = "Organization"
        source.orgRepos = listOf(repo("o", "other"))
        source.trees = mapOf("trunk" to emptyList())
        val s = browser.createSession("https://github.com/o")
        val list = browser.listFiles(s, GitHubBrowser.AUTO_ROOT)
        assertEquals("github:repo:o/other", list.single().fid)
        assertTrue(source.calls.contains("orgrepos:o:1"))

        // 从账号列表点进仓库后，上下文要切到新仓库（含它的默认分支）
        source.repo = repo("o", "other", branch = "trunk")
        val root = browser.listFiles(s, list.single().fid)
        assertEquals("github:code:trunk", root.first().fid)
        val zip = browser.listFiles(s, "github:code:trunk").first()
        assertEquals(
            "https://codeload.github.com/o/other/zip/refs/heads/trunk",
            link(zip).url,
        )
    }

    @Test
    fun direct_file_link_becomes_a_single_item_list() = runTest {
        val s = browser.createSession(
            "https://raw.githubusercontent.com/o/r/main/bin/app%20v1.zip",
        )
        assertEquals(GitHubBrowser.DIRECT_MARKER, s.stoken)
        val files = browser.listFiles(s, GitHubBrowser.AUTO_ROOT)
        assertEquals(1, files.size)
        assertEquals("app%20v1.zip", files.single().fname)
        assertEquals(
            "https://raw.githubusercontent.com/o/r/main/bin/app%20v1.zip",
            link(files.single()).url,
        )
    }

    @Test
    fun mirror_prefix_is_only_applied_when_configured() = runTest {
        source.trees = mapOf("main" to listOf(GitHubTreeEntry("a.txt", "blob", "sha", 1L)))
        browser.createSession("https://github.com/o/r")
        val file = browser.listFiles(session(), "github:code:main")
            .first { it.fname == "a.txt" }
        val direct = link(file)
        assertEquals("https://raw.githubusercontent.com/o/r/main/a.txt", direct.url)

        mirror = "https://v4.gh-proxy.org/"
        assertTrue(link(file).url.startsWith("https://v4.gh-proxy.org/https://"))
    }

    @Test
    fun unknown_fid_is_rejected_instead_of_queued_as_a_url() = runTest {
        browser.createSession("https://github.com/o/r")
        assertFailsWith<IllegalArgumentException> {
            browser.listFiles(session(), "github:wat")
        }
        assertFailsWith<IllegalArgumentException> {
            link(ShareFile("github:releases", "Releases", -1, true, "", ""))
        }
    }

    @Test
    fun service_routes_github_through_the_browser() = runTest {
        source.trees = mapOf("main" to emptyList())
        val service = CloudDriveService(githubBrowser = browser)
        val parsed = service.parse("https://github.com/o/r")
        assertEquals(SharePlatform.GITHUB, parsed?.platform)
        assertEquals(GitHubBrowser.AUTO_ROOT, service.rootDirFid(SharePlatform.GITHUB))

        val s = service.createSession("https://github.com/o/r", null, "", SharePlatform.GITHUB)
        val files = service.listFiles(s, GitHubBrowser.AUTO_ROOT, "", SharePlatform.GITHUB)
        assertEquals(listOf("代码", "Releases"), files.map { it.fname })

        val zip = service.listFiles(s, "github:code:main", "", SharePlatform.GITHUB).first()
        val link = service.downloadLink(s, zip, "", SharePlatform.GITHUB)
        assertTrue(link.url.startsWith("https://codeload.github.com/"))
        assertEquals(null, link.cleanupDirFid)
    }

    // ------------------------------------------------------------------ fixtures

    private companion object {
        fun repo(owner: String, name: String, branch: String = "main") = GitHubRepo(
            name = name,
            fullName = "$owner/$name",
            description = null,
            fork = false,
            parentFullName = null,
            defaultBranch = branch,
            pushedAt = "2026-11-14T00:00:00Z",
        )

        fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false) = GitHubRelease(
            tagName = tag,
            name = tag,
            publishedAt = "2026-11-14T00:00:00Z",
            prerelease = prerelease,
            draft = draft,
            assets = listOf(
                GitHubAsset(
                    name = "app.zip",
                    downloadUrl = "https://github.com/o/r/releases/download/$tag/app.zip",
                    size = 1024L,
                    contentType = "application/zip",
                ),
            ),
        )
    }
}

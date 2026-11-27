package top.mcly99.dl.data.network

import kotlinx.coroutines.test.runTest
import top.mcly99.dl.utils.HostPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {

    private fun release(
        tag: String,
        assets: List<GitHubAsset> = listOf(
            GitHubAsset("KtDownload-$tag.dmg", "https://x/$tag.dmg", 1L, null),
            GitHubAsset("KtDownload-$tag.exe", "https://x/$tag.exe", 1L, null),
        ),
        prerelease: Boolean = false,
        draft: Boolean = false,
        body: String? = "更新说明",
    ) = GitHubRelease(
        tagName = tag,
        name = null,
        publishedAt = null,
        assets = assets,
        prerelease = prerelease,
        draft = draft,
        body = body,
    )

    @Test
    fun reports_newer_version_with_matching_installer() = runTest {
        val checker = UpdateChecker(
            latestProvider = { _, _ -> release("v1.0.1") },
            platform = HostPlatform.MACOS,
        )
        val info = checker.check("1.0.0")
        assertTrue(info != null)
        assertEquals("v1.0.1", info.version)
        assertEquals("KtDownload-v1.0.1.dmg", info.asset.name)
        assertEquals("更新说明", info.notes)
        assertTrue(info.releaseUrl.contains("v1.0.1"))
    }

    @Test
    fun windows_gets_exe_not_dmg() = runTest {
        val checker = UpdateChecker(
            latestProvider = { _, _ -> release("v1.0.1") },
            platform = HostPlatform.WINDOWS,
        )
        assertEquals("KtDownload-v1.0.1.exe", checker.check("1.0.0")?.asset?.name)
    }

    @Test
    fun equal_or_older_version_is_ignored() = runTest {
        val checker = UpdateChecker({ _, _ -> release("v1.0.0") }, platform = HostPlatform.MACOS)
        assertNull(checker.check("1.0.0"))
        assertNull(checker.check("1.0.1"))
    }

    @Test
    fun prerelease_and_draft_are_skipped() = runTest {
        val pre = UpdateChecker({ _, _ -> release("v2.0.0", prerelease = true) }, platform = HostPlatform.MACOS)
        assertNull(pre.check("1.0.0"))
        val draft = UpdateChecker({ _, _ -> release("v2.0.0", draft = true) }, platform = HostPlatform.MACOS)
        assertNull(draft.check("1.0.0"))
    }

    @Test
    fun failures_are_silent() = runTest {
        val networkFail = UpdateChecker({ _, _ -> null }, platform = HostPlatform.MACOS)
        assertNull(networkFail.check("1.0.0"))
        val noPackage = UpdateChecker(
            { _, _ -> release("v1.0.1", assets = emptyList()) },
            platform = HostPlatform.MACOS,
        )
        assertNull(noPackage.check("1.0.0"))
        val unsupported = UpdateChecker({ _, _ -> release("v1.0.1") }, platform = HostPlatform.UNSUPPORTED)
        assertNull(unsupported.check("1.0.0"))
    }

    @Test
    fun passes_owner_and_repo_to_provider() = runTest {
        var seenOwner = ""
        var seenRepo = ""
        val checker = UpdateChecker(
            latestProvider = { o, r -> seenOwner = o; seenRepo = r; release("v1.0.1") },
            owner = "myowner",
            repo = "myrepo",
            platform = HostPlatform.MACOS,
        )
        checker.check("1.0.0")
        assertEquals("myowner", seenOwner)
        assertEquals("myrepo", seenRepo)
    }

    @Test
    fun asset_version_must_match_the_release_tag() = runTest {
        val mismatch = UpdateChecker(
            { _, _ -> release(
                "v1.0.2",
                assets = listOf(GitHubAsset("KtDownload-1.0.1.exe", "https://x/1.0.1.exe", 1L, null)),
            ) },
            platform = HostPlatform.WINDOWS,
        )
        assertNull(mismatch.check("1.0.0"), "tag 与安装包版本不符时不应返回 UpdateInfo")

        val unrelated = UpdateChecker(
            { _, _ -> release(
                "v1.0.2",
                assets = listOf(GitHubAsset("notes.exe", "https://x/notes.exe", 1L, null)),
            ) },
            platform = HostPlatform.WINDOWS,
        )
        assertNull(unrelated.check("1.0.0"), "Release 里混入的无关 exe 不能被选中")
    }
}

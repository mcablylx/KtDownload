package top.mcly99.dl.data.network

import top.mcly99.dl.utils.HostPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateModelsTest {

    private fun asset(name: String) = GitHubAsset(
        name = name,
        downloadUrl = "https://example.com/$name",
        size = 1024L,
        contentType = null,
    )

    @Test
    fun windows_prefers_exe_then_msi() {
        val assets = listOf(
            asset("KtDownload-1.0.1.dmg"),
            asset("KtDownload-1.0.1.msi"),
            asset("KtDownload-1.0.1.exe"),
        )
        assertEquals("KtDownload-1.0.1.exe", pickInstaller(assets, HostPlatform.WINDOWS)?.name)

        val noExe = listOf(asset("KtDownload-1.0.1.msi"), asset("KtDownload-1.0.1.dmg"))
        assertEquals("KtDownload-1.0.1.msi", pickInstaller(noExe, HostPlatform.WINDOWS)?.name)
    }

    @Test
    fun macos_prefers_dmg_then_pkg() {
        val assets = listOf(
            asset("KtDownload-1.0.1.exe"),
            asset("KtDownload-1.0.1.pkg"),
            asset("KtDownload-1.0.1.dmg"),
        )
        assertEquals("KtDownload-1.0.1.dmg", pickInstaller(assets, HostPlatform.MACOS)?.name)

        val noDmg = listOf(asset("KtDownload-1.0.1.pkg"), asset("KtDownload-1.0.1.exe"))
        assertEquals("KtDownload-1.0.1.pkg", pickInstaller(noDmg, HostPlatform.MACOS)?.name)
    }

    @Test
    fun matches_extension_case_insensitively() {
        val assets = listOf(asset("KtDownload-1.0.1.DMG"))
        assertEquals("KtDownload-1.0.1.DMG", pickInstaller(assets, HostPlatform.MACOS)?.name)
    }

    @Test
    fun returns_null_when_no_matching_package() {
        val assets = listOf(asset("KtDownload-1.0.1.zip"), asset("readme.txt"))
        assertNull(pickInstaller(assets, HostPlatform.MACOS))
        assertNull(pickInstaller(assets, HostPlatform.WINDOWS))
        assertNull(pickInstaller(listOf(asset("KtDownload-1.0.1.dmg")), HostPlatform.UNSUPPORTED))
    }

    @Test
    fun ignores_unrelated_files_with_same_extension() {
        // P1-08：只认 KtDownload-<版本>.<ext>；Release 里混入的 notes.exe 不能被静默选中并执行
        val assets = listOf(asset("notes.exe"), asset("KtDownload-1.0.1.exe"))
        assertEquals("KtDownload-1.0.1.exe", pickInstaller(assets, HostPlatform.WINDOWS)?.name)
    }

    @Test
    fun versioned_selection_rejects_mismatched_or_unversioned_assets() {
        val assets = listOf(
            asset("KtDownload-9.9.9.exe"),
            asset("notes.exe"),
            asset("KtDownload-1.0.1.exe"),
        )
        assertEquals("KtDownload-1.0.1.exe", pickInstaller(assets, HostPlatform.WINDOWS, "v1.0.1")?.name)
        assertNull(pickInstaller(listOf(asset("KtDownload-9.9.9.exe")), HostPlatform.WINDOWS, "v1.0.1"))
        assertNull(pickInstaller(listOf(asset("notes.exe")), HostPlatform.WINDOWS, "v1.0.1"))
    }

    @Test
    fun v_prefix_is_normalized_on_both_sides() {
        val assets = listOf(asset("KtDownload-v1.0.1.dmg"))
        assertEquals("KtDownload-v1.0.1.dmg", pickInstaller(assets, HostPlatform.MACOS, "1.0.1")?.name)
        assertEquals("KtDownload-v1.0.1.dmg", pickInstaller(assets, HostPlatform.MACOS, "v1.0.1")?.name)
    }
}

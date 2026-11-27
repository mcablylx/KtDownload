package top.mcly99.dl.data.network

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateDownloaderTest {

    @Test
    fun sha256_matches_known_digest() {
        val dir = kotlin.io.path.createTempDirectory("update-sha-test")
        val file: File = dir.resolve("data.bin").toFile()
        file.writeText("abc")
        val digest = UpdateDownloader().sha256(file)
        // SHA-256("abc") 的标准值
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest)
        file.delete()
        dir.toFile().delete()
    }

    @Test
    fun sha256_is_lowercase_hex() {
        val dir = kotlin.io.path.createTempDirectory("update-sha-test2")
        val file: File = dir.resolve("empty.bin").toFile()
        file.writeBytes(byteArrayOf())
        val digest = UpdateDownloader().sha256(file)
        assertEquals(64, digest.length)
        assertTrue(digest.all { it in '0'..'9' || it in 'a'..'f' })
        file.delete()
        dir.toFile().delete()
    }

    @Test
    fun verify_rejects_missing_digest_size_mismatch_and_post_move_tampering() {
        val dir = kotlin.io.path.createTempDirectory("update-verify-test")
        val file = dir.resolve("installer.bin").toFile()
        file.writeText("payload")
        val downloader = UpdateDownloader()
        val digest = downloader.sha256(file)
        val asset = GitHubAsset(
            name = "KtDownload-1.0.3.exe",
            downloadUrl = "https://x/a.exe",
            size = file.length(),
            contentType = null,
            digest = "sha256:$digest",
        )

        assertTrue(downloader.verify(file.toPath(), asset, digest), "正确的 digest 应通过")
        assertFalse(downloader.verify(file.toPath(), asset, null), "无 digest 必须拒绝自动安装")
        assertFalse(
            downloader.verify(file.toPath(), asset.copy(size = file.length() + 1), digest),
            "size 不符必须拒绝",
        )

        // P1-08：模拟「下载校验 → 打开安装器」之间文件被替换（TOCTOU）
        file.writeText("tampered")
        assertFalse(downloader.verify(file.toPath(), asset, digest), "落盘后被篡改必须拒绝")
        file.delete()
        dir.toFile().delete()
    }

    @Test
    fun installer_name_rejects_unrelated_and_traversal_names() {
        assertTrue(INSTALLER_NAME.matches("KtDownload-1.0.3.exe"))
        assertTrue(INSTALLER_NAME.matches("KtDownload-v1.0.3.DMG"))
        assertFalse(INSTALLER_NAME.matches("notes.exe"))
        assertFalse(INSTALLER_NAME.matches("../evil.exe"))
        assertFalse(INSTALLER_NAME.matches("KtDownload-1.0.3/../evil.exe"))
    }
}

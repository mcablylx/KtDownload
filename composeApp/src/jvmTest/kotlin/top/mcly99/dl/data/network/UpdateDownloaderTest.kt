package top.mcly99.dl.data.network

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
}

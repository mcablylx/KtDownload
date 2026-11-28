package top.mcly99.dl.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 磁盘空间预检。
 *
 * 大部分用例打的是纯函数 [DiskSpaceGuard.evaluate]：磁盘类型与余量在实际机器上没法任选，
 * 只能把判定逻辑拎出来测。最后几条走真实文件系统，验证接线（找磁盘、读余量、读临时文件长度）。
 */
class DiskSpaceGuardTest {

    private val mib = 1024L * 1024L
    private val gib = 1024L * mib
    private val tempDir: File = Files.createTempDirectory("ktdown-diskguard").toFile()

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun unknown_size_is_never_blocked() {
        // 大小未知时既算不出需求，也不该猜；放行交给后续的真实错误
        assertNull(DiskSpaceGuard.evaluate(0L, 0L, 1L, "fat32"))
        assertNull(DiskSpaceGuard.evaluate(-1L, 0L, 0L, "ntfs"))
    }

    @Test
    fun fat32_rejects_a_file_over_the_four_gib_limit_even_with_plenty_of_space() {
        val issue = DiskSpaceGuard.evaluate(
            totalSize = 5L * gib,
            alreadyOnDisk = 0L,
            usable = 500L * gib,
            fsType = "FAT32",
        )

        assertNotNull(issue)
        assertTrue(issue.contains("FAT32"), "要说清是文件系统限制: $issue")
        assertTrue(issue.contains("4.00 GB"), "要给出具体上限: $issue")
    }

    @Test
    fun fat32_allows_a_file_right_at_the_limit() {
        assertNull(
            DiskSpaceGuard.evaluate(
                totalSize = DiskSpaceGuard.FAT32_MAX_FILE_BYTES,
                alreadyOnDisk = 0L,
                usable = 500L * gib,
                fsType = "fat32",
            ),
            "恰好 4 GiB − 1 是 FAT32 允许的最大值，不该被拦",
        )
    }

    @Test
    fun a_big_file_on_exfat_or_ntfs_is_fine() {
        // exFAT 没有 4 GiB 单文件限制，不能跟着 FAT32 一起拦
        assertNull(DiskSpaceGuard.evaluate(20L * gib, 0L, 500L * gib, "exfat"))
        assertNull(DiskSpaceGuard.evaluate(20L * gib, 0L, 500L * gib, "ntfs"))
    }

    @Test
    fun insufficient_space_names_both_numbers() {
        val issue = DiskSpaceGuard.evaluate(
            totalSize = 10L * gib,
            alreadyOnDisk = 0L,
            usable = 2L * gib,
            fsType = "ntfs",
        )

        assertNotNull(issue)
        assertTrue(issue.contains("空间不足"), issue)
        assertTrue(issue.contains("10.00 GB"), "要说清还需要多少: $issue")
        assertTrue(issue.contains("2.00 GB"), "要说清当前可用多少: $issue")
    }

    @Test
    fun resumed_bytes_are_deducted_from_the_requirement() {
        // 已下 9 GiB，只剩 1 GiB：余量 2 GiB 够用，不该因为「文件总共 10 GiB」被拦
        assertNull(
            DiskSpaceGuard.evaluate(
                totalSize = 10L * gib,
                alreadyOnDisk = 9L * gib,
                usable = 2L * gib,
                fsType = "ntfs",
            ),
        )
    }

    @Test
    fun already_on_disk_more_than_the_total_never_produces_a_negative_requirement() {
        assertNull(
            DiskSpaceGuard.evaluate(
                totalSize = 10L * gib,
                alreadyOnDisk = 12L * gib,
                usable = 1L * mib,
                fsType = "ntfs",
            ),
        )
    }

    @Test
    fun headroom_is_counted() {
        val total = 100L * mib
        val exactlyEnough = total + DiskSpaceGuard.HEADROOM_BYTES

        assertNull(
            DiskSpaceGuard.evaluate(total, 0L, exactlyEnough, "ntfs"),
            "刚好等于「文件 + 余量」应当放行",
        )
        assertNotNull(
            DiskSpaceGuard.evaluate(total, 0L, exactlyEnough - 1, "ntfs"),
            "少一个字节就不够，余量必须真的算进去",
        )
    }

    @Test
    fun unknown_usable_space_is_treated_as_ok() {
        // 0 通常是「查不到」（UNC 路径、权限不足），这时只能放行
        assertNull(DiskSpaceGuard.evaluate(10L * gib, 0L, 0L, "ntfs"))
        assertNull(DiskSpaceGuard.evaluate(10L * gib, 0L, -1L, ""))
    }

    @Test
    fun real_filesystem_check_passes_for_a_small_file() {
        val target = File(tempDir, "small.bin")

        assertNull(DiskSpaceGuard.check(target, totalSize = 1L * mib), "临时目录肯定放得下 1 MiB")
    }

    @Test
    fun parent_directory_that_does_not_exist_yet_still_resolves_a_disk() {
        // 下载前只 mkdirs 到保存目录，深层子目录可能还没建；不能因此抛异常
        val target = File(tempDir, "a/b/c/deep.bin")

        assertNull(DiskSpaceGuard.check(target, totalSize = 1L * mib))
    }

    @Test
    fun existing_bytes_reads_the_partial_file_length() {
        val target = File(tempDir, "resume.bin")
        partialDownloadFile(target).writeBytes(ByteArray(3 * 1024))

        assertEquals(3L * 1024, DiskSpaceGuard.existingBytes(target))
    }

    @Test
    fun existing_bytes_is_zero_without_a_partial_file() {
        assertEquals(0L, DiskSpaceGuard.existingBytes(File(tempDir, "nothing.bin")))
    }
}

package top.mcly99.dl.data

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P3-05：ShimPrefs 原子写 + 权限收紧（设备指纹、签名配置都落在这些文件里） */
class ShimPrefsSecurityTest {
    private val dirs = mutableListOf<java.nio.file.Path>()

    private fun newDir(): java.nio.file.Path =
        Files.createTempDirectory("ktdown-shimprefs-test").also { dirs.add(it) }

    @AfterTest
    fun cleanup() {
        dirs.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    @Test
    fun writes_are_atomic_and_leave_no_temp_files() {
        val dir = newDir()
        val target = dir.resolve("yunx-misc.properties")
        val prefs = ShimPrefs(target.toFile())
        repeat(20) { prefs.put("key-$it", "value-$it") }

        assertEquals("value-19", prefs.get("key-19", null))
        assertEquals(1L, Files.list(dir).use { it.count() }, "原子替换后不应残留 .tmp")
        assertEquals("value-19", ShimPrefs(target.toFile()).get("key-19", null))
    }

    @Test
    fun concurrent_puts_do_not_corrupt_the_file() {
        val dir = newDir()
        val target = dir.resolve("yunx-xunlei-fp.properties").toFile()
        val prefs = ShimPrefs(target)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.submit {
                start.await()
                repeat(40) { i -> prefs.put("t$t-$i", "v$i") }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发写应在 30s 内完成")
        pool.shutdown()

        val reloaded = ShimPrefs(target)
        repeat(threads) { t -> assertEquals("v39", reloaded.get("t$t-39", null)) }
    }

    @Test
    fun file_is_private_when_posix_is_available() {
        val dir = newDir()
        val target = dir.resolve("yunx-misc.properties")
        ShimPrefs(target.toFile()).put("k", "v")
        val permissions = runCatching {
            PosixFilePermissions.toString(Files.getPosixFilePermissions(target))
        }.getOrNull() ?: return // Windows 不支持 POSIX 权限，跳过
        assertEquals("rw-------", permissions)
    }
}

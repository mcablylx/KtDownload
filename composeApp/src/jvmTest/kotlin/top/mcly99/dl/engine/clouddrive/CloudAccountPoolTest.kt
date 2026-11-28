package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.SharePlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/**
 * P3 自研加密账号池。
 *
 * 全部用内存实现或临时目录，绝不触碰 `~/.mclydownload`；文件实现只验证
 * 「往返一致 / 无明文 / AAD 绑定 / 0600」，不依赖用户机器上的主密钥。
 */
class CloudAccountPoolTest {

    private val tempDirs = mutableListOf<Path>()

    private fun tempDir(): Path = Files.createTempDirectory("ktdown-accounts").also { tempDirs.add(it) }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    private fun store(
        legacy: InMemoryCloudCredentialStorage = InMemoryCloudCredentialStorage(),
        pool: InMemoryCloudAccountStorage = InMemoryCloudAccountStorage(),
    ) = CloudDriveAccountStore(legacy, pool)

    // ------------------------------------------------------------ 旧配置迁移

    @Test
    fun legacy_single_credential_migrates_to_a_primary_account() {
        val legacy = InMemoryCloudCredentialStorage(
            mapOf(SharePlatform.QUARK to "old-quark", SharePlatform.UC to "old-uc"),
        )
        val pool = InMemoryCloudAccountStorage()
        val s = store(legacy, pool)

        assertEquals("old-quark", s.credentialFor(SharePlatform.QUARK))
        assertEquals("old-uc", s.credentialFor(SharePlatform.UC))
        assertEquals(1, s.accountCount(SharePlatform.QUARK))
        assertTrue(s.accountsFor(SharePlatform.QUARK).single().isPrimary)
        assertEquals(2, pool.load().size)

        // 迁移后旧投影仍在：GitHubTokenStore / 降级回旧版本都能读到主账号
        assertEquals("old-quark", legacy.load()[SharePlatform.QUARK])
    }

    @Test
    fun explicitly_clearing_all_accounts_is_not_resurrected_on_restart() {
        val legacy = InMemoryCloudCredentialStorage(mapOf(SharePlatform.QUARK to "old"))
        val pool = InMemoryCloudAccountStorage()
        store(legacy, pool).clear(SharePlatform.QUARK)

        // clear 同时清掉兼容投影，否则下次启动会把它当「旧版单凭证」再迁移回来
        assertTrue(legacy.load().isEmpty())
        assertEquals(0, store(legacy, pool).accountCount(SharePlatform.QUARK))
    }

    @Test
    fun an_existing_empty_pool_is_never_overwritten_by_the_projection() {
        // 模拟「用户主动清空账号池，但旧投影文件因外部原因仍残留」：
        // 账号池存在且非空时优先用账号池；为空且投影有值时才按升级处理。
        val legacy = InMemoryCloudCredentialStorage(mapOf(SharePlatform.UC to "stale"))
        val pool = InMemoryCloudAccountStorage(
            listOf(
                CloudAccount("a-1", SharePlatform.QUARK, "fresh", isPrimary = true),
            ),
        )
        val s = store(legacy, pool)
        assertEquals("fresh", s.credentialFor(SharePlatform.QUARK))
        assertEquals(0, s.accountCount(SharePlatform.UC))
    }

    // ------------------------------------------------------------ 多账号 CRUD

    @Test
    fun adding_accounts_keeps_exactly_one_primary_and_primary_first() {
        val s = store()
        assertNull(s.addAccount(SharePlatform.QUARK, "   "))
        assertEquals(0, s.accountCount(SharePlatform.QUARK))

        val first = assertNotNull(s.addAccount(SharePlatform.QUARK, "one", remark = "一号"))
        val second = assertNotNull(s.addAccount(SharePlatform.QUARK, "two", remark = "二号"))
        assertNotEquals(first, second)

        // 第一条自动成为主账号；后续添加不改主账号
        assertEquals("one", s.credentialFor(SharePlatform.QUARK))
        assertEquals(2, s.accountCount(SharePlatform.QUARK))
        assertEquals(1, s.accountsFor(SharePlatform.QUARK).count { it.isPrimary })
        assertTrue(s.accountsFor(SharePlatform.QUARK).first().isPrimary)
        assertEquals("二号", s.accountById(SharePlatform.QUARK, second)?.remark)
    }

    @Test
    fun set_primary_rename_and_remove_keep_the_pool_consistent() {
        val s = store()
        s.set(SharePlatform.QUARK, "primary")
        val second = assertNotNull(s.addAccount(SharePlatform.QUARK, "second"))
        val third = assertNotNull(s.addAccount(SharePlatform.QUARK, "third"))

        s.setPrimary(SharePlatform.QUARK, third)
        assertEquals("third", s.credentialFor(SharePlatform.QUARK))
        assertEquals(1, s.accountsFor(SharePlatform.QUARK).count { it.isPrimary })
        assertTrue(s.accountById(SharePlatform.QUARK, third)?.isPrimary == true)
        assertFalse(s.accountById(SharePlatform.QUARK, second)?.isPrimary == true)

        s.rename(SharePlatform.QUARK, second, "  备用  ")
        assertEquals("备用", s.accountById(SharePlatform.QUARK, second)?.remark)

        // 删掉主账号后自动把剩余账号里最近更新的一条补成主账号
        s.remove(SharePlatform.QUARK, third)
        assertEquals(2, s.accountCount(SharePlatform.QUARK))
        assertEquals(1, s.accountsFor(SharePlatform.QUARK).count { it.isPrimary })
        assertTrue(s.accountsFor(SharePlatform.QUARK).first().isPrimary)
    }

    @Test
    fun legacy_set_overwrites_the_primary_without_creating_a_new_account() {
        val s = store()
        s.set(SharePlatform.QUARK, "first")
        s.addAccount(SharePlatform.QUARK, "second")
        s.set(SharePlatform.QUARK, "refreshed")

        assertEquals(2, s.accountCount(SharePlatform.QUARK))
        assertEquals("refreshed", s.credentialFor(SharePlatform.QUARK))
        assertEquals(listOf(SharePlatform.QUARK), s.configuredPlatforms())
    }

    @Test
    fun all_credentials_are_primary_first_deduplicated_and_non_blank() {
        val s = store()
        s.set(SharePlatform.QUARK, "primary")
        s.addAccount(SharePlatform.QUARK, "second")
        s.addAccount(SharePlatform.QUARK, "primary") // 重复 Cookie 轮换没有意义
        s.addAccount(SharePlatform.QUARK, "   ")

        assertEquals(listOf("primary", "second"), s.allCredentials(SharePlatform.QUARK))
        assertTrue(s.allCredentials(SharePlatform.UC).isEmpty())
    }

    @Test
    fun refreshed_token_is_written_back_to_the_account_that_was_used() {
        val s = store()
        s.set(SharePlatform.QUARK, "primary")
        val second = assertNotNull(s.addAccount(SharePlatform.QUARK, "second"))

        s.updateCredential(SharePlatform.QUARK, "second", "second-refreshed")
        assertEquals("second-refreshed", s.accountById(SharePlatform.QUARK, second)?.credential)
        assertEquals("primary", s.credentialFor(SharePlatform.QUARK))

        // P2-10：找不到活跃凭证时**丢弃**刷新结果，绝不回落到主账号。
        // 旧实现的回落会把 B 账号刷新出的 token 静默写进 A，造成多账号串号。
        s.updateCredential(SharePlatform.QUARK, "gone", "primary-refreshed")
        assertEquals("primary", s.credentialFor(SharePlatform.QUARK))
        assertEquals("second-refreshed", s.accountById(SharePlatform.QUARK, second)?.credential)
        s.updateCredential(SharePlatform.QUARK, "primary", "  ")
        assertEquals("primary", s.credentialFor(SharePlatform.QUARK))
    }

    @Test
    fun mask_never_leaks_the_middle_of_a_credential() {
        val s = store()
        s.set(SharePlatform.QUARK, "whole-cookie-value-1234")
        val masked = s.maskCredential(s.accountsFor(SharePlatform.QUARK).single())
        assertTrue(masked.startsWith("who"))
        assertTrue(masked.endsWith("34"))
        assertFalse(masked.contains("cookie"))
    }

    // ------------------------------------------------------------ 文件实现

    @Test
    fun file_storage_round_trips_accounts_without_plaintext_and_with_0600() {
        val dir = tempDir()
        val filePool = FileCloudAccountStorage(dir)
        val fileProjection = FileCloudCredentialStorage(dir)
        val s = CloudDriveAccountStore(fileProjection, filePool)
        s.set(SharePlatform.QUARK, "super-secret-cookie")
        s.addAccount(SharePlatform.QUARK, "second-secret", nickname = "小号", remark = "家里用")
        s.set(SharePlatform.UC, "uc-secret")

        val file = dir.resolve("cloud-accounts.properties")
        val raw = file.toFile().readText()
        assertFalse(raw.contains("super-secret-cookie"))
        assertFalse(raw.contains("second-secret"))
        // Properties.store 会把 value 里的 ':' 写成 '\:'（旧 CloudCredentialStorage 也踩过），
        // 因此这里只验证密文版本标记，格式本身由下面的重启往返验证
        assertTrue(raw.contains("v2"))
        assertTrue(raw.startsWith("#")) // Properties.store 的时间戳注释

        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(file),
            )
            // 主密钥在 keys/ 子目录（P3-08 的布局），不能按 dataDir 根部去猜路径：
            // 写得不对时 Windows 上被 posix 判断整体跳过，只有 Linux/macOS 的 CI 才会暴露
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(CloudCredentialKeyStore.keyFile(dir)),
            )
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(CloudCredentialKeyStore.keyDir(dir)),
                "keys/ 目录必须是 0700，否则同机其他用户能读到密钥文件名",
            )
        }

        // 换一组实例重新读：模拟应用重启
        val reloaded = CloudDriveAccountStore(
            FileCloudCredentialStorage(dir),
            FileCloudAccountStorage(dir),
        )
        assertEquals(2, reloaded.accountCount(SharePlatform.QUARK))
        assertEquals("super-secret-cookie", reloaded.credentialFor(SharePlatform.QUARK))
        assertEquals(
            "家里用",
            reloaded.accountsFor(SharePlatform.QUARK).firstNotNullOf { it.remark.takeIf(String::isNotBlank) },
        )
        assertEquals("小号", reloaded.accountsFor(SharePlatform.QUARK).firstNotNullOf { it.nickname.takeIf(String::isNotBlank) })
        assertEquals("uc-secret", reloaded.credentialFor(SharePlatform.UC))
        assertEquals(1, reloaded.accountsFor(SharePlatform.UC).count { it.isPrimary })

        // 兼容投影也写进了主账号
        assertEquals("super-secret-cookie", FileCloudCredentialStorage(dir).load()[SharePlatform.QUARK])
    }

    @Test
    fun ciphertext_moved_to_another_account_id_is_rejected_by_aad() {
        val dir = tempDir()
        val s = CloudDriveAccountStore(FileCloudCredentialStorage(dir), FileCloudAccountStorage(dir))
        s.set(SharePlatform.QUARK, "cannot-be-replayed")

        val file = dir.resolve("cloud-accounts.properties")
        val props = Properties().also { file.toFile().inputStream().use(it::load) }
        val sourceKey = props.stringPropertyNames().first { it.startsWith("acct|") }
        val cipher = props.getProperty(sourceKey)
        val forgedKey = "acct|QUARK|a-forged"
        props.setProperty(forgedKey, cipher)
        props.setProperty("meta|QUARK|a-forged", "Zm9yZ2Vk||0|0")
        file.toFile().outputStream().use { props.store(it, "tampered") }

        val loaded = FileCloudAccountStorage(dir).load()
        assertEquals(1, loaded.size) // 伪造条目被静默跳过，真实账号仍可用
        assertEquals("cannot-be-replayed", loaded.single().credential)
        assertFalse(loaded.any { it.id == "a-forged" })
    }

    @Test
    fun broken_metadata_degrades_without_dropping_the_account() {
        val dir = tempDir()
        val s = CloudDriveAccountStore(FileCloudCredentialStorage(dir), FileCloudAccountStorage(dir))
        s.set(SharePlatform.QUARK, "still-here")

        val file = dir.resolve("cloud-accounts.properties")
        val props = Properties().also { file.toFile().inputStream().use(it::load) }
        val metaKey = props.stringPropertyNames().first { it.startsWith("meta|") }
        props.setProperty(metaKey, "not-base64!!!")
        file.toFile().outputStream().use { props.store(it, "broken meta") }

        val loaded = FileCloudAccountStorage(dir).load().single()
        assertEquals("still-here", loaded.credential)
        assertEquals("", loaded.remark)
        assertFalse(loaded.isPrimary)
    }

    @Test
    fun saving_an_empty_pool_removes_the_cipher_file() {
        val dir = tempDir()
        val s = CloudDriveAccountStore(FileCloudCredentialStorage(dir), FileCloudAccountStorage(dir))
        s.set(SharePlatform.QUARK, "temporary")
        assertTrue(Files.exists(dir.resolve("cloud-accounts.properties")))

        s.clear(SharePlatform.QUARK)
        assertFalse(Files.exists(dir.resolve("cloud-accounts.properties")))
        assertFalse(Files.exists(dir.resolve("cloud-credentials.properties")))
        assertTrue(Files.exists(dir.resolve("keys/cloud-credentials.key"))) // 主密钥保留复用（P3-08 迁入 keys/）
    }

    @Test
    fun generated_ids_are_unique_and_have_a_stable_prefix() {
        val ids = (1..50).map { newCloudAccountId() }.toSet()
        assertEquals(50, ids.size)
        assertTrue(ids.all { it.startsWith("a-") && it.length > 3 })
    }
}

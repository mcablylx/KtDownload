package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.SharePlatform
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 网盘凭证加密落盘：往返、文件权限、AAD 防重放、损坏数据降级 */
class CloudCredentialStorageTest {

    private val dirs = mutableListOf<java.nio.file.Path>()

    private fun newDir(): java.nio.file.Path =
        Files.createTempDirectory("ktdown-cred-test").also { dirs.add(it) }

    @AfterTest
    fun cleanup() {
        dirs.forEach { dir ->
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    @Test
    fun save_then_load_round_trips_every_platform() {
        val storage = FileCloudCredentialStorage(newDir())
        val original = mapOf(
            SharePlatform.QUARK to "BDUSS=中文 Cookie; __puus=abc",
            SharePlatform.UC to "uc-token-with=equals",
            SharePlatform.XUNLEI to """{"accessToken":"eyJhbGci","refreshToken":"r"}""",
        )
        storage.save(original)

        assertEquals(original, storage.load())
        assertTrue(Files.exists(dirs.single().resolve("cloud-credentials.properties")))
        assertTrue(Files.exists(dirs.single().resolve("keys/cloud-credentials.key")))
    }

    @Test
    fun ciphertext_never_contains_the_plaintext() {
        val dir = newDir()
        FileCloudCredentialStorage(dir).save(mapOf(SharePlatform.BAIDU to "SUPER_SECRET_BDUSS"))
        val file = dir.resolve("cloud-credentials.properties")
        val raw = String(Files.readAllBytes(file))
        assertFalse(raw.contains("SUPER_SECRET_BDUSS"))
        // Properties.store 会把 value 中的 ':' 转义成 '\:'（load 时再还原），
        // 因此按 properties 语义读回后才检查密文条目的版本头。
        val stored = Properties().also { file.toFile().inputStream().use(it::load) }
        assertTrue(stored.getProperty(SharePlatform.BAIDU.name).orEmpty().startsWith("v1:"))
    }

    @Test
    fun saving_an_empty_map_removes_the_ciphertext_file() {
        val dir = newDir()
        val storage = FileCloudCredentialStorage(dir)
        storage.save(mapOf(SharePlatform.QUARK to "cookie"))
        assertTrue(Files.exists(dir.resolve("cloud-credentials.properties")))

        storage.save(emptyMap())
        assertFalse(Files.exists(dir.resolve("cloud-credentials.properties")))
        assertTrue(storage.load().isEmpty())
    }

    @Test
    fun aad_prevents_replaying_one_platform_ciphertext_as_another() {
        val dir = newDir()
        val storage = FileCloudCredentialStorage(dir)
        storage.save(mapOf(SharePlatform.QUARK to "quark-only"))

        // 把夸克的密文原样搬到 UC 键下：GCM 的 AAD 是平台名，解密必须失败
        val file = dir.resolve("cloud-credentials.properties")
        val props = Properties().also { file.toFile().inputStream().use(it::load) }
        props.setProperty(SharePlatform.UC.name, props.getProperty(SharePlatform.QUARK.name))
        file.toFile().outputStream().use { props.store(it, null) }

        val loaded = storage.load()
        assertEquals("quark-only", loaded[SharePlatform.QUARK])
        assertNull(loaded[SharePlatform.UC])
    }

    @Test
    fun corrupted_entries_are_skipped_without_breaking_the_others() {
        val dir = newDir()
        val storage = FileCloudCredentialStorage(dir)
        storage.save(mapOf(SharePlatform.QUARK to "still-good"))

        val file = dir.resolve("cloud-credentials.properties")
        val props = Properties().also { file.toFile().inputStream().use(it::load) }
        props.setProperty(SharePlatform.UC.name, "not-a-valid-v1-entry")
        file.toFile().outputStream().use { props.store(it, null) }

        val loaded = storage.load()
        assertEquals("still-good", loaded[SharePlatform.QUARK])
        assertNull(loaded[SharePlatform.UC])
    }

    @Test
    fun replacing_the_master_key_makes_old_credentials_unreadable_but_does_not_throw() {
        val dir = newDir()
        val storage = FileCloudCredentialStorage(dir)
        storage.save(mapOf(SharePlatform.QUARK to "old-cookie"))

        Files.delete(dir.resolve("keys/cloud-credentials.key"))
        assertTrue(storage.load().isEmpty())
        // 新密钥写新凭证要能正常工作
        storage.save(mapOf(SharePlatform.QUARK to "new-cookie"))
        assertEquals("new-cookie", storage.load()[SharePlatform.QUARK])
    }

    @Test
    fun files_are_private_when_the_fs_supports_posix_permissions() {
        val dir = newDir()
        FileCloudCredentialStorage(dir).save(mapOf(SharePlatform.QUARK to "cookie"))
        val permissions = runCatching {
            PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("cloud-credentials.properties")))
        }.getOrNull() ?: return // Windows 不支持 POSIX 权限，跳过
        assertEquals("rw-------", permissions)
        assertEquals(
            "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("keys/cloud-credentials.key"))),
        )
    }

    @Test
    fun in_memory_storage_is_isolated() {
        val store = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
        store.set(SharePlatform.QUARK, "cookie")
        assertTrue(store.isConfigured(SharePlatform.QUARK))
        assertEquals("", CloudDriveAccountStore(InMemoryCloudCredentialStorage()).credentialFor(SharePlatform.QUARK))
    }

    // ---- P3-08：主密钥与密文分目录 + 旧 key 无损迁移 ----

    @Test
    fun master_key_lives_in_a_separate_subdirectory_from_ciphertext() {
        val dir = newDir()
        FileCloudCredentialStorage(dir).save(mapOf(SharePlatform.QUARK to "cookie"))
        val key = dir.resolve("keys/cloud-credentials.key")
        val store = dir.resolve("cloud-credentials.properties")
        assertTrue(Files.exists(key))
        assertTrue(Files.exists(store))
        assertEquals("keys", key.parent.fileName.toString())
        assertEquals(dir, store.parent)
    }

    @Test
    fun legacy_root_key_is_migrated_without_losing_credentials() {
        val dir = newDir()
        val storage = FileCloudCredentialStorage(dir)
        storage.save(mapOf(SharePlatform.QUARK to "legacy-cookie"))
        val legacy = dir.resolve("cloud-credentials.key")
        val migrated = dir.resolve("keys/cloud-credentials.key")
        // 模拟旧版布局：key 直接放在 dataDir 根部，与密文同目录
        Files.move(migrated, legacy)
        assertFalse(Files.exists(migrated))

        assertEquals("legacy-cookie", storage.load()[SharePlatform.QUARK])
        assertTrue(Files.exists(migrated), "旧密钥应迁到 keys/ 子目录")
        assertFalse(Files.exists(legacy), "迁移成功后旧密钥应删除")
        assertEquals("legacy-cookie", storage.load()[SharePlatform.QUARK], "迁移后再次读取仍应能解密")
    }

    @Test
    fun keys_directory_is_private_when_posix_is_available() {
        val dir = newDir()
        FileCloudCredentialStorage(dir).save(mapOf(SharePlatform.QUARK to "cookie"))
        val permissions = runCatching {
            PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("keys")))
        }.getOrNull() ?: return // Windows 不支持 POSIX 权限，跳过
        assertEquals("rwx------", permissions)
    }
}

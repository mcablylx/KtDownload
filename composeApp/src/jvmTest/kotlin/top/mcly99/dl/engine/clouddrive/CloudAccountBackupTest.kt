package top.mcly99.dl.engine.clouddrive

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.mcly99.dl.data.network.SharePlatform

/**
 * 账号备份：口令加密往返、篡改检测、合并语义。
 *
 * 这里最该守住的两条：**口令错不能返回空列表**（用户会以为恢复了），
 * **合并不能覆盖本机已有账号**（恢复备份不该有删除语义）。
 */
class CloudAccountBackupTest {

    private fun withTempDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("ktab-test")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private val accounts = listOf(
        CloudAccount(
            id = "a-1",
            platform = SharePlatform.QUARK,
            credential = "quark-cookie-secret",
            nickname = "主号",
            remark = "常用",
            isPrimary = true,
            updatedAt = 1_700_000_000_000L,
        ),
        CloudAccount(
            id = "a-2",
            platform = SharePlatform.PAN115,
            credential = "UID=1_A1_1;CID=abc;SEID=def",
            nickname = "",
            remark = "小号",
            isPrimary = false,
            updatedAt = 1_700_000_000_001L,
        ),
    )

    @Test
    fun roundtrip_keeps_every_field_including_primary_flag() = withTempDir { dir ->
        val target = dir.resolve("accounts.ktab")
        val written = CloudAccountBackup.export(accounts, "pass-123".toCharArray(), target, "1.5.5")
        assertEquals(2, written)
        assertTrue(CloudAccountBackup.looksLikeBackup(target))

        val backup = CloudAccountBackup.import(target, "pass-123".toCharArray())
        assertEquals("1.5.5", backup.appVersion)
        assertTrue(backup.exportedAt > 0)
        assertEquals(accounts.size, backup.accounts.size)
        val quark = backup.accounts.first { it.platform == SharePlatform.QUARK }
        assertEquals("quark-cookie-secret", quark.credential)
        assertEquals("主号", quark.nickname)
        assertEquals("常用", quark.remark)
        assertTrue(quark.isPrimary)
        assertEquals("a-1", quark.id)
        val pan115 = backup.accounts.first { it.platform == SharePlatform.PAN115 }
        assertEquals("小号", pan115.remark)
        assertFalse(pan115.isPrimary)
    }

    @Test
    fun the_backup_file_never_contains_plaintext_credentials() = withTempDir { dir ->
        val target = dir.resolve("accounts.ktab")
        CloudAccountBackup.export(accounts, "pass-123".toCharArray(), target, "1.5.5")
        val text = Files.readString(target, StandardCharsets.UTF_8)
        // 头部是明文（便于识别版本），正文必须是密文
        assertTrue(text.startsWith(CloudAccountBackup.MAGIC))
        assertFalse(text.contains("quark-cookie-secret"), "凭证不能以明文出现")
        assertFalse(text.contains("小号"), "备注同样在密文里")
    }

    @Test
    fun a_wrong_passphrase_is_reported_as_an_error_not_an_empty_result() = withTempDir { dir ->
        val target = dir.resolve("accounts.ktab")
        CloudAccountBackup.export(accounts, "pass-123".toCharArray(), target, "1.5.5")
        val error = assertFailsWith<IllegalStateException> {
            CloudAccountBackup.import(target, "pass-124".toCharArray())
        }
        assertTrue(error.message.orEmpty().contains("口令"))
    }

    @Test
    fun tampering_with_the_payload_is_detected() = withTempDir { dir ->
        val target = dir.resolve("accounts.ktab")
        CloudAccountBackup.export(accounts, "pass-123".toCharArray(), target, "1.5.5")
        val lines = Files.readAllLines(target)
        // 翻掉载荷里的一个字符：GCM tag 校验必须失败
        val index = lines.indexOfFirst { it.length > 40 && !it.contains('=') }
        assertTrue(index >= 0, "备份文件应该有多行载荷")
        val broken = lines.toMutableList()
        val line = broken[index]
        broken[index] = (if (line[0] == 'A') "B" else "A") + line.substring(1)
        Files.write(target, broken)

        assertFailsWith<IllegalStateException> {
            CloudAccountBackup.import(target, "pass-123".toCharArray())
        }
    }

    @Test
    fun lowering_the_iteration_count_in_the_header_is_detected() = withTempDir { dir ->
        val target = dir.resolve("accounts.ktab")
        CloudAccountBackup.export(accounts, "pass-123".toCharArray(), target, "1.5.5")
        val tampered = Files.readAllLines(target).map { line ->
            if (line.startsWith("iterations=")) "iterations=1" else line
        }
        Files.write(target, tampered)

        // 迭代次数被写进 AAD：改小它会让 GCM 校验失败，而不是降级成弱 KDF
        assertFailsWith<IllegalStateException> {
            CloudAccountBackup.import(target, "pass-123".toCharArray())
        }
    }

    @Test
    fun a_file_from_another_tool_is_rejected_with_a_clear_message() = withTempDir { dir ->
        val target = dir.resolve("notes.txt")
        Files.writeString(target, "hello world\n")
        assertFalse(CloudAccountBackup.looksLikeBackup(target))
        val error = assertFailsWith<IllegalArgumentException> {
            CloudAccountBackup.import(target, "pass-123".toCharArray())
        }
        assertTrue(error.message.orEmpty().contains("备份"))
    }

    @Test
    fun a_newer_format_version_is_refused_instead_of_being_misread() = withTempDir { dir ->
        val target = dir.resolve("accounts.ktab")
        CloudAccountBackup.export(accounts, "pass-123".toCharArray(), target, "1.5.5")
        val bumped = Files.readAllLines(target).map { line ->
            if (line.startsWith("version=")) "version=${CloudAccountBackup.FORMAT_VERSION + 1}" else line
        }
        Files.write(target, bumped)

        val error = assertFailsWith<IllegalArgumentException> {
            CloudAccountBackup.import(target, "pass-123".toCharArray())
        }
        assertTrue(error.message.orEmpty().contains("版本"))
    }

    @Test
    fun an_empty_account_pool_exports_zero_and_imports_nothing() = withTempDir { dir ->
        val target = dir.resolve("empty.ktab")
        assertEquals(0, CloudAccountBackup.export(emptyList(), "pass-123".toCharArray(), target, "1.5.5"))
        assertTrue(CloudAccountBackup.import(target, "pass-123".toCharArray()).accounts.isEmpty())
    }

    @Test
    fun a_blank_passphrase_is_refused() = withTempDir { dir ->
        assertFailsWith<IllegalArgumentException> {
            CloudAccountBackup.export(accounts, CharArray(0), dir.resolve("x.ktab"), "1.5.5")
        }
    }

    // ------------------------------------------------------------ 合并语义

    @Test
    fun merging_dedupes_by_platform_and_credential() {
        val store = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
        store.set(SharePlatform.QUARK, "cookie-a")
        assertEquals(1, store.accountCount(SharePlatform.QUARK))

        // 同一份 Cookie 再导入一次：不产生第二个账号
        assertEquals(0, store.mergeAll(listOf(account("q1", SharePlatform.QUARK, "cookie-a"))))
        assertEquals(1, store.accountCount(SharePlatform.QUARK))

        // 不同平台 / 不同凭证才算新增
        assertEquals(2, store.mergeAll(
            listOf(
                account("q2", SharePlatform.QUARK, "cookie-b"),
                account("u1", SharePlatform.UC, "cookie-a"),
            ),
        ))
        assertEquals(2, store.accountCount(SharePlatform.QUARK))
        assertEquals(1, store.accountCount(SharePlatform.UC))
        assertEquals("cookie-a", store.credentialFor(SharePlatform.QUARK), "主账号不能被导入覆盖")
    }

    @Test
    fun merging_keeps_existing_accounts_when_an_id_collides_but_the_credential_differs() {
        val store = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
        store.addAccount(SharePlatform.QUARK, "cookie-mine", nickname = "本机")
        val mine = store.accountsFor(SharePlatform.QUARK).single()

        // 另一台机器随机生成了同一个 id：必须换新 id 而不是覆盖本机账号
        store.mergeAll(listOf(account(mine.id, SharePlatform.QUARK, "cookie-theirs")))

        val all = store.accountsFor(SharePlatform.QUARK)
        assertEquals(2, all.size)
        assertTrue(all.any { it.credential == "cookie-mine" && it.nickname == "本机" })
        assertTrue(all.any { it.credential == "cookie-theirs" && it.id != mine.id })
    }

    @Test
    fun merging_an_empty_list_is_a_no_op() {
        val store = CloudDriveAccountStore(InMemoryCloudCredentialStorage())
        assertEquals(0, store.mergeAll(emptyList()))
        assertTrue(store.configuredPlatforms().isEmpty())
    }

    private fun account(id: String, platform: SharePlatform, credential: String) = CloudAccount(
        id = id,
        platform = platform,
        credential = credential,
    )
}

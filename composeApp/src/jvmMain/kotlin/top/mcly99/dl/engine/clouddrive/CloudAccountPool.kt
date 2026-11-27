package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import top.mcly99.dl.utils.FilePermissions
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 一个网盘账号（P3 · 自研多账号池，**不是**上游 AGPL 移植代码）。
 *
 * 上游用 10 张 SQLite `*_account` 表 + `isPrimary` / `remark`；ktdown 不引入 sqlite-jdbc，
 * 改为「一个加密 properties 文件 + 复合键」达到同等能力：同一平台可以存多条凭证，
 * 其中恰好一条是主账号（列目录 / 转存 / 上传等单一身份场景用它）。
 *
 * @property credential 明文 Cookie / token / 迅雷结构化 JSON；只在内存与密文文件里存在
 * @property isPrimary 主账号；同一平台最多一条，由 [CloudDriveAccountStore.normalize] 保证
 */
data class CloudAccount(
    val id: String,
    val platform: SharePlatform,
    val credential: String,
    val nickname: String = "",
    val remark: String = "",
    val isPrimary: Boolean = false,
    val updatedAt: Long = 0L,
)

/** 账号池持久化后端；测试注入 [InMemoryCloudAccountStorage] 避免读写用户目录。 */
interface CloudAccountStorage {
    fun load(): List<CloudAccount>
    fun save(accounts: List<CloudAccount>)
}

/** 内存实现：与 [InMemoryCloudCredentialStorage] 配套，供 jvmTest 注入。 */
class InMemoryCloudAccountStorage(
    initial: List<CloudAccount> = emptyList(),
) : CloudAccountStorage {
    private val rows = java.util.concurrent.CopyOnWriteArrayList<CloudAccount>(initial)

    override fun load(): List<CloudAccount> = rows.toList()

    override fun save(accounts: List<CloudAccount>) {
        rows.clear()
        rows.addAll(accounts)
    }
}

/**
 * 文件实现：`~/.mclydownload/cloud-accounts.properties`。
 *
 * - 密文 key：`acct|<PLATFORM>|<id>` → `v2:<iv>:<ct>`，AES-256-GCM、12 字节 IV、128 位 tag；
 * - **AAD 绑到「平台 + 账号 id」**：把 A 账号的密文搬到 B 账号键下会解密失败；
 * - 元数据 key：`meta|<PLATFORM>|<id>` → `base64(nickname)|base64(remark)|isPrimary|updatedAt`，
 *   不含任何凭证，明文存放以便无密钥时也能列出账号；
 * - 主密钥复用 `keys/cloud-credentials.key`（与 [FileCloudCredentialStorage] 通过
 *   [CloudCredentialKeyStore] 共享同一份 32 字节，P3-08 迁入独立子目录），
 *   加密参数一致，只是 purpose 不同，因此两套密文互不干扰；
 * - 目录 `0700`、文件 `0600`，写入走 `.tmp` + `ATOMIC_MOVE`。
 */
class FileCloudAccountStorage(
    private val dataDir: Path = AppConstants.APP_DATA_DIR,
) : CloudAccountStorage {

    private val storeFile: Path get() = dataDir.resolve("cloud-accounts.properties")

    override fun load(): List<CloudAccount> {
        if (!Files.exists(storeFile)) return emptyList()
        return runCatching {
            val props = Properties().also { storeFile.toFile().inputStream().use(it::load) }
            val key = CloudCredentialKeyStore.readOrCreate(dataDir) ?: return@runCatching emptyList()
            val out = mutableListOf<CloudAccount>()
            for (name in props.stringPropertyNames()) {
                if (!name.startsWith(ACCT_PREFIX)) continue
                val (platform, id) = parseKey(name) ?: continue
                val plain = runCatching { decrypt(props.getProperty(name), purpose(platform, id), key) }
                    .onFailure {
                        AppLogger.warn(
                            "CloudAccountPool",
                            "跳过无法解密的 ${platform.name} 账号（主密钥可能已更换）",
                        )
                    }
                    .getOrNull()
                if (plain.isNullOrEmpty()) continue
                val meta = parseMeta(props.getProperty(META_PREFIX + "${platform.name}|$id"))
                out += CloudAccount(
                    id = id,
                    platform = platform,
                    credential = plain,
                    nickname = meta.first,
                    remark = meta.second,
                    isPrimary = meta.third,
                    updatedAt = meta.fourth,
                )
            }
            out
        }.onFailure { e ->
            AppLogger.warn("CloudAccountPool", "读取网盘账号池失败，按未登录处理: ${e.message}", e)
        }.getOrDefault(emptyList())
    }

    override fun save(accounts: List<CloudAccount>) {
        runCatching {
            Files.createDirectories(dataDir)
            if (accounts.isEmpty()) {
                Files.deleteIfExists(storeFile)
                return
            }
            val key = CloudCredentialKeyStore.readOrCreate(dataDir) ?: return
            val props = Properties()
            for (account in accounts) {
                if (account.credential.isBlank()) continue
                val suffix = "${account.platform.name}|${account.id}"
                props.setProperty(
                    ACCT_PREFIX + suffix,
                    encrypt(account.credential, purpose(account.platform, account.id), key),
                )
                props.setProperty(META_PREFIX + suffix, encodeMeta(account))
            }
            val tmp = dataDir.resolve("cloud-accounts.properties.tmp")
            tmp.toFile().outputStream().use { props.store(it, "KtDownload encrypted cloud-drive accounts") }
            Files.move(
                tmp, storeFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            hardenPermissions()
        }.onFailure { e ->
            AppLogger.warn("CloudAccountPool", "保存网盘账号池失败: ${e.message}", e)
        }
    }

    private fun encrypt(plain: String, purpose: String, key: SecretKeySpec): String {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(purpose.toByteArray(StandardCharsets.UTF_8))
        val ct = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        return "$ENTRY_PREFIX:${b64(iv)}:${b64(ct)}"
    }

    private fun decrypt(raw: String, purpose: String, key: SecretKeySpec): String {
        val parts = raw.split(':')
        require(parts.size == 3 && parts[0] == ENTRY_PREFIX) { "未知的账号密文格式" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(parts[1])))
        cipher.updateAAD(purpose.toByteArray(StandardCharsets.UTF_8))
        return String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8)
    }

    private fun purpose(platform: SharePlatform, id: String): String = "$ENTRY_PREFIX|acct|${platform.name}|$id"

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8))

    private fun unb64(text: String): String = runCatching {
        String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8)
    }.getOrDefault("")

    private fun encodeMeta(account: CloudAccount): String = listOf(
        b64(account.nickname),
        b64(account.remark),
        if (account.isPrimary) "1" else "0",
        account.updatedAt.toString(),
    ).joinToString("|")

    /** 元数据损坏时按「非主账号、无备注」降级，绝不因为备注读不出来丢掉整个账号 */
    private fun parseMeta(raw: String?): Meta {
        if (raw.isNullOrBlank()) return Meta("", "", false, 0L)
        val parts = raw.split('|')
        return Meta(
            first = parts.getOrNull(0)?.let(::unb64).orEmpty(),
            second = parts.getOrNull(1)?.let(::unb64).orEmpty(),
            third = parts.getOrNull(2) == "1",
            fourth = parts.getOrNull(3)?.toLongOrNull() ?: 0L,
        )
    }

    private data class Meta(val first: String, val second: String, val third: Boolean, val fourth: Long)

    private fun parseKey(name: String): Pair<SharePlatform, String>? {
        val suffix = name.removePrefix(ACCT_PREFIX)
        val platformName = suffix.substringBefore('|')
        val id = suffix.substringAfter('|', "")
        if (id.isBlank()) return null
        val platform = runCatching { SharePlatform.valueOf(platformName) }.getOrNull() ?: return null
        return platform to id
    }

    private fun hardenPermissions() {
        CloudCredentialKeyStore.harden(dataDir)
        FilePermissions.hardenFile(storeFile)
    }

    private companion object {
        const val ACCT_PREFIX = "acct|"
        const val META_PREFIX = "meta|"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ENTRY_PREFIX = "v2"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** 新账号 id；集中在一处，方便测试断言格式与唯一性 */
fun newCloudAccountId(): String = "a-" + UUID.randomUUID().toString().take(12)

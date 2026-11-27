package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import top.mcly99.dl.utils.FilePermissions
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网盘登录凭证的本地存储。
 *
 * 上游用 SQLite + 列级 AES-256-GCM + 主密钥三档（LOOSE / NORMAL / STRICT），ktdown 不引入
 * sqlite-jdbc，这里用「一个小 properties 文件 + 一个主密钥文件」达到同等的数据保护边界：
 *
 * - 密文：`AES/GCM/NoPadding`，每条凭证一个随机 12 字节 IV；
 * - **AAD 用平台名**：把夸克的密文搬到 UC 键下会解密失败，避免跨用途重放；
 * - 主密钥：随机 32 字节，存在**独立子目录** `keys/cloud-credentials.key`，仅靠 `0600` 文件权限保护
 *   （等价于上游 `DbEncryptionLevel.LOOSE`，没有 Windows DPAPI / 机器指纹包裹）；
 *   P3-08：旧版把 key 与密文放同一目录（与本节原声明不符），现迁移到 `keys/` 并对旧 key 做无损迁移；
 * - POSIX 下目录 `0700`、两个文件 `0600`；Windows 没有 POSIX 权限，由
 *   [FilePermissions] 检查 ACL，对 Everyone / Users 可读时明确告警（P2-16，不再静默 no-op）；
 * - 写入走「唯一临时文件 + ATOMIC_MOVE」，崩溃不会留下半截密文。
 *
 * 与 `TaskStore` 刻意不写会话 Cookie 并不矛盾：那里拒绝的是**明文**落盘，这里是加密落盘。
 * 主密钥与密文分目录存放（`keys/` 子目录），只拿到 `cloud-credentials.properties` 无法还原 Cookie。
 */
interface CloudCredentialStorage {
    fun load(): Map<SharePlatform, String>
    fun save(values: Map<SharePlatform, String>)
}

/** 只在内存里保存；单测与无磁盘环境用，避免污染用户目录 */
class InMemoryCloudCredentialStorage(
    initial: Map<SharePlatform, String> = emptyMap(),
) : CloudCredentialStorage {
    private val values = ConcurrentHashMap<SharePlatform, String>(initial)

    override fun load(): Map<SharePlatform, String> = values.toMap()

    override fun save(values: Map<SharePlatform, String>) {
        this.values.clear()
        this.values.putAll(values)
    }
}

class FileCloudCredentialStorage(
    private val dataDir: Path = AppConstants.APP_DATA_DIR,
) : CloudCredentialStorage {

    private val storeFile: Path get() = dataDir.resolve("cloud-credentials.properties")

    override fun load(): Map<SharePlatform, String> {
        val file = storeFile
        if (!Files.exists(file)) return emptyMap()
        return runCatching {
            val props = Properties().also { file.toFile().inputStream().use(it::load) }
            val key = CloudCredentialKeyStore.readOrCreate(dataDir) ?: return@runCatching emptyMap()
            val result = LinkedHashMap<SharePlatform, String>()
            for (platform in SharePlatform.values()) {
                val raw = props.getProperty(platform.name) ?: continue
                val plain = runCatching { decrypt(raw, platform, key) }
                    .onFailure {
                        AppLogger.warn(
                            "CloudCredentialStorage",
                            "跳过无法解密的 ${platform.name} 凭证（主密钥可能已更换）",
                        )
                    }
                    .getOrNull()
                if (!plain.isNullOrEmpty()) result[platform] = plain
            }
            result
        }.onFailure { e ->
            AppLogger.warn("CloudCredentialStorage", "读取网盘凭证失败，按未登录处理: ${e.message}", e)
        }.getOrDefault(emptyMap())
    }

    override fun save(values: Map<SharePlatform, String>) {
        runCatching {
            Files.createDirectories(dataDir)
            if (values.isEmpty()) {
                // 全部退出登录后不留下密文文件；主密钥保留，下次登录可直接复用
                Files.deleteIfExists(storeFile)
                return
            }
            val key = CloudCredentialKeyStore.readOrCreate(dataDir) ?: return
            val props = Properties()
            for ((platform, value) in values) {
                if (value.isBlank()) continue
                props.setProperty(platform.name, encrypt(value, platform, key))
            }
            val tmp = dataDir.resolve("cloud-credentials.properties.tmp")
            tmp.toFile().outputStream().use { props.store(it, "KtDownload encrypted cloud-drive credentials") }
            Files.move(
                tmp, storeFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            hardenPermissions()
        }.onFailure { e ->
            AppLogger.warn("CloudCredentialStorage", "保存网盘凭证失败: ${e.message}", e)
        }
    }

    private fun encrypt(plain: String, platform: SharePlatform, key: SecretKeySpec): String {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad(platform))
        val ct = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        return "$ENTRY_PREFIX:${b64(iv)}:${b64(ct)}"
    }

    private fun decrypt(raw: String, platform: SharePlatform, key: SecretKeySpec): String {
        val parts = raw.split(':')
        require(parts.size == 3 && parts[0] == ENTRY_PREFIX) { "未知的密文格式" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(parts[1])))
        cipher.updateAAD(aad(platform))
        return String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8)
    }

    private fun aad(platform: SharePlatform): ByteArray =
        "$ENTRY_PREFIX|${platform.name}".toByteArray(StandardCharsets.UTF_8)

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** 每次 save 都用新文件替换：新文件继承 umask（常见 0644），必须重新收紧（P2-16 跨平台） */
    private fun hardenPermissions() {
        CloudCredentialKeyStore.harden(dataDir)
        FilePermissions.hardenFile(storeFile)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ENTRY_PREFIX = "v1"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

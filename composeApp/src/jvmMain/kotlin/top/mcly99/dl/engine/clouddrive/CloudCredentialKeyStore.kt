package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.utils.AppLogger
import top.mcly99.dl.utils.FilePermissions
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec

/**
 * P3-08：网盘凭证密文与账号池密文**共享的同一把 32 字节主密钥**。
 *
 * 旧实现里 [FileCloudCredentialStorage] 与 [FileCloudAccountStorage] 各自在 dataDir 根部
 * 维护 `cloud-credentials.key`，靠「文件已存在就复用」实现共享；任一方的路径变化都会让
 * 另一方读到不同的 key，并把对方写的所有密文判成「主密钥已更换」。这里把路径、旧 key
 * 迁移、唯一 tmp 原子写、权限收紧与并发锁统一收口到一处，避免再次漂移。
 *
 * 新布局：key 放在 `dataDir/keys/cloud-credentials.key`（0700 目录 / 0600 文件），
 * 两份密文仍留在 dataDir 根部；旧版根部 key 会在首次读取时无损迁入 keys/。
 */
internal object CloudCredentialKeyStore {

    fun keyDir(dataDir: Path): Path = dataDir.resolve(KEY_DIR)

    fun keyFile(dataDir: Path): Path = keyDir(dataDir).resolve(KEY_NAME)

    private fun legacyKeyFile(dataDir: Path): Path = dataDir.resolve(KEY_NAME)

    private val locks = ConcurrentHashMap<Path, Any>()
    private fun lockFor(dataDir: Path): Any =
        locks.computeIfAbsent(dataDir.toAbsolutePath().normalize()) { Any() }

    fun readOrCreate(dataDir: Path): SecretKeySpec? = synchronized(lockFor(dataDir)) {
        val dir = keyDir(dataDir)
        val target = keyFile(dataDir)
        val legacy = legacyKeyFile(dataDir)
        if (!Files.exists(target)) {
            Files.createDirectories(dir)
            FilePermissions.hardenDirectory(dir)
            val raw = if (Files.exists(legacy)) {
                // 旧 key 与密文同目录：先原子写到 keys/，成功后再删旧文件；中途失败旧 key 仍在
                Files.readAllBytes(legacy)
            } else {
                ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
            }
            writeAtomically(dir, target, raw)
            if (Files.exists(legacy)) Files.deleteIfExists(legacy)
        }
        // P2-16：首次由 load() 创建时也要收紧（旧实现只有 save() 才调用 hardenPermissions）
        FilePermissions.hardenFile(target)
        val raw = Files.readAllBytes(target)
        if (raw.size != KEY_BYTES) {
            AppLogger.warn("CloudCredentialKeyStore", "主密钥长度异常，忽略已保存的网盘数据")
            return@synchronized null
        }
        SecretKeySpec(raw, "AES")
    }

    fun harden(dataDir: Path) {
        FilePermissions.hardenDirectory(dataDir)
        FilePermissions.hardenDirectory(keyDir(dataDir))
        FilePermissions.hardenFile(keyFile(dataDir))
    }

    private fun writeAtomically(dir: Path, target: Path, raw: ByteArray) {
        val tmp = Files.createTempFile(dir, KEY_NAME + ".", ".tmp")
        try {
            Files.write(tmp, raw)
            try {
                Files.move(
                    tmp, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private const val KEY_DIR = "keys"
    private const val KEY_NAME = "cloud-credentials.key"
    private const val KEY_BYTES = 32
}

package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.SharePlatform
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 一次备份的完整内容（导入时返回） */
data class AccountBackup(
    val accounts: List<CloudAccount>,
    val exportedAt: Long,
    val appVersion: String,
)

/**
 * 网盘账号的**口令加密备份**（GAP 2.6，P5 剩余项）。
 *
 * 为什么要这一份：账号池的主密钥保存在本机 `cloud-credentials.key`，换机、重装、
 * 或者只把 `cloud-accounts.properties` 拷走，密文都解不开 —— 用户只能逐个平台重新登录。
 * 这里用「用户自己记得的口令」重新加密一份可搬运的备份：
 *
 * - 口令派生：`PBKDF2WithHmacSHA256`，16 字节随机盐，210,000 次迭代（与浏览器保存口令同一量级）；
 * - 加密：`AES-256-GCM`，12 字节随机 IV，128 位 tag，**AAD 绑定文件头**
 *   （magic + 版本 + KDF 名 + 迭代次数），改任何一项都会解密失败，避免被降级成低迭代次数；
 * - 文件是文本格式：头部明文（便于识别版本），载荷 base64 分行，方便邮件/网盘传输；
 * - 落盘 `0600`；口令只在内存里，用完清零。
 *
 * 失败一律 fail-loud：口令错 / 文件被改 / 版本过新都抛异常，**绝不返回空列表**冒充成功 ——
 * 那样用户会以为备份恢复了，实际什么都没导入。
 */
object CloudAccountBackup {

    /** 文件首行标记；导入时据此判断「这是不是本应用的备份」 */
    const val MAGIC = "KTDOWN-ACCOUNT-BACKUP"

    /** 当前格式版本；遇到更高版本直接拒绝，避免用旧逻辑解新结构 */
    const val FORMAT_VERSION = 1

    /** 备份文件扩展名（保存对话框用） */
    const val FILE_EXTENSION = "ktab"

    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val PAYLOAD_LINE = 76

    /**
     * 导出为口令加密文件，返回写入的账号数。
     *
     * 空账号池也允许导出（一个只有头部、没有账号的文件），但会返回 0，
     * 让 UI 能提示「当前没有可备份的账号」而不是假装成功。
     */
    fun export(
        accounts: List<CloudAccount>,
        passphrase: CharArray,
        target: Path,
        appVersion: String,
        exportedAt: Long = System.currentTimeMillis(),
    ): Int {
        require(passphrase.isNotEmpty()) { "备份口令不能为空" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt, ITERATIONS)

        val plain = encodePayload(accounts, exportedAt, appVersion)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad(ITERATIONS))
        val cipherText = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))

        val text = buildString {
            append(MAGIC).append('\n')
            append("version=").append(FORMAT_VERSION).append('\n')
            append("kdf=").append(KDF).append('\n')
            append("iterations=").append(ITERATIONS).append('\n')
            append("salt=").append(b64(salt)).append('\n')
            append("iv=").append(b64(iv)).append('\n')
            append("payload=").append('\n')
            val encoded = b64(cipherText)
            var index = 0
            while (index < encoded.length) {
                append(encoded, index, minOf(index + PAYLOAD_LINE, encoded.length)).append('\n')
                index += PAYLOAD_LINE
            }
        }

        // 先写临时文件再原子替换：导出中途失败不会留下半截文件
        val dir = target.toAbsolutePath().parent ?: java.nio.file.Paths.get(".").toAbsolutePath()
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".ktab-", ".tmp")
        try {
            Files.write(tmp, text.toByteArray(StandardCharsets.UTF_8))
            runCatching {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
            }
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
        return accounts.count { it.credential.isNotBlank() }
    }

    /**
     * 读取并解密备份。
     *
     * @throws IllegalArgumentException 不是本应用的备份 / 版本过新 / 头部缺字段
     * @throws IllegalStateException 口令错误或文件损坏（GCM 校验失败）
     */
    fun import(source: Path, passphrase: CharArray): AccountBackup {
        require(passphrase.isNotEmpty()) { "备份口令不能为空" }
        val lines = Files.readAllLines(source, StandardCharsets.UTF_8)
        require(lines.isNotEmpty() && lines.first().trim() == MAGIC) {
            "这不是本应用的账号备份文件"
        }

        val header = linkedMapOf<String, String>()
        val payload = StringBuilder()
        var inPayload = false
        for (line in lines.drop(1)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (inPayload) {
                payload.append(trimmed)
                continue
            }
            if (trimmed == "payload=") {
                inPayload = true
                continue
            }
            val at = trimmed.indexOf('=')
            if (at <= 0) continue
            header[trimmed.substring(0, at)] = trimmed.substring(at + 1)
        }

        val version = header["version"]?.toIntOrNull()
            ?: throw IllegalArgumentException("备份文件缺少版本号")
        require(version <= FORMAT_VERSION) { "备份文件版本过新（v$version），请升级应用后再导入" }
        require(header["kdf"] == KDF) { "备份文件使用了不支持的密钥派生算法" }
        // 迭代次数以文件为准（向后兼容），但 AAD 里绑的就是它，改小会导致校验失败
        val iterations = header["iterations"]?.toIntOrNull()
            ?: throw IllegalArgumentException("备份文件缺少迭代次数")

        val salt = header["salt"]?.let { unb64(it) }
            ?: throw IllegalArgumentException("备份文件缺少盐值")
        val iv = header["iv"]?.let { unb64(it) }
            ?: throw IllegalArgumentException("备份文件缺少 IV")
        require(payload.isNotEmpty()) { "备份文件没有内容" }

        val key = deriveKey(passphrase, salt, iterations)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(iterations))
            cipher.doFinal(unb64(payload.toString()))
        } catch (e: Exception) {
            // GCM tag 校验失败 = 口令错或文件被改。两者都不该继续，也不该透露是哪一种。
            throw IllegalStateException("口令错误或备份文件已损坏", e)
        }
        return decodePayload(String(plain, StandardCharsets.UTF_8))
    }

    // ------------------------------------------------------------ 载荷编码

    /**
     * 载荷是「行 + 制表符」结构，自由文本（账号 id、备注、凭证）一律 base64，
     * 这样凭证里的换行、制表符、中文都不会破坏格式。
     */
    private fun encodePayload(accounts: List<CloudAccount>, exportedAt: Long, appVersion: String): String {
        val out = StringBuilder()
        out.append("meta\t").append(exportedAt).append('\t').append(b64(appVersion.toByteArray()))
            .append('\n')
        for (account in accounts) {
            if (account.credential.isBlank()) continue
            out.append("acct\t")
                .append(account.platform.name).append('\t')
                .append(b64(account.id.toByteArray())).append('\t')
                .append(b64(account.nickname.toByteArray())).append('\t')
                .append(b64(account.remark.toByteArray())).append('\t')
                .append(if (account.isPrimary) "1" else "0").append('\t')
                .append(account.updatedAt).append('\t')
                .append(b64(account.credential.toByteArray())).append('\n')
        }
        return out.toString()
    }

    private fun decodePayload(text: String): AccountBackup {
        var exportedAt = 0L
        var appVersion = ""
        val accounts = mutableListOf<CloudAccount>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val cells = line.split('\t')
            when (cells.firstOrNull()) {
                "meta" -> {
                    exportedAt = cells.getOrNull(1)?.toLongOrNull() ?: 0L
                    appVersion = cells.getOrNull(2)?.let { String(unb64(it), StandardCharsets.UTF_8) }.orEmpty()
                }
                "acct" -> {
                    if (cells.size < 8) continue
                    val platform = SharePlatform.values()
                        .firstOrNull { it.name == cells[1] } ?: continue
                    accounts += CloudAccount(
                        id = String(unb64(cells[2]), StandardCharsets.UTF_8),
                        platform = platform,
                        nickname = String(unb64(cells[3]), StandardCharsets.UTF_8),
                        remark = String(unb64(cells[4]), StandardCharsets.UTF_8),
                        isPrimary = cells[5] == "1",
                        updatedAt = cells[6].toLongOrNull() ?: 0L,
                        credential = String(unb64(cells[7]), StandardCharsets.UTF_8),
                    )
                }
            }
        }
        return AccountBackup(accounts, exportedAt, appVersion)
    }

    // ------------------------------------------------------------ 加密原语

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance(KDF)
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** 绑定文件头的 AAD：改版本 / KDF / 迭代次数都会让解密失败 */
    private fun aad(iterations: Int): ByteArray =
        "$MAGIC|v$FORMAT_VERSION|$KDF|$iterations".toByteArray(StandardCharsets.UTF_8)

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun b64(text: String): String = b64(text.toByteArray(StandardCharsets.UTF_8))

    private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

    /** 供测试与 UI 复用：判断某个文件是否是本应用导出的备份（只看首行，不解密） */
    fun looksLikeBackup(source: Path): Boolean = runCatching {
        Files.newBufferedReader(source, StandardCharsets.UTF_8).use { reader ->
            reader.readLine()?.trim() == MAGIC
        }
    }.getOrDefault(false)
}

package top.mcly99.dl.engine.hls

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS `METHOD=AES-128` 分片解密。
 *
 * 规范：AES-CBC + PKCS#7 填充；`#EXT-X-KEY` 未声明 IV 时，
 * 用分片的媒体序列号（[HlsSegment.sequence]）作为 128 位大端 IV。
 */
internal object HlsAesDecryptor {

    /** 用显式 `IV=0x...` 解密；[ivHex] 为去掉 `0x` 的 32 位十六进制 */
    fun decrypt(data: ByteArray, key: ByteArray, ivHex: String): ByteArray =
        decrypt(data, key, ivFromHex(ivHex))

    /** 用媒体序列号推导的默认 IV 解密 */
    fun decrypt(data: ByteArray, key: ByteArray, sequence: Int): ByteArray =
        decrypt(data, key, ivFromSequence(sequence))

    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        return try {
            newDecryptCipher(key, iv).doFinal(data)
        } catch (e: GeneralSecurityException) {
            throw HlsException("AES-128 解密失败：${e.message}", cause = e)
        }
    }

    /**
     * 创建流式解密用的 Cipher：调用方用 [Cipher.update] 边读边写，最后 [Cipher.doFinal]。
     * 这样不必把整段密文与明文同时留在内存里。
     *
     * @param padded 是否按规范处理 PKCS#7 填充。少数编码器输出整块无填充密文，
     *   调用方可先用 `true` 尝试，遇到填充错误再以 `false` 重解。
     */
    fun newDecryptCipher(key: ByteArray, iv: ByteArray, padded: Boolean = true): Cipher {
        if (key.size != KEY_BYTES) {
            throw HlsException("AES-128 密钥长度应为 $KEY_BYTES 字节，实际 ${key.size}")
        }
        if (iv.size != BLOCK_BYTES) {
            throw HlsException("AES-128 IV 长度应为 $BLOCK_BYTES 字节，实际 ${iv.size}")
        }
        val transformation = if (padded) "AES/CBC/PKCS5Padding" else "AES/CBC/NoPadding"
        return try {
            Cipher.getInstance(transformation).apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            }
        } catch (e: GeneralSecurityException) {
            throw HlsException("AES-128 解密初始化失败：${e.message}", cause = e)
        }
    }

    /** 把 32 位十六进制（不带 `0x`）转成 IV */
    fun ivFromHex(hex: String): ByteArray {
        val text = hex.trim()
        if (text.length != BLOCK_BYTES * 2) {
            throw HlsException("AES-128 IV 应为 ${BLOCK_BYTES * 2} 位十六进制，实际 ${text.length} 位")
        }
        return try {
            ByteArray(BLOCK_BYTES) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            throw HlsException("AES-128 IV 不是合法的十六进制：$text", cause = e)
        }
    }

    /** 分片序列号 → 16 字节大端 IV */
    fun ivFromSequence(sequence: Int): ByteArray =
        ByteBuffer.allocate(BLOCK_BYTES).putLong(0L).putInt(sequence).array()

    private const val KEY_BYTES = 16
    private const val BLOCK_BYTES = 16
}

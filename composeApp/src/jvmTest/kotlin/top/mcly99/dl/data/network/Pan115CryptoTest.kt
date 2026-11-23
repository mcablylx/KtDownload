package top.mcly99.dl.data.network

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 115 下载协议加解密契约（P3-T3）。
 *
 * `encode` / `decode` 的分工不对称：请求体用**公钥**加密（服务端解），响应用**私钥**解密，
 * 所以不存在「encode 完再 decode 回到原文」的往返测试。这里锁住的是退化后仍能跑、
 * 却会让整条取链悄悄失败的部分：
 * - key 必须 16 字节（协议常量，改错会在真实请求里表现为 400/空响应）；
 * - 同一 payload + key 必须产出同一串（否则签名/重放校验通不过）；
 * - 输出必须是合法 Base64 且长度对齐 RSA 块；
 * - 响应长度异常时要明确抛错，而不是把垃圾当明文交给上层 JSON 解析。
 */
class Pan115CryptoTest {

    private val key = ByteArray(16) { (it * 7 + 3).toByte() }

    private val payload = """{"pick_code":"abc123","sha1":"da39a3ee5e6b4b0d3255bfef95601890afd80709"}"""

    @Test
    fun new_key_is_sixteen_bytes_and_differs_between_calls() {
        val first = Pan115Crypto.newKey()
        val second = Pan115Crypto.newKey()
        assertEquals(16, first.size)
        assertEquals(16, second.size)
        assertFalse(first.contentEquals(second), "每次请求的 key 必须随机，复用会被服务端判定重放")
    }

    @Test
    fun encode_is_deterministic_for_same_payload_and_key() {
        assertEquals(Pan115Crypto.encode(payload, key), Pan115Crypto.encode(payload, key))
    }

    @Test
    fun encode_output_is_base64_and_block_aligned() {
        val encoded = Pan115Crypto.encode(payload, key)
        val decoded = Base64.getDecoder().decode(encoded) // 非合法 Base64 会在这里抛异常
        assertEquals(128, decoded.size, "单块 RSA 加密结果应为 128 字节（1024 位）")
    }

    @Test
    fun encode_rejects_key_with_wrong_length() {
        assertFailsWith<IllegalArgumentException> {
            Pan115Crypto.encode(payload, ByteArray(8))
        }
        assertFailsWith<IllegalArgumentException> {
            Pan115Crypto.decode(Base64.getEncoder().encodeToString(ByteArray(128)), ByteArray(15))
        }
    }

    @Test
    fun decode_rejects_empty_response() {
        // 空响应解不出 16 字节 salt，必须报「长度异常」而不是把空串当 JSON 交给上层
        assertFailsWith<IllegalStateException> { Pan115Crypto.decode("", key) }
    }

    @Test
    fun different_payloads_do_not_collide() {
        val other = """{"pick_code":"zzz999"}"""
        assertTrue(Pan115Crypto.encode(payload, key) != Pan115Crypto.encode(other, key))
    }
}

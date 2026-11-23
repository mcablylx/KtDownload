package top.mcly99.dl.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 蓝奏 `acw_sc__v2` 计算回归（P3-T3）。
 *
 * 蓝奏在风控时会返回一页 JS 挑战，必须本地算出 Cookie 才能继续登录/解析。
 * 这段算法有两处「抄错也照样能跑」的风险：
 * ① 40 位的重排表若出现重复或越界，字符会被静默丢掉，结果长度不变但值全错；
 * ② 异或掩码被误改时，只有真实请求才会暴露（表现为一直提示「请求过于频繁」）。
 * 这里用独立实现的期望值把两者都钉住。
 */
class LanzouCryptoTest {

    /** 示例 arg1：40 位十六进制（与服务端下发格式一致） */
    private val arg1 = "91af6a1c2b3d4e5f60718293a4b5c6d7e8f90123"

    /** 参考实现里的异或掩码，独立于被测代码写死，改错 MASK 这里会红 */
    private val mask = "3000176000856006061501533003690027800375"

    @Test
    fun permutation_table_covers_every_position_exactly_once() {
        val pos = LanzouCrypto.POS
        assertEquals(40, pos.size, "重排表长度必须与 arg1 等长")
        assertEquals((1..40).toSet(), pos.toSet(), "重排表必须是 1..40 的排列：重复或越界会丢字符")
    }

    @Test
    fun result_is_forty_lowercase_hex_chars_and_deterministic() {
        // arg1 是 40 位十六进制，按字节两两异或后仍是 40 个字符（20 字节）
        val value = LanzouCrypto.acwScV2(arg1)
        assertEquals(40, value.length, "40 位 arg1 → 20 组异或 → 40 个十六进制字符")
        assertTrue(value.all { it in '0'..'9' || it in 'a'..'f' }, "应为小写十六进制：$value")
        assertEquals(value, LanzouCrypto.acwScV2(arg1), "同输入必须得到同输出")
    }

    @Test
    fun matches_independently_written_permutation_and_xor() {
        // 用倒排写法重算一遍：pos[z] 是 1-based，字符取 arg1[pos[z] - 1]
        val permuted = buildString {
            LanzouCrypto.POS.forEach { p -> append(arg1[p - 1]) }
        }
        val expected = buildString {
            var i = 0
            while (i + 2 <= permuted.length) {
                val a = permuted.substring(i, i + 2).toInt(16)
                val b = mask.substring(i, i + 2).toInt(16)
                append("%02x".format(a xor b))
                i += 2
            }
        }
        assertEquals(expected, LanzouCrypto.acwScV2(arg1))
    }

    @Test
    fun every_source_position_affects_the_result() {
        // 重排覆盖全部 40 位 → 任意一位改动都必须改变输出
        val base = LanzouCrypto.acwScV2(arg1)
        arg1.indices.forEach { index ->
            val mutated = arg1.toCharArray().also { chars ->
                chars[index] = if (chars[index] == 'a') 'b' else 'a'
            }.concatToString()
            assertTrue(
                LanzouCrypto.acwScV2(mutated) != base,
                "第 ${index + 1} 位没有参与计算，重排表可能漏了该位置",
            )
        }
    }

    @Test
    fun challenge_cookie_extracts_arg1_from_page() {
        val page = """<script>var arg1='$arg1';document.cookie='acw_sc__v2='+x;</script>"""
        assertEquals(LanzouCrypto.acwScV2(arg1), LanzouCrypto.challengeCookie(page))
    }

    @Test
    fun challenge_cookie_is_null_without_arg1() {
        assertNull(LanzouCrypto.challengeCookie("<html><body>正常页面</body></html>"))
    }
}

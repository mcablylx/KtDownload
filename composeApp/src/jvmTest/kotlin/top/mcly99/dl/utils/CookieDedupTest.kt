package top.mcly99.dl.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * issue #94 回归测试：同名 Cookie 必须去重（保留最后一次），
 * 否则百度 api/list 因多份冲突 STOKEN 返回 errno=-6。
 */
class CookieDedupTest {

    @Test
    fun `同名键值对只保留最后一次且顺序按首次出现稳定`() {
        val input = listOf(
            "A" to "1",
            "STOKEN" to "old-1",
            "B" to "2",
            "STOKEN" to "old-2",
            "C" to "3",
            "STOKEN" to "new",
        )
        assertEquals(
            listOf("A" to "1", "STOKEN" to "new", "B" to "2", "C" to "3"),
            deduplicateCookiePairs(input),
        )
    }

    @Test
    fun `issue94 四份冲突 STOKEN 去重后只剩有效值`() {
        // 实测：4 份不同 STOKEN 同头 → errno=-6；保留最后一次 → errno=0、列出 91 项。
        val dirty = "BDUSS=abc; STOKEN=old1; PTOKEN=p; STOKEN=old2; PANPSC=x; STOKEN=old3; STOKEN=new"
        assertEquals(
            "BDUSS=abc; STOKEN=new; PTOKEN=p; PANPSC=x",
            deduplicateCookieHeader(dirty),
        )
    }

    @Test
    fun `按第一个等号切分 值中的等号不被误当键名`() {
        val dirty = "token=ab==; token=cd"
        assertEquals("token=cd", deduplicateCookieHeader(dirty))
    }

    @Test
    fun `没有重复时原样返回`() {
        val clean = "BDUSS=abc; STOKEN=new; PTOKEN=p"
        assertEquals(clean, deduplicateCookieHeader(clean))
        val pairs = listOf("BDUSS" to "abc", "STOKEN" to "new")
        assertEquals(pairs, deduplicateCookiePairs(pairs))
    }

    @Test
    fun `空输入与空名安全`() {
        assertEquals("", deduplicateCookieHeader(""))
        assertEquals("  ", deduplicateCookieHeader("  "))
        assertEquals(emptyList(), deduplicateCookiePairs(emptyList()))
        assertTrue(deduplicateCookieHeader("=novalue; =another; A=1").startsWith("A=1"))
    }
}

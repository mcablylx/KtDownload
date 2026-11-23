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
    fun same_name_keeps_last_value_with_stable_order() {
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
    fun issue94_four_conflicting_stokens_keep_only_last() {
        // 实测：4 份不同 STOKEN 同头 → errno=-6；保留最后一次 → errno=0、列出 91 项。
        val dirty = "BDUSS=abc; STOKEN=old1; PTOKEN=p; STOKEN=old2; PANPSC=x; STOKEN=old3; STOKEN=new"
        assertEquals(
            "BDUSS=abc; STOKEN=new; PTOKEN=p; PANPSC=x",
            deduplicateCookieHeader(dirty),
        )
    }

    @Test
    fun splits_on_first_equals_only() {
        val dirty = "token=ab==; token=cd"
        assertEquals("token=cd", deduplicateCookieHeader(dirty))
    }

    @Test
    fun no_duplicates_returns_unchanged() {
        val clean = "BDUSS=abc; STOKEN=new; PTOKEN=p"
        assertEquals(clean, deduplicateCookieHeader(clean))
        val pairs = listOf("BDUSS" to "abc", "STOKEN" to "new")
        assertEquals(pairs, deduplicateCookiePairs(pairs))
    }

    @Test
    fun blank_input_and_blank_names_are_safe() {
        assertEquals("", deduplicateCookieHeader(""))
        assertEquals("  ", deduplicateCookieHeader("  "))
        assertEquals(emptyList(), deduplicateCookiePairs(emptyList()))
        assertTrue(deduplicateCookieHeader("=novalue; =another; A=1").startsWith("A=1"))
    }
}

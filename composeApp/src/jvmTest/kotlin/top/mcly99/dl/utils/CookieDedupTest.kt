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

    // ---------------------------------------------------------------- 边界（P2-E11）

    @Test
    fun name_matching_is_case_sensitive() {
        // HTTP 规范里 Cookie 名区分大小写：`STOKEN` 与 `stoken` 是两个不同的键，不能互相覆盖
        assertEquals("STOKEN=a; stoken=b", deduplicateCookieHeader("STOKEN=a; stoken=b"))
    }

    @Test
    fun empty_value_is_preserved() {
        // 空值是合法的（服务端用它来清 Cookie），去重后仍应保留 `A=`
        assertEquals("A=; B=2", deduplicateCookieHeader("A=; A=; B=2"))
        assertEquals(listOf("A" to ""), deduplicateCookiePairs(listOf("A" to "")))
    }

    @Test
    fun segment_without_equals_is_dropped_not_mangled() {
        // 头值里出现裸 `;` 时只能按分隔符理解：无 `=` 的碎片丢掉，不能拼回相邻的键值
        assertEquals("A=1; B=3", deduplicateCookieHeader("A=1;2; B=3"))
    }

    @Test
    fun pair_value_with_equals_signs_is_kept_verbatim() {
        // pairs 形态的 value 已经是切好的，里面的 `=` 属于值本身（base64 padding）
        assertEquals(
            listOf("token" to "cd==", "other" to "a=b"),
            deduplicateCookiePairs(listOf("token" to "ab==", "other" to "a=b", "token" to "cd==")),
        )
    }

    @Test
    fun single_pair_input_is_copied_not_aliased() {
        // 返回新集合的契约：调用方改动入参不能影响结果（P2-E10）
        val input = mutableListOf("A" to "1")
        val result = deduplicateCookiePairs(input)
        input.clear()
        assertEquals(listOf("A" to "1"), result)
    }
}

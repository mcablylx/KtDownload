package top.mcly99.dl.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * P3 多账号文案：独立语言表，不占用 [UiStrings] 的构造槽位（P1 的 255 槽 ClassFormatError）。
 *
 * 这里锁住「两种语言字段齐全、专有名词不被翻译、函数型文案参数不丢」。
 */
class MultiAccountStringsTest {

    private val zh = multiAccountStrings("zh-CN")
    private val en = multiAccountStrings("en-US")

    private fun plainFields(s: MultiAccountStrings) = listOf(
        s.experimentalTitle, s.experimentalDesc, s.multiAccountTitle, s.multiAccountDesc,
        s.multiAccountRisk, s.multiSourceTitle, s.multiSourceDesc, s.multiSourceRisk,
        s.accountSectionTitle, s.primaryBadge, s.unnamedAccount,
        s.addAccount, s.addAccountHint, s.setPrimary, s.rename, s.remove,
    )

    @Test
    fun language_selection_matches_the_rest_of_the_app() {
        assertEquals(zh, multiAccountStrings("zh-CN"))
        assertEquals(zh, multiAccountStrings("fr-FR"))
        assertEquals(en, multiAccountStrings("en-US"))
        assertEquals(en, multiAccountStrings("EN-us"))
        assertNotEquals(zh, en)
    }

    @Test
    fun every_language_has_non_blank_labels_and_no_missing_line() {
        listOf(zh, en).forEach { s ->
            plainFields(s).forEach { assertTrue(it.isNotBlank(), "blank label: $it") }
            assertTrue(s.accountLabel("小号", "abc••••34").contains("小号"))
            assertTrue(s.accountLabel("小号", "abc••••34").contains("abc••••34"))
            assertTrue(s.accountAdded("夸克").contains("夸克"))
            assertTrue(s.accountRemoved("夸克").contains("夸克"))
            assertTrue(s.primaryChanged("夸克").contains("夸克"))
            assertTrue(s.accountCount(3).contains("3"))
        }
    }

    @Test
    fun experimental_warning_explains_the_two_real_constraints() {
        listOf(zh, en).forEach { s ->
            // 开关默认关、且只有 2 个以上账号才生效 —— 这两条必须写给用户看
            assertTrue(s.experimentalDesc.isNotBlank())
            assertTrue(s.multiAccountRisk.isNotBlank())
            assertFalse(s.multiAccountTitle.isBlank())
        }
        assertTrue(zh.multiAccountRisk.contains("2"))
        assertTrue(en.multiAccountRisk.contains("2"))
    }

    @Test
    fun c1_label_does_not_translate_the_experiment_code() {
        assertTrue(zh.multiAccountTitle.contains("C1"))
        assertTrue(en.multiAccountTitle.contains("C1"))
    }

    @Test
    fun c2_label_keeps_the_code_and_warns_that_c1_is_required() {
        assertTrue(zh.multiSourceTitle.contains("C2"))
        assertTrue(en.multiSourceTitle.contains("C2"))
        assertTrue(zh.multiSourceRisk.contains("C1"))
        assertTrue(en.multiSourceRisk.contains("C1"))
    }

    @Test
    fun adding_a_language_table_did_not_touch_ui_strings_constructor() {
        // UiStrings 构造槽位已在上限；这张表必须保持独立，不能并进去
        assertTrue(zhCN().cloudDriveStrings.githubMirrorLabel.isNotBlank())
        assertTrue(networkStrings("en-US").http2Title.isNotBlank())
        assertTrue(multiAccountStrings("en-US").addAccount.isNotBlank())
    }
}

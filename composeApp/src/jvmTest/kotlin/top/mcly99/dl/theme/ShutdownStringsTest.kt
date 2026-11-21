package top.mcly99.dl.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 关机文案独立成表，不占 UiStrings 的 JVM 参数槽（N-10）。 */
class ShutdownStringsTest {

    @Test
    fun both_languages_fill_every_field_and_the_countdown_is_interpolated() {
        for (lang in listOf("zh-CN", "en-US")) {
            val s = shutdownStrings(lang)
            listOf(s.title, s.desc, s.risk, s.dialogTitle, s.cancel, s.shutdownNow)
                .forEach { assertTrue(it.isNotBlank(), "$lang 有空文案") }
            assertTrue(s.dialogMessage(42).contains("42"), "$lang 倒计时秒数没插进文案")
        }
    }

    @Test
    fun zh_and_en_are_actually_different_translations() {
        assertFalse(shutdownStrings("zh-CN").title == shutdownStrings("en-US").title)
        assertFalse(shutdownStrings("zh-CN").cancel == shutdownStrings("en-US").cancel)
    }

    @Test
    fun unknown_language_falls_back_to_chinese() {
        assertEquals(shutdownStrings("zh-CN").title, shutdownStrings("fr-FR").title)
    }
}

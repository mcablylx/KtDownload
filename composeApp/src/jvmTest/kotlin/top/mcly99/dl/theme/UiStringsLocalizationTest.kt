package top.mcly99.dl.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 英文文案表与解析结果行的本地化契约（P2-22）。
 *
 * 走查发现 `enUS()` 里 `appName` / `windowTitle` 直接写了中文「下载管理器」，
 * 以及结果行把「提取码 X」硬编码在 UI 里 —— 英文界面同样显示中文。
 */
class UiStringsLocalizationTest {

    private val cjk = Regex("[\u4e00-\u9fff]")

    @Test
    fun english_app_name_and_window_title_are_not_chinese() {
        val en = enUS()
        assertFalse(cjk.containsMatchIn(en.appName), "appName 仍是中文: ${en.appName}")
        assertFalse(cjk.containsMatchIn(en.windowTitle), "windowTitle 仍是中文: ${en.windowTitle}")
        assertTrue(en.appName.isNotBlank())
        assertTrue(en.windowTitle.isNotBlank())
    }

    @Test
    fun chinese_table_keeps_its_original_titles() {
        val zh = zhCN()
        assertTrue(cjk.containsMatchIn(zh.appName), "中文表不该被一起改掉: ${zh.appName}")
        assertTrue(cjk.containsMatchIn(zh.windowTitle), "中文表不该被一起改掉: ${zh.windowTitle}")
    }

    @Test
    fun english_table_has_no_chinese_values_except_language_names() {
        val en = enUS()
        // langZhCN = "简体中文" 是语言选择器的本地化名称，属于合理的中文值
        val offenders = stringValues(en).filterKeys { it != "getLangZhCN" }
            .filter { (_, value) -> cjk.containsMatchIn(value) }
        assertTrue(offenders.isEmpty(), "en-US 表仍有中文值: $offenders")
    }

    @Test
    fun english_cloud_drive_table_has_no_chinese_values() {
        val cd = enUS().cloudDriveStrings
        val offenders = stringValues(cd).filter { (_, value) -> cjk.containsMatchIn(value) }
        assertTrue(offenders.isEmpty(), "en-US 云盘表仍有中文值: $offenders")
    }

    @Test
    fun passcode_label_is_translated_and_keeps_the_code() {
        assertEquals("提取码 ab12", zhCN().cloudDriveStrings.passcodeLabel("ab12"))
        assertEquals("Passcode: ab12", enUS().cloudDriveStrings.passcodeLabel("ab12"))
        assertFalse(cjk.containsMatchIn(enUS().cloudDriveStrings.passcodeLabel("ab12")))
    }

    @Test
    fun both_language_tables_are_reachable_through_stringsFor() {
        assertEquals("Download Manager", stringsFor("en-US").windowTitle)
        assertEquals(zhCN().appName, stringsFor("zh-CN").appName)
        // 与既有的回落规则一致：未知语言回落中文
        assertEquals(zhCN().appName, stringsFor("fr-FR").appName)
    }

    /** 用 Java 反射枚举所有 String getter，不需要 kotlin-reflect 依赖 */
    private fun stringValues(target: Any): Map<String, String> =
        target.javaClass.declaredMethods
            .filter {
                it.parameterCount == 0 &&
                    it.returnType == String::class.java &&
                    it.name.startsWith("get")
            }
            .mapNotNull { m ->
                runCatching { m.invoke(target) as? String }.getOrNull()?.let { m.name to it }
            }
            .toMap()
}

package top.mcly99.dl.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import java.io.File

/**
 * 应用内第三方署名（C-02 清单里最后一条未勾项）的契约测试。
 *
 * 这些断言锁住的是**法律上必须出现的信息**，不是排版：AGPL-3.0 §5(a)/§13 要求显著
 * 标注上游项目、许可、改动与获取源码的途径。以后有人精简弹窗、或升级上游却忘了同步
 * 快照版本，测试要在这里拦下来。另检查弹窗里写到的三个许可全文路径确实随包存在，
 * 否则「打开许可目录」按钮会把用户送进一个空目录。
 */
class LicenseStringsTest {

    @Test
    fun both_languages_list_the_same_four_components_in_the_same_order() {
        val zh = zhCN().licenses
        val en = enUS().licenses
        assertEquals(4, zh.components.size)
        assertEquals(4, en.components.size)
        assertEquals(zh.components.map { it.name }, en.components.map { it.name })
        assertEquals(zh.components.map { it.license }, en.components.map { it.license })
        assertEquals(zh.components.map { it.sourceUrl }, en.components.map { it.sourceUrl })
    }

    @Test
    fun every_entry_has_non_empty_attribution_fields_and_an_https_source() {
        listOf(zhCN().licenses, enUS().licenses).forEach { strings ->
            listOf(
                strings.title, strings.subtitle, strings.warning, strings.licenseLabel,
                strings.integrationLabel, strings.openUpstream, strings.openFolder, strings.close,
            ).forEach { assertTrue(it.isNotBlank(), "UI label must not be blank") }
            strings.components.forEach { entry ->
                listOf(entry.name, entry.license, entry.integration, entry.sourceUrl, entry.notes)
                    .forEach { assertTrue(it.isNotBlank(), "${entry.name}: attribution field is blank") }
                assertTrue(entry.sourceUrl.startsWith("https://"), "${entry.name}: source URL is not https")
            }
        }
    }

    @Test
    fun agpl_entry_carries_the_full_upstream_attribution_in_both_languages() {
        listOf(zhCN().licenses, enUS().licenses).forEach { strings ->
            val agpl = strings.components.single { it.license.contains("AGPL-3.0") }
            assertTrue("tidain/YunX-Desktop" in agpl.sourceUrl)
            assertTrue("YunX-Desktop" in agpl.name)
            listOf("56", "13,670", "v1.3.4", "00a030f7", "SOURCE.txt", "LICENSE.txt", "§5(a)/§13")
                .forEach { marker ->
                    assertTrue(
                        agpl.notes.contains(marker) || agpl.integration.contains(marker),
                        "AGPL attribution is missing '$marker'",
                    )
                }
        }
    }

    @Test
    fun warning_states_agpl_coverage_and_upstream_attribution() {
        val zh = zhCN().licenses.warning
        val en = enUS().licenses.warning
        assertTrue("AGPL-3.0" in zh && "§13" in zh && "SOURCE.txt" in zh)
        assertTrue("AGPL-3.0" in en && "§13" in en && "SOURCE.txt" in en && "Corresponding Source" in en)
        assertNotEquals(zh, en)
    }

    @Test
    fun languages_are_wired_to_distinct_string_tables() {
        assertNotEquals(zhCN().openSourceLicenses, enUS().openSourceLicenses)
        assertNotEquals(zhCN().licenses.title, enUS().licenses.title)
        assertTrue(zhCN().openSourceLicenses.isNotBlank())
    }

    @Test
    fun license_texts_referenced_by_the_dialog_ship_with_the_resources() {
        // Gradle 测试的工作目录通常是 composeApp/；向上找几层以兼容从仓库根运行
        listOf(
            "resources/common/ffmpeg/LICENSE.txt",
            "resources/common/vlc/COPYING.LIB.txt",
            "resources/common/yunx-desktop/LICENSE.txt",
        ).forEach { relative ->
            var dir: File? = File(".").absoluteFile
            var file: File? = null
            repeat(5) {
                val current = dir
                if (current != null && file == null) {
                    File(current, relative).takeIf { it.isFile }?.let { file = it }
                }
                dir = current?.parentFile
            }
            val resolved = file
            assertTrue(resolved != null, "Declared license file is missing: $relative")
            // 文件存在还不够：占位/空文件同样会让「打开许可目录」失效，且不满足 AGPL §5(a)
            val text = resolved.readText()
            assertTrue(text.length > 1000, "License file looks like a placeholder: $relative")
            if (relative.contains("yunx-desktop")) {
                assertTrue(
                    text.contains("GNU AFFERO GENERAL PUBLIC LICENSE"),
                    "YunX-Desktop license must ship the full AGPL-3.0 text: $relative",
                )
            }
        }
    }
}

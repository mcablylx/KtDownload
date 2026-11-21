package top.mcly99.dl.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 「数据与诊断」文案表：与 [UiStrings] 完全独立。
 *
 * 这条约束不是洁癖：UiStrings 的构造参数已经逼近 JVM 方法签名 255 槽上限，
 * 再多一个字段就会「编译通过、运行时 ClassFormatError」（P1 踩过的坑）。
 */
class DataToolsStringsTest {

    private val zh = dataToolsStrings("zh-CN")
    private val en = dataToolsStrings("en-US")

    private fun plainFields(s: DataToolsStrings) = listOf(
        s.title, s.desc,
        s.exportBackup, s.exportBackupDesc,
        s.importBackup, s.importBackupDesc,
        s.diagnostics, s.diagnosticsDesc,
        s.passphraseTitle, s.passphraseLabel, s.passphraseHint, s.passphraseNote,
        s.passphraseTooShort, s.cancel, s.confirm, s.working,
        s.backupEmpty, s.noStore,
    )

    @Test
    fun language_selection_matches_the_rest_of_the_app() {
        assertEquals(zh, dataToolsStrings("zh-CN"))
        assertEquals(zh, dataToolsStrings("fr-FR"))
        assertEquals(en, dataToolsStrings("en-US"))
        assertEquals(en, dataToolsStrings("EN-us"))
        assertNotEquals(zh, en)
    }

    @Test
    fun every_label_is_non_blank_and_dynamic_labels_carry_their_arguments() {
        listOf(zh, en).forEach { s ->
            plainFields(s).forEach { assertTrue(it.isNotBlank(), "blank label: $it") }
            assertTrue(s.backupDone(3).contains("3"))
            assertTrue(s.importDone(2, 1).contains("2") && s.importDone(2, 1).contains("1"))
            assertTrue(s.importFailed("bad tag").contains("bad tag"))
            assertTrue(s.diagnosticsDone("/tmp/a.zip").contains("/tmp/a.zip"))
            assertTrue(s.diagnosticsFailed("disk full").contains("disk full"))
        }
    }

    @Test
    fun the_section_does_not_add_a_slot_to_ui_strings() {
        assertTrue(zhCN().cloudDriveStrings.title.isNotBlank())
        assertTrue(enUS().cloudDriveStrings.title.isNotBlank())
    }
}

package top.mcly99.dl.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 「移除任务」确认框文案：与 [UiStrings] 完全独立（255 槽上限，见 [DataToolsStringsTest]）。
 *
 * 这张表存在的理由写在代码里：默认动作必须是「保留文件」，所以两个动作的文案必须是
 * 两个不同的动词（「仅移除任务」/「移除并删除文件」），不能都叫「删除」——
 * 后者正是 P0-1 那次误删的根源：用户以为在清列表，实际在删文件。
 */
class RemoveTaskStringsTest {

    private val zh = removeTaskStrings("zh-CN")
    private val en = removeTaskStrings("en-US")

    @Test
    fun language_selection_matches_the_rest_of_the_app() {
        assertEquals(zh, removeTaskStrings("zh-CN"))
        assertEquals(zh, removeTaskStrings("fr-FR"))
        assertEquals(en, removeTaskStrings("en-US"))
        assertEquals(en, removeTaskStrings("EN-us"))
        assertNotEquals(zh, en)
    }

    @Test
    fun every_label_is_non_blank_and_the_message_interpolates_the_file_name() {
        listOf(zh, en).forEach { s ->
            listOf(s.title, s.keepFile, s.deleteFile, s.cancel).forEach {
                assertTrue(it.isNotBlank(), "blank label: $it")
            }
            assertTrue(s.message("movie.mp4").contains("movie.mp4"))
        }
    }

    @Test
    fun the_two_actions_are_worded_differently() {
        listOf(zh, en).forEach { s ->
            assertNotEquals(s.keepFile, s.deleteFile, "两个动作不能叫同一个名字，否则用户无法区分后果")
        }
    }
}

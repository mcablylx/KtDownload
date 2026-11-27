package top.mcly99.dl.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 任务卡片图标按钮的无障碍文案（P2-23）。
 *
 * 重点不是「有值」，而是「不能拿播放器 / 批量按钮的文案冒充单个任务的动作」：
 * 读屏用户听到「播放」或「全部开始」会以为点下去影响的是别的东西。
 */
class TaskActionStringsTest {

    private val cjk = Regex("[\u4e00-\u9fff]")

    @Test
    fun both_languages_have_all_five_descriptions() {
        listOf(taskActionStrings("zh-CN"), taskActionStrings("en-US")).forEach { a ->
            listOf(a.pause, a.resume, a.retry, a.openFolder, a.remove)
                .forEach { assertTrue(it.isNotBlank(), "空白的图标描述: $a") }
        }
    }

    @Test
    fun english_descriptions_have_no_chinese() {
        val en = taskActionStrings("en-US")
        listOf(en.pause, en.resume, en.retry, en.openFolder, en.remove)
            .forEach { assertFalse(cjk.containsMatchIn(it), "英文描述混入中文: $it") }
    }

    @Test
    fun unknown_language_falls_back_to_chinese_like_stringsFor() {
        val zh = taskActionStrings("zh-CN")
        assertEquals(zh, taskActionStrings("fr-FR"))
        // en-US 判定与其他独立表一样忽略大小写
        assertEquals(taskActionStrings("en-US"), taskActionStrings("EN-us"))
        assertNotEquals(zh, taskActionStrings("en-US"))
    }

    @Test
    fun reused_labels_match_the_existing_ui_strings_values() {
        val zh = zhCN()
        val zhA = taskActionStrings("zh-CN")
        assertEquals(zh.retry, zhA.retry)
        assertEquals(zh.openFolder, zhA.openFolder)
        assertEquals(zh.deleteTask, zhA.remove)

        val en = enUS()
        val enA = taskActionStrings("en-US")
        assertEquals(en.retry, enA.retry)
        assertEquals(en.openFolder, enA.openFolder)
        assertEquals(en.deleteTask, enA.remove)
    }

    @Test
    fun pause_and_resume_do_not_reuse_player_or_batch_wording() {
        listOf(taskActionStrings("zh-CN"), taskActionStrings("en-US")).forEachIndexed { index, a ->
            val zh = zhCN()
            val en = enUS()
            val ui = if (index == 0) zh else en
            assertNotEquals(ui.playerPlay, a.resume, "不能把「播放」当成「继续任务」")
            assertNotEquals(ui.startAll, a.resume, "不能把「全部开始」当成「继续任务」")
            assertNotEquals(ui.pauseAll, a.pause, "不能把「全部暂停」当成「暂停任务」")
        }
    }

    @Test
    fun pause_and_resume_are_distinct() {
        listOf(taskActionStrings("zh-CN"), taskActionStrings("en-US")).forEach { a ->
            assertNotEquals(a.pause, a.resume)
        }
    }
}

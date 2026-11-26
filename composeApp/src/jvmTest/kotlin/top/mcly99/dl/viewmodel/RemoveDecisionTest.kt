package top.mcly99.dl.viewmodel

import top.mcly99.dl.model.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「点 × 之后删不删文件」的判定。
 *
 * 这是 P0-1（一次误点静默删掉成品）的复发点，逐条钉住三种结局：
 * 未完成的任务连残片一起清，已完成的要问用户，文件已被手动删掉的不必再问。
 */
class RemoveDecisionTest {

    @Test
    fun unfinished_task_is_removed_with_leftovers() {
        // 半截文件 / .partN / .hls 分片都不是成品：只删记录会留一堆用户找不到的垃圾
        val unfinished = listOf(
            DownloadStatus.DOWNLOADING,
            DownloadStatus.QUEUED,
            DownloadStatus.PAUSED,
            DownloadStatus.FAILED,
            DownloadStatus.CANCELLED,
        )

        unfinished.forEach { status ->
            assertEquals(
                RemoveDecision.RemoveNow(deleteFile = true),
                RemoveDecision.of(status, fileExists = true),
                "$status 应直接移除并清理临时文件",
            )
            // 未完成的任务不查磁盘，fileExists 传什么都不该影响结论
            assertEquals(
                RemoveDecision.RemoveNow(deleteFile = true),
                RemoveDecision.of(status, fileExists = false),
                "$status 的处理不应依赖文件是否存在",
            )
        }
    }

    @Test
    fun completed_with_existing_file_asks_user_first() {
        assertEquals(
            RemoveDecision.AskUser,
            RemoveDecision.of(DownloadStatus.COMPLETED, fileExists = true),
        )
    }

    @Test
    fun completed_with_missing_file_removes_record_only() {
        // 用户自己把成品删了：没有可问的，直接移除记录，别再报「文件不存在」
        assertEquals(
            RemoveDecision.RemoveNow(deleteFile = false),
            RemoveDecision.of(DownloadStatus.COMPLETED, fileExists = false),
        )
    }
}

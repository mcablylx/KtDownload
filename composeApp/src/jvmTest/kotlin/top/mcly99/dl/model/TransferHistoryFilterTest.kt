package top.mcly99.dl.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 传输历史「清空已结束」的判定规则（走查 P1-23）。
 *
 * 这条逻辑原先写在 `TransferViewModel.clearCompleted()` 里，且只清 `COMPLETED`：
 * 失败与已取消的记录永远留在列表里，用户没有任何手段清掉它们。改成纯函数后可以
 * 直接验证「什么该清、什么必须留」。
 */
class TransferHistoryFilterTest {

    private fun task(name: String, status: TransferStatus) = TransferTask(
        fileName = name,
        fileSize = 1024,
        targetDevice = NetworkDevice(
            id = "dev",
            name = "Test Device",
            ipAddress = "192.168.1.10",
            port = 8899,
            lastSeen = 0L,
        ),
        status = status,
    )

    @Test
    fun keeps_only_waiting_and_transferring() {
        val tasks = listOf(
            task("waiting.bin", TransferStatus.WAITING),
            task("running.bin", TransferStatus.TRANSFERRING),
            task("done.bin", TransferStatus.COMPLETED),
            task("failed.bin", TransferStatus.FAILED),
            task("cancelled.bin", TransferStatus.CANCELLED),
        )

        val left = tasks.keepingUnfinished().map { it.fileName }

        assertEquals(listOf("waiting.bin", "running.bin"), left)
    }

    @Test
    fun failure_and_cancellation_are_finished() {
        // 关键回归：旧实现只认 COMPLETED，失败/取消的记录会永久堆积
        assertTrue(task("a", TransferStatus.FAILED).isFinished)
        assertTrue(task("b", TransferStatus.CANCELLED).isFinished)
        assertTrue(task("c", TransferStatus.COMPLETED).isFinished)
        assertFalse(task("d", TransferStatus.TRANSFERRING).isFinished)
        assertFalse(task("e", TransferStatus.WAITING).isFinished)
    }

    @Test
    fun empty_list_stays_empty() {
        assertTrue(emptyList<TransferTask>().keepingUnfinished().isEmpty())
    }
}

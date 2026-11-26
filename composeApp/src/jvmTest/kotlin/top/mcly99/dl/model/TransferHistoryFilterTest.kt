package top.mcly99.dl.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 传输历史的两条规则：`keepingUnfinished`（清空已结束）与 `trimmedTo`（超限裁剪）。
 *
 * 「清空」原先写在 `TransferViewModel.clearCompleted()` 里，且只清 `COMPLETED`：
 * 失败与已取消的记录永远留在列表里，用户没有任何手段清掉它们（走查 P1-23）。
 * 「裁剪」原先写在 `appendTask` 里，规则更绕：优先丢最老的已结束记录，进行中的一条不动。
 * 两者都抽成纯函数——ViewModel 构造就会起 UDP 发现，单测没法只验证规则本身。
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

    // ---------------------------------------------------------------- 历史裁剪

    @Test
    fun trim_within_limit_returns_same_list() {
        val tasks = listOf(task("a", TransferStatus.COMPLETED), task("b", TransferStatus.WAITING))
        // 未超限时不能白白复制一份：appendTask 每次加入任务都会走这里
        assertSame(tasks, tasks.trimmedTo(2))
        assertSame(tasks, tasks.trimmedTo(10))
    }

    @Test
    fun trim_drops_oldest_finished_first() {
        val tasks = listOf(
            task("old-done", TransferStatus.COMPLETED),
            task("running", TransferStatus.TRANSFERRING),
            task("mid-done", TransferStatus.COMPLETED),
            task("waiting", TransferStatus.WAITING),
            task("new-done", TransferStatus.COMPLETED),
        )

        // limit=3 只超出 2 条：从旧到新丢「已结束」的，最新的 new-done 必须留下
        assertEquals(
            listOf("running", "waiting", "new-done"),
            tasks.trimmedTo(3).map { it.fileName },
        )
    }

    @Test
    fun trim_never_drops_active_tasks() {
        val tasks = listOf(
            task("a", TransferStatus.WAITING),
            task("b", TransferStatus.TRANSFERRING),
            task("c", TransferStatus.WAITING),
            task("d", TransferStatus.TRANSFERRING),
        )

        // 全是进行中：宁可超限也不让任务从列表里消失（用户会以为传输被中断）
        assertEquals(4, tasks.trimmedTo(2).size)
    }

    @Test
    fun trim_keeps_overflow_when_not_enough_finished() {
        val tasks = listOf(
            task("done", TransferStatus.COMPLETED),
            task("w1", TransferStatus.WAITING),
            task("w2", TransferStatus.WAITING),
            task("w3", TransferStatus.WAITING),
            task("w4", TransferStatus.WAITING),
        )

        // 超出 2 条但只有 1 条能丢：只丢那一条，剩下 4 条仍超限，等它们结束再回收
        val trimmed = tasks.trimmedTo(3)
        assertEquals(4, trimmed.size)
        assertEquals(listOf("w1", "w2", "w3", "w4"), trimmed.map { it.fileName })
    }

    @Test
    fun trim_rejects_negative_limit() {
        assertFailsWith<IllegalArgumentException> {
            listOf(task("a", TransferStatus.COMPLETED)).trimmedTo(-1)
        }
    }
}

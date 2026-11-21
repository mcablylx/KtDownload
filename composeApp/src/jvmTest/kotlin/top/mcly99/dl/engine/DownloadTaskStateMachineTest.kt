package top.mcly99.dl.engine

import top.mcly99.dl.model.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadTaskStateMachineTest {

    @Test
    fun queued_task_starts_downloading() {
        assertEquals(
            DownloadStatus.DOWNLOADING,
            DownloadTaskStateMachine.nextStatus(DownloadStatus.QUEUED, DownloadEvent.START),
        )
    }

    @Test
    fun pause_is_allowed_for_queued_downloading_and_merging() {
        listOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.MERGING).forEach { from ->
            assertEquals(
                DownloadStatus.PAUSED,
                DownloadTaskStateMachine.nextStatus(from, DownloadEvent.PAUSE),
                "pause from $from",
            )
        }
    }

    @Test
    fun pause_keeps_terminal_states_unchanged() {
        listOf(
            DownloadStatus.PAUSED,
            DownloadStatus.COMPLETED,
            DownloadStatus.FAILED,
            DownloadStatus.CANCELLED,
        ).forEach { from ->
            assertEquals(from, DownloadTaskStateMachine.nextStatus(from, DownloadEvent.PAUSE), "pause from $from")
        }
    }

    @Test
    fun merge_and_complete_form_multi_thread_flow() {
        val merging = DownloadTaskStateMachine.nextStatus(DownloadStatus.DOWNLOADING, DownloadEvent.MERGE)
        assertEquals(DownloadStatus.MERGING, merging)
        assertEquals(
            DownloadStatus.COMPLETED,
            DownloadTaskStateMachine.nextStatus(merging, DownloadEvent.COMPLETE),
        )
    }

    @Test
    fun hls_segment_phase_uses_merging_before_completion() {
        val merging = DownloadTaskStateMachine.nextStatus(DownloadStatus.DOWNLOADING, DownloadEvent.MERGE)
        assertEquals(DownloadStatus.MERGING, merging)
        assertEquals(
            DownloadStatus.COMPLETED,
            DownloadTaskStateMachine.nextStatus(merging, DownloadEvent.COMPLETE),
        )
        assertEquals(
            DownloadStatus.PAUSED,
            DownloadTaskStateMachine.nextStatus(merging, DownloadEvent.PAUSE),
        )
    }

    @Test
    fun cancel_is_not_masked_as_failure() {
        val cancelled = DownloadTaskStateMachine.nextStatus(DownloadStatus.DOWNLOADING, DownloadEvent.CANCEL)
        assertEquals(DownloadStatus.CANCELLED, cancelled)
        assertFalse(cancelled == DownloadStatus.FAILED)
    }

    @Test
    fun completed_task_cannot_be_cancelled_or_failed() {
        assertEquals(
            DownloadStatus.COMPLETED,
            DownloadTaskStateMachine.nextStatus(DownloadStatus.COMPLETED, DownloadEvent.CANCEL),
        )
        assertEquals(
            DownloadStatus.COMPLETED,
            DownloadTaskStateMachine.nextStatus(DownloadStatus.COMPLETED, DownloadEvent.FAIL),
        )
    }

    @Test
    fun retry_from_failed_or_cancelled_reaches_queue_then_running() {
        listOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED, DownloadStatus.PAUSED).forEach { from ->
            val queued = DownloadTaskStateMachine.nextStatus(from, DownloadEvent.ENQUEUE)
            assertEquals(DownloadStatus.QUEUED, queued, "enqueue from $from")
            assertEquals(
                DownloadStatus.DOWNLOADING,
                DownloadTaskStateMachine.nextStatus(queued, DownloadEvent.START),
                "start after enqueue from $from",
            )
        }
    }

    /**
     * 非法事件必须返回原状态（幂等），这是引擎唯一依赖的语义：
     * 重复发送 PAUSE / CANCEL 不会把任务推到别的状态。
     *
     * v4-P2-3：原先另有一个 `canTransition(from, to)` 方法用 `when(to)` 把同一套规则写了第二遍，
     * 生产代码从不调用它，测试却只测那份影子实现。现已删除，规则只保留 `nextStatus` 一处。
     */
    @Test
    fun illegal_events_are_idempotent() {
        // 已在终态时，PAUSE / MERGE / START 都不应改变状态
        assertEquals(DownloadStatus.COMPLETED, DownloadTaskStateMachine.nextStatus(DownloadStatus.COMPLETED, DownloadEvent.PAUSE))
        assertEquals(DownloadStatus.COMPLETED, DownloadTaskStateMachine.nextStatus(DownloadStatus.COMPLETED, DownloadEvent.MERGE))
        assertEquals(DownloadStatus.CANCELLED, DownloadTaskStateMachine.nextStatus(DownloadStatus.CANCELLED, DownloadEvent.START))
        // 已完成的任务不会被取消（否则用户看到「取消」把成品标成失败）
        assertEquals(DownloadStatus.COMPLETED, DownloadTaskStateMachine.nextStatus(DownloadStatus.COMPLETED, DownloadEvent.CANCEL))
        // 已暂停的任务不会因为异步完成回调变成 COMPLETED
        assertEquals(DownloadStatus.PAUSED, DownloadTaskStateMachine.nextStatus(DownloadStatus.PAUSED, DownloadEvent.COMPLETE))

        // 合法路径仍然成立
        assertEquals(
            DownloadStatus.MERGING,
            DownloadTaskStateMachine.nextStatus(DownloadStatus.DOWNLOADING, DownloadEvent.MERGE),
        )
        assertEquals(
            DownloadStatus.CANCELLED,
            DownloadTaskStateMachine.nextStatus(DownloadStatus.DOWNLOADING, DownloadEvent.CANCEL),
        )
        assertEquals(
            DownloadStatus.QUEUED,
            DownloadTaskStateMachine.nextStatus(DownloadStatus.PAUSED, DownloadEvent.ENQUEUE),
        )
    }
}

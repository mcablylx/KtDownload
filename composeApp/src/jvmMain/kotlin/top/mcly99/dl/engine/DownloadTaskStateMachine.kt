package top.mcly99.dl.engine

import top.mcly99.dl.model.DownloadStatus

/** 触发状态迁移的事件 */
enum class DownloadEvent {
    ENQUEUE,
    START,
    PAUSE,
    CANCEL,
    MERGE,
    COMPLETE,
    FAIL,
}

/**
 * 下载状态机。
 *
 * 所有状态迁移都必须经过这里，避免引擎中散落的 `copy(status = ...)` 把
 * 取消误标为完成、或让暂停中的任务被异步完成回调覆盖。
 */
object DownloadTaskStateMachine {

    /**
     * 根据当前状态与事件计算下一个状态。
     *
     * 对当前状态下不合法的事件返回原状态（幂等），调用方可安全地重复发送
     * PAUSE / CANCEL 等事件。
     */
    fun nextStatus(current: DownloadStatus, event: DownloadEvent): DownloadStatus = when (event) {
        DownloadEvent.ENQUEUE ->
            if (current == DownloadStatus.DOWNLOADING) current else DownloadStatus.QUEUED

        DownloadEvent.START ->
            if (current == DownloadStatus.QUEUED) DownloadStatus.DOWNLOADING else current

        DownloadEvent.PAUSE ->
            if (current == DownloadStatus.QUEUED ||
                current == DownloadStatus.DOWNLOADING ||
                current == DownloadStatus.MERGING
            ) DownloadStatus.PAUSED else current

        DownloadEvent.CANCEL ->
            if (current == DownloadStatus.COMPLETED) current else DownloadStatus.CANCELLED

        DownloadEvent.MERGE ->
            if (current == DownloadStatus.DOWNLOADING) DownloadStatus.MERGING else current

        DownloadEvent.COMPLETE ->
            if (current == DownloadStatus.DOWNLOADING || current == DownloadStatus.MERGING) {
                DownloadStatus.COMPLETED
            } else {
                current
            }

        DownloadEvent.FAIL ->
            if (current == DownloadStatus.DOWNLOADING || current == DownloadStatus.MERGING) {
                DownloadStatus.FAILED
            } else {
                current
            }
    }
}

package top.mcly99.dl.model

/**
 * 下载任务状态。
 *
 * 状态流转（由 `DownloadTaskStateMachine` 统一约束）：
 * ```
 *             ┌──────────────┐
 *   add ────► │    QUEUED    │ ◄──── 恢复/重试/继续
 *             └──────┬───────┘
 *                    │ START
 *                    ▼
 *             ┌──────────────┐
 *             │ DOWNLOADING  │ ── MERGE ──► MERGING
 *             └──┬───┬───┬───┘                │
 *          PAUSE │   │   │ FAIL               │ COMPLETE / FAIL
 *                ▼   │   ▼                    ▼
 *             PAUSED │  FAILED          COMPLETED / FAILED
 *                    │ CANCEL
 *                    ▼
 *                CANCELLED
 * ```
 *
 * 与旧版的差异：新增 [QUEUED] 与 [MERGING]；取消不再伪装成失败。
 */
enum class DownloadStatus {
    QUEUED,       // 排队中，等待并发槽位
    DOWNLOADING,  // 下载中
    MERGING,      // 分片校验/合并中
    PAUSED,       // 已暂停，可续传
    COMPLETED,    // 已完成
    FAILED,       // 下载/合并失败
    CANCELLED,    // 用户取消
}

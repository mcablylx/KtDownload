package top.mcly99.dl.viewmodel

import top.mcly99.dl.model.DownloadStatus

/**
 * 点任务卡片上的「×」之后应该做什么。
 *
 * 这段判断以前直接写在 `requestRemoveTask` 里，三个分支都涉及「删不删磁盘上的文件」——
 * 判错一次就是用户的成品被静默删除（P0-1 的复发点），所以单独成类型并让测试盯住：
 *
 * | 任务状态 | 文件在磁盘上 | 结果 |
 * |---|---|---|
 * | 未完成（下载中/排队/暂停/失败/取消） | 无关 | 直接移除，**连残片一起清** |
 * | 已完成 | 否（用户自己删了） | 直接移除记录，没什么可问的 |
 * | 已完成 | 是 | 弹框让用户选「仅移除」还是「连文件一起删」 |
 *
 * 未完成的任务不查磁盘：它们的半截文件与 `.partN` / `.hls` 分片都不是成品，
 * 留着只会变成用户再也找不到清理入口的垃圾。
 */
sealed interface RemoveDecision {

    /** 任务已经不在引擎里了（多半被别处删掉）：什么都不做 */
    data object None : RemoveDecision

    /** 直接移除记录；[deleteFile] 决定是否连成品一起删 */
    data class RemoveNow(val deleteFile: Boolean) : RemoveDecision

    /** 已完成且文件还在：必须先问用户 */
    data object AskUser : RemoveDecision

    companion object {
        /**
         * @param status 任务的当前状态
         * @param fileExists 成品文件是否还在；仅在 [DownloadStatus.COMPLETED] 时才会被读取，
         *   调用方对未完成任务不必做文件系统查询
         */
        fun of(status: DownloadStatus, fileExists: Boolean = false): RemoveDecision = when {
            status != DownloadStatus.COMPLETED -> RemoveNow(deleteFile = true)
            !fileExists -> RemoveNow(deleteFile = false)
            else -> AskUser
        }
    }
}

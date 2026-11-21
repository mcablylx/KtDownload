package top.mcly99.dl.theme

/**
 * 「移除任务」确认框的文案（自研，双语）。
 *
 * 与 [DataToolsStrings] / [MyDriveStrings] 同一条理由：**不进 [UiStrings]** ——
 * 它的构造参数已逼近 JVM 方法签名 255 槽上限，再加字段会「编译通过、运行时 ClassFormatError」。
 *
 * 为什么需要这张表：任务卡片上的「×」曾经直接调 `removeTask(id)`，而引擎的默认参数是
 * `deleteFile = true`，一次误点就把下载好的成品删了且没有确认（P0-1）。现在改成
 * 「默认只移除记录」，只有成品文件仍在磁盘上时才问这一句 —— 两个动作必须各有一个
 * 明确动词，不能都叫「删除」。
 */
data class RemoveTaskStrings(
    val title: String,
    /** 参数：文件名 */
    val message: (String) -> String,
    /** 只删列表记录，磁盘文件保留（默认动作） */
    val keepFile: String,
    /** 连磁盘上的成品一起删（破坏性动作，红色） */
    val deleteFile: String,
    val cancel: String,
)

fun removeTaskStrings(language: String): RemoveTaskStrings =
    if (language.equals("en-US", ignoreCase = true)) enUSRemoveTask else zhCNRemoveTask

private val zhCNRemoveTask = RemoveTaskStrings(
    title = "移除这个任务？",
    message = { name -> "「$name」已下载完成。移除任务记录时，要不要连磁盘上的文件一起删掉？" },
    keepFile = "仅移除任务",
    deleteFile = "移除并删除文件",
    cancel = "取消",
)

private val enUSRemoveTask = RemoveTaskStrings(
    title = "Remove this task?",
    message = { name -> "\"$name\" has finished downloading. Should the file on disk be deleted as well?" },
    keepFile = "Remove task only",
    deleteFile = "Remove and delete file",
    cancel = "Cancel",
)

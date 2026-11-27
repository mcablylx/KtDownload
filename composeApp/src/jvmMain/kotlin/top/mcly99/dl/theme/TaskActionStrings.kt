package top.mcly99.dl.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 任务卡片上纯图标按钮的无障碍文案（P2-23）。
 *
 * 刻意不并入 [UiStrings]：它的构造参数已逼近 JVM 方法签名 255 槽上限，
 * 再加一个字段编译能过、运行时却抛 `ClassFormatError: Too many arguments in method signature`。
 *
 * 也没有直接复用 [UiStrings] 里的现成文案：那里「暂停 / 开始」只有播放器的
 * `playerPlay = "播放"` 与批量的 `startAll = "全部开始"`，拿来描述单个下载任务的
 * 图标按钮会误导读屏用户；`retry` / `openFolder` / `deleteTask` 的文案则照搬进来，
 * 让同一行按钮的 contentDescription 全部来自同一张表，语种不会错配。
 *
 * 由 `AppNew` 通过 [LocalTaskActionStrings] 按 `AppSettings.language` 提供，
 * 组件无需层层传参；`@Preview` 未注入时回落到中文。
 */
data class TaskActionStrings(
    val pause: String,
    val resume: String,
    val retry: String,
    val openFolder: String,
    val remove: String,
)

private val zhCNTaskActions = TaskActionStrings(
    pause = "暂停任务",
    resume = "继续任务",
    // 下面三条照搬 UiStrings 的现成文案值，避免同一动作两种说法
    retry = "重试",
    openFolder = "打开文件夹",
    remove = "删除任务",
)

private val enUSTaskActions = TaskActionStrings(
    pause = "Pause task",
    resume = "Resume task",
    retry = "Retry",
    openFolder = "Open Folder",
    remove = "Delete Task",
)

/** 与 [stringsFor] 的回落规则一致：除 `en-US` 外一律中文 */
fun taskActionStrings(language: String): TaskActionStrings =
    if (language.equals("en-US", ignoreCase = true)) enUSTaskActions else zhCNTaskActions

val LocalTaskActionStrings = staticCompositionLocalOf { zhCNTaskActions }

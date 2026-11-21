package top.mcly99.dl.ui.components

/**
 * 顶部导航栏组件
 * 
 * 该组件实现了应用程序的顶部导航栏，根据不同视图显示不同的UI布局和功能按钮，
 * 提供统一的用户界面入口点和状态显示区域。
 * 
 * 视图模式适配：
 * - 任务视图（All/Downloading/Completed）：显示完整功能栏，包含添加任务、批量控制、搜索框和速度显示
 * - 抓网页视图（CatchWeb）：显示标题 + 网址输入框 + 抓取/取消按钮
 * - 传输视图（Transfer）：显示简洁标题栏，仅显示当前视图名称
 * - 设置视图（Settings）：显示简洁标题栏，仅显示当前视图名称
 * 
 * 主要功能区域：
 * - 左侧操作区：添加任务按钮
 * - 中部控制区：开始全部、暂停全部、清理完成等批量操作按钮
 * - 右侧状态区：下载/上传速度显示、搜索框
 * 
 * 设计特点：
 * - 响应式布局：使用Compose Layout API实现灵活的响应式设计
 * - 主题适配：完全支持应用主题颜色系统
 * - 可访问性：提供完整的contentDescription和语义化标签
 * - 预览支持：内置Compose Preview注解，支持IDE实时预览
 * 
 * @param currentView 当前显示的视图类型
 * @param searchQuery 搜索框当前文本
 * @param onSearchChange 搜索文本变化回调
 * @param onAddClick 添加任务按钮点击回调
 * @param onStartAll 开始全部任务回调
 * @param onPauseAll 暂停全部任务回调
 * @param onClearCompleted 清理已完成任务回调
 * @param onClearAll 全部删除回调（已完成 + 未完成，调用方负责二次确认）
 * @param hasTasks 是否还有任务；为 false 时「全部删除」置灰
 * @param totalDownloadSpeed 总下载速度（字节/秒）
 * @param modifier Compose修饰符
 * 
 * @see ViewType 视图类型枚举
 * @see Strings UI字符串资源
 * @see FlowDownTheme 应用主题
 */

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.mcly99.dl.model.ViewType
import top.mcly99.dl.theme.FlowDownColors
import top.mcly99.dl.theme.FlowDownTheme
import top.mcly99.dl.theme.Strings
import top.mcly99.dl.utils.formatSpeed

/**
 * 「抓网页」视图在顶栏需要的全部状态。
 *
 * 合成一个对象而不是给 [HeaderBar] 散开加四个参数：这四个值永远一起出现，
 * 而 `HeaderBar` 已经有 10 个参数了，再加四个会难读且容易传错顺序。
 * 带默认值还能让现有调用点与 `@Preview` 一行都不用改。
 */
data class CatchWebHeaderState(
    val url: String = "",
    val busy: Boolean = false,
    val onUrlChange: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
)

/** 「云盘」顶栏需要的全部状态：解析地址 + 提取码 + 解析按钮 */
data class CloudDriveHeaderState(
    val url: String = "",
    val passcode: String = "",
    val busy: Boolean = false,
    val onUrlChange: (String) -> Unit = {},
    val onPasscodeChange: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    /** D-29：打开「解析历史 / 链接收藏」选择弹窗 */
    val onShowHistory: () -> Unit = {},
    val onShowBookmarks: () -> Unit = {},
)

@Composable
fun HeaderBar(
    currentView: ViewType,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onAddClick: () -> Unit,
    onStartAll: () -> Unit,
    onPauseAll: () -> Unit,
    onClearCompleted: () -> Unit,
    onClearAll: () -> Unit = {},
    hasTasks: Boolean = true,
    totalDownloadSpeed: Long,
    totalUploadSpeed: Long = 0L,
    catchWeb: CatchWebHeaderState = CatchWebHeaderState(),
    cloudDrive: CloudDriveHeaderState = CloudDriveHeaderState(),
    /** P4「我的网盘」标题：独立文案表，不给 UiStrings 增加构造槽位 */
    myDriveTitle: String = "我的网盘",
    modifier: Modifier = Modifier,
) {
    val s = Strings.current
    when (currentView) {
        ViewType.CatchWeb -> {
            Row(
                modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(FlowDownColors.Card)
                    .topBorder(1.dp, FlowDownColors.Border)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    s.catchWeb,
                    style = MaterialTheme.typography.titleMedium,
                    color = FlowDownColors.Foreground,
                )
                Box(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .width(1.dp)
                        .height(24.dp)
                        .background(FlowDownColors.Border),
                )
                CompactTextField(
                    value = catchWeb.url,
                    onValueChange = catchWeb.onUrlChange,
                    // 撑满剩余宽度：长 URL 不至于被截成看不清
                    modifier = Modifier.weight(1f),
                    placeholder = s.catchWebPlaceholder,
                    leadingIcon = Icons.Default.Link,
                    onSubmit = catchWeb.onSubmit,
                )
                Spacer(Modifier.width(8.dp))
                CompactButton(
                    // 抓取中同一个按钮变成「取消」：长页面要等十几秒，用户最想点的就是它
                    text = if (catchWeb.busy) s.catchWebCancel else s.catchWebAction,
                    onClick = catchWeb.onSubmit,
                    enabled = catchWeb.busy || catchWeb.url.isNotBlank(),
                    // 与「添加任务」同一套样式：中性底色 + 图标，不做主色填充
                    icon = if (catchWeb.busy) Icons.Default.Close else Icons.Default.TravelExplore,
                )
            }
        }

        // 与「抓网页」同一套 head：标题 + 分隔线 + 输入框 + 按钮；
        // 只在输入框与解析按钮之间多一个「提取码(可选)」输入框。
        ViewType.CloudDrive -> {
            Row(
                modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(FlowDownColors.Card)
                    .topBorder(1.dp, FlowDownColors.Border)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    s.cloudDriveStrings.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = FlowDownColors.Foreground,
                )
                Box(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .width(1.dp)
                        .height(24.dp)
                        .background(FlowDownColors.Border),
                )
                CompactTextField(
                    value = cloudDrive.url,
                    onValueChange = cloudDrive.onUrlChange,
                    modifier = Modifier.weight(1f),
                    placeholder = s.cloudDriveStrings.urlPlaceholder,
                    leadingIcon = Icons.Default.Link,
                    onSubmit = cloudDrive.onSubmit,
                )
                Spacer(Modifier.width(8.dp))
                CompactTextField(
                    value = cloudDrive.passcode,
                    onValueChange = cloudDrive.onPasscodeChange,
                    modifier = Modifier.width(140.dp),
                    placeholder = s.cloudDriveStrings.passcodePlaceholder,
                    onSubmit = cloudDrive.onSubmit,
                )
                // D-29：解析历史 / 链接收藏入口，弹窗由 CloudDriveScreen 渲染
                IconButton(
                    onClick = cloudDrive.onShowHistory,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = s.cloudDriveStrings.linkHistory,
                        tint = FlowDownColors.MutedForeground,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = cloudDrive.onShowBookmarks,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Default.Bookmark,
                        contentDescription = s.cloudDriveStrings.linkBookmarks,
                        tint = FlowDownColors.MutedForeground,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                CompactButton(
                    text = if (cloudDrive.busy) s.catchWebCancel else s.cloudDriveStrings.action,
                    onClick = cloudDrive.onSubmit,
                    enabled = cloudDrive.busy || cloudDrive.url.isNotBlank(),
                    icon = if (cloudDrive.busy) Icons.Default.Close else Icons.Default.CloudQueue,
                )
            }
        }

        ViewType.Transfer, ViewType.Settings, ViewType.MyDrive -> {
            Row(
                modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(FlowDownColors.Card)
                    .topBorder(1.dp, FlowDownColors.Border)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (currentView) {
                        ViewType.Transfer -> s.lanTransfer
                        ViewType.MyDrive -> myDriveTitle
                        else -> s.settings
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = FlowDownColors.Foreground,
                )
            }
        }
        else -> {
            Row(
                modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(FlowDownColors.Card)
                    .topBorder(1.dp, FlowDownColors.Border)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactButton(text = s.addTask, onClick = onAddClick, icon = Icons.Default.Add)
                Box(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .width(1.dp)
                        .height(24.dp)
                        .background(FlowDownColors.Border),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactButton(text = s.startAll, onClick = onStartAll, icon = Icons.Default.PlayArrow)
                    CompactButton(text = s.pauseAll, onClick = onPauseAll, icon = Icons.Default.Pause)
                    CompactButton(text = s.clearCompleted, onClick = onClearCompleted, icon = Icons.Default.Delete)
                    // 与「清理已完成」之间加一道分隔：这条一旦点了会连未完成的任务一起清掉，
                    // 紧挨着放容易被当成同一档操作误点
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .width(1.dp)
                            .height(24.dp)
                            .background(FlowDownColors.Border),
                    )
                    CompactButton(
                        text = s.deleteAllStrings.button,
                        onClick = onClearAll,
                        icon = Icons.Default.DeleteSweep,
                        enabled = hasTasks,
                        destructive = true,
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = FlowDownColors.Primary, modifier = Modifier.size(16.dp))
                        Text(formatSpeed(totalDownloadSpeed), color = FlowDownColors.Primary, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = FlowDownColors.MutedForeground, modifier = Modifier.size(16.dp))
                        Text(formatSpeed(totalUploadSpeed), color = FlowDownColors.MutedForeground, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.width(16.dp))
                CompactTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    // 顶栏按钮多了一个之后，窗口缩到最小宽度（900dp）时会挤掉搜索框。
                    // 给个可压缩的宽度区间：宽屏仍是 220dp，窄屏最多让到 120dp，不至于整个消失
                    modifier = Modifier.widthIn(min = 120.dp, max = 220.dp).weight(1f, fill = false),
                    placeholder = s.searchPlaceholder,
                    leadingIcon = Icons.Default.Search,
                )
            }
        }
    }
}

/** 预览：顶栏·任务视图（约 900×56） */
@Preview
@Composable
private fun PreviewHeaderBarTaskViews() {
    FlowDownTheme {
        Box(Modifier.size(900.dp, 56.dp)) {
            HeaderBar(
                currentView = ViewType.All,
                searchQuery = "",
                onSearchChange = {},
                onAddClick = {},
                onStartAll = {},
                onPauseAll = {},
                onClearCompleted = {},
                totalDownloadSpeed = 18L * 1024 * 1024,
            )
        }
    }
}

/** 预览：顶栏·抓网页（空闲 / 抓取中） */
@Preview
@Composable
private fun PreviewHeaderBarCatchWeb() {
    FlowDownTheme {
        Column(Modifier.width(900.dp)) {
            HeaderBar(
                currentView = ViewType.CatchWeb,
                searchQuery = "",
                onSearchChange = {},
                onAddClick = {},
                onStartAll = {},
                onPauseAll = {},
                onClearCompleted = {},
                totalDownloadSpeed = 0,
                catchWeb = CatchWebHeaderState(url = "https://example.com/video/123"),
            )
            HeaderBar(
                currentView = ViewType.CatchWeb,
                searchQuery = "",
                onSearchChange = {},
                onAddClick = {},
                onStartAll = {},
                onPauseAll = {},
                onClearCompleted = {},
                totalDownloadSpeed = 0,
                catchWeb = CatchWebHeaderState(url = "https://example.com/video/123", busy = true),
            )
        }
    }
}

/** 预览：顶栏·传输 / 设置标题栏 */
@Preview
@Composable
private fun PreviewHeaderBarTitleOnly() {
    FlowDownTheme {
        Column(Modifier.width(600.dp)) {
            HeaderBar(
                currentView = ViewType.Transfer,
                searchQuery = "",
                onSearchChange = {},
                onAddClick = {},
                onStartAll = {},
                onPauseAll = {},
                onClearCompleted = {},
                totalDownloadSpeed = 0,
            )
            HeaderBar(
                currentView = ViewType.Settings,
                searchQuery = "",
                onSearchChange = {},
                onAddClick = {},
                onStartAll = {},
                onPauseAll = {},
                onClearCompleted = {},
                totalDownloadSpeed = 0,
            )
        }
    }
}

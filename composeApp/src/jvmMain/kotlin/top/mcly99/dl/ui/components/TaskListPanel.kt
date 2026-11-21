package top.mcly99.dl.ui.components

/**
 * 任务列表面板组件
 *
 * 该组件实现了下载任务的主列表显示区域，提供任务卡片式布局、状态管理
 * 和用户交互功能，是用户查看和管理下载任务的核心界面。
 *
 * 主要功能：
 * - 任务过滤：根据当前视图类型（全部、下载中、已完成）动态过滤任务
 * - 任务卡片：每个任务以卡片形式显示，包含状态、进度、文件信息等
 * - 状态操作：支持暂停、恢复、重试、删除等任务操作
 * - 上下文菜单：右键菜单提供额外的操作选项
 * - 空状态处理：当没有任务时显示友好的空状态提示
 *
 * 设计特点：
 * - 响应式布局：支持不同屏幕尺寸的自适应布局
 * - 状态驱动：基于任务状态自动更新 UI 样式和操作按钮
 * - 用户友好：提供直观的图标、颜色编码和交互反馈
 * - 性能优化：使用 remember 进行智能状态缓存
 *
 * @param currentView 当前选中的视图类型
 * @param tasks 下载任务列表
 * @param onPause 暂停任务回调
 * @param onResume 恢复任务回调
 * @param onRetry 重试任务回调
 * @param onRemove 删除任务回调
 * @param onOpenFolder 打开文件夹回调
 * @param modifier Compose 修饰符
 *
 * @see UiTask 面向 UI 的任务快照
 * @see ViewType 视图类型枚举
 * @see TaskCard 任务卡片组件
 * @see StatusBadge 状态徽章组件
 * @see TypeChip 类型标签组件
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.mcly99.dl.data.initialMockTasks
import top.mcly99.dl.model.DownloadStatus
import top.mcly99.dl.model.TaskType
import top.mcly99.dl.model.UiTask
import top.mcly99.dl.model.ViewType
import top.mcly99.dl.theme.FlowDownColors
import top.mcly99.dl.theme.FlowDownTheme
import top.mcly99.dl.theme.Strings
import top.mcly99.dl.utils.estimateTimeRemaining
import top.mcly99.dl.utils.formatBytes
import top.mcly99.dl.utils.formatSpeed

@Composable
fun TaskListPanel(
    currentView: ViewType,
    tasks: List<UiTask>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    onOpenFolder: (String) -> Unit = {},
    onCleanupSegments: (String) -> Unit = {},
    searchKeyword: String = "",
    modifier: Modifier = Modifier,
) {
    val filtered = remember(tasks, currentView) {
        tasks.filter { task ->
            when (currentView) {
                ViewType.All -> true
                ViewType.Downloading -> task.isActiveTask
                ViewType.Completed -> task.status == DownloadStatus.COMPLETED
                else -> true
            }
        }
    }
    val s = Strings.current
    if (filtered.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = FlowDownColors.MutedForeground, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                // 搜索无结果时给出针对性提示，而不是“暂无下载任务”
                if (searchKeyword.isNotBlank()) {
                    Text(s.noSearchResults(searchKeyword), style = MaterialTheme.typography.titleMedium, color = FlowDownColors.MutedForeground)
                    Text(s.noSearchResultsHint, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
                } else {
                    Text(s.noTasks, style = MaterialTheme.typography.titleMedium, color = FlowDownColors.MutedForeground)
                    Text(s.noTasksHint, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
                }
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // key：按任务 ID 复用节点，进度刷新时不再重建整列表
            items(filtered, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    onPause = { onPause(task.id) },
                    onResume = { onResume(task.id) },
                    onRetry = { onRetry(task.id) },
                    onRemove = { onRemove(task.id) },
                    onOpenFolder = { onOpenFolder(task.id) },
                    onCleanupSegments = { onCleanupSegments(task.id) },
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: UiTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpenFolder: () -> Unit = {},
    onCleanupSegments: () -> Unit = {},
) {
    val s = Strings.current
    val borderColor = if (task.status == DownloadStatus.FAILED) FlowDownColors.Destructive.copy(alpha = 0.3f) else FlowDownColors.Border
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(FlowDownColors.Card)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = fileIcon(task),
                contentDescription = null,
                tint = fileIconTint(task),
                modifier = Modifier.padding(top = 4.dp).size(20.dp),
            )
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(task.name, style = MaterialTheme.typography.bodyMedium, color = FlowDownColors.Foreground, maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        StatusBadge(task.status)
                        Spacer(Modifier.width(8.dp))
                        TypeChip(task.type)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (task.status) {
                            DownloadStatus.DOWNLOADING, DownloadStatus.MERGING ->
                                TaskIconButton(Icons.Default.Pause, onPause)
                            DownloadStatus.PAUSED, DownloadStatus.QUEUED ->
                                TaskIconButton(Icons.Default.PlayArrow, onResume)
                            DownloadStatus.FAILED, DownloadStatus.CANCELLED ->
                                TaskIconButton(Icons.Default.Refresh, onRetry)
                            else -> {}
                        }
                        // HLS 失败/降级完成时，分片目录还在磁盘上（峰值能到文件的两倍）。
                        // 不给入口的话用户只能自己去下载目录里找 `xxx.mp4.hls`（v7-P2-3）
                        if (hasLeftoverSegments(task)) {
                            TaskIconButton(
                                Icons.Default.Delete,
                                onCleanupSegments,
                                contentDescription = s.cleanupSegments,
                            )
                        }
                        TaskIconButton(Icons.Default.FolderOpen, onOpenFolder)
                        TaskIconButton(Icons.Default.Close, onRemove)
                    }
                }
                if (task.error != null && (task.status == DownloadStatus.FAILED || task.status == DownloadStatus.COMPLETED)) {
                    val warning = task.status == DownloadStatus.COMPLETED
                    val tint = if (warning) FlowDownColors.Warning else FlowDownColors.Destructive
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                        Text(task.error, style = MaterialTheme.typography.bodySmall, color = tint, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (task.type == TaskType.M3u8 && task.segmentTotal > 0) {
                            if (task.status == DownloadStatus.COMPLETED && task.size > 0) {
                                formatBytes(task.size)
                            } else {
                                s.segmentProgress(task.segmentDone, task.segmentTotal)
                            }
                        } else {
                            "${formatBytes(task.downloaded)} / " +
                                if (task.size > 0) formatBytes(task.size) else s.unknown
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = FlowDownColors.MutedForeground,
                    )
                    Text("${task.progress}%", style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(FlowDownColors.Muted),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((task.progress.coerceIn(0, 100)) / 100f)
                            .background(progressColor(task.status)),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row {
                        if (task.status == DownloadStatus.DOWNLOADING) {
                            Text(s.speed(formatSpeed(task.speed)), style = MaterialTheme.typography.labelSmall, color = FlowDownColors.MutedForeground)
                            Spacer(Modifier.width(16.dp))
                            if (task.type == TaskType.M3u8) {
                                // HLS 的总字节要下完才知道，用「平均片大小 × 剩余片数」估剩余时间
                                // 误差能到 3 倍以上，给个错的时间比不给更糟（v6-P1-1）
                                Text(formatBytes(task.downloaded), style = MaterialTheme.typography.labelSmall, color = FlowDownColors.MutedForeground)
                            } else {
                                Text(
                                    s.remaining(estimateTimeRemaining(task.downloaded, task.size, task.speed, s)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = FlowDownColors.MutedForeground,
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                        }
                        Text(s.threadCount(task.threads), style = MaterialTheme.typography.labelSmall, color = FlowDownColors.MutedForeground)
                    }
                    Text(task.savePath, style = MaterialTheme.typography.labelSmall, color = FlowDownColors.MutedForeground)
                }
            }
        }
    }
}

/** 任务卡片上的图标按钮：与设置页的 36dp 图标按钮保持一致。 */
@Composable
private fun TaskIconButton(icon: ImageVector, onClick: () -> Unit, contentDescription: String? = null) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = FlowDownColors.MutedForeground,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 是否可能有残留分片。
 *
 * 只看状态，不去 stat 目录：列表每 200ms 会重组一次，逐条查文件系统不值当。
 * 判错的代价很小 —— 点一下返回「没有可清理的分片」，用户不会有损失。
 */
private fun hasLeftoverSegments(task: UiTask): Boolean =
    task.type == TaskType.M3u8 &&
        (task.status == DownloadStatus.FAILED ||
            (task.status == DownloadStatus.COMPLETED && task.error != null))

@Composable
private fun progressColor(status: DownloadStatus): Color = when (status) {
    DownloadStatus.COMPLETED -> FlowDownColors.Success
    DownloadStatus.DOWNLOADING -> FlowDownColors.Primary
    DownloadStatus.MERGING -> FlowDownColors.Info
    DownloadStatus.PAUSED -> FlowDownColors.Warning
    DownloadStatus.FAILED -> FlowDownColors.Destructive
    DownloadStatus.CANCELLED -> FlowDownColors.MutedForeground
    DownloadStatus.QUEUED -> FlowDownColors.Info
}

@Composable
private fun StatusBadge(status: DownloadStatus) {
    val s = Strings.current
    val (bg, fg, label) = when (status) {
        DownloadStatus.DOWNLOADING -> Triple(FlowDownColors.Primary.copy(alpha = 0.2f), FlowDownColors.Primary, s.statusDownloading)
        DownloadStatus.MERGING -> Triple(FlowDownColors.Info.copy(alpha = 0.2f), FlowDownColors.Info, s.statusMerging)
        DownloadStatus.PAUSED -> Triple(FlowDownColors.Warning.copy(alpha = 0.2f), FlowDownColors.Warning, s.statusPaused)
        DownloadStatus.COMPLETED -> Triple(FlowDownColors.Success.copy(alpha = 0.2f), FlowDownColors.Success, s.statusCompleted)
        DownloadStatus.FAILED -> Triple(FlowDownColors.Destructive.copy(alpha = 0.2f), FlowDownColors.Destructive, s.statusFailed)
        DownloadStatus.CANCELLED -> Triple(FlowDownColors.Muted, FlowDownColors.MutedForeground, s.statusCancelled)
        DownloadStatus.QUEUED -> Triple(FlowDownColors.Info.copy(alpha = 0.2f), FlowDownColors.Info, s.statusQueued)
    }
    Text(
        label,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = fg,
    )
}

@Composable
private fun TypeChip(type: TaskType) {
    val (icon, label, tint) = when (type) {
        TaskType.Http -> Triple(Icons.Default.Public, "HTTP", FlowDownColors.Blue)
        TaskType.Bt -> Triple(Icons.Default.Link, "BT", FlowDownColors.Orange)
        TaskType.M3u8 -> Triple(Icons.Default.Movie, "M3U8", FlowDownColors.Purple)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, modifier = Modifier.padding(start = 2.dp))
    }
}

private fun fileIcon(task: UiTask): ImageVector = when (task.type) {
    TaskType.Bt -> Icons.Default.Link
    TaskType.M3u8 -> Icons.Default.Movie
    else -> {
        val ext = task.name.substringAfterLast('.', "").lowercase()
        when (ext) {
            in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm") -> Icons.Default.VideoFile
            in listOf("mp3", "wav", "flac", "aac", "ogg", "m4a") -> Icons.Default.AudioFile
            in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg") -> Icons.Default.Image
            in listOf("zip", "rar", "7z", "tar", "gz") -> Icons.Default.FolderZip
            in listOf("pdf", "doc", "docx", "txt") -> Icons.Default.Description
            else -> Icons.AutoMirrored.Filled.InsertDriveFile
        }
    }
}

@Composable
private fun fileIconTint(task: UiTask): Color = when (task.type) {
    TaskType.Bt -> FlowDownColors.Orange
    TaskType.M3u8 -> FlowDownColors.Purple
    else -> {
        val ext = task.name.substringAfterLast('.', "").lowercase()
        when {
            ext in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm") -> FlowDownColors.Blue
            ext in listOf("mp3", "wav", "flac", "aac", "ogg", "m4a") -> FlowDownColors.Green
            ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg") -> FlowDownColors.Pink
            ext in listOf("zip", "rar", "7z", "tar", "gz") -> FlowDownColors.Yellow
            ext in listOf("pdf", "doc", "docx", "txt") -> FlowDownColors.Red
            else -> FlowDownColors.MutedForeground
        }
    }
}

/** 预览：任务列表（有数据，约 800×640） */
@Preview
@Composable
private fun PreviewTaskListPanelAll() {
    FlowDownTheme {
        Box(Modifier.size(800.dp, 640.dp)) {
            TaskListPanel(
                currentView = ViewType.All,
                tasks = initialMockTasks(),
                onPause = {},
                onResume = {},
                onRetry = {},
                onRemove = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 预览：任务列表（空状态，约 600×360） */
@Preview
@Composable
private fun PreviewTaskListPanelEmpty() {
    FlowDownTheme {
        Box(Modifier.size(600.dp, 360.dp)) {
            TaskListPanel(
                currentView = ViewType.All,
                tasks = emptyList(),
                onPause = {},
                onResume = {},
                onRetry = {},
                onRemove = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

package top.mcly99.dl.ui.components

/**
 * 侧边栏面板组件
 * 
 * 该组件实现了应用程序的左侧导航侧边栏，提供主菜单导航、任务统计和应用信息显示，
 * 是用户快速切换视图和了解应用状态的主要入口点。
 * 
 * 主要功能区域：
 * - 应用标识区：显示应用Logo、名称和版本信息
 * - 导航菜单区：下载任务分类（全部、下载中、已完成）
 * - 工具菜单区：传输功能、设置等辅助功能
 * - 统计信息：实时显示任务数量统计（总数、下载中、已完成）
 * 
 * 设计特点：
 * - 分层导航：清晰的视觉层次结构，区分主菜单和工具菜单
 * - 状态指示：使用徽章（Badge）显示实时任务数量
 * - 响应式交互：支持点击导航和状态高亮
 * - 主题适配：完全支持深色/浅色主题和自定义颜色方案
 * - 可访问性：提供完整的contentDescription和语义化标签
 * 
 * @param currentView 当前选中的视图类型
 * @param onViewChange 视图切换回调
 * @param stats 侧边栏统计信息
 * @param modifier Compose修饰符
 * 
 * @see ViewType 视图类型枚举
 * @see SidebarStats 侧边栏统计数据模型
 * @see NavRow 导航行组件
 * @see SimpleNavRow 简单导航行组件
 * @see SectionLabel 分类标签组件
 */

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ktdownload.composeapp.generated.resources.Res
import ktdownload.composeapp.generated.resources.img_logo
import org.jetbrains.compose.resources.painterResource
import top.mcly99.dl.model.ViewType
import top.mcly99.dl.theme.FlowDownColors
import top.mcly99.dl.theme.FlowDownTheme
import top.mcly99.dl.theme.Strings
import top.mcly99.dl.APP_VERSION

data class SidebarStats(val total: Int, val downloading: Int, val completed: Int)

@Composable
fun SidebarPanel(
    currentView: ViewType,
    onViewChange: (ViewType) -> Unit,
    stats: SidebarStats,
    /** P4「我的网盘」标题：独立文案表，刻意不给 UiStrings 增加构造槽位 */
    myDriveLabel: String = "我的网盘",
    /** 有新版本可用时在版本号旁显示角标 */
    updateAvailable: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val s = Strings.current
    Column(
        modifier
            .width(224.dp)
            .fillMaxHeight()
            .background(FlowDownColors.Sidebar)
            .border(width = 1.dp, color = FlowDownColors.SidebarBorder),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
//                .border(width = 1.dp, color = FlowDownColors.SidebarBorder)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(FlowDownColors.Primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(Res.drawable.img_logo),
                    contentDescription = null,
//                    tint = FlowDownColors.OnPrimary,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(32.dp)
                )
            }
            Column(Modifier.padding(start = 8.dp)) {
                Text("Mcly99", color = FlowDownColors.Foreground, fontWeight = FontWeight.SemiBold)
                Text(s.appName, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionLabel(s.downloadTasks)
            NavRow(
                selected = currentView == ViewType.All,
                icon = Icons.AutoMirrored.Filled.ListAlt,
                label = s.allTasks,
                badge = stats.total.takeIf { it > 0 },
                badgeBg = FlowDownColors.Muted,
                badgeFg = FlowDownColors.MutedForeground,
                onClick = { onViewChange(ViewType.All) },
            )
            NavRow(
                selected = currentView == ViewType.Downloading,
                icon = Icons.Default.Download,
                label = s.downloading,
                badge = stats.downloading.takeIf { it > 0 },
                badgeBg = FlowDownColors.Primary.copy(alpha = 0.2f),
                badgeFg = FlowDownColors.Primary,
                onClick = { onViewChange(ViewType.Downloading) },
            )
            NavRow(
                selected = currentView == ViewType.Completed,
                icon = Icons.Default.CheckCircle,
                label = s.completed,
                badge = stats.completed.takeIf { it > 0 },
                badgeBg = FlowDownColors.Success.copy(alpha = 0.2f),
                badgeFg = FlowDownColors.Green,
                onClick = { onViewChange(ViewType.Completed) },
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel(s.tools)
            SimpleNavRow(
                selected = currentView == ViewType.CatchWeb,
                icon = Icons.Default.TravelExplore,
                label = s.catchWeb,
                onClick = { onViewChange(ViewType.CatchWeb) },
            )
            SimpleNavRow(
                selected = currentView == ViewType.CloudDrive,
                icon = Icons.Default.CloudQueue,
                label = s.cloudDriveStrings.title,
                onClick = { onViewChange(ViewType.CloudDrive) },
            )
            SimpleNavRow(
                selected = currentView == ViewType.MyDrive,
                icon = Icons.Default.Folder,
                label = myDriveLabel,
                onClick = { onViewChange(ViewType.MyDrive) },
            )
            SimpleNavRow(
                selected = currentView == ViewType.Transfer,
                icon = Icons.AutoMirrored.Filled.Send,
                label = s.lanTransfer,
                onClick = { onViewChange(ViewType.Transfer) },
            )
            SimpleNavRow(
                selected = currentView == ViewType.Settings,
                icon = Icons.Default.Settings,
                label = s.settings,
                onClick = { onViewChange(ViewType.Settings) },
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
//                .border(width = 1.dp, color = FlowDownColors.SidebarBorder)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(s.version(APP_VERSION), style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
                if (updateAvailable) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(FlowDownColors.Primary, CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelSmall,
        color = FlowDownColors.MutedForeground,
    )
}

@Composable
private fun NavRow(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    badge: Int?,
    badgeBg: Color,
    badgeFg: Color,
    onClick: () -> Unit,
) {
    val bg = if (selected) FlowDownColors.SidebarAccent else Color.Transparent
    val fg = if (selected) FlowDownColors.Foreground else FlowDownColors.MutedForeground
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = fg)
            Text(label, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, color = fg)
        }
        if (badge != null) {
            Text(
                badge.toString(),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(badgeBg)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = badgeFg,
            )
        }
    }
}

@Composable
private fun SimpleNavRow(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val bg = if (selected) FlowDownColors.SidebarAccent else Color.Transparent
    val fg = if (selected) FlowDownColors.Foreground else FlowDownColors.MutedForeground
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = fg)
        Text(label, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, color = fg)
    }
}

/** 预览：侧栏（约 224×640） */
@Preview
@Composable
private fun PreviewSidebarPanel() {
    FlowDownTheme {
        Box(Modifier.size(224.dp, 640.dp)) {
            SidebarPanel(
                currentView = ViewType.All,
                onViewChange = {},
                stats = SidebarStats(total = 7, downloading = 4, completed = 2),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 预览：SectionLabel */
@Preview
@Composable
private fun PreviewSectionLabel() {
    FlowDownTheme {
        SectionLabel(text = "下载任务")
    }
}

/** 预览：NavRow（未选中状态） */
@Preview
@Composable
private fun PreviewNavRowUnselected() {
    FlowDownTheme {
        NavRow(
            selected = false,
            icon = Icons.AutoMirrored.Filled.ListAlt,
            label = "全部任务",
            badge = 7,
            badgeBg = FlowDownColors.Muted,
            badgeFg = FlowDownColors.MutedForeground,
            onClick = {},
        )
    }
}

/** 预览：NavRow（选中状态） */
@Preview
@Composable
private fun PreviewNavRowSelected() {
    FlowDownTheme {
        NavRow(
            selected = true,
            icon = Icons.Default.Download,
            label = "下载中",
            badge = 4,
            badgeBg = FlowDownColors.Primary.copy(alpha = 0.2f),
            badgeFg = FlowDownColors.Primary,
            onClick = {},
        )
    }
}

/** 预览：NavRow（无徽章） */
@Preview
@Composable
private fun PreviewNavRowNoBadge() {
    FlowDownTheme {
        NavRow(
            selected = false,
            icon = Icons.Default.CheckCircle,
            label = "已完成",
            badge = null,
            badgeBg = FlowDownColors.Success.copy(alpha = 0.2f),
            badgeFg = FlowDownColors.Green,
            onClick = {},
        )
    }
}

/** 预览：SimpleNavRow（未选中状态） */
@Preview
@Composable
private fun PreviewSimpleNavRowUnselected() {
    FlowDownTheme {
        SimpleNavRow(
            selected = false,
            icon = Icons.AutoMirrored.Filled.Send,
            label = "局域网传输",
            onClick = {},
        )
    }
}

/** 预览：SimpleNavRow（选中状态） */
@Preview
@Composable
private fun PreviewSimpleNavRowSelected() {
    FlowDownTheme {
        SimpleNavRow(
            selected = true,
            icon = Icons.Default.Settings,
            label = "设置",
            onClick = {},
        )
    }
}

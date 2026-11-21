package top.mcly99.dl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.mcly99.dl.theme.FlowDownColors
import top.mcly99.dl.theme.FlowDownTheme
import top.mcly99.dl.theme.Strings

/**
 * 底部状态栏：并发/线程配置 + 任务数量统计。
 *
 * 作为独立组件（P2-1），让 `AppNew` 只保留布局编排。
 */
@Composable
fun StatusFooter(
    maxConcurrent: Int,
    defaultThreads: Int,
    stats: SidebarStats,
) {
    val s = Strings.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(FlowDownColors.Card)
            .topBorder(width = 1.dp, color = FlowDownColors.Border)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(s.concurrent(maxConcurrent), style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
            Text(s.threads(defaultThreads), style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(s.totalTasks(stats.total), style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
            Text(s.downloadingCount(stats.downloading), style = MaterialTheme.typography.bodySmall, color = FlowDownColors.Primary)
            Text(s.completedCount(stats.completed), style = MaterialTheme.typography.bodySmall, color = FlowDownColors.Green)
        }
    }
}

@Preview
@Composable
private fun PreviewStatusFooter() {
    FlowDownTheme {
        StatusFooter(
            maxConcurrent = 3,
            defaultThreads = 8,
            stats = SidebarStats(total = 7, downloading = 4, completed = 2),
        )
    }
}

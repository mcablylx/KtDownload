package top.mcly99.dl.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.mcly99.dl.data.network.UpdateInfo
import top.mcly99.dl.theme.FlowDownColors
import top.mcly99.dl.theme.UpdateStrings

/**
 * 「发现新版本」提示框。
 *
 * 无内部状态：显示与否、下载进度都由调用方（AppViewModel）注入，
 * 与项目里 ConfirmDialog / AddTaskDialog 的模式一致。
 *
 * @param downloading 是否正在下载；为 true 时显示进度条，主按钮切换为「取消下载」。
 * @param progress 下载进度 0f..1f；负数表示总量未知（显示不确定进度条）。
 * @param onCancel 取消正在进行的下载；下载中主按钮与 Esc 之外的关闭都走这里。
 */
@Composable
fun UpdateDialog(
    open: Boolean,
    info: UpdateInfo?,
    strings: UpdateStrings,
    downloading: Boolean,
    progress: Float,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onCancel: () -> Unit,
    onOpenRelease: () -> Unit,
) {
    if (!open || info == null) return

    AlertDialog(
        // 下载中禁用 Esc/遮罩关闭：否则弹窗消失但下载在后台继续，完成后自动安装/退出（P1-1）
        onDismissRequest = if (downloading) { {} } else onLater,
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = FlowDownColors.Primary) },
        title = { Text(strings.newVersionTitle, color = FlowDownColors.Foreground) },
        text = {
            Column(modifier = Modifier.widthIn(max = 420.dp)) {
                Text(
                    strings.newVersionMessage(info.version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlowDownColors.Foreground,
                )
                if (!info.notes.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = FlowDownColors.MutedForeground,
                        maxLines = 12,
                    )
                }
                if (downloading) {
                    Spacer(Modifier.height(12.dp))
                    Text(strings.downloading, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
                    Spacer(Modifier.height(6.dp))
                    if (progress >= 0f) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.widthIn(max = 380.dp))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.widthIn(max = 380.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                OpenReleaseTextButton(strings.openReleasePage, onOpenRelease)
            }
        },
        // 与应用内工具栏/设置页按钮统一：主操作实心主色，次操作中性底色，
        // 均为 36dp 高、6dp 圆角（见 CompactButton）。
        confirmButton = {
            CompactButton(
                text = if (downloading) strings.cancelDownload else strings.updateNow,
                onClick = if (downloading) onCancel else onUpdate,
                primary = true,
            )
        },
        dismissButton = {
            // 下载中不显示「稍后」：避免与后台下载状态脱节
            if (!downloading) {
                CompactButton(
                    text = strings.later,
                    onClick = onLater,
                )
            }
        },
        containerColor = FlowDownColors.Card,
        iconContentColor = FlowDownColors.Primary,
        titleContentColor = FlowDownColors.Foreground,
        textContentColor = FlowDownColors.MutedForeground,
    )
}

/** Release 页面兜底入口的独立小按钮，放在对话框之外由设置页/侧边栏调用。 */
@Composable
fun OpenReleaseTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(text, color = FlowDownColors.Primary) }
}

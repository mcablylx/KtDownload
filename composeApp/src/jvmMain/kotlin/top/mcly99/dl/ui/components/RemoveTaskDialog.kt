package top.mcly99.dl.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import top.mcly99.dl.theme.FlowDownColors
import top.mcly99.dl.theme.RemoveTaskStrings

/**
 * 「移除任务」确认框：让用户在「只删记录」和「连文件一起删」之间明确选一个。
 *
 * 为什么不用通用的 [ConfirmDialog]：它只有一个确认按钮，表达不了「两个都合法但后果不同」
 * 的选择。任务卡片上的「×」原本直接删文件且不问（P0-1），修法不是「加一个确认」——
 * 那样用户每次清理列表都要为一个不该有的破坏性默认值买单 —— 而是把默认动作改成
 * **保留文件**，只有在这种破坏真的有意义时（成品还在磁盘上）才多问一句。
 *
 * 布局约定（防误点）：
 * - 安全动作「仅移除任务」在最上，实心主色，是视觉上的默认项；
 * - 破坏性动作「移除并删除文件」在中间，红色描边（不用实心红底，避免与默认项抢注意力）；
 * - 「取消」在最下，纯文字；
 * - 三个按钮都是全宽竖排：横排三个动词会挤成一小撮，反而更容易点错。
 */
@Composable
fun RemoveTaskDialog(
    open: Boolean,
    taskName: String,
    strings: RemoveTaskStrings,
    onKeepFile: () -> Unit,
    onDeleteFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!open) return

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = FlowDownColors.Card,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(min = 320.dp, max = 420.dp),
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.Start) {
                Text(
                    strings.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = FlowDownColors.Foreground,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    strings.message(taskName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlowDownColors.MutedForeground,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onKeepFile,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FlowDownColors.Primary,
                        contentColor = FlowDownColors.OnPrimary,
                    ),
                ) {
                    Text(strings.keepFile)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDeleteFile,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, FlowDownColors.Destructive),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = FlowDownColors.Destructive),
                ) {
                    Text(strings.deleteFile)
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.cancel, color = FlowDownColors.MutedForeground)
                }
            }
        }
    }
}

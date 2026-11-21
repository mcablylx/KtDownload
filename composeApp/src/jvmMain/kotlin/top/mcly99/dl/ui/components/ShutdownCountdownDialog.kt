package top.mcly99.dl.ui.components

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.mcly99.dl.theme.FlowDownColors
import top.mcly99.dl.theme.ShutdownStrings

/**
 * 下载完成后关机的 60 秒倒计时弹窗。
 *
 * 刻意**不复用** `ConfirmDialog`：那个组件点遮罩 / 按 Esc 都会走 onDismiss，
 * 而关机这种动作必须显式选择「取消关机」或「立即关机」才改变状态 —— 否则用户随手
 * 点一下弹窗外，倒计时看起来消失了，机器仍会在归零时关机。
 *
 * 倒计时数字来自常驻控制器，每秒刷新；弹窗本身不持有计时逻辑。
 */
@Composable
fun ShutdownCountdownDialog(
    open: Boolean,
    seconds: Int,
    strings: ShutdownStrings,
    onCancel: () -> Unit,
    onShutdownNow: () -> Unit,
) {
    if (!open) return
    AlertDialog(
        // 空实现 = 点遮罩 / Esc 不会误关倒计时窗口
        onDismissRequest = {},
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = FlowDownColors.Destructive) },
        title = { Text(strings.dialogTitle, color = FlowDownColors.Foreground) },
        text = {
            Text(
                strings.dialogMessage(seconds),
                modifier = Modifier.widthIn(max = 420.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = FlowDownColors.MutedForeground,
            )
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel) { Text(strings.cancel) }
        },
        confirmButton = {
            Button(
                onClick = onShutdownNow,
                colors = ButtonDefaults.buttonColors(
                    containerColor = FlowDownColors.Destructive,
                    contentColor = Color.White,
                ),
            ) { Text(strings.shutdownNow) }
        },
        containerColor = FlowDownColors.Card,
        iconContentColor = FlowDownColors.Destructive,
        titleContentColor = FlowDownColors.Foreground,
        textContentColor = FlowDownColors.MutedForeground,
    )
}

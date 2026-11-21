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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.mcly99.dl.theme.FlowDownColors

/**
 * 通用二次确认弹窗。
 *
 * 只为「不可撤销的批量操作」而存在（例如顶栏的「全部删除」）：这类操作点错一次就要
 * 重新添加几十个任务，光靠按钮文案拦不住误触，必须再问一遍。
 *
 * 交互约定：
 * - 确认按钮用 [FlowDownColors.Destructive] 底色，与「取消」在视觉上明确区分；
 * - 确认动作**不会**由回车默认触发 —— 键盘焦点初始落在弹窗本体，只有显式点击才生效，
 *   避免用户顺手回车把任务删光（这正是二次确认要防的事）；
 * - 弹窗只负责「问」，删除逻辑由 [onConfirm] 注入，组件本身不碰引擎。
 *
 * @param open 是否显示；false 时直接返回，不产生额外布局节点
 * @param title 标题（一句话说清要删什么）
 * @param message 正文：必须写明影响范围与不可恢复的部分
 * @param confirmText 确认按钮文案；用动词而非「确定」，降低误点概率
 * @param dismissText 取消按钮文案
 * @param onConfirm 用户点击确认按钮后执行
 * @param onDismiss 取消 / 点击遮罩 / 按 Esc 时执行
 * @param icon 标题上方的图标
 * @param destructive 为 true 时确认按钮使用警示色
 */
@Composable
fun ConfirmDialog(
    open: Boolean,
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector = Icons.Default.Warning,
    destructive: Boolean = true,
) {
    if (!open) return

    val accent = if (destructive) FlowDownColors.Destructive else FlowDownColors.Primary

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null, tint = accent) },
        title = { Text(title, color = FlowDownColors.Foreground) },
        text = {
            Text(
                message,
                modifier = Modifier.widthIn(max = 420.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = FlowDownColors.MutedForeground,
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    // 先关窗再执行：删除过程会刷新整张列表，弹窗留着会跟状态竞争
                    onDismiss()
                    onConfirm()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = if (destructive) Color.White else FlowDownColors.OnPrimary,
                ),
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(dismissText) }
        },
        containerColor = FlowDownColors.Card,
        iconContentColor = accent,
        titleContentColor = FlowDownColors.Foreground,
        textContentColor = FlowDownColors.MutedForeground,
    )
}

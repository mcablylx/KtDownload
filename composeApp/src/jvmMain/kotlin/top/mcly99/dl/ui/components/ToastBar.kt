package top.mcly99.dl.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.mcly99.dl.theme.FlowDownColors

/**
 * 应用内一次性提示条（几秒后由调用方的状态自动清空）。
 *
 * 只用于「操作结果必须让用户知道」的场景，例如清理 HLS 分片释放了多少空间。
 * 为什么不能只发系统通知：通知开关可以关、macOS 上还依赖托盘气泡是否可见 ——
 * 两条通道都失效时，用户点完按钮会以为什么都没发生（P1-11）。
 *
 * 刻意不做动画与队列：这是应用自己的窗口内提示，同一时刻只有一件事需要说，
 * 真正的队列会引出「先来的提示被后来的顶掉」这种没人想维护的规则。
 */
@Composable
fun ToastBar(message: String?, modifier: Modifier = Modifier) {
    if (message.isNullOrBlank()) return
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = FlowDownColors.Card,
        tonalElevation = 6.dp,
        modifier = modifier.widthIn(max = 520.dp),
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = FlowDownColors.Foreground,
        )
    }
}

package top.mcly99.dl.ui.components

/**
 * 紧凑按钮：36dp 高、6dp 圆角；主操作使用主色，其余使用中性底色。
 *
 * 任务工具栏、设置页与局域网传输页共用，避免各页面按钮风格漂移。
 */
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.mcly99.dl.theme.FlowDownColors

@Composable
fun CompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    primary: Boolean = false,
    /** 破坏性操作（如「全部删除」）：只把文字/图标染成警示色，底色仍保持中性，避免满屏红块 */
    destructive: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) FlowDownColors.Primary else FlowDownColors.Muted,
            contentColor = when {
                primary -> FlowDownColors.OnPrimary
                destructive -> FlowDownColors.Destructive
                else -> FlowDownColors.Foreground
            },
            disabledContainerColor = FlowDownColors.Muted,
            disabledContentColor = FlowDownColors.MutedForeground,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

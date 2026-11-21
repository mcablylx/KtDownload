package top.mcly99.dl.ui.components

/**
 * 紧凑开关：缩放到 Material3 开关的一半，视觉尺寸与其他 36dp 控件协调。
 *
 * 缩放只影响绘制，点击区域仍保持完整的 48dp，符合最小可点面积要求。
 */
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import top.mcly99.dl.theme.FlowDownColors

@Composable
fun CompactSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.graphicsLayer {
            scaleX = 0.5f
            scaleY = 0.5f
            transformOrigin = TransformOrigin(1f, 0.5f)
        },
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = FlowDownColors.OnPrimary,
            checkedTrackColor = FlowDownColors.Primary,
            checkedBorderColor = FlowDownColors.Primary,
            uncheckedThumbColor = FlowDownColors.MutedForeground,
            uncheckedTrackColor = FlowDownColors.Muted,
            uncheckedBorderColor = FlowDownColors.Border,
        ),
    )
}

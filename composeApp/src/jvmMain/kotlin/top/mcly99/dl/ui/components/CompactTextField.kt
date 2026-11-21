package top.mcly99.dl.ui.components

/**
 * 紧凑输入框：36dp 高、无边框填充底，聚焦时才显示 1dp 主色描边。
 *
 * Material3 的 TextField 最小高度是 56dp，压到 36dp 会裁掉文字，
 * 因此用 BasicTextField 自绘容器。搜索框与设置页输入框共用此样式。
 */
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import top.mcly99.dl.theme.FlowDownColors

@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    readOnly: Boolean = false,
    /**
     * 按下 **Ctrl+Enter** 时触发；为 `null` 时行为与加这个参数之前完全一致。
     *
     * 不用裸回车：输入法确认候选词通常也是回车，`onPreviewKeyEvent` 会先拿到事件，
     * 中文用户选词时就会误触发一次抓取（抓取中还会变成「取消」）。普通回车完全留给输入法，
     * 按钮仍是主入口。
     *
     * 也不用 `KeyboardActions(onDone)`：桌面端 `ImeAction.Done` 依赖输入法状态，
     * 中文输入法下经常收不到。
     */
    onSubmit: (() -> Unit)? = null,
    /**
     * 口令类输入：只影响显示，值仍是明文。
     * 备份口令不该在设置页上被旁人一眼看到。
     */
    masked: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(FlowDownColors.Muted)
            .border(1.dp, if (focused) FlowDownColors.Primary else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                leadingIcon,
                contentDescription = null,
                tint = FlowDownColors.MutedForeground,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onSubmit == null) {
                        Modifier
                    } else {
                        Modifier.onPreviewKeyEvent { event ->
                            val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                            // 只认 Ctrl+Enter：裸回车要留给输入法确认候选词
                            if (event.type == KeyEventType.KeyDown && isEnter && event.isCtrlPressed) {
                                onSubmit()
                                true
                            } else {
                                false
                            }
                        }
                    },
                ),
            singleLine = true,
            readOnly = readOnly,
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            interactionSource = interactionSource,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = FlowDownColors.Foreground),
            cursorBrush = SolidColor(FlowDownColors.Primary),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodySmall,
                            color = FlowDownColors.MutedForeground,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

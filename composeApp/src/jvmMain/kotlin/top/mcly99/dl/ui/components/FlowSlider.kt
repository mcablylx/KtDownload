package top.mcly99.dl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.mcly99.dl.theme.FlowDownColors

/**
 * 滑块的圆点拇指（14dp 实心圆，主色）。
 *
 * 为什么抽出来：Material3 的默认拇指是 **4×44dp 的竖条**，看起来像一根小棍；
 * 设置页的「速度限制」早期就把拇指换成了圆点，播放页的音量与进度条却一直用默认值，
 * 同一个应用里于是出现两种滑块观感。抽成组件后「样式一样」是结构性保证，
 * 而不是三处各复制一遍 `Box(...clip(CircleShape)...)`。
 *
 * 14dp 是设置页试出来的尺寸：再小在 88dp 宽的音量条上不好抓，再大会压住轨道两端。
 * 换成自定义拇指后，按下时没有 Material3 自带的光晕（halo）—— 设置页本来也没有，
 * 保持一致优先。
 */
@Composable
fun FlowSliderThumb(size: Dp = 14.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(FlowDownColors.Primary),
    )
}

/**
 * 带圆点拇指的滑块，参数与 [Slider] 基本一致。
 *
 * 存在的意义是「别忘了传 thumb」：直接写 [Slider] 时漏掉 `thumb` 就会静默退回默认竖条，
 * 而这种不一致在代码审查里很难看出来。
 *
 * @param inactiveTrackColor 未走过的轨道色**必须由调用方给**：它要跟着底色走 ——
 *   设置页在卡片上（`FlowDownColors.Muted`），播放页压在视频画面上（白色 25%）。
 *   强行统一会把播放页的进度条弄丢在黑背景里。
 *
 * 带 `thumb` 参数的 [Slider] 重载在 Material3 里仍是实验 API，所以这里要 OptIn ——
 * 设置页原来那处也带同样的注解。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    inactiveTrackColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(
            thumbColor = FlowDownColors.Primary,
            activeTrackColor = FlowDownColors.Primary,
            inactiveTrackColor = inactiveTrackColor,
        ),
        thumb = { FlowSliderThumb() },
    )
}

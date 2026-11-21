package top.mcly99.dl.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * 仅在顶部绘制一条边框线。
 *
 * Compose 内置的 `Modifier.border(...)` 只能对四条边统一加边框，
 * 无法满足“只画顶边/底边”的需求，因此提供该单边扩展。
 *
 * 使用 `drawBehind` 在内容背后绘制，线条沿组件顶边居中，
 * 不会额外改变组件尺寸，也不会遮挡内容。
 *
 * @param width 边框线宽
 * @param color 边框颜色
 */
fun Modifier.topBorder(width: Dp, color: Color): Modifier = drawBehind {
    val stroke = width.toPx()
    if (stroke > 0f) {
        drawLine(
            color = color,
            start = Offset(0f, stroke / 2f),
            end = Offset(size.width, stroke / 2f),
            strokeWidth = stroke,
        )
    }
}

/** 仅在底部绘制一条边框线，规则同 [topBorder]。 */
fun Modifier.bottomBorder(width: Dp, color: Color): Modifier = drawBehind {
    val stroke = width.toPx()
    if (stroke > 0f) {
        drawLine(
            color = color,
            start = Offset(0f, size.height - stroke / 2f),
            end = Offset(size.width, size.height - stroke / 2f),
            strokeWidth = stroke,
        )
    }
}

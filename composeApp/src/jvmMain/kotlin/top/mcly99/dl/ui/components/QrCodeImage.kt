package top.mcly99.dl.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import top.mcly99.dl.utils.QrCode

/**
 * 二维码画布：把 [QrCode] 生成的模块矩阵画成方块。
 *
 * 编码结果用 `remember(content)` 缓存，避免每帧重新计算 Reed-Solomon。
 *
 * @param content 要编码的文本（例如带令牌的上传地址）
 * @param modifier 尺寸修饰符（建议固定正方形）
 */
@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    foreground: Color = Color(0xFF16161A),
    background: Color = Color.White,
) {
    val matrix = remember(content) {
        if (content.isBlank()) null else QrCode.encode(content)
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(background, size = size)
            val m = matrix
            if (m == null) {
                // 内容过长或为空：画一个占位方框，避免 UI 塌陷
                drawRect(
                    color = Color.Gray.copy(alpha = 0.4f),
                    topLeft = Offset(2f, 2f),
                    size = Size(size.width - 4f, size.height - 4f),
                    style = Stroke(width = 2f),
                )
                return@Canvas
            }
            val modules = m.size
            // 四周留 4 模块静区（quiet zone），保证扫码成功率
            val quiet = 4
            val total = modules + quiet * 2
            val cell = size.minDimension / total
            val offset = (size.minDimension - cell * total) / 2f

            for (y in 0 until modules) {
                for (x in 0 until modules) {
                    if (!m[y][x]) continue
                    drawRect(
                        color = foreground,
                        topLeft = Offset(offset + (x + quiet) * cell, offset + (y + quiet) * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}

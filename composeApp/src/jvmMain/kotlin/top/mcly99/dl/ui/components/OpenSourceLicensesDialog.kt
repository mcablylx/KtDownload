package top.mcly99.dl.ui.components

/**
 * 「设置 → 开源许可」弹窗。
 *
 * 原先这个入口只做一件事：用系统文件管理器打开随包的 FFmpeg 许可目录。最终用户因此
 * 完全看不到 YunX-Desktop 的 AGPL-3.0 署名 —— 那是 `THIRD-PARTY-NOTICES.md` 里唯一还
 * 没勾的应用内署名项。现在改为在应用内直接列出所有第三方组件，并保留「打开许可目录」
 * 作为查看许可全文的入口。
 *
 * 组件清单来自 [top.mcly99.dl.theme.LicenseStrings]（纯数据，含中英双语），本文件只负责
 * 渲染与打开外部链接，不含许可判断逻辑。KtDownload 自有实现，无上游源码。
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.mcly99.dl.theme.FlowDownColors
import top.mcly99.dl.theme.LicenseEntry
import top.mcly99.dl.theme.LicenseStrings
import top.mcly99.dl.utils.AppLogger
import top.mcly99.dl.utils.FfmpegLocator
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files

@Composable
fun OpenSourceLicensesDialog(
    strings: LicenseStrings,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.title, color = FlowDownColors.Foreground) },
        text = {
            Column(
                Modifier
                    .widthIn(min = 460.dp)
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // AGPL 覆盖整个组合作品，这条警示必须排在组件清单之前
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = FlowDownColors.Warning.copy(alpha = 0.12f),
                    ),
                ) {
                    Text(
                        strings.warning,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = FlowDownColors.Foreground,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    strings.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = FlowDownColors.MutedForeground,
                )
                Spacer(Modifier.height(8.dp))
                strings.components.forEachIndexed { index, entry ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = FlowDownColors.MutedForeground.copy(alpha = 0.25f),
                        )
                    }
                    LicenseEntryRow(entry, strings)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::openLicenseFolder) { Text(strings.openFolder) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.close) }
        },
        containerColor = FlowDownColors.Card,
        titleContentColor = FlowDownColors.Foreground,
        textContentColor = FlowDownColors.MutedForeground,
    )
}

@Composable
private fun LicenseEntryRow(entry: LicenseEntry, strings: LicenseStrings) {
    // AGPL 条目用警示色，避免它淹没在三个宽松许可组件里
    val headline = if (entry.license.contains("AGPL")) FlowDownColors.Warning else FlowDownColors.Primary
    Column(Modifier.fillMaxWidth()) {
        Text(entry.name, style = MaterialTheme.typography.titleSmall, color = headline)
        Spacer(Modifier.height(4.dp))
        Text(
            "${strings.licenseLabel}: ${entry.license}",
            style = MaterialTheme.typography.bodySmall,
            color = FlowDownColors.Foreground,
        )
        Text(
            "${strings.integrationLabel}: ${entry.integration}",
            style = MaterialTheme.typography.bodySmall,
            color = FlowDownColors.MutedForeground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            entry.notes,
            style = MaterialTheme.typography.labelSmall,
            color = FlowDownColors.MutedForeground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            entry.sourceUrl,
            style = MaterialTheme.typography.labelSmall,
            color = headline,
        )
        Text(
            strings.openUpstream,
            modifier = Modifier.clickable { openUpstream(entry.sourceUrl) },
            style = MaterialTheme.typography.labelSmall,
            color = FlowDownColors.Primary,
        )
    }
}

private fun openUpstream(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
        .onFailure { AppLogger.warn("OpenSourceLicenses", "打开上游源码失败: ${it.message}", it) }
}

/**
 * 打开随包分发的许可目录。
 *
 * `FfmpegLocator.licenseDirectory()` 指向 `<resources>/ffmpeg`（打包后）或
 * `resources/common/ffmpeg`（开发环境）；它的父目录同时包含 `vlc/` 与
 * `yunx-desktop/`，因此直接打开父目录就能一次看到全部许可全文。
 */
private fun openLicenseFolder() {
    runCatching {
        val ffmpegDir = FfmpegLocator.licenseDirectory()
        val root = ffmpegDir?.parent?.takeIf { Files.isDirectory(it) } ?: ffmpegDir
        if (root != null) {
            Desktop.getDesktop().open(root.toFile())
        } else {
            // 既没有打包资源也没有仓库目录（例如从 jar 直接启动）：至少给出上游许可页
            Desktop.getDesktop().browse(URI("https://ffmpeg.org/legal.html"))
        }
    }.onFailure { AppLogger.warn("OpenSourceLicenses", "打开许可目录失败: ${it.message}", it) }
}

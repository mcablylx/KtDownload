package top.mcly99.dl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.mcly99.dl.engine.web.MediaCandidate
import top.mcly99.dl.engine.web.MediaKind
import top.mcly99.dl.theme.FlowDownColors
import top.mcly99.dl.theme.FlowDownTheme
import top.mcly99.dl.theme.Strings
import top.mcly99.dl.utils.formatBytes
import top.mcly99.dl.viewmodel.CatchWebNotice
import top.mcly99.dl.viewmodel.CatchWebState
import top.mcly99.dl.viewmodel.FailureReason

/**
 * 「抓网页」结果区。
 *
 * 输入框与抓取按钮在 [HeaderBar] 里，这里只负责展示：能力边界提示 + 状态 + 结果列表。
 * 这样输入区天然固定在顶部，不随列表滚动。
 *
 * @param addedUrls 已加入下载的地址，对应行的按钮变「已加入」
 * @param sizes 地址 → 文件字节数；「探测文件大小」关闭时为空，行内就不显示大小
 * @param onPlay 在线观看；只对 [MediaKind.canPlay] 的类型给按钮
 * @param onRemove 移除这一条候选；只影响列表，不影响已加入的下载任务
 */
@Composable
fun CatchWebScreen(
    state: CatchWebState,
    addedUrls: Set<String>,
    sizes: Map<String, Long>,
    notice: CatchWebNotice?,
    onDownload: (MediaCandidate) -> Unit,
    onPlay: (MediaCandidate) -> Unit,
    onRemove: (MediaCandidate) -> Unit,
    onDownloadAll: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = Strings.current
    Column(modifier.fillMaxSize()) {
        // 能力边界必须写在正文首行：不写清楚，用户会把「抓不到」当成 bug
        Text(
            s.catchWebHint,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelSmall,
            color = FlowDownColors.MutedForeground,
        )
        // 深度抓取降级提示：不阻断结果，但必须让用户知道「少抓了一层」
        if (notice != null) {
            Text(
                noticeText(notice),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = FlowDownColors.Warning,
            )
        }

        when (state) {
            CatchWebState.Idle -> Placeholder(
                icon = Icons.Default.TravelExplore,
                title = s.catchWebIdle,
                hint = s.catchWebIdleHint,
            )

            CatchWebState.Loading -> Loading(onCancel)

            CatchWebState.Empty -> Placeholder(
                icon = Icons.Default.Search,
                title = s.catchWebEmpty,
                hint = s.catchWebEmptyHint,
            )

            is CatchWebState.Failed -> Placeholder(
                icon = Icons.Default.ErrorOutline,
                title = failureText(state),
                hint = failureHint(state),
                tint = FlowDownColors.Destructive,
            )

            is CatchWebState.Success -> Column(Modifier.fillMaxSize()) {
                Row(
                    // 右边距与 CandidateRow 一致（8dp）：行尾多了 36dp 的移除按钮，
                    // 图标本身带内边距，8dp 才是视觉上对齐的那一档
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        s.catchWebFound(state.results.size),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = FlowDownColors.MutedForeground,
                    )
                    // 只有一条时这个按钮没有意义，藏在「结果 > 1」后面
                    if (state.results.size > 1) {
                        CompactButton(
                            text = s.catchWebDownloadAll,
                            onClick = onDownloadAll,
                            // 与顶栏「添加任务」/ 行内「下载」同一套样式，不另做小号 TextButton
                            icon = Icons.Default.Download,
                            enabled = state.results.any { it.url !in addedUrls },
                        )
                    }
                }
                // LazyColumn + key：长列表（上限 200 条）下 Column + forEach 会明显卡顿
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(state.results, key = { _, candidate -> candidate.url }) { index, candidate ->
                        CandidateRow(
                            candidate = candidate,
                            // 名称兜底放这里而不是提取器：序号要按展示顺序编
                            displayName = candidate.name.ifBlank { s.catchWebMediaName(index + 1) },
                            added = candidate.url in addedUrls,
                            size = sizes[candidate.url],
                            onDownload = onDownload,
                            onPlay = onPlay,
                            onRemove = onRemove,
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(FlowDownColors.Border),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Loading(onCancel: () -> Unit) {
    val s = Strings.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = FlowDownColors.Primary,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                s.catchWebBusy,
                style = MaterialTheme.typography.bodyMedium,
                color = FlowDownColors.MutedForeground,
            )
            Spacer(Modifier.height(12.dp))
            CompactButton(text = s.catchWebCancel, onClick = onCancel)
        }
    }
}

@Composable
private fun Placeholder(
    icon: ImageVector,
    title: String,
    hint: String?,
    tint: Color = FlowDownColors.MutedForeground,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = tint)
            if (hint != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    hint,
                    modifier = Modifier.padding(horizontal = 48.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = FlowDownColors.MutedForeground,
                )
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: MediaCandidate,
    displayName: String,
    added: Boolean,
    size: Long?,
    onDownload: (MediaCandidate) -> Unit,
    onPlay: (MediaCandidate) -> Unit,
    onRemove: (MediaCandidate) -> Unit,
) {
    val s = Strings.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindChip(candidate.kind)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = FlowDownColors.Foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 地址允许选中复制：有些站点需要把地址贴到别处才能用
            SelectionContainer {
                Text(
                    candidate.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = FlowDownColors.MutedForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // 大小只在探测成功时出现；探不到就不显示，比显示「未知」干净
        if (size != null && size > 0L) {
            Text(
                formatBytes(size),
                style = MaterialTheme.typography.labelSmall,
                color = FlowDownColors.MutedForeground,
            )
            Spacer(Modifier.width(12.dp))
        }
        // 播放按钮只给 m3u8 / mp4：判定收在 MediaKind.canPlay 一处，
        // 将来放开 flv/mkv 只改枚举，不动这里
        if (candidate.kind.canPlay) {
            CompactButton(
                text = s.catchWebPlay,
                onClick = { onPlay(candidate) },
                icon = Icons.Default.PlayArrow,
            )
            Spacer(Modifier.width(8.dp))
        }
        CompactButton(
            text = if (added) s.catchWebAdded else s.catchWebDownload,
            onClick = { onDownload(candidate) },
            enabled = !added,
        )
        // 移除候选：一个页面常有几十条（广告位、预览片），不能逐条删就只能整页重抓（v6-P2-2）。
        // 语义是「移除候选」而不是「取消任务」，所以用图标 + 「移除」的 contentDescription
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { onRemove(candidate) }, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = s.catchWebRemove,
                tint = FlowDownColors.MutedForeground,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun KindChip(kind: MediaKind) {
    val (label, tint) = when (kind) {
        MediaKind.M3u8 -> "M3U8" to FlowDownColors.Primary
        MediaKind.Mp4 -> "MP4" to FlowDownColors.Success
        MediaKind.Other -> "FILE" to FlowDownColors.MutedForeground
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** 深度抓取降级时的一句话说明；正常状态不显示任何东西 */
@Composable
private fun noticeText(notice: CatchWebNotice): String {
    val s = Strings.current
    return when (notice) {
        CatchWebNotice.NoBrowser -> s.deepCatchBrowserMissing
        CatchWebNotice.DeepCatchFailed -> s.deepCatchFailed
    }
}

/** 每种失败给不同文案：用户要知道是自己输错了、站点不给抓，还是网络不通 */
@Composable
private fun failureText(state: CatchWebState.Failed): String {
    val s = Strings.current
    val code = state.httpCode ?: 0
    return when (state.reason) {
        FailureReason.InvalidUrl -> s.catchWebErrInvalidUrl
        FailureReason.NotHtml -> s.catchWebErrNotHtml
        FailureReason.NetworkError -> s.catchWebErrNetwork
        // 5xx 是站点自己挂了（522 = Cloudflare 连不上源站），说成「抓不到」会让人以为是自己网络的问题
        FailureReason.HttpError -> if (code >= 500) s.catchWebErrHttpServer(code) else s.catchWebErrHttp(code)
        FailureReason.Unknown -> s.catchWebErrUnknown
    }
}

/** 失败页下面的建议：只有「不知道原因」和 5xx 才值得给一句下一步怎么做 */
@Composable
private fun failureHint(state: CatchWebState.Failed): String? {
    val s = Strings.current
    return when {
        state.reason == FailureReason.Unknown -> state.detail
        state.reason == FailureReason.HttpError && (state.httpCode ?: 0) >= 500 -> s.catchWebErrHttpServerHint
        else -> null
    }
}

// ---------------------------------------------------------------------- 预览

private val previewResults = listOf(
    MediaCandidate(
        url = "https://cdn.example.com/hls/ep03/index.m3u8",
        kind = MediaKind.M3u8,
        name = "第 3 集",
        source = MediaCandidate.Source.Attribute,
    ),
    MediaCandidate(
        url = "https://cdn.example.com/v/trailer.mp4",
        kind = MediaKind.Mp4,
        name = "预告片",
        source = MediaCandidate.Source.InlineScript,
    ),
    MediaCandidate(
        url = "https://cdn.example.com/v/extra.mkv",
        kind = MediaKind.Other,
        name = "",
        source = MediaCandidate.Source.RelativePath,
    ),
)

@Preview
@Composable
private fun PreviewCatchWebSuccess() {
    FlowDownTheme {
        Box(Modifier.size(900.dp, 420.dp)) {
            CatchWebScreen(
                state = CatchWebState.Success("https://example.com/video/123", previewResults),
                addedUrls = setOf("https://cdn.example.com/v/trailer.mp4"),
                sizes = mapOf("https://cdn.example.com/v/trailer.mp4" to 48_234_496L),
                notice = null,
                onDownload = {},
                onPlay = {},
                onRemove = {},
                onDownloadAll = {},
                onCancel = {},
            )
        }
    }
}

@Preview
@Composable
private fun PreviewCatchWebStates() {
    FlowDownTheme {
        Row(Modifier.size(900.dp, 300.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { CatchWebScreen(CatchWebState.Idle, emptySet(), emptyMap(), null, {}, {}, {}, {}, {}) }
            Box(Modifier.weight(1f)) { CatchWebScreen(CatchWebState.Loading, emptySet(), emptyMap(), null, {}, {}, {}, {}, {}) }
            Box(Modifier.weight(1f)) {
                CatchWebScreen(
                    CatchWebState.Failed(FailureReason.HttpError, httpCode = 403),
                    emptySet(),
                    emptyMap(),
                    null,
                    {},
                    {},
                    {},
                    {},
                    {},
                )
            }
        }
    }
}

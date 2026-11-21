package top.mcly99.dl.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.mcly99.dl.engine.player.PlaybackError
import top.mcly99.dl.engine.player.PlaybackPhase
import top.mcly99.dl.engine.player.PlaybackState
import top.mcly99.dl.engine.player.QualityOption
import top.mcly99.dl.theme.FlowDownColors
import top.mcly99.dl.theme.FlowDownTheme
import top.mcly99.dl.theme.Strings
import top.mcly99.dl.theme.UiStrings
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 播放页：整窗覆盖在主界面之上，Esc 或左上角返回。
 *
 * 之所以不做成独立窗口：用户要的是「点一下就看看能不能播」，
 * 独立窗口会多出一个任务栏条目，还要处理窗口关闭与播放状态的一致性。
 *
 * @param qualities 可选清晰度；空列表表示不给下拉（不是主播放列表 / 只有一档 / 探测失败）
 * @param selectedQualityUrl 当前档位地址；null 表示「自动」（让 libvlc 按带宽挑）
 * @param onViewportChange 播放区域像素尺寸；引擎按它降采样，避免每帧转换原分辨率
 */
@Composable
fun PlayerScreen(
    state: PlaybackState,
    frame: ImageBitmap?,
    onClose: () -> Unit,
    onTogglePause: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    qualities: List<QualityOption> = emptyList(),
    selectedQualityUrl: String? = null,
    onSelectQuality: (QualityOption) -> Unit = {},
    onSelectAutoQuality: () -> Unit = {},
    onRetry: () -> Unit = {},
    onVolumeChange: (Int) -> Unit = {},
    onToggleMute: () -> Unit = {},
    onViewportChange: (Int, Int) -> Unit = { _, _ -> },
) {
    val s = Strings.current
    val focusRequester = remember { FocusRequester() }

    // 播放页一出现就抢焦点，否则 Esc 收不到（焦点还在下面的列表上）
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            // 窗口大小变化时告诉引擎，帧按实际显示尺寸降采样
            .onSizeChanged { onViewportChange(it.width, it.height) }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onClose()
                    true
                } else {
                    false
                }
            },
    ) {
        // ---- 画面 ----
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else if (state.phase != PlaybackPhase.Failed) {
            // 还没有第一帧：m3u8 起播要拉播放列表 + 首个分片，没有 loading 态会像卡死
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = FlowDownColors.Primary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    s.playerOpening,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }

        // ---- 失败 ----
        if (state.phase == PlaybackPhase.Failed) {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    errorTitle(s, state.error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlowDownColors.Destructive,
                )
                errorHint(s, state.error)?.let { hint ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    state.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.5f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 「重试」只在这条路走得通时才给：会话注入不了是内核能力缺失，点一百次也一样，
                // 摆一个按不出结果的按钮只会让人以为是偶发失败
                if (state.error != PlaybackError.SessionRequired) {
                    Spacer(Modifier.height(16.dp))
                    CompactButton(
                        text = s.playerRetry,
                        onClick = onRetry,
                        icon = Icons.Default.Refresh,
                        primary = true,
                    )
                }
            }
        }

        // ---- 顶部：返回 + 标题 + 直播标记 ----
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = s.playerBack, tint = Color.White)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                state.title.ifBlank { state.url },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 清晰度下拉：只有拉到主播放列表且不止一档时才出现
            if (qualities.isNotEmpty()) {
                QualityMenu(
                    qualities = qualities,
                    selectedUrl = selectedQualityUrl,
                    onSelect = onSelectQuality,
                    onSelectAuto = onSelectAutoQuality,
                )
                Spacer(Modifier.width(8.dp))
            }
            if (state.isLive) {
                LiveChip(s.playerLive)
                Spacer(Modifier.width(8.dp))
            }
        }

        // ---- 底部控制条 ----
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (state.phase == PlaybackPhase.Opening && state.lengthMs <= 0L) {
                // 起播前不知道是点播还是直播：给不确定态进度条，而不是假装 0%
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = FlowDownColors.Primary,
                    trackColor = Color.White.copy(alpha = 0.15f),
                )
            } else if (state.isLive) {
                if (state.buffering) {
                    Text(
                        s.playerBuffering,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                } else {
                    Spacer(Modifier.height(2.dp))
                }
            } else {
                SeekBar(state = state, onSeek = onSeek)
            }

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when (state.phase) {
                    PlaybackPhase.Playing -> Icons.Default.Pause
                    PlaybackPhase.Ended -> Icons.Default.Replay
                    else -> Icons.Default.PlayArrow
                }
                // 无障碍文案必须跟着图标走：常量「暂停」会让读屏在播放态与重播态都说反
                val iconLabel = when (state.phase) {
                    PlaybackPhase.Playing -> s.playerPause
                    PlaybackPhase.Ended -> s.playerReplay
                    else -> s.playerPlay
                }
                IconButton(
                    onClick = onTogglePause,
                    modifier = Modifier.size(36.dp),
                    enabled = state.phase != PlaybackPhase.Opening,
                ) {
                    Icon(icon, contentDescription = iconLabel, tint = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    when (state.phase) {
                        PlaybackPhase.Ended -> s.playerEnded
                        PlaybackPhase.Opening -> s.playerOpening
                        PlaybackPhase.Paused -> s.playerPause
                        PlaybackPhase.Playing -> s.playerPlay
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
                Spacer(Modifier.weight(1f))
                if (state.buffering && state.phase == PlaybackPhase.Playing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = Color.White.copy(alpha = 0.7f),
                        strokeWidth = 1.5.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (state.phase.isActive) {
                    val silent = state.muted || state.volume == 0
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            if (silent) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = if (silent) s.playerUnmute else s.playerMute,
                            tint = Color.White,
                        )
                    }
                    FlowSlider(
                        value = state.volume / 100f,
                        onValueChange = { onVolumeChange((it * 100).roundToInt()) },
                        modifier = Modifier.width(88.dp),
                        // 轨道压在白字与视频画面上：用半透明白，主色的 Muted 在这里几乎看不见
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                    )
                }
            }
        }
    }
}

/**
 * 失败标题：引擎层只给类型，文案在这里映射成当前语言。
 *
 * 这样英文界面不会出现「Cannot play: 播放器报错」这种中英混排。
 */
private fun errorTitle(s: UiStrings, error: PlaybackError?): String = when (error) {
    PlaybackError.EngineUnavailable -> s.playerFailed(s.playerErrEngine)
    PlaybackError.MediaError -> s.playerFailed(s.playerErrMedia)
    PlaybackError.SessionRequired -> s.playerFailed(s.playerErrSession)
    is PlaybackError.Unknown -> s.playerFailed(error.detail.orEmpty())
    null -> s.playerFailed("")
}

/** 失败时给下一步建议；能自己恢复的错误（如网络抖动）由「重试」按钮解决，不再啰嗦 */
private fun errorHint(s: UiStrings, error: PlaybackError?): String? = when (error) {
    PlaybackError.EngineUnavailable -> s.playerErrEngineHint
    PlaybackError.MediaError -> s.playerErrMediaHint
    // 这条恰恰是「重试没用」的那一种，必须给出可执行的下一步
    PlaybackError.SessionRequired -> s.playerErrSessionHint
    else -> null
}

@Composable
private fun SeekBar(state: PlaybackState, onSeek: (Long) -> Unit) {
    // 拖动期间用本地值：直接跟 timeMs 会让滑块在手指下被播放进度拽回去
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val progress = if (dragging) dragValue else state.progress

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatClock(if (dragging) (dragValue * state.lengthMs).toLong() else state.timeMs),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f),
        )
        FlowSlider(
            value = progress,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                dragging = false
                onSeek((dragValue * state.lengthMs).toLong())
            },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            enabled = state.canSeek,
            inactiveTrackColor = Color.White.copy(alpha = 0.25f),
        )
        Text(
            formatClock(state.lengthMs),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f),
        )
    }
}

/**
 * 清晰度下拉。
 *
 * 第一项固定是「自动」—— 即交回原始主列表地址，由 libvlc 的 adaptive 模块按带宽自选。
 * 用户切到某一档之后想回到自动，得有这个入口，否则只能退出重进。
 */
@Composable
private fun QualityMenu(
    qualities: List<QualityOption>,
    selectedUrl: String?,
    onSelect: (QualityOption) -> Unit,
    onSelectAuto: () -> Unit,
) {
    val s = Strings.current
    var open by remember { mutableStateOf(false) }
    val current = qualities.firstOrNull { it.url == selectedUrl }?.label ?: s.playerQualityAuto

    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.14f))
                .clickable { open = true }
                .padding(start = 8.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current, style = MaterialTheme.typography.labelSmall, color = Color.White)
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = s.playerQuality,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            QualityItem(
                label = s.playerQualityAuto,
                detail = null,
                selected = selectedUrl == null,
                onClick = {
                    open = false
                    onSelectAuto()
                },
            )
            qualities.forEach { option ->
                QualityItem(
                    label = option.label,
                    detail = option.detail,
                    selected = option.url == selectedUrl,
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun QualityItem(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        onClick = onClick,
        text = {
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) FlowDownColors.Primary else FlowDownColors.Foreground,
                )
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = FlowDownColors.MutedForeground,
                    )
                }
            }
        },
        trailingIcon = if (selected) {
            {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = FlowDownColors.Primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            null
        },
    )
}

@Composable
private fun LiveChip(label: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(FlowDownColors.Destructive.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

/**
 * 毫秒 → `0:12` / `1:02:03`。
 *
 * 不用 [top.mcly99.dl.utils.formatDuration]：那个给下载剩余时间用，输出「3 分 5 秒」，
 * 放在进度条两侧太长。
 */
internal fun formatClock(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L)) / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    // Locale.ROOT：阿拉伯语等区域的默认 Locale 会把数字换成当地数字，进度条两侧就对不上
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

// ---------------------------------------------------------------------- 预览

@Preview
@Composable
private fun PreviewPlayerVod() {
    FlowDownTheme {
        Box(Modifier.size(900.dp, 520.dp)) {
            PlayerScreen(
                state = PlaybackState(
                    phase = PlaybackPhase.Playing,
                    title = "第 3 集",
                    url = "https://cdn.example.com/hls/ep03/index.m3u8",
                    timeMs = 65_000,
                    lengthMs = 1_440_000,
                ),
                frame = null,
                onClose = {},
                onTogglePause = {},
                onSeek = {},
            )
        }
    }
}

@Preview
@Composable
private fun PreviewPlayerLive() {
    FlowDownTheme {
        Row(Modifier.size(900.dp, 300.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                PlayerScreen(
                    state = PlaybackState(
                        phase = PlaybackPhase.Playing,
                        title = "直播频道",
                        url = "https://cdn.example.com/live/index.m3u8",
                        timeMs = 0,
                        lengthMs = 0,
                    ),
                    frame = null,
                    onClose = {},
                    onTogglePause = {},
                    onSeek = {},
                )
            }
            Box(Modifier.weight(1f)) {
                PlayerScreen(
                    state = PlaybackState(
                        phase = PlaybackPhase.Failed,
                        url = "https://cdn.example.com/a.mp4",
                        error = PlaybackError.EngineUnavailable,
                    ),
                    frame = null,
                    onClose = {},
                    onTogglePause = {},
                    onSeek = {},
                )
            }
        }
    }
}

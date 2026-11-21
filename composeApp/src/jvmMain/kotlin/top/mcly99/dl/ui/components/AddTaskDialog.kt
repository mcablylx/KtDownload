package top.mcly99.dl.ui.components

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.mcly99.dl.engine.previewFileName
import top.mcly99.dl.model.TaskType
import top.mcly99.dl.theme.FlowDownColors
import top.mcly99.dl.theme.FlowDownTheme
import top.mcly99.dl.theme.Strings
import top.mcly99.dl.utils.NativeFileDialog
import top.mcly99.dl.utils.detectUrlType
import top.mcly99.dl.utils.getClipboardUrl
import top.mcly99.dl.utils.isSupportedDownloadUrl

@Composable
fun AddTaskDialog(
    open: Boolean,
    onDismiss: () -> Unit,
    onAdd: (urls: List<String>, name: String?, savePath: String, threads: Int) -> Unit,
    defaultSavePath: String = "/Downloads",
    defaultThreads: Int = 8,
) {
    if (!open) return

    val s = Strings.current

    var urls by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    // 用户手动改过文件名之后就不再跟随链接自动填充（v6-P2-1）
    var nameTouched by remember { mutableStateOf(false) }
    var savePath by remember { mutableStateOf(defaultSavePath) }
    var threads by remember { mutableStateOf(defaultThreads.toString()) }
    val urlList = remember(urls) { urls.lines().map { it.trim() }.filter { it.isNotEmpty() } }
    // 与引擎保持一致：HTTP / HTTPS 直链与 m3u8 可下载，BT 在 UI 层就拦截
    val supportedUrls = remember(urlList) { urlList.filter { isSupportedDownloadUrl(it) } }
    val unsupportedCount = urlList.size - supportedUrls.size
    val detected: TaskType? = urlList.firstOrNull()?.let { detectUrlType(it) }
    val hasM3u8 = remember(urlList) { urlList.any { detectUrlType(it) == TaskType.M3u8 } }
    // 多链接时一个名字会套到所有任务上（引擎只能加序号区分），不如让用户按地址命名
    val multiLink = supportedUrls.size > 1

    // 文件名跟随链接：让用户在点「添加」之前就能看到会存成什么名字。
    // 推导复用引擎的 previewFileName，避免 UI 与引擎两份规则分叉（v6-P2-1）。
    // 只以链接为 key：用户把文件名清空后不应被立刻填回去。
    LaunchedEffect(supportedUrls) {
        when {
            multiLink -> {
                name = ""
                nameTouched = false
            }
            !nameTouched -> name = supportedUrls.firstOrNull()?.let(::previewFileName).orEmpty()
        }
    }

    LaunchedEffect(open) {
        // 复用 Clipboard.kt 的唯一实现（v4-P3-6）：原先这里内联重写了一遍剪贴板读取，
        // 判定条件还与 getClipboardUrl() 不一致（少了「URL 必须带 path」的校验），
        // 异常也被空 catch 静默吞掉（v4-P3-10）。
        if (open && urls.isBlank()) {
            getClipboardUrl()?.let { urls = it }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    onAdd(supportedUrls, name.takeIf { it.isNotBlank() }, savePath, threads.toIntOrNull() ?: 8)
                    urls = ""
                    name = ""
                    nameTouched = false
                    onDismiss()
                },
                enabled = supportedUrls.isNotEmpty(),
            ) {
                Text(if (supportedUrls.isEmpty()) s.addDownloadTask else s.addTasks(supportedUrls.size))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(s.dialogCancel) }
        },
        title = { Text(s.addDownloadTask) },
        text = {
            Column(Modifier.widthIn(max = 500.dp)) {
                Text(s.downloadLinkHint, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
                Spacer(Modifier.height(16.dp))
                Text(s.downloadLink, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.Foreground)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = urls,
                    onValueChange = { urls = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    placeholder = { Text(s.linkPlaceholder) },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = FlowDownColors.Background,
                        focusedContainerColor = FlowDownColors.Background,
                        unfocusedTextColor = FlowDownColors.Foreground,
                        focusedTextColor = FlowDownColors.Foreground,
                    ),
                )
                if (urlList.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = when (detected) {
                                TaskType.Bt -> Icons.Default.Link
                                TaskType.M3u8 -> Icons.Default.Movie
                                else -> Icons.Default.Public
                            },
                            contentDescription = null,
                            tint = when (detected) {
                                TaskType.Bt -> FlowDownColors.Orange
                                TaskType.M3u8 -> FlowDownColors.Purple
                                else -> FlowDownColors.Blue
                            },
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            s.linkCount(urlList.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = FlowDownColors.MutedForeground,
                        )
                    }
                    if (unsupportedCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            s.unsupportedLinks(unsupportedCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = FlowDownColors.Orange,
                        )
                    }
                    if (hasM3u8) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            s.hlsHighestQualityHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = FlowDownColors.Purple,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(s.fileNameOptional, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        // 改过就锁住，之后换链接也不再覆盖用户输入
                        nameTouched = it.isNotBlank()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(s.fileNamePlaceholder) },
                    singleLine = true,
                    // 多链接时禁填：一个名字套 N 个任务只会让引擎加序号，用户还以为是 bug
                    enabled = !multiLink,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = FlowDownColors.Background,
                        focusedContainerColor = FlowDownColors.Background,
                        unfocusedTextColor = FlowDownColors.Foreground,
                        focusedTextColor = FlowDownColors.Foreground,
                    ),
                )
                if (multiLink) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        s.fileNameFromLinks,
                        style = MaterialTheme.typography.labelSmall,
                        color = FlowDownColors.MutedForeground,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(s.savePath, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = savePath,
                                onValueChange = { savePath = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedContainerColor = FlowDownColors.Background,
                                    focusedContainerColor = FlowDownColors.Background,
                                    unfocusedTextColor = FlowDownColors.Foreground,
                                    focusedTextColor = FlowDownColors.Foreground,
                                ),
                            )
                            IconButton(onClick = {
                                NativeFileDialog.chooseDirectory()?.let { savePath = it }
                            }) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null)
                            }
                        }
                    }
                    Spacer(Modifier.size(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.threadCountField, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        ThreadsDropdown(threads) { threads = it }
                    }
                }
            }
        },
    )
}

@Composable
private fun ThreadsDropdown(value: String, onSelect: (String) -> Unit) {
    val s = Strings.current
    var menuOpen by remember { mutableStateOf(false) }
    val options = listOf("1", "2", "4", "8", "16", "32")
    Box {
        OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text(s.threadsLabel(value.toIntOrNull() ?: 0))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            options.forEach { v ->
                DropdownMenuItem(
                    text = { Text(s.threadsLabel(v.toIntOrNull() ?: 0)) },
                    onClick = {
                        onSelect(v)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

/** 预览：添加任务对话框（约 520×560） */
@Preview
@Composable
private fun PreviewAddTaskDialog() {
    FlowDownTheme {
        Box(
            Modifier
                .size(520.dp, 560.dp)
                .background(Color(0x66000000)),
        ) {
            AddTaskDialog(
                open = true,
                onDismiss = {},
                onAdd = { _, _, _, _ -> },
            )
        }
    }
}

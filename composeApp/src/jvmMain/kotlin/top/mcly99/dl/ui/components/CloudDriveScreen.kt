package top.mcly99.dl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.mcly99.dl.data.network.GitHubMirror
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.engine.clouddrive.CloudDriveCatalog
import top.mcly99.dl.engine.clouddrive.GitHubMirrorChoice
import top.mcly99.dl.engine.clouddrive.CloudDrivePlatformInfo
import top.mcly99.dl.engine.clouddrive.CloudFile
import top.mcly99.dl.engine.clouddrive.CloudLink
import top.mcly99.dl.engine.clouddrive.CloudLinkEntry
import top.mcly99.dl.engine.clouddrive.CloudQuota
import top.mcly99.dl.theme.FlowDownColors
import top.mcly99.dl.theme.MultiAccountStrings
import top.mcly99.dl.theme.Strings
import top.mcly99.dl.utils.formatBytes
import top.mcly99.dl.viewmodel.CloudDriveState
import top.mcly99.dl.viewmodel.CloudDriveViewModel
import top.mcly99.dl.viewmodel.CloudFileSort
import top.mcly99.dl.viewmodel.CloudResolution
import top.mcly99.dl.viewmodel.LinkPicker

/**
 * 「云盘」正文：顶栏输入框之外的一切都在这里。
 *
 * 区域栏固定分两块，对应 YunX-Desktop 的两个标签页：
 * - 上半「解析结果」：分享信息 + 文件树 + 已取到的直链，标题栏带下载图标（对齐解析页）；
 * - 下半「网盘列表」：**每个网盘一张账号卡片**（对齐「网盘」标签页的 DriveScreen），
 *   可逐平台配置凭证、看登录态与空间配额、打开官网、切换卡片/列表、刷新配额。
 * 两块各自独立滚动，解析失败只影响上半块。
 */
@Composable
fun CloudDriveScreen(
    vm: CloudDriveViewModel,
    modifier: Modifier = Modifier,
) {
    val s = Strings.current
    // 与上游网盘页一致：进入页面时刷新已配置平台的空间配额
    LaunchedEffect(Unit) { vm.refreshQuotas() }

    Column(modifier.fillMaxSize()) {
        Text(
            s.cloudDriveStrings.hint,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelSmall,
            color = FlowDownColors.MutedForeground,
        )
        vm.notice?.let { message ->
            Text(
                message,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = FlowDownColors.Warning,
            )
        }

        // ① 解析结果
        Section(
            title = s.cloudDriveStrings.resultTitle,
            modifier = Modifier.weight(1.25f),
            trailing = {
                // D-29：解析成功后可把当前链接收进「链接收藏」，下次从顶栏星标入口复用
                if (vm.state is CloudDriveState.Success) {
                    val marked = vm.isCurrentBookmarked()
                    IconButton(
                        onClick = vm::toggleCurrentBookmark,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.Default.Bookmark,
                            contentDescription = if (marked) {
                                s.cloudDriveStrings.linkBookmarkRemove
                            } else {
                                s.cloudDriveStrings.linkBookmarkAdd
                            },
                            tint = if (marked) FlowDownColors.Primary else FlowDownColors.MutedForeground,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                // 有结果时才给「清空」；之前 links 只增不减，也没有回到初始态的入口
                if (vm.state !is CloudDriveState.Idle) {
                    CompactButton(
                        text = s.cloudDriveStrings.clearResults,
                        onClick = vm::clearResults,
                        enabled = !vm.busy,
                        destructive = true,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                // 标题栏的下载图标：把当前目录里所有文件批量取链并送入下载队列
                IconButton(
                    onClick = vm::downloadAll,
                    enabled = !vm.busy && vm.files.any { !it.isDir },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = s.cloudDriveStrings.resultDownload,
                        tint = if (vm.busy) FlowDownColors.MutedForeground else FlowDownColors.Primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        ) {
            ResultPane(vm)
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(FlowDownColors.Border))

        // ② 网盘列表（每个网盘）
        Section(
            title = s.cloudDriveStrings.filesTitle,
            modifier = Modifier.weight(1f),
            trailing = {
                IconButton(
                    onClick = vm::toggleGridMode,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Default.GridView,
                        contentDescription = s.cloudDriveStrings.accountGrid,
                        tint = FlowDownColors.MutedForeground,
                        modifier = Modifier.size(17.dp),
                    )
                }
                IconButton(
                    onClick = vm::refreshQuotas,
                    enabled = !vm.quotaLoading,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = s.cloudDriveStrings.accountRefresh,
                        tint = if (vm.quotaLoading) FlowDownColors.MutedForeground else FlowDownColors.Primary,
                        modifier = Modifier.size(17.dp),
                    )
                }
            },
        ) {
            DrivePane(vm)
        }
    }

    // 顶栏「历史 / 收藏」入口共用的选择弹窗；状态由 ViewModel 持有，切屏不会丢
    vm.linkPicker?.let { picker -> LinkPickerDialog(vm, picker) }
}

/** D-29：最近解析 / 收藏链接选择弹窗，点击条目即回填输入框并重新解析 */
@Composable
private fun LinkPickerDialog(vm: CloudDriveViewModel, picker: LinkPicker) {
    val s = Strings.current.cloudDriveStrings
    val isHistory = picker == LinkPicker.HISTORY
    val entries = if (isHistory) vm.linkHistory else vm.bookmarks
    AlertDialog(
        onDismissRequest = vm::closeLinkPicker,
        title = {
            Text(
                if (isHistory) s.linkHistory else s.linkBookmarks,
                color = FlowDownColors.Foreground,
            )
        },
        text = {
            Column(Modifier.widthIn(min = 420.dp)) {
                Text(
                    s.linkLibraryHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = FlowDownColors.MutedForeground,
                )
                Spacer(Modifier.height(8.dp))
                if (entries.isEmpty()) {
                    Text(
                        s.linkLibraryEmpty,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FlowDownColors.MutedForeground,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(entries, key = { it.url }) { entry ->
                            LinkLibraryRow(
                                entry = entry,
                                removeLabel = s.linkLibraryRemove,
                                onReuse = { vm.reuseEntry(entry) },
                                onRemove = {
                                    if (isHistory) vm.removeHistoryEntry(entry)
                                    else vm.removeBookmarkEntry(entry)
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (isHistory) vm.clearLinkHistory() else vm.clearAllBookmarks() },
                enabled = entries.isNotEmpty(),
            ) {
                Text(s.linkLibraryClear, color = FlowDownColors.Warning)
            }
        },
        dismissButton = {
            TextButton(onClick = vm::closeLinkPicker) { Text(s.linkLibraryClose) }
        },
    )
}

@Composable
private fun LinkLibraryRow(
    entry: CloudLinkEntry,
    removeLabel: String,
    onReuse: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onReuse)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = FlowDownColors.Foreground,
            )
            Text(
                entry.url,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = FlowDownColors.MutedForeground,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            timeLabel(entry.savedAt),
            style = MaterialTheme.typography.labelSmall,
            color = FlowDownColors.MutedForeground,
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = removeLabel,
                tint = FlowDownColors.MutedForeground,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun timeLabel(epochMillis: Long): String = runCatching {
    java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(epochMillis))
}.getOrDefault("")

@Composable
private fun Section(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = FlowDownColors.Foreground,
            )
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

// ------------------------------------------------------------------ ① 解析结果

@Composable
private fun ResultPane(vm: CloudDriveViewModel) {
    val s = Strings.current
    Column(Modifier.fillMaxSize()) {
        when (val state = vm.state) {
            CloudDriveState.Idle -> Placeholder(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.CloudQueue,
                title = s.cloudDriveStrings.idle,
                hint = s.cloudDriveStrings.idleHint,
            )

            CloudDriveState.Loading -> Busy(
                text = s.cloudDriveStrings.busy,
                onCancel = vm::cancel,
                modifier = Modifier.weight(1f),
            )

            is CloudDriveState.Failed -> Placeholder(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.ErrorOutline,
                title = state.message,
                hint = null,
                tint = FlowDownColors.Destructive,
            )

            is CloudDriveState.Success -> {
                MetaRow(state.resolution)
                FileToolbar(vm)
                FileFilterRow(vm)
                if (vm.working) {
                    WorkingRow(s.cloudDriveStrings.busy)
                }
                if (vm.visibleFiles.isEmpty()) {
                    Placeholder(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Folder,
                        title = s.cloudDriveStrings.noFiles,
                        hint = null,
                    )
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(vm.visibleFiles, key = { it.fid }) { file ->
                            FileRow(
                                file = file,
                                selected = file.fid in vm.selected,
                                enabled = !vm.busy,
                                onToggle = { vm.toggleSelect(file) },
                                onOpen = { vm.openFolder(file) },
                                onDownload = { vm.downloadFile(file) },
                                onDownloadFolder = { vm.downloadFolder(file) },
                            )
                            Box(Modifier.fillMaxWidth().height(1.dp).background(FlowDownColors.Border))
                        }
                    }
                }
                if (vm.links.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(FlowDownColors.Border))
                    LinksStrip(vm)
                }
            }
        }
    }
}

@Composable
private fun MetaRow(resolution: CloudResolution) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            resolution.platformName,
            style = MaterialTheme.typography.labelSmall,
            color = FlowDownColors.Primary,
        )
        Text(
            resolution.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = FlowDownColors.Foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (resolution.passcode.isNotBlank()) {
            Text(
                "提取码 ${resolution.passcode}",
                style = MaterialTheme.typography.labelSmall,
                color = FlowDownColors.MutedForeground,
            )
        }
        Text(
            "${Strings.current.cloudDriveStrings.found(resolution.fileCount)} · ${formatBytes(resolution.totalSize)}",
            style = MaterialTheme.typography.labelSmall,
            color = FlowDownColors.MutedForeground,
        )
    }
}

/** 文件树工具栏：返回上级 / 面包屑 / 全选 / 下载选中 */
@Composable
private fun FileToolbar(vm: CloudDriveViewModel) {
    val s = Strings.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactButton(
            text = s.cloudDriveStrings.up,
            onClick = vm::goUp,
            enabled = vm.pathNames.isNotEmpty() && !vm.busy,
        )
        Spacer(Modifier.width(8.dp))
        Breadcrumbs(
            names = vm.pathNames,
            rootLabel = s.cloudDriveStrings.root,
            onLevel = vm::goToLevel,
            enabled = !vm.busy,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        CompactButton(
            text = s.cloudDriveStrings.selectAll,
            onClick = vm::toggleSelectAll,
            enabled = vm.files.any { !it.isDir } && !vm.busy,
        )
        Spacer(Modifier.width(8.dp))
        CompactButton(
            text = s.cloudDriveStrings.downloadSelected,
            onClick = vm::downloadSelected,
            enabled = vm.selected.isNotEmpty() && !vm.busy,
            icon = Icons.Default.Download,
            primary = true,
        )
    }
}

/**
 * 当前目录的搜索 + 排序。
 *
 * 只在本地过滤 / 排序已经拉到的这一层文件，不重新请求网盘接口；
 * 列表本身是 LazyColumn，几千个文件也不会一次性组合。
 */
@Composable
private fun FileFilterRow(vm: CloudDriveViewModel) {
    val s = Strings.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = vm.searchQuery,
            onValueChange = vm::updateSearchQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.labelSmall.copy(color = FlowDownColors.Foreground),
            modifier = Modifier
                .weight(1f)
                .height(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(FlowDownColors.Muted)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (vm.searchQuery.isEmpty()) {
                        Text(
                            s.cloudDriveStrings.searchPlaceholder,
                            style = MaterialTheme.typography.labelSmall,
                            color = FlowDownColors.MutedForeground,
                        )
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        CompactButton(
            text = when (vm.sortMode) {
                CloudFileSort.NAME -> s.cloudDriveStrings.sortByName
                CloudFileSort.SIZE -> s.cloudDriveStrings.sortBySize
                CloudFileSort.TYPE -> s.cloudDriveStrings.sortByType
            },
            onClick = vm::cycleSortMode,
        )
    }
}

@Composable
private fun WorkingRow(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            Modifier.size(14.dp),
            color = FlowDownColors.Primary,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = FlowDownColors.MutedForeground)
    }
}

/** 解析结果底部的直链条：固定高度，不与文件树抢滚动空间 */
@Composable
private fun LinksStrip(vm: CloudDriveViewModel) {
    val s = Strings.current
    // 固定高度 + 内层 weight：LazyColumn 必须有确定高度，否则 Column 会给它无限高度约束
    Column(Modifier.fillMaxWidth().height(112.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${s.cloudDriveStrings.linksTitle} · ${s.cloudDriveStrings.linksFound(vm.links.size)}",
                style = MaterialTheme.typography.labelSmall,
                color = FlowDownColors.MutedForeground,
            )
            Spacer(Modifier.weight(1f))
            CompactButton(
                text = s.cloudDriveStrings.clearLinks,
                onClick = vm::clearLinks,
                destructive = true,
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(vm.links, key = { it.url }) { link ->
                LinkRow(
                    link = link,
                    added = link.url in vm.addedUrls,
                    onDownload = { vm.downloadLink(link) },
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(FlowDownColors.Border))
            }
        }
    }
}

@Composable
private fun LinkRow(link: CloudLink, added: Boolean, onDownload: () -> Unit) {
    val s = Strings.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                link.filename,
                style = MaterialTheme.typography.bodySmall,
                color = FlowDownColors.Foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 直链带签名参数，允许选中复制（有些播放器要手动粘地址）
            SelectionContainer {
                Text(
                    link.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = FlowDownColors.MutedForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (link.size > 0) {
            Text(
                formatBytes(link.size),
                style = MaterialTheme.typography.labelSmall,
                color = FlowDownColors.MutedForeground,
            )
            Spacer(Modifier.width(8.dp))
        }
        CompactButton(
            text = if (added) s.cloudDriveStrings.added else s.cloudDriveStrings.download,
            onClick = onDownload,
            enabled = !added,
            icon = Icons.Default.Download,
        )
    }
}

@Composable
private fun Breadcrumbs(
    names: List<String>,
    rootLabel: String,
    onLevel: (Int) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            rootLabel,
            modifier = Modifier.clickable(enabled = enabled) { onLevel(0) },
            style = MaterialTheme.typography.labelSmall,
            color = if (names.isEmpty()) FlowDownColors.Foreground else FlowDownColors.Primary,
            maxLines = 1,
        )
        names.forEachIndexed { index, name ->
            Text(" / ", style = MaterialTheme.typography.labelSmall, color = FlowDownColors.MutedForeground)
            Text(
                name,
                modifier = Modifier.clickable(enabled = enabled) { onLevel(index + 1) },
                style = MaterialTheme.typography.labelSmall,
                color = if (index == names.lastIndex) FlowDownColors.Foreground else FlowDownColors.Primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FileRow(
    file: CloudFile,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onDownloadFolder: () -> Unit,
) {
    val s = Strings.current
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (file.isDir) {
            Icon(
                Icons.Default.Folder,
                contentDescription = s.cloudDriveStrings.folder,
                tint = FlowDownColors.Primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
        } else {
            Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = enabled)
        }
        Column(
            Modifier
                .weight(1f)
                .then(if (file.isDir) Modifier.clickable(enabled = enabled, onClick = onOpen) else Modifier),
        ) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodySmall,
                color = FlowDownColors.Foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (file.isDir) s.cloudDriveStrings.folder else formatBytes(file.size),
                style = MaterialTheme.typography.labelSmall,
                color = FlowDownColors.MutedForeground,
            )
        }
        if (file.isDir) {
            // 文件夹不参与勾选：点名字进目录，点按钮则递归下载整个文件夹（含所有子目录）
            CompactButton(
                text = s.cloudDriveStrings.downloadFolder,
                onClick = onDownloadFolder,
                enabled = enabled,
                icon = Icons.Default.Download,
            )
        } else {
            CompactButton(
                text = s.cloudDriveStrings.download,
                onClick = onDownload,
                enabled = enabled,
                icon = Icons.Default.Download,
            )
        }
    }
}

// ------------------------------------------------------------------ ② 网盘列表（每个网盘）

@Composable
private fun DrivePane(vm: CloudDriveViewModel) {
    val s = Strings.current
    Column(Modifier.fillMaxSize()) {
        Text(
            s.cloudDriveStrings.driveHint,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = FlowDownColors.MutedForeground,
        )
        // 凭证编辑器原先只有 ViewModel 状态、没有 UI 接线。GitHub 卡片不能走 JCEF
        // 网页登录（它没有网盘登录页），所以这个手填入口是配置可选 Token 的必需路径。
        vm.editingPlatform?.let { CredentialEditor(vm, it) }
        // P3：账号管理面板（多账号设主 / 改备注 / 退出 / 添加）
        vm.accountPanelPlatform?.let { AccountPanel(vm, it) }
        val columns = if (vm.gridMode) 2 else 1
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vm.mirrorOptions.isNotEmpty()) {
                item { MirrorRow(vm) }
            }
            items(CloudDriveCatalog.platforms.chunked(columns)) { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { info ->
                        PlatformCard(
                            info = info,
                            configured = vm.isConfigured(info.platform),
                            masked = vm.maskedCredential(info.platform),
                            current = vm.activePlatform == info.platform,
                            quota = vm.quotas[info.platform],
                            accountCount = vm.accountCount(info.platform),
                            accountStrings = vm.accountStrings,
                            // GitHub 没有网盘登录页：直接展开凭证编辑器填可选 Token；
                            // 其余平台仍打开应用内网页登录 Screen（含手动粘贴 Cookie）。
                            onClick = {
                                if (info.platform == SharePlatform.GITHUB) vm.openPlatform(info.platform)
                                else vm.openLogin(info.platform)
                            },
                            onManageAccounts = { vm.openAccountPanel(info.platform) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 最后一行不足 columns 张卡时补空位，避免单卡被拉满整行
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * 手填凭证（Cookie / access token / GitHub PAT）。输入框一律遮罩：
 * GitHubTokenStore 的上游安全约定明确禁止在 UI 回显完整 Token。
 */
@Composable
private fun CredentialEditor(vm: CloudDriveViewModel, platform: SharePlatform) {
    val s = Strings.current
    val info = CloudDriveCatalog.of(platform)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(FlowDownColors.Card)
            .border(1.dp, FlowDownColors.Primary, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${info?.name ?: platform.name} · ${info?.credentialLabel.orEmpty()}",
                style = MaterialTheme.typography.labelSmall,
                color = FlowDownColors.Foreground,
            )
            BasicTextField(
                value = vm.credentialDraft,
                onValueChange = vm::updateCredentialDraft,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = TextStyle(color = FlowDownColors.Foreground, fontSize = 12.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(FlowDownColors.Muted)
                    .padding(6.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        CompactButton(text = s.cloudDriveStrings.accountSave, onClick = vm::saveCredential, primary = true)
        Spacer(Modifier.width(4.dp))
        CompactButton(
            text = s.cloudDriveStrings.accountClear,
            onClick = vm::clearCredential,
            destructive = true,
        )
    }
}

/**
 * GitHub 下载镜像：默认 AUTO（实测最快，未测出可用镜像时回落直连），点击循环切换
 * 「自动最快 / 5 个预设 / 直连」。
 *
 * P2：默认开启镜像后，这一行必须把「当前实际生效」显示出来 —— 不能让用户的下载流量
 * 在不知情的情况下经第三方转发。测速快照更新会触发重组，状态随之刷新。
 */
@Composable
private fun MirrorRow(vm: CloudDriveViewModel) {
    val s = Strings.current
    val options = vm.mirrorOptions
    if (options.isEmpty()) return
    val probe = GitHubMirror.snapshot.collectAsState().value
    val effective = GitHubMirror.activePrefix()
    val status = when {
        effective.isNotBlank() ->
            s.cloudDriveStrings.githubMirrorEffective(effective.removePrefix("https://").trimEnd('/'))
        probe.testing -> s.cloudDriveStrings.githubMirrorTesting
        else -> s.cloudDriveStrings.githubMirrorFallback
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                s.cloudDriveStrings.githubMirrorLabel,
                style = MaterialTheme.typography.labelSmall,
                color = FlowDownColors.MutedForeground,
                modifier = Modifier.weight(1f),
            )
            CompactButton(
                text = options.firstOrNull { it.first == vm.mirrorChoice }?.second ?: options.first().second,
                onClick = {
                    val idx = options.indexOfFirst { it.first == vm.mirrorChoice }
                    vm.selectMirror(options[(idx + 1) % options.size].first)
                },
                primary = vm.mirrorChoice != GitHubMirrorChoice.DIRECT,
            )
        }
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            color = FlowDownColors.MutedForeground,
            modifier = Modifier.padding(start = 2.dp, top = 2.dp),
        )
    }
}

/**
 * P3 账号管理面板：同一平台的多个账号、设主账号、改备注、退出、添加。
 * 只消费 [top.mcly99.dl.viewmodel.CloudAccountView] 投影，不接触凭证原文。
 */
@Composable
private fun AccountPanel(vm: CloudDriveViewModel, platform: SharePlatform) {
    val e = vm.accountStrings
    val s = Strings.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(FlowDownColors.Card)
            .border(1.dp, FlowDownColors.Border, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${CloudDriveCatalog.of(platform)?.name ?: platform.name} · ${e.accountSectionTitle}",
                style = MaterialTheme.typography.labelSmall,
                color = FlowDownColors.Foreground,
                modifier = Modifier.weight(1f),
            )
            CompactButton(text = s.cloudDriveStrings.accountCancel, onClick = vm::closeAccountPanel)
        }
        Spacer(Modifier.height(6.dp))
        if (vm.accountList.isEmpty()) {
            Text(
                s.cloudDriveStrings.accountGuest,
                style = MaterialTheme.typography.labelSmall,
                color = FlowDownColors.MutedForeground,
            )
        }
        vm.accountList.forEach { account ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(FlowDownColors.Muted)
                    .padding(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        e.accountLabel(account.label, account.masked),
                        style = MaterialTheme.typography.labelSmall,
                        color = FlowDownColors.Foreground,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (account.isPrimary) {
                        Text(
                            e.primaryBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = FlowDownColors.Primary,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (vm.renamingAccountId == account.id) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = vm.remarkDraft,
                            onValueChange = vm::updateRemarkDraft,
                            singleLine = true,
                            textStyle = TextStyle(color = FlowDownColors.Foreground, fontSize = 12.sp),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(FlowDownColors.Card)
                                .padding(6.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        CompactButton(
                            text = s.cloudDriveStrings.accountSave,
                            onClick = vm::commitRename,
                            primary = true,
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!account.isPrimary) {
                            CompactButton(
                                text = e.setPrimary,
                                onClick = { vm.setPrimaryAccount(account.id) },
                            )
                        }
                        CompactButton(text = e.rename, onClick = { vm.beginRename(account.id) })
                        CompactButton(
                            text = e.remove,
                            onClick = { vm.removeAccount(account.id) },
                            destructive = true,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        CompactButton(
            text = e.addAccount,
            onClick = { vm.startAddAccount(platform) },
            primary = true,
        )
        Text(
            e.addAccountHint,
            style = MaterialTheme.typography.labelSmall,
            color = FlowDownColors.MutedForeground,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun PlatformCard(
    info: CloudDrivePlatformInfo,
    configured: Boolean,
    masked: String,
    current: Boolean,
    quota: CloudQuota?,
    accountCount: Int,
    accountStrings: MultiAccountStrings,
    onClick: () -> Unit,
    onManageAccounts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = Strings.current
    val capability = if (info.platform == SharePlatform.GITHUB) {
        s.cloudDriveStrings.accountGithubHint
    } else {
        listOfNotNull(
            s.cloudDriveStrings.accountGuestBrowse.takeIf { info.guestBrowse },
            if (info.guestDownload) s.cloudDriveStrings.accountGuestDownload
            else s.cloudDriveStrings.accountNeedCredential,
        ).joinToString(" · ")
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(FlowDownColors.Card)
            .border(
                1.dp,
                if (current) FlowDownColors.Primary else FlowDownColors.Border,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 上游 DriveScreen 也是用文字头像，没有品牌 PNG
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (configured) FlowDownColors.Primary else FlowDownColors.Muted),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                info.avatar,
                style = MaterialTheme.typography.labelSmall,
                color = if (configured) FlowDownColors.OnPrimary else FlowDownColors.MutedForeground,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    info.name,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodySmall,
                    color = FlowDownColors.Foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (current) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        s.cloudDriveStrings.accountCurrent,
                        style = MaterialTheme.typography.labelSmall,
                        color = FlowDownColors.Primary,
                        maxLines = 1,
                    )
                }
            }
            Text(
                when {
                    !CloudDriveCatalog.supportsCredential(info.platform) -> s.cloudDriveStrings.accountGithubHint
                    configured -> buildString {
                        append(s.cloudDriveStrings.accountConfigured)
                        append(" · ").append(masked)
                        if (accountCount > 1) append(" · ").append(accountStrings.accountCount(accountCount))
                    }
                    else -> s.cloudDriveStrings.accountGuest
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (configured) FlowDownColors.Success else FlowDownColors.MutedForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${info.shareHost} · $capability",
                style = MaterialTheme.typography.labelSmall,
                color = FlowDownColors.MutedForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            quota?.let { QuotaBar(it) }
        }
        Spacer(Modifier.width(6.dp))
        // 状态指示（不是按钮）：已配置=对勾，未配置=人形轮廓（未登录）。
        // 原来未配置时用 Settings 齿轮，与右边真正的「账号管理」齿轮撞脸，
        // 看起来像有两个设置按钮；换成人物图标后两者一眼可分。
        Icon(
            if (configured) Icons.Default.CheckCircle else Icons.Default.PersonOutline,
            contentDescription = if (configured) s.cloudDriveStrings.accountConfigured else s.cloudDriveStrings.accountGuest,
            tint = if (configured) FlowDownColors.Success else FlowDownColors.MutedForeground,
            modifier = Modifier.size(18.dp),
        )
        // P3：账号管理入口单独成一个按钮，卡片主体点击仍保持原来的「登录 / 填 Token」行为
        IconButton(onClick = onManageAccounts, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Settings,
                contentDescription = accountStrings.accountSectionTitle,
                tint = FlowDownColors.MutedForeground,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun QuotaBar(quota: CloudQuota) {
    val s = Strings.current
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(FlowDownColors.Muted),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(quota.fraction)
                    .height(4.dp)
                    .background(FlowDownColors.Primary),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            s.cloudDriveStrings.accountQuota(formatBytes(quota.used), formatBytes(quota.total)),
            style = MaterialTheme.typography.labelSmall,
            color = FlowDownColors.MutedForeground,
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------ 通用状态视图

@Composable
private fun Busy(
    text: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = Strings.current
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                Modifier.size(28.dp),
                color = FlowDownColors.Primary,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.height(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
            Spacer(Modifier.height(10.dp))
            CompactButton(text = s.catchWebCancel, onClick = onCancel)
        }
    }
}

@Composable
private fun Placeholder(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    hint: String?,
    modifier: Modifier = Modifier,
    tint: Color = FlowDownColors.MutedForeground,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                modifier = Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.bodySmall,
                color = tint,
            )
            if (hint != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    hint,
                    modifier = Modifier.padding(horizontal = 48.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = FlowDownColors.MutedForeground,
                )
            }
        }
    }
}

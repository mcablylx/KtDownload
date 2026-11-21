package top.mcly99.dl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.model.ShareExpire
import top.mcly99.dl.engine.clouddrive.CloudDrivePlatformInfo
import top.mcly99.dl.engine.clouddrive.MyDriveFile
import top.mcly99.dl.theme.FlowDownColors
import top.mcly99.dl.theme.MyDriveStrings
import top.mcly99.dl.utils.copyTextToClipboard
import top.mcly99.dl.utils.formatBytes
import top.mcly99.dl.viewmodel.MyDriveDialog
import top.mcly99.dl.viewmodel.MyDriveViewModel

/**
 * P4「我的网盘」统一页面（自研）。
 *
 * 上游是 10 个 *CloudScreen + 10 个 ViewModel；这里用一个页面 + 一张平台能力表，
 * 按 [top.mcly99.dl.engine.clouddrive.MyDriveCapabilities] 隐藏不支持的按钮，
 * 不复制上游 UI，也不带进「回收站可恢复」等上游的假文案。
 */
@Composable
fun MyDriveScreen(
    vm: MyDriveViewModel,
    strings: MyDriveStrings,
    /** 打开该平台的登录页；由 AppViewModel 接到 CloudDriveViewModel.openLogin */
    onLogin: (SharePlatform) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 账号池不是 Compose state：在这里读一次版本号，让整个页面（含左侧平台列表）
    // 都能订阅登录 / 登出变化。只读右侧内容区的话，左侧 item 会继续显示旧状态。
    val accountsRevision = vm.accountsRevision
    Row(modifier.fillMaxSize().padding(16.dp)) {
        PlatformPane(vm, strings, accountsRevision, Modifier.width(216.dp).fillMaxHeight())
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Toolbar(vm, strings)
            Spacer(Modifier.height(10.dp))
            Breadcrumbs(vm, strings)
            QuotaBar(vm, strings)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    // 未登录要先判，否则 loadDir 写下的 error=loginRequired 会抢先显示成红字
                    vm.platform != null && !vm.isLoggedIn ->
                        LoginRequiredPane(strings, onLogin, vm.platform)
                    vm.error != null -> Text(
                        vm.error.orEmpty(),
                        color = FlowDownColors.Destructive,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    vm.files.isEmpty() -> Text(
                        if (vm.isLoggedIn) strings.emptyDir else strings.loginHint,
                        color = FlowDownColors.MutedForeground,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> FileList(vm, strings)
                }
            }
            vm.notice?.let { message ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(FlowDownColors.Card)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = FlowDownColors.Foreground,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::clearNotice) { Text(strings.confirm) }
                }
            }
        }
    }

    when (val d = vm.dialog) {
        MyDriveDialog.None -> Unit
        MyDriveDialog.CreateFolder -> NameDialog(
            title = strings.newFolderTitle,
            hint = strings.newFolderHint,
            label = strings.nameLabel,
            initial = "",
            strings = strings,
            onConfirm = vm::submitCreateFolder,
            onDismiss = vm::dismissDialog,
        )
        is MyDriveDialog.Rename -> NameDialog(
            title = strings.renameTitle,
            hint = strings.newFolderHint,
            label = strings.nameLabel,
            initial = d.file.name,
            strings = strings,
            onConfirm = { vm.submitRename(d.file, it) },
            onDismiss = vm::dismissDialog,
        )
        is MyDriveDialog.Delete -> AlertDialog(
            onDismissRequest = vm::dismissDialog,
            title = { Text(strings.deleteTitle) },
            text = { Text(strings.deleteMessage(d.files.size)) },
            confirmButton = {
                TextButton(onClick = vm::confirmDelete) {
                    Text(strings.deleteConfirm, color = FlowDownColors.Destructive)
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissDialog) { Text(strings.cancel) } },
        )
        MyDriveDialog.Move -> MoveDialog(vm, strings)
        is MyDriveDialog.Share -> ShareDialog(vm, strings, d.files)
        MyDriveDialog.SaveShare -> SaveShareDialog(vm, strings)
        is MyDriveDialog.ShareResult -> ShareResultDialog(vm, strings, d)
    }
}

/** 未登录时的右侧引导：说明 + 一键跳到该平台的登录页 */
@Composable
private fun LoginRequiredPane(
    s: MyDriveStrings,
    onLogin: (SharePlatform) -> Unit,
    platform: SharePlatform?,
) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = FlowDownColors.MutedForeground,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            s.loginRequired,
            color = FlowDownColors.Foreground,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        if (platform != null) {
            CompactButton(
                text = s.loginAction,
                onClick = { onLogin(platform) },
                icon = Icons.Default.Lock,
                primary = true,
            )
        }
    }
}

@Composable
private fun PlatformPane(
    vm: MyDriveViewModel,
    s: MyDriveStrings,
    accountsRevision: Int,
    modifier: Modifier,
) {
    Column(modifier) {
        Text(s.platformSection, style = MaterialTheme.typography.titleSmall, color = FlowDownColors.Foreground)
        Text(s.subtitle, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(vm.platforms, key = { it.platform.name }) { info ->
                PlatformRow(info, vm, s, accountsRevision)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(s.accountSection, style = MaterialTheme.typography.titleSmall, color = FlowDownColors.Foreground)
        Spacer(Modifier.height(6.dp))
        if (vm.platform == null) {
            Text(s.loginHint, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
        } else if (!vm.isLoggedIn) {
            Text(s.loginHint, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
        } else {
            vm.accountsForPlatform.forEachIndexed { index, account ->
                val selected = (vm.accountId == null && account.isPrimary) || vm.accountId == account.id
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, if (selected) FlowDownColors.Primary else FlowDownColors.Border)
                        .background(FlowDownColors.Card)
                        .clickable { vm.selectAccount(if (account.isPrimary) null else account.id) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (selected) Icons.Default.CheckCircle else Icons.Default.Link,
                        contentDescription = null,
                        tint = if (selected) FlowDownColors.Primary else FlowDownColors.MutedForeground,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        vm.accountLabel(index),
                        style = MaterialTheme.typography.bodySmall,
                        color = FlowDownColors.Foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            Text(
                s.accountCount(vm.accountsForPlatform.size),
                style = MaterialTheme.typography.bodySmall,
                color = FlowDownColors.MutedForeground,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(s.capabilityHint, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
    }
}

@Composable
private fun PlatformRow(
    info: CloudDrivePlatformInfo,
    vm: MyDriveViewModel,
    s: MyDriveStrings,
    accountsRevision: Int,
) {
    val selected = vm.platform == info.platform
    // 版本号在此处实际读取，登录写回账号池后这个 item 才会重算 configured/accountCount
    val configured = accountsRevision >= 0 && vm.configuredFor(info.platform)
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (selected) FlowDownColors.Primary else FlowDownColors.Border, RoundedCornerShape(8.dp))
            .background(FlowDownColors.Card)
            .clickable { vm.selectPlatform(info.platform) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(FlowDownColors.Primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(info.avatar, color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                info.name,
                style = MaterialTheme.typography.bodyMedium,
                color = FlowDownColors.Foreground,
                maxLines = 1,
            )
            Text(
                if (configured) s.accountCount(vm.accountCountFor(info.platform)) else s.notLoggedIn,
                style = MaterialTheme.typography.bodySmall,
                color = if (configured) FlowDownColors.Green else FlowDownColors.MutedForeground,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Toolbar(vm: MyDriveViewModel, s: MyDriveStrings) {
    val enabled = vm.isLoggedIn && !vm.busy
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 操作按钮数量随平台能力变化，窄窗口下一行放不下时就换行，
        // 而不是把末尾的「取消选择」压成多行 / 截断
        FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactButton(s.refresh, vm::refresh, icon = Icons.Default.Refresh,
                enabled = vm.platform != null && !vm.busy)
            CompactButton(s.newFolder, vm::openCreateFolder, icon = Icons.Default.CreateNewFolder,
                enabled = enabled && vm.canCreateFolder)
            CompactButton(s.rename, vm::openRename, enabled = enabled && vm.canRename)
            CompactButton(s.move, vm::openMove, icon = Icons.AutoMirrored.Filled.DriveFileMove,
                enabled = enabled && vm.canMove)
            CompactButton(s.delete, vm::openDelete, icon = Icons.Default.Delete,
                enabled = enabled && vm.canDelete, destructive = true)
            CompactButton(s.share, vm::openShare, icon = Icons.Default.Share, enabled = enabled && vm.canShare)
            CompactButton(s.download, vm::downloadSelected, icon = Icons.Default.Download,
                enabled = enabled && vm.canDownload)
            CompactButton(s.saveShare, vm::openSaveShare, enabled = enabled && vm.canSaveShare)
        }
        Spacer(Modifier.width(8.dp))
        // 选择状态 + 取消选择按内容宽度测量，前面的按钮再怎么换行也不会挤到它
        Row(
            Modifier.width(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (vm.selectedIds.isNotEmpty()) {
                Text(
                    s.selectedCount(vm.selectedIds.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = FlowDownColors.MutedForeground,
                )
                CompactButton(s.clearSelection, vm::clearSelection)
            } else {
                CompactButton(s.selectAll, vm::selectAll, enabled = vm.files.isNotEmpty())
            }
        }
    }
}

@Composable
private fun Breadcrumbs(vm: MyDriveViewModel, s: MyDriveStrings) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (vm.pathStack.isNotEmpty()) {
            TextButton(onClick = vm::goUp) { Text(s.up) }
        }
        val path = listOf(s.title) + vm.pathStack.map { it.second }
        Text(
            path.joinToString(" / "),
            style = MaterialTheme.typography.bodySmall,
            color = FlowDownColors.MutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(s.itemsCount(vm.files.size), style = MaterialTheme.typography.bodySmall,
            color = FlowDownColors.MutedForeground)
    }
}

@Composable
private fun QuotaBar(vm: MyDriveViewModel, s: MyDriveStrings) {
    val q = vm.quota ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            s.quotaLabel(formatBytes(q.used), formatBytes(q.total)),
            style = MaterialTheme.typography.bodySmall,
            color = FlowDownColors.MutedForeground,
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
            .background(FlowDownColors.Muted)) {
            Box(Modifier.fillMaxWidth(q.fraction).height(4.dp).background(FlowDownColors.Primary))
        }
    }
}

@Composable
private fun FileList(vm: MyDriveViewModel, s: MyDriveStrings) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(vm.files, key = { it.id }) { file ->
            FileRow(file, selected = file.id in vm.selectedIds, s = s,
                onToggle = { vm.toggleSelect(file.id) }, onOpen = { vm.openDir(file) })
        }
        // 139 / 123 的目录是分页的；没有这个入口，第二页之后的文件根本取不到
        if (vm.hasMore) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CompactButton(
                        text = if (vm.loadingMore) s.loadingMore else s.loadMore,
                        onClick = vm::loadMore,
                        enabled = !vm.loadingMore,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: MyDriveFile,
    selected: Boolean,
    s: MyDriveStrings,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, FlowDownColors.Border, RoundedCornerShape(6.dp))
            .background(if (selected) FlowDownColors.Card else Color.Transparent)
            .clickable { if (file.isDir) onOpen() else onToggle() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Icon(
            if (file.isDir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (file.isDir) FlowDownColors.Primary else FlowDownColors.MutedForeground,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            color = FlowDownColors.Foreground)
        Text(
            if (file.isDir) s.folderTag else formatBytes(file.size),
            style = MaterialTheme.typography.bodySmall,
            color = FlowDownColors.MutedForeground,
        )
    }
}

// ---------------------------------------------------------------- 弹窗

@Composable
private fun NameDialog(
    title: String,
    hint: String,
    label: String,
    initial: String,
    strings: MyDriveStrings,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(label, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
                CompactTextField(value = text, onValueChange = { text = it }, placeholder = hint,
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(strings.confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@Composable
private fun MoveDialog(vm: MyDriveViewModel, s: MyDriveStrings) {
    AlertDialog(
        onDismissRequest = vm::dismissDialog,
        title = { Text(s.moveTitle) },
        text = {
            Column(Modifier.fillMaxWidth().height(260.dp)) {
                Text(
                    (listOf(s.title) + vm.movePathStack.map { it.second }).joinToString(" / "),
                    style = MaterialTheme.typography.bodySmall,
                    color = FlowDownColors.MutedForeground,
                    maxLines = 1,
                )
                Spacer(Modifier.height(6.dp))
                if (vm.movePathStack.isNotEmpty()) {
                    TextButton(onClick = vm::moveGoUp) { Text(s.moveUp) }
                }
                if (vm.moveFolders.isEmpty()) {
                    Text(s.moveEmpty, style = MaterialTheme.typography.bodySmall,
                        color = FlowDownColors.MutedForeground)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(vm.moveFolders, key = { it.id }) { folder ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                                    .clickable { vm.openMoveFolder(folder) }.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null,
                                    tint = FlowDownColors.Primary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    folder.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = FlowDownColors.Foreground,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::confirmMove) { Text(s.moveHere) } },
        dismissButton = { TextButton(onClick = vm::dismissDialog) { Text(s.cancel) } },
    )
}

@Composable
private fun ShareDialog(vm: MyDriveViewModel, s: MyDriveStrings, files: List<MyDriveFile>) {
    var passcode by remember { mutableStateOf("") }
    // 档位由平台能力表决定：通用四档 / 115 的五档 / 蓝奏则完全没有选择器
    val tiers = vm.shareExpiryTiers.ifEmpty { listOf(ShareExpire.FOREVER) }
    var selectedTier by remember(tiers) { mutableStateOf(tiers.first()) }
    AlertDialog(
        onDismissRequest = vm::dismissDialog,
        title = { Text(s.shareTitle) },
        text = {
            Column {
                if (vm.shareSupportsPasscode) {
                    Text(s.passcodeLabel, style = MaterialTheme.typography.bodySmall,
                        color = FlowDownColors.MutedForeground)
                    CompactTextField(value = passcode, onValueChange = { passcode = it },
                        placeholder = s.passcodeHint, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                if (vm.shareExpiryTiers.isNotEmpty()) {
                    Text(s.expireLabel, style = MaterialTheme.typography.bodySmall,
                        color = FlowDownColors.MutedForeground)
                    tiers.forEach { code ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedTier == code,
                                onClick = { selectedTier = code },
                            )
                            Text(
                                s.expireOption(code),
                                style = MaterialTheme.typography.bodySmall,
                                color = FlowDownColors.Foreground,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.submitShare(files, passcode, selectedTier) }) { Text(s.confirm) }
        },
        dismissButton = { TextButton(onClick = vm::dismissDialog) { Text(s.cancel) } },
    )
}

@Composable
private fun SaveShareDialog(vm: MyDriveViewModel, s: MyDriveStrings) {
    var link by remember { mutableStateOf("") }
    var passcode by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = vm::dismissDialog,
        title = { Text(s.saveShareTitle) },
        text = {
            Column {
                CompactTextField(value = link, onValueChange = { link = it },
                    placeholder = s.shareLinkHint, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                CompactTextField(value = passcode, onValueChange = { passcode = it },
                    placeholder = s.passcodeHint, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.submitSaveShare(link, passcode) }, enabled = link.isNotBlank()) {
                Text(s.saveShareAction)
            }
        },
        dismissButton = { TextButton(onClick = vm::dismissDialog) { Text(s.cancel) } },
    )
}

@Composable
private fun ShareResultDialog(vm: MyDriveViewModel, s: MyDriveStrings, d: MyDriveDialog.ShareResult) {
    AlertDialog(
        onDismissRequest = vm::dismissDialog,
        title = { Text(s.shareResultTitle) },
        text = {
            Column {
                SelectionContainer { Text(d.result.url, style = MaterialTheme.typography.bodyMedium) }
                if (d.result.passcode.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("${s.shareResultCode}: ${d.result.passcode}",
                        style = MaterialTheme.typography.bodySmall)
                }
                d.result.warning?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.Destructive)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { copyTextToClipboard(d.result.url) }) { Text(s.copyLink) }
        },
        dismissButton = { TextButton(onClick = vm::dismissDialog) { Text(s.cancel) } },
    )
}

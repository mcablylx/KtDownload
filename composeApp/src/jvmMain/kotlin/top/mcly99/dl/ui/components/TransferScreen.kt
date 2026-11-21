package top.mcly99.dl.ui.components

/**
 * 传输功能主界面组件
 * 
 * 该组件实现了局域网文件传输功能的完整用户界面，采用标签页（Tab）设计，
 * 提供设备发现、Web上传服务和传输历史三大核心功能模块。
 * 
 * 主要功能区域：
 * - 设备发现标签页：扫描和显示局域网内可用设备，支持一键发送文件
 * - Web上传标签页：配置和管理嵌入式Web上传服务器，提供URL分享功能
 * - 传输历史标签页：显示已完成和进行中的文件传输记录
 * 
 * 设计特点：
 * - 标签页导航：清晰分离不同功能区域，提升用户体验
 * - 状态可视化：设备在线状态、服务器运行状态等直观显示
 * - 响应式交互：支持设备选择、URL复制、服务启停等用户操作
 * - 网络适配：自动检测IP地址和端口，适应不同网络环境
 * - 用户友好：提供详细的描述文本和状态提示
 * 
 * @param devices 可用设备列表
 * @param transfers 传输任务列表
 * @param settings 应用设置
 * @param onLanServerEnabled 局域网服务器启用回调
 * @param onSendToDevice 发送文件到设备回调
 * @param onSendFolderToDevice 发送文件夹到设备回调
 * @param webServerRunning Web服务器运行状态
 * @param webServerIp Web服务器IP地址
 * @param modifier Compose修饰符
 * 
 * @see UiDevice 设备展示模型
 * @see UiTransfer 传输任务展示模型
 * @see AppSettings 应用设置模型
 * @see DevicesTab 设备发现标签页组件
 * @see WebTab Web上传标签页组件
 * @see HistoryTab 传输历史标签页组件
 */

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.mcly99.dl.data.defaultSettings
import top.mcly99.dl.data.initialMockDevices
import top.mcly99.dl.data.initialMockTransfers
import top.mcly99.dl.model.*
import top.mcly99.dl.theme.FlowDownColors
import top.mcly99.dl.theme.FlowDownTheme
import top.mcly99.dl.theme.Strings
import top.mcly99.dl.theme.TransferExtraStrings
import top.mcly99.dl.theme.transferExtraStrings
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.copyTextToClipboard
import top.mcly99.dl.utils.formatBytes
import top.mcly99.dl.utils.formatSpeed

@Composable
fun TransferScreen(
    devices: List<UiDevice>,
    transfers: List<UiTransfer>,
    settings: AppSettings,
    onLanServerEnabled: (Boolean) -> Unit,
    onSendToDevice: (String) -> Unit = {},
    onSendFolderToDevice: (String) -> Unit = {},
    onCancelTransfer: (String) -> Unit = {},
    onRemoveTransfer: (String) -> Unit = {},
    onClearFinished: () -> Unit = {},
    onOpenReceiveDir: () -> Unit = {},
    webServerRunning: Boolean = false,
    webServerError: Boolean = false,
    webServerIp: String = "",
    webServerUrl: String = "",
    /** 服务实际监听的端口：换端口失败回滚时与设置里的值不同 */
    webServerPort: Int = AppConstants.DEFAULT_WEB_PORT,
    discoveryRunning: Boolean = true,
    fileServerRunning: Boolean = true,
    extra: TransferExtraStrings = transferExtraStrings("zh-CN"),
    modifier: Modifier = Modifier,
) {
    val s = Strings.current

    var tab by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    // P1-25：只有服务真正启动后 `webServerUrl` 才是带令牌的地址。原先在这里用
    // `"http://$ip:$port/"` 兜底，拼出一个**必定 403** 的地址并且还能被复制走。
    // 现在未就绪时输入框显示占位文案，复制按钮直接禁用。
    val urlReady = webServerUrl.startsWith("http://") || webServerUrl.startsWith("https://")
    val displayUrl = if (urlReady) webServerUrl else s.serviceStarting

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 960.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            // 使用 PrimaryTabRow 替代已弃用的 TabRow（Material3 推荐）
            PrimaryTabRow(
                selectedTabIndex = tab,
                containerColor = Color.Transparent,
                contentColor = FlowDownColors.Foreground,
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(s.tabDeviceDiscovery) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(s.tabWebUpload) })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(s.tabTransferHistory) })
            }
            Spacer(Modifier.height(16.dp))
            when (tab) {
                0 -> DevicesTab(devices, onSendToDevice, onSendFolderToDevice, discoveryRunning, fileServerRunning, extra)
                1 -> WebTab(
                    settings = settings,
                    webUrl = displayUrl,
                    urlReady = urlReady,
                    copied = copied,
                    serverRunning = webServerRunning,
                    serverError = webServerError,
                    serverPort = webServerPort,
                    extra = extra,
                    onLanServerEnabled = onLanServerEnabled,
                    onCopiedChange = { copied = it },
                )
                2 -> HistoryTab(transfers, extra, onCancelTransfer, onRemoveTransfer, onClearFinished, onOpenReceiveDir)
            }
        }
    }
}

@Composable
private fun DevicesTab(
    devices: List<UiDevice>,
    onSendToDevice: (String) -> Unit = {},
    onSendFolderToDevice: (String) -> Unit = {},
    discoveryRunning: Boolean = true,
    fileServerRunning: Boolean = true,
    extra: TransferExtraStrings = transferExtraStrings("zh-CN"),
) {
    val s = Strings.current

    Card(colors = CardDefaults.cardColors(containerColor = FlowDownColors.Card)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(s.lanDevices, style = MaterialTheme.typography.titleSmall, color = FlowDownColors.Foreground)
                    Text(s.lanDevicesDesc, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
                    if (!discoveryRunning || !fileServerRunning) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            s.lanPortUnavailable,
                            style = MaterialTheme.typography.bodySmall,
                            color = FlowDownColors.Warning,
                        )
                    }
                }
                CompactButton(text = s.refresh, onClick = {}, icon = Icons.Default.Refresh)
            }
            Spacer(Modifier.height(12.dp))
            if (devices.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Wifi, contentDescription = null, tint = FlowDownColors.MutedForeground, modifier = Modifier.size(48.dp))
                    Text(s.noDevicesFound, color = FlowDownColors.MutedForeground)
                    Text(s.noDevicesHint, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    devices.forEach { device ->
                        DeviceRow(
                            device,
                            extra = extra,
                            onSendFile = { onSendToDevice(device.id) },
                            onSendFolder = { onSendFolderToDevice(device.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: UiDevice,
    extra: TransferExtraStrings = transferExtraStrings("zh-CN"),
    onSendFile: () -> Unit = {},
    onSendFolder: () -> Unit = {},
) {
    val s = Strings.current
    val icon = deviceIcon(device.type)
    var copyDone by remember { mutableStateOf(false) }

    LaunchedEffect(copyDone) {
        if (copyDone) {
            kotlinx.coroutines.delay(AppConstants.COPY_FEEDBACK_MS)
            copyDone = false
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (device.online) FlowDownColors.Card else FlowDownColors.Muted.copy(alpha = 0.3f))
            .border(1.dp, FlowDownColors.Border.copy(alpha = if (device.online) 1f else 0.5f), RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (device.online) FlowDownColors.Primary.copy(alpha = 0.1f) else FlowDownColors.Muted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (device.online) FlowDownColors.Primary else FlowDownColors.MutedForeground, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.padding(start = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.name, style = MaterialTheme.typography.bodyMedium, color = FlowDownColors.Foreground)
                    Spacer(Modifier.size(8.dp))
                    if (device.online) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(FlowDownColors.Green))
                            Spacer(Modifier.size(4.dp))
                            Text(s.online, style = MaterialTheme.typography.labelSmall, color = FlowDownColors.Green)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(12.dp), tint = FlowDownColors.MutedForeground)
                            Spacer(Modifier.size(4.dp))
                            Text(s.offline, style = MaterialTheme.typography.labelSmall, color = FlowDownColors.MutedForeground)
                        }
                    }
                }
                Text(device.ip, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
                Spacer(Modifier.size(8.dp))
                Text(s.devicePort(device.port), style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // P1-24：这里原先复制 `http://ip:port`，可对方的 Web 上传服务强制要求
            // `?token=`，而 token 是本机启动时随机生成的私有值，外人无从得知 ——
            // 复制出来的链接打开必定是 403。改成复制 IP（用户自己拼地址、或告诉同事 IP 都用得上）。
            IconButton(
                onClick = {
                    copyTextToClipboard(device.ip)
                    copyDone = true
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    if (copyDone) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                    contentDescription = if (copyDone) extra.copiedIp(device.ip) else extra.copyIp,
                    tint = if (copyDone) FlowDownColors.Green else FlowDownColors.MutedForeground,
                    modifier = Modifier.size(18.dp),
                )
            }
            CompactButton(
                text = s.sendFolder,
                onClick = onSendFolder,
                icon = Icons.Default.CreateNewFolder,
                enabled = device.online,
            )
            Spacer(Modifier.size(8.dp))
            CompactButton(
                text = s.sendFile,
                onClick = onSendFile,
                icon = Icons.AutoMirrored.Filled.Send,
                enabled = device.online,
                primary = true,
            )
        }
    }
}

private fun deviceIcon(type: DeviceType): ImageVector = when (type) {
    DeviceType.Desktop -> Icons.Default.DesktopWindows
    DeviceType.Mobile -> Icons.Default.PhoneAndroid
    DeviceType.Tablet -> Icons.Default.Tablet
}

@Composable
private fun WebTab(
    settings: AppSettings,
    webUrl: String,
    urlReady: Boolean,
    copied: Boolean,
    serverRunning: Boolean,
    serverError: Boolean = false,
    serverPort: Int = settings.lanServerPort,
    extra: TransferExtraStrings = transferExtraStrings("zh-CN"),
    onLanServerEnabled: (Boolean) -> Unit,
    onCopiedChange: (Boolean) -> Unit,
) {
    val s = Strings.current

    Card(colors = CardDefaults.cardColors(containerColor = FlowDownColors.Card)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(s.webUploadService, style = MaterialTheme.typography.titleSmall, color = FlowDownColors.Foreground)
                    Text(s.webUploadDesc, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.enableService, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
                    Spacer(Modifier.size(8.dp))
                    CompactSwitch(checked = settings.lanServerEnabled, onCheckedChange = onLanServerEnabled)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (settings.lanServerEnabled) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(FlowDownColors.Muted.copy(alpha = 0.3f))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(128.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(FlowDownColors.Card)
                            .border(1.dp, FlowDownColors.Border, RoundedCornerShape(8.dp))
                            .padding(4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 服务运行中且已有带令牌的地址时才生成二维码
                        if (serverRunning && urlReady) {
                            QrCodeImage(content = webUrl, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(
                                Icons.Default.QrCode2,
                                contentDescription = null,
                                tint = FlowDownColors.MutedForeground,
                                modifier = Modifier.size(64.dp),
                            )
                        }
                    }
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Text(s.scanOrVisit, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CompactTextField(
                                value = webUrl,
                                onValueChange = {},
                                modifier = Modifier.weight(1f),
                                readOnly = true,
                            )
                            Spacer(Modifier.width(4.dp))
                            // 地址里带的是本次启动随机生成的 token，没就绪时它根本不存在，
                            // 复制出去只会得到 403，所以未就绪直接禁用（P1-25）
                            IconButton(
                                onClick = {
                                    if (!urlReady) return@IconButton
                                    copyTextToClipboard(webUrl)
                                    onCopiedChange(true)
                                },
                                enabled = urlReady,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    if (copied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    tint = if (!urlReady) {
                                        FlowDownColors.MutedForeground.copy(alpha = 0.4f)
                                    } else if (copied) {
                                        FlowDownColors.Green
                                    } else {
                                        FlowDownColors.MutedForeground
                                    },
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (serverRunning && serverError) {
                        // 换端口失败但旧服务还在跑：地址仍是可用的，只是端口不是用户刚填的那个
                        Box(Modifier.size(8.dp).clip(CircleShape).background(FlowDownColors.Warning))
                        Spacer(Modifier.size(8.dp))
                        Text(
                            extra.portSwitchFailed(serverPort),
                            style = MaterialTheme.typography.bodySmall,
                            color = FlowDownColors.Warning,
                        )
                    } else if (serverRunning) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(FlowDownColors.Green))
                        Spacer(Modifier.size(8.dp))
                        Text(s.serviceRunning(serverPort), style = MaterialTheme.typography.bodySmall, color = FlowDownColors.Green)
                    } else if (serverError) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(FlowDownColors.Destructive))
                        Spacer(Modifier.size(8.dp))
                        Text(s.serviceStartFailed(serverPort), style = MaterialTheme.typography.bodySmall, color = FlowDownColors.Destructive)
                    } else {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(FlowDownColors.Warning))
                        Spacer(Modifier.size(8.dp))
                        Text(s.serviceStarting, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.Warning)
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Upload, contentDescription = null, tint = FlowDownColors.MutedForeground, modifier = Modifier.size(48.dp))
                    Text(s.webUploadDisabled, color = FlowDownColors.MutedForeground)
                    Text(s.webUploadDisabledHint, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
                }
            }
        }
    }
}

@Composable
private fun HistoryTab(
    transfers: List<UiTransfer>,
    extra: TransferExtraStrings = transferExtraStrings("zh-CN"),
    onCancelTransfer: (String) -> Unit = {},
    onRemoveTransfer: (String) -> Unit = {},
    onClearFinished: () -> Unit = {},
    onOpenReceiveDir: () -> Unit = {},
) {
    val s = Strings.current
    val hasFinished = transfers.any {
        it.status != TransferStatus.TRANSFERRING && it.status != TransferStatus.WAITING
    }

    Card(colors = CardDefaults.cardColors(containerColor = FlowDownColors.Card)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.transferHistory, style = MaterialTheme.typography.titleSmall, color = FlowDownColors.Foreground)
                    Text(s.transferHistoryDesc, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
                }
                // P1-23：这两个能力在 ViewModel 里一直存在，但没有任何入口 —— 用户既打不开
                // 接收目录，也清不掉堆积如山的记录。这里补上按钮，并带上可用性判断。
                CompactButton(
                    text = extra.openReceiveDir,
                    onClick = onOpenReceiveDir,
                    icon = Icons.Default.FolderOpen,
                )
                Spacer(Modifier.size(8.dp))
                CompactButton(
                    text = extra.clearFinished,
                    onClick = onClearFinished,
                    icon = Icons.Default.DeleteSweep,
                    enabled = hasFinished,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (transfers.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = FlowDownColors.MutedForeground, modifier = Modifier.size(48.dp))
                    Text(s.noTransferHistory, color = FlowDownColors.MutedForeground)
                }
            } else {
                // v4-P2-7：原先是 Column + forEach，所有历史行一次性组合；长会话累积后
                // 每次进度刷新（200ms）都要重建整列。这里改成 LazyColumn 只组合可见行。
                // 外层页面是 verticalScroll，所以必须给列表一个有界高度，否则无法测量。
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(transfers, key = { it.id }) { transfer ->
                        TransferRow(
                            transfer = transfer,
                            extra = extra,
                            onCancel = { onCancelTransfer(transfer.id) },
                            onRemove = { onRemoveTransfer(transfer.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferRow(
    transfer: UiTransfer,
    extra: TransferExtraStrings = transferExtraStrings("zh-CN"),
    onCancel: () -> Unit = {},
    onRemove: () -> Unit = {},
) {
    val s = Strings.current

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(FlowDownColors.Muted.copy(alpha = 0.3f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    if (transfer.direction == TransferDirection.Send) FlowDownColors.Blue.copy(alpha = 0.2f)
                    else FlowDownColors.Primary.copy(alpha = 0.2f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (transfer.direction == TransferDirection.Send) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = null,
                tint = if (transfer.direction == TransferDirection.Send) FlowDownColors.Blue else FlowDownColors.Primary,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = FlowDownColors.MutedForeground, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(8.dp))
                Text(transfer.fileName, style = MaterialTheme.typography.bodyMedium, color = FlowDownColors.Foreground, maxLines = 1)
                Spacer(Modifier.size(8.dp))
                TransferStatusChip(transfer.status)
            }
            if (transfer.status == TransferStatus.TRANSFERRING) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(FlowDownColors.Muted),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((transfer.progress.coerceIn(0, 100)) / 100f)
                            .height(6.dp)
                            .background(FlowDownColors.Primary),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    "${if (transfer.direction == TransferDirection.Send) s.sending else s.receiving} ${transfer.deviceName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = FlowDownColors.MutedForeground,
                )
                Spacer(Modifier.size(16.dp))
                // 文件夹发送在枚举完成前总大小未知，此时显示「正在统计文件…」而不是误报 0 B（v4-P3-3）
                Text(
                    if (transfer.sizePreparing) s.countingFiles else formatBytes(transfer.fileSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = FlowDownColors.MutedForeground,
                )
                if (transfer.status == TransferStatus.TRANSFERRING) {
                    Spacer(Modifier.size(16.dp))
                    Text(formatSpeed(transfer.speed), style = MaterialTheme.typography.labelSmall, color = FlowDownColors.MutedForeground)
                }
            }
        }
        // P1-23：状态列原先只是几个静态图标 —— 传输卡住时用户既取消不了、也删不掉记录。
        // 进行中给「取消」（会关 socket，真正停下来），已结束给「移除记录」。
        when (transfer.status) {
            TransferStatus.TRANSFERRING, TransferStatus.WAITING -> {
                CompactButton(
                    text = extra.cancelTransfer,
                    onClick = onCancel,
                    icon = Icons.Default.Stop,
                )
            }
            TransferStatus.COMPLETED -> {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = FlowDownColors.Green, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(4.dp))
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = extra.removeRecord,
                        tint = FlowDownColors.MutedForeground,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            TransferStatus.FAILED, TransferStatus.CANCELLED -> {
                Icon(Icons.Default.Close, contentDescription = null, tint = FlowDownColors.MutedForeground, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(4.dp))
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = extra.removeRecord,
                        tint = FlowDownColors.MutedForeground,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferStatusChip(status: TransferStatus) {
    val s = Strings.current
    val (bg, fg, label) = when (status) {
        TransferStatus.COMPLETED -> Triple(FlowDownColors.Green.copy(alpha = 0.2f), FlowDownColors.Green, s.transferStatusCompleted)
        TransferStatus.TRANSFERRING -> Triple(FlowDownColors.Primary.copy(alpha = 0.2f), FlowDownColors.Primary, s.transferStatusTransferring)
        TransferStatus.FAILED -> Triple(FlowDownColors.Destructive.copy(alpha = 0.2f), FlowDownColors.Destructive, s.transferStatusFailed)
        TransferStatus.WAITING -> Triple(FlowDownColors.Muted, FlowDownColors.MutedForeground, s.statusQueued)
        TransferStatus.CANCELLED -> Triple(FlowDownColors.Muted, FlowDownColors.MutedForeground, s.statusCancelled)
    }
    Text(
        label,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = fg,
    )
}

/** 预览：局域网传输（约 900×720） */
@Preview
@Composable
private fun PreviewTransferScreen() {
    FlowDownTheme {
        Box(Modifier.size(900.dp, 720.dp)) {
            TransferScreen(
                devices = initialMockDevices(),
                transfers = initialMockTransfers(),
                settings = defaultSettings(),
                onLanServerEnabled = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

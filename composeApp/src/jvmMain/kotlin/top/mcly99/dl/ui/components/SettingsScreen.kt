package top.mcly99.dl.ui.components

/**
 * 设置界面组件
 * 
 * 该组件实现了应用程序的设置管理界面，提供用户友好的图形化设置面板，
 * 支持下载、网络、行为、代理等多个维度的配置管理。
 * 
 * 主要功能区域：
 * - 下载设置：默认下载路径、最大并发数、默认线程数
 * - 速度限制：全局下载速度限制滑块控制
 * - 行为设置：自动启动、剪贴板监控、通知显示、最小化到托盘
 * - 代理设置：HTTP代理启用和配置
 * - 网络设置：局域网服务器启用和端口配置
 * - 外观设置：主题和语言选择
 * 
 * 设计特点：
 * - 分类卡片：使用SettingsCard组件组织不同功能类别
 * - 响应式布局：支持不同屏幕尺寸的自适应布局
 * - 实时预览：所有设置更改实时生效无需重启
 * - 用户友好：提供直观的图标、描述和交互控件
 * 
 * @param settings 当前应用设置
 * @param onUpdate 设置更新回调
 * @param modifier Compose修饰符
 * 
 * @see AppSettings 应用设置模型
 * @see SettingsCard 设置分类卡片组件
 * @see SettingsDropdown 下拉选择器
 * @see SwitchRow 开关行组件
 * @see NativeFileDialog 文件对话框工具类
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.mcly99.dl.APP_VERSION
import top.mcly99.dl.data.defaultSettings
import top.mcly99.dl.data.network.PlatformProxyDetector
import top.mcly99.dl.engine.clouddrive.CloudDriveAccountStore
import top.mcly99.dl.engine.web.browser.BrowserLocator
import top.mcly99.dl.model.AppSettings
import top.mcly99.dl.model.ProxyMode
import top.mcly99.dl.theme.FlowDownColors
import top.mcly99.dl.theme.FlowDownTheme
import top.mcly99.dl.theme.Strings
import top.mcly99.dl.theme.dataToolsStrings
import top.mcly99.dl.theme.multiAccountStrings
import top.mcly99.dl.theme.networkStrings
import top.mcly99.dl.theme.shutdownStrings
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import top.mcly99.dl.utils.FfmpegLocator
import top.mcly99.dl.utils.NativeFileDialog
import top.mcly99.dl.utils.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdate: (AppSettings) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 账号池：用于「导出/导入账号备份」与诊断包的账号计数。
     * 传 null 时该卡片只保留诊断包导出（预览、无账号环境）。
     */
    accountStore: CloudDriveAccountStore? = null,
) {
    val s = Strings.current
    // 网络文案单独成表：UiStrings 的构造签名已逼近 JVM 255 槽上限，不能为新模块加字段
    val n = networkStrings(settings.language)
    val e = multiAccountStrings(settings.language)
    val sd = shutdownStrings(settings.language)
    val dt = dataToolsStrings(settings.language)
    var showLicenses by remember { mutableStateOf(false) }

    // ffmpeg 探测会启动子进程，放到 IO 线程，避免打开设置页时卡顿
    var ffmpegVersion by remember { mutableStateOf<String?>(null) }
    var ffmpegProbed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        ffmpegVersion = withContext(Dispatchers.IO) { FfmpegLocator.version() }
        ffmpegProbed = true
    }

    // 深度抓取依赖本机 Chromium 内核：探测结果缓存在 remember 里，
    // 打开设置页只扫一次文件系统，不随每次重组重复扫
    val browserPath = remember { BrowserLocator.find() }
    val browserAvailable = browserPath != null
    val browserHint = if (browserPath != null) {
        s.deepCatchBrowserReady(browserPath.fileName?.toString() ?: browserPath.toString())
    } else {
        s.deepCatchBrowserMissing
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 768.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SettingsCard(
                title = s.downloadSettings,
                description = s.downloadSettingsDesc,
                leading = { Icon(Icons.Default.Download, contentDescription = null, tint = FlowDownColors.Primary, modifier = Modifier.size(20.dp)) },
            ) {
                Text(s.defaultDownloadPath, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.Foreground)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactTextField(
                        value = settings.downloadPath,
                        onValueChange = { onUpdate(settings.copy(downloadPath = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = {
                            NativeFileDialog.chooseDirectory()?.let { onUpdate(settings.copy(downloadPath = it)) }
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = FlowDownColors.MutedForeground,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(s.maxConcurrent, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.Foreground)
                        Spacer(Modifier.height(8.dp))
                        SettingsDropdown(
                            value = settings.maxConcurrent,
                            options = listOf(1, 2, 3, 5, 8, 10),
                            label = { s.maxConcurrentLabel(it) },
                            onSelect = { onUpdate(settings.copy(maxConcurrent = it)) },
                        )
                        Text(s.maxConcurrentHint, style = MaterialTheme.typography.labelSmall, color = FlowDownColors.MutedForeground)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(s.defaultThreads, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.Foreground)
                        Spacer(Modifier.height(8.dp))
                        SettingsDropdown(
                            value = settings.defaultThreads,
                            options = listOf(1, 2, 4, 8, 16, 32, 64),
                            label = { s.defaultThreadsLabel(it) },
                            onSelect = { onUpdate(settings.copy(defaultThreads = it)) },
                        )
                        Text(s.defaultThreadsHint, style = MaterialTheme.typography.labelSmall, color = FlowDownColors.MutedForeground)
                    }
                }
            }

            SettingsCard(
                title = s.mediaSettings,
                description = s.mediaSettingsDesc,
                leading = { Icon(Icons.Default.Movie, contentDescription = null, tint = FlowDownColors.Primary, modifier = Modifier.size(20.dp)) },
            ) {
                SwitchRow(s.keepHlsSegments, s.keepHlsSegmentsDesc, settings.keepHlsSegments) {
                    onUpdate(settings.copy(keepHlsSegments = it))
                }
                SwitchRow(s.probeMediaSize, s.probeMediaSizeDesc, settings.probeMediaSize) {
                    onUpdate(settings.copy(probeMediaSize = it))
                }
                // 深度抓取要真的起浏览器，默认关闭；开启后每次抓取会多等 5~15 秒。
                // 没装浏览器时开关直接禁用（显示真实值，只是不能改）：开着它只会让每次抓取白跑一趟（v7-P3-2）
                SwitchRow(
                    title = s.deepCatch,
                    subtitle = s.deepCatchDesc,
                    checked = settings.deepCatchEnabled,
                    enabled = browserAvailable,
                ) {
                    onUpdate(settings.copy(deepCatchEnabled = it))
                }
                if (settings.deepCatchEnabled) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        browserHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (browserAvailable) FlowDownColors.MutedForeground else FlowDownColors.Warning,
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 委托属性无法智能转换，先取一次局部变量，避免用 !!（v4-P3-4）
                val probedVersion = ffmpegVersion
                Text(
                    "${s.ffmpegStatus}: " + when {
                        !ffmpegProbed -> s.calculating
                        probedVersion != null -> s.ffmpegAvailable(probedVersion)
                        else -> s.ffmpegNotFound
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = FlowDownColors.MutedForeground,
                )
            }

            SettingsCard(
                title = s.speedLimit,
                description = s.speedLimitDesc,
                leading = { Icon(Icons.Default.Speed, contentDescription = null, tint = FlowDownColors.Primary, modifier = Modifier.size(20.dp)) },
            ) {
                Text(
                    if (settings.speedLimit == 0L) s.unlimited else s.speedLimitValue(formatBytes(settings.speedLimit)),
                    style = MaterialTheme.typography.bodySmall,
                    color = FlowDownColors.MutedForeground,
                )
                Spacer(Modifier.height(8.dp))
                val maxV = AppConstants.MAX_SPEED_LIMIT_BYTES
                FlowSlider(
                    value = settings.speedLimit.toFloat().coerceIn(0f, maxV.toFloat()),
                    onValueChange = { onUpdate(settings.copy(speedLimit = it.toLong())) },
                    valueRange = 0f..maxV.toFloat(),
                    inactiveTrackColor = FlowDownColors.Muted,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(s.unlimited, style = MaterialTheme.typography.labelSmall, color = FlowDownColors.MutedForeground)
                    Text(formatBytes(maxV) + "/s", style = MaterialTheme.typography.labelSmall, color = FlowDownColors.MutedForeground)
                }
            }

            SettingsCard(
                title = s.behaviorSettings,
                description = s.behaviorSettingsDesc,
                leading = { Icon(Icons.Default.Notifications, contentDescription = null, tint = FlowDownColors.Primary, modifier = Modifier.size(20.dp)) },
            ) {
                SwitchRow(s.autoStart, s.autoStartDesc, settings.autoStart) {
                    onUpdate(settings.copy(autoStart = it))
                }
                SwitchRow(s.clipboardWatch, s.clipboardWatchDesc, settings.clipboardWatch) {
                    onUpdate(settings.copy(clipboardWatch = it))
                }
                SwitchRow(s.showNotifications, s.showNotificationsDesc, settings.showNotifications) {
                    onUpdate(settings.copy(showNotifications = it))
                }
                SwitchRow(s.minimizeToTray, s.minimizeToTrayDesc, settings.minimizeToTray) {
                    onUpdate(settings.copy(minimizeToTray = it))
                }
                SwitchRow(sd.title, sd.desc, settings.shutdownAfterComplete) {
                    onUpdate(settings.copy(shutdownAfterComplete = it))
                }
                if (settings.shutdownAfterComplete) {
                    Text(
                        sd.risk,
                        style = MaterialTheme.typography.bodySmall,
                        color = FlowDownColors.Warning,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            SettingsCard(
                title = s.proxySettings,
                description = s.proxySettingsDesc,
                leading = { Icon(Icons.Default.Public, contentDescription = null, tint = FlowDownColors.Primary, modifier = Modifier.size(20.dp)) },
            ) {
                Text(n.proxyMode, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.Foreground)
                Spacer(Modifier.height(6.dp))
                SettingsDropdown(
                    value = settings.proxyMode,
                    options = ProxyMode.values().toList(),
                    label = { n.proxyModeLabel(it) },
                    onSelect = { onUpdate(settings.copy(proxyMode = it)) },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when (settings.proxyMode) {
                        ProxyMode.DIRECT -> n.proxyModeDirectDesc
                        ProxyMode.MANUAL -> n.proxyModeManualDesc
                        ProxyMode.SYSTEM -> n.proxyModeSystemDesc
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = FlowDownColors.MutedForeground,
                )
                if (settings.proxyMode == ProxyMode.MANUAL) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(2f)) {
                            Text(s.proxyHost, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            CompactTextField(
                                value = settings.proxyHost,
                                onValueChange = { onUpdate(settings.copy(proxyHost = it)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "127.0.0.1",
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(s.proxyPort, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            CompactTextField(
                                value = settings.proxyPort.toString(),
                                onValueChange = { v -> onUpdate(settings.copy(proxyPort = v.toIntOrNull() ?: 1080)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "1080",
                            )
                        }
                    }
                }
                if (settings.proxyMode == ProxyMode.SYSTEM) {
                    Spacer(Modifier.height(12.dp))
                    var refreshToken by remember { mutableStateOf(0) }
                    var inspect by remember { mutableStateOf<PlatformProxyDetector.Inspect?>(null) }
                    // 探测会起子进程（scutil / reg / gsettings），必须放到 IO 线程
                    LaunchedEffect(refreshToken) {
                        inspect = withContext(Dispatchers.IO) { PlatformProxyDetector.current() }
                    }
                    Text(
                        text = when (val value = inspect) {
                            null -> n.proxyDetecting
                            is PlatformProxyDetector.Inspect.Proxy -> n.proxyDetected(
                                value.host, value.port, if (value.socks) "SOCKS" else "HTTP",
                            )
                            PlatformProxyDetector.Inspect.Direct -> n.proxyNotDetected
                            PlatformProxyDetector.Inspect.PacUnsupported -> n.proxyPacUnsupported
                            is PlatformProxyDetector.Inspect.Unavailable -> n.proxyUnavailable(value.reason)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = FlowDownColors.MutedForeground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        PlatformProxyDetector.invalidate()
                        refreshToken++
                    }) {
                        Text(n.refreshProxy)
                    }
                }
            }

            SettingsCard(
                title = n.advancedTitle,
                description = n.advancedDesc,
                leading = { Icon(Icons.Default.Speed, contentDescription = null, tint = FlowDownColors.Primary, modifier = Modifier.size(20.dp)) },
            ) {
                SwitchRow(n.http2Title, n.http2Desc, settings.http2Enabled) {
                    onUpdate(settings.copy(http2Enabled = it))
                }
            }

            SettingsCard(
                title = e.experimentalTitle,
                description = e.experimentalDesc,
                leading = { Icon(Icons.Default.Speed, contentDescription = null, tint = FlowDownColors.Primary, modifier = Modifier.size(20.dp)) },
            ) {
                SwitchRow(e.multiAccountTitle, e.multiAccountDesc, settings.multiAccountDownload) {
                    // 关掉 C1 时联动关掉 C2：C2 的备选直链来自账号轮换池，没有 C1 就没有池
                    onUpdate(
                        settings.copy(
                            multiAccountDownload = it,
                            multiSourceDownload = if (it) settings.multiSourceDownload else false,
                        ),
                    )
                }
                Text(
                    e.multiAccountRisk,
                    style = MaterialTheme.typography.bodySmall,
                    color = FlowDownColors.MutedForeground,
                    modifier = Modifier.padding(top = 8.dp),
                )
                SwitchRow(
                    e.multiSourceTitle,
                    e.multiSourceDesc,
                    settings.multiSourceDownload,
                    enabled = settings.multiAccountDownload,
                ) {
                    onUpdate(settings.copy(multiSourceDownload = it && settings.multiAccountDownload))
                }
                Text(
                    e.multiSourceRisk,
                    style = MaterialTheme.typography.bodySmall,
                    color = FlowDownColors.MutedForeground,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            SettingsCard(
                title = s.lanService,
                description = s.lanServiceDesc,
                leading = { Icon(Icons.Default.Wifi, contentDescription = null, tint = FlowDownColors.Primary, modifier = Modifier.size(20.dp)) },
            ) {
                SwitchRow(s.enableWebUpload, s.enableWebUploadDesc, settings.lanServerEnabled) {
                    onUpdate(settings.copy(lanServerEnabled = it))
                }
                if (settings.lanServerEnabled) {
                    Spacer(Modifier.height(12.dp))
                    Text(s.servicePort, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    val scope = rememberCoroutineScope()
                    // 以 settings.lanServerPort 为 key：端口从别处变化时输入框跟着同步（v4-P3-8）
                    var portText by remember(settings.lanServerPort) {
                        mutableStateOf(settings.lanServerPort.toString())
                    }
                    // Job 是句柄不是状态：用非快照容器持有，避免每次赋值都触发重组
                    val revertJobRef = remember { arrayOfNulls<Job>(1) }
                    CompactTextField(
                        value = portText,
                        onValueChange = { text ->
                            portText = text
                            val port = text.toIntOrNull()
                            if (port != null && port in 1..65535) {
                                revertJobRef[0]?.cancel()
                                onUpdate(settings.copy(lanServerPort = port))
                            } else {
                                revertJobRef[0]?.cancel()
                                revertJobRef[0] = scope.launch {
                                    delay(AppConstants.PORT_INPUT_REVERT_MS)
                                    portText = settings.lanServerPort.toString()
                                }
                            }
                        },
                        modifier = Modifier.width(128.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                SwitchRow(s.deviceDiscovery, s.deviceDiscoveryDesc, settings.lanDiscoveryEnabled) {
                    onUpdate(settings.copy(lanDiscoveryEnabled = it))
                }
            }

            SettingsCard(
                title = s.appearance,
                description = s.appearanceDesc,
                leading = { Icon(Icons.Default.Palette, contentDescription = null, tint = FlowDownColors.Primary, modifier = Modifier.size(20.dp)) },
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(s.theme, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        SettingsDropdown(
                            value = settings.theme,
                            options = listOf("dark", "light", "system"),
                            label = { v ->
                                when (v) {
                                    "dark" -> s.themeDark
                                    "light" -> s.themeLight
                                    else -> s.themeSystem
                                }
                            },
                            onSelect = { onUpdate(settings.copy(theme = it)) },
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp), tint = FlowDownColors.MutedForeground)
                            Spacer(Modifier.size(8.dp))
                            Text(s.language, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        SettingsDropdown(
                            value = settings.language,
                            options = listOf("zh-CN", "en-US"),
                            label = { v -> if (v == "zh-CN") s.langZhCN else s.langEnUS },
                            onSelect = { onUpdate(settings.copy(language = it)) },
                        )
                    }
                }
            }

            DataToolsSection(
                strings = dt,
                settings = settings,
                accountStore = accountStore,
            )

            SettingsCard(
                title = s.logs,
                description = s.logsDesc,
                leading = { Icon(Icons.Default.BugReport, contentDescription = null, tint = FlowDownColors.Primary, modifier = Modifier.size(20.dp)) },
            ) {
                CompactButton(
                    text = s.openLogFolder,
                    onClick = { AppLogger.openLogDirectory() },
                    icon = Icons.Default.FolderOpen,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    s.logFolderPath(AppConstants.LOG_DIR.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = FlowDownColors.MutedForeground,
                )
            }

            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.version(APP_VERSION), style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
                Text(s.appSubtitle, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
                Spacer(Modifier.height(8.dp))
                CompactButton(
                    text = s.openSourceLicenses,
                    onClick = { showLicenses = true },
                    icon = Icons.Default.Description,
                )
            }
        }

        // 应用内第三方署名：AGPL-3.0 §5(a)/§13 要求的「显著署名」必须让最终用户看得到，
        // 只在仓库根目录放一份 THIRD-PARTY-NOTICES.md 不够
        if (showLicenses) {
            OpenSourceLicensesDialog(
                strings = s.licenses,
                onDismiss = { showLicenses = false },
            )
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    description: String,
    leading: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FlowDownColors.Card)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                leading()
                Spacer(Modifier.size(8.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = FlowDownColors.Foreground)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
                }
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                // 禁用时整行一起变灰：只灰开关、文字照旧，会让人以为开关坏了
                color = if (enabled) FlowDownColors.Foreground else FlowDownColors.MutedForeground,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = FlowDownColors.MutedForeground)
        }
        CompactSwitch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

/** 下拉选择器：弹出菜单宽度与触发器保持一致。 */
@Composable
private fun <T> SettingsDropdown(
    value: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableStateOf(0) }
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { anchorWidth = it.size.width },
    ) {
        DropdownTrigger(text = label(value), onClick = { open = true })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.width(with(LocalDensity.current) { anchorWidth.toDp() }),
        ) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) }, onClick = {
                    onSelect(option)
                    open = false
                })
            }
        }
    }
}

/** 设置页统一的下拉触发器：36dp 高、中性底色，避免整页都是主色大按钮。 */
@Composable
private fun DropdownTrigger(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(36.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = FlowDownColors.Muted,
            contentColor = FlowDownColors.Foreground,
        ),
        contentPadding = PaddingValues(start = 12.dp, end = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
        Icon(
            Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = FlowDownColors.MutedForeground,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 预览：设置页（约 800×960） */
@Preview
@Composable
private fun PreviewSettingsScreen() {
    FlowDownTheme {
        Box(Modifier.size(800.dp, 960.dp)) {
            SettingsScreen(
                settings = defaultSettings(),
                onUpdate = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

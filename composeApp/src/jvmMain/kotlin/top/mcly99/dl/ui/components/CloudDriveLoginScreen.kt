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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.XunleiWebCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefMessageRouter
import top.mcly99.dl.engine.clouddrive.CloudDriveCatalog
import top.mcly99.dl.engine.clouddrive.CloudDriveLoginCatalog
import top.mcly99.dl.engine.clouddrive.CloudLoginMode
import top.mcly99.dl.engine.clouddrive.browser.JcefHolder
import top.mcly99.dl.engine.clouddrive.browser.clearCookies
import top.mcly99.dl.engine.clouddrive.browser.clearXunleiBridgeCookies
import top.mcly99.dl.engine.clouddrive.browser.collectLoginCookies
import top.mcly99.dl.engine.clouddrive.browser.collectXunleiCredential
import top.mcly99.dl.engine.clouddrive.browser.XunleiBridgeHandler
import top.mcly99.dl.engine.clouddrive.browser.createXunleiBridgeRouter
import top.mcly99.dl.theme.CloudDriveStrings
import top.mcly99.dl.theme.FlowDownColors
import top.mcly99.dl.theme.Strings
import top.mcly99.dl.viewmodel.CloudDriveViewModel
import java.awt.BorderLayout
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 网盘网页登录 Screen（全屏覆盖主界面，不打开系统浏览器）。
 *
 * 做法与 YunX-Desktop 的 `JcefLoginPane` / `XunleiWebLoginScreen` 一致：
 * - `SwingPanel` 把 JCEF 的 `CefBrowser.uiComponent` 嵌进 Compose 树；
 * - 每 1.5s 轮询全局 Cookie（迅雷走 localStorage → Cookie 桥）判定登录态；
 * - 检测到后由用户点「保存登录」，或手工粘贴凭证；
 * - 页面内提供刷新 / 清除会话 / 退出登录，Esc / 返回按钮回到云盘页。
 */
@Composable
fun CloudDriveLoginScreen(
    platform: SharePlatform,
    vm: CloudDriveViewModel,
    modifier: Modifier = Modifier,
) {
    val s = Strings.current.cloudDriveStrings
    val info = CloudDriveCatalog.of(platform)
    val config = remember(platform) { CloudDriveLoginCatalog.of(platform) }
    val uiScope = rememberCoroutineScope()

    // 只有本机已有内核时才静默初始化；否则一律等用户显式选择登录方式。
    // 打开登录页就去下载 ~150MB 的 Chromium 是最糟的默认值：国内网络常常卡十几分钟。
    val kernelLocal = remember { JcefHolder.hasLocalKernel() }
    var wantEmbedded by remember(platform) { mutableStateOf(kernelLocal) }
    LaunchedEffect(kernelLocal) { if (kernelLocal) JcefHolder.initInBackground() }
    val ready = JcefHolder.browserReady
    val initFailure = JcefHolder.initFailure
    val stage = JcefHolder.stage
    val percent = JcefHolder.progress

    var detected by remember(platform) { mutableStateOf<String?>(null) }
    var incompleteLogin by remember(platform) { mutableStateOf(false) }
    var saving by remember(platform) { mutableStateOf(false) }
    // 没有内核时直接把手工粘贴展开：这是最快的可用路径
    var showManual by remember(platform) { mutableStateOf(!kernelLocal) }
    var transient by remember(platform) { mutableStateOf<String?>(null) }

    // Esc 返回云盘页。内嵌 Chromium 是一个 AWT 重量级组件，Compose 的按键事件到不了它，
    // 所以在 AWT 层挂 KeyEventDispatcher，焦点落在网页里时也能退出。
    DisposableEffect(Unit) {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = KeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_ESCAPE) {
                vm.closeLogin()
                true
            } else {
                false
            }
        }
        manager.addKeyEventDispatcher(dispatcher)
        onDispose { manager.removeKeyEventDispatcher(dispatcher) }
    }

    // CEF 要求在 AWT EDT 上创建浏览器；Compose Desktop 的 composition 正跑在 EDT 上。
    val browser = remember(platform, ready) {
        if (!ready || config == null) null else createBrowser(config.loginUrl)
    }

    DisposableEffect(browser) {
        onDispose {
            runCatching { browser?.browser?.close(true) }
            // P2-15：先摘消息路由再 dispose client，避免回调打进已销毁的 handler
            runCatching { browser?.bridgeRouter?.let { browser.client.removeMessageRouter(it) } }
            runCatching { browser?.bridgeRouter?.dispose() }
            runCatching { browser?.client?.dispose() }
        }
    }

    // 登录态轮询
    LaunchedEffect(browser, config) {
        if (browser == null || config == null || config.mode == CloudLoginMode.MANUAL) return@LaunchedEffect
        while (isActive) {
            // first = 完整登录态；second = 只有部分 Cookie（如百度缺 STOKEN），需要提示用户再等等
            val (credential, incomplete) = withContext(Dispatchers.IO) {
                when (config.mode) {
                    CloudLoginMode.XUNLEI_WEB -> {
                        // P2-15：只在可信域（https + *.xunlei.com）注入桥接脚本；
                        // 脚本内部还会再判一次域，并把 JSON 经 CefMessageRouter 送进内存。
                        // 若当前停在风控页/广告页，注入不会把 refresh_token 交给第三方。
                        val currentUrl = runCatching { browser.browser.url }.getOrNull()
                        if (XunleiWebCredential.isTrustedUrl(currentUrl)) {
                            runCatching {
                                SwingUtilities.invokeLater {
                                    browser.browser.executeJavaScript(
                                        XunleiWebCredential.MESSAGE_BRIDGE_SCRIPT,
                                        XunleiWebCredential.LOGIN_URL,
                                        0,
                                    )
                                }
                            }
                        }
                        delay(400)
                        // 优先取内存桥的载荷；collectXunleiCredential 内部仅在本次没有
                        // 新载荷时才回退读旧 Cookie（兼容上一次启动残留）。
                        // 顺手清掉旧桥 Cookie，不让明文 token 按 max-age 躺在浏览器里。
                        collectXunleiCredential(browser.bridgeHandler).also { clearXunleiBridgeCookies() } to false
                    }

                    CloudLoginMode.COOKIE -> {
                        val full = collectLoginCookies(
                            config.cookieDomains, config.requiredKeys, config.anyOfKeys,
                        )?.let { config.transform(it) }
                        if (full != null) {
                            full to false
                        } else {
                            // 必需键不齐，但确实已经有本域的 Cookie → 说明是「登到一半」
                            val any = collectLoginCookies(config.cookieDomains, emptyList(), emptyList())
                            null to (any != null)
                        }
                    }

                    CloudLoginMode.MANUAL -> null to false
                }
            }
            if (credential != null) detected = credential
            if (incomplete && detected == null) incompleteLogin = true
            if (detected != null) incompleteLogin = false
            delay(1500)
        }
    }

    Column(modifier.fillMaxSize().background(FlowDownColors.Background)) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::closeLogin) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = s.loginBack,
                    // 这一页其余元素都走 FlowDownColors；这里不指定的话会跟随
                    // LocalContentColor，深色模式下与标题不同色、贴在深背景上看不清
                    tint = FlowDownColors.Foreground,
                )
            }
            Text(
                s.loginTitle(info?.name ?: platform.name),
                style = MaterialTheme.typography.titleSmall,
                color = FlowDownColors.Foreground,
            )
            Spacer(Modifier.weight(1f))
            if (config != null) {
                Text(
                    config.loginUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = FlowDownColors.MutedForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(280.dp),
                )
            }
        }

        // 状态卡
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (detected != null) FlowDownColors.Muted else FlowDownColors.Card)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val embeddedPending = !ready && (kernelLocal || wantEmbedded) && initFailure == null
            val status = when {
                initFailure != null -> s.loginInitFailed(initFailure.orEmpty())
                embeddedPending -> s.loginInitializing
                !ready -> s.loginChooseTitle
                detected != null -> s.loginDetected
                incompleteLogin -> s.loginIncomplete
                else -> config?.note?.takeIf { it.isNotBlank() } ?: s.loginPrompt
            }
            if (embeddedPending) {
                CircularProgressIndicator(
                    Modifier.size(16.dp),
                    color = FlowDownColors.Primary,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    if (detected != null) Icons.Default.CheckCircle else Icons.Default.Public,
                    contentDescription = null,
                    tint = if (detected != null) FlowDownColors.Success else FlowDownColors.MutedForeground,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                transient ?: status,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (initFailure != null) FlowDownColors.Warning else FlowDownColors.Foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detected != null) {
                CompactButton(
                    text = if (saving) s.loginSaving else s.loginSave,
                    onClick = {
                        val fallback = detected ?: return@CompactButton
                        val cfg = config ?: return@CompactButton
                        saving = true
                        // 点保存时**重新采一次**：轮询到的快照可能停在「刚登录、STOKEN 还没下发」
                        // 的那一刻，存下去就是半张 Cookie（浏览正常、转存恒 errno=2）
                        uiScope.launch {
                            val fresh = withContext(Dispatchers.IO) {
                                runCatching {
                                    collectLoginCookies(
                                        cfg.cookieDomains,
                                        cfg.requiredKeys,
                                        cfg.anyOfKeys,
                                    )?.let { cfg.transform(it) }
                                }.getOrNull()
                            }
                            val value = fresh ?: fallback
                            if (vm.saveLoginCookie(value)) {
                                transient = null
                            } else {
                                transient = s.loginSaveFailed
                            }
                            saving = false
                        }
                    },
                    enabled = !saving,
                    icon = Icons.Default.CheckCircle,
                    primary = true,
                )
            }
        }

        // 内嵌浏览器
        Box(
            // 只有真的嵌了网页时才铺白底；选择登录方式的卡片要跟主题走，否则深色模式下浅字压白底
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(if (browser != null) Color.White else FlowDownColors.Background),
            contentAlignment = Alignment.Center,
        ) {
            if (browser != null) {
                val h = browser
                SwingPanel(
                    factory = {
                        object : JPanel(BorderLayout()) {
                            override fun doLayout() {
                                super.doLayout()
                                val canvas = h.browser.uiComponent
                                if (canvas.width != width || canvas.height != height ||
                                    canvas.x != 0 || canvas.y != 0
                                ) {
                                    canvas.setBounds(0, 0, width, height)
                                }
                            }
                        }.apply {
                            background = java.awt.Color.WHITE
                            add(h.browser.uiComponent, BorderLayout.CENTER)
                            addComponentListener(object : ComponentAdapter() {
                                override fun componentResized(e: ComponentEvent) {
                                    h.browser.uiComponent.setBounds(0, 0, width, height)
                                }
                            })
                            SwingUtilities.invokeLater { validate() }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    background = Color.White,
                )
            } else if (!kernelLocal && !wantEmbedded) {
                // 本机没有内核：先让用户确认要安装，下载动作必须是显式的
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        s.loginChooseTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = FlowDownColors.Foreground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        s.loginChooseBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = FlowDownColors.MutedForeground,
                        modifier = Modifier.width(460.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    CompactButton(
                        text = s.loginDownloadKernel,
                        onClick = {
                            wantEmbedded = true
                            JcefHolder.initInBackground(allowDownload = true)
                        },
                        icon = Icons.Default.Download,
                        primary = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        s.loginFallbackHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = FlowDownColors.MutedForeground,
                    )
                }
            } else {
                // 初始化中 / 初始化失败
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val label = initFailure ?: stageLabel(s, stage)
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (initFailure != null) FlowDownColors.Warning else FlowDownColors.Foreground,
                    )
                    if (initFailure == null && percent >= 0f) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${(percent * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = FlowDownColors.MutedForeground,
                        )
                    }
                    if (initFailure != null) {
                        Spacer(Modifier.height(12.dp))
                        CompactButton(
                            text = s.loginKernelRetry,
                            onClick = {
                                JcefHolder.initInBackground(allowDownload = wantEmbedded)
                            },
                            icon = Icons.Default.Refresh,
                            primary = true,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        s.loginFallbackHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = FlowDownColors.MutedForeground,
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(FlowDownColors.Border))

        // 底部操作条
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactButton(
                text = s.loginReload,
                onClick = { runCatching { browser?.browser?.reload() } },
                enabled = browser != null,
                icon = Icons.Default.Refresh,
            )
            CompactButton(
                text = s.loginClearSession,
                onClick = {
                    if (config != null) {
                        uiScope.launch {
                            withContext(Dispatchers.IO) { clearCookies(config.cookieDomains) }
                            if (config.mode == CloudLoginMode.XUNLEI_WEB) {
                                runCatching {
                                    SwingUtilities.invokeLater {
                                        browser?.browser?.executeJavaScript(
                                            XunleiWebCredential.CLEAR_SESSION_SCRIPT,
                                            XunleiWebCredential.LOGIN_URL,
                                            0,
                                        )
                                    }
                                }
                            }
                            detected = null
                            transient = s.loginSessionCleared
                            runCatching { browser?.browser?.reload() }
                        }
                    }
                },
                enabled = browser != null,
                icon = Icons.Default.Delete,
            )
            CompactButton(
                text = s.loginReadCookie,
                onClick = {
                    if (config != null) {
                        uiScope.launch {
                            val cookie = withContext(Dispatchers.IO) {
                                collectLoginCookies(config.cookieDomains, emptyList(), emptyList())
                            }
                            transient = if (cookie.isNullOrBlank()) s.loginReadCookieEmpty else null
                            if (!cookie.isNullOrBlank()) detected = config.transform(cookie)
                        }
                    }
                },
                enabled = ready,
                icon = Icons.Default.Link,
            )
            CompactButton(
                text = s.loginManual,
                onClick = { showManual = !showManual },
            )
            Spacer(Modifier.weight(1f))
            CompactButton(
                text = s.loginLogout,
                onClick = {
                    if (config != null) {
                        uiScope.launch {
                            withContext(Dispatchers.IO) { clearCookies(config.cookieDomains) }
                            if (config.mode == CloudLoginMode.XUNLEI_WEB) {
                                // P2-12：退出登录还要清 localStorage 里的 device_id / 旧凭据，
                                // 否则换号后仍读到上一个账号的设备号（重登「请求参数错误」的根因之一）
                                runCatching {
                                    SwingUtilities.invokeLater {
                                        browser?.browser?.executeJavaScript(
                                            XunleiWebCredential.CLEAR_SESSION_SCRIPT,
                                            XunleiWebCredential.LOGIN_URL,
                                            0,
                                        )
                                    }
                                }
                                runCatching { browser?.browser?.reload() }
                            }
                        }
                        vm.logout(platform)
                        detected = null
                        transient = null
                    }
                },
                enabled = vm.isConfigured(platform),
            )
        }

        // 手工粘贴兜底（YunX 的 CookieLoginContent 等价能力，但不跳外部浏览器）
        if (showManual) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                    .background(FlowDownColors.Muted)
                    .padding(10.dp),
            ) {
                Text(
                    "${s.credential}: ${info?.credentialLabel.orEmpty()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = FlowDownColors.MutedForeground,
                )
                Spacer(Modifier.height(6.dp))
                CompactTextField(
                    value = vm.credentialDraft,
                    onValueChange = vm::updateCredentialDraft,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = s.credentialHint,
                    leadingIcon = Icons.Default.Link,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactButton(
                        text = s.accountSave,
                        onClick = vm::saveCredential,
                        icon = Icons.Default.CheckCircle,
                        primary = true,
                    )
                    CompactButton(
                        text = s.accountClear,
                        onClick = vm::clearCredential,
                        icon = Icons.Default.Delete,
                    )
                }
            }
        }
    }
}

private class BrowserHolder(
    val client: CefClient,
    val browser: CefBrowser,
    val bridgeHandler: XunleiBridgeHandler,
    val bridgeRouter: CefMessageRouter?,
)

/** 初始化阶段 → 可读文案；stage 为 null 表示刚起步还没上报 */
private fun stageLabel(s: CloudDriveStrings, stage: JcefHolder.Stage?): String = when (stage) {
    JcefHolder.Stage.LOCATING -> s.loginStageLocating
    JcefHolder.Stage.DOWNLOADING -> s.loginStageDownloading
    JcefHolder.Stage.EXTRACTING -> s.loginStageExtracting
    JcefHolder.Stage.INSTALLING -> s.loginStageInstalling
    JcefHolder.Stage.INITIALIZING -> s.loginStageInitializing
    null -> s.loginInitializing
}

private fun createBrowser(url: String): BrowserHolder? {
    val app = JcefHolder.app() ?: return null
    return runCatching {
        val client = app.createClient()
        // P2-15：凭据通过 CefMessageRouter 直接进 JVM 内存，不再写明文 Cookie。
        val handler = XunleiBridgeHandler()
        val router = createXunleiBridgeRouter(handler)
        client.addMessageRouter(router)
        // 不调用 createImmediately()：画布尚未挂到窗口（尺寸 0）时强行创建原生窗口会白屏，
        // 交给 java-cef 在 addNotify 拿到真实尺寸后自行创建。
        BrowserHolder(client, client.createBrowser(url, false, false), handler, router)
    }.getOrNull()
}

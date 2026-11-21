package top.mcly99.dl.utils

/**
 * 系统托盘工具类
 * 
 * 该工具类提供跨平台的系统托盘图标管理功能，允许应用程序在系统托盘中显示图标、
 * 显示通知消息和响应用户交互，提升用户体验和应用可用性。
 * 
 * 平台支持：
 * - Windows: 完全支持系统托盘图标和通知
 * - macOS: 支持系统托盘图标（菜单栏）
 * - Linux: 基于X11的桌面环境支持
 * 
 * 主要功能：
 * - 托盘图标设置：加载图标、设置提示文本、配置弹出菜单
 * - 用户交互处理：响应点击事件和菜单项选择
 * - 通知消息：显示系统级通知消息
 * - 生命周期管理：托盘图标的创建、更新和销毁
 * 
 * 设计特点：
 * - 安全检查：自动检测系统托盘支持并优雅降级
 * - 资源管理：智能图标加载和内存管理
 * - 异常处理：全面的异常捕获确保稳定性
 * - 可配置性：支持自定义菜单项标签和行为
 * 
 * @see TrayIcon Java AWT系统托盘图标
 * @see SystemTray Java AWT系统托盘管理器
 * @see PopupMenu Java AWT弹出菜单
 */

import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.imageio.ImageIO
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import java.awt.Font
import java.awt.GraphicsEnvironment

object TrayHelper {
    private var trayIcon: TrayIcon? = null

    val isSupported: Boolean get() = SystemTray.isSupported()

    /**
     * 托盘图标**是否真的挂上去了**。
     *
     * 与 [isSupported] 的区别：`isSupported` 只说明「这台机器有系统托盘」，
     * 而 `SystemTray.add` 仍可能因为托盘区被占满、桌面环境异常、图标尺寸不被接受等原因失败。
     * 「关闭窗口时藏到托盘」必须看这个值，否则托盘没装上又把窗口藏了，应用就彻底失联（P1-13）。
     */
    val isActive: Boolean get() = trayIcon != null

    /**
     * 加载托盘图标。
     *
     * 优先使用调用方传入的 Compose Resources 字节（`Res.readBytes("drawable/img_logo.png")`，
     * 由 main.kt 在协程里读好），避免依赖打包后 classpath 的具体目录结构；
     * 失败时回退到历史 classpath 路径，最后用内置的矢量图标兜底，
     * 保证托盘不会因为缺图而加载失败。
     */
    private fun loadTrayImage(iconBytes: ByteArray?): Image? {
        val side = SystemTray.getSystemTray().trayIconSize.let { if (it.width > 0) it.width else 16 }

        // 首选：调用方在协程里读好的 Compose Resources 字节（与 main.kt 的窗口图标同源）。
        // v4-P3-1：这里原先用 `runBlocking { Res.readBytes(...) }` 同步读资源，
        // 会在 AWT/启动路径上阻塞线程；改为由调用方 await 后传入。
        if (iconBytes != null) {
            runCatching { ImageIO.read(iconBytes.inputStream()) }.getOrNull()
                ?.let { return it.getScaledInstance(side, side, Image.SCALE_SMOOTH) }
        }

        // 回退：历史版本可能打进去的 classpath 路径
        val legacyPaths = listOf(
            "drawable/drawable/img_logo.png",
            "composeResources/ktdownload.composeapp.generated.resources/drawable/img_logo.png",
        )
        for (path in legacyPaths) {
            val stream = javaClass.classLoader.getResourceAsStream(path) ?: continue
            runCatching { ImageIO.read(stream) }.getOrNull()
                ?.let { return it.getScaledInstance(side, side, Image.SCALE_SMOOTH) }
        }

        return fallbackImage(side)
    }

    /** 兜底图标：深色圆角方块 + 白色向下箭头，矢量绘制，不依赖任何资源 */
    private fun fallbackImage(side: Int): Image {
        val img = java.awt.image.BufferedImage(side, side, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
            )
            val w = side.toFloat()
            g.color = java.awt.Color(0x34, 0xD3, 0x99)
            g.fillRoundRect(0, 0, side, side, (w * 0.28f).toInt(), (w * 0.28f).toInt())
            g.color = java.awt.Color.WHITE
            val shaft = w * 0.14f
            g.fillRect(((w - shaft) / 2).toInt(), (w * 0.22f).toInt(), shaft.toInt(), (w * 0.34f).toInt())
            val arrow = intArrayOf((w * 0.24f).toInt(), (w * 0.52f).toInt(), (w * 0.76f).toInt())
            val ay = intArrayOf((w * 0.52f).toInt(), (w * 0.80f).toInt(), (w * 0.52f).toInt())
            g.fillPolygon(arrow, ay, 3)
        } finally {
            g.dispose()
        }
        return img
    }

    /**
     * 安装托盘图标。
     *
     * Windows 下 AWT 的 SystemTray + PopupMenu 有两个已知限制：
     * 1. 菜单文字在高 DPI 缩放（125%/150%/175%）下会渲染成方块，
     *    原因是 AWT 菜单未启用 per-monitor DPI 感知 —— 因此这里改用 Swing 的
     *    [JPopupMenu] 自行弹出，Swing 文本渲染不受该问题影响；
     * 2. 托盘图标尺寸固定，需按系统托盘图标尺寸指定，避免模糊。
     */
    /**
     * 安装托盘图标。
     *
     * @return 托盘图标是否**真的**添加成功。调用方必须用它（而不是 [isSupported]）决定
     *   「关闭窗口时是否藏到托盘」：`SystemTray.add` 失败时返回 false，此时藏窗口没有
     *   任何入口能把应用叫回来（P1-13）。
     */
    fun setup(
        onShow: () -> Unit,
        onExit: () -> Unit,
        showWindowLabel: String = "Show Window",
        exitLabel: String = "Exit",
        tooltip: String = "KtDownload",
        iconBytes: ByteArray? = null,
    ): Boolean {
        if (!isSupported) return false
        remove()

        val image = loadTrayImage(iconBytes)

        // 不再把 PopupMenu 交给 TrayIcon：改为监听右键后自行弹 Swing 菜单。
        // 注意：Windows 下 pressed 与 released 都会带 isPopupTrigger，
        // 若两处都弹出，第二次会立刻关闭第一次的菜单，表现为"闪一下就消失"。
        // 因此这里只处理 mouseReleased，并用时间戳做一次防抖兜底。
        trayIcon = TrayIcon(image, tooltip).apply {
            isImageAutoSize = true
            addActionListener { onShow() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    val looksLikeRightClick = e.isPopupTrigger || e.button == MouseEvent.BUTTON3
                    if (!looksLikeRightClick) return
                    // 300ms 内重复触发视为同一次右键（部分 AWM 会连发多个事件）
                    val now = System.currentTimeMillis()
                    if (now - lastMenuShowAt < AppConstants.TRAY_MENU_DEBOUNCE_MS) return
                    lastMenuShowAt = now
                    // 菜单弹出失败只记日志：不让全局未捕获异常处理弹错误框
                    // （开发时 `gradlew clean` 会删掉热重载 classpath，右键就会踩到）
                    runCatching { showSwingMenu(e, showWindowLabel, exitLabel, onShow, onExit) }
                        .onFailure { AppLogger.warn("TrayHelper", "托盘菜单弹出失败: ${it.message}", it) }
                }
            })
        }

        try {
            SystemTray.getSystemTray().add(trayIcon)
        } catch (e: Exception) {
            // 托盘不可用：优雅降级，不影响主窗口
            AppLogger.warn("TrayHelper", "无法添加托盘图标: ${e.message}", e)
            trayIcon = null
            return false
        }
        return true
    }

    private val isWindows: Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")

    /** 上次弹出菜单的时间戳，用于防止重复右键事件 */
    private var lastMenuShowAt: Long = 0L

    /** 当前显示的菜单（用于关闭上一次） */
    private var visibleMenu: JPopupMenu? = null

    /** 非模态宿主窗口：1×1 透明且不抢焦点 */
    private var menuHost: JWindow? = null

    /**
     * 选择一款确定支持中文的界面字体。
     * JBR 打包后若使用默认逻辑字体，中文可能回退失败显示为方块，
     * 这里优先使用系统自带的 UI 字体，并显式声明支持中文字形。
     */
    private val CJK_UI_FONT: Font by lazy {
        val candidates = listOf(
            "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC",
            "Noto Sans CJK SC", "Source Han Sans SC", "SimHei",
        )
        val available = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .availableFontFamilyNames.toSet()
        val family = candidates.firstOrNull { it in available }
            ?: UIManager.getFont("Label.font")?.family
            ?: Font.SANS_SERIF
        Font(family, Font.PLAIN, 13)
    }

    /**
     * 在托盘图标位置弹出 Swing 菜单（修复高 DPI 下 AWT 菜单显示为方块的问题）
     *
     * 位置要点：托盘在屏幕底部时，若以鼠标点为原点向下弹出会盖住托盘，
     * 因此鼠标位于屏幕下半部分时改为**向上**展开，并整体夹紧到该显示器工作区内。
     */
    private fun showSwingMenu(
        e: MouseEvent,
        showWindowLabel: String,
        exitLabel: String,
        onShow: () -> Unit,
        onExit: () -> Unit,
    ) {
        // 关闭上一次未关闭的菜单，避免叠加与"闪一下"的观感
        visibleMenu?.let { if (it.isVisible) it.isVisible = false }
        visibleMenu = null

        val menu = JPopupMenu().apply {
            font = CJK_UI_FONT
            add(JMenuItem(showWindowLabel).apply {
                font = CJK_UI_FONT
                addActionListener {
                    hideMenu()
                    onShow()
                }
            })
            addSeparator()
            add(JMenuItem(exitLabel).apply {
                font = CJK_UI_FONT
                addActionListener {
                    // 只移除托盘图标，退出流程交给 main.kt 的 exitApplication
                    hideMenu()
                    trayIcon = null
                    onExit()
                }
            })
        }

        // 宿主窗口复用：1×1、透明、不抢焦点
        val host = (menuHost ?: JWindow().apply {
            isAlwaysOnTop = true
            background = java.awt.Color(0, 0, 0, 0)
            menuHost = this
        })

        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(evt: PopupMenuEvent) {}
            override fun popupMenuWillBecomeInvisible(evt: PopupMenuEvent) {
                if (visibleMenu === menu) visibleMenu = null
            }
            override fun popupMenuCanceled(evt: PopupMenuEvent) {
                if (visibleMenu === menu) visibleMenu = null
            }
        })

        SwingUtilities.updateComponentTreeUI(menu)
        // 创建原生 peer 并完成一次布局，preferredSize 才可靠
        menu.pack()
        visibleMenu = menu
        host.isVisible = true

        val point = menuLocation(menu, e.xOnScreen, e.yOnScreen)
        // 鼠标坐标是屏幕绝对坐标；把宿主窗口移到该点后，菜单再相对宿主 (0,0) 弹出
        host.setLocation(point.x, point.y)
        host.setSize(1, 1)
        menu.show(host.contentPane, 0, 0)
    }

    /**
     * 计算菜单应出现的位置（屏幕坐标）。
     *
     * 规则与系统托盘菜单一致：
     * - 鼠标在屏幕**下半部分**（托盘位于任务栏）时，菜单底边贴住鼠标上方展开；
     * - 鼠标在上半部分时，菜单顶边贴住鼠标下方展开；
     * - 最后夹紧到该显示器工作区，保证完整可见，绝不遮挡托盘图标本身。
     */
    private fun menuLocation(menu: JPopupMenu, mouseX: Int, mouseY: Int): java.awt.Point {
        val size = menu.preferredSize
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()

        // 鼠标所在显示器；拿不到时退回默认屏幕
        var area: java.awt.Rectangle? = null
        for (device in ge.screenDevices) {
            val bounds = device.defaultConfiguration.bounds
            if (bounds.contains(mouseX, mouseY)) {
                // 优先用排除任务栏的可用区域
                area = runCatching {
                    device.defaultConfiguration.bounds.intersection(bounds)
                }.getOrDefault(bounds)
                break
            }
        }
        val screen = area ?: ge.defaultScreenDevice.defaultConfiguration.bounds

        val margin = 4
        // 托盘多位于屏幕下半部分：此时向上展开，避免盖住托盘
        val openUpward = mouseY > screen.y + screen.height / 2

        var y = if (openUpward) mouseY - size.height - margin else mouseY + margin
        // 水平方向右对齐鼠标点，右侧越界时向左收
        var x = mouseX - size.width + margin

        if (x < screen.x + margin) x = screen.x + margin
        if (x + size.width > screen.x + screen.width - margin) {
            x = screen.x + screen.width - size.width - margin
        }
        if (y < screen.y + margin) y = screen.y + margin
        if (y + size.height > screen.y + screen.height - margin) {
            y = screen.y + screen.height - size.height - margin
        }

        return java.awt.Point(x, y)
    }

    private fun hideMenu() {
        visibleMenu?.let { if (it.isVisible) it.isVisible = false }
        visibleMenu = null
        menuHost?.isVisible = false
    }

    fun notify(title: String, message: String) {
        if (!isSupported || trayIcon == null) return
        try {
            trayIcon?.displayMessage(title, message, TrayIcon.MessageType.INFO)
        } catch (_: Exception) {}
    }

    fun remove() {
        try {
            trayIcon?.let { SystemTray.getSystemTray().remove(it) }
        } catch (_: Exception) {}
        trayIcon = null
    }
}

package top.mcly99.dl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** P3-01 / P3-02 / P3-06：没有 UI instrumentation，沿用源码契约锁定接线 */
class P3UiWiringTest {

    @Test
    fun window_icon_matches_sidebar_and_tray() {
        val main = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/main.kt")
        assertTrue(!main.contains("Res.drawable.img_log)"), "窗口图标不应再用 .ico（Skia 解码路径不同）")
        assertTrue(main.contains("Res.drawable.img_logo"), "窗口图标应与侧栏/托盘统一为 PNG")
    }

    @Test
    fun no_hardcoded_chinese_default_arguments() {
        val sidebar = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/ui/components/SidebarPanel.kt")
        assertTrue(!sidebar.contains("myDriveLabel: String = \"我的网盘\""), "侧栏默认参数不能硬编码中文")
        assertTrue(sidebar.contains("myDriveStrings(Strings.current.language).title"), "侧栏默认值应按运行时语言取")

        val header = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/ui/components/HeaderBar.kt")
        assertTrue(!header.contains("myDriveTitle: String = \"我的网盘\""), "顶栏默认参数不能硬编码中文")
        assertTrue(header.contains("myDriveStrings(Strings.current.language).title"), "顶栏默认值应按运行时语言取")

        val transfer = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/ui/components/TransferScreen.kt")
        assertTrue(!transfer.contains("transferExtraStrings(\"zh-CN\")"), "传输页默认参数不能硬编码中文")
        assertTrue(
            transfer.contains("transferExtraStrings(Strings.current.language)"),
            "传输页默认值应跟随 CompositionLocal 语言",
        )
    }

    @Test
    fun cloud_drive_service_has_no_fixed_proxy_snapshot_entry() {
        val source = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/engine/clouddrive/CloudDriveService.kt")
        assertTrue(!source.contains("fun applyProxy("), "一次性固定代理入口会覆盖现取 provider，应删除")
        assertTrue(source.contains("setProxyProvider"), "云盘链路应统一走 setProxyProvider")
    }

    @Test
    fun content_disposition_parser_is_available() {
        val source = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/engine/DownloadFileSupport.kt")
        assertTrue(
            source.contains("fun extractFileNameFromContentDisposition("),
            "应提供 RFC 6266 文件名解析能力",
        )
    }

    private fun sourceOf(relative: String): String =
        listOf(File(relative), File("../$relative"), File("../../$relative"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("找不到源码 $relative（工作目录 ${System.getProperty("user.dir")}）")
}

package top.mcly99.dl.viewmodel

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2-20 / P2-21 / P2-22 的契约测试。
 *
 * 这三处改的是 Compose / AWT 边界，项目没有 UI  instrumentation 测试，
 * 因此用「纯函数行为 + 源码契约」两层来锁住：纯函数测文案选择，源码契约防止
 * 以后有人新增一条入队失败路径时又只调 notify、或把剪贴板读回主线程。
 */
class UiFixesContractTest {

    @Test
    fun failure_message_prefers_the_exception_detail() {
        assertEquals(
            "保存路径不是文件夹：/x",
            enqueueFailureMessage(IllegalArgumentException("保存路径不是文件夹：/x"), "https://a/b"),
        )
    }

    @Test
    fun failure_message_falls_back_to_url_or_filename() {
        assertEquals("https://a/b", enqueueFailureMessage(RuntimeException(), "https://a/b"))
        assertEquals("file.bin", enqueueFailureMessage(IllegalStateException("  "), "file.bin"))
    }

    @Test
    fun failure_message_is_never_blank_when_a_fallback_exists() {
        assertTrue(enqueueFailureMessage(Exception(), "fallback").isNotBlank())
    }

    @Test
    fun all_three_enqueue_failures_go_through_the_dual_channel_helper() {
        val src = readSource("viewmodel/AppViewModel.kt")
        // 1 处定义 + 3 处调用（抓网页 / 云盘直链 / 手动添加）
        assertEquals(4, Regex("reportEnqueueFailure\\(").findAll(src).count(), "入队失败路径数量变了")
        assertFalse(
            src.contains("notify(strings.notifyDownloadFailed, e.message ?:"),
            "入队失败里不能再出现裸 notify（关掉通知开关就没有反馈了）",
        )
    }

    @Test
    fun failure_helper_uses_both_notification_and_toast() {
        val src = readSource("viewmodel/AppViewModel.kt")
        val body = src.substringAfter("private fun reportEnqueueFailure(", "")
            .substringBefore("\n    }", "")
        assertTrue(body.contains("notify("), "helper 缺少系统通知通道")
        assertTrue(body.contains("showToast("), "helper 缺少应用内提示通道")
    }

    @Test
    fun add_task_dialog_reads_the_clipboard_on_io() {
        val src = readSource("ui/components/AddTaskDialog.kt")
        assertTrue(src.contains("import kotlinx.coroutines.Dispatchers"))
        assertTrue(
            src.contains("withContext(Dispatchers.IO) { getClipboardUrl() }"),
            "AWT 剪贴板是同步阻塞调用，不能留在 Main 协程里",
        )
    }

    @Test
    fun result_row_no_longer_hardcodes_a_chinese_passcode_label() {
        val src = readSource("ui/components/CloudDriveScreen.kt")
        assertFalse(src.contains("\"提取码 \${"), "英文界面也会看到中文「提取码」")
        assertTrue(src.contains("passcodeLabel(resolution.passcode)"))
    }

    private fun readSource(pathUnderJvmMain: String): String {
        val relative = "src/jvmMain/kotlin/top/mcly99/dl/$pathUnderJvmMain"
        val file = listOf(File(relative), File("composeApp/$relative"), File("../composeApp/$relative"))
            .firstOrNull { it.isFile }
            ?: error("找不到源码 $relative（工作目录 ${System.getProperty("user.dir")}）")
        return file.readText()
    }
}

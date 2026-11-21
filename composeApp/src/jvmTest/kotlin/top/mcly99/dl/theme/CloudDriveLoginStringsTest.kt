package top.mcly99.dl.theme

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 登录页文案契约。
 *
 * 两个要求来自用户明确反馈：
 * 1. **不要「用系统浏览器登录 + 粘贴 Cookie」那种流程**——普通人做不了；登录必须在软件内完成。
 *    macOS 因此改为随包分发 Chromium 内核（见 composeApp/build.gradle.kts 的 natives 依赖），
 *    文案里也不该再出现「系统浏览器」「手工复制 Cookie」这类引导。
 * 2. 旧文案「首次启动需解包 Chromium，可能要一两分钟」把 125MB 网络下载说成了解包，
 *    已改为中性的「正在准备内嵌浏览器…」。
 */
class CloudDriveLoginStringsTest {

    @Test
    fun login_flow_strings_exist_in_both_languages() {
        listOf(zhCN().cloudDriveStrings, enUS().cloudDriveStrings).forEach { s ->
            listOf(
                s.loginChooseTitle,
                s.loginChooseBody,
                s.loginDownloadKernel,
                s.loginKernelRetry,
                s.loginStageLocating,
                s.loginStageDownloading,
                s.loginStageExtracting,
                s.loginStageInstalling,
                s.loginStageInitializing,
            ).forEach { assertTrue(it.isNotBlank(), "blank login string: ${s.loginChooseTitle}") }
        }
    }

    @Test
    fun no_flow_asks_the_user_to_use_a_system_browser_or_copy_cookies() {
        listOf(zhCN().cloudDriveStrings, enUS().cloudDriveStrings).forEach { s ->
            val text = listOf(
                s.loginChooseTitle, s.loginChooseBody, s.loginDownloadKernel,
                s.loginPrompt, s.loginFallbackHint, s.loginInitializing,
            ).joinToString(" ")
            assertFalse(text.contains("系统浏览器"), "system-browser flow must be gone: $text")
            assertFalse(text.contains("system browser"), "system-browser flow must be gone: $text")
            assertFalse(text.contains("粘贴 Cookie"), "cookie pasting must not be the advertised path: $text")
        }
    }

    @Test
    fun install_prompt_states_the_cost_in_both_languages() {
        val zh = zhCN().cloudDriveStrings
        val en = enUS().cloudDriveStrings
        assertTrue(zh.loginDownloadKernel.contains("130"), zh.loginDownloadKernel)
        assertTrue(en.loginDownloadKernel.contains("130"), en.loginDownloadKernel)
        // 不再给出「一两分钟」这类会被网络打脸的估计
        assertFalse(zh.loginInitializing.contains("一两分钟"), zh.loginInitializing)
        assertFalse(en.loginInitializing.contains("first launch"), en.loginInitializing)
    }

    @Test
    fun initializing_text_is_neutral_in_both_languages() {
        assertTrue(zhCN().cloudDriveStrings.loginInitializing.contains("正在"))
        assertTrue(enUS().cloudDriveStrings.loginInitializing.contains("准备") ||
            enUS().cloudDriveStrings.loginInitializing.contains("Preparing"))
    }
}

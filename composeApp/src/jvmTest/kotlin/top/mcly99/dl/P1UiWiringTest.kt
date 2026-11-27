package top.mcly99.dl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** P1-09：Compose UI 无 instrumentation，沿用源码契约检查「仅可信局域网」标注确实接线。 */
class P1UiWiringTest {

    @Test
    fun transfer_screen_shows_trusted_lan_notice_on_discovery_and_web_tabs() {
        val screen = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/ui/components/TransferScreen.kt")
        val hits = screen.split("extra.trustedLanOnly").size - 1
        assertTrue(hits >= 2, "发现设备页与网页上传页都要有可信局域网标注，实际出现 $hits 次")
    }

    @Test
    fun trusted_lan_notice_is_localized_in_both_languages() {
        val strings = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/theme/TransferExtraStrings.kt")
        assertTrue(strings.contains("val trustedLanOnly: String"), "补充文案表要有专门字段")
        assertTrue(strings.contains("trustedLanOnly = \"仅可在可信局域网内使用"), "中文文案")
        assertTrue(strings.contains("trustedLanOnly = \"Trusted LAN only"), "英文文案")
    }

    private fun sourceOf(relative: String): String =
        listOf(File(relative), File("../$relative"), File("../../$relative"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("找不到源码 $relative（工作目录 ${System.getProperty("user.dir")}）")
}

package top.mcly99.dl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** P2-29：冒烟用例必须 opt-in 且「不跑」要在报告里体现为 SKIPPED，不能静默通过。 */
class P2IntegrationTestWiringTest {

    @Test
    fun integration_test_task_is_opt_in_and_shares_one_filter_list() {
        val gradle = sourceOf("composeApp/build.gradle.kts")
        assertTrue(gradle.contains("val integrationTestPatterns"), "两个任务必须共用同一份冒烟清单")
        assertTrue(gradle.contains("excludeTestsMatching(pattern)"), "jvmTest 必须把冒烟用例排除")
        assertTrue(gradle.contains("includeTestsMatching(pattern)"), "integrationTest 必须只跑冒烟用例")
        assertTrue(gradle.contains("KTDL_RUN_INTEGRATION"), "integrationTest 必须显式 opt-in")
        assertTrue(gradle.contains("onlyIf("), "未 opt-in 时任务应整体跳过")
        assertTrue(
            !gradle.contains("check.dependsOn(integrationTest)"),
            "integrationTest 不能挂到 check，否则普通 verify 会拉起真浏览器",
        )
    }

    @Test
    fun smoke_cases_use_assumptions_instead_of_silent_returns() {
        val cdp = sourceOf("composeApp/src/jvmTest/kotlin/top/mcly99/dl/engine/web/browser/BrowserCaptureSmokeTest.kt")
        assertTrue(cdp.contains("KTDL_CDP_SMOKE"), "CDP 冒烟需要环境变量")
        assertTrue(cdp.contains("assumeTrue("), "CDP 冒烟不满足前置条件必须标记 SKIPPED")
        assertTrue(!cdp.contains("!= \"1\") return"), "不能再静默 return 假装通过")

        val jcef = sourceOf("composeApp/src/jvmTest/kotlin/top/mcly99/dl/engine/clouddrive/browser/JcefHolderTest.kt")
        assertTrue(jcef.contains("MCLY_JCEF_SMOKE=1，跳过真实 JCEF 启动"))

        val vlc = sourceOf("composeApp/src/jvmTest/kotlin/top/mcly99/dl/engine/player/VlcPlaybackSmokeTest.kt")
        assertTrue(vlc.contains("smokeAssumption()"))
        assertTrue(vlc.contains("fun smokeAssumption()") && vlc.contains("assumeTrue("))
        assertTrue(!vlc.contains("if (!smokeEnabled()) return"))

        val ffmpeg = sourceOf("composeApp/src/jvmTest/kotlin/top/mcly99/dl/engine/hls/FfmpegMuxerTest.kt")
        assertTrue(ffmpeg.contains("assumeTrue(\"本机没有可用的 ffmpeg"))
        assertTrue(!ffmpeg.contains("locateFfmpeg() ?: return@runBlocking"))
    }

    private fun sourceOf(relative: String): String =
        listOf(File(relative), File("../$relative"), File("../../$relative"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("找不到源码 $relative（工作目录 ${System.getProperty("user.dir")}）")
}

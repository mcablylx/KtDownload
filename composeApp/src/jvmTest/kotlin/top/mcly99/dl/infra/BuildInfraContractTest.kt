package top.mcly99.dl.infra

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2-24 / 25 / 26 / 29 / 30 / 31 的「构建基础设施」契约测试。
 *
 * 这批改的是 build.gradle.kts、GitHub Actions workflow、打包脚本与文档，没有 JVM 行为
 * 可以直接断言，也不适合在单测里真的跑 gradle / codesign / notarytool。沿用
 * UiFixesContractTest 的范式：读源码做静态契约断言，锁住最容易悄悄回退的点 ——
 * action 又变回浮动 @v4、check_01 又被加回触发分支、冒烟用例又被算进 jvmTest、
 * 打包脚本又丢掉 set -e、bat 又变回 LF 换行。
 */
class BuildInfraContractTest {

    @Test
    fun ci_pins_every_action_to_a_full_commit_sha() {
        val refs = actionRefs(repoFile(".github/workflows/ci.yml").readText())
        assertTrue(refs.size >= 4, "ci.yml 至少应有 4 个 action，实际 ${refs.size}")
        refs.forEach { ref ->
            val sha = ref.substringAfterLast('@')
            assertTrue(
                sha.length == 40 && sha.all { it in '0'..'9' || it in 'a'..'f' },
                "action 未 pin 到 40 位 commit SHA：$ref",
            )
        }
    }

    @Test
    fun ci_runs_three_platforms_without_the_legacy_branch() {
        val lines = repoFile(".github/workflows/ci.yml").readText().lines()
        val matrix = lines.first { it.trimStart().startsWith("os: [") }
        assertTrue(matrix.contains("ubuntu-latest") && matrix.contains("macos-latest") &&
            matrix.contains("windows-latest"), "CI 必须是三平台矩阵：$matrix")
        val branches = lines.filter { it.trimStart().startsWith("branches:") }
        assertTrue(branches.isNotEmpty(), "缺少 push.branches")
        assertTrue(branches.none { it.contains("check_01") }, "遗留的 check_01 又被加回常驻触发分支")
        assertTrue(lines.any { it.contains("--no-daemon") }, "CI 构建应带 --no-daemon")
    }

    @Test
    fun ci_does_not_hide_failures_behind_continue_on_error() {
        val ci = repoFile(".github/workflows/ci.yml").readText()
        assertFalse(
            ci.lines().any { it.trimStart().startsWith("continue-on-error:") },
            "三平台都是门禁，不能用 continue-on-error 掩盖平台失败（见 P1-01）",
        )
    }

    @Test
    fun release_workflow_only_creates_a_draft_and_pins_actions() {
        val text = repoFile(".github/workflows/release.yml").readText()
        val refs = actionRefs(text)
        assertTrue(refs.size >= 5, "release.yml 至少应有 5 个 action，实际 ${refs.size}")
        refs.forEach { ref ->
            val sha = ref.substringAfterLast('@')
            assertTrue(sha.length == 40 && sha.all { it in '0'..'9' || it in 'a'..'f' },
                "action 未 pin 到 40 位 commit SHA：$ref")
        }
        assertTrue(text.contains("draft: true"), "发布流程只能产出 draft，不能自动发布")
        assertTrue(text.contains("needs: package"), "发布 job 必须等三平台打包完成")
    }

    @Test
    fun smoke_cases_are_excluded_from_jvmTest_and_opt_in_for_integrationTest() {
        val build = repoFile("composeApp/build.gradle.kts").readText()
        assertTrue(build.contains("val integrationTestPatterns"), "缺少共用清单常量")
        assertTrue(build.contains("excludeTestsMatching(pattern)"), "jvmTest 未排除冒烟用例")
        assertTrue(build.contains("includeTestsMatching(pattern)"), "integrationTest 未收录冒烟用例")
        assertTrue(build.contains("KTDL_RUN_INTEGRATION"), "integrationTest 必须是显式 opt-in")
        assertTrue(build.contains("Not part of check"), "integrationTest 不应挂到 check 门禁")
    }

    @Test
    fun smoke_patterns_cover_all_environment_gated_cases() {
        val build = repoFile("composeApp/build.gradle.kts").readText()
        listOf(
            "*BrowserCaptureSmokeTest",
            "*VlcPlaybackSmokeTest",
            "*JcefHolderTest.starts_the_embedded_browser_on_this_machine",
            "*FfmpegMuxerTest.real_ffmpeg_muxes_ts_segments_to_playable_mp4",
            "*FfmpegMuxerTest.real_ffmpeg_muxes_video_with_separate_audio_track",
        ).forEach { pattern ->
            assertTrue(build.contains("\"$pattern\""), "清单缺少冒烟用例：$pattern")
        }
    }

    @Test
    fun vlc_sha_is_injectable_and_no_unknown_sha_is_hardcoded() {
        val build = repoFile("composeApp/build.gradle.kts").readText()
        assertTrue(build.contains("providers.gradleProperty(\"vlcArchiveSha256\")"),
            "Windows/Linux 归档必须支持 -PvlcArchiveSha256 注入")
        assertTrue(build.contains("val vlcExpectedSha256"), "缺少统一的期望 SHA 变量")
        assertTrue(build.contains("verifyVlcArchive"), "打包前必须有归档校验闸门")
        assertTrue(build.contains("本次未校验"), "无 SHA 时必须明确告警，不能静默跳过")
        // 只有 macOS dmg 有内置值，不能给 Windows/Linux 编一个未知 SHA
        assertTrue(build.contains("else -> null"), "bundledVlcSha256 的非 macOS 分支应保持 null")
        // 回归防线（v1.0.4 Release 的 ubuntu job 就死在这里）：归档路径不能声明成
        // inputs.file —— Linux 上插件的落盘名与 vlcBundleVersion 无关（硬编 3.0.20-2），
        // 声明成输入会在配置校验阶段直接失败，且 optional() 也拦不住。
        assertFalse(build.contains("inputs.file(archive)"),
            "归档不能声明为任务输入：Linux 上文件名与 vlcBundleVersion 不一致会直接让构建失败")
        assertTrue(build.contains("fun cachedVlcArchive()"),
            "三平台归档落地名必须由 cachedVlcArchive() 统一解析")
    }

    @Test
    fun macos_signing_stays_ad_hoc_by_default_but_is_configurable() {
        val build = repoFile("composeApp/build.gradle.kts").readText()
        assertTrue(build.contains("providers.gradleProperty(\"macosSignIdentity\")"))
        assertTrue(build.contains("getOrElse(\"-\")"), "默认必须仍是 ad-hoc 签名，行为不变")
        assertTrue(build.contains("\"--options\", \"runtime\", \"--timestamp\""),
            "真实 Developer ID 签名需要 hardened runtime + 时间戳")
    }

    @Test
    fun package_macos_script_fails_fast_and_uses_absolute_paths() {
        val sh = repoFile("package-macos.sh").readText()
        assertTrue(sh.contains("set -euo pipefail"), "打包失败不能继续打印「打包完成！」")
        assertTrue(sh.contains("REPO_ROOT=") && sh.contains("cd \"${'$'}REPO_ROOT\""),
            "必须用脚本自身位置解析路径，不依赖调用者 cwd")
        assertTrue(sh.contains("--no-configuration-cache"))
        assertTrue(sh.contains("-t 0"), "read 必须只在交互终端执行，不能阻塞自动化")
        assertTrue(sh.contains("KTDL_NOTARIZE"), "应提供接公证脚本的入口")
    }

    @Test
    fun notarize_script_checks_platform_and_completes_the_full_flow() {
        val sh = repoFile("scripts/notarize-macos.sh").readText()
        assertTrue(sh.contains("set -euo pipefail"))
        assertTrue(sh.contains("uname -s"), "非 macOS 应明确报错退出")
        listOf("notarytool submit", "stapler staple", "spctl --assess", "productsign", "codesign")
            .forEach { step -> assertTrue(sh.contains(step), "公证流程缺少：$step") }
    }

    @Test
    fun installer_bat_is_crlf_and_does_not_block_automation() {
        val text = repoFile("package-installer.bat").readBytes().toString(Charsets.UTF_8)
        // 换行必须由 .gitattributes 的 `*.bat text eol=crlf` 保证：
        // 只靠 Windows 上的 core.autocrlf=true，Linux/macOS 的 checkout 会拿到 LF 文件
        assertTrue(text.contains("\r\n"), "bat 必须是 CRLF 换行（.gitattributes 的 eol=crlf 负责，不能只靠 autocrlf）")
        assertEquals(0, text.replace("\r\n", "").count { it == '\n' }, "bat 不能有裸 LF 行尾")
        assertTrue(text.contains("if not defined CI pause"), "CI 下的 pause 必须可跳过")
    }

    @Test
    fun readme_no_longer_over_promises_offline_packaging() {
        val readme = repoFile("README.md").readText()
        assertFalse(readme.contains("clone 后直接打包即可，全程不联网、不现场编译"),
            "README 不能再无条件承诺 clone 后全程离线")
        assertTrue(readme.contains("默认 clone 不一定包含这些大文件"))
        assertFalse(readme.contains("主要覆盖 Intel x86_64"), "与 universal 预置二进制的措辞矛盾")
        assertTrue(readme.contains("release.yml"), "目录结构应说明发布流程")
        assertTrue(readme.contains("发版时需与"), "版本号硬编码处应提示与 gradle.properties 同步")
    }

    @Test
    fun source_txt_records_the_only_bundled_sha_and_marks_the_gaps() {
        val src = repoFile("third-party/binaries/SOURCE.txt").readText()
        assertTrue(src.contains("50380e56f81d630e3531426a265abf8b3dee6c0822bb7c9e30b532c7d69a65c0"))
        assertTrue(src.contains("<未提供>"), "Windows/Linux 无内置 SHA 必须显式标注")
        assertTrue(src.contains("sparse-checkout"), "必须说明默认 clone 不含大文件")
        assertTrue(src.contains("-PvlcArchiveSha256"), "必须给出注入 SHA 的方式")
    }

    private fun actionRefs(yaml: String): List<String> =
        yaml.lines().mapNotNull { line ->
            val s = line.trim()
            if (s.startsWith("uses:")) s.removePrefix("uses:").trim().substringBefore("#").trim()
            else null
        }

    /** 测试工作目录通常是 composeApp/，也可能是仓库根；逐个候选找。 */
    private fun repoFile(path: String): File =
        listOf(File(path), File("../$path"), File("../../$path"))
            .firstOrNull { it.isFile }
            ?: error("找不到 $path（工作目录 ${System.getProperty("user.dir")}）")
}

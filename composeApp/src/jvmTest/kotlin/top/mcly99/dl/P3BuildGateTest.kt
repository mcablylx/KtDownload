package top.mcly99.dl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** P3-04：依赖门禁与包体裁剪决策的源码契约（CI / Gradle 配置无法在 JVM 单测里真实执行） */
class P3BuildGateTest {

    @Test
    fun ci_has_dependency_review_job_pinned_to_commit_sha() {
        val ci = sourceOf(".github/workflows/ci.yml")
        assertTrue(ci.contains("dependency-review:"), "CI 应有 dependency-review job")
        assertTrue(ci.contains("if: github.event_name == 'pull_request'"), "依赖门禁应只在 PR 触发")
        assertTrue(
            ci.contains("actions/dependency-review-action@2031cfc080254a8a887f58cffee85186f0e49e48"),
            "dependency-review-action 必须 pin 到 commit SHA（P2-26 约定）",
        )
    }

    @Test
    fun gradle_has_dependency_locking_infrastructure() {
        val gradle = sourceOf("composeApp/build.gradle.kts")
        assertTrue(gradle.contains("dependencyLocking {"), "应接入依赖锁定基础设施")
        assertTrue(gradle.contains("lockAllConfigurations()"), "应覆盖所有可解析配置")
    }

    @Test
    fun proguard_stays_disabled_with_documented_reason() {
        val gradle = sourceOf("composeApp/build.gradle.kts")
        assertTrue(gradle.contains("isEnabled = false"), "ProGuard 裁剪有真实破坏记录，保持关闭")
        assertTrue(gradle.contains("jcef-api"), "关闭理由必须保留：JCEF 反射类被裁会导致登录页白屏")
        assertTrue(gradle.contains("vlcj"), "关闭理由必须保留：vlcj 被裁会破坏播放内核")
    }

    private fun sourceOf(relative: String): String =
        listOf(File(relative), File("../$relative"), File("../../$relative"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("找不到源码 $relative（工作目录 ${System.getProperty("user.dir")}）")
}

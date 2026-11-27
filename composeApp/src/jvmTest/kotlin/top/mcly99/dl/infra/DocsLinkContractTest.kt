package top.mcly99.dl.infra

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** P2-27 / #80：仓库里写到的 docs 路径必须真的存在，clone 后不能是死链。 */
class DocsLinkContractTest {

    @Test
    fun every_referenced_doc_file_exists() {
        val refs = linkedMapOf<String, MutableSet<String>>()
        REFERENCING_FILES.forEach { relative ->
            val file = repoFile(relative)
            Regex("""docs/([A-Za-z0-9_.\-]+\.md)""").findAll(file.readText()).forEach { match ->
                refs.getOrPut(match.groupValues[1]) { linkedSetOf() } += relative
            }
        }
        assertTrue(refs.isNotEmpty(), "引用扫描不应为空，检查 REFERENCING_FILES 是否写错")
        refs.forEach { (name, sources) ->
            val doc = repoFile("docs/$name")
            assertTrue(doc.isFile && doc.length() > 0, "docs/$name 不存在或是空文件（被 $sources 引用）")
        }
    }

    @Test
    fun historically_referenced_sections_still_exist() {
        val plan = repoFile("docs/CATCH_WEB_PLAN.md").readText()
        assertTrue(plan.contains("## 8. 手工验证清单"), "测试注释仍引用 §8，标题不能改")
        assertTrue(plan.contains("KTDL_CDP_SMOKE"), "手工清单要给出可执行的 opt-in 命令")

        val improvements = repoFile("docs/IMPROVEMENTS.md").readText()
        assertTrue(improvements.contains("v9"), "build.gradle 注释引用 v9 修复记录")

        val disclaimer = repoFile("docs/DISCLAIMER.md").readText()
        assertTrue(disclaimer.contains("Non-commercial") || disclaimer.contains("非商业"))
        assertTrue(disclaimer.contains("github.com/tidain/YunX-Desktop"), "摘要要指回上游全文")
    }

    @Test
    fun bundled_binaries_are_declared_as_optional_not_promised() {
        val gradle = repoFile("composeApp/build.gradle.kts").readText()
        assertTrue(gradle.contains("可选预置归档"), "构建脚本不能再暗示默认 clone 一定含归档")
        assertTrue(gradle.contains("restoreVlcFromRepo"), "缺归档时仍要有还原入口")

        val readme = repoFile("README.md").readText()
        assertTrue(readme.contains("默认 clone 不一定包含这些大文件"))
        assertTrue(readme.contains("SOURCE.txt"), "缺口必须由清单文件说明")
    }

    private companion object {
        val REFERENCING_FILES = listOf(
            "composeApp/build.gradle.kts",
            "README.md",
            "THIRD-PARTY-NOTICES.md",
            "docs/PROJECT_REVIEW_2026-11-26.md",
            "composeApp/src/jvmTest/kotlin/top/mcly99/dl/engine/player/QualityOptionsTest.kt",
            "composeApp/src/jvmTest/kotlin/top/mcly99/dl/engine/player/PlayerEntryTest.kt",
        )
    }

    /** 测试工作目录通常是 composeApp/，也可能是仓库根；逐个候选找。 */
    private fun repoFile(path: String): File =
        listOf(File(path), File("../$path"), File("../../$path"))
            .firstOrNull { it.isFile }
            ?: error("找不到 $path（工作目录 ${System.getProperty("user.dir")}）")
}

package top.mcly99.dl.infra

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2-27 / #80：仓库里写到的 docs 路径必须真的存在，clone 后不能是死链。
 *
 * #82：`docs/` 是维护者的本地评审资料，被 `.gitignore` 忽略、**不随仓库分发**。
 * 所以依赖 docs 内容的用例统一用 [assumeTrue] 表达「本机有评审资料才跑」：
 * 全新 clone / CI 上如实显示 SKIPPED，不会因为拿不到 docs 而假失败。
 */
class DocsLinkContractTest {

    @Test
    fun every_referenced_doc_file_exists() {
        assumeTrue(DOCS_ABSENT_MESSAGE, docsDir() != null)

        val refs = linkedMapOf<String, MutableSet<String>>()
        REFERENCING_FILES.forEach { relative ->
            val file = repoFile(relative)
            Regex("""docs/([A-Za-z0-9_.\-]+\.md)""").findAll(file.readText()).forEach { match ->
                refs.getOrPut(match.groupValues[1]) { linkedSetOf() } += relative
            }
        }
        assertTrue(refs.isNotEmpty(), "引用扫描不应为空，检查 REFERENCING_FILES 是否写错")
        refs.forEach { (name, sources) ->
            val doc = repoFileOrNull("docs/$name")
            assertTrue(doc != null && doc.length() > 0, "docs/$name 不存在或是空文件（被 $sources 引用）")
        }
    }

    @Test
    fun historically_referenced_sections_still_exist() {
        assumeTrue(DOCS_ABSENT_MESSAGE, docsDir() != null)

        val plan = repoFile("docs/CATCH_WEB_PLAN.md").readText()
        assertTrue(plan.contains("## 8. 手工验证清单"), "测试注释仍引用 §8，标题不能改")
        assertTrue(plan.contains("KTDL_CDP_SMOKE"), "手工清单要给出可执行的 opt-in 命令")

        val improvements = repoFile("docs/IMPROVEMENTS.md").readText()
        assertTrue(improvements.contains("v9"), "build.gradle 注释引用 v9 修复记录")

        val disclaimer = repoFile("docs/DISCLAIMER.md").readText()
        assertTrue(disclaimer.contains("Non-commercial") || disclaimer.contains("非商业"))
        assertTrue(disclaimer.contains("github.com/tidain/YunX-Desktop"), "摘要要指回上游全文")
    }

    /**
     * #82：`docs/` 只留在维护者本机，不随仓库 / 远端分发。
     *
     * 这条**不需要 docs 存在**——它守的正是「不要又把 docs 塞回版本库」：
     * `.gitignore` 必须继续忽略它，对外文档也不能再写成本仓库里的路径。
     */
    @Test
    fun docs_stay_out_of_version_control() {
        val ignore = repoFile(".gitignore").readText()
        assertTrue(
            ignore.lineSequence().any { it.trim() == "docs/" },
            ".gitignore 必须继续忽略 docs/（评审资料只在本地）",
        )

        val notices = repoFile("THIRD-PARTY-NOTICES.md").readText()
        assertFalse(
            notices.contains("docs/DISCLAIMER.md"),
            "对外可见的说明不能指向不随仓库分发的 docs/DISCLAIMER.md",
        )
        assertTrue(
            notices.contains("not") && notices.contains("distributed"),
            "第三方声明要写明评审资料不随仓库分发",
        )
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
        const val DOCS_ABSENT_MESSAGE = "本机没有 docs/ 评审资料（不随仓库分发），跳过该检查"

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
        repoFileOrNull(path)
            ?: error("找不到 $path（工作目录 ${System.getProperty("user.dir")}）")

    private fun repoFileOrNull(path: String): File? =
        listOf(File(path), File("../$path"), File("../../$path"))
            .firstOrNull { it.isFile && it.length() > 0 }

    private fun docsDir(): File? =
        listOf(File("docs"), File("../docs"), File("../../docs"))
            .firstOrNull { it.isDirectory }
}

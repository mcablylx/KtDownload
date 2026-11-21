package top.mcly99.dl.engine.clouddrive.browser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

/**
 * 内嵌浏览器「内核从哪来」的判定契约。
 *
 * 现在的策略是**随包分发**：`composeApp/build.gradle.kts` 按构建平台引入
 * `jcef-natives-<platform>`，jcefmaven 只做本地解包，不联网。判定靠的是
 * classpath 上到底有没有这个 natives 资源，而不是按 `os.name` 猜——
 * 猜错的后果就是重演「打开登录页去下 125MB、卡十几分钟」。
 */
class JcefHolderTest {

    private fun tempDir(): File = Files.createTempDirectory("jcef-probe").toFile()

    @Test
    fun build_ships_natives_for_the_current_platform() {
        assertTrue(
            JcefHolder.nativesOnClasspath(),
            "当前平台的 jcef-natives 不在 classpath 上：检查 composeApp/build.gradle.kts 里的 " +
                "natives 依赖是否覆盖了这个平台/架构，否则登录页会退化成运行时下载",
        )
    }

    @Test
    fun bundled_natives_count_as_a_local_kernel() {
        // 有随包 natives 时，打开登录页不应该触发任何网络请求
        assertTrue(JcefHolder.hasLocalKernel())
    }

    /**
     * 真机冒烟：解包 natives 并真的把 CEF 拉起来。
     *
     * 默认跳过（会解包 125MB、启动 CEF 子进程，慢且依赖图形会话）。
     * 手动验证：`MCLY_JCEF_SMOKE=1 ./gradlew :composeApp:jvmTest --tests '*JcefHolderTest'`
     * ——这是确认「macOS 上内嵌浏览器确实能用」的唯一硬证据。
     */
    @Test
    fun starts_the_embedded_browser_on_this_machine() {
        if (System.getenv("MCLY_JCEF_SMOKE") != "1") return
        assertTrue(JcefHolder.hasLocalKernel(), "冒烟测试要求内核已在本地")
        JcefHolder.initInBackground(allowDownload = false)
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline &&
            !JcefHolder.browserReady && JcefHolder.initFailure == null
        ) {
            Thread.sleep(500)
        }
        assertNull(JcefHolder.initFailure, "JCEF 初始化失败：${JcefHolder.initFailure}")
        assertTrue(JcefHolder.browserReady, "JCEF 在 180s 内没有就绪")
    }

    @Test
    fun empty_directory_is_not_an_installation() {
        val dir = tempDir()
        try {
            assertFalse(JcefHolder.isKernelInstalled(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun install_lock_marks_a_completed_installation() {
        val dir = tempDir()
        try {
            File(dir, "install.lock").createNewFile()
            assertTrue(JcefHolder.isKernelInstalled(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun download_temp_alone_is_not_a_usable_kernel() {
        // 上次下载到一半留下的 download.zip.temp 不能被当成「已安装」
        val dir = tempDir()
        try {
            File(dir, "download.zip.temp").writeBytes(ByteArray(64))
            assertFalse(JcefHolder.isKernelInstalled(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}

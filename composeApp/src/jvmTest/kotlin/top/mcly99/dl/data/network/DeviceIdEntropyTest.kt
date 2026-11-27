package top.mcly99.dl.data.network

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** P3-05：设备指纹必须用 CSPRNG，不能是 kotlin.random.Random / UUID.randomUUID */
class DeviceIdEntropyTest {

    @Test
    fun device_fingerprints_use_secure_random() {
        val xunlei = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/data/network/XunleiDeviceFingerprint.kt")
        // 只查 import 行：注释里会解释「旧实现用了什么」，不能按全文子串误伤
        assertTrue(
            !xunlei.contains("import kotlin.random.Random"),
            "迅雷设备指纹不能 import 非 CSPRNG 的 kotlin.random.Random",
        )
        assertTrue(xunlei.contains("import java.security.SecureRandom"), "迅雷设备指纹应使用 SecureRandom")

        val pan123 = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/data/network/Pan123DeviceId.kt")
        assertTrue(!pan123.contains("import java.util.UUID"), "123 设备标识不应依赖 UUID 内部实现")
        assertTrue(pan123.contains("import java.security.SecureRandom"), "123 设备标识应显式使用 SecureRandom")
    }

    @Test
    fun shim_prefs_uses_atomic_write_and_permission_hardening() {
        val source = sourceOf("composeApp/src/jvmMain/kotlin/top/mcly99/dl/data/AppContext.kt")
        assertTrue(source.contains("AtomicPropsFile.write"), "ShimPrefs 必须走原子写")
        assertTrue(source.contains("FilePermissions.hardenFile"), "ShimPrefs 写完必须收紧权限")
    }

    private fun sourceOf(relative: String): String =
        listOf(File(relative), File("../$relative"), File("../../$relative"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("找不到源码 $relative（工作目录 ${System.getProperty("user.dir")}）")
}

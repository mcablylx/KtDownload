/*
 * Compatibility shim for the ported YunX-Desktop cloud-drive resolver.
 * Upstream uses java.util.prefs; ktdown keeps the same get/put surface but backs it
 * with a small properties file under ~/.mclydownload, so device ids and misc flags
 * live beside tasks.properties / config.properties and can be inspected together.
 *
 * Note (2026-11-14): composeApp/build.gradle.kts DOES list "java.prefs" in
 * modules(...), so the older comment claiming "the jlink image does not include
 * java.prefs" was inaccurate. The shim is a deliberate storage choice, not a
 * workaround for a missing module. See third-party/yunx-desktop/SOURCE.txt (N-03).
 */
package top.mcly99.dl.data

import java.io.File
import java.util.Properties

class ShimPrefs(private val file: File) {
    private val lock = Any()
    private val props = Properties()

    init {
        runCatching {
            if (file.isFile) file.inputStream().use { props.load(it) }
        }
    }

    fun get(key: String, default: String?): String? = synchronized(lock) {
        props.getProperty(key) ?: default
    }

    fun put(key: String, value: String) {
        synchronized(lock) {
            props.setProperty(key, value)
            runCatching {
                file.parentFile?.mkdirs()
                file.outputStream().use { props.store(it, "KtDownload / YunX resolver preferences") }
            }
        }
    }
}

object AppContext {
    private val dir = File(System.getProperty("user.home"), ".mclydownload")

    val miscPrefs: ShimPrefs = ShimPrefs(File(dir, "yunx-misc.properties"))
    val xunleiFpPrefs: ShimPrefs = ShimPrefs(File(dir, "yunx-xunlei-fp.properties"))
}

package top.mcly99.dl.utils

/**
 * 当前运行平台的识别，仅用于从 Release assets 里挑选正确的安装包。
 *
 * 与构建期选择 JCEF natives 的逻辑一致（见 build.gradle.kts）：
 * 以 `os.name` 为准，Windows 与 macOS 各自匹配一种安装包的扩展名。
 */
enum class HostPlatform {
    WINDOWS, MACOS, UNSUPPORTED;

    companion object {
        fun current(): HostPlatform {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                os.contains("win") -> WINDOWS
                os.contains("mac") || os.contains("darwin") -> MACOS
                else -> UNSUPPORTED
            }
        }
    }
}

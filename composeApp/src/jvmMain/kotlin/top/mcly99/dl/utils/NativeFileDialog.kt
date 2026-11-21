package top.mcly99.dl.utils

/**
 * 原生文件对话框工具类
 * 
 * 该工具类提供跨平台的原生文件选择对话框功能，针对不同操作系统使用最优的原生实现，
 * 确保用户获得最佳的本地化体验。
 * 
 * 平台适配策略：
 * - macOS: 使用Java AWT FileDialog（通过系统属性启用原生NSOpenPanel）
 * - Windows/Linux: 使用JFileChooser（配置为原生外观）
 * 
 * 主要功能：
 * - 目录选择：打开原生目录选择对话框
 * - 文件选择：打开原生文件选择对话框
 * - 平台检测：自动识别当前运行的操作系统
 * - 错误处理：优雅降级和异常捕获
 * 
 * 设计特点：
 * - 跨平台兼容：统一API，底层自动适配不同平台
 * - 原生体验：使用操作系统原生文件对话框，符合用户预期
 * - 轻量级：不依赖外部库，仅使用Java标准库
 * - 可扩展性：易于添加新的平台适配或自定义选项
 * 
 * @see FileDialog Java AWT文件对话框
 * @see JFileChooser Java Swing文件选择器
 */

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

object NativeFileDialog {
    private val isMac: Boolean = System.getProperty("os.name", "").lowercase().contains("mac")

    fun chooseDirectory(title: String = "选择文件夹"): String? {
        return if (isMac) chooseDirectoryMac(title) else chooseDirectorySwing(title)
    }

    /**
     * @param extension 只列该扩展名的文件（不带点），例如 `"ktab"`；null 表示不过滤
     */
    fun chooseFile(title: String = "选择文件", extension: String? = null): File? {
        return if (isMac) chooseFileMac(title, extension) else chooseFileSwing(title, extension)
    }

    /**
     * 「另存为」对话框。返回用户选定的目标文件（**不含**写盘动作）。
     *
     * @param defaultName 预填文件名
     * @param extension 用户没写扩展名时自动补上，避免导出成没有后缀的文件
     */
    fun saveFile(
        title: String = "保存文件",
        defaultName: String = "",
        extension: String? = null,
    ): File? = if (isMac) saveFileMac(title, defaultName, extension) else saveFileSwing(title, defaultName, extension)

    // -- macOS: native NSOpenPanel via FileDialog --

    /**
     * `apple.awt.fileDialogForDirectories` 是 JVM 全局属性，不是线程局部的。
     * 两次调用重叠时（快速连点、或与其它 FileDialog 并发），后一次的 `false` 恢复
     * 会把前一次的 `true` 提前清掉，对话框退化成「选文件」（v4-P3-2）。
     * 这里用一把锁把「设置 → 显示 → 恢复」包成临界区。
     */
    private val macDialogLock = Any()

    private fun chooseDirectoryMac(title: String): String? = synchronized(macDialogLock) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            dialog.isVisible = true
            if (dialog.file != null) {
                File(dialog.directory, dialog.file).absolutePath
            } else null
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }

    private fun chooseFileMac(title: String, extension: String?): File? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        if (extension != null) dialog.setFilenameFilter { _, name -> name.endsWith(".$extension") }
        dialog.isVisible = true
        return if (dialog.file != null) {
            File(dialog.directory, dialog.file)
        } else null
    }

    private fun saveFileMac(title: String, defaultName: String, extension: String?): File? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
        if (defaultName.isNotBlank()) dialog.file = defaultName
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val dir = dialog.directory ?: return null
        return File(dir, withExtension(name, extension))
    }

    /** 用户可能自己输入了 `xxx.txt`；只有「没有以目标扩展名结尾」时才补 */
    private fun withExtension(name: String, extension: String?): String {
        if (extension.isNullOrBlank()) return name
        return if (name.endsWith(".$extension", ignoreCase = true)) name else "$name.$extension"
    }

    // -- Windows/Linux: JFileChooser (with native look) --

    private fun chooseDirectorySwing(title: String): String? {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        val result = chooser.showOpenDialog(null)
        return if (result == JFileChooser.APPROVE_OPTION && chooser.selectedFile != null) {
            chooser.selectedFile.absolutePath
        } else null
    }

    private fun chooseFileSwing(title: String, extension: String?): File? {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        chooser.fileSelectionMode = JFileChooser.FILES_ONLY
        if (extension != null) {
            chooser.fileFilter = FileNameExtensionFilter("$title (*.$extension)", extension)
        }
        val result = chooser.showOpenDialog(null)
        return if (result == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile
        } else null
    }

    private fun saveFileSwing(title: String, defaultName: String, extension: String?): File? {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        if (defaultName.isNotBlank()) chooser.selectedFile = File(defaultName)
        if (extension != null) {
            chooser.fileFilter = FileNameExtensionFilter("$title (*.$extension)", extension)
        }
        val result = chooser.showSaveDialog(null)
        if (result != JFileChooser.APPROVE_OPTION) return null
        val selected = chooser.selectedFile ?: return null
        return File(selected.parentFile, withExtension(selected.name, extension))
    }
}

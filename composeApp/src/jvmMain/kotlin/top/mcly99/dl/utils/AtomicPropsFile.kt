package top.mcly99.dl.utils

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * `Properties` 文件的原子写入（P1-06 / P2-05）。
 *
 * 旧实现两处（[top.mcly99.dl.data.SettingsRepository] / [top.mcly99.dl.engine.TaskStore]）
 * 都写死 `<name>.tmp` 且无互斥：防抖写盘与 shutdown 同步写盘可能同时打开同一个 tmp，
 * 其中一次 `Files.move` 抛 `NoSuchFileException` 被 `catch (Exception)` 吞掉，
 * 最后一次修改丢失或两份 props 混合。
 *
 * 这里统一为：
 * - **按目标路径加锁**（可重入 `synchronized`；`TaskStore.load` 内部再调 save 不会自锁死）；
 * - 每次 `createTempFile` 生成**唯一** tmp，`finally` 清理残留；
 * - `ATOMIC_MOVE` 不支持时回退普通 `REPLACE_EXISTING`，而不是静默失败；
 * - 读写共用同一把锁，避免「读时写」与外部写交错读到半截内容。
 */
internal object AtomicPropsFile {

    private val locks = ConcurrentHashMap<Path, Any>()

    private fun lockFor(target: Path): Any =
        locks.computeIfAbsent(target.toAbsolutePath().normalize()) { Any() }

    fun write(target: Path, props: Properties, comment: String) {
        synchronized(lockFor(target)) {
            val absolute = target.toAbsolutePath().normalize()
            val parent = checkNotNull(absolute.parent) { "Properties 目标必须有父目录: $target" }
            Files.createDirectories(parent)
            // 前缀保留目标文件名便于排查，后缀 .tmp 与既有习惯一致；同目录保证 ATOMIC_MOVE 可跨卷无关
            val tmp = Files.createTempFile(parent, absolute.fileName.toString() + ".", ".tmp")
            try {
                Files.newOutputStream(tmp).use { props.store(it, comment) }
                try {
                    Files.move(
                        tmp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    // 网络盘 / FAT / 跨卷：退回普通替换，最坏只是短暂非原子
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
    }

    fun read(target: Path): Properties = synchronized(lockFor(target)) {
        val props = Properties()
        Files.newInputStream(target).use { props.load(it) }
        props
    }
}

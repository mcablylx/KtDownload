package top.mcly99.dl.engine.hls

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import top.mcly99.dl.utils.AppLogger
import top.mcly99.dl.utils.FfmpegLocator
import top.mcly99.dl.utils.SafeDelete
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/** 合成结果：成功产出 MP4、降级为保留分片，或真实失败（可重试）。 */
sealed interface HlsMuxResult {
    data class Mp4(val file: File) : HlsMuxResult

    /** 没有可用的 ffmpeg 且快路径不适用；分片保留在 [directory]，任务按「完成（附警告）」处理。 */
    data class SegmentsOnly(val directory: File, val reason: String) : HlsMuxResult

    /** ffmpeg 存在但转封装失败/超时；分片保留在 [directory]，任务按失败处理，允许重试。 */
    data class Failed(val directory: File, val reason: String, val cause: Throwable? = null) : HlsMuxResult
}

/**
 * 把 [HlsDownloadResult] 合成一个可播放的 MP4。
 *
 * 顺序：
 * 1. fMP4 快路径：`init.mp4` + 全部分片直接拼接。拼接前校验初始化段含 `ftyp`/`moov`、
 *    每个分片含 `moof` 或 `styp`；成功则不依赖 ffmpeg。有独立音轨时跳过快路径，否则会丢掉声音。
 * 2. 快路径失败或 TS 流：调用 ffmpeg `-c copy` 转封装（不重新编码）；
 *    独立音轨作为第二路输入，用 `-map 0:v:0 -map 1:a:0` 合并。
 * 3. ffmpeg 不可用：保留分片目录并返回 [HlsMuxResult.SegmentsOnly]；
 *    ffmpeg 存在但失败/超时：返回 [HlsMuxResult.Failed]，不再伪装成完成。
 *
 * 成功后会按 [mux] 的 `keepSegments` 决定是否删除分片目录。
 *
 * @param ffmpegPath 可执行文件路径；为 null 表示当前环境没有 ffmpeg
 * @param ffmpegTimeoutMs 转封装超时；超时后强制结束进程并按失败处理
 */
class FfmpegMuxer(
    private val ffmpegPath: Path? = FfmpegLocator.locate(),
    private val ffmpegTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    /**
     * @param onProcess 转封装进程回调：开始为 `Process`，结束为 null，供取消时 `destroyForcibly()`
     */
    suspend fun mux(
        result: HlsDownloadResult,
        output: File,
        keepSegments: Boolean = false,
        onProcess: (Process?) -> Unit = {},
    ): HlsMuxResult = withContext(Dispatchers.IO) {
        output.parentFile?.mkdirs()
        val tmp = File(output.parentFile, "${output.name}.tmp")
        tmp.delete()

        try {
            val fastPathDone = result.initSegment != null &&
                result.audioSegments.isEmpty() &&
                isFmp4Init(result.initSegment) &&
                result.segments.all(::isFmp4Segment) &&
                concatFmp4(result, tmp)

            val noFfmpeg = ffmpegPath == null
            val outcome = when {
                fastPathDone -> MuxOutcome.Success
                noFfmpeg -> MuxOutcome.Failure("没有可用的 ffmpeg")
                else -> try {
                    runFfmpeg(result, tmp, onProcess)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 生成 concat 列表等准备工作失败（如路径含换行）也按失败处理，保留分片
                    MuxOutcome.Failure("转封装准备失败：${e.message}", e)
                }
            }

            if (outcome is MuxOutcome.Failure || tmp.length() <= 0L) {
                tmp.delete()
                val reason = (outcome as? MuxOutcome.Failure)?.reason ?: "合成结果为空"
                return@withContext if (noFfmpeg) {
                    HlsMuxResult.SegmentsOnly(result.directory, reason)
                } else {
                    HlsMuxResult.Failed(result.directory, reason, (outcome as? MuxOutcome.Failure)?.cause)
                }
            }

            // 暂停/取消可能发生在「ffmpeg 已成功、尚未 move」的窗口：此时不能留下成品
            if (!currentCoroutineContext().isActive) {
                tmp.delete()
                throw CancellationException("HLS 合成已取消")
            }
            moveIntoPlace(tmp, output)
            if (!keepSegments) {
                SafeDelete.deleteTree(result.directory, "HLS 分片")
            }
            HlsMuxResult.Mp4(output)
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            // moveIntoPlace 可能因目标占用/磁盘满抛 IOException（Windows 常见）：
            // 必须删掉 .tmp 并转成 Failed，不能遗留半成品（P2-5）
            tmp.delete()
            return@withContext HlsMuxResult.Failed(result.directory, "合成结果落盘失败: ${e.message}", e)
        }
    }

    // ------------------------------------------------------------------ 快路径

    /** 直接拼接 fMP4；拼接后不是合法 MP4（缺少 `ftyp`）时返回 false，交给 ffmpeg。 */
    private fun concatFmp4(result: HlsDownloadResult, tmp: File): Boolean {
        val init = result.initSegment ?: return false
        return runCatching {
            FileOutputStream(tmp).use { output ->
                FileInputStream(init).use { it.copyTo(output, COPY_BUFFER_SIZE) }
                result.segments.forEach { segment ->
                    FileInputStream(segment).use { it.copyTo(output, COPY_BUFFER_SIZE) }
                }
            }
            hasFtypBox(tmp)
        }.getOrElse {
            AppLogger.warn("FfmpegMuxer", "fMP4 直接拼接失败: ${it.message}", it)
            tmp.delete()
            false
        }
    }

    /** 初始化段必须是 ISO BMFF：包含 `ftyp` 与 `moov`。 */
    private fun isFmp4Init(file: File): Boolean =
        topLevelBoxTypes(file).let { "ftyp" in it && "moov" in it }

    /** fMP4 媒体分片至少包含 `moof`（或 `styp`）；TS/截断/加密数据会被拒绝。 */
    private fun isFmp4Segment(file: File): Boolean =
        topLevelBoxTypes(file).let { "moof" in it || "styp" in it }

    /**
     * 扫描文件顶层的 box 类型（只读 box 头并跳过内容，不会把大文件读进内存）。
     *
     * 遇到 size 字段损坏时停止；调用方拿到的集合可能为空，从而拒绝快路径。
     */
    private fun topLevelBoxTypes(file: File): Set<String> = try {
        RandomAccessFile(file, "r").use { input ->
            val types = mutableSetOf<String>()
            val length = input.length()
            val header = ByteArray(16)
            var offset = 0L
            while (offset + 8 <= length) {
                input.seek(offset)
                if (input.read(header, 0, 8) != 8) break
                var size = readUInt32(header, 0)
                val type = String(header, 4, 4, Charsets.US_ASCII)
                var headerSize = 8L
                when {
                    size == 1L -> {
                        if (input.read(header, 8, 8) != 8) break
                        size = readUInt64(header, 8)
                        headerSize = 16
                    }
                    size == 0L -> size = length - offset
                }
                if (size < headerSize) break
                types.add(type)
                offset += size
            }
            types
        }
    } catch (e: Exception) {
        AppLogger.warn("FfmpegMuxer", "读取 MP4 box 失败: ${file.name}", e)
        emptySet()
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun readUInt64(bytes: ByteArray, offset: Int): Long =
        (readUInt32(bytes, offset) shl 32) or readUInt32(bytes, offset + 4)

    /** ISO BMFF 文件前 8 字节的 4..7 应为 `ftyp` */
    private fun hasFtypBox(file: File): Boolean {
        val header = ByteArray(8)
        FileInputStream(file).use { input ->
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            if (read < header.size) return false
        }
        return header[4] == 'f'.code.toByte() &&
            header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() &&
            header[7] == 'p'.code.toByte()
    }

    // ------------------------------------------------------------------ ffmpeg

    private sealed interface MuxOutcome {
        object Success : MuxOutcome

        data class Failure(val reason: String, val cause: Throwable? = null) : MuxOutcome
    }

    private suspend fun runFfmpeg(
        result: HlsDownloadResult,
        tmp: File,
        onProcess: (Process?) -> Unit,
    ): MuxOutcome {
        val ffmpeg = ffmpegPath ?: return MuxOutcome.Failure("没有可用的 ffmpeg")
        val tempFiles = mutableListOf<File>()
        val hasSeparateAudio = result.audioSegments.isNotEmpty()
        val command = buildList {
            add(ffmpeg.toString())
            // GUI 进程不应继承/等待 stdin；部分环境会把 ffmpeg 卡在交互模式
            add("-nostdin")
            add("-hide_banner")
            addAll(listOf("-loglevel", "error", "-y"))
            addAll(inputArgs("video", result.initSegment, result.segments, result.directory, tempFiles))
            if (hasSeparateAudio) {
                addAll(
                    inputArgs(
                        "audio",
                        result.audioInitSegment,
                        result.audioSegments,
                        result.directory,
                        tempFiles,
                    ),
                )
                addAll(listOf("-map", "0:v:0", "-map", "1:a:0"))
            }
            addAll(listOf("-c", "copy"))
            // 独立音轨为 fMP4（AAC ASC）时不需要也不应做 ADTS→ASC 转换
            if (!hasSeparateAudio || result.audioInitSegment == null) {
                addAll(listOf("-bsf:a", "aac_adtstoasc"))
            }
            addAll(listOf("-movflags", "+faststart"))
            // 临时文件以 .tmp 结尾，ffmpeg 无法从扩展名推断封装格式；必须显式指定
            addAll(listOf("-f", "mp4", tmp.absolutePath))
        }

        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            tempFiles.forEach { it.delete() }
            AppLogger.warn("FfmpegMuxer", "ffmpeg 启动失败: ${e.message}", e)
            return MuxOutcome.Failure("ffmpeg 启动失败：${e.message}", e)
        }

        onProcess(process)
        // 必须持续读走输出，否则管道写满会让 ffmpeg 阻塞
        val log = StringBuilder()
        val drainer = Thread({ drainLog(process, log) }, "ktdown-ffmpeg-log").apply {
            isDaemon = true
            start()
        }

        try {
            val deadline = System.nanoTime() + ffmpegTimeoutMs.coerceAtLeast(1L) * 1_000_000
            while (true) {
                if (!currentCoroutineContext().isActive) {
                    stopProcess(process, drainer)
                    throw CancellationException("ffmpeg 合成已取消")
                }
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                if (remainingMs <= 0L) {
                    stopProcess(process, drainer)
                    return MuxOutcome.Failure("ffmpeg 转封装超时（${ffmpegTimeoutMs} ms）")
                }
                if (process.waitFor(minOf(remainingMs, CANCEL_POLL_MS), TimeUnit.MILLISECONDS)) break
            }
            drainer.join(1_000)
            val exit = process.exitValue()
            if (exit == 0) return MuxOutcome.Success

            val tail = synchronized(log) { log.toString().takeLast(500) }
            AppLogger.warn("FfmpegMuxer", "ffmpeg 转封装失败 ($exit): $tail")
            return MuxOutcome.Failure("ffmpeg 转封装失败（退出码 $exit）")
        } finally {
            onProcess(null)
            tempFiles.forEach { it.delete() }
        }
    }

    private fun drainLog(process: Process, log: StringBuilder) {
        runCatching {
            process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4_096)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    synchronized(log) { log.appendRange(buffer, 0, n) }
                }
            }
        }
    }

    private fun stopProcess(process: Process, drainer: Thread) {
        process.destroyForcibly()
        runCatching { process.waitFor(2, TimeUnit.SECONDS) }
        drainer.join(1_000)
    }

    /**
     * 生成一路 `-i` 输入参数。
     *
     * - TS 分片（[init] 为 null）：写 concat 列表交给 concat demuxer；
     * - fMP4（有 [init]）：先把初始化段与分片拼成单个文件，再作为普通输入。
     *
     * 临时文件加入 [tempFiles]，由调用方在结束时删除。
     */
    private fun inputArgs(
        tag: String,
        init: File?,
        segments: List<File>,
        directory: File,
        tempFiles: MutableList<File>,
    ): List<String> {
        if (init == null) {
            val concat = File(directory, "$tag-concat.txt")
            concat.writeText(buildConcatFile(segments))
            tempFiles.add(concat)
            return listOf("-f", "concat", "-safe", "0", "-i", concat.absolutePath)
        }
        val joined = File(directory, "$tag-joined.mp4")
        FileOutputStream(joined).use { output ->
            FileInputStream(init).use { it.copyTo(output, COPY_BUFFER_SIZE) }
            segments.forEach { segment ->
                FileInputStream(segment).use { it.copyTo(output, COPY_BUFFER_SIZE) }
            }
        }
        tempFiles.add(joined)
        return listOf("-i", joined.absolutePath)
    }

    /**
     * concat demuxer 的路径用单引号包裹；路径内的 `'` 需写成 `'\''`。
     *
     * concat 列表以换行分隔，路径本身不能含 `\r`/`\n`（ffmpeg 无法转义），
     * 遇到时直接报错，避免生成一份指向错误文件的列表。
     * 公开为 internal 便于单测。
     */
    internal fun buildConcatFile(segments: List<File>): String =
        segments.joinToString(separator = "\n", postfix = "\n") { file ->
            val path = file.absolutePath
            if (path.contains('\n') || path.contains('\r')) {
                throw HlsException("分片路径包含换行，无法生成 ffmpeg concat 列表：$path")
            }
            "file '${path.replace("'", "'\\''")}'"
        }

    private fun moveIntoPlace(tmp: File, output: File) {
        try {
            Files.move(
                tmp.toPath(),
                output.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        /** 默认转封装超时：30 分钟；超时视为失败而不是永远停在 MERGING */
        const val DEFAULT_TIMEOUT_MS = 30L * 60 * 1000

        /** 等待 ffmpeg 时的取消轮询间隔 */
        const val CANCEL_POLL_MS = 250L

        /** 直拼/合并时的读写缓冲：大文件下比默认 8 KB 少很多系统调用 */
        const val COPY_BUFFER_SIZE = 64 * 1024
    }
}

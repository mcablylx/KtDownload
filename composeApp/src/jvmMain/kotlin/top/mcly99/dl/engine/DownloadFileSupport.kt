package top.mcly99.dl.engine

import top.mcly99.dl.utils.AppConstants
import java.io.File
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 下载任务的文件名与分块规划。
 *
 * 这些逻辑原先埋在 [DownloadEngine] 私有方法里，无法单独回归测试。
 * P3-2 将其提取为 internal 纯函数，引擎与 `jvmTest` 共用同一份实现。
 */

/** Windows 文件名中不允许出现的字符；`/` 与 `\` 同时用于防止路径穿越。 */
private const val ILLEGAL_FILE_NAME_CHARS = "<>:\"/\\|?*"

/** Windows 保留设备名（不区分大小写，带扩展名也算，如 `CON.txt`）。 */
private val RESERVED_FILE_NAMES = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL"))
    for (i in 1..9) {
        add("COM$i")
        add("LPT$i")
    }
}

/**
 * 从 URL 提取文件名；忽略 query / fragment，按 UTF-8 解码百分号转义，无法提取时使用时间戳兜底。
 *
 * 解码会把 `%2F` 变成 `/`，但 [sanitizeDownloadFileName] 只保留最后一段，
 * 因此不会产生目录穿越。
 */
internal fun extractFileName(
    url: String,
    fallbackTimeMillis: Long = System.currentTimeMillis(),
): String = try {
    val rawPath = URL(url).path
    // URLDecoder 会把 `+` 当作空格；URL 路径中的 `+` 是字面量，先转义再解码
    val decoded = runCatching {
        URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8)
    }.getOrDefault(rawPath)
    sanitizeDownloadFileName(decoded, fallback = "download_$fallbackTimeMillis")
} catch (_: Exception) {
    "download_$fallbackTimeMillis"
}

/**
 * P3-06：解析 RFC 6266 `Content-Disposition` 中的文件名。
 *
 * `filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf` 优先于普通 `filename=`；
 * 两者都解析不出时返回 [fallback]。结果仍交给 [sanitizeDownloadFileName] 清洗，
 * 因此 `../../etc/passwd` 只会得到 `passwd`，不会形成路径穿越。
 *
 * 注意：本函数只提供解析能力。主链路在任务入队时就要确定 savePath 与分片目录，
 * 此时还没有响应头；下载开始后再改名会牵动去重、`.partN`/`.offset` 与清理路径，
 * 风险高于收益，故当前未接入（云盘直链本身已带 `filename`）。
 */
internal fun extractFileNameFromContentDisposition(
    header: String?,
    fallback: String,
): String {
    if (header.isNullOrBlank()) return fallback
    val extended = Regex(
        """filename\*\s*=\s*([^';]*)(?:'[^']*)?'([^;]+)""",
        RegexOption.IGNORE_CASE,
    ).find(header)
    if (extended != null) {
        val charset = runCatching { Charset.forName(extended.groupValues[1].ifBlank { "UTF-8" }) }
            .getOrDefault(StandardCharsets.UTF_8)
        val raw = extended.groupValues[2].trim().trim('"')
        val decoded = runCatching { URLDecoder.decode(raw, charset) }.getOrDefault(raw)
        val cleaned = sanitizeDownloadFileName(decoded, fallback)
        if (cleaned != fallback) return cleaned
    }
    val plain = Regex("""filename\s*=\s*(?:"([^"]*)"|([^;]+))""", RegexOption.IGNORE_CASE)
        .find(header)
        ?.let { it.groupValues[1].ifBlank { it.groupValues[2] }.trim() }
    return plain?.takeIf { it.isNotBlank() }
        ?.let { sanitizeDownloadFileName(it, fallback) }
        ?: fallback
}

/**
 * 清洗单个文件名：只保留最后一段、替换 Windows 非法字符、处理保留名并限制长度。
 *
 * 返回值保证非空；无法得到有效名称时使用 [fallback]。
 */
internal fun sanitizeDownloadFileName(rawName: String, fallback: String): String {
    val lastSegment = rawName.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = buildString {
        for (ch in lastSegment.trim()) {
            append(if (ch.code < 0x20 || ch in ILLEGAL_FILE_NAME_CHARS) '_' else ch)
        }
    }.trim().trimEnd('.', ' ').ifBlank { fallback }

    val base = cleaned.substringBefore('.').trim().uppercase()
    val safe = if (base in RESERVED_FILE_NAMES) "_$cleaned" else cleaned
    return truncateFileName(safe)
}

/** 超长文件名截断；尽量保留扩展名，避免变成无法识别的文件类型。 */
private fun truncateFileName(name: String): String {
    val max = AppConstants.MAX_DOWNLOAD_FILE_NAME_LENGTH
    if (name.length <= max) return name

    val dot = name.lastIndexOf('.')
    // 无扩展名、隐藏文件（.gitignore）或超长“扩展名”时直接截断正文
    if (dot <= 0 || name.length - dot > 32) return name.take(max).trimEnd('.', ' ')

    val extension = name.substring(dot)
    val base = name.substring(0, dot)
    return base.take(max - extension.length).trimEnd('.', ' ') + extension
}

/**
 * 解析任务保存路径：
 * - 未指定 → 默认目录 + 文件名；
 * - 已是目录 → 目录 + 文件名；
 * - 其他 → 视为完整文件路径。
 *
 * 不再用「文件名没有点就是目录」猜测调用方意图：目录由调用方先创建。
 */
internal fun resolveTaskPath(savePath: Path?, fileName: String, defaultDir: Path): Path = when {
    savePath == null -> defaultDir.resolve(fileName)
    savePath.toFile().isDirectory -> savePath.resolve(fileName)
    else -> savePath
}

/**
 * 同名文件自动追加 `(1)`、`(2)` 后缀。
 *
 * 只检查目标文件是否存在，保留原扩展名；无扩展名时直接在末尾追加序号。
 * 隐藏文件（如 `.gitignore`）没有扩展名，序号追加在完整名称后。
 *
 * @param extraExists 额外占用判断：例如 HLS 任务还要检查 `{name}.hls` 目录、已排队任务占用同一路径
 */
internal fun handleDuplicateFile(path: Path, extraExists: (Path) -> Boolean = { false }): Path {
    fun taken(candidate: Path): Boolean = candidate.toFile().exists() || extraExists(candidate)

    if (!taken(path)) return path
    val parent = path.parent ?: return path
    val fileName = path.fileName.toString()
    val dot = fileName.lastIndexOf('.')
    val hasExtension = dot > 0
    val nameWithoutExt = if (hasExtension) fileName.substring(0, dot) else fileName
    val extension = if (hasExtension) fileName.substring(dot) else ""
    var counter = 1
    var newPath: Path
    do {
        newPath = parent.resolve("$nameWithoutExt($counter)$extension")
        counter++
    } while (taken(newPath))
    return newPath
}

/**
 * HLS 输出统一为 `.mp4`：用户自定义名称缺少扩展名或写成 `.m3u8` 时替换扩展名。
 * 已存在的其它扩展名（如 `.mkv`）也会改为 `.mp4`，因为合成结果就是 MP4。
 */
internal fun ensureHlsOutputPath(path: Path): Path {
    val name = path.fileName.toString()
    if (name.endsWith(".mp4", ignoreCase = true)) return path
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val newName = "$base.mp4"
    return path.parent?.resolve(newName) ?: Paths.get(newName)
}

/**
 * 添加任务对话框里「预计会存成什么名字」。
 *
 * 必须与 [DownloadEngine.addTask] 的推导保持一致：`extractFileName` + HLS 换成 `.mp4`
 * （[ensureHlsOutputPath]）。抽成一个函数是为了让 UI 不复制一份规则 ——
 * 两份实现早晚会分叉（v6-P2-1）。
 *
 * @return 不含目录的纯文件名；URL 无法解析时返回时间戳兜底名
 */
internal fun previewFileName(url: String): String {
    val name = extractFileName(url)
    if (top.mcly99.dl.utils.detectUrlType(url) != top.mcly99.dl.model.TaskType.M3u8) return name
    return ensureHlsOutputPath(Paths.get(name)).fileName.toString()
}

/** HLS 分片工作目录：`movie.mp4` → 同目录下的 `movie.hls` */
internal fun hlsSegmentDir(output: File): File {
    val name = output.name
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    // 兜底：任务目标本身是根目录 / 盘符根时（`name` 为空、`parentFile` 为 null），
    // 不能退化成 `File(null, ".hls")` —— 那是一个**相对进程工作目录**的路径，
    // 分片会被写到与本任务无关的地方，后续清理也会指错目标。
    // 这里改成「根目录下的 `.hls`」，至少与任务目标同盘且可被清理闸门认清。
    val parent = output.parentFile ?: output.absoluteFile
    val dirName = if (base.isBlank()) ".hls" else "$base.hls"
    return File(parent, dirName)
}

/**
 * 多线程下载过程中的单文件临时产物。
 *
 * 从「N 个 `.partN` + 合并」改成「一个 `.part` 按绝对偏移写 + 完成后改名」之后：
 * 少一整遍读写、峰值磁盘占用减半、也不会再卡在 `MERGING` 99%。
 * `.part` 与旧的 `.part0` 不同名，升级后不会认错老文件。
 */
internal fun partialDownloadFile(target: File): File =
    File(target.parentFile, "${target.name}.part")

/**
 * 段表快照：记录「哪些区间还没下完、各自写到哪」。
 *
 * 它替代了 `.partN.offset` 与 `.chunks.plan` 两件事。旧模型靠「分片数 + 线程数」
 * 判断计划是否漂移，段表模型不需要：未完成区间本身就是计划，线程数改了也照样能接着下。
 * 唯一的失效条件是**文件长度或内容指纹变了**，那时整份作废重下。
 */
internal fun segmentPlanFile(target: File): File =
    File(target.parentFile, "${target.name}.segments")

/**
 * 清理旧模型（≤1.0.4）留下的残片。
 *
 * `.partN` / `.partN.offset` / `.chunks.plan` / `.merging` 都还可能有用户磁盘上的残留：
 * 升级前暂停的任务、被强杀进程留下的半成品。不清理的话同名任务重下会与这些文件混淆，
 * 而「删任务后仍留着 xxx.part3.offset」正是 v6-P1-2 修过的现象。
 */
internal fun cleanupLegacyChunkArtifacts(target: File) {
    for (i in 0 until AppConstants.MAX_THREADS) {
        File(target.parentFile, "${target.name}.part$i").delete()
        File(target.parentFile, "${target.name}.part$i.offset").delete()
    }
    File(target.parentFile, "${target.name}.merging").delete()
    File(target.parentFile, "${target.name}.chunks.plan").delete()
}

/** 清掉一个任务的全部下载中间产物（不含用户的目标文件） */
internal fun cleanupDownloadArtifacts(target: File) {
    partialDownloadFile(target).delete()
    segmentPlanFile(target).delete()
    File(target.parentFile, "${segmentPlanFile(target).name}.tmp").delete()
    cleanupLegacyChunkArtifacts(target)
}

package top.mcly99.dl.engine.hls

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import top.mcly99.dl.APP_VERSION
import top.mcly99.dl.engine.HttpRedirects
import top.mcly99.dl.engine.setSafeHeader
import top.mcly99.dl.engine.RateLimiter
import top.mcly99.dl.engine.ThrottledInputStream
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import top.mcly99.dl.utils.formatBytes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * HLS 下载结果：交给合成层（T3）的文件清单。
 *
 * @property media 实际下载的媒体播放列表（已选最高清晰度）
 * @property segments 分片文件，按播放顺序排列；均已解密
 * @property initSegment fMP4 的 `#EXT-X-MAP` 初始化段；TS 流为 null
 * @property audioSegments 独立音轨（`#EXT-X-MEDIA:TYPE=AUDIO`）的分片；音轨内嵌时为空
 * @property audioInitSegment 独立音轨的初始化段；音轨为 TS 流或不存在时为 null
 * @property directory 分片目录，合成成功后可整体删除
 */
data class HlsDownloadResult(
    val media: HlsPlaylist.Media,
    val segments: List<File>,
    val initSegment: File?,
    val audioSegments: List<File> = emptyList(),
    val audioInitSegment: File? = null,
    val directory: File,
)

/**
 * HLS 下载层：把 m3u8 落地为磁盘上完整、有序的分片集合。
 *
 * - 自动跟随主列表并选择最高带宽（[HlsPlaylist.Master.selectHighestBandwidth]）
 * - 主列表声明独立音轨（`AUDIO=` / `#EXT-X-MEDIA`）时一并下载到 `{name}.hls/audio/`
 * - 分片并发下载，并发数由 [concurrency] 控制
 * - 支持 `AES-128`（[HlsAesDecryptor]）与 `#EXT-X-BYTERANGE`
 * - 分片流式写入 `*.part` 后原子改名，AES 边读边解密；播放列表/密钥/分片均有大小上限
 * - 断点续传：每片完成后写 `seg_XXXXX.done`（内容是该片的身份签名 + 落盘长度），
 *   重跑时只有签名仍然对得上的片才跳过（见 [HlsSegmentIdentity]）
 * - 失败重试：默认 1s/2s/4s 三次；401/403/404/410 等确定性 4xx 立即失败，429 优先遵循 `Retry-After`
 * - 仍失败则抛 [HlsException]，整体任务失败
 *
 * 直播流、DRM 与加密初始化段由 [M3u8Parser] 在解析阶段直接拒绝。
 *
 * @param concurrency 同时下载的分片数（生产传入 `downloadThreadsPerFile`）
 * @param retryDelaysMs 每次重试前的等待；元素个数即重试次数
 * @param rateLimiter 全局限速器；为 null 或未启用时不限速
 * @param proxy 下载连接使用的代理；为 null 时直连
 * @param referer 防盗链 `Referer` 初始值（通常是页面地址或任务 URL）；仅当它等于播放列表地址时，
 *   播放列表重定向后才改用最终地址 —— 页面 Referer 是跨域 CDN 认的身份，不能被覆盖
 * @param cookie 会话 `Cookie`（深度抓取抓回来的那一份）。带签名的播放列表常把会话也绑进去，
 *   缺了它播放列表/分片会 403（v6-P1-3）
 * @param browserUserAgent 是否用浏览器 UA。抓网页来的任务为 true —— 源站常按 UA 做防盗链，
 *   自报 `KtDownload/x.y.z` 会被判非浏览器；手动添加的任务保持 false，服务端日志里可追溯（v7-P1-2）
 * @param userAgent 平台专用 UA（网盘 HLS 直链用）。非空时优先于 [browserUserAgent]
 * @param origin `Origin` 头：UC / 蓝奏优享的 CDN 会校验跨域来源
 */
class HlsDownloader(
    private val concurrency: Int = 4,
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
    private val rateLimiter: RateLimiter? = null,
    private val proxy: Proxy? = null,
    private val referer: String? = null,
    private val cookie: String? = null,
    private val browserUserAgent: Boolean = false,
    private val userAgent: String? = null,
    private val origin: String? = null,
) {

    /** 实际用于 `Referer` 的地址：播放列表重定向后更新为最终地址 */
    @Volatile
    private var effectiveReferer: String? = referer

    /**
     * 下载 [playlistUrl] 的全部分片到 [outputDir]。
     *
     * @param onConnection 连接登记回调：`(key, 连接)` 表示打开，`(key, null)` 表示关闭。
     *   引擎用它实现暂停/取消时立即断开阻塞读；key 已按本次下载唯一化。
     * @param onProgress 已处理片数 / 总片数；分片并发完成时会从多个线程调用
     * @param onBytes 累计已下载字节数（含初始化段与续传时已在盘上的分片），
     *   用于算下载速度。分片并发下载时会从多个线程调用，但**回调顺序与数值顺序一致**，
     *   参数单调不减（v6-P1-1）
     * @return 有序分片清单，供 ffmpeg 合成
     * @throws HlsException 播放列表非法、直播/DRM、密钥或分片重试耗尽时
     */
    suspend fun download(
        playlistUrl: String,
        outputDir: File,
        onConnection: (key: String, connection: HttpURLConnection?) -> Unit = { _, _ -> },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onBytes: (Long) -> Unit = {},
    ): HlsDownloadResult = withContext(Dispatchers.IO) {
        val tracker = ConnectionTracker(onConnection)
        val loaded = loadMedia(playlistUrl, tracker)
        // 播放列表可能重定向：只有「Referer 本来就是播放列表自己」时才跟随改写成最终地址。
        // 页面 Referer 是跨域 CDN 认的身份（下载防盗链校验它），不能被播放列表地址顶掉。
        if (referer == null || referer == playlistUrl) {
            effectiveReferer = loaded.finalUrl
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw HlsException("无法创建分片目录：$outputDir")
        }

        val audioMedia = loaded.audio?.let { rendition ->
            val fetched = fetchText(rendition.uri!!, tracker)
            M3u8Parser.parse(fetched.text, fetched.url, loaded.sessionKey) as? HlsPlaylist.Media
                ?: throw HlsException("音轨列表指向的仍是主列表，暂不支持多级嵌套")
        }
        val total = loaded.media.segments.size + (audioMedia?.segments?.size ?: 0)
        val done = AtomicInteger(0)
        val onSegmentDone: () -> Unit = { onProgress(done.incrementAndGet(), total) }
        // 分片并发写，累计值必须是原子量；对外只在变化时才回调，避免每个 8KB 都刷一次 UI。
        // 「自增 + 回调」必须整体串行：否则 A 线程先拿到 768、B 线程后拿到 512，
        // 但两次回调可能反过来执行，下游就会收到 768→512 的倒退曲线（v6-P1-1）
        val byteCounter = AtomicLong(0)
        val byteLock = Any()
        val emitBytes: (Long) -> Unit = { n ->
            if (n > 0) synchronized(byteLock) { onBytes(byteCounter.addAndGet(n)) }
        }

        // 初始化段很小且必须排在最前，每次重跑都刷新，避免留下半截文件
        val initSegment = writeInitSegment(loaded.media, outputDir, tracker, emitBytes)
        val segments = downloadSegments(loaded.media, outputDir, tracker, onSegmentDone, emitBytes)
        val audioDir = File(outputDir, AUDIO_DIR_NAME)
        val audioInitSegment = audioMedia?.let { writeInitSegment(it, audioDir, tracker, emitBytes) }
        val audioSegments = audioMedia?.let { downloadSegments(it, audioDir, tracker, onSegmentDone, emitBytes) }
            ?: emptyList()

        HlsDownloadResult(
            media = loaded.media,
            segments = segments,
            initSegment = initSegment,
            audioSegments = audioSegments,
            audioInitSegment = audioInitSegment,
            directory = outputDir,
        )
    }

    // ------------------------------------------------------------------ 播放列表

    /** 已选定的媒体列表及其最终地址（重定向后）。 */
    private data class LoadedMedia(
        val media: HlsPlaylist.Media,
        val finalUrl: String,
        val audio: HlsRendition?,
        /** 主列表的 `#EXT-X-SESSION-KEY`，供独立音轨列表继承 */
        val sessionKey: HlsKey? = null,
    )

    private suspend fun loadMedia(url: String, tracker: ConnectionTracker): LoadedMedia {
        val first = fetchText(url, tracker)
        return when (val playlist = M3u8Parser.parse(first.text, first.url)) {
            is HlsPlaylist.Media -> LoadedMedia(playlist, first.url, null)
            is HlsPlaylist.Master -> {
                val variant = playlist.selectHighestBandwidth()
                val fetched = fetchText(variant.uri, tracker)
                val media = M3u8Parser.parse(fetched.text, fetched.url, playlist.sessionKey) as? HlsPlaylist.Media
                    ?: throw HlsException("清晰度列表指向的仍是主列表，暂不支持多级嵌套")
                LoadedMedia(media, fetched.url, playlist.selectAudio(variant), playlist.sessionKey)
            }
        }
    }

    // ------------------------------------------------------------------ 解密

    /** 预取所有密钥：同一 URI 只请求一次，失败即整体失败 */
    private suspend fun fetchKeys(media: HlsPlaylist.Media, tracker: ConnectionTracker): Map<String, ByteArray> {
        val cache = HashMap<String, ByteArray>()
        for (key in media.segments.mapNotNull { it.key }.distinctBy { it.uri }) {
            val uri = key.uri ?: throw HlsException("#EXT-X-KEY 缺少密钥地址")
            val bytes = fetchBytes("密钥", uri, maxBytes = KEY_MAX_BYTES, tracker = tracker).bytes
            if (bytes.size != AES_KEY_BYTES) {
                throw HlsException("AES-128 密钥长度应为 $AES_KEY_BYTES 字节，实际 ${bytes.size}")
            }
            cache[uri] = bytes
        }
        return cache
    }

    // ------------------------------------------------------------------ 分片

    /**
     * 下载一个媒体列表的全部分片到 [outputDir]。
     *
     * @param onSegmentDone 每完成一片回调一次（含跳过续传的片），用于合并音视频进度
     */
    private suspend fun downloadSegments(
        media: HlsPlaylist.Media,
        outputDir: File,
        tracker: ConnectionTracker,
        onSegmentDone: () -> Unit,
        onBytes: (Long) -> Unit = {},
    ): List<File> {
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw HlsException("无法创建分片目录：$outputDir")
        }
        discardStaleSegments(outputDir, media)
        val keyCache = fetchKeys(media, tracker)
        val total = media.segments.size
        val files = arrayOfNulls<File>(total)
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        val reused = AtomicInteger(0)

        coroutineScope {
            media.segments.forEachIndexed { index, segment ->
                launch {
                    semaphore.withPermit {
                        val file = File(outputDir, segmentName(segment.sequence))
                        if (!isCompleted(outputDir, segment, file)) {
                            val key = segment.key
                            val keyBytes = key?.let {
                                val uri = it.uri ?: throw HlsException("分片 ${segment.sequence} 缺少密钥地址")
                                keyCache[uri] ?: throw HlsException("分片 ${segment.sequence} 缺少密钥")
                            }
                            val iv = key?.let {
                                if (it.iv != null) HlsAesDecryptor.ivFromHex(it.iv) else HlsAesDecryptor.ivFromSequence(segment.sequence)
                            }
                            fetchToFile(
                                label = "分片 ${segment.sequence}",
                                uri = segment.uri,
                                byteRange = segment.byteRange,
                                target = file,
                                maxBytes = SEGMENT_MAX_BYTES,
                                tracker = tracker,
                                key = keyBytes,
                                iv = iv,
                                onBytes = onBytes,
                            )
                            doneFile(outputDir, segment.sequence).writeText(
                                HlsSegmentIdentity.doneContent(segment, file.length()),
                            )
                        } else {
                            // 续传跳过的分片也算「已下载」：否则断点续传后速度看起来像从 0 开始，
                            // 已下载字节数也会比磁盘上的实际内容少一大截
                            reused.incrementAndGet()
                            onBytes(file.length())
                        }
                        files[index] = file
                        onSegmentDone()
                    }
                }
            }
        }
        if (reused.get() > 0) {
            AppLogger.info(
                "HlsDownloader",
                "续传复用 ${reused.get()}/$total 个分片，重下 ${total - reused.get()} 个：${outputDir.name}",
            )
        }
        return files.map { it ?: throw HlsException("分片清单不完整") }
    }

    /** 下载并写入 fMP4 初始化段；媒体列表没有 `#EXT-X-MAP` 时返回 null */
    private suspend fun writeInitSegment(
        media: HlsPlaylist.Media,
        outputDir: File,
        tracker: ConnectionTracker,
        onBytes: (Long) -> Unit = {},
    ): File? = media.map?.let { map ->
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw HlsException("无法创建分片目录：$outputDir")
        }
        File(outputDir, INIT_SEGMENT_NAME).also { file ->
            fetchToFile(
                label = "初始化段",
                uri = map.uri,
                byteRange = map.byteRange,
                target = file,
                maxBytes = INIT_MAX_BYTES,
                tracker = tracker,
                onBytes = onBytes,
            )
        }
    }

    // ------------------------------------------------------------------ 续传

    /**
     * 这一片能不能跳过。
     *
     * 除了「文件在、`.done` 在」，还要求 `.done` 里的身份签名就是当前这一片的签名 ——
     * 改动前只看 `.done` 是否存在，同一路径换了内容会被原样复用（拼出坏视频）。
     */
    private fun isCompleted(outputDir: File, segment: HlsSegment, file: File): Boolean {
        if (!file.exists()) return false
        val length = file.length()
        if (length <= 0) return false
        val done = doneFile(outputDir, segment.sequence)
        if (!done.exists()) return false
        val text = runCatching { done.readText() }.getOrNull()
        return HlsSegmentIdentity.matches(text, segment, length)
    }

    /**
     * 删掉不属于当前播放列表的分片与标记。
     *
     * 播放列表变短（换成了更短的视频、或剪辑版）时，上次留下的尾部分片会一直躺在磁盘上：
     * 合成用不到，却会在下次续传时被当成「已有内容」，也会让分片目录无限膨胀。
     *
     * 按**序号集合**判断而不是「前 N 个」：`#EXT-X-MEDIA-SEQUENCE` 不保证从 0 开始，
     * 序号可以整体偏移或跳号。
     */
    private fun discardStaleSegments(outputDir: File, media: HlsPlaylist.Media) {
        val files = outputDir.listFiles() ?: return
        val keep = media.segments.mapTo(HashSet(media.segments.size)) { it.sequence }
        var removed = 0
        for (file in files) {
            val sequence = SEGMENT_FILE_PATTERN.find(file.name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (sequence in keep) continue
            if (file.delete()) removed++
        }
        if (removed > 0) {
            AppLogger.info("HlsDownloader", "播放列表已变更，丢弃 $removed 个旧分片文件：${outputDir.name}")
        }
    }

    private fun segmentName(sequence: Int): String = "seg_${sequence.toString().padStart(5, '0')}.ts"

    private fun doneFile(outputDir: File, sequence: Int): File =
        File(outputDir, "seg_${sequence.toString().padStart(5, '0')}.done")

    // ------------------------------------------------------------------ 网络

    private data class FetchedText(val text: String, val url: String)

    private data class FetchedBytes(val bytes: ByteArray, val finalUrl: String)

    private suspend fun fetchText(url: String, tracker: ConnectionTracker): FetchedText {
        val fetched = fetchBytes("播放列表", url, maxBytes = PLAYLIST_MAX_BYTES, tracker = tracker)
        return FetchedText(String(fetched.bytes, Charsets.UTF_8), fetched.finalUrl)
    }

    /**
     * 下载 [uri] 的完整内容到内存；[byteRange] 非空时只取该区间。
     *
     * 只用于播放列表与密钥这类小文件；分片走 [fetchToFile] 流式落盘。
     * 返回的 [FetchedBytes.finalUrl] 是跟随重定向后的地址，用于解析相对路径与生成 `Referer`。
     */
    private suspend fun fetchBytes(
        label: String,
        uri: String,
        maxBytes: Long,
        byteRange: LongRange? = null,
        tracker: ConnectionTracker,
    ): FetchedBytes = withConnection(label, uri, byteRange, tracker, maxBytes) { connection, expected ->
        val out = ByteArrayOutputStream()
        val received = copyLimited(connection, label, maxBytes) { buffer, count ->
            out.write(buffer, 0, count)
        }
        if (expected >= 0 && received != expected) {
            throw IOException("$label 长度不符：$received != $expected")
        }
        FetchedBytes(out.toByteArray(), connection.url.toString())
    }

    /**
     * 流式下载 [uri] 到 [target]：先写临时文件，完整校验后再原子改名。
     *
     * 分片可能很大，不能整段读进内存。加密分片先把密文落到 `*.part.enc`，
     * 再流式解密到 `*.part`：规范要求 PKCS#7 填充，但少数编码器输出无填充密文，
     * 此时按 `NoPadding` 重解一次（P3-5）。
     *
     * @return 跟随重定向后的最终地址
     */
    private suspend fun fetchToFile(
        label: String,
        uri: String,
        byteRange: LongRange?,
        target: File,
        maxBytes: Long,
        tracker: ConnectionTracker,
        key: ByteArray? = null,
        iv: ByteArray? = null,
        onBytes: (Long) -> Unit = {},
    ): String = withConnection(label, uri, byteRange, tracker, maxBytes) { connection, expected ->
        val part = File(target.parentFile, "${target.name}.part")
        val sink = if (key != null) File(target.parentFile, "${target.name}.part.enc") else part
        part.delete()
        sink.delete()
        try {
            val received = FileOutputStream(sink).use { out ->
                copyLimited(connection, label, maxBytes) { buffer, count ->
                    out.write(buffer, 0, count)
                    // 边下边报字节：这是唯一能拿到「已收多少」的地方（v6-P1-1）
                    onBytes(count.toLong())
                }
            }
            if (expected >= 0 && received != expected) {
                throw IOException("$label 长度不符：$received != $expected")
            }
            if (key != null) {
                decryptToFile(
                    source = sink,
                    target = part,
                    key = key,
                    iv = iv ?: throw HlsException("$label 缺少解密 IV"),
                )
                sink.delete()
            }
            moveAtomically(part, target)
            connection.url.toString()
        } catch (e: Exception) {
            part.delete()
            sink.delete()
            throw e
        }
    }

    /**
     * 把 [source] 中的密文解密到 [target]。
     *
     * 先按规范用 PKCS#7 解密；遇到填充错误再按无填充重解一次，兼容少数
     * 输出整块密文的编码器。两次都是流式处理，内存占用与分片大小无关。
     */
    private fun decryptToFile(source: File, target: File, key: ByteArray, iv: ByteArray) {
        try {
            decryptToFile(source, target, key, iv, padded = true)
        } catch (e: GeneralSecurityException) {
            target.delete()
            try {
                decryptToFile(source, target, key, iv, padded = false)
                AppLogger.warn("HlsDownloader", "分片不带 PKCS#7 填充，已按 NoPadding 解密：${source.name}")
            } catch (retry: Exception) {
                target.delete()
                throw HlsException("分片解密失败：${retry.message}", cause = retry)
            }
        }
    }

    private fun decryptToFile(
        source: File,
        target: File,
        key: ByteArray,
        iv: ByteArray,
        padded: Boolean,
    ) {
        val cipher = HlsAesDecryptor.newDecryptCipher(key, iv, padded)
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(DECRYPT_BUFFER_SIZE)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    cipher.update(buffer, 0, n)?.let { plain ->
                        if (plain.isNotEmpty()) out.write(plain)
                    }
                }
                out.write(cipher.doFinal())
            }
        }
    }

    /** 打开连接并校验状态码/大小；[block] 负责读取响应体。 */
    private suspend fun <T> withConnection(
        label: String,
        uri: String,
        byteRange: LongRange?,
        tracker: ConnectionTracker,
        maxBytes: Long,
        block: suspend (HttpURLConnection, Long) -> T,
    ): T = withRetry(label) {
        val connection = newConnection(uri)
        val key = tracker.register(connection)
        try {
            if (byteRange != null) {
                connection.setSafeHeader("Range", "bytes=${byteRange.first}-${byteRange.last}")
            }
            connection.connect()
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                val detail = httpFailure(label, uri, code)
                // 401/403/404/410 重试没有意义，直接失败；5xx/408/429 仍走重试
                if (code in RETRYABLE_HTTP_STATUS) {
                    throw HttpStatusException(code, retryAfterMs(connection), detail)
                }
                throw HlsException(detail)
            }
            if (byteRange != null && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("服务器未返回请求的字节区间（HTTP $code）")
            }
            val expected = byteRange?.let { it.last - it.first + 1 } ?: connection.contentLengthLong
            if (expected > maxBytes) {
                throw HlsException("$label 超过大小上限（${formatBytes(maxBytes)}）")
            }
            block(connection, expected)
        } finally {
            tracker.release(key)
            runCatching { connection.disconnect() }
        }
    }

    /** 可读的 HTTP 错误：带状态码与 host/path，去掉可能含 token 的 query */
    private fun httpFailure(label: String, uri: String, code: Int): String {
        val location = runCatching {
            val parsed = URI(uri)
            buildString {
                parsed.host?.let { append(it) }
                append(parsed.path ?: "")
            }
        }.getOrElse { uri.substringBefore('?') }
        return "$label 请求失败：HTTP $code（$location）"
    }

    /** 读取 429/503 的 `Retry-After`（秒），超过上限时忽略，避免任务被拖太久 */
    private fun retryAfterMs(connection: HttpURLConnection): Long? =
        connection.getHeaderField("Retry-After")
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it in 0..MAX_RETRY_AFTER_SECONDS }
            ?.times(1_000)

    /**
     * 把响应体按块交给 [sink]，返回实际读取的原始字节数。
     *
     * 超过 [maxBytes] 时立即中止，避免异常/恶意响应把磁盘或内存撑爆。
     */
    private suspend fun copyLimited(
        connection: HttpURLConnection,
        label: String,
        maxBytes: Long,
        sink: (ByteArray, Int) -> Unit,
    ): Long {
        val job = currentCoroutineContext()[Job]
        val raw = connection.inputStream
        val input = if (rateLimiter?.enabled == true) {
            ThrottledInputStream(raw, rateLimiter) { job?.isActive != false }
        } else {
            raw
        }
        return input.use { stream ->
            val buffer = ByteArray(AppConstants.CHUNK_BUFFER_SIZE)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = stream.read(buffer)
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    throw HlsException("$label 超过大小上限（${formatBytes(maxBytes)}）")
                }
                sink(buffer, n)
            }
            total
        }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private suspend fun <T> withRetry(label: String, block: suspend () -> T): T {
        var last: Exception? = null
        repeat(retryDelaysMs.size + 1) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HlsException) {
                // 大小上限/协议限制等确定性错误重试没有意义
                throw e
            } catch (e: HttpStatusException) {
                last = e
                if (attempt < retryDelaysMs.size) {
                    val waitMs = e.retryAfterMs ?: retryDelaysMs[attempt]
                    AppLogger.warn(
                        "HlsDownloader",
                        "$label 第 ${attempt + 1} 次失败：${e.message}，${waitMs}ms 后重试",
                    )
                    delay(waitMs)
                }
            } catch (e: Exception) {
                last = e
                val waitMs = retryDelaysMs.getOrNull(attempt)
                if (waitMs != null) {
                    AppLogger.warn("HlsDownloader", "$label 第 ${attempt + 1} 次失败：${e.message}，${waitMs}ms 后重试")
                    delay(waitMs)
                }
            }
        }
        throw HlsException("$label 下载失败：${last?.message ?: "未知错误"}", cause = last)
    }

    /**
     * 建立连接。
     *
     * 带 `Cookie` 时不交给 JDK 自动跟随：`instanceFollowRedirects` 会复制请求头，
     * 302 跨域时会把会话送到第三方域（v7-P2-2）。自己跟随才能在 host 变化时摘掉 Cookie。
     */
    private fun newConnection(url: String): HttpURLConnection =
        if (cookie.isNullOrBlank()) {
            rawConnection(url, cookie = null, followRedirects = true)
        } else {
            followRedirectsKeepingCookie(url, cookie)
        }

    private fun rawConnection(url: String, cookie: String?, followRedirects: Boolean): HttpURLConnection {
        val normalized = encodeUrl(url)
        val connection = if (proxy != null) {
            URL(normalized).openConnection(proxy)
        } else {
            URL(normalized).openConnection()
        }
        return (connection as HttpURLConnection).apply {
            connectTimeout = AppConstants.CONNECT_TIMEOUT_MS
            readTimeout = AppConstants.READ_TIMEOUT_MS
            instanceFollowRedirects = followRedirects
            setSafeHeader(
                "User-Agent",
                userAgent?.takeIf { it.isNotBlank() }
                    ?: if (browserUserAgent) AppConstants.CATCH_WEB_USER_AGENT else "KtDownload/$APP_VERSION",
            )
            effectiveReferer?.let { referer -> setSafeHeader("Referer", referer) }
            cookie?.takeIf { it.isNotBlank() }?.let { setSafeHeader("Cookie", it) }
            origin?.takeIf { it.isNotBlank() }?.let { setSafeHeader("Origin", it) }
        }
    }

    /** 手动跟随重定向；host 变了就不再带 Cookie（与 `DownloadEngine` 同一策略） */
    private fun followRedirectsKeepingCookie(url: String, cookie: String): HttpURLConnection {
        var current = url
        var currentCookie: String? = cookie
        repeat(HttpRedirects.MAX_HOPS) {
            val connection = rawConnection(current, currentCookie, followRedirects = false)
            val code = try {
                connection.responseCode
            } catch (e: Exception) {
                runCatching { connection.disconnect() }
                throw e
            }
            if (code !in HttpRedirects.CODES) return connection
            val location = connection.getHeaderField("Location")
            runCatching { connection.disconnect() }
            if (location.isNullOrBlank()) throw IOException("HTTP $code 但没有 Location 头")
            val next = HttpRedirects.resolve(current, location)
            if (!HttpRedirects.sameHost(current, next)) currentCookie = null
            current = next
        }
        throw IOException("重定向次数超过 ${HttpRedirects.MAX_HOPS} 次：$url")
    }

    /**
     * 规范化 URL：优先交给 [URI] 做 ASCII 转义；解析失败时把空格与非 ASCII
     * 字符按 UTF-8 百分号编码，避免播放列表里含中文/空格时 `URL()` 直接报错。
     */
    private fun encodeUrl(raw: String): String {
        val trimmed = raw.trim()
        return runCatching { URI(trimmed).toASCIIString() }.getOrElse {
            buildString {
                var index = 0
                while (index < trimmed.length) {
                    val codePoint = trimmed.codePointAt(index)
                    val chars = Character.charCount(codePoint)
                    when {
                        codePoint == ' '.code -> append("%20")
                        codePoint < 0x80 -> append(trimmed, index, index + chars)
                        else -> codePoint.toString().toByteArray(Charsets.UTF_8).forEach { byte ->
                            val value = byte.toInt() and 0xFF
                            append('%').append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
                        }
                    }
                    index += chars
                }
            }
        }
    }

    private companion object {
        const val AES_KEY_BYTES = 16
        const val INIT_SEGMENT_NAME = "init.mp4"
        const val AUDIO_DIR_NAME = "audio"
        const val HEX_DIGITS = "0123456789ABCDEF"

        /**
         * 分片 / 标记文件名。
         *
         * 用 `\d+` 而不是 `\d{5}`：`segmentName` 的补零只有下限，序号超过 99999 时
         * 文件名会自然变成 6 位，写死 5 位会把它们当成「不认识的残留文件」漏掉。
         */
        val SEGMENT_FILE_PATTERN = Regex("""^seg_(\d+)\.(ts|done)$""")

        /** 播放列表文本上限：正常 m3u8 远小于此值 */
        const val PLAYLIST_MAX_BYTES = 8L * 1024 * 1024

        /** 密钥上限：AES-128 密钥固定 16 字节 */
        const val KEY_MAX_BYTES = 1024L

        /** 单个分片上限：覆盖 4K 长分片，同时避免异常响应拖垮进程 */
        const val SEGMENT_MAX_BYTES = 512L * 1024 * 1024

        /** 初始化段上限 */
        const val INIT_MAX_BYTES = 64L * 1024 * 1024

        /** 解密时的读写缓冲 */
        const val DECRYPT_BUFFER_SIZE = 64 * 1024

        /** 值得重试的 HTTP 状态：5xx、请求超时与限流 */
        val RETRYABLE_HTTP_STATUS = setOf(408, 429, 500, 502, 503, 504)

        /** `Retry-After` 上限：超过则改用默认退避，避免任务被拖太久 */
        const val MAX_RETRY_AFTER_SECONDS = 60L

        val DEFAULT_RETRY_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L)
    }
}

/**
 * 可重试的 HTTP 状态错误；携带服务端 `Retry-After`（毫秒）。
 *
 * 401/403/404/410 等确定性状态不会构造该异常，而是直接抛 [HlsException]。
 */
private class HttpStatusException(
    val code: Int,
    val retryAfterMs: Long?,
    message: String,
) : IOException(message)

/**
 * 为每次下载生成互不相同的连接 key，让引擎能精确断开本次任务占用的连接。
 * key 前缀带对象身份哈希，旧任务残留的 `release` 不会误删新任务的同名条目。
 */
private class ConnectionTracker(
    private val onConnection: (String, HttpURLConnection?) -> Unit,
) {
    private val ids = AtomicInteger(0)
    private val prefix = "hls-${System.identityHashCode(this)}-"

    fun register(connection: HttpURLConnection): String =
        "$prefix${ids.incrementAndGet()}".also { onConnection(it, connection) }

    fun release(key: String) = onConnection(key, null)
}

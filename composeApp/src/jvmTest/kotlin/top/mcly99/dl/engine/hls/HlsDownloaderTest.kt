package top.mcly99.dl.engine.hls

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HlsDownloader] 集成测试：用本地 [HttpServer] 提供假的 m3u8 与分片。
 *
 * 覆盖计划 T2-3 要求的四条路径（无加密 / AES-128 / 断点续传 / 直播拒绝），
 * 另加最高清晰度、fMP4 初始化段、重试与重试耗尽。
 */
class HlsDownloaderTest {

    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private lateinit var tempDir: File

    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val flakyAttempts = AtomicInteger(0)
    private val limitedAttempts = AtomicInteger(0)
    private val originHits = AtomicInteger(0)
    private val proxyHits = AtomicInteger(0)
    private val referers = ConcurrentHashMap<String, String>()
    private var proxyServer: HttpServer? = null
    private var proxyExecutor: ExecutorService? = null

    /** `/swap/` 这一组路由的可变状态，用来模拟「同一条播放列表地址背后换了内容」 */
    @Volatile
    private var swapSign = ""

    @Volatile
    private var swapSegmentCount = 3

    @Volatile
    private var swapDuration = 4.0

    @Volatile
    private var swapRenameMiddle = false

    private val plainSegments = List(3) { i -> ByteArray(256) { b -> (i * 97 + b).toByte() } }
    private val aesKey = ByteArray(16) { (it + 1).toByte() }
    private val explicitIv = "000102030405060708090a0b0c0d0e0f"
    private val aesSegments = List(3) { i -> ByteArray(300) { b -> (i * 41 + b).toByte() } }

    /** 无 PKCS#7 填充的加密分片：长度必须是 16 的整数倍 */
    private val unpaddedSegments = List(3) { i -> ByteArray(304) { b -> (i * 43 + b).toByte() } }
    private val hiData = ByteArray(320) { (it * 13 + 7).toByte() }
    private val loData = ByteArray(320) { (it * 3 + 1).toByte() }
    private val fmp4Init = ByteArray(64) { (it + 9).toByte() }
    private val fmp4Segments = List(2) { i -> ByteArray(128) { b -> (i * 23 + b).toByte() } }
    private val byteRangeBlob = ByteArray(64) { (it * 5 + 2).toByte() }
    private val audioSegments = List(2) { i -> ByteArray(192) { b -> (i * 61 + b).toByte() } }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("ktdown-hls-test").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        executor = Executors.newFixedThreadPool(8)
        server.executor = executor
        server.createContext("/") { exchange ->
            originHits.incrementAndGet()
            route(exchange)
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        executor.shutdownNow()
        proxyServer?.stop(0)
        proxyExecutor?.shutdownNow()
        tempDir.deleteRecursively()
    }

    // ---------------------------------------------------------------- T2-3 必测路径

    @Test
    fun downloads_plain_segments_in_order() = runBlocking {
        val outDir = File(tempDir, "plain.hls")
        val progress = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())

        val result = downloader().download(
            url("/plain/index.m3u8"),
            outDir,
            onProgress = { done, total -> progress.add(done to total) },
        )

        assertEquals(3, result.segments.size)
        result.segments.forEachIndexed { i, file ->
            assertEquals("seg_${i.toString().padStart(5, '0')}.ts", file.name)
            assertContentEquals(plainSegments[i], file.readBytes())
            assertTrue(File(outDir, "seg_${i.toString().padStart(5, '0')}.done").exists())
        }
        assertNull(result.initSegment)
        assertEquals(setOf(1 to 3, 2 to 3, 3 to 3), progress.toSet())
    }

    @Test
    fun decrypts_aes128_with_explicit_iv() = runBlocking {
        val result = downloader().download(url("/aes/index.m3u8"), File(tempDir, "aes.hls"))

        result.segments.forEachIndexed { i, file ->
            assertContentEquals(aesSegments[i], file.readBytes())
        }
    }

    @Test
    fun decrypts_aes128_with_sequence_derived_iv() = runBlocking {
        val result = downloader().download(url("/aesseq/index.m3u8"), File(tempDir, "aesseq.hls"))

        result.segments.forEachIndexed { i, file ->
            assertContentEquals(aesSegments[i], file.readBytes())
        }
    }

    @Test
    fun resume_only_redownloads_segment_without_done_marker() = runBlocking {
        val outDir = File(tempDir, "resume.hls")
        val hls = downloader()
        hls.download(url("/plain/index.m3u8"), outDir)

        File(outDir, "seg_00001.done").delete()

        val result = hls.download(url("/plain/index.m3u8"), outDir)

        assertEquals(3, result.segments.size)
        result.segments.forEachIndexed { i, file -> assertContentEquals(plainSegments[i], file.readBytes()) }
        assertEquals(1, hitCount("/plain/seg0.ts"))
        assertEquals(2, hitCount("/plain/seg1.ts"), "只有缺少 .done 的分片应重下")
        assertEquals(1, hitCount("/plain/seg2.ts"))
    }

    // ---------------------------------------------------------------- 内容指纹（P0-2 续传）

    @Test
    fun resume_reuses_every_segment_when_only_the_signature_changed() = runBlocking {
        val outDir = File(tempDir, "swap-resign.hls")
        val hls = downloader()
        hls.download(url("/swap/index.m3u8"), outDir)

        // 网盘直链重新解析后的常态：地址还是那些地址，query 里的时效签名换了
        swapSign = "?sign=second-round&expire=9999999999"
        val result = hls.download(url("/swap/index.m3u8"), outDir)

        assertEquals(3, result.segments.size)
        assertEquals(1, hitCount("/swap/seg0.ts"), "签名变化不该触发重下")
        assertEquals(1, hitCount("/swap/seg1.ts"), "签名变化不该触发重下")
        assertEquals(1, hitCount("/swap/seg2.ts"), "签名变化不该触发重下")
    }

    @Test
    fun resume_redownloads_only_the_segment_whose_path_changed() = runBlocking {
        val outDir = File(tempDir, "swap-path.hls")
        val hls = downloader()
        hls.download(url("/swap/index.m3u8"), outDir)

        swapRenameMiddle = true
        val result = hls.download(url("/swap/index.m3u8"), outDir)

        assertEquals(3, result.segments.size)
        assertEquals(1, hitCount("/swap/seg0.ts"), "路径没变的片必须继续复用")
        assertEquals(1, hitCount("/swap/seg2.ts"), "路径没变的片必须继续复用")
        assertEquals(1, hitCount("/swap/seg1b.ts"), "路径变了的片必须重下")
        // 重下的是新地址，内容落在原来那个 seg_00001.ts 上
        assertContentEquals(plainSegments[1], File(outDir, "seg_00001.ts").readBytes())
    }

    @Test
    fun resume_redownloads_everything_when_the_durations_changed() = runBlocking {
        val outDir = File(tempDir, "swap-duration.hls")
        val hls = downloader()
        hls.download(url("/swap/index.m3u8"), outDir)

        // 同一批地址、时长全变了 = 另一份切片（换了视频或重新编码）
        swapDuration = 6.0
        hls.download(url("/swap/index.m3u8"), outDir)

        assertEquals(2, hitCount("/swap/seg0.ts"))
        assertEquals(2, hitCount("/swap/seg1.ts"))
        assertEquals(2, hitCount("/swap/seg2.ts"))
    }

    @Test
    fun resume_drops_segments_that_the_shorter_playlist_no_longer_lists() = runBlocking {
        val outDir = File(tempDir, "swap-shorter.hls")
        val hls = downloader()
        hls.download(url("/swap/index.m3u8"), outDir)
        assertTrue(File(outDir, "seg_00002.ts").exists())

        swapSegmentCount = 2
        val result = hls.download(url("/swap/index.m3u8"), outDir)

        assertEquals(2, result.segments.size)
        assertFalse(File(outDir, "seg_00002.ts").exists(), "播放列表外的旧分片必须清掉")
        assertFalse(File(outDir, "seg_00002.done").exists(), "旧标记也要跟着清掉")
    }

    @Test
    fun a_truncated_segment_file_is_downloaded_again() = runBlocking {
        val outDir = File(tempDir, "swap-truncated.hls")
        val hls = downloader()
        hls.download(url("/swap/index.m3u8"), outDir)

        // 磁盘上的分片被外部改短：标记还在、签名也对，只有长度对不上
        val target = File(outDir, "seg_00001.ts")
        target.writeBytes(target.readBytes().copyOf(16))

        hls.download(url("/swap/index.m3u8"), outDir)

        assertEquals(1, hitCount("/swap/seg0.ts"))
        assertEquals(2, hitCount("/swap/seg1.ts"), "长度不符的分片必须重下")
        assertContentEquals(plainSegments[1], target.readBytes())
    }

    @Test
    fun empty_done_markers_left_by_the_previous_version_are_discarded() = runBlocking {
        val outDir = File(tempDir, "swap-legacy.hls")
        val hls = downloader()
        hls.download(url("/swap/index.m3u8"), outDir)

        // 改动前写的是空标记：没有身份信息，无法验明这批分片属于哪份内容
        File(outDir, "seg_00000.done").writeBytes(ByteArray(0))

        hls.download(url("/swap/index.m3u8"), outDir)

        assertEquals(2, hitCount("/swap/seg0.ts"), "空标记不能当成「已完成」")
        assertEquals(1, hitCount("/swap/seg1.ts"))
    }

    @Test
    fun rejects_live_playlist() = runBlocking {
        val error = assertFailsWith<HlsException> {
            downloader().download(url("/live/index.m3u8"), File(tempDir, "live.hls"))
        }

        assertTrue(error.reason.contains("直播"), error.reason)
    }

    // ---------------------------------------------------------------- 其他关键逻辑

    @Test
    fun selects_highest_bandwidth_from_master() = runBlocking {
        val result = downloader().download(url("/master/index.m3u8"), File(tempDir, "master.hls"))

        assertEquals(1, result.segments.size)
        assertContentEquals(hiData, result.segments[0].readBytes())
        assertEquals(0, hitCount("/master/lo0.ts"), "不应下载低清晰度分片")
    }

    @Test
    fun writes_fmp4_init_segment_before_variants() = runBlocking {
        val result = downloader().download(url("/fmp4/index.m3u8"), File(tempDir, "fmp4.hls"))

        val init = assertNotNull(result.initSegment)
        assertContentEquals(fmp4Init, init.readBytes())
        result.segments.forEachIndexed { i, file -> assertContentEquals(fmp4Segments[i], file.readBytes()) }
    }

    @Test
    fun honors_byte_range() = runBlocking {
        val range = 16L until 16L + 20L
        val result = downloader().download(url("/range/index.m3u8"), File(tempDir, "range.hls"))

        assertContentEquals(byteRangeBlob.copyOfRange(range.first.toInt(), range.last.toInt() + 1), result.segments[0].readBytes())
    }

    @Test
    fun retries_transient_segment_failures() = runBlocking {
        val result = downloader(retryDelaysMs = listOf(10L, 10L, 10L))
            .download(url("/retry/index.m3u8"), File(tempDir, "retry.hls"))

        assertContentEquals(plainSegments[0], result.segments[0].readBytes())
        assertEquals(3, hitCount("/retry/seg0.ts"), "前两次 500 应被重试")
    }

    @Test
    fun fails_after_retries_are_exhausted() = runBlocking {
        val error = assertFailsWith<HlsException> {
            downloader(retryDelaysMs = listOf(10L))
                .download(url("/fail/index.m3u8"), File(tempDir, "fail.hls"))
        }

        assertTrue(error.reason.contains("分片 0"), error.reason)
        assertEquals(2, hitCount("/fail/seg0.ts"))
    }

    // ---------------------------------------------------------------- v3-P3 回归

    @Test
    fun does_not_retry_deterministic_4xx() = runBlocking {
        val error = assertFailsWith<HlsException> {
            downloader(retryDelaysMs = listOf(10L, 10L, 10L))
                .download(url("/forbidden/index.m3u8"), File(tempDir, "forbidden.hls"))
        }

        assertTrue(error.reason.contains("403"), error.reason)
        assertEquals(1, hitCount("/forbidden/seg0.ts"), "403 重试没有意义，只应请求一次")
    }

    @Test
    fun retries_429_and_honors_retry_after() = runBlocking {
        val result = downloader(retryDelaysMs = listOf(5_000L))
            .download(url("/limited/index.m3u8"), File(tempDir, "limited.hls"))

        assertContentEquals(plainSegments[0], result.segments[0].readBytes())
        assertEquals(2, hitCount("/limited/seg0.ts"), "429 后应重试一次")
    }

    @Test
    fun decrypts_aes128_without_pkcs7_padding() = runBlocking {
        val result = downloader().download(url("/aesnopad/index.m3u8"), File(tempDir, "aesnopad.hls"))

        result.segments.forEachIndexed { i, file ->
            assertContentEquals(unpaddedSegments[i], file.readBytes())
        }
    }

    @Test
    fun inherits_session_key_from_master_playlist() = runBlocking {
        val result = downloader().download(url("/session/index.m3u8"), File(tempDir, "session.hls"))

        result.segments.forEachIndexed { i, file ->
            assertContentEquals(aesSegments[i], file.readBytes())
        }
    }

    // ---------------------------------------------------------------- v3-P2 回归

    @Test
    fun rejects_oversized_playlist_without_retrying() = runBlocking {
        val error = assertFailsWith<HlsException> {
            downloader(retryDelaysMs = listOf(1L))
                .download(url("/huge/index.m3u8"), File(tempDir, "huge.hls"))
        }

        assertTrue(error.reason.contains("上限"), error.reason)
        assertEquals(1, hitCount("/huge/index.m3u8"), "确定性的大小错误不应重试")
    }

    @Test
    fun rejects_oversized_key_without_retrying() = runBlocking {
        val error = assertFailsWith<HlsException> {
            downloader(retryDelaysMs = listOf(1L))
                .download(url("/bigkey/index.m3u8"), File(tempDir, "bigkey.hls"))
        }

        assertTrue(error.reason.contains("上限"), error.reason)
        assertEquals(1, hitCount("/bigkey/key.bin"), "确定性的大小错误不应重试")
    }

    @Test
    fun leaves_no_part_files_after_download() = runBlocking {
        val outDir = File(tempDir, "part.hls")

        downloader().download(url("/plain/index.m3u8"), outDir)

        assertTrue(
            outDir.listFiles().orEmpty().none { it.name.endsWith(".part") },
            "流式下载完成后不应留下 .part 临时文件",
        )
    }

    // ---------------------------------------------------------------- v3-P1 回归

    @Test
    fun sends_referer_with_every_request() = runBlocking {
        val playlist = url("/plain/index.m3u8")

        downloader(referer = playlist).download(playlist, File(tempDir, "referer.hls"))

        assertEquals(playlist, referers["/plain/index.m3u8"], "播放列表请求也应带 Referer")
        assertEquals(playlist, referers["/plain/seg0.ts"])
    }

    @Test
    fun uses_final_playlist_url_after_redirect_for_referer_and_relative_segments() = runBlocking {
        val start = url("/redir/index.m3u8")

        val result = downloader(referer = start)
            .download(start, File(tempDir, "redir.hls"))

        assertEquals(1, result.segments.size)
        assertContentEquals(plainSegments[0], result.segments[0].readBytes())
        assertEquals(1, hitCount("/moved/seg0.ts"), "相对分片应基于重定向后的地址解析")
        assertEquals(start, referers["/redir/index.m3u8"])
        assertEquals(url("/moved/index.m3u8"), referers["/moved/seg0.ts"])
    }

    @Test
    fun keeps_page_referer_when_playlist_redirects() = runBlocking {
        val page = "https://page.example.com/watch/1"
        val start = url("/redir/index.m3u8")

        downloader(referer = page).download(start, File(tempDir, "page-referer.hls"))

        assertEquals(page, referers["/redir/index.m3u8"])
        // 跨域 CDN 校验的是播放页地址：播放列表重定向后分片仍要带页面 Referer，而不是 CDN 自己的地址
        assertEquals(page, referers["/moved/seg0.ts"])
    }

    @Test
    fun routes_hls_requests_through_configured_proxy() = runBlocking {
        val port = startProxy()
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
        // 域名不可解析：若未走代理，连接会直接失败
        val playlist = "http://hls.invalid/plain/index.m3u8"

        val result = HlsDownloader(proxy = proxy, retryDelaysMs = listOf(1L))
            .download(playlist, File(tempDir, "proxy.hls"))

        assertEquals(3, result.segments.size)
        assertContentEquals(plainSegments[0], result.segments[0].readBytes())
        assertEquals(0, originHits.get(), "配置代理后不应直连源站")
        assertEquals(4, proxyHits.get(), "1 个播放列表 + 3 个分片都应经过代理")
    }

    @Test
    fun downloads_separate_audio_rendition_into_subdirectory() = runBlocking {
        val progress = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())

        val result = downloader().download(
            url("/audio/index.m3u8"),
            File(tempDir, "audio.hls"),
            onProgress = { done, total -> progress.add(done to total) },
        )

        assertEquals(2, result.segments.size)
        assertEquals(2, result.audioSegments.size)
        result.audioSegments.forEachIndexed { index, file ->
            assertEquals("audio", file.parentFile.name)
            assertContentEquals(audioSegments[index], file.readBytes())
        }
        assertNull(result.initSegment)
        assertNull(result.audioInitSegment)
        assertEquals(4, progress.last().second, "进度总数应包含独立音轨分片")
        assertEquals(4, progress.last().first)
    }

    // ---------------------------------------------------------------- v6-P1-1 字节统计

    @Test
    fun byte_callback_reports_monotonic_totals_matching_disk_size() = runBlocking {
        val outDir = File(tempDir, "bytes.hls")
        val samples = Collections.synchronizedList(mutableListOf<Long>())

        val result = downloader().download(
            url("/plain/index.m3u8"),
            outDir,
            onBytes = { samples.add(it) },
        )

        // 下载速度就是靠这条曲线算出来的：必须单调不减，且最终值等于落盘总字节
        assertTrue(samples.isNotEmpty(), "至少要有一次字节回调")
        assertEquals(samples.sorted(), samples, "累计字节必须单调不减")
        assertEquals(result.segments.sumOf { it.length() }, samples.last())
    }

    @Test
    fun byte_callback_counts_resumed_segments() = runBlocking {
        val outDir = File(tempDir, "resume.hls")
        // 第一次下完，第二次重跑：所有分片都走「续传跳过」分支，字节仍应被计入
        downloader().download(url("/plain/index.m3u8"), outDir)
        val samples = Collections.synchronizedList(mutableListOf<Long>())
        val result = downloader().download(url("/plain/index.m3u8"), outDir, onBytes = { samples.add(it) })

        assertTrue(samples.isNotEmpty(), "续传跳过的分片也要计入已下载字节")
        // 并发回调的顺序也必须单调：下游速度采样器拿到的是一条只会往上走的曲线
        assertEquals(samples.sorted(), samples, "并发回调顺序与累计值顺序必须一致")
        assertEquals(result.segments.sumOf { it.length() }, samples.last())
    }

    // ---------------------------------------------------------------- 本地服务

    private fun downloader(
        retryDelaysMs: List<Long> = listOf(1L, 2L, 4L),
        referer: String? = null,
    ) = HlsDownloader(retryDelaysMs = retryDelaysMs, referer = referer)

    private fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"

    private fun hitCount(path: String): Int = hits[path]?.get() ?: 0

    private fun route(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
        exchange.requestHeaders.getFirst("Referer")?.let { referers[path] = it }
        try {
            when {
                path == "/plain/index.m3u8" -> respondText(exchange, mediaPlaylist(plainSegments.size))
                path == "/plain/seg0.ts" -> respond(exchange, plainSegments[0])
                path == "/plain/seg1.ts" -> respond(exchange, plainSegments[1])
                path == "/plain/seg2.ts" -> respond(exchange, plainSegments[2])

                path == "/swap/index.m3u8" -> respondText(exchange, swapPlaylist())
                path.startsWith("/swap/seg") -> respond(exchange, plainSegments[swapSegmentIndex(path)])

                path == "/master/index.m3u8" -> respondText(exchange, MASTER_PLAYLIST)
                path == "/master/lo.m3u8" -> respondText(exchange, mediaPlaylist(1, prefix = "lo"))
                path == "/master/lo0.ts" -> respond(exchange, loData)
                path == "/master/hi.m3u8" -> respondText(exchange, mediaPlaylist(1, prefix = "hi"))
                path == "/master/hi0.ts" -> respond(exchange, hiData)

                path == "/aes/index.m3u8" -> respondText(
                    exchange,
                    mediaPlaylist(aesSegments.size).replace(
                        "#EXT-X-TARGETDURATION:4",
                        "#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x$explicitIv",
                    ),
                )
                path == "/aes/key.bin" -> respond(exchange, aesKey)
                path.startsWith("/aes/seg") -> respond(
                    exchange,
                    encrypt(aesSegments[segmentIndex(path)], HlsAesDecryptor.ivFromHex(explicitIv)),
                )

                path == "/aesseq/index.m3u8" -> respondText(
                    exchange,
                    mediaPlaylist(aesSegments.size).replace(
                        "#EXT-X-TARGETDURATION:4",
                        "#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"",
                    ),
                )
                path == "/aesseq/key.bin" -> respond(exchange, aesKey)
                path.startsWith("/aesseq/seg") -> {
                    val index = segmentIndex(path)
                    respond(exchange, encrypt(aesSegments[index], HlsAesDecryptor.ivFromSequence(index)))
                }

                path == "/fmp4/index.m3u8" -> respondText(
                    exchange,
                    """
                        #EXTM3U
                        #EXT-X-VERSION:7
                        #EXT-X-TARGETDURATION:4
                        #EXT-X-MAP:URI="init.mp4"
                        #EXTINF:4.0,
                        seg0.m4s
                        #EXTINF:4.0,
                        seg1.m4s
                        #EXT-X-ENDLIST
                    """.trimIndent(),
                )
                path == "/fmp4/init.mp4" -> respond(exchange, fmp4Init)
                path == "/fmp4/seg0.m4s" -> respond(exchange, fmp4Segments[0])
                path == "/fmp4/seg1.m4s" -> respond(exchange, fmp4Segments[1])

                path == "/range/index.m3u8" -> respondText(
                    exchange,
                    """
                        #EXTM3U
                        #EXT-X-VERSION:4
                        #EXT-X-TARGETDURATION:4
                        #EXTINF:4.0,
                        #EXT-X-BYTERANGE:20@16
                        blob.bin
                        #EXT-X-ENDLIST
                    """.trimIndent(),
                )
                path == "/range/blob.bin" -> {
                    val header = exchange.requestHeaders.getFirst("Range")
                    if (header == null) {
                        respondError(exchange, 400)
                    } else {
                        val start = header.removePrefix("bytes=").substringBefore('-').toInt()
                        val end = header.substringAfter('-').toInt()
                        respond(exchange, byteRangeBlob.copyOfRange(start, end + 1), status = 206)
                    }
                }

                path == "/huge/index.m3u8" -> runCatching {
                    // 超过 8 MB 的播放列表：客户端应在读取前就拒绝
                    val size = 9L * 1024 * 1024
                    exchange.sendResponseHeaders(200, size)
                    exchange.responseBody.use { body ->
                        val chunk = ByteArray(64 * 1024) { '#'.code.toByte() }
                        var written = 0L
                        while (written < size) {
                            val n = minOf(chunk.size.toLong(), size - written).toInt()
                            body.write(chunk, 0, n)
                            written += n
                        }
                    }
                }

                path == "/bigkey/index.m3u8" -> respondText(
                    exchange,
                    mediaPlaylist(1).replace(
                        "#EXT-X-TARGETDURATION:4",
                        "#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"",
                    ),
                )
                path == "/bigkey/key.bin" -> respond(exchange, ByteArray(2048))

                path == "/live/index.m3u8" -> respondText(exchange, mediaPlaylist(2).replace("#EXT-X-ENDLIST\n", ""))
                path == "/redir/index.m3u8" -> respondRedirect(exchange, url("/moved/index.m3u8"))
                path == "/moved/index.m3u8" -> respondText(exchange, mediaPlaylist(1))
                path == "/moved/seg0.ts" -> respond(exchange, plainSegments[0])

                path == "/audio/index.m3u8" -> respondText(exchange, AUDIO_MASTER_PLAYLIST)
                path == "/audio/video.m3u8" -> respondText(exchange, mediaPlaylist(2, prefix = "v"))
                path == "/audio/v0.ts" -> respond(exchange, plainSegments[0])
                path == "/audio/v1.ts" -> respond(exchange, plainSegments[1])
                path == "/audio/audio-en.m3u8" -> respondText(exchange, mediaPlaylist(2, prefix = "a"))
                path == "/audio/a0.ts" -> respond(exchange, audioSegments[0])
                path == "/audio/a1.ts" -> respond(exchange, audioSegments[1])

                path == "/retry/index.m3u8" -> respondText(exchange, mediaPlaylist(1))
                path == "/retry/seg0.ts" -> {
                    if (flakyAttempts.incrementAndGet() <= 2) respondError(exchange, 500)
                    else respond(exchange, plainSegments[0])
                }

                path == "/fail/index.m3u8" -> respondText(exchange, mediaPlaylist(1))
                path == "/fail/seg0.ts" -> respondError(exchange, 500)

                path == "/forbidden/index.m3u8" -> respondText(exchange, mediaPlaylist(1))
                path == "/forbidden/seg0.ts" -> respondError(exchange, 403)

                path == "/limited/index.m3u8" -> respondText(exchange, mediaPlaylist(1))
                path == "/limited/seg0.ts" -> {
                    if (limitedAttempts.incrementAndGet() == 1) {
                        exchange.responseHeaders.add("Retry-After", "0")
                        respondError(exchange, 429)
                    } else {
                        respond(exchange, plainSegments[0])
                    }
                }

                path == "/aesnopad/index.m3u8" -> respondText(
                    exchange,
                    mediaPlaylist(unpaddedSegments.size).replace(
                        "#EXT-X-TARGETDURATION:4",
                        "#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x$explicitIv",
                    ),
                )
                path == "/aesnopad/key.bin" -> respond(exchange, aesKey)
                path.startsWith("/aesnopad/seg") -> respond(
                    exchange,
                    encryptNoPadding(unpaddedSegments[segmentIndex(path)], HlsAesDecryptor.ivFromHex(explicitIv)),
                )

                path == "/session/index.m3u8" -> respondText(
                    exchange,
                    """
                        #EXTM3U
                        #EXT-X-SESSION-KEY:METHOD=AES-128,URI="key.bin",IV=0x$explicitIv
                        #EXT-X-STREAM-INF:BANDWIDTH=1000000
                        media.m3u8
                    """.trimIndent(),
                )
                path == "/session/media.m3u8" -> respondText(exchange, mediaPlaylist(aesSegments.size))
                path == "/session/key.bin" -> respond(exchange, aesKey)
                path.startsWith("/session/seg") -> respond(
                    exchange,
                    encrypt(aesSegments[segmentIndex(path)], HlsAesDecryptor.ivFromHex(explicitIv)),
                )

                else -> respondError(exchange, 404)
            }
        } finally {
            exchange.close()
        }
    }

    private fun mediaPlaylist(count: Int, prefix: String = "seg"): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:4")
        appendLine("#EXT-X-MEDIA-SEQUENCE:0")
        repeat(count) { i ->
            appendLine("#EXTINF:4.0,")
            appendLine("$prefix$i.ts")
        }
        appendLine("#EXT-X-ENDLIST")
    }

    /**
     * `/swap/` 的播放列表：内容由三个可变开关决定，用来模拟「同一个地址背后换了视频」。
     *
     * 分片路径与 `plainSegments` 一一对应，内容不变，只有上层特征在变 ——
     * 这样一旦发现重下/不重下的判断错了，断言直接指向是哪一条特征没被纳入指纹。
     */
    private fun swapPlaylist(): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:8")
        appendLine("#EXT-X-MEDIA-SEQUENCE:0")
        repeat(swapSegmentCount) { i ->
            appendLine("#EXTINF:$swapDuration,")
            val name = if (swapRenameMiddle && i == 1) "seg1b.ts" else "seg$i.ts"
            appendLine("$name$swapSign")
        }
        appendLine("#EXT-X-ENDLIST")
    }

    /** `seg1b.ts` 与 `seg1.ts` 落到同一份内容，方便断言「重下的是新地址、落在老文件上」 */
    private fun swapSegmentIndex(path: String): Int =
        path.substringAfterLast('/').removePrefix("seg").substringBefore('.').removeSuffix("b").toInt()

    private fun segmentIndex(path: String): Int =
        path.substringAfterLast("seg").substringBefore('.').toInt()

    private fun encrypt(plain: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plain)
    }

    /** 无 PKCS#7 填充的密文，模拟不按规范补齐的编码器 */
    private fun encryptNoPadding(plain: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plain)
    }

    private fun respondText(exchange: HttpExchange, text: String) =
        respond(exchange, text.toByteArray())

    private fun respond(exchange: HttpExchange, bytes: ByteArray, status: Int = 200) {
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respondError(exchange: HttpExchange, status: Int) {
        exchange.sendResponseHeaders(status, -1)
    }

    private fun respondRedirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
    }

    /**
     * 启动一个仅用于测试的 HTTP 代理：直接按路径返回假内容（不转发），
     * 只用来证明 [HlsDownloader] 的请求确实交给了代理。
     *
     * @return 代理端口
     */
    private fun startProxy(): Int {
        val proxy = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newFixedThreadPool(4)
        proxy.executor = pool
        proxy.createContext("/") { exchange ->
            proxyHits.incrementAndGet()
            route(exchange)
        }
        proxy.start()
        proxyServer = proxy
        proxyExecutor = pool
        return proxy.address.port
    }

    private companion object {
        val MASTER_PLAYLIST = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            lo.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            hi.m3u8
        """.trimIndent()

        val AUDIO_MASTER_PLAYLIST = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="audio-en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO="aud"
            video.m3u8
        """.trimIndent()
    }
}

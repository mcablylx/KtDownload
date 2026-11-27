package top.mcly99.dl.engine.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import com.sun.net.httpserver.HttpServer
import top.mcly99.dl.utils.FfmpegLocator
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 真起播的冒烟测试（**默认不跑**）。
 *
 * 播放依赖 native libvlc 与图形栈，无头 CI 上不可靠，所以：
 * - 只有设置环境变量 `KTDL_VLC_SMOKE=1` 时才执行，其余情况用 Assume 标记为 SKIPPED（P2-29）；
 * - 需要 `resources/<平台>/` 下已有 libvlc（先跑过 `./gradlew :composeApp:vlcSetup`）；
 * - 用随包 ffmpeg 现造测试视频，不依赖任何外部网络。
 *
 * 手工验证命令：
 * ```
 * KTDL_VLC_SMOKE=1 ./gradlew :composeApp:jvmTest --tests "*VlcPlaybackSmokeTest"
 * ```
 *
 * 它验证的是 P2 的核心假设：
 * 1. 打包进来的 libvlc 能被发现、能解出帧（T2-1 / T2-3）；
 * 2. 本地 mp4 能解析出总时长，即「点播」判定有依据（T2-5）；
 * 3. **HLS 的分片请求是否继承媒体级 `Referer`**（T2-7 的待实测项）。
 */
class VlcPlaybackSmokeTest {

    @Test
    fun plays_a_local_mp4_and_delivers_frames() {
        smokeAssumption()

        val sample = createSampleVideo()
            .also { assumeTrue("没有可用的 ffmpeg，无法生成测试视频", it != null) }!!

        val harness = Harness()
        try {
            harness.player.play(PlaybackRequest(sample.toString(), title = "smoke"))
            assertTrue(harness.playingLatch.await(20, TimeUnit.SECONDS), "20 秒内没有进入播放状态")
            assertTrue(harness.frameLatch.await(20, TimeUnit.SECONDS), "20 秒内没有收到画面帧")
            val frame = harness.firstFrame.get()
            assertNotNull(frame)
            assertTrue(frame.width > 0 && frame.height > 0, "帧尺寸不合法: ${frame.width}x${frame.height}")
            // 只有尺寸、像素全空也会「收到帧」：必须确认缓冲真的被填进去了，否则是黑屏
            val center = frame.toPixelMap()[frame.width / 2, frame.height / 2]
            assertTrue(center.alpha > 0f, "首帧中心像素 alpha=${center.alpha}，画面是空的")
            // 本地 mp4 是点播：总时长必须解析出来，否则「直播/点播分流」的判定就没有依据
            assertTrue(harness.length.get() > 0, "本地 mp4 应当解析出总时长，实际 ${harness.length.get()}")
            println("收到首帧 ${frame.width}x${frame.height}，时长 ${harness.length.get()} ms")
        } finally {
            harness.close()
            Files.deleteIfExists(sample)
        }
    }

    /**
     * HLS：本地 HttpServer 提供播放列表与分片，**分片请求不带 Referer 就返回 403**。
     *
     * 这一条是 T2-7 的实测：VLC 的 HLS 模块会为每个分片另建 access 实例，
     * 媒体级 `:http-referrer` 到底会不会被继承，只能真跑一次才知道。
     */
    @Test
    fun hls_segments_inherit_media_level_referer() {
        smokeAssumption()

        val dir = createSampleHls()
            .also { assumeTrue("没有可用的 ffmpeg，无法生成 HLS", it != null) }!!

        val referer = "https://page.example.com/watch/42"
        val seenReferers = Collections.synchronizedList(mutableListOf<String?>())
        val forbidden = java.util.concurrent.atomic.AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val header = exchange.requestHeaders.getFirst("Referer")
            if (path.endsWith(".ts")) {
                seenReferers.add(header)
                if (header != referer) {
                    // 防盗链 CDN 的典型行为
                    forbidden.incrementAndGet()
                    exchange.sendResponseHeaders(403, -1)
                    exchange.close()
                    return@createContext
                }
            }
            val file = dir.resolve(path.trimStart('/'))
            if (!Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }
            val bytes = Files.readAllBytes(file)
            val contentType = if (path.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t"
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        val harness = Harness()
        try {
            val url = "http://127.0.0.1:${server.address.port}/index.m3u8"
            harness.player.play(PlaybackRequest(url, referer = referer, title = "hls"))
            val playing = harness.playingLatch.await(30, TimeUnit.SECONDS)
            val gotFrame = harness.frameLatch.await(10, TimeUnit.SECONDS)
            println("HLS 起播=$playing 收到帧=$gotFrame 分片请求=${seenReferers.size} 403次数=${forbidden.get()}")
            println("分片 Referer 实测: ${seenReferers.take(3)}")
            assertTrue(playing, "HLS 没有进入播放状态")
            assertTrue(seenReferers.isNotEmpty(), "libvlc 一个分片都没请求，HLS 没起来")
            assertTrue(forbidden.get() == 0, "有 ${forbidden.get()} 个分片因为没带 Referer 被 403")
        } finally {
            harness.close()
            server.stop(0)
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    /**
     * v7-P1-1 的真机验证：媒体级 `:http-cookie=` 到底会不会被 libvlc 发出去。
     *
     * 背景：libvlc 3 的 http access 文档里只列了 `--http-referrer` / `--http-user-agent` /
     * `--http-forward-cookies`，**没有**「注入自定义 Cookie」的参数。`:http-cookie=` 是按语义猜的，
     * 不认时只打一条 unknown option 警告并静默忽略 —— 症状和「Cookie 没传」完全一样，
     * 光看黑屏永远排不出来。所以让本地服务器把收到的 Cookie 原样记下来。
     *
     * **2026-10-28 实测结论：libvlc 3.0.21 不支持。** 同一次运行里 `:http-referrer` 在分片请求上
     * 生效、`:http-cookie` 一条都没带 —— 说明「媒体级选项 → 子请求」这条链路是通的，
     * 只是这个选项名不存在。播放侧因此只能保证「不带坏」,真正需要登录态的地址要靠失败文案兜底
     * （见 `VideoPlayer` 的 `SessionRequired`）。
     *
     * 这条用例**故意不 assert「必须带上」**：它是一支探针，不是回归。换 libvlc 4 之后重跑一次，
     * 看打印的结论从「不支持」变成「支持」即可。
     *
     * 服务器**不返回 403**：一旦拦下来，就看不出「是没带 Cookie」还是「带了但被别的条件挡了」。
     */
    @Test
    fun hls_segments_carry_injected_cookie() {
        smokeAssumption()

        val dir = createSampleHls()
            .also { assumeTrue("没有可用的 ffmpeg，无法生成 HLS", it != null) }!!

        val cookie = "SESSDATA=abc123%2C456; bili_jct=deadbeef"
        // 路径 → 该次请求带的 Cookie，保留「播放列表 / 分片」的区分
        val seen = Collections.synchronizedMap(mutableMapOf<String, String?>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            seen[path] = exchange.requestHeaders.getFirst("Cookie")
            val file = dir.resolve(path.trimStart('/'))
            if (!Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }
            val bytes = Files.readAllBytes(file)
            val contentType = if (path.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t"
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        val harness = Harness()
        try {
            val url = "http://127.0.0.1:${server.address.port}/index.m3u8"
            harness.player.play(PlaybackRequest(url, cookie = cookie, title = "hls-cookie"))
            val playing = harness.playingLatch.await(30, TimeUnit.SECONDS)
            harness.frameLatch.await(10, TimeUnit.SECONDS)

            val playlistCookie = seen.entries.firstOrNull { it.key.endsWith(".m3u8") }?.value
            val segmentCookies = seen.filterKeys { it.endsWith(".ts") }.values.toList()
            val carried = segmentCookies.count { it == cookie }
            println("HLS 起播=$playing 请求路径数=${seen.size}")
            println("播放列表 Cookie = $playlistCookie")
            println("分片 Cookie（前 3 条）= ${segmentCookies.take(3)}")
            println(
                "结论：libvlc " + (if (carried > 0) "支持" else "不支持") +
                    "媒体级 :http-cookie=（${carried}/${segmentCookies.size} 个分片带上）",
            )

            assertTrue(playing, "HLS 没有进入播放状态，后面的断言没有意义")
            assertTrue(segmentCookies.isNotEmpty(), "libvlc 一个分片都没请求，HLS 没起来")
            // 不要求「一定带上」，但**不能一半带一半不带** —— 那说明选项时灵时不灵，比不支持更难查
            assertTrue(
                carried == 0 || carried == segmentCookies.size,
                "Cookie 只在部分分片上生效（$carried/${segmentCookies.size}），行为不稳定",
            )
        } finally {
            harness.close()
            server.stop(0)
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    // ------------------------------------------------------------------ 辅助

    private fun smokeAssumption() {
        assumeTrue(
            "未设置 KTDL_VLC_SMOKE=1，跳过真实起播",
            System.getenv("KTDL_VLC_SMOKE") == "1",
        )
    }

    /** 播放器 + 三个断言点 */
    private class Harness {
        val firstFrame = AtomicReference<ImageBitmap?>(null)
        val frameLatch = CountDownLatch(1)
        val playingLatch = CountDownLatch(1)
        val length = AtomicReference(0L)

        private val factory = VlcRuntime.factory()
            ?: error("libvlc 初始化失败: ${VlcRuntime.lastError()}")

        val player = VideoPlayer(
            factory = factory,
            onState = { state ->
                if (state.phase == PlaybackPhase.Playing) {
                    length.set(state.lengthMs)
                    playingLatch.countDown()
                }
            },
            onFrame = { frame ->
                if (frame != null && firstFrame.compareAndSet(null, frame)) frameLatch.countDown()
            },
        )

        fun close() {
            player.stop()
            player.release()
            VlcRuntime.release()
        }
    }

    /** 用随包 ffmpeg 生成一段 2 秒的测试视频；没有 ffmpeg 时返回 null */
    private fun createSampleVideo(): Path? {
        val ffmpeg = FfmpegLocator.locate() ?: return null
        val target = Files.createTempFile("ktdown-vlc-smoke", ".mp4")
        val ok = runFfmpeg(
            ffmpeg.toString(),
            "-y", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc=duration=2:size=320x240:rate=15",
            "-pix_fmt", "yuv420p",
            target.toString(),
        )
        if (!ok) {
            Files.deleteIfExists(target)
            return null
        }
        return target
    }

    /** 生成 2 秒的 HLS（1 秒一片）；没有 ffmpeg 时返回 null */
    private fun createSampleHls(): Path? {
        val ffmpeg = FfmpegLocator.locate() ?: return null
        val dir = Files.createTempDirectory("ktdown-vlc-hls")
        val ok = runFfmpeg(
            ffmpeg.toString(),
            "-y", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc=duration=2:size=320x240:rate=15",
            // 随包 ffmpeg 是 LGPL 构建，没有 libx264；MPEG-TS 里用 mpeg2video 一样能验证 HLS 链路
            "-c:v", "mpeg2video", "-b:v", "800k", "-g", "15",
            "-f", "hls", "-hls_time", "1", "-hls_list_size", "0",
            "-hls_segment_filename", dir.resolve("seg%d.ts").toString(),
            dir.resolve("index.m3u8").toString(),
        )
        return if (ok) dir else null
    }

    private fun runFfmpeg(vararg command: String): Boolean = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    }.getOrDefault(false)
}

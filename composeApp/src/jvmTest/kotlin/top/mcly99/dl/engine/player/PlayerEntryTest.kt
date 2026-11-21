package top.mcly99.dl.engine.player

import top.mcly99.dl.engine.web.MediaCandidate
import top.mcly99.dl.engine.web.MediaKind
import top.mcly99.dl.ui.components.formatClock
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 播放相关的**纯逻辑**单测。
 *
 * 真正的起播依赖 native 库与图形环境，无头 CI 跑不了（见 docs/CATCH_WEB_PLAN.md §8 手工验证清单）；
 * 这里只钉住「谁能播」「参数怎么拼」「直播怎么判」这些一旦写错就只能靠肉眼发现的部分。
 */
class PlayerEntryTest {

    // ---------------------------------------------------------------- 谁能播

    @Test
    fun m3u8_and_mp4_are_playable() {
        assertTrue(MediaKind.M3u8.canPlay)
        assertTrue(MediaKind.Mp4.canPlay)
    }

    @Test
    fun other_kinds_have_no_play_button() {
        assertFalse(MediaKind.Other.canPlay)
    }

    @Test
    fun m4v_and_mov_are_treated_as_mp4() {
        // ISO-BMFF 家族走同一条解码路径，给 mp4 就等于给它们播放按钮
        assertEquals(MediaKind.Mp4, MediaKind.fromUrl("https://cdn/a/b.m4v"))
        assertEquals(MediaKind.Mp4, MediaKind.fromUrl("https://cdn/a/b.mov"))
        assertTrue(MediaKind.fromUrl("https://cdn/a/b.mov")!!.canPlay)
    }

    // ---------------------------------------------------------------- 参数拼装

    @Test
    fun media_options_carry_referer_when_present() {
        val options = VlcOptions.mediaOptions(
            PlaybackRequest(url = "https://cdn/a.m3u8", referer = "https://page.example.com/watch/1"),
            cachingMs = 3_000,
        )
        assertTrue(options.contains(":http-referrer=https://page.example.com/watch/1"))
        assertTrue(options.contains(":network-caching=3000"))
        // 每个媒体级参数都必须带冒号前缀，否则会被当成全局参数
        assertTrue(options.all { it.startsWith(":") })
    }

    @Test
    fun media_options_skip_blank_referer() {
        val withoutReferer = VlcOptions.mediaOptions(PlaybackRequest("https://cdn/a.mp4"), 1_000)
        assertTrue(withoutReferer.none { it.startsWith(":http-referrer") })

        val blankReferer = VlcOptions.mediaOptions(
            PlaybackRequest("https://cdn/a.mp4", referer = "   "),
            1_000,
        )
        assertTrue(blankReferer.none { it.startsWith(":http-referrer") })
    }

    @Test
    fun media_options_always_carry_user_agent() {
        // 不少 CDN 对非浏览器 UA 直接 403，这条不能漏
        val options = VlcOptions.mediaOptions(PlaybackRequest("https://cdn/a.mp4"))
        assertTrue(options.any { it.startsWith(":http-user-agent=") })
    }

    @Test
    fun media_options_carry_cookie_when_present() {
        // v7-P1-1：能下不能播的根因就是这里漏了 Cookie。
        // 参数名按媒体级 `:http-cookie=` 传；VLC 3 不认时会打一条 unknown option 并忽略，
        // 所以这条断言钉的是「我们确实传了」，不是「VLC 一定吃」（后者需真机验证）
        val options = VlcOptions.mediaOptions(
            PlaybackRequest(url = "https://cdn/a.m3u8", cookie = "sid=abc; token=x=y"),
            cachingMs = 3_000,
        )
        assertTrue(options.contains(":http-cookie=sid=abc; token=x=y"))
        assertTrue(options.all { it.startsWith(":") })
    }

    @Test
    fun media_options_skip_blank_cookie() {
        val blank = VlcOptions.mediaOptions(PlaybackRequest("https://cdn/a.mp4", cookie = "  "), 1_000)
        assertTrue(blank.none { it.startsWith(":http-cookie") })
    }

    @Test
    fun factory_args_are_global_parameters() {
        val args = VlcOptions.factoryArgs()
        assertTrue(args.contains("--no-video-title-show"))
        // UA 放在工厂级：HLS 的分片请求由 libvlc 内部另建 access，只有全局参数一定继承
        assertTrue(args.any { it.startsWith("--http-user-agent=") })
        assertTrue(args.none { it.startsWith(":") })
    }

    @Test
    fun hls_gets_a_bigger_network_cache_than_mp4() {
        assertTrue(VlcOptions.cachingFor(MediaKind.M3u8) > VlcOptions.cachingFor(MediaKind.Mp4))
    }

    // ---------------------------------------------------------------- 切清晰度

    @Test
    fun media_options_resume_from_position_when_switching_quality() {
        // 用户拖到 40 分钟发现画质不行，换档后从头开始是最让人恼火的交互
        val options = VlcOptions.mediaOptions(
            PlaybackRequest("https://cdn/a.m3u8", startMs = 12_345L),
            cachingMs = 3_000,
        )
        assertTrue(options.contains(":start-time=12.345"))
    }

    @Test
    fun media_options_omit_start_time_for_fresh_playback() {
        val options = VlcOptions.mediaOptions(PlaybackRequest("https://cdn/a.m3u8"), 3_000)
        assertTrue(options.none { it.startsWith(":start-time") })
    }

    @Test
    fun start_time_always_uses_dot_as_decimal_separator() {
        // 默认 Locale 可能输出 "12,345"，libvlc 会把它当非法值并静默从头播
        assertEquals("12.345", VlcOptions.formatStartSeconds(12_345))
        assertEquals("600.000", VlcOptions.formatStartSeconds(600_000))
    }

    @Test
    fun request_is_built_from_candidate() {
        val candidate = MediaCandidate(
            url = "https://cdn/a.m3u8",
            kind = MediaKind.M3u8,
            name = "第 3 集",
            source = MediaCandidate.Source.Attribute,
            referer = "https://page.example.com/p/3",
            cookie = "sid=abc",
        )
        val request = PlaybackRequest.of(candidate)
        assertEquals(candidate.url, request.url)
        assertEquals(candidate.referer, request.referer)
        // 候选上的 Cookie 必须一路传到播放请求，否则签名地址「浏览器能播、这里 403」
        assertEquals(candidate.cookie, request.cookie)
        assertEquals("第 3 集", request.title)
    }

    // ---------------------------------------------------------------- 失败归类

    @Test
    fun error_on_a_session_request_is_reported_as_session_required() {
        // libvlc 3 注入不了 Cookie（2026-10-28 实测，见 VlcPlaybackSmokeTest）。
        // 这类地址重试一百次也是 403，文案必须指向「下载后看」
        assertEquals(PlaybackError.SessionRequired, playbackErrorFor("SESSDATA=abc"))
    }

    @Test
    fun error_without_cookie_stays_a_generic_media_error() {
        // 没有会话时不该甩「需要登录态」：断网、格式不支持都会走到这里
        assertEquals(PlaybackError.MediaError, playbackErrorFor(null))
    }

    // ---------------------------------------------------------------- 直播判定
    @Test
    fun live_when_length_is_zero_while_playing() {
        val live = PlaybackState(phase = PlaybackPhase.Playing, lengthMs = 0L)
        assertTrue(live.isLive)
        // 直播不给进度条：没有总时长，拖动没有意义
        assertFalse(live.canSeek)
        assertEquals(0f, live.progress)
    }

    @Test
    fun not_live_before_first_frame() {
        // 起播瞬间 length() 也是 0，这时判成直播会让点播也闪一下「直播」标签
        assertFalse(PlaybackState(phase = PlaybackPhase.Opening, lengthMs = 0L).isLive)
        assertFalse(PlaybackState(phase = PlaybackPhase.Idle).isLive)
    }

    @Test
    fun vod_is_seekable_and_reports_progress() {
        val vod = PlaybackState(phase = PlaybackPhase.Playing, timeMs = 30_000, lengthMs = 120_000)
        assertFalse(vod.isLive)
        assertTrue(vod.canSeek)
        assertEquals(0.25f, vod.progress)
    }

    @Test
    fun progress_is_clamped() {
        // 播放器偶尔会报出略大于总时长的位置，不能让滑块跑出轨道
        val state = PlaybackState(phase = PlaybackPhase.Playing, timeMs = 130_000, lengthMs = 120_000)
        assertEquals(1f, state.progress)
    }

    @Test
    fun paused_vod_still_shows_progress_but_ended_does_not_allow_seek() {
        assertTrue(PlaybackState(phase = PlaybackPhase.Paused, lengthMs = 10_000).canSeek)
        assertFalse(PlaybackState(phase = PlaybackPhase.Ended, lengthMs = 10_000).canSeek)
    }

    // ---------------------------------------------------------------- 时间显示

    @Test
    fun clock_formats_minutes_and_hours() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:12", formatClock(12_400))
        assertEquals("3:45", formatClock(225_000))
        assertEquals("1:02:03", formatClock(3_723_000))
    }

    @Test
    fun clock_never_shows_negative() {
        // seek 到 0 之前 libvlc 可能返回 -1
        assertEquals("0:00", formatClock(-1))
    }

    // ---------------------------------------------------------------- 内核定位

    @Test
    fun locator_reports_platform_core_library() {
        val os = System.getProperty("os.name", "").lowercase()
        val expected = when {
            os.contains("win") -> "libvlc.dll"
            os.contains("mac") -> "libvlc.dylib"
            else -> "libvlc.so"
        }
        assertEquals(expected, VlcLocator.coreLibraryName)
    }

    @Test
    fun locator_honors_override_directory_with_core_library() {
        // 「目录里有核心库」是唯一判据：只看目录存在，打包漏文件时会一路走到 native 崩溃
        val temp = kotlin.io.path.createTempDirectory("vlc-locator")
        val previous = System.getProperty("ktdownload.vlc")
        try {
            Files.createFile(temp.resolve(VlcLocator.coreLibraryName))
            System.setProperty("ktdownload.vlc", temp.toString())
            VlcLocator.reset()
            assertEquals(
                temp.toAbsolutePath().normalize(),
                VlcLocator.locate(),
            )
        } finally {
            if (previous == null) System.clearProperty("ktdownload.vlc")
            else System.setProperty("ktdownload.vlc", previous)
            VlcLocator.reset()
            runCatching { temp.toFile().deleteRecursively() }
        }
    }

    @Test
    fun locator_retries_after_missing_core_library() {
        val temp = kotlin.io.path.createTempDirectory("vlc-locator-retry")
        try {
            // 第一次找不到：失败不能缓存，否则用户装好 VLC 后只能重启应用
            VlcLocator.finder = { null }
            VlcLocator.reset()
            assertNull(VlcLocator.locate())

            Files.createFile(temp.resolve(VlcLocator.coreLibraryName))
            VlcLocator.finder = { temp.toAbsolutePath().normalize() }
            assertEquals(temp.toAbsolutePath().normalize(), VlcLocator.locate())
        } finally {
            VlcLocator.finder = null
            VlcLocator.reset()
            runCatching { temp.toFile().deleteRecursively() }
        }
    }
}

package top.mcly99.dl.engine.hls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [M3u8Parser] 单元测试。
 *
 * 全部使用内联的播放列表文本，不依赖网络，可在 CI 中稳定运行。
 */
class M3u8ParserTest {

    private val base = "https://example.com/hls/movie/index.m3u8"

    // ---------------------------------------------------------------- 主列表

    @Test
    fun parses_master_playlist_with_three_variants() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.42c01e,mp4a.40.2"
            360p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"
            720p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            1080p/index.m3u8
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Master
        assertEquals(3, playlist.variants.size)
        assertEquals(800_000L, playlist.variants[0].bandwidth)
        assertEquals("640x360", playlist.variants[0].resolution)
        assertEquals("1280x720", playlist.variants[1].resolution)
        assertNull(playlist.variants[2].codecs)
    }

    @Test
    fun master_resolves_relative_variant_urls() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000
            720p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000
            /abs/1080p/index.m3u8
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Master
        assertEquals("https://example.com/hls/movie/720p/index.m3u8", playlist.variants[0].uri)
        assertEquals("https://example.com/abs/1080p/index.m3u8", playlist.variants[1].uri)
    }

    @Test
    fun selectHighestBandwidth_picks_max() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            a.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000
            b.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000
            c.m3u8
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Master
        val best = playlist.selectHighestBandwidth()
        assertEquals(5_000_000L, best.bandwidth)
        assertEquals("https://example.com/hls/movie/b.m3u8", best.uri)
    }

    @Test
    fun selectHighestBandwidth_prefers_resolution_on_tie() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000
            nores.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1920x1080
            withres.m3u8
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Master
        assertEquals("withres.m3u8", playlist.selectHighestBandwidth().uri.substringAfterLast('/'))
    }

    // ---------------------------------------------------------------- 独立音轨

    @Test
    fun parses_audio_renditions_and_selects_default() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="中文",LANGUAGE="zh",DEFAULT=NO,AUTOSELECT=YES,URI="audio/zh.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO="aud"
            video/index.m3u8
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Master
        assertEquals(2, playlist.renditions.size)
        assertEquals("https://example.com/hls/movie/audio/en.m3u8", playlist.renditions[1].uri)
        assertEquals("aud", playlist.variants[0].audioGroup)

        val audio = assertNotNull(playlist.selectAudio(playlist.variants[0]))
        assertEquals("en", audio.language)
        assertEquals("English", audio.name)
        assertTrue(audio.isDefault)
    }

    @Test
    fun variant_without_audio_group_has_no_separate_track() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",DEFAULT=YES,URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000
            video/index.m3u8
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Master
        assertNull(playlist.variants[0].audioGroup)
        assertNull(playlist.selectAudio(playlist.variants[0]))
    }

    @Test
    fun audio_group_without_matching_rendition_selects_nothing() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="other",DEFAULT=YES,URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="aud"
            video/index.m3u8
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Master
        assertNull(playlist.selectAudio(playlist.variants[0]))
    }

    // ---------------------------------------------------------------- 媒体列表

    @Test
    fun parses_media_playlist_segments() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:8
            #EXTINF:8.0,
            0.ts
            #EXTINF:8.0,
            1.ts
            #EXTINF:1.066667,
            2.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Media
        assertEquals(3, playlist.segments.size)
        assertEquals(8, playlist.targetDuration)
        assertEquals(8.0, playlist.segments[0].duration)
        assertEquals(1.066667, playlist.segments[2].duration)
        assertEquals(3, playlist.segments.map { it.sequence }.distinct().size)
        assertTrue(playlist.totalDuration > 17.0)
        assertTrue(!playlist.isLive)
    }

    @Test
    fun segments_follow_media_sequence_offset() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:100
            #EXTINF:4.0,
            a.ts
            #EXTINF:4.0,
            b.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Media
        assertEquals(100, playlist.segments[0].sequence)
        assertEquals(101, playlist.segments[1].sequence)
    }

    @Test
    fun resolves_segment_urls_relative_absolute_and_scheme_relative() {
        val text = """
            #EXTM3U
            #EXTINF:4.0,
            seg1.ts
            #EXTINF:4.0,
            /root/seg2.ts
            #EXTINF:4.0,
            //cdn.example.net/seg3.ts
            #EXTINF:4.0,
            https://other.example.org/seg4.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Media
        assertEquals("https://example.com/hls/movie/seg1.ts", playlist.segments[0].uri)
        assertEquals("https://example.com/root/seg2.ts", playlist.segments[1].uri)
        assertEquals("https://cdn.example.net/seg3.ts", playlist.segments[2].uri)
        assertEquals("https://other.example.org/seg4.ts", playlist.segments[3].uri)
    }

    // ---------------------------------------------------------------- 加密

    @Test
    fun parses_aes128_key_with_explicit_iv() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="enc.key",IV=0x5d0f11431b118ee80b8f2d658437020f
            #EXTINF:8.0,
            0.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Media
        val key = playlist.segments[0].key!!
        assertEquals("AES-128", key.method)
        assertEquals("https://example.com/hls/movie/enc.key", key.uri)
        assertEquals("5d0f11431b118ee80b8f2d658437020f", key.iv)
    }

    @Test
    fun aes128_without_iv_leaves_iv_null() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="k.key"
            #EXTINF:4.0,
            0.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Media
        assertNull(playlist.segments[0].key!!.iv)
    }

    @Test
    fun key_method_none_clears_encryption() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="k.key"
            #EXTINF:4.0,
            encrypted.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4.0,
            plain.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Media
        assertTrue(playlist.segments[0].key != null)
        assertNull(playlist.segments[1].key)
    }

    @Test
    fun key_change_applies_to_following_segments_only() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="a.key"
            #EXTINF:4.0,
            s0.ts
            #EXT-X-KEY:METHOD=AES-128,URI="b.key"
            #EXTINF:4.0,
            s1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Media
        assertTrue(playlist.segments[0].key!!.uri!!.endsWith("a.key"))
        assertTrue(playlist.segments[1].key!!.uri!!.endsWith("b.key"))
    }

    @Test
    fun sample_aes_is_rejected() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://key",KEYFORMAT="com.apple.streamingkeydelivery"
            #EXTINF:4.0,
            0.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val error = assertFailsWith<HlsException> { M3u8Parser.parse(text, base) }
        assertTrue(error.reason.contains("不支持"))
    }

    @Test
    fun non_identity_keyformat_is_rejected() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="skd://key",KEYFORMAT="com.widevine"
            #EXTINF:4.0,
            0.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val error = assertFailsWith<HlsException> { M3u8Parser.parse(text, base) }
        assertTrue(error.reason.contains("DRM"), error.reason)
    }

    @Test
    fun identity_keyformat_is_accepted() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="enc.key",KEYFORMAT="identity"
            #EXTINF:4.0,
            0.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Media
        assertEquals("AES-128", playlist.segments[0].key!!.method)
    }

    @Test
    fun parses_session_key_from_master_playlist() {
        val text = """
            #EXTM3U
            #EXT-X-SESSION-KEY:METHOD=AES-128,URI="enc.key",IV=0x5d0f11431b118ee80b8f2d658437020f
            #EXT-X-STREAM-INF:BANDWIDTH=1000000
            media.m3u8
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Master
        val key = assertNotNull(playlist.sessionKey)
        assertEquals("https://example.com/hls/movie/enc.key", key.uri)
        assertEquals("5d0f11431b118ee80b8f2d658437020f", key.iv)
    }

    @Test
    fun session_key_is_inherited_by_media_playlist() {
        val master = """
            #EXTM3U
            #EXT-X-SESSION-KEY:METHOD=AES-128,URI="enc.key"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000
            media.m3u8
        """.trimIndent()
        val media = """
            #EXTM3U
            #EXTINF:4.0,
            0.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val sessionKey = (M3u8Parser.parse(master, base) as HlsPlaylist.Master).sessionKey
        val playlist = M3u8Parser.parse(media, base, sessionKey) as HlsPlaylist.Media
        assertEquals("https://example.com/hls/movie/enc.key", playlist.segments[0].key!!.uri)
    }

    @Test
    fun media_key_overrides_inherited_session_key() {
        val media = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="own.key"
            #EXTINF:4.0,
            0.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val sessionKey = HlsKey("AES-128", "https://example.com/hls/movie/session.key")
        val playlist = M3u8Parser.parse(media, base, sessionKey) as HlsPlaylist.Media
        assertTrue(playlist.segments[0].key!!.uri!!.endsWith("own.key"))
    }

    @Test
    fun too_many_segments_are_rejected() {
        val text = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-TARGETDURATION:4")
            repeat(10_001) { i ->
                appendLine("#EXTINF:4.0,")
                appendLine("$i.ts")
            }
            appendLine("#EXT-X-ENDLIST")
        }

        val error = assertFailsWith<HlsException> { M3u8Parser.parse(text, base) }
        assertTrue(error.reason.contains("分片过多"), error.reason)
    }

    // ---------------------------------------------------------------- fMP4

    @Test
    fun parses_ext_x_map() {
        val text = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            seg1.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Media
        assertEquals("https://example.com/hls/movie/init.mp4", playlist.map!!.uri)
        assertTrue(playlist.isFragmentedMp4)
    }

    @Test
    fun map_byterange_defaults_to_zero_offset() {
        val text = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="720"
            #EXTINF:4.0,
            seg1.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Media
        assertEquals(0L until 720L, playlist.map!!.byteRange)
    }

    @Test
    fun encrypted_map_is_rejected() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="k.key"
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            seg1.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        val error = assertFailsWith<HlsException> { M3u8Parser.parse(text, base) }
        assertTrue(error.reason.contains("加密的初始化段"), error.reason)
    }

    @Test
    fun multiple_distinct_maps_are_rejected() {
        val text = """
            #EXTM3U
            #EXT-X-MAP:URI="init0.mp4"
            #EXTINF:4.0,
            seg0.m4s
            #EXT-X-DISCONTINUITY
            #EXT-X-MAP:URI="init1.mp4"
            #EXTINF:4.0,
            seg1.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        val error = assertFailsWith<HlsException> { M3u8Parser.parse(text, base) }
        assertTrue(error.reason.contains("多个初始化段"), error.reason)
    }

    @Test
    fun repeated_identical_map_is_not_treated_as_multiple() {
        val text = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            seg0.m4s
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            seg1.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Media
        assertEquals(2, playlist.segments.size)
        assertEquals("https://example.com/hls/movie/init.mp4", playlist.map!!.uri)
    }

    @Test
    fun ts_stream_has_no_map() {
        val text = """
            #EXTM3U
            #EXTINF:4.0,
            0.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Media
        assertNull(playlist.map)
        assertTrue(!playlist.isFragmentedMp4)
    }

    // ---------------------------------------------------------------- BYTERANGE

    @Test
    fun parses_ext_x_byterange() {
        val text = """
            #EXTM3U
            #EXT-X-BYTERANGE:1000@0
            #EXTINF:4.0,
            full.ts
            #EXT-X-BYTERANGE:2000@1000
            #EXTINF:4.0,
            full.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Media
        assertEquals(0L until 1000L, playlist.segments[0].byteRange)
        assertEquals(1000L until 3000L, playlist.segments[1].byteRange)
    }

    @Test
    fun byterange_without_offset_continues_previous_subrange() {
        val text = """
            #EXTM3U
            #EXT-X-BYTERANGE:1000@0
            #EXTINF:4.0,
            full.ts
            #EXT-X-BYTERANGE:500
            #EXTINF:4.0,
            full.ts
            #EXT-X-BYTERANGE:250
            #EXTINF:4.0,
            other.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Media
        assertEquals(0L until 1000L, playlist.segments[0].byteRange)
        assertEquals(1000L until 1500L, playlist.segments[1].byteRange, "省略 offset 应紧接同一资源上一子区间")
        assertEquals(0L until 250L, playlist.segments[2].byteRange, "不同资源应重新从 0 开始")
    }

    // ---------------------------------------------------------------- 直播 / 异常

    @Test
    fun live_stream_is_rejected() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            0.ts
            #EXTINF:6.0,
            1.ts
        """.trimIndent()

        val error = assertFailsWith<HlsException> { M3u8Parser.parse(text, base) }
        assertTrue(error.reason.contains("直播"))
    }

    @Test
    fun missing_extm3u_header_is_rejected() {
        val text = """
            #EXTINF:4.0,
            0.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        assertFailsWith<HlsException> { M3u8Parser.parse(text, base) }
    }

    @Test
    fun empty_playlist_is_rejected() {
        val text = "#EXTM3U\n#EXT-X-ENDLIST"
        assertFailsWith<HlsException> { M3u8Parser.parse(text, base) }
    }

    // ---------------------------------------------------------------- 文本细节

    @Test
    fun handles_bom_and_crlf() {
        val text = "\uFEFF#EXTM3U\r\n#EXTINF:4.0,\r\n0.ts\r\n#EXT-X-ENDLIST\r\n"
        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Media
        assertEquals(1, playlist.segments.size)
        assertEquals("https://example.com/hls/movie/0.ts", playlist.segments[0].uri)
    }

    @Test
    fun ignores_blank_lines_and_unknown_tags() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-INDEPENDENT-SEGMENTS

            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:4.0,

            0.ts

            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = M3u8Parser.parse(text, base) as HlsPlaylist.Media
        assertEquals(1, playlist.segments.size)
    }

    @Test
    fun attribute_value_with_comma_is_not_split() {
        val attrs = M3u8Parser.parseAttributes("""BANDWIDTH=1000,CODECS="avc1.64,mp4a.40.2",RESOLUTION=1x1""")
        assertEquals("1000", attrs["BANDWIDTH"])
        assertEquals("avc1.64,mp4a.40.2", attrs["CODECS"])
        assertEquals("1x1", attrs["RESOLUTION"])
    }

    // ---------------------------------------------------------------- 真实样本

    /**
     * 用线上样本的**结构**做回归（去掉时效性 sign 参数）：
     * 直接是 media playlist、AES-128 + 显式 IV、TS 分片、28 片、带 ENDLIST。
     */
    @Test
    fun parses_real_world_sample_structure() {
        val text = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:6")
            appendLine("#EXT-X-TARGETDURATION:8")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
            appendLine("#EXT-X-KEY:METHOD=AES-128,URI=\"enc.key\",IV=0x5d0f11431b118ee80b8f2d658437020f")
            repeat(27) { i ->
                appendLine("#EXTINF:8.000000,")
                appendLine("$i.ts")
            }
            appendLine("#EXTINF:1.066667,")
            appendLine("27.ts")
            appendLine("#EXT-X-ENDLIST")
        }

        val playlist = M3u8Parser.parse(text, "https://example.com/hl718/1f/bb/39/a2/x/1/index.m3u8") as HlsPlaylist.Media
        assertEquals(28, playlist.segments.size)
        assertEquals("https://example.com/hl718/1f/bb/39/a2/x/1/enc.key", playlist.segments[0].key!!.uri)
        assertEquals("5d0f11431b118ee80b8f2d658437020f", playlist.segments[0].key!!.iv)
        assertTrue(playlist.segments.all { it.key != null })
        assertTrue(playlist.segments.all { it.uri.endsWith(".ts") })
        assertEquals(27 * 8 + 1.066667, playlist.totalDuration, 0.001)
    }

    @Test
    fun parseVariantsOrNull_returns_null_for_media_playlist() {
        val text = """
            #EXTM3U
            #EXTINF:4.0,
            0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertNull(M3u8Parser.parseVariantsOrNull(text, base))
    }
}

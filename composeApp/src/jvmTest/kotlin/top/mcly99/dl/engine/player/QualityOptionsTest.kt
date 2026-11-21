package top.mcly99.dl.engine.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 清晰度档位解析的纯逻辑单测。
 *
 * 主播放列表原文进、给人看的档位列表出，不发任何网络请求。
 * 真正的「切档后能不能接着播」依赖 libvlc，属于 docs/CATCH_WEB_PLAN.md §8 的手工验证。
 */
class QualityOptionsTest {

    private val masterUrl = "https://cdn.example.com/hls/master.m3u8"

    // ---------------------------------------------------------------- 档位解析

    @Test
    fun master_playlist_yields_options_sorted_by_quality() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720
            v/720/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080
            v/1080/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=854x480
            v/480/index.m3u8
        """.trimIndent()

        val options = QualityOptions.fromMaster(master, masterUrl)

        assertEquals(listOf("1080p", "720p", "480p"), options.map { it.label })
        assertEquals(
            listOf(
                "https://cdn.example.com/hls/v/1080/index.m3u8",
                "https://cdn.example.com/hls/v/720/index.m3u8",
                "https://cdn.example.com/hls/v/480/index.m3u8",
            ),
            options.map { it.url },
        )
        // 副标签给出码率，方便用户判断「同一分辨率不同码率」的档位
        assertEquals("4.5 Mbps", options.first().detail)
    }

    @Test
    fun audio_track_name_is_shown_in_detail() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="国语",DEFAULT=YES,URI="audio/cn.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080,AUDIO="aud"
            v/1080/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,AUDIO="aud"
            v/720/index.m3u8
        """.trimIndent()

        val options = QualityOptions.fromMaster(master, masterUrl)

        assertEquals("4.5 Mbps · 国语", options.first().detail)
        assertEquals("800 kbps · 国语", options.last().detail)
    }

    @Test
    fun variants_without_resolution_fall_back_to_bandwidth_label() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2500000
            v/high.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=500000
            v/low.m3u8
        """.trimIndent()

        val options = QualityOptions.fromMaster(master, masterUrl)

        assertEquals(listOf("2.5 Mbps", "500 kbps"), options.map { it.label })
        // 标签已经是码率，副标签再重复一次没有意义
        assertNull(options.first().detail)
    }

    @Test
    fun single_variant_has_no_selector() {
        // 只有一档时给下拉只会让人以为「切换没生效」
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080
            v/1080/index.m3u8
        """.trimIndent()

        assertTrue(QualityOptions.fromMaster(master, masterUrl).isEmpty())
    }

    @Test
    fun media_playlist_has_no_selector() {
        // 普通点播列表本来就没有 STREAM-INF，属于「没有清晰度可选」而不是错误
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        assertTrue(QualityOptions.fromMaster(media, masterUrl).isEmpty())
    }

    @Test
    fun malformed_text_returns_empty_instead_of_throwing() {
        assertTrue(QualityOptions.fromMaster("not a playlist", masterUrl).isEmpty())
        assertTrue(QualityOptions.fromMaster("", masterUrl).isEmpty())
    }

    @Test
    fun duplicate_variants_are_merged_by_url() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080
            v/1080/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080
            v/1080/index.m3u8
        """.trimIndent()

        assertEquals(1, QualityOptions.fromMaster(master, masterUrl).size)
    }

    @Test
    fun option_count_is_capped() {
        val variants = (1..15).joinToString("\n") { i ->
            """
            #EXT-X-STREAM-INF:BANDWIDTH=${i * 100_000},RESOLUTION=${i * 100}x2160
            v/$i/index.m3u8
            """.trimIndent()
        }
        val options = QualityOptions.fromMaster("#EXTM3U\n$variants", masterUrl)

        assertEquals(QualityOptions.MAX_OPTIONS, options.size)
        // 截断保留的是最高档，不是页面上先出现的那些
        assertEquals("1500p", options.first().label)
    }

    // ---------------------------------------------------------------- 字段解析

    @Test
    fun height_uses_the_short_side() {
        assertEquals(1080, QualityOptions.parseHeight("1920x1080"))
        // 竖屏声明：用户认的是 1080p，不是 1920p
        assertEquals(1080, QualityOptions.parseHeight("1080x1920"))
        assertEquals(720, QualityOptions.parseHeight("720x1280"))
        assertEquals(1080, QualityOptions.parseHeight(" 1920X1080 "))
    }

    @Test
    fun height_is_zero_when_resolution_is_absent_or_invalid() {
        assertEquals(0, QualityOptions.parseHeight(null))
        assertEquals(0, QualityOptions.parseHeight(""))
        assertEquals(0, QualityOptions.parseHeight("1080"))
        assertEquals(0, QualityOptions.parseHeight("1920x"))
        assertEquals(0, QualityOptions.parseHeight("1920xabc"))
        assertEquals(0, QualityOptions.parseHeight("0x0"))
    }

    @Test
    fun bandwidth_is_formatted_in_mbps_or_kbps() {
        assertEquals("4.5 Mbps", QualityOptions.formatBandwidth(4_500_000))
        assertEquals("1.0 Mbps", QualityOptions.formatBandwidth(1_000_000))
        assertEquals("500 kbps", QualityOptions.formatBandwidth(500_000))
        assertNull(QualityOptions.formatBandwidth(0))
        assertNull(QualityOptions.formatBandwidth(-1))
    }
}

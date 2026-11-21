package top.mcly99.dl.engine.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [HtmlMediaExtractor] 单测。
 *
 * 提取器是纯函数（`String -> List<MediaCandidate>`），所以这里不起服务器、不碰网络 ——
 * 抓取相关的集成测试在 [WebPageFetcherTest] 里。
 *
 * 用例按「真实站点的写法」组织：属性直链、内联脚本 JSON、相对路径、转义、分片干扰。
 */
class HtmlMediaExtractorTest {

    private val pageUrl = "https://example.com/video/123"

    private fun extract(html: String, page: String = pageUrl, maxResults: Int = 200) =
        HtmlMediaExtractor.extract(html, page, maxResults)

    // ------------------------------------------------------------------ 通道 A：标签属性

    @Test
    fun extracts_video_src_attribute() {
        val results = extract("""<video src="https://cdn.example.com/v/a.mp4" controls></video>""")

        assertEquals(1, results.size)
        assertEquals("https://cdn.example.com/v/a.mp4", results[0].url)
        assertEquals(MediaKind.Mp4, results[0].kind)
        assertEquals(MediaCandidate.Source.Attribute, results[0].source)
    }

    @Test
    fun data_src_attribute_is_also_scanned() {
        val results = extract("""<div data-src="https://cdn.example.com/hls/index.m3u8"></div>""")

        assertEquals(1, results.size)
        assertEquals(MediaKind.M3u8, results[0].kind)
    }

    @Test
    fun reads_video_url_from_meta_content() {
        // 社交分享卡片常把正片地址放在 meta 里，页面上没有可见的 <video>
        val html = """
            <meta property="og:video" content="https://cdn.example.com/v/trailer.mp4">
            <meta name="twitter:player:stream" content="https://cdn.example.com/hls/live.m3u8">
        """.trimIndent()
        val results = extract(html)

        assertEquals(2, results.size)
        assertEquals(
            setOf("https://cdn.example.com/v/trailer.mp4", "https://cdn.example.com/hls/live.m3u8"),
            results.map { it.url }.toSet(),
        )
    }

    @Test
    fun meta_content_without_media_url_is_ignored() {
        // 只在 meta 里认 content，且必须是媒体地址，否则每个页面都会冒出垃圾候选
        val html = """
            <meta property="og:image" content="https://cdn.example.com/cover.jpg">
            <meta name="description" content="这是一个视频页面">
        """.trimIndent()

        assertTrue(extract(html).isEmpty())
    }

    @Test
    fun uses_anchor_text_as_name() {
        val html = """<a href="https://cdn.example.com/hls/ep03.m3u8">第 3 集</a>"""
        val results = extract(html)

        assertEquals(1, results.size)
        assertEquals("第 3 集", results[0].name)
    }

    @Test
    fun falls_back_to_anchor_title_attribute() {
        val html = """<a href="https://cdn.example.com/hls/ep03.m3u8" title="第 3 集"></a>"""
        val results = extract(html)

        assertEquals("第 3 集", results[0].name)
    }

    @Test
    fun strips_tags_inside_anchor_text() {
        val html = """<a href="https://cdn.example.com/v/a.mp4"><span>预告</span><b>片</b></a>"""
        val results = extract(html)

        // 标签被换成空格再压缩，得到「预告 片」而不是「预告片」—— 名称只是提示，可接受
        assertTrue(results[0].name.startsWith("预告"), "实际: ${results[0].name}")
        assertFalse(results[0].name.contains("<"), "名称里不应残留标签")
    }

    // ------------------------------------------------------------------ 通道 B：内联脚本

    @Test
    fun extracts_url_from_inline_script_json() {
        val html = """
            <script>
              var player_data = {"url":"https://cdn.example.com/hls/index.m3u8","poster":"p.jpg"};
            </script>
        """.trimIndent()
        val results = extract(html)

        assertEquals(1, results.size)
        assertEquals("https://cdn.example.com/hls/index.m3u8", results[0].url)
        assertEquals(MediaCandidate.Source.InlineScript, results[0].source)
    }

    @Test
    fun resolves_bare_relative_path_in_inline_script() {
        // 不带 `./` 或 `/` 前缀的写法很常见；解析时必须补在当前页面目录下
        val results = extract("""<script>var u = "video/index.m3u8";</script>""")

        assertEquals(1, results.size)
        assertEquals("https://example.com/video/video/index.m3u8", results[0].url)
        assertEquals(MediaCandidate.Source.RelativePath, results[0].source)
    }

    @Test
    fun ignores_quoted_strings_without_media_extension() {
        // 放宽相对路径匹配后，不能把任意字符串都当成候选
        val html = """<script>var a = "some/other/file"; var b = "just/text";</script>"""

        assertTrue(extract(html).isEmpty())
    }

    @Test
    fun restores_json_escaped_slashes() {
        val html = """<script>var u = "https:\/\/cdn.example.com\/hls\/index.m3u8";</script>"""
        val results = extract(html)

        assertEquals(1, results.size)
        assertEquals("https://cdn.example.com/hls/index.m3u8", results[0].url)
    }

    @Test
    fun restores_html_entity_in_query() {
        val html = """<a href="https://cdn.example.com/a.m3u8?x=1&amp;y=2">x</a>"""
        val results = extract(html)

        assertEquals(1, results.size)
        assertEquals("https://cdn.example.com/a.m3u8?x=1&y=2", results[0].url)
    }

    @Test
    fun keeps_signature_query_untouched() {
        val html = """<script>load("https://cdn.example.com/a.m3u8?sign=abc%2Fdef&t=1")</script>"""
        val results = extract(html)

        assertEquals("https://cdn.example.com/a.m3u8?sign=abc%2Fdef&t=1", results[0].url)
    }

    @Test
    fun restores_hex_entity_slashes() {
        // 有的模板引擎会把 URL 里的斜杠全转成实体，不还原就一条都匹配不到
        val html = """<script>var u = "https:&#x2F;&#x2F;cdn.example.com&#x2F;hls&#x2F;a.m3u8";</script>"""
        val results = extract(html)

        assertEquals(1, results.size)
        assertEquals("https://cdn.example.com/hls/a.m3u8", results[0].url)
    }

    @Test
    fun restores_quot_entity_around_url() {
        // 属性值被整体转义：`&quot;https://…&quot;`
        val html = """<script>var u = &quot;https://cdn.example.com/a.m3u8&quot;;</script>"""
        val results = extract(html)

        assertEquals(1, results.size)
        assertEquals("https://cdn.example.com/a.m3u8", results[0].url)
    }

    // ------------------------------------------------------------------ 通道 C：相对路径

    @Test
    fun resolves_parent_relative_path() {
        val results = extract("""<a href="../v/a.mp4">x</a>""")

        assertEquals(1, results.size)
        assertEquals("https://example.com/v/a.mp4", results[0].url)
    }

    @Test
    fun resolves_root_relative_path() {
        val results = extract("""<a href="/v/a.mp4">x</a>""")

        assertEquals("https://example.com/v/a.mp4", results[0].url)
    }

    @Test
    fun resolves_protocol_relative_url() {
        val results = extract("""<source src="//cdn.example.com/hls/a.m3u8">""")

        assertEquals("https://cdn.example.com/hls/a.m3u8", results[0].url)
    }

    @Test
    fun percent_encodes_non_ascii_path() {
        val results = extract("""<a href="/视频/第3集.mp4">x</a>""")

        assertEquals(1, results.size)
        assertTrue(results[0].url.startsWith("https://example.com/"), "实际: ${results[0].url}")
        assertFalse(results[0].url.contains("第"), "非 ASCII 路径应被百分号编码")
    }

    // ------------------------------------------------------------------ 过滤与去重

    @Test
    fun deduplicates_same_url_across_channels() {
        val html = """
            <video src="https://cdn.example.com/v/a.mp4"></video>
            <script>var u = "https://cdn.example.com/v/a.mp4";</script>
        """.trimIndent()
        val results = extract(html)

        assertEquals(1, results.size)
        // 属性通道先跑，先到先得
        assertEquals(MediaCandidate.Source.Attribute, results[0].source)
    }

    @Test
    fun ignores_hls_segments() {
        val html = """
            <script>
              var list = ["https://cdn.example.com/hls/seg1.ts","https://cdn.example.com/hls/seg2.m4s"];
              var playlist = "https://cdn.example.com/hls/index.m3u8";
            </script>
        """.trimIndent()
        val results = extract(html)

        assertEquals(1, results.size)
        assertEquals(MediaKind.M3u8, results[0].kind)
    }

    @Test
    fun ignores_javascript_and_magnet_links() {
        val html = """
            <a href="javascript:void(0)">播放</a>
            <a href="magnet:?xt=urn:btih:abcdef">下载</a>
        """.trimIndent()

        assertTrue(extract(html).isEmpty())
    }

    @Test
    fun ignores_non_media_assets() {
        val html = """
            <link rel="stylesheet" href="https://cdn.example.com/app.css">
            <script src="https://cdn.example.com/app.js"></script>
            <img src="https://cdn.example.com/logo.png">
        """.trimIndent()

        assertTrue(extract(html).isEmpty())
    }

    @Test
    fun truncates_to_max_results() {
        val html = (1..250).joinToString("\n") {
            """<a href="https://cdn.example.com/hls/$it.m3u8">第 $it 集</a>"""
        }

        assertEquals(200, extract(html).size)
        assertEquals(5, extract(html, maxResults = 5).size)
    }

    @Test
    fun sorts_m3u8_before_mp4() {
        val html = """
            <a href="https://cdn.example.com/v/a.mp4">A</a>
            <a href="https://cdn.example.com/hls/b.m3u8">B</a>
            <a href="https://cdn.example.com/v/c.mkv">C</a>
        """.trimIndent()
        val results = extract(html)

        assertEquals(listOf(MediaKind.M3u8, MediaKind.Mp4, MediaKind.Other), results.map { it.kind })
    }

    @Test
    fun keeps_page_order_within_same_kind() {
        val html = """
            <a href="https://cdn.example.com/v/first.mp4">第一</a>
            <a href="https://cdn.example.com/v/second.mp4">第二</a>
        """.trimIndent()
        val results = extract(html)

        assertEquals(
            listOf("https://cdn.example.com/v/first.mp4", "https://cdn.example.com/v/second.mp4"),
            results.map { it.url },
        )
    }

    // ------------------------------------------------------------------ 健壮性

    @Test
    fun returns_empty_for_blank_html() {
        assertTrue(extract("").isEmpty())
        assertTrue(extract("   \n\t ").isEmpty())
    }

    @Test
    fun returns_empty_when_page_has_no_media() {
        assertTrue(extract("<html><body><p>纯文字页面</p></body></html>").isEmpty())
    }

    @Test
    fun tolerates_malformed_html() {
        val html = """<div><video src="https://cdn.example.com/v/a.mp4" <span>未闭合"""
        val results = extract(html)

        assertEquals(1, results.size)
        assertEquals("https://cdn.example.com/v/a.mp4", results[0].url)
    }

    @Test
    fun tolerates_invalid_page_url_by_dropping_relative_paths() {
        val html = """<a href="/v/a.mp4">相对</a><a href="https://cdn.example.com/v/b.mp4">绝对</a>"""
        val results = extract(html, page = "not a url")

        assertEquals(1, results.size)
        assertEquals("https://cdn.example.com/v/b.mp4", results[0].url)
    }

    // ------------------------------------------------------------------ 命名与能力

    @Test
    fun falls_back_to_page_title_with_index() {
        val html = """
            <html><head><title>某某剧</title></head><body>
            <video src="https://cdn.example.com/v/1.mp4"></video>
            <video src="https://cdn.example.com/v/2.mp4"></video>
            </body></html>
        """.trimIndent()
        val results = extract(html)

        assertEquals(listOf("某某剧 - 1", "某某剧 - 2"), results.map { it.name })
    }

    @Test
    fun uses_page_title_alone_when_single_result() {
        val html = """<html><head><title>某某剧</title></head><body><video src="https://cdn.example.com/v/1.mp4"></video></body></html>"""

        assertEquals("某某剧", extract(html)[0].name)
    }

    @Test
    fun falls_back_to_url_last_segment() {
        val html = """<script>var u = "https://cdn.example.com/hls/index.m3u8";</script>"""

        assertEquals("index", extract(html)[0].name)
    }

    @Test
    fun every_candidate_remembers_its_page_as_referer() {
        val html = """<a href="https://cdn.example.com/hls/ep03.m3u8">第 3 集</a>"""
        val results = extract(html)

        // 跨域 CDN 认的是播放页地址，这个值会一路带到下载任务里
        assertEquals(pageUrl, results[0].referer)
    }

    @Test
    fun referer_is_null_when_page_url_is_unusable() {
        val results = extract("""<a href="https://cdn.example.com/v/a.mp4">x</a>""", page = "  ")

        assertEquals(null, results[0].referer)
    }

    // ------------------------------------------------------------------ iframe 一层

    @Test
    fun extracts_iframe_sources() {
        val html = """
            <iframe src="https://player.example.com/embed/123"></iframe>
            <iframe src="//player.example.com/embed/456"></iframe>
            <iframe src="/local/player"></iframe>
        """.trimIndent()
        val frames = HtmlMediaExtractor.extractFrames(html, pageUrl)

        assertEquals(
            listOf(
                "https://player.example.com/embed/123",
                "https://player.example.com/embed/456",
                "https://example.com/local/player",
            ),
            frames,
        )
    }

    @Test
    fun extracts_lazy_loaded_iframe_sources() {
        // 懒加载播放器：真实地址在 data-src 上，src 是占位图或空
        val html = """
            <iframe src="about:blank" data-src="https://player.example.com/embed/789"></iframe>
            <frame data-src="/frame/player"></frame>
        """.trimIndent()
        val frames = HtmlMediaExtractor.extractFrames(html, pageUrl)

        assertEquals(
            listOf("https://player.example.com/embed/789", "https://example.com/frame/player"),
            frames,
        )
    }

    @Test
    fun iframe_list_is_deduplicated_and_capped() {
        val html = (1..10).joinToString("\n") { """<iframe src="https://p.example.com/$it"></iframe>""" }
            .plus("\n").plus("""<iframe src="https://p.example.com/1"></iframe>""")
        val frames = HtmlMediaExtractor.extractFrames(html, pageUrl)

        // 上限 3 个（广告 iframe 太多），重复地址只留一次
        assertEquals(listOf(1, 2, 3).map { "https://p.example.com/$it" }, frames)
    }

    @Test
    fun ad_iframes_do_not_crowd_out_the_player() {
        val html = """
            <iframe src="https://www.googletagmanager.com/ns.html?id=GTM-X"></iframe>
            <iframe src="https://hm.baidu.com/hm.js?abc"></iframe>
            <iframe src="https://ads.example.com/1"></iframe>
            <iframe src="https://player.example.com/embed/123"></iframe>
        """.trimIndent()

        val frames = HtmlMediaExtractor.extractFrames(html, pageUrl)

        // 广告/统计 iframe 直接排除；剩下的按「像播放器」排，播放器不会被文档顺序挤掉
        assertEquals("https://player.example.com/embed/123", frames.first())
        assertFalse(frames.any { it.contains("googletagmanager") || it.contains("hm.baidu") })
    }

    @Test
    fun player_iframe_beats_earlier_ad_frames() {
        val ads = (1..3).joinToString("\n") { """<iframe src="https://ads.example.com/$it"></iframe>""" }
        val frames = HtmlMediaExtractor.extractFrames(
            "$ads\n<iframe src=\"https://player.example.com/embed/9\"></iframe>",
            pageUrl,
        )

        assertEquals("https://player.example.com/embed/9", frames.first())
    }

    @Test
    fun iframe_list_ignores_blank_and_non_http_sources() {
        val html = """
            <iframe src="about:blank"></iframe>
            <iframe src="javascript:void(0)"></iframe>
            <iframe></iframe>
        """.trimIndent()

        assertTrue(HtmlMediaExtractor.extractFrames(html, pageUrl).isEmpty())
    }

    @Test
    fun only_m3u8_and_mp4_can_play() {
        assertTrue(MediaKind.M3u8.canPlay)
        assertTrue(MediaKind.Mp4.canPlay)
        assertFalse(MediaKind.Other.canPlay)
    }

    @Test
    fun kind_is_detected_from_path_extension_only() {
        assertEquals(MediaKind.M3u8, MediaKind.fromUrl("https://cdn.example.com/hls/index.m3u8?sign=1"))
        assertEquals(MediaKind.Mp4, MediaKind.fromUrl("https://cdn.example.com/v/a.MP4"))
        // query 里的扩展名不算数
        assertEquals(null, MediaKind.fromUrl("https://cdn.example.com/play?file=a.m3u8"))
        assertEquals(null, MediaKind.fromUrl("https://cdn.example.com/hls/seg1.ts"))
    }
}

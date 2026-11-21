package top.mcly99.dl.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 移植自 YunX-Desktop `desktop/src/test/.../data/network/ShareLinkParserTest.kt`
 * （AGPL-3.0），按 ktdown 包名与 kotlin.test 改写，并补齐下游枚举里另外 4 个平台的回归。
 *
 * 解析器是 47 个移植文件之一，但上游的单测没有跟着过来；一旦升级上游时正则被改坏，
 * 原先只有 `CloudDriveServiceParseTest` 间接覆盖到一小部分。
 */
class ShareLinkParserTest {

    @Test
    fun parses_all_supported_platforms() {
        val cases = listOf(
            "https://pan.quark.cn/s/Abc123?pwd=a1B2" to (SharePlatform.QUARK to "Abc123"),
            "https://drive.uc.cn/s/Abc123" to (SharePlatform.UC to "Abc123"),
            "https://pan.xunlei.com/s/Abc_123-xy" to (SharePlatform.XUNLEI to "Abc_123-xy"),
            "https://pan.baidu.com/s/1Abc_123-xy?pwd=9xYz" to (SharePlatform.BAIDU to "Abc_123-xy"),
            "https://yun.139.com/shareweb/#/w/i/Abc_123" to (SharePlatform.C139 to "Abc_123"),
            "https://www.123pan.com/s/2785Vv-T4Ded" to (SharePlatform.PAN123 to "2785Vv-T4Ded"),
            "https://115.com/s/swabc123def?password=n307" to (SharePlatform.PAN115 to "swabc123def"),
            "https://www.guangyapan.com/s/gy_123" to (SharePlatform.GUANGYA to "gy_123"),
            "https://www.ilanzou.com/s/ilz123" to (SharePlatform.ILANZOU to "ilz123"),
            "https://www.lanzou.com/iABC123" to (SharePlatform.LANZOU to "iABC123"),
        )

        cases.forEach { (text, expected) ->
            val parsed = ShareLinkParser.parse(text)
                ?: error("期望能解析：$text")
            assertEquals(expected.first, parsed.platform, text)
            assertEquals(expected.second, parsed.shareId, text)
        }
    }

    @Test
    fun explicit_text_password_is_extracted() {
        val parsed = ShareLinkParser.parse("链接 https://drive.uc.cn/s/Abc123 提取码：a1B2")!!
        assertEquals("a1B2", parsed.pwd)
    }

    @Test
    fun baidu_surl_loses_its_leading_one() {
        val parsed = ShareLinkParser.parse("https://pan.baidu.com/s/1Abc123?pwd=9xYz")!!
        assertEquals("Abc123", parsed.shareId)
        assertEquals("9xYz", parsed.pwd)
    }

    @Test
    fun pan115_command_carries_its_own_passcode() {
        val parsed = ShareLinkParser.parse("https://115.com/sws8lxs36jf-n307/")!!
        assertEquals(SharePlatform.PAN115, parsed.platform)
        assertEquals("sws8lxs36jf", parsed.shareId)
        assertEquals("n307", parsed.pwd)
    }

    @Test
    fun ilanzou_is_not_mistaken_for_lanzou() {
        // 没有前置边界时 "www.ilanzou.com" 里的 "lanzou.com" 会先命中蓝奏云
        val parsed = ShareLinkParser.parse("https://www.ilanzou.com/s/abc?pwd=ab12")!!
        assertEquals(SharePlatform.ILANZOU, parsed.platform)
        assertEquals("abc", parsed.shareId)
        assertEquals("ab12", parsed.pwd)
    }

    @Test
    fun rejects_unrelated_url() {
        assertNull(ShareLinkParser.parse("https://example.com/s/Abc123"))
        // GitHub 是枚举里的第 11 个值，但解析器不认它（改走抓网页 / 添加任务）
        assertNull(ShareLinkParser.parse("https://github.com/tidain/YunX-Desktop"))
    }
}

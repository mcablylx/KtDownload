package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.SharePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 分享链接识别与提取码自动填充：纯本地逻辑，不发网络请求 */
class CloudDriveServiceParseTest {

    private val service = CloudDriveService()

    @Test
    fun recognises_all_supported_share_platforms() {
        val cases = listOf(
            "https://pan.quark.cn/s/abc123" to SharePlatform.QUARK,
            "https://drive.uc.cn/s/abcd1234" to SharePlatform.UC,
            "https://pan.xunlei.com/s/VOabc-123" to SharePlatform.XUNLEI,
            "https://pan.baidu.com/s/1abcDEF_123?pwd=abcd" to SharePlatform.BAIDU,
            "https://yun.139.com/shareweb/#/w/i/abcDEF123" to SharePlatform.C139,
            "https://www.123pan.com/s/abc-DEF" to SharePlatform.PAN123,
            "https://115.com/s/swabc123def" to SharePlatform.PAN115,
            "https://www.guangyapan.com/s/abc_123" to SharePlatform.GUANGYA,
            "https://www.ilanzou.com/s/abc123" to SharePlatform.ILANZOU,
            "https://www.lanzoux.com/abc123" to SharePlatform.LANZOU,
        )
        cases.forEach { (url, platform) ->
            assertEquals(platform, service.parse(url)?.platform, "识别失败: $url")
        }
    }

    @Test
    fun recognises_link_inside_share_text() {
        val text = "链接：https://pan.quark.cn/s/abc123 提取码：8k2f 复制到浏览器打开"
        val parsed = service.parse(text)
        assertEquals(SharePlatform.QUARK, parsed?.platform)
        assertEquals("abc123", parsed?.shareId)
        assertEquals("8k2f", parsed?.passcode)
    }

    @Test
    fun extracts_passcode_from_url_parameters_and_text() {
        assertEquals("abcd", service.extractPasscode("https://pan.baidu.com/s/1abc?pwd=abcd"))
        assertEquals("n307", service.extractPasscode("https://115.com/s/swabc?password=n307"))
        assertEquals("x7y8", service.extractPasscode("https://www.lanzou.com/a?passcode=x7y8"))
        assertEquals("8k2f", service.extractPasscode("https://pan.quark.cn/s/abc 提取码：8k2f"))
        assertNull(service.extractPasscode("https://pan.quark.cn/s/abc"))
    }

    @Test
    fun baidu_surl_drops_leading_one() {
        assertEquals("abcDEF_123", service.parse("https://pan.baidu.com/s/1abcDEF_123")?.shareId)
    }
}

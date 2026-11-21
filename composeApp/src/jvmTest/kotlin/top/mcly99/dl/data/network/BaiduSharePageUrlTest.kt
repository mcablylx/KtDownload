package top.mcly99.dl.data.network

import org.junit.Test
import kotlin.test.assertEquals

/**
 * 分享页地址必须带开头的 `1`。
 *
 * 链接解析时按接口惯例把 `surl` 的首位 `1` 去掉了（`xpan/share?shorturl=` 要的就是去掉 1 的部分），
 * 而网页 `/s/<surl>` 少了它百度直接回 404「页面不存在」：
 *   curl https://pan.baidu.com/s/o885bq4mskOb_KjoJy5TLw   → 404 页面不存在
 *   curl https://pan.baidu.com/s/1o885bq4mskOb_KjoJy5TLw  → 302 /share/init?surl=o885...
 * 分享页 404 就拿不到 yunData 里的 shareid / share_uk，转存只能靠猜。
 */
class BaiduSharePageUrlTest {

    @Test
    fun adds_leading_one_to_short_surl() {
        assertEquals(
            "https://pan.baidu.com/s/1o885bq4mskOb_KjoJy5TLw",
            BaiduConstants.sharePageUrl("o885bq4mskOb_KjoJy5TLw"),
        )
    }

    /** surl 本身以 1 开头（原始链接是 /s/11xxx）时不能再补一个 */
    @Test
    fun keeps_surl_that_already_starts_with_one() {
        assertEquals(
            "https://pan.baidu.com/s/1abcDEF",
            BaiduConstants.sharePageUrl("1abcDEF"),
        )
    }

    /**
     * 前端接口（tplconfig 等）同样要带首位 1，且与分享页用的是同一个值。
     * 实测：`/share/tplconfig?surl=o885...` → errno=2「啊哦，链接出错了」；
     *      `/share/tplconfig?surl=1o885...` → errno=0 + data.sign。
     */
    @Test
    fun api_surl_restores_leading_one() {
        assertEquals("1o885bq4mskOb_KjoJy5TLw", BaiduConstants.apiSurl("o885bq4mskOb_KjoJy5TLw"))
        assertEquals("1abcDEF", BaiduConstants.apiSurl("1abcDEF"))
        assertEquals("1abcDEF", BaiduConstants.apiSurl("  abcDEF "))
    }
}

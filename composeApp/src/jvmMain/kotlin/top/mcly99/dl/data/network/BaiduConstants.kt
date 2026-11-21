/*
 * Ported from YunX-Desktop — https://github.com/tidain/YunX-Desktop
 * Upstream: main branch @ 00a030f7 (tag v1.3.4, 2026-11-13).
 * Copyright (C) the YunX-Desktop contributors.
 * License: GNU Affero General Public License v3.0 (AGPL-3.0).
 * Full text: third-party/yunx-desktop/LICENSE.txt and THIRD-PARTY-NOTICES.md.
 * This file is a derivative work: namespace isolated, app services shimmed.
 */
package top.mcly99.dl.data.network

/**
 * 百度网盘登录与 API 相关常量（依据抓包 + 文档）。
 */
object BaiduConstants {

    /** WebView 登录页（提取 BDUSS/STOKEN） */
    const val LOGIN_URL = "https://pan.baidu.com/"

    /** 提取 Cookie 的域名 */
    const val COOKIE_DOMAIN = "https://pan.baidu.com"

    /** PC 网页 UA（share/verify、xpan/share、gettemplatevariable 使用） */
    const val UA_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** 百度客户端 UA（yun/api/list、filemanager、locatedownload 使用） */
    const val UA_NETDISK =
        "netdisk;12.24.6;piano;android-android;16;JSbridge4.4.0;jointBridge;1.1.0"

    /** 百度网盘统一 app_id */
    const val APP_ID = "250528"

    /** 临时转存目录名（本应用自有命名，不再沿用上游 YunX 的字样） */
    const val TEMP_DIR_NAME = "MclyDownload临时转存"

    /**
     * 历史临时目录名（早期版本沿用上游命名建的目录）。
     *
     * 老用户的网盘里可能已经有这个目录、甚至里面还有转存过的文件，
     * 所以它只用于「优先复用」，不会新建。
     */
    val LEGACY_TEMP_DIR_NAMES = listOf("YunX临时转存")

    /**
     * 网页/前端接口要的 surl：**带**开头的 `1`。
     *
     * 链接解析时按 HTTP 接口惯例把 `surl` 开头的 `1` 去掉了（`rest/2.0/xpan/share?shorturl=`、
     * `share/verify?surl=` 要的就是去掉 1 的部分），但所有「网页前端」用的地址都必须带那个 `1`：
     *  - `/s/<surl>`：少了 1 百度直接回 404（`<title>页面不存在</title>`）；
     *  - `/share/tplconfig?surl=`：少了 1 回 `{"errno":2,"show_msg":"啊哦，链接出错了"}`（实测），
     *    带上 1 立刻回 `{"errno":0,"data":{"sign":...}}`。
     *
     * 两个接口对同一个短码要求相反的形态，是这条链路最容易踩的坑。
     */
    fun apiSurl(surl: String): String {
        val s = surl.trim()
        return if (s.startsWith("1")) s else "1$s"
    }

    /** 分享页地址（必须带首位 `1`，见 [apiSurl]） */
    fun sharePageUrl(surl: String): String = "https://pan.baidu.com/s/${apiSurl(surl)}"

    /** 关键 Cookie 字段，缺失 BDUSS 则视为未登录 */
    fun isValidCookie(cookie: String?): Boolean =
        cookie != null && cookie.contains("BDUSS=")

    /**
     * 是否带 STOKEN。
     *
     * 只有 BDUSS 时，`pan.baidu.com` 的列表类接口能用（所以「浏览分享目录」看起来正常），
     * 但**分享页拿不到 loginstate、share/transfer 一律拒绝**（errno=2，err_msg 还写成
     * 「文件已存在」，极具误导）。BaiduPCS-Go 对此有同样的判定：
     * 「请确认登录参数中已经包含了网盘 STOKEN」。
     */
    fun hasStoken(cookie: String?): Boolean =
        cookie != null && Regex("""(^|;\s*)STOKEN=[^;]+""").containsMatchIn(cookie)

    /** Cookie 里出现的键名（只取键、不取值，便于日志排查且不泄露凭据） */
    fun cookieKeys(cookie: String): List<String> =
        cookie.split(';')
            .mapNotNull { it.trim().substringBefore('=', "").takeIf(String::isNotBlank) }
            .distinct()
}
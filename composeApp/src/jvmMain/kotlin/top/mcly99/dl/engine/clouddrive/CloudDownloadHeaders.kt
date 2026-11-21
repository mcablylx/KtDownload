package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.BaiduConstants
import top.mcly99.dl.data.network.C139Constants
import top.mcly99.dl.data.network.GuangYaConstants
import top.mcly99.dl.data.network.ILanzouConstants
import top.mcly99.dl.data.network.LanzouConstants
import top.mcly99.dl.data.network.Pan115Constants
import top.mcly99.dl.data.network.Pan123Constants
import top.mcly99.dl.data.network.QuarkConstants
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.UCConstants
import top.mcly99.dl.data.network.XunleiConstants
import java.net.URI

/**
 * 一条网盘直链在**下载阶段**必须携带的 HTTP 头。
 *
 * 取链和下载是两次完全独立的请求：取链接口返回的 URL 只是 CDN 上的签名地址，
 * CDN 侧的防盗链 / 限速 / 会话校验全部发生在下载这一次请求上。所以「用哪份 Cookie、
 * 哪个 UA、哪个 Referer」必须与取链时保持一致，否则会出现这些典型故障：
 *
 * - 115：CDN 同时校验登录 Cookie 与取链响应下发的 900 秒 CDN Cookie，缺任一直接
 *   `403 no cookie value`；UA 用浏览器串会被判成「网页端」拒发大文件直链。
 * - 迅雷：直链自带签名，浏览器 UA 会让 CDN 降级成「整文件 200」，分片续传失效。
 * - UC：OSS 按 Referer 档位限速，缺 Referer 会被 Callback 限到 ~100 KB/s。
 * - 百度：缺 `netdisk` 客户端 UA 时大文件直链会被打回登录页。
 * - 蓝奏：下载节点要求 `Referer` 是该节点自己的 origin，并要注入 `down_ip=1`。
 *
 * 这些差异集中在这里，`CloudDriveService` 只负责把结果搬进 [CloudLink]，
 * 下载引擎只负责把字段写进请求头 —— 任何一侧都不再各自猜平台。
 *
 * @property userAgent 覆盖引擎默认 UA（`null` = 沿用引擎按 Referer/Cookie 推断的默认值）
 * @property referer 防盗链来源；网盘直链一律非空
 * @property origin `Origin` 头，只有 UC / 蓝奏优享这类会校验跨域来源的 CDN 需要
 * @property secChUa UC 游客链路的 `Sec-Ch-Ua` 客户端提示（与游客 UA 配套）
 * @property cookie 下载会话；`null` 表示这个平台不该带 Cookie（如 139 / 123 / 光鸭）
 */
data class CloudDownloadHeaders(
    val userAgent: String? = null,
    val referer: String? = null,
    val origin: String? = null,
    val secChUa: String? = null,
    val cookie: String? = null,
) {

    companion object {

        /**
         * 按平台生成下载头。
         *
         * @param credential 当前账号凭证（登录 Cookie / token JSON）
         * @param guestCookie 取链响应随直链下发的临时会话（夸克/UC 的 `__puus`、115 的 CDN Cookie）；
         *   非空表示这是一条游客直链，此时必须用它而不是账号凭证。
         * @param downloadUrl 取链返回的最终地址，只有蓝奏需要从中取节点 origin。
         */
        fun of(
            platform: SharePlatform,
            credential: String,
            guestCookie: String = "",
            downloadUrl: String = "",
        ): CloudDownloadHeaders {
            val guest = guestCookie.isNotBlank()
            // 游客直链优先用服务端下发的临时会话；否则用账号凭证
            val session = if (guest) guestCookie else credential
            return when (platform) {
                // 夸克：防盗链要固定 Referer；游客与登录态 UA 相同（对齐 AList quark_uc）
                SharePlatform.QUARK -> CloudDownloadHeaders(
                    userAgent = QuarkConstants.API_USER_AGENT,
                    referer = QuarkConstants.DOWNLOAD_REFERER,
                    cookie = session.nonBlank,
                )

                // UC：OSS 直链按 Referer 档位限速；游客链路换 uc-cloud-drive 客户端 UA
                SharePlatform.UC -> CloudDownloadHeaders(
                    userAgent = if (guest) UCConstants.GUEST_UA else UCConstants.USER_AGENT,
                    referer = UCConstants.DOWNLOAD_REFERER,
                    origin = UCConstants.WEB_ORIGIN,
                    secChUa = if (guest) UCConstants.GUEST_SEC_CH_UA else null,
                    cookie = session.nonBlank,
                )

                // 百度：大文件直链要求 netdisk 客户端 UA + 登录 Cookie
                SharePlatform.BAIDU -> CloudDownloadHeaders(
                    userAgent = BaiduConstants.UA_NETDISK,
                    referer = refererFor(SharePlatform.BAIDU),
                    cookie = credential.nonBlank,
                )

                // 139：直链是 CDN 签名地址，账号不参与校验，带 Cookie 反而是多余外发
                SharePlatform.C139 -> CloudDownloadHeaders(
                    userAgent = C139Constants.PC_UA,
                    referer = refererFor(SharePlatform.C139),
                )

                // 123：CDN 签名地址，必须带 Referer（文档 §5.3.1）
                SharePlatform.PAN123 -> CloudDownloadHeaders(
                    userAgent = Pan123Constants.WEB_UA,
                    referer = Pan123Constants.DOWNLOAD_REFERER,
                )

                // 115：登录 Cookie + CDN Cookie 必须合并后一起发，UA 用客户端串
                SharePlatform.PAN115 -> CloudDownloadHeaders(
                    userAgent = Pan115Constants.CLIENT_UA,
                    referer = Pan115Constants.DOWNLOAD_REFERER,
                    cookie = Pan115Constants.mergeCookies(credential, guestCookie).nonBlank,
                )

                // 光鸭：签名 CDN 地址，只要 UA + Referer，不带 Authorization/Cookie
                SharePlatform.GUANGYA -> CloudDownloadHeaders(
                    userAgent = GuangYaConstants.WEB_UA,
                    referer = GuangYaConstants.DOWNLOAD_REFERER,
                )

                // 蓝奏优享：CDN 校验 UA / Referer / Origin，不认 Cookie
                SharePlatform.ILANZOU -> CloudDownloadHeaders(
                    userAgent = ILanzouConstants.WEB_UA,
                    referer = ILanzouConstants.DOWNLOAD_REFERER,
                    origin = ILanzouConstants.DOWNLOAD_ORIGIN,
                )

                // 蓝奏云：节点要求 Referer 是自己的 origin，并注入 down_ip=1
                SharePlatform.LANZOU -> CloudDownloadHeaders(
                    userAgent = LanzouConstants.WEB_UA,
                    referer = lanzouReferer(downloadUrl),
                    cookie = LanzouConstants.DOWN_IP_COOKIE,
                )

                // 迅雷：直链自带签名，不要 Cookie；UA 必须是官方 app 串，
                // 浏览器 UA 会触发 CDN 降级（回 200 整文件，Range 失效）
                SharePlatform.XUNLEI -> CloudDownloadHeaders(
                    userAgent = XunleiConstants.APP_UA,
                    referer = refererFor(SharePlatform.XUNLEI),
                )

                // GitHub：raw / codeload 不需要登录态，Referer 仅为标识来源
                SharePlatform.GITHUB -> CloudDownloadHeaders(
                    referer = refererFor(SharePlatform.GITHUB),
                    cookie = credential.nonBlank,
                )
            }
        }

        /**
         * 平台默认 Referer。蓝奏云按节点动态取，这里给的是公共站点兜底值。
         * 供 UI / 日志展示，以及平台无关的调用点使用。
         */
        fun refererFor(platform: SharePlatform): String = when (platform) {
            SharePlatform.QUARK -> QuarkConstants.DOWNLOAD_REFERER
            SharePlatform.UC -> UCConstants.DOWNLOAD_REFERER
            SharePlatform.BAIDU -> "https://pan.baidu.com/"
            SharePlatform.XUNLEI -> "https://pan.xunlei.com/"
            SharePlatform.C139 -> "https://yun.139.com/"
            SharePlatform.PAN123 -> Pan123Constants.DOWNLOAD_REFERER
            SharePlatform.PAN115 -> Pan115Constants.DOWNLOAD_REFERER
            SharePlatform.GUANGYA -> GuangYaConstants.DOWNLOAD_REFERER
            SharePlatform.ILANZOU -> ILanzouConstants.DOWNLOAD_REFERER
            SharePlatform.LANZOU -> "https://www.lanzou.com/"
            SharePlatform.GITHUB -> "https://github.com/"
        }

        /** 蓝奏下载节点要求 Referer 是**该节点自己**的 origin，不是公共域名 */
        internal fun lanzouReferer(downloadUrl: String): String {
            val origin = runCatching { URI(downloadUrl) }.getOrNull()
                ?.let { uri ->
                    val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: return@let null
                    val host = uri.host?.takeIf { it.isNotBlank() } ?: return@let null
                    // 非默认端口要一起带上，否则 Referer 与节点 origin 对不上
                    val port = uri.port.takeIf { it > 0 && it != 443 && it != 80 }?.let { ":$it" }.orEmpty()
                    "$scheme://$host$port"
                }
            return when {
                origin.isNullOrBlank() || origin == "null" -> "https://pan.lanzoui.com/"
                origin.endsWith("/") -> origin
                else -> "$origin/"
            }
        }
    }
}

/** 空串一律视为「不设这个头」，避免下游发一个空的 `Cookie:` */
private val String?.nonBlank: String?
    get() = this?.takeIf { it.isNotBlank() }

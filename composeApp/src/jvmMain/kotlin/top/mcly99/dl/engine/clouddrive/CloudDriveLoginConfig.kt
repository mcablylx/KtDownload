package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.Pan115Constants
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.XunleiWebCredential

/** 登录态的提取方式 */
enum class CloudLoginMode {
    /** 轮询目标域 Cookie，必需键齐全即视为登录成功（YunX JcefLoginPane 的做法） */
    COOKIE,

    /** 迅雷：localStorage → Cookie 桥 → access_token（YunX XunleiWebLoginScreen 的做法） */
    XUNLEI_WEB,

    /** 站点不下发可判定的 Cookie：仍在内嵌浏览器里登录，由用户点「读取当前 Cookie」或手工粘贴 */
    MANUAL,
}

/**
 * 每个网盘的网页登录参数，逐项对齐 YunX-Desktop 的 `*LoginScreen.kt`。
 *
 * @property cookieDomains 轮询 / 清理 Cookie 时匹配的域名子串
 * @property requiredKeys 必须全部出现的 Cookie 键名
 * @property anyOfKeys 候选键名，任一命中也算登录（139 的客户端 / 网页双形态）
 * @property transform 保存前对整串 Cookie 做过滤（115 只保留会话键）
 */
data class CloudLoginConfig(
    val loginUrl: String,
    val cookieDomains: List<String>,
    val requiredKeys: List<String> = emptyList(),
    val anyOfKeys: List<String> = emptyList(),
    val mode: CloudLoginMode = CloudLoginMode.COOKIE,
    val note: String = "",
    val transform: (String) -> String = { it },
)

object CloudDriveLoginCatalog {
    fun of(platform: SharePlatform): CloudLoginConfig? = when (platform) {
        SharePlatform.QUARK -> CloudLoginConfig(
            loginUrl = "https://pan.quark.cn/?fr=pc&platform=pc",
            cookieDomains = listOf(".quark.cn", "quark.cn"),
            requiredKeys = listOf("__pus", "__puus"),
        )

        SharePlatform.UC -> CloudLoginConfig(
            loginUrl = "https://drive.uc.cn/",
            cookieDomains = listOf(".uc.cn", "uc.cn"),
            requiredKeys = listOf("__pus", "__puus"),
        )

        SharePlatform.XUNLEI -> CloudLoginConfig(
            loginUrl = XunleiWebCredential.LOGIN_URL,
            cookieDomains = listOf("xunlei.com"),
            mode = CloudLoginMode.XUNLEI_WEB,
            note = "迅雷的登录态保存在网页本地存储里，登录成功后会自动读取；若长时间未检测到，可在下方手工粘贴 access_token",
        )

        SharePlatform.BAIDU -> CloudLoginConfig(
            loginUrl = "https://pan.baidu.com/",
            cookieDomains = listOf(".baidu.com", "baidu.com"),
            // 必须同时有 STOKEN：只有 BDUSS 时浏览目录能用，但转存会被判「未登录分享页」
            // 并恒回 errno=2（err_msg 写作「文件已存在」），BaiduPCS-Go 对此有同样判定
            requiredKeys = listOf("BDUSS", "STOKEN"),
            note = "请在内嵌网页里完成登录，等页面进入网盘主页（地址栏为 pan.baidu.com/disk/... ）再点保存",
        )

        SharePlatform.C139 -> CloudLoginConfig(
            loginUrl = "https://yun.139.com/m/#/login",
            cookieDomains = listOf("mail.10086.cn", "yun.139.com", ".10086.cn"),
            requiredKeys = listOf("Os_SSo_Sid", "RMKEY"),
            anyOfKeys = listOf("authorization"),
        )

        SharePlatform.PAN115 -> CloudLoginConfig(
            loginUrl = "https://115.com/",
            cookieDomains = listOf("115.com", "115cdn.com"),
            requiredKeys = listOf("UID", "SEID"),
            transform = Pan115Constants::filterLoginCookies,
        )

        SharePlatform.PAN123 -> CloudLoginConfig(
            loginUrl = "https://www.123pan.com/",
            cookieDomains = listOf("123pan.com"),
            mode = CloudLoginMode.MANUAL,
            note = "123 云盘网页不暴露可直接用于 API 的 Cookie，登录后请点「读取当前 Cookie」或手工粘贴 access token",
        )

        SharePlatform.GUANGYA -> CloudLoginConfig(
            loginUrl = "https://www.guangyapan.com/",
            cookieDomains = listOf("guangyapan.com"),
            mode = CloudLoginMode.MANUAL,
            note = "光鸭云盘请在下方手工粘贴 access token",
        )

        SharePlatform.ILANZOU -> CloudLoginConfig(
            loginUrl = "https://www.ilanzou.com/",
            cookieDomains = listOf("ilanzou.com"),
            mode = CloudLoginMode.MANUAL,
            note = "蓝奏云优享版请在下方手工粘贴 appToken / uuid",
        )

        SharePlatform.LANZOU -> CloudLoginConfig(
            loginUrl = "https://www.lanzou.com/",
            cookieDomains = listOf("lanzou.com"),
            mode = CloudLoginMode.MANUAL,
            note = "蓝奏云通常无需登录；如分享需要密码，请在下方手工粘贴 Cookie（可选）",
        )

        // 与上游一致：GitHub 不提供网盘登录页
        SharePlatform.GITHUB -> null
    }
}

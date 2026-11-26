/*
 * ktdown original code (not ported from YunX-Desktop).
 * Depends on top.mcly99.dl.data.network.XunleiWebCredential (AGPL-3.0, ported).
 */
package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.XunleiWebCredential
import org.json.JSONObject
import java.net.URLDecoder

/**
 * 迅雷网盘的可用凭证：`access_token` + `refresh_token` + 设备号。
 *
 * 为什么要单独一层：迅雷网盘的接口（`api-pan.xunlei.com`）只认
 * `Authorization: Bearer <access_token>`，而且**每次请求都要带与该账号配套的
 * device_id 与 X-Captcha-Token**。而我们这边登录走的是内嵌网页（localStorage →
 * Cookie 桥），拿到的东西有三种形态：
 *
 * 1. `yunx_xl_cred_*` Cookie 分片（桥接脚本写的，里面是 localStorage 的原始 JSON）；
 * 2. localStorage 原文 JSON（手动粘贴，含 `access_token` / `refresh_token`）；
 * 3. 裸 `access_token`（手动粘贴兜底，没有 refresh_token，过期只能重新登录）。
 *
 * 以前直接把整串 Cookie 当 token 交给解析层，`jwtExp()` 解析不出 `exp`
 * （返回 0）就被判成「已过期」，于是刚登录完也报「迅雷登录已过期」。
 * 这里负责把三种形态统一收敛成结构化的四个字段。
 */
data class XunleiDriveCredential(
    val accessToken: String,
    val refreshToken: String = "",
    val deviceId: String = "",
    val captchaToken: String = "",
    val userId: String = "",
) {
    /** 落库/传给解析层用的规范形态（只放解析层真正会用的字段） */
    fun toJson(): String = JSONObject()
        .put(FIELD_ACCESS, accessToken)
        .put(FIELD_REFRESH, refreshToken)
        .put(FIELD_DEVICE, deviceId)
        .put(FIELD_CAPTCHA, captchaToken)
        .put(FIELD_USER, userId)
        .toString()

    /** 能否支撑「登录态」的下载（转存 / 取直链必须有 access_token） */
    val isUsable: Boolean get() = accessToken.isNotBlank()

    /** 卡片副标题用：`token eyJhbGci…`，比整串 Cookie 的掩码可读 */
    fun preview(): String =
        if (accessToken.isBlank()) "" else "token ${accessToken.take(8)}…"

    /**
     * 拼出一次请求要的三件套（access_token + X-Device-Id + X-Captcha-Token）。
     *
     * **设备号必须非空**：`X-Device-Id: ` 空值会被服务端直接判
     * `invalid_argument / device_id is empty`，用户看到的就是「请求参数错误」。
     * 网页登录在「退出后重新登录」时经常抓不到 `deviceid` Cookie，凭证里就成了空串，
     * 所以要由调用方提供本地设备指纹兜底。
     *
     * **验证码与设备号绑定**（服务端校验失败会回 `captcha_invalid / device not match`）：
     * 一旦设备号是兜底来的，登录时带回来的那条 captcha 一定属于网页侧另一个设备，
     * 复用它只会稳定失败，因此丢弃、交给调用方按当前设备号现签。
     */
    fun requestIdentity(fallbackDeviceId: () -> String): XunleiRequestIdentity {
        val usableDevice = deviceId.ifBlank { fallbackDeviceId() }
        return XunleiRequestIdentity(
            accessToken = accessToken,
            deviceId = usableDevice,
            captchaToken = if (deviceId.isBlank()) "" else captchaToken,
        )
    }

    companion object {
        private const val FIELD_ACCESS = "accessToken"
        private const val FIELD_REFRESH = "refreshToken"
        private const val FIELD_DEVICE = "deviceId"
        private const val FIELD_CAPTCHA = "captchaToken"
        private const val FIELD_USER = "userId"

        /**
         * 把任意形态的迅雷凭证收敛成 [XunleiDriveCredential]；无法解析时返回 null。
         *
         * 解析顺序刻意如此：先认自己的规范 JSON（最快、无歧义），再认 Cookie 串
         * （老版本存下来的形态，也是登录页「读取当前 Cookie」的产物），最后交给
         * [XunleiWebCredential.parse] 处理 localStorage 原文与裸 token。
         */
        fun parse(raw: String): XunleiDriveCredential? {
            val text = raw.trim()
            if (text.isEmpty()) return null

            fromOwnJson(text)?.let { return it }

            if (looksLikeCookie(text)) {
                fromCookie(text)?.let { return it }
            }

            val tokens = XunleiWebCredential.parse(text) ?: return null
            return XunleiDriveCredential(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                deviceId = tokens.deviceId,
                captchaToken = tokens.captchaToken,
                userId = tokens.userId,
            ).takeIf { it.isUsable }
        }

        /** 自己写出去的规范 JSON（键名 camelCase） */
        private fun fromOwnJson(text: String): XunleiDriveCredential? {
            if (!text.startsWith("{")) return null
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val access = json.optString(FIELD_ACCESS)
            if (access.isBlank()) return null
            return XunleiDriveCredential(
                accessToken = access,
                refreshToken = json.optString(FIELD_REFRESH),
                deviceId = json.optString(FIELD_DEVICE),
                captchaToken = json.optString(FIELD_CAPTCHA),
                userId = json.optString(FIELD_USER),
            )
        }

        /** `key=value; key=value`：既可能是 Cookie 串，也可能是别的东西，先粗判 */
        private fun looksLikeCookie(text: String): Boolean =
            !text.startsWith("{") && text.contains('=') && text.contains(";")


        /** Cookie 串 → 键值对，再交给 [fromCookiePairs] 统一处理 */
        private fun fromCookie(text: String): XunleiDriveCredential? =
            fromCookiePairs(
                text.split(';')
                    .mapNotNull { seg ->
                        val s = seg.trim()
                        val idx = s.indexOf('=')
                        if (idx <= 0) null else s.substring(0, idx).trim() to s.substring(idx + 1).trim()
                    }
                    .toMap(),
            )

        /**
         * Cookie 键值对 → 凭证，**分片拼接的唯一实现**。
         *
         * 桥接脚本把 localStorage 原文 URL 编码后按 3000 字符切片写进
         * `yunx_xl_cred_0..n`（单条 Cookie 有 4KB 上限），这里按分片序号拼回来再解析。
         * 拼不出来但串里有 `access_token=`（某些浏览器插件会把整段 JSON 塞进 Cookie）时也认。
         *
         * 公开出来是为了让 `JcefCookies` 直接复用：CEF 已经把 Cookie 收成 Map，
         * 以前那里又抄了一遍分片逻辑，并且另有一个只取 access_token 的旧入口，
         * 导致「刚登录就过期」的兜底路径拿不到 refresh_token。
         */
        fun fromCookiePairs(pairs: Map<String, String>): XunleiDriveCredential? {
            val deviceId = normalizeDeviceId(pairs["deviceid"].orEmpty())

            val count = pairs[XunleiWebCredential.CRED_COOKIE_COUNT]?.toIntOrNull() ?: 0
            if (count in 1..32) {
                val payload = buildString {
                    for (i in 0 until count) {
                        append(pairs["${XunleiWebCredential.CRED_COOKIE_PREFIX}$i"].orEmpty())
                    }
                }
                decode(payload)?.let { raw ->
                    XunleiWebCredential.parse(raw)?.let { tokens ->
                        return XunleiDriveCredential(
                            accessToken = tokens.accessToken,
                            refreshToken = tokens.refreshToken,
                            deviceId = tokens.deviceId.ifBlank { deviceId },
                            captchaToken = tokens.captchaToken,
                            userId = tokens.userId,
                        ).takeIf { it.isUsable }
                    }
                }
            }

            // 兜底：Cookie 里直接放了 access_token（抓包复制 / 插件写回）
            val directAccess = pairs["access_token"].orEmpty()
            if (directAccess.isNotBlank()) {
                return XunleiDriveCredential(
                    accessToken = directAccess,
                    refreshToken = pairs["refresh_token"].orEmpty(),
                    deviceId = deviceId,
                )
            }
            return null
        }

        /** Cookie 里的 `deviceid` 可能是 `前缀.32位` 复合形态，与桥接脚本取法保持一致 */
        private fun normalizeDeviceId(raw: String): String {
            val decoded = decode(raw)?.trim().orEmpty()
            if (decoded.isBlank()) return ""
            return if (decoded.contains('.') && decoded.length > 32) {
                decoded.split('.')[1].take(32)
            } else {
                decoded
            }
        }

        private fun decode(value: String): String? {
            if (value.isBlank()) return null
            return runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }
                .getOrElse { value }
        }
    }
}

/**
 * 一次迅雷请求的身份（三件套）。
 *
 * 单独成类型而不是 `Triple`：这三项**必须来自同一个账号、同一台设备**，
 * 位置参数一旦传反（device 与 captcha 互换）服务端只会回一句「参数错误」，很难排查。
 */
data class XunleiRequestIdentity(
    val accessToken: String,
    val deviceId: String,
    val captchaToken: String,
)

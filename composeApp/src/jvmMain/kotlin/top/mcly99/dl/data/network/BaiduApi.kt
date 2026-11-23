/*
 * Ported from YunX-Desktop — https://github.com/tidain/YunX-Desktop
 * Upstream: main branch @ 00a030f7 (tag v1.3.4, 2026-11-13).
 * Copyright (C) the YunX-Desktop contributors.
 * License: GNU Affero General Public License v3.0 (AGPL-3.0).
 * Full text: third-party/yunx-desktop/LICENSE.txt and THIRD-PARTY-NOTICES.md.
 * This file is a derivative work: namespace isolated, app services shimmed.
 */
package top.mcly99.dl.data.network

import top.mcly99.dl.data.network.model.QuotaInfo
import top.mcly99.dl.data.network.model.ShareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import top.mcly99.dl.utils.AppLogger
import top.mcly99.dl.utils.deduplicateCookieHeader
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

/**
 * 百度网盘分享解析数据（xpan/share list 响应）。
 */
data class BaiduShareList(
    val title: String,
    val shareId: String,
    val uk: String,
    val files: List<ShareFile>
)

/**
 * 百度转存结果：转存后的新 fs_id + 新路径（locatedownload 用路径）。
 */
data class BaiduTransferResult(
    val fsId: String,
    val path: String
)

/**
 * 分享元信息：`share/transfer` 必需的 `shareid`（分享数字 ID）与 `from`（分享者 uk）。
 *
 * 注意：这两个值**不是**链接里的 surl（`pan.baidu.com/s/1xxxxxxxx` 的短码），
 * 也无法保证由列表接口返回；现行做法是从分享页 HTML 内嵌的 `yunData` 里取
 * （`"shareid":123,"share_uk":"456"`）。缺失时百度只会回一个 errno=2「文件已存在」，
 * 既不提参数也不提目录，极难定位。
 */
data class BaiduShareMeta(
    val shareId: String,
    val uk: String
)

/**
 * 分享直链签名（`/share/tplconfig` 或分享页 `yunData` 里的 `sign` + `timestamp`）。
 *
 * `/api/sharedownload`（网页端「下载」按钮走的接口）要求这两个值，
 * 缺了会被直接拒（errno=2 / 9019 need verify），与 `bdstoken` 无关。
 *
 * 两个来源的差别很重要：`tplconfig` 是我们自己拼的请求换来的签名，
 * 分享页里的那份是**浏览器自己渲染页面时拿到的**，与页面里的 `bdstoken`、验证态同源，
 * 现行页面就靠它调 `/api/sharedownload`。因此取链时优先用分享页那份。
 */
data class BaiduShareSign(
    val sign: String,
    val timestamp: String
)

/**
 * 分享页解析结果（页面内嵌的 `window.yunData` / `window.locals`）。
 *
 * @property shareId `shareid`（数字 ID），转存 / 取链的 `primaryid`
 * @property uk 分享者 `share_uk`，转存的 `from`、取链的 `uk`
 * @property sign 直链签名；验证页（要输提取码的那页）里没有，只有内容页才有
 * @property timestamp 与 [sign] 配对的毫秒时间戳
 * @property bdstoken 分享页自己下发的那份 token（不是用户网盘首页的），部分账号只认它
 */
data class BaiduSharePage(
    val shareId: String,
    val uk: String,
    val sign: String = "",
    val timestamp: String = "",
    val bdstoken: String = "",
) {
    /** 页面里带齐了签名才够调 `/api/sharedownload` */
    fun toSign(): BaiduShareSign? =
        if (sign.isNotBlank() && timestamp.isNotBlank()) BaiduShareSign(sign, timestamp) else null
}

/**
 * 百度网盘 API 封装（OkHttp + Cookie 认证）：
 * 登录态 BDUSS 经 Cookie 携带；分享解析链路 share/verify → xpan/share → share/transfer → filemetas。
 * 全部基于抓包字段，错误码用 errno（0 表示成功）判定。
 */
class BaiduApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {
    /** 每次请求动态获取全局客户端（忽略 SSL 开关切换即时生效） */
    private val client get() = clientProvider()

    private val TAG = "BaiduApi"

    private val formMediaType = "application/x-www-form-urlencoded".toMediaType()

    /** bdstoken 缓存（登录态内长期有效） */
    @Volatile
    private var cachedBdstoken: String? = null

    /** 缓存归属：cachedBdstoken 对应的 Cookie 哈希，换凭证后必须重取 */
    @Volatile
    private var cachedBdstokenOwner: Int = 0

    /** 上次转存成功的参数形状下标（见 [TRANSFER_SHAPES]），批量下载时直接复用它 */
    @Volatile
    private var preferredShapeIndex: Int = 0

    /** 已经打印过键名的 Cookie 哈希：诊断信息只打一次，别刷屏 */
    @Volatile
    private var loggedCookieOwner: Int = 0

    /** surl -> 分享页解析结果，同一分享的多个文件共用（含签名，10 分钟后过期重抓） */
    private val sharePageCache = java.util.concurrent.ConcurrentHashMap<String, PageEntry>()

    /** `tplconfig` 换来的签名（按 surl + 登录态缓存）：一个分享只需换一次 */
    private val shareSignCache = java.util.concurrent.ConcurrentHashMap<String, BaiduShareSign>()

    /** 免转存失败过的分享（3 分钟内不再重试，直接走转存，避免每个文件白跑一轮参数形状） */
    private val directDeadShares = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 上次免转存成功的参数形状下标（见 [DIRECT_SHAPES]），同账号后续文件直接用它 */
    @Volatile
    private var preferredDirectShape: Int = 0

    private class PageEntry(val page: BaiduSharePage, val at: Long)

    /** 解析失败的 surl（负缓存）：批量下载几百个文件时不该为同一个失败分享反复抓页面 */
    private val shareMetaMisses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // ---------- 账号 ----------

    /** 获取昵称（gettemplatevariable 的 username 字段）；失败返回 null */
    @Suppress("UNUSED") // 与其它网盘 API 保持对称的账号入口，预留给昵称展示
    suspend fun fetchNickname(cookie: String): String? = withContext(Dispatchers.IO) {
        val result = templateVariable(cookie, """["username"]""") ?: return@withContext null
        result.optString("username").takeIf { it.isNotBlank() }
    }

    /** 获取 bdstoken（gettemplatevariable，带缓存） */
    suspend fun getBdstoken(cookie: String): String? = withContext(Dispatchers.IO) {
        // 缓存必须与 Cookie 绑定：换账号/游客→登录后仍用旧 token 会让转存/删除静默失败
        cachedBdstoken?.takeIf { it.isNotBlank() && cachedBdstokenOwner == cookie.hashCode() }
            ?.let { return@withContext it }
        val result = templateVariable(cookie, """["bdstoken"]""") ?: return@withContext null
        val token = result.optString("bdstoken").takeIf { it.isNotBlank() } ?: return@withContext null
        cachedBdstoken = token
        cachedBdstokenOwner = cookie.hashCode()
        token
    }

    /**
     * 强制刷新 bdstoken（清缓存后重新拉取）。
     * 用于 errno=-6（bdstoken 失效）时重试，或登出/换账号时清空避免跨账号复用。
     */
    suspend fun refreshBdstoken(cookie: String): String? = withContext(Dispatchers.IO) {
        cachedBdstoken = null
        cachedBdstokenOwner = 0
        val result = templateVariable(cookie, """["bdstoken"]""") ?: return@withContext null
        val token = result.optString("bdstoken").takeIf { it.isNotBlank() } ?: return@withContext null
        cachedBdstoken = token
        cachedBdstokenOwner = cookie.hashCode()
        token
    }

    /**
     * 清空 bdstoken 缓存，避免旧 token 跨账号复用导致 errno=-6。
     *
     * 当前无需外部调用：缓存按 `cookie.hashCode()` 绑定 owner，换账号后 hashCode 变化
     * 会自动失效；`refreshBdstoken` 也已强制清缓存。保留为对称 API 备用。
     */
    @Suppress("UNUSED")
    fun clearSessionCache() {
        cachedBdstoken = null
        cachedBdstokenOwner = 0
    }

    private suspend fun templateVariable(cookie: String, fields: String): JSONObject? =
        withContext(Dispatchers.IO) {
            val url = "https://pan.baidu.com/api/gettemplatevariable" +
                "?clienttype=0&channel=chunlei&app_id=${BaiduConstants.APP_ID}&web=1&fields=" +
                URLEncoder.encode(fields, "UTF-8")
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", BaiduConstants.UA_WEB)
                .get()
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.optInt("errno") != 0) return@use null
                    json.optJSONObject("result")
                }
            }.getOrNull()
        }

    // ---------- 分享解析 ----------

    /**
     * 取分享元信息（`shareid` + `from`），转存接口的**必需参数**。
     *
     * 这是本模块最容易踩的坑：链接里的 `surl`（`1AbCdEf`）只是短码，
     * `share/transfer` 要的是数字 `shareid`，`from` 要的是分享者的 `uk`。
     * 列表接口不一定回这两个字段（子目录列表尤其可能回空），一旦传空，
     * 百度不报「参数缺失」，只回 `{"errno":2,"show_msg":"文件已存在"}` ——
     * 看起来像重名，实际是参数不成立，怎么换目录、换 token 都好不了。
     *
     * 因此这里按「分享页 HTML」解析，与通用转存工具的做法一致：
     * 分享页把 `yunData` 以内联 JSON 的形式写进 HTML（`"shareid":123,"share_uk":"456"`），
     * 带 BDCLND（= 提取码验证得到的 randsk）访问即可拿到。
     *
     * @param sekey 提取码验证得到的 randsk；公共分享传空
     * @return 解析成功返回元信息；页面无该字段（已失效 / 需验证）时返回 null
     */
    suspend fun shareMeta(
        surl: String,
        sekey: String,
        cookie: String,
        force: Boolean = false,
    ): BaiduShareMeta? =
        sharePage(surl, sekey, cookie, force)?.let { BaiduShareMeta(it.shareId, it.uk) }

    /**
     * 抓一次分享页，把页面里能用的东西全解析出来（[BaiduSharePage]）。
     *
     * 为什么不能只取 `shareid`/`uk`：`/api/sharedownload` 要的 `sign` + `timestamp`
     * 最好就用**分享页这一份**。页面里的签名与页面下发的 `bdstoken`、验证态同源，
     * 网页端「下载」按钮就是拿它去请求的；我们自己走 `tplconfig` 换来的签名是另一条路。
     *
     * 结果按 surl 缓存 10 分钟：批量下载一个文件夹时，几百个文件不该每个都抓一次页面。
     */
    suspend fun sharePage(
        surl: String,
        sekey: String,
        cookie: String,
        force: Boolean = false,
    ): BaiduSharePage? =
        withContext(Dispatchers.IO) {
            val key = surl.trim()
            if (key.isBlank()) return@withContext null
            if (!force) {
                sharePageCache[key]?.let { entry ->
                    // 只解析出 shareid/uk、没有签名（多半是还没验证时抓到的「请输入提取码」页）时，
                    // 用很短的 TTL —— 补上验证后应当尽快重新抓到内容页那份签名
                    val ttl = if (entry.page.toSign() != null) SHARE_PAGE_TTL_MS else 60_000L
                    if (System.currentTimeMillis() - entry.at < ttl) {
                        return@withContext entry.page
                    }
                }
                if (shareMetaMisses.contains(key)) return@withContext null
            }
            val request = Request.Builder()
                .url(BaiduConstants.sharePageUrl(key))
                .header("Cookie", cookieWithSekey(cookie, sekey))
                // 分享页必须用客户端 UA：浏览器 UA 容易被百度反爬引导到「未登录」页，
                // 页面里没有 loginstate / yunData，解析必然失败（BaiduPCS-Go #534 同结论）
                .header("User-Agent", BaiduConstants.UA_NETDISK)
                .header("Referer", "https://pan.baidu.com/disk/home")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .get()
                .build()
            var html = ""
            val page = runCatching {
                client.newCall(request).execute().use { response ->
                    html = response.body?.string().orEmpty()
                    parseSharePage(html)
                }
            }.getOrNull()
            if (page != null) {
                sharePageCache[key] = PageEntry(page, System.currentTimeMillis())
                shareMetaMisses.remove(key)
                AppLogger.info(
                    TAG,
                    "分享页解析成功 shareid=${page.shareId} uk=${page.uk}（surl=$key）" +
                        " sign=${page.sign.isNotBlank()} ts=${page.timestamp.isNotBlank()} " +
                        "bdstoken=${page.bdstoken.isNotBlank()} 页面=${html.length}B",
                )
            } else {
                shareMetaMisses.add(key)
                // 页面里到底有没有 yunData，是判断「分享失效/需登录」和「正则没覆盖」的唯一线索
                AppLogger.warn(
                    TAG,
                    "分享页未解析出 shareid/uk（surl=$key）页面=${html.length}B " +
                        "yunData=${html.contains("yunData")} " +
                        "含shareid=${html.contains("shareid", ignoreCase = true)} " +
                        "head=${html.take(160).replace('\n', ' ')}",
                )
            }
            page
        }

    /** 分享页 HTML → [BaiduSharePage]；字段名大小写 / 引号形态都兜一遍（`SHAREID` 与 `shareid` 都出现过） */
    private fun parseSharePage(html: String): BaiduSharePage? {
        if (html.isBlank()) return null
        val shareId = firstCapture(
            html,
            """"shareid"\s*:\s*"?(\d{3,})""",
            """"share_id"\s*:\s*"?(\d{3,})""",
            """SHAREID[^\d]{0,6}(\d{3,})""",
            """SHARE_ID[^\d]{0,6}(\d{3,})""",
        )
        val uk = firstCapture(
            html,
            """"share_uk"\s*:\s*"?(\d{3,})""",
            """"uk"\s*:\s*"?(\d{3,})""",
            """SHARE_UK[^\d]{0,6}(\d{3,})""",
        )
        if (shareId.isNullOrBlank() || uk.isNullOrBlank()) return null
        // 签名只在「内容页」里有；拿到的是验证页（请输入提取码）时这两项为空，
        // 调用方据此回退到 tplconfig 换签名，不算失败。
        val scope = run {
            val at = html.indexOf("yunData", ignoreCase = true)
            if (at >= 0) html.substring(at, minOf(html.length, at + 24_000)) else html
        }
        fun pick(vararg patterns: String): String =
            firstCapture(scope, *patterns) ?: firstCapture(html, *patterns) ?: ""
        val sign = pick(""""sign"\s*:\s*"([^"]{8,})"""", """"SIGN"\s*:\s*"([^"]{8,})"""")
        val timestamp = pick(""""timestamp"\s*:\s*"?(\d{9,})""", """"TIMESTAMP"\s*:\s*"?(\d{9,})""")
        val bdstoken = pick(
            """"bdstoken"\s*:\s*"([0-9A-Za-z_\-]{4,})"""",
            """"BDSTOKEN"\s*:\s*"([0-9A-Za-z_\-]{4,})"""",
        )
        return BaiduSharePage(shareId, uk, sign, timestamp, bdstoken)
    }

    private fun firstCapture(text: String, vararg patterns: String): String? {
        for (p in patterns) {
            val value = Regex(p).find(text)?.groupValues?.getOrNull(1)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }


    /**
     * 验证提取码：POST /share/verify，返回 randsk（URL 编码形式，直接作为 sekey 使用）。
     * @return sekey；失败抛异常（携带服务端 errmsg）
     */
    suspend fun verifyShare(surl: String, pwd: String, cookie: String): String =
        withContext(Dispatchers.IO) {
            val body = "pwd=${urlEncode(pwd)}&vcode_str=&vcode="
            val request = Request.Builder()
                .url("https://pan.baidu.com/share/verify?surl=$surl")
                .header("Cookie", cookie)
                .header("User-Agent", BaiduConstants.UA_WEB)
                .header("Referer", BaiduConstants.sharePageUrl(surl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(body.toRequestBody(formMediaType))
                .build()
            val json = executeJson(request)
            checkErrno(json, "验证提取码失败")
            json.optString("randsk").takeIf { it.isNotBlank() }
                ?.let(::normalizeSekey)
                ?: throw BaiduApiException("未返回分享密钥")
        }

    /**
     * 分享密钥（randsk）规范化。
     *
     * `share/verify` 回的是 **URL 编码**形式（`abc%2Bdef%2F...`）。后续有两条相反的用法：
     *  - 作为 query 参数 `sekey=`：必须重新 URL 编码，否则 `+` 会被解成空格；
     *  - 作为 Cookie `BDCLND=`：**不能**是编码形式，浏览器里是 `BDCLND=decodeURIComponent(randsk)`。
     *
     * 以前两者都直接用原始字符串：query 里恰好被浏览器/OkHttp 当成已编码能跑通（所以列目录正常），
     * 但 Cookie 里的 BDCLND 变成 `%2B...` 这种脏值，转存就判定分享未验证 → errno=2。
     * 这里统一规范化成「解码后的原文」，调用方各自决定是否再编码。
     */
    private fun normalizeSekey(raw: String): String {
        if (!raw.contains('%')) return raw
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    /**
 * 列出分享文件：GET xpan/share?method=list。
 * 修复（文档《百度网盘解析问题修复》）：
 *  - 顶层 root=1，子目录 root=0（root=1 下百度忽略 dir → 子文件夹进不去）；
 *  - 子目录（root=0）必须携带 BDCLND cookie（= verify 返回的 randsk），否则 errno=2；
 *  - sekey 为空（公共分享）时省略 &sekey= 参数；
 *  - 无 sekey 却 errno!=0 → 实为加密分享，抛"该分享需要提取码"。
 * @param dir 分享内目录路径，根目录传 "/"（子目录传 "/folder"）
 * @return 文件列表 + share_id/uk（转存需要）
 */
suspend fun listShare(surl: String, sekey: String, dir: String, cookie: String, page: Int = 1): BaiduShareList =
    withContext(Dispatchers.IO) {
        val isRoot = dir.isBlank() || dir == "/"
        val root = if (isRoot) "1" else "0"
        // sekey 已规范化为原文，塞进 query 必须重新编码（randsk 里常见 + / = 三个字符）
        val sekeyPart = if (sekey.isNotBlank()) "&sekey=${urlEncode(sekey)}" else ""
        val url = "https://pan.baidu.com/rest/2.0/xpan/share?method=list" +
            "&shorturl=$surl&page=$page&num=100&root=$root&dir=" +
            URLEncoder.encode(if (dir.isBlank()) "/" else dir, "UTF-8") +
            sekeyPart
        // 子目录(root=0)必须携带 BDCLND（= verify 的 randsk），否则 errno=2；顶层(root=1)无需。
        // 凭证 Cookie 里可能残留「其它分享」的旧 BDCLND，必须以本分享的 sekey 为准，
        // 否则旧值会顶掉新值导致后续转存 errno=2。sekey 为空（公共分享）时保持原样，
        // 用户在浏览器里验证过的 BDCLND 仍可用。
        val authCookie = cookieWithSekey(cookie, sekey)
        val request = Request.Builder()
            .url(url)
            .header("Cookie", authCookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Referer", BaiduConstants.sharePageUrl(surl))
            .get()
            .build()
        val json = executeJson(request)
        val errno = json.optInt("errno")
        if (errno != 0) {
            // 无 sekey 却失败 → 多数情况是加密分享，提示用户索取提取码。
            // 但 errno=-6 是「未登录/身份验证失败」（游客模式下常见），此时光提示提取码会让人白找密码，单独区分。
            if (sekey.isBlank()) {
                throw BaiduApiException(
                    if (errno == -6) "该分享需要提取码，或需要登录百度网盘（errno=$errno）"
                    else "该分享需要提取码（errno=$errno）"
                )
            }
            checkErrno(json, "获取分享文件列表失败")
        }
        val array = json.optJSONArray("list") ?: JSONArray()
        val files = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val isdir = item.optString("isdir") == "1"
                val path = item.optString("path")
                    add(
                        ShareFile(
                            // 目录用 path 作 fid（导航传参），文件用 fs_id（转存传参）
                            fid = if (isdir) path else item.optString("fs_id"),
                            fname = item.optString("server_filename"),
                            fsize = item.optLong("size"),
                            isdir = isdir,
                            pdirFid = path,
                            fidToken = "",
                            modifyTime = item.optString("server_mtime")
                        )
                    )
                }
            }
            val sid = firstJsonString(json, "share_id", "shareid", "shareId")
            val shareUk = firstJsonString(json, "uk", "share_uk", "from")
            // 顶层列表就把 shareid/uk 缺不缺记下来：缺了就必须走分享页解析，
            // 否则转存一定拿到 errno=2（且提示是误导性的「文件已存在」）
            if (isRoot) {
                AppLogger.info(
                    TAG,
                    "分享列表 root=$root dir=$dir 文件数=${files.size} " +
                        "shareid=${sid.ifBlank { "(空)" }} uk=${shareUk.ifBlank { "(空)" }}",
                )
            }
            BaiduShareList(
                title = json.optString("title"),
                // 字段名各版本不一（share_id / shareid / shareId），子目录列表还可能整段缺失；
                // 缺失时返回空串，由调用方回退到 [shareMeta]（分享页解析）
                shareId = sid,
                uk = shareUk,
                files = files
            )
        }

    /**
     * 按候选键名依次取字符串值（数字也转成字符串）。
     * 兼顾「字段缺失」与「显式返回空串」，两者都当作未取到。
     */
    private fun firstJsonString(json: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = json.optString(key).trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    // ---------- 个人网盘 / 转存 ----------

    /** 创建目录（个人网盘根目录下），返回是否成功 */
    suspend fun createDir(path: String, cookie: String): Boolean = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie) ?: return@withContext false
        // 官方新建文件夹用的是 api/create?a=commit（对齐抓包）：
        // filemanager?opera=mkdir 在纯 Cookie 认证下恒 errno=2（接口校验路径不同）。
        // 统一使用 UA_WEB + Referer pan.baidu.com/disk/main + body 完整参数
        val body = "path=${urlEncode(path)}&isdir=1&size&block_list=%5B%5D&method=post&dataType=json"
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/create?a=commit&channel=chunlei&web=1" +
                "&app_id=${BaiduConstants.APP_ID}&clienttype=0&bdstoken=$bdstoken")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Referer", "https://pan.baidu.com/disk/main")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .post(body.toRequestBody(formMediaType))
            .build()
        runCatching {
            val json = executeJson(request)
            json.optInt("errno") == 0
        }.getOrDefault(false)
    }

    /**
     * 列出个人网盘目录（检查临时转存目录是否存在 / 找同名残留），返回子项 path 集合。
     *
     * 与 [listCloudFiles] 对齐：必须带 bdstoken + channel=chunlei + showempty=0，
     * 缺 bdstoken 时部分账号直接返回 errno=-6，被静默当成「空目录」——
     * 那会让「复用已有转存」永远失效，残留也删不掉（历史上就是这样踩的坑）。
     * errno=-6（token 失效）→ 刷新 bdstoken 重试一次；其它 errno 记日志后返回空。
     */
    suspend fun listDir(dir: String, cookie: String): List<String> = withContext(Dispatchers.IO) {
        suspend fun once(token: String?): List<String>? {
            val url = "https://pan.baidu.com/api/list?clienttype=0&app_id=${BaiduConstants.APP_ID}" +
                "&channel=chunlei&web=1&showempty=0&order=time&desc=1&num=100&page=1" +
                (token?.let { "&bdstoken=$it" } ?: "") +
                "&dir=" + URLEncoder.encode(dir, "UTF-8")
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", BaiduConstants.UA_WEB)
                .header("Referer", "https://pan.baidu.com/disk/main")
                .get()
                .build()
            val json = runCatching { executeJson(request) }.getOrNull() ?: return null
            val errno = json.optInt("errno", 0)
            if (errno != 0) {
                AppLogger.warn(TAG, "列目录失败 dir=$dir errno=$errno ${json.optString("err_msg")}")
                return if (errno == -6) null else emptyList()
            }
            val array = json.optJSONArray("list") ?: return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { add(it.optString("path")) }
                }
            }
        }
        once(getBdstoken(cookie))?.let { return@withContext it }
        once(refreshBdstoken(cookie)) ?: emptyList()
    }

    /**
     * 转存分享文件到指定目录（同步返回结果）。
     *
     * 这个接口的 err_msg 不可信（errno=2 会被写成「文件已存在」，实际多表示
     * **目标目录不存在 / 参数不被接受**），而且各代前端发出去的参数形状并不一致。
     * 因此这里按「现行客户端 → 旧写法」依次尝试，只在 errno=2（参数/目录类）时换形状重试，
     * 容量不足、文件数超限、提取码错误等直接抛出，不做无意义的重复请求。
     *
     * @return 转存后的新 fs_id + 新路径（路径为空表示服务端只回了成功没回路径，由调用方列目录补）
     */
    suspend fun transfer(
        shareId: String,
        uk: String,
        sekey: String,
        fsId: String,
        toDir: String,
        cookie: String,
        bdclndOverride: String? = null,
        surl: String? = null,
    ): BaiduTransferResult {
        // 只有 BDUSS、没有 STOKEN 的「半张 Cookie」是本模块最坑的一种登录态：
        // 浏览分享目录、取 bdstoken、列目录全都正常，唯独 share/transfer 恒回 errno=2
        // （err_msg 还写成「文件已存在」）。这种状态下换任何参数形状都不可能成功，
        // 直接给出可执行的提示，别让用户看着它试九遍。
        if (!BaiduConstants.hasStoken(cookie)) {
            AppLogger.warn(
                TAG,
                "转存中止：登录态缺少 STOKEN（cookieKeys=${BaiduConstants.cookieKeys(cookie)}）",
            )
            throw BaiduApiException(
                "百度网盘登录态不完整（缺少 STOKEN），请在「网盘」页重新登录一次再试"
            )
        }
        // 登录态只打键名（不含值），排查「转存恒 errno=2」时可据此确认 BDUSS/STOKEN/BDCLND 是否齐全
        if (loggedCookieOwner != cookie.hashCode()) {
            loggedCookieOwner = cookie.hashCode()
            AppLogger.info(
                TAG,
                "登录态 cookieKeys=${BaiduConstants.cookieKeys(cookie)}",
            )
        }
        var lastError: BaiduApiException? = null
        // 从一个可用的形状开始试：成功过就记住它，批量下载时不至于每个文件都从头试三种
        val start = preferredShapeIndex.coerceIn(0, TRANSFER_SHAPES.lastIndex)
        for (offset in TRANSFER_SHAPES.indices) {
            val index = (start + offset) % TRANSFER_SHAPES.size
            val shape = TRANSFER_SHAPES[index]
            try {
                val result = transferOnce(
                    shareId, uk, sekey, fsId, toDir, cookie, bdclndOverride, shape, surl,
                )
                preferredShapeIndex = index
                if (index != start) {
                    AppLogger.info(TAG, "转存改用参数形状 ${shape.code} 成功，后续文件沿用")
                }
                return result
            } catch (e: BaiduApiException) {
                lastError = e
                // errno=-9「提取码错误」只由开放平台那一档（xpan）吐出来，公开分享也可能误报：
                // 它不该打断别的形状，记下后继续；真全是 -9 时最后抛出的就是它，文案本身是对的。
                if (e.errno == ERRNO_VERIFY_ERROR) {
                    AppLogger.info(TAG, "转存形状 ${shape.code} 返回 errno=-9（提取码/验证态），继续换形状")
                    continue
                }
                // 只有「参数/目录」类错误值得换形状；其它（容量、次数）立刻上抛
                if (e.errno != ERRNO_BAD_PARAM) throw e
                AppLogger.info(TAG, "转存形状 ${shape.code} 返回 errno=2，换下一种参数形状重试")
            }
        }
        throw lastError ?: BaiduApiException("转存失败")
    }

    /**
     * 单次转存请求。
     *
     * @param shape 参数形状，见 [TRANSFER_SHAPES]
     */
    private suspend fun transferOnce(
        shareId: String,
        uk: String,
        sekey: String,
        fsId: String,
        toDir: String,
        cookie: String,
        bdclndOverride: String?,
        shape: TransferShape,
        surl: String? = null,
    ): BaiduTransferResult = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie)
            ?: throw BaiduApiException("获取 bdstoken 失败，请重新登录")
        // shareid/from 为空时百度不会提示「参数缺失」，只回 errno=2 + show_msg「文件已存在」，
        // 让人一路误判成重名/目录不存在。这里直接拦下来，把真正的原因说清楚。
        if (shareId.isBlank() || uk.isBlank()) {
            throw BaiduApiException(
                "转存失败：缺少分享参数（shareid=${shareId.ifBlank { "空" }}，" +
                    "from=${uk.ifBlank { "空" }}），请重新解析该分享"
            )
        }
        // fsidlist 只接受数字 fs_id；拿到路径说明这是「目录行」当文件传了，
        // 百度同样只回 errno=2，不说明原因
        if (fsId.isBlank() || !fsId.all { it.isDigit() }) {
            throw BaiduApiException("转存失败：文件标识无效（$fsId），目录请用「下载文件夹」，或刷新列表后重试")
        }
        // fsidlist 是 JSON 数组。现行客户端（BaiduPCS-Go / 批量转存工具）发的是 **不带引号的数字**：
        // `fsidlist=[12345]`。旧 YunX 写法带引号（`["12345"]`），部分账号会被判成参数错误 → errno=2。
        val fsidList = if (shape.quoteFsid) "[\"$fsId\"]" else "[$fsId]"
        val url = buildString {
            // 网页版 share/transfer 与开放平台 rest/2.0/xpan/share?method=transfer 是同一件事的两个入口。
            // 前者对部分登录态恒回 errno=2（哪怕参数齐全），后者走的是另一套校验，
            // 而且同为 rest/2.0 的 method=list 在本应用里一直工作正常，所以留作最后一档。
            if (shape.xpanApi) {
                append("https://pan.baidu.com/rest/2.0/xpan/share?method=transfer&shareid=").append(shareId)
            } else {
                append("https://pan.baidu.com/share/transfer?shareid=").append(shareId)
            }
            append("&from=").append(uk)
            append("&channel=").append(if (shape.chunked) "chunked" else "chunlei")
            append("&web=1&clienttype=0&app_id=").append(BaiduConstants.APP_ID)
            if (!shape.noBdstoken) append("&bdstoken=").append(bdstoken)
            // sekey 是规范化后的原文，进 query 必须重新编码（+ / = 三个字符最常见）
            if (shape.sendSekey && sekey.isNotBlank()) append("&sekey=").append(urlEncode(sekey))
            if (shape.ondupNewCopy) append("&ondup=newcopy")
            // 客户端（BaiduPCS-Go）走异步转存，同步接口对部分账号只回 errno=2
            if (shape.chunked) append("&async=1")
        }
        val body = "fsidlist=${urlEncode(fsidList)}&path=${urlEncode(toDir)}"
        // verify 响应会 Set-Cookie: BDCLND=<randsk>，transfer 必须携带（分享验证标识）；
        // 残留「其它分享」的旧值会顶掉新值。BDCLND 的值不能是 URL 编码形式（见 [normalizeSekey]）。
        val authCookie = if (shape.injectBdclnd) {
            cookieWithSekey(cookie, bdclndOverride ?: sekey)
        } else {
            // 保持用户原始 Cookie：浏览器自己写过的 BDCLND 有可能才是对的
            cookie
        }
        // 带真实分享页 Referer 更接近浏览器行为；surl 未知时退回站点根
        val sharePageReferer = surl?.takeIf { it.isNotBlank() }
            ?.let { BaiduConstants.sharePageUrl(it) }
            ?: "https://pan.baidu.com/s/"
        val request = Request.Builder()
            .url(url)
            .header("Cookie", authCookie)
            .header("User-Agent", if (shape.netdiskUa) BaiduConstants.UA_NETDISK else BaiduConstants.UA_WEB)
            .header("Origin", "https://pan.baidu.com")
            .header("Referer", if (shape.shareReferer) sharePageReferer else "https://pan.baidu.com/s/")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .post(body.toRequestBody(formMediaType))
            .build()
        val json = executeJson(request)
        val errno = json.optInt("errno", 0)
        if (errno != 0) {
            AppLogger.warn(
                TAG,
                "转存失败 shape=${shape.code} to=$toDir fsid=$fsId errno=$errno " +
                    "shareid=$shareId from=$uk " +
                    "msg=${json.optString("err_msg").ifBlank { json.optString("show_msg") }} " +
                    "innerErrno=${json.optJSONArray("info")?.optJSONObject(0)?.optInt("errno")} " +
                    "sekey=${sekey.isNotBlank()} sekeyPct=${sekey.contains('%')} " +
                    "bdclnd=${authCookie.contains("BDCLND=")} ua=${if (shape.netdiskUa) "netdisk" else "web"} " +
                    "referer=${if (shape.shareReferer) "share" else "site"} " +
                    "tokenHash=${bdstoken.hashCode()} requestId=${json.optString("request_id")} " +
                    "raw=${json.toString().take(240)}",
            )
            throw transferError(json, errno)
        }
        parseTransferResult(json)
    }

    /**
     * 把转存接口的错误码翻成用户能看懂的话。
     *
     * 依据公开错误码表：2=目标目录不存在、4=目标目录已有同名、12=转存文件数超限
     * （此时 info[0].errno=-30 才是「同名」）、-10/20=容量不足、-6=登录态失效、
     * -62=访问次数过多、-9/-12=提取码错误。
     */
    private fun transferError(json: JSONObject, errno: Int): BaiduApiException {
        val raw = json.optString("err_msg").ifBlank { json.optString("show_msg") }
        val inner = json.optJSONArray("info")?.optJSONObject(0)
        val innerErrno = inner?.optInt("errno") ?: 0
        val innerPath = inner?.optString("path").orEmpty()
        val msg = when {
            // 文案兼顾两种真实含义：目标目录不存在、或分享参数（shareid/from/fsidlist）不被接受。
            // 百度给这两种情况的响应完全一样，不能只写「目录不存在」把人带偏。
            errno == ERRNO_BAD_PARAM ->
                "转存失败：百度拒绝了本次请求（参数形状 / 目标目录 / 分享验证三者之一，errno=2）"
            errno == 4 -> "转存失败：目标目录已有同名文件（errno=4）"
            errno == 12 && innerErrno == -30 ->
                "转存失败：目标目录已有同名文件 ${innerPath.substringAfterLast('/')}（errno=12/-30）"
            errno == 12 -> {
                val nums = json.optLong("target_file_nums", -1)
                val limit = json.optLong("target_file_nums_limit", -1)
                if (nums > 0 && limit > 0) "转存失败：单次转存 $nums 个文件，超过账号上限 $limit"
                else "转存失败：转存文件数超过账号上限（errno=12）"
            }
            errno == -10 || errno == 20 -> "转存失败：网盘容量不足（errno=$errno）"
            errno == -6 -> "转存失败：登录状态失效，请重新登录百度网盘（errno=-6）"
            errno == -62 -> "转存失败：该链接访问次数过多，请稍后再试（errno=-62）"
            errno == -9 || errno == -12 -> "提取码错误（errno=$errno）"
            else -> "${raw.ifBlank { "转存失败" }}（errno=$errno）"
        }
        return BaiduApiException(msg, errno)
    }

    /**
     * 解析转存成功响应。服务端有两种格式，都要认：
     *  - `extra.list[0]`：`to_fs_id` / `to`（新版 xpan 风格）
     *  - `info[0]`：`fsid` / `path`（网页/客户端风格）
     * 两者都没给路径时返回空路径，交给调用方列目录补全。
     */
    private fun parseTransferResult(json: JSONObject): BaiduTransferResult {
        val first = json.optJSONObject("extra")?.optJSONArray("list")?.optJSONObject(0)
        var fsId = first?.optString("to_fs_id").orEmpty()
        var path = first?.optString("to").orEmpty()
        if (path.isBlank()) {
            val info = json.optJSONArray("info")?.optJSONObject(0)
            if (info != null) {
                if (fsId.isBlank()) fsId = info.optString("fsid")
                if (path.isBlank()) path = info.optString("path")
            }
        }
        return BaiduTransferResult(fsId = fsId, path = path)
    }

    /**
     * 转存请求的参数形状。按顺序尝试，靠前的更贴近现行客户端。
     *
     * 这些维度不是猜着玩的，每一条都对应一类常见的 errno=2：
     *  - `injectBdclnd`：BDCLND 与分享不匹配（编码脏值 / 上一次别的分享残留）会让服务端判「未验证」；
     *  - `sendSekey`：一部分账号只认 query 里的 sekey，另一部分只认 Cookie；
     *  - `netdiskUa`：`share/transfer` 对 web UA 与客户端 UA 的白名单校验不同；
     *  - `shareReferer`：带真实分享页 Referer 能过普通的防盗链校验；
     *  - `ondupNewCopy`：重名时另存一份，绕开 errno=4 / 12(-30)。
     *
     * @property quoteFsid `fsidlist` 里的 id 是否加引号（旧写法）
     * @property sendSekey 是否在 query 里带 sekey
     * @property ondupNewCopy 是否带 ondup=newcopy
     * @property injectBdclnd 是否把分享密钥写进 BDCLND Cookie
     * @property netdiskUa 是否改用客户端 UA（netdisk）
     * @property shareReferer 是否带真实分享页 Referer
     * @property noBdstoken 是否不带 query 里的 bdstoken（个别账号只认 Cookie 里的登录态）
     * @property chunked 是否用客户端写法 `channel=chunked&async=1`（BaiduPCS-Go 即此形状）
     * @property xpanApi 是否改走开放平台入口 `rest/2.0/xpan/share?method=transfer`
     */
    private data class TransferShape(
        val code: String,
        val quoteFsid: Boolean = false,
        val sendSekey: Boolean = false,
        val ondupNewCopy: Boolean = false,
        val injectBdclnd: Boolean = true,
        val netdiskUa: Boolean = false,
        val shareReferer: Boolean = false,
        val noBdstoken: Boolean = false,
        val chunked: Boolean = false,
        val xpanApi: Boolean = false,
    )

    private companion object {
        /** errno=2：百度用于「目标目录不存在 / 参数不被接受」 */
        const val ERRNO_BAD_PARAM = 2

        /** errno=-9：提取码 / 分享验证态不被接受 */
        const val ERRNO_VERIFY_ERROR = -9

        /** 分享页解析结果的有效期：签名与页面同源，10 分钟内复用不必重抓 */
        const val SHARE_PAGE_TTL_MS = 10 * 60_000L

        /** 免转存整体失败后，同一分享在这段时间内不再重试（批量下载别为每个文件白跑一轮） */
        const val DIRECT_DEAD_MS = 3 * 60_000L

        /**
         * 参数形状阶梯。顺序 = 代价从低到高，全部只在 errno=2（参数/目录类）时继续往下试：
         * 1. 现行 web 写法：不带 sekey，靠 BDCLND 证明已通过提取码验证；
         * 2. 带 sekey + 真实分享页 Referer（最贴近浏览器抓包）；
         * 3. 只带 sekey、不动 Cookie（Cookie 里的 BDCLND 若被污染，这一步能救）；
         * 4. 维持用户原始 Cookie（BDCLND 由浏览器自己写过，可能有正确值）；
         * 5. 客户端 UA：部分账号的 share/transfer 只对 netdisk UA 放行；
         * 6. 不带 bdstoken（个别账号只认 Cookie 里的登录态）；
         * 7. 重名另存（ondup=newcopy），绕开同名冲突类错误；
         * 8. 客户端写法 `channel=chunked&async=1`（异步转存，落盘要回查目录确认）；
         * 9. 旧 YunX 写法（fsidlist 带引号）；
         * 10. 开放平台入口 `rest/2.0/xpan/share?method=transfer`（换一套校验，网页入口全拒时的最后手段）。
         */
        val TRANSFER_SHAPES = listOf(
            TransferShape(code = "pcs"),
            TransferShape(code = "pcs+sekey", sendSekey = true, shareReferer = true),
            TransferShape(code = "sekey-only", sendSekey = true, injectBdclnd = false, shareReferer = true),
            TransferShape(code = "raw-cookie", injectBdclnd = false, shareReferer = true),
            TransferShape(code = "netdisk-ua", sendSekey = true, netdiskUa = true, shareReferer = true),
            TransferShape(code = "no-token", sendSekey = true, noBdstoken = true, shareReferer = true),
            TransferShape(code = "ondup", sendSekey = true, ondupNewCopy = true, shareReferer = true),
            TransferShape(
                code = "chunked-async",
                sendSekey = true,
                ondupNewCopy = true,
                shareReferer = true,
                chunked = true,
            ),
            TransferShape(code = "quoted", quoteFsid = true, sendSekey = true, ondupNewCopy = true),
            TransferShape(code = "xpan", sendSekey = true, shareReferer = true, xpanApi = true),
        )

        /**
         * `/api/sharedownload` 的参数形状阶梯。
         *
         * 公开实现里存在两套并行的写法，差别集中在三处，这套阶梯就是按它们排的：
         *  - **id 列表叫什么**：现行网页是 `fsidlist`，pan-baidu-download / panwget 等
         *    长期可用的实现用的是 `fid_list`（且不传 `path`）；
         *  - **验证密钥放哪**：带提取码的分享除了 Cookie 里的 `BDCLND`，还要在 body 的
         *    `extra={"sekey":"<randsk 原文>"}` 里再给一份（pan-baidu-download 即如此），
         *    或者塞进 query 的 `sekey`；
         *  - **要不要 `bdstoken`**：有的账号只认分享页自己下发的那份 token。
         *
         * 顺序 = 命中概率从高到低；成功一次就记下下标（[preferredDirectShape]），
         * 之后同一账号的文件直接复用，不再走阶梯。
         */
        val DIRECT_SHAPES = listOf(
            DirectShape("fidlist-extra", bodyKey = "fid_list", extraSekey = true),
            DirectShape("fidlist", bodyKey = "fid_list"),
            DirectShape("fidlist-path-extra", bodyKey = "fid_list", extraSekey = true, withPath = true),
            DirectShape("fsidlist-extra-path", bodyKey = "fsidlist", extraSekey = true, withPath = true),
            DirectShape("fsidlist-path", bodyKey = "fsidlist", withPath = true),
            DirectShape("fsidlist-query-sekey", bodyKey = "fsidlist", querySekey = true, withPath = true),
            DirectShape("fsidlist-extra-no-token", bodyKey = "fsidlist", extraSekey = true, withPath = true, usePageToken = false),
            DirectShape("fidlist-extra-encoded", bodyKey = "fid_list", extraSekey = true, encodedBdclnd = true),
        )
    }

    /**
     * 免转存直链的参数形状（只对 `/api/sharedownload` 有意义）。
     *
     * @property bodyKey body 里 id 列表的字段名（`fid_list` / `fsidlist`）
     * @property extraSekey 是否在 body 带 `extra={"sekey":..}`（带提取码的分享需要）
     * @property querySekey 是否把 sekey 放 query（`extra` 的替代写法）
     * @property withPath 是否带 `path`（分享内目录；根目录对部分文件可省）
     * @property usePageToken query 是否带分享页下发的 `bdstoken`
     * @property encodedBdclnd BDCLND 是否用 URL 编码形态
     */
    private data class DirectShape(
        val code: String,
        val bodyKey: String,
        val extraSekey: Boolean = false,
        val querySekey: Boolean = false,
        val withPath: Boolean = false,
        val usePageToken: Boolean = true,
        val encodedBdclnd: Boolean = false,
    )

    /**
     * 访问分享页并抓 BDCLND（分享验证 Cookie）。
     *
     * 无提取码的分享不会走 `share/verify`，拿不到 randsk；此时服务端对「子目录里的文件」
     * 仍会校验 BDCLND，缺失就回 errno=2。分享页响应头会下发 `Set-Cookie: BDCLND=...`，
     * 这里尽力取一次（取不到返回 null，不影响主流程）。
     */
    @Suppress("UNUSED") // 分享页 BDCLND 兜底抓取入口，当前主流程用不到，保留备用
    suspend fun shareBdclnd(surl: String, cookie: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BaiduConstants.sharePageUrl(surl))
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_NETDISK)
            .header("Referer", "https://pan.baidu.com/disk/home")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                response.headers("Set-Cookie").forEach { header ->
                    val value = Regex("BDCLND=([^;]+)").find(header)?.groupValues?.getOrNull(1)
                    if (!value.isNullOrBlank()) {
                        return@use value
                    }
                }
                // 少数情况下 Set-Cookie 被重定向吃掉，正文里也会带一份
                val body = response.body?.string().orEmpty()
                Regex("""BDCLND["'\s:=]+([A-Za-z0-9%_\-]{8,})""").find(body)?.groupValues?.getOrNull(1)
            }
        }.getOrNull()
    }

    // ---------- 免转存直链（网页「下载」按钮的路径） ----------

    /**
     * 取分享直链签名：`GET /share/tplconfig?surl=<surl>&fields=sign,timestamp`。
     *
     * 两个必须知道的细节（均实测）：
     *  1. `surl` **要带开头的 `1`**。不带 1 时接口回 `{"errno":2,"show_msg":"啊哦，链接出错了"}`，
     *     和「分享已失效」的表现一模一样，很容易误判成提取码过期；带上 1 立刻回 errno=0。
     *     链接解析器按 HTTP 接口惯例去掉了 1，所以这里要补回来。
     *  2. `sign` 藏在 **`data` 对象里**：`{"errno":0,"data":{"sign":"..","timestamp":..}}`，
     *     顶层没有 sign。以前按顶层取，就算请求通了也永远判成「没拿到签名」。
     *
     * 验证态靠 Cookie 里的 `BDCLND` 传递（不带也能取到签名，但后续 `/api/sharedownload` 会要），
     * 因此两种形态都试一次，代价极小。
     */
    suspend fun shareSign(surl: String, sekey: String, cookie: String): BaiduShareSign? =
        withContext(Dispatchers.IO) {
            val cacheKey = "${surl.trim()}|${cookie.hashCode()}"
            shareSignCache[cacheKey]?.let { return@withContext it }
            suspend fun attempt(target: String, encodedBdclnd: Boolean): BaiduShareSign? {
                val url = "https://pan.baidu.com/share/tplconfig?surl=${urlEncode(target)}" +
                    "&fields=sign,timestamp&channel=chunlei&web=1&clienttype=0" +
                    "&app_id=${BaiduConstants.APP_ID}"
                val authCookie = cookieWithSekey(cookie, sekey, encoded = encodedBdclnd)
                val request = Request.Builder()
                    .url(url)
                    .header("Cookie", authCookie)
                    .header("User-Agent", BaiduConstants.UA_WEB)
                    .header("Referer", BaiduConstants.sharePageUrl(target))
                    .get()
                    .build()
                return runCatching {
                    val json = executeJson(request)
                    val data = json.optJSONObject("data")
                    val sign = data?.optString("sign").orEmpty()
                        .ifBlank { json.optString("sign") }
                    val timestamp = (data?.opt("timestamp") ?: json.opt("timestamp"))
                        ?.toString()?.trim().orEmpty()
                    if (json.optInt("errno") != 0 || sign.isBlank() || timestamp.isBlank()) {
                        AppLogger.warn(
                            TAG,
                            "分享签名获取失败 surl=$target errno=${json.optInt("errno")} " +
                                "msg=${json.optString("errmsg").ifBlank { json.optString("show_msg") }} " +
                                "bdclnd=${authCookie.contains("BDCLND=")} encoded=$encodedBdclnd " +
                                "raw=${json.toString().take(200)}",
                        )
                        null
                    } else {
                        BaiduShareSign(sign, timestamp)
                    }
                }.getOrNull()
            }
            // 带 1 的形态优先（实测可用）；不带 1 的留作兜底，防上游哪天改回来
            val targets = listOf(BaiduConstants.apiSurl(surl), surl.trim()).distinct()
            for (target in targets) {
                for (encoded in listOf(false, true)) {
                    attempt(target, encoded)?.let {
                        shareSignCache[cacheKey] = it
                        return@withContext it
                    }
                }
            }
            null
        }

    /**
     * 免转存直链：`POST /api/sharedownload`（网页「下载」按钮的同一条链路）。
     *
     * 与转存后 `locatedownload` 相比，这条路**不往用户网盘写任何东西**，
     * 也不会因为「目标目录不存在 / 转存被拒（errno=2）」而失败。
     *
     * 请求要素（按公开实现交叉核对）：
     *  - query：`sign` / `timestamp`（优先用分享页里那份）+ `channel=chunlei&web=1&clienttype=0`
     *    + `app_id` + 分享页下发的 `bdstoken`；
     *  - Cookie：带 `BDCLND`（= 提取码验证得到的 randsk），否则服务端判「本次分享未验证」；
     *  - body：`encrypt=0&product=share&uk=<share_uk>&primaryid=<shareid>` + id 列表；
     *    **带提取码的分享还必须在 body 里给 `extra={"sekey":"<randsk 原文>"}`** ——
     *    少了这一项，服务端只回 `{"errno":2,"show_msg":"请求失败"}`（线上就是卡在这里）。
     *
     * 两种 id 列表写法 / 密钥位置的组合见 [DIRECT_SHAPES]；命中一次就记住形状，
     * 同一账号后续文件直接一步到位。
     *
     * @param signs 候选签名，按优先级排列（分享页 → tplconfig）
     * @param path 文件所在目录（`/` 或 `/子目录`）
     * @param referer 分享页地址（网页端就是从分享页发起的，防盗链认它）
     */
    suspend fun shareDirectDlink(
        shareId: String,
        shareUk: String,
        fsId: String,
        path: String,
        signs: List<BaiduShareSign>,
        sekey: String,
        cookie: String,
        bdstoken: String = "",
        referer: String = "https://pan.baidu.com/",
    ): String? = withContext(Dispatchers.IO) {
        if (signs.isEmpty()) return@withContext null
        val dir = path.takeIf { it.isNotBlank() } ?: "/"
        val now = System.currentTimeMillis()
        directDeadShares[shareId]?.let { at ->
            if (now - at < DIRECT_DEAD_MS) {
                AppLogger.info(TAG, "免转存直链近期整体失败过，本次跳过（shareid=$shareId）")
                return@withContext null
            }
        }
        val fidJson = "[$fsId]"
        val extraJson = if (sekey.isBlank()) {
            ""
        } else {
            """{"sekey":"${sekey.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""
        }

        suspend fun attempt(shape: DirectShape, sign: BaiduShareSign): String? {
            val url = buildString {
                append("https://pan.baidu.com/api/sharedownload")
                append("?sign=").append(urlEncode(sign.sign))
                append("&timestamp=").append(urlEncode(sign.timestamp))
                append("&channel=chunlei&web=1&clienttype=0&app_id=").append(BaiduConstants.APP_ID)
                if (shape.usePageToken && bdstoken.isNotBlank()) {
                    append("&bdstoken=").append(urlEncode(bdstoken))
                }
                if (shape.querySekey && sekey.isNotBlank()) {
                    append("&sekey=").append(urlEncode(sekey))
                }
            }
            val body = buildString {
                append("encrypt=0&product=share")
                append("&uk=").append(shareUk)
                append("&primaryid=").append(shareId)
                append("&").append(shape.bodyKey).append("=").append(urlEncode(fidJson))
                if (shape.withPath) append("&path=").append(urlEncode(dir))
                if (shape.extraSekey && extraJson.isNotBlank()) {
                    append("&extra=").append(urlEncode(extraJson))
                }
            }
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookieWithSekey(cookie, sekey, encoded = shape.encodedBdclnd))
                .header("User-Agent", BaiduConstants.UA_WEB)
                .header("Referer", referer)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .post(body.toRequestBody(formMediaType))
                .build()
            return runCatching {
                val json = executeJson(request)
                val errno = json.optInt("errno")
                val dlink = extractDlink(json)
                if (errno != 0 || dlink.isBlank()) {
                    AppLogger.warn(
                        TAG,
                        "免转存直链失败 fsid=$fsId shape=${shape.code} errno=$errno " +
                            "msg=${json.optString("errmsg").ifBlank { json.optString("show_msg") }} " +
                            "token=${bdstoken.isNotBlank()} extra=${extraJson.isNotBlank()} " +
                            "raw=${json.toString().take(240)}",
                    )
                    null
                } else {
                    dlink
                }
            }.getOrNull()
        }

        // 上次成功的形状先试，其余按默认顺序补；形状在外层，签名在内层——
        // 参数形状（尤其 extra.sekey）比签名来源更可能不对，先把每个形状的两个签名试完，
        // 再换下一个形状，命中更早。
        val order = buildList {
            add(preferredDirectShape)
            addAll(DIRECT_SHAPES.indices) // 展开为多个下标；用 addAll 明确意图，避免 `+` 遍历实参的歧义警告
        }
            .distinct()
            .mapNotNull { DIRECT_SHAPES.getOrNull(it) }
        for (shape in order) {
            for (sign in signs) {
                attempt(shape, sign)?.let { dlink ->
                    preferredDirectShape = DIRECT_SHAPES.indexOf(shape)
                    directDeadShares.remove(shareId)
                    AppLogger.info(
                        TAG,
                        "免转存直链成功 fsid=$fsId shape=${shape.code} " +
                            "host=${dlink.substringAfter("//").substringBefore("/")}",
                    )
                    return@withContext dlink
                }
            }
        }
        directDeadShares[shareId] = now
        null
    }

    /** 直链响应里的地址：`list[0].dlink` / `dlink[0].dlink` / 顶层 `dlink` 三种形态都出现过 */
    private fun extractDlink(json: JSONObject): String {
        fun fromObject(obj: JSONObject?): String {
            if (obj == null) return ""
            return obj.optJSONArray("list")?.optJSONObject(0)?.optString("dlink").orEmpty()
                .ifBlank { obj.optJSONArray("dlink")?.optJSONObject(0)?.optString("dlink").orEmpty() }
                .ifBlank { obj.optString("dlink") }
        }
        return fromObject(json).ifBlank { fromObject(json.optJSONObject("data")) }
    }

    // ---------- 下载直链 ----------
    /**
     * 获取高速下载直链（官方 locatedownload 接口，对齐 MoePal 抓包）：
     * POST d.pcs.baidu.com/rest/2.0/pcs/file?method=locatedownload&path=<转存后完整路径>。
     * 响应 urls[] 按 rank 返回多个候选 CDN 直链（自带 sign/expires，无需计算）：
     *  - rank1 常为 d2-ant.baidu.com（encrypt=1 加密通道，内容需 AES-CTR 解密，且部分网络 TLS 握手失败）
     *  - rank2+ 为 appallNN.baidupcs.com（encrypt=0 明文通道，可直接 Range 下载）
     * 仅需 BDUSS 登录态 + 手机 UA；psign 为写死常量，rand/devuid 复用抓包常量即可。
     * @return 选中的 appall 明文直链；全部候选不可用时抛异常
     */
    suspend fun locateDownload(path: String, cookie: String): String = withContext(Dispatchers.IO) {
        val time = System.currentTimeMillis() / 1000
        // 抓包常量：psign 写死；rand/devuid/cuid/deviceid 有 BDUSS 登录态时可直接复用
        val url = "https://d.pcs.baidu.com/rest/2.0/pcs/file" +
            "?method=locatedownload" +
            "&app_id=${BaiduConstants.APP_ID}" +
            "&clienttype=17&ver=4.0" +
            "&ant=1&check_blue=1&es=1&esl=1&apn_id=1_-1" +
            "&freeisp=0&queryfree=0&use=1&dtype=1&eck=1&ehps=1" +
            "&err_ver=1.0&network_type=WIFI&channel=0" +
            "&path=${urlEncode(path)}" +
            "&time=$time" +
            "&rand=5ed606e9da222cde0474cdf70eda884b" +
            "&devuid=0F1E9FC2E084472DA5A61C4CF4C759AF" +
            "&cuid=0F1E9FC2E084472DA5A61C4CF4C759AF" +
            "&deviceid=348642637967375013" +
            "&psign=860a071f77c860e8cea06e4e54c518f3" +
            "&version=2.2.111.34&version_app=12.24.6&vip=0"
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_NETDISK)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post("0".toRequestBody(formMediaType))
            .build()
        val json = executeJson(request)
        checkErrno(json, "获取高速下载链接失败")
        // 直链候选选择：优先 encrypt=0（明文，无需解密）的 https 直链（appall01/02）；
        // rank1 的 d2-ant 为 encrypt=1 加密通道（需 AES-CTR 解密且部分网络 TLS 握手失败），直接排除；
        // 全部为加密通道时退回第一个 https 候选（仍有尝试价值），最后兜底第一个候选。
        val urls = json.optJSONArray("urls")
        val candidates = (0 until (urls?.length() ?: 0))
            .mapNotNull { urls?.optJSONObject(it) }
            .filter { it.optString("url").isNotBlank() }
        val directUrl = candidates
            .filter { it.optInt("encrypt", 1) == 0 }
            .sortedBy { !it.optString("url").startsWith("https") }
            .firstOrNull()?.optString("url")
            ?: candidates.firstOrNull { it.optString("url").startsWith("https") }?.optString("url")
            ?: candidates.firstOrNull()?.optString("url")
            ?: throw BaiduApiException("未返回下载链接")
        directUrl
    }

    /**
     * 获取下载直链：filemetas（按 fs_id 取链，个人网盘文件无 path 时使用）。
     * 注意：该 dlink（d.pcs.baidu.com）在文件被删除后立即失效，仅限文件仍存在时下载。
     * @return dlink；失败抛异常
     */
    suspend fun fileMetasDlink(fsId: String, cookie: String): String = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie)
            ?: throw BaiduApiException("获取 bdstoken 失败，请重新登录")
        val fsids = URLEncoder.encode("""["$fsId"]""", "UTF-8")
        val url = "https://pan.baidu.com/api/filemetas?dlink=1&fsids=$fsids&bdstoken=$bdstoken" +
            "&clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1"
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .get()
            .build()
        val json = executeJson(request)
        checkErrno(json, "获取下载链接失败")
        val info = json.optJSONArray("info")
        val dlink = info?.optJSONObject(0)?.optString("dlink")?.takeIf { it.isNotBlank() }
            ?: throw BaiduApiException("未返回下载链接")
        dlink
    }

    /**
     * 删除个人网盘文件（转存后清理），按完整路径删除。
     *
     * 以前失败只返回 false 不留痕迹，排查「同名残留删不掉」时完全没有线索；
     * 现在把 errno/err_msg 记进日志，并对 errno=-6（token 失效）刷新后重试一次。
     */
    suspend fun deleteFile(path: String, cookie: String): Boolean = withContext(Dispatchers.IO) {
        suspend fun once(token: String?): Boolean? {
            if (token.isNullOrBlank()) return null
            val body = "filelist=${URLEncoder.encode("""["$path"]""", "UTF-8")}"
            val request = Request.Builder()
                .url("https://pan.baidu.com/api/filemanager?async=2&onnest=fail&opera=delete" +
                    "&bdstoken=$token&newVerify=1&clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1")
                .header("Cookie", cookie)
                .header("User-Agent", BaiduConstants.UA_WEB)
                .header("Referer", "https://pan.baidu.com/disk/main")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .post(body.toRequestBody(formMediaType))
                .build()
            val json = runCatching { executeJson(request) }.getOrNull() ?: return null
            val errno = json.optInt("errno", 0)
            if (errno != 0) {
                AppLogger.warn(TAG, "删除失败 path=$path errno=$errno ${json.optString("err_msg")}")
                return if (errno == -6) null else false
            }
            return true
        }
        once(getBdstoken(cookie)) ?: (once(refreshBdstoken(cookie)) ?: false)
    }

    // ---------- 云盘文件管理（百度网盘功能） ----------

    /**
     * 列出个人网盘目录（修复版）：
     *  - URL 使用 pan.baidu.com（旧 yun.baidu.com 在部分账号下偶发 errno=-6/191）
     *  - 携带 bdstoken + channel=chunlei + showempty=0，对齐官方 web 抓包
     *  - errno=-6（bdstoken 失效 / 脏 Cookie 多份同名值）→ 先 refreshBdstoken 重试一次，
     *    仍 -6 则按 name 去重 Cookie（保留最后一次）后再兜底重试一次
     *  - 其它非零 errno → 抛 IllegalStateException（不静默吞掉，避免误判为空目录）
     */
    suspend fun listCloudFiles(dir: String, cookie: String): List<ShareFile> = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie)
        // 首试：用当前 bdstoken（可能为空，仍尝试一次让服务端兜底）
        var (array, errno) = requestListCloudFiles(dir, cookie, bdstoken)
        if (errno == -6) {
            // bdstoken 失效：强制刷新后重试一次
            val fresh = refreshBdstoken(cookie) ?: bdstoken
            val (array2, errno2) = requestListCloudFiles(dir, cookie, fresh)
            array = array2
            errno = errno2
        }
        if (errno == -6) {
            // 存量脏 Cookie 兜底（issue #94）：同一 name 出现多份冲突值（典型是 4 份
            // STOKEN）会让鉴权恒回 -6，且 gettemplatevariable 同样 -6，上面的刷新重试
            // 必然失败。按 name 去重（保留最后一次）后再走一遍，避免强迫用户重新登录。
            val clean = deduplicateCookieHeader(cookie)
            if (clean != cookie) {
                val fresh = refreshBdstoken(clean) ?: bdstoken
                val (array3, errno3) = requestListCloudFiles(dir, clean, fresh)
                array = array3
                errno = errno3
            }
        }
        if (errno != 0) {
            throw IllegalStateException("百度网盘接口错误 (errno=$errno)")
        }
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    ShareFile(
                        fid = item.optString("fs_id"),
                        fname = item.optString("server_filename"),
                        fsize = item.optLong("size"),
                        isdir = item.optInt("isdir") == 1,
                        pdirFid = dir,
                        fidToken = item.optString("path"),
                        modifyTime = item.optString("server_mtime")
                    )
                )
            }
        }
    }

    /**
     * 构造并发送一次 list 请求（私有 helper，不重试不缓存）。
     * @return (list 数组, errno)；errno 由调用方判断是否重试
     */
    private suspend fun requestListCloudFiles(
        dir: String,
        cookie: String,
        bdstoken: String?
    ): Pair<JSONArray, Int> = withContext(Dispatchers.IO) {
        val bdstokenPart = bdstoken?.takeIf { it.isNotBlank() }?.let { "&bdstoken=$it" } ?: ""
        val url = "https://pan.baidu.com/api/list?clienttype=0&app_id=${BaiduConstants.APP_ID}" +
            "&web=1&channel=chunlei&showempty=0&order=time&desc=1&dir=" +
            URLEncoder.encode(dir, "UTF-8") + "&num=100&page=1" + bdstokenPart
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "https://pan.baidu.com/disk/main")
            .get()
            .build()
        val json = executeJson(request)
        JSONArray(json.optJSONArray("list") ?: JSONArray()) to json.optInt("errno")
    }

    /** 重命名（filemanager opera=rename，按完整路径） */
    suspend fun renameFile(path: String, newName: String, cookie: String): Boolean = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie) ?: return@withContext false
        val filelist = """[{"path":"$path","newname":"$newName"}]"""
        val body = "filelist=${URLEncoder.encode(filelist, "UTF-8")}"
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/filemanager?async=0&onnest=fail&opera=rename" +
                "&bdstoken=$bdstoken&clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", "https://pan.baidu.com/disk/main")
            .post(body.toRequestBody(formMediaType))
            .build()
        runCatching {
            val json = executeJson(request)
            json.optInt("errno") == 0
        }.getOrDefault(false)
    }

    /** 移动单个/多个文件（filemanager opera=move，path→dest，newname 保持原名） */
    suspend fun moveFiles(
        paths: List<String>,
        dest: String,
        cookie: String
    ): Boolean = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie) ?: return@withContext false
        val items = paths.joinToString(",") { p ->
            val name = p.substringAfterLast('/')
            """{"path":"$p","dest":"$dest","newname":"$name"}"""
        }
        val body = "filelist=${URLEncoder.encode("[$items]", "UTF-8")}"
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/filemanager?async=2&onnest=fail&opera=move" +
                "&bdstoken=$bdstoken&clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", "https://pan.baidu.com/disk/main")
            .post(body.toRequestBody(formMediaType))
            .build()
        runCatching {
            val json = executeJson(request)
            json.optInt("errno") == 0
        }.getOrDefault(false)
    }

    /** 批量删除（filemanager opera=delete，按完整路径） */
    suspend fun deleteFiles(paths: List<String>, cookie: String): Boolean = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie) ?: return@withContext false
        val body = "filelist=${URLEncoder.encode(paths.joinToString(",", "[", "]") { "\"$it\"" }, "UTF-8")}"
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/filemanager?async=2&onnest=fail&opera=delete" +
                "&bdstoken=$bdstoken&newVerify=1&clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("Referer", "https://pan.baidu.com/disk/main")
            .post(body.toRequestBody(formMediaType))
            .build()
        runCatching {
            val json = executeJson(request)
            json.optInt("errno") == 0
        }.getOrDefault(false)
    }

    /** 百度创建分享结果
     *
     *  @param expiredType 服务端返回的有效期（0永久/1一天/7七天/30三十天）；响应未返回该字段时为 null，
     *    由调用方回退到用户所选值（别用 0 兜底——0 是「永久有效」，会把创建失败静默显示成永久）。
     */
    data class BaiduShareResult(
        val link: String,
        val pwd: String,
        val shareId: String,
        val expiredType: Int?
    )

    /** 创建分享（share/set，按 fs_id 列表 + 有效期 period + 4 位提取码）
     *
     *  @param period 百度 API 的有效期**字面值**：0=永久、1=一天、7=七天、30=三十天。
     *    ⚠️ 不是 UI 中性码（1/2/3/4），调用方必须先用
     *    [top.mcly99.dl.data.network.model.ShareExpire.baiduPeriod] 转换。
     */
    suspend fun createShare(
        fsIds: List<String>,
        period: Int,
        pwd: String,
        cookie: String
    ): BaiduShareResult = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie)
            ?: throw BaiduApiException("获取 bdstoken 失败，请重新登录")
        val fidList = fsIds.joinToString(",", "[", "]")
        val body = "fid_list=${URLEncoder.encode(fidList, "UTF-8")}" +
            "&schannel=4&channel_list=%5B%5D&period=$period&pwd=${urlEncode(pwd)}"
        val request = Request.Builder()
            .url("https://pan.baidu.com/share/set?channel=chunlei&web=1" +
                "&app_id=${BaiduConstants.APP_ID}&bdstoken=$bdstoken&clienttype=0")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("Referer", "https://pan.baidu.com/disk/main")
            .post(body.toRequestBody(formMediaType))
            .build()
        val json = executeJson(request)
        checkErrno(json, "创建分享失败")
        val link = json.optString("shorturl").ifBlank { json.optString("link") }
            .takeIf { it.isNotBlank() } ?: throw BaiduApiException("未返回分享链接")
        BaiduShareResult(
            link = link,
            pwd = pwd,
            shareId = json.optString("shareid"),
            // 用 opt + Number 转换：字段缺失 / 显式 null / 非数字一律回 null，
            // 交给调用方回退到用户所选值（optInt 会把它们变成 0，而 0 是「永久有效」，会显示成假的有效期）
            expiredType = (json.opt("expiredType") as? Number)?.toInt()
        )
    }

    // ---------- 网盘空间详情 ----------

    /** 网盘空间详情（GET pan.baidu.com/api/quota：total / used） */
    suspend fun getQuota(cookie: String): QuotaInfo? = withContext(Dispatchers.IO) {
        val url = "https://pan.baidu.com/api/quota?clienttype=0&app_id=${BaiduConstants.APP_ID}" +
            "&web=1&channel=chunlei&version=${System.currentTimeMillis()}"
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", BaiduConstants.UA_WEB)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "https://pan.baidu.com/disk/main")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val body = response.use { it.body?.string() ?: return@runCatching null }
            val json = JSONObject(body)
            if (json.optInt("errno") != 0) return@runCatching null
            QuotaInfo(
                used = json.optLong("used"),
                total = json.optLong("total")
            )
        }.getOrNull()
    }

    // ---------- 公共 ----------

    /**
     * 执行 JSON 请求（suspend，支持 errno=8888 限流自动重试）：
     *  - 百度网盘偶发 errno=8888（接口限流），自动重试 3 次（间隔 2 秒）；
     *  - 每次 retry 都重新构造 Request（OkHttp Request 一旦执行即被消耗，不能复用）；
     *  - 重试期间补充 Accept / Accept-Language 头，模拟浏览器正常请求降低被限流概率；
     *  - 最终仍为 8888 → 抛 BaiduApiException，提示用户稍后再试。
     */
    private suspend fun executeJson(request: Request): JSONObject = withContext(Dispatchers.IO) {
        // 首试：直接执行原始 request
        var json = doExecuteOnce(request)
        var errno = json.optInt("errno", 0)
        if (errno == 8888) {
            // 限流：重试 3 次，每次重建 Request 并补头
            repeat(3) {
                delay(2.seconds)
                val rebuilt = Request.Builder()
                    .url(request.url)
                    .headers(request.headers)
                    .method(request.method, request.body)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build()
                json = doExecuteOnce(rebuilt)
                errno = json.optInt("errno", 0)
                if (errno != 8888) return@repeat
            }
        }
        json
    }

    /** 单次执行并解析 JSON（不重试，仅做基础响应校验） */
    private fun doExecuteOnce(request: Request): JSONObject {
        val response = client.newCall(request).execute()
        val body = response.use { it.body?.string() ?: throw BaiduApiException("请求失败：响应为空") }
        return runCatching { JSONObject(body) }.getOrElse {
            throw BaiduApiException("响应解析失败")
        }
    }

    /**
     * 组装携带本分享验证标识的 Cookie：
     * sekey 非空时剔除 Cookie 里残留的旧 BDCLND 再补上本分享的 randsk；
     * sekey 为空（公共分享 / 用户在浏览器里已验证）时原样返回，不动用户 Cookie。
     */
    /**
     * 把分享验证得到的 randsk 写进 BDCLND。
     * @param encoded true 时按 URL 编码形式写入（部分接口只认这种），false 写原文（网页 Cookie 的形态）
     */
    private fun cookieWithSekey(cookie: String, sekey: String, encoded: Boolean = false): String {
        if (sekey.isBlank()) return cookie
        val base = cookie.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("BDCLND=") }
            .joinToString("; ")
        val value = if (encoded) urlEncode(sekey) else sekey
        return "$base; BDCLND=$value"
    }

    private fun checkErrno(json: JSONObject, fallback: String) {
        val errno = json.optInt("errno")
        if (errno != 0) {
            // 常见：errno=-12 提取码错误 / 403 分享已失效 / 31066 文件不存在
            val msg = json.optString("err_msg").ifBlank { json.optString("show_msg") }.ifBlank { fallback }
            throw BaiduApiException("$msg（errno=$errno）", errno)
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

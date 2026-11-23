/*
 * Ported from YunX-Desktop — https://github.com/tidain/YunX-Desktop
 * Upstream: main branch @ 00a030f7 (tag v1.3.4, 2026-11-13).
 * Copyright (C) the YunX-Desktop contributors.
 * License: GNU Affero General Public License v3.0 (AGPL-3.0).
 * Full text: third-party/yunx-desktop/LICENSE.txt and THIRD-PARTY-NOTICES.md.
 * This file is a derivative work: namespace isolated, app services shimmed.
 */
package top.mcly99.dl.data.repository

import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.data.network.BaiduApi
import top.mcly99.dl.data.network.BaiduApiException
import top.mcly99.dl.data.network.BaiduConstants
import top.mcly99.dl.data.network.BaiduTransferResult
import top.mcly99.dl.data.network.ShareLinkParser
import top.mcly99.dl.data.network.model.DownloadLink
import top.mcly99.dl.data.network.model.ShareFile
import top.mcly99.dl.data.network.model.ShareSession
import kotlinx.coroutines.delay
import top.mcly99.dl.utils.AppLogger

/**
 * 百度分享解析仓库：verify 拿 sekey → xpan/share 列文件 → 转存临时目录（自动创建，失败回退根目录）→ locatedownload 拿 appall 高速链 → 立即删除临时转存。
 * 全部基于抓包链路（share/verify → xpan/share list → share/transfer → locatedownload）。
 * appall 直链 URL 自带签名、删除转存后仍有效，故取链成功后立即删除临时转存（失败不阻断）。
 */
class BaiduResolveRepository(private val api: BaiduApi) : ShareResolveRepository {

    /** ktdown 适配：百度覆写了免转存取链（tplconfig + sharedownload），失败时上层回落到转存链路 */
    override val supportsSeparateNoSaveLink: Boolean get() = true

    private val TAG = "BaiduResolve"

    // 会话缓存按「账号 + surl」二维隔离：多账号解析同一个分享时不互相覆盖 sekey/shareid/workDir（P1-5）。
    // 账号用完整 Cookie 字符串作 key（不用 hashCode，避免碰撞）；全部用 ConcurrentHashMap 支撑并发。

    /** (cookie|surl) -> sekey（verify 返回的 randsk） */
    private val sekeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** (cookie|surl) -> (share_id, uk)，由列表接口返回（转存必需） */
    private val shareInfos = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    /** 账号 -> 已探明可用的转存目录，同一账号后续文件直接复用，避免每个文件重跑兜底 */
    private val workDirs = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 缓存 key：账号凭证 + surl，两个维度缺一不可 */
    private fun keyOf(cookie: String, surl: String): String = "$cookie|$surl"

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            val parsed = ShareLinkParser.parse(link)
                ?: throw IllegalArgumentException("无法识别百度分享链接")
            val surl = parsed.shareId
            // 修复：公共分享（pwd 为空）不强制提取码——跳过 verify，sekey 置空，
            // listShare 将不带 sekey 直接列出（抓包实证：公共分享无需 sekey/Cookie 即 errno=0）
            val effectivePwd = pwd?.takeIf { it.isNotBlank() } ?: parsed.pwd
            val sekey = if (effectivePwd.isNullOrBlank()) {
                ""
            } else {
                api.verifyShare(surl, effectivePwd, cookie)
            }
            sekeys[keyOf(cookie, surl)] = sekey
            // 转存必须先拿到 shareid（数字 ID）与分享者 uk：链接里的 surl 只是短码，
            // 列表接口又可能不回这两个字段。这里趁解析时顺手从分享页取一次并缓存，
            // 取不到也不阻断浏览（列表仍可用），转存时再兜底重试。
            runCatching { api.shareMeta(surl, sekey, cookie) }.getOrNull()?.let {
                shareInfos[keyOf(cookie, surl)] = it.shareId to it.uk
            }
            ShareSession(surl, sekey, "")
        }

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            val sekey = session.stoken.ifBlank { sekeys[keyOf(cookie, session.shareId)] ?: "" }
            // 顶层 dirFid 为空/"/"；子目录 dirFid 为目录 path（如 /folder）
            val all = mutableListOf<ShareFile>()
            var page = 1
            var result: top.mcly99.dl.data.network.BaiduShareList
            do {
                result = api.listShare(session.shareId, sekey, dirFid, cookie, page)
                all += result.files
                page++
            } while (result.files.size == 100 && page <= 100)
            // 缓存 share_id/uk（转存需要）；子目录列表可能回空字段，
            // 空值不许覆盖掉解析阶段已经从分享页拿到的有效值
            if (result.shareId.isNotBlank() && result.uk.isNotBlank()) {
                shareInfos[keyOf(cookie, session.shareId)] = result.shareId to result.uk
            }
            all
        }

    override suspend fun ensureTempDir(cookie: String): Result<String> = runCatching {
        val rootListing = runCatching { api.listDir("/", cookie) }.getOrNull()
        // 优先复用已有的目录：老版本建的 legacy 目录（网盘里可能还留着转存过的文件）
        // 也一并认，避免升级后在用户网盘里多出一个空目录
        val existing = (BaiduConstants.LEGACY_TEMP_DIR_NAMES + BaiduConstants.TEMP_DIR_NAME)
            .map { "/$it" }
            .firstOrNull { dir -> rootListing?.any { it == dir } == true }
        if (existing != null) return@runCatching existing

        val dir = "/${BaiduConstants.TEMP_DIR_NAME}"
        suspend fun existsNow(): Boolean =
            runCatching { api.listDir("/", cookie).any { it == dir } }.getOrDefault(false)
        // 不存在则创建，**建完必须回查**——
        // 建目录接口回 errno=0 却没建成的情况真实存在，对着不存在的目录转存只会拿到 errno=2
        if (api.createDir(dir, cookie) && existsNow()) {
            dir
        } else {
            AppLogger.warn(TAG, "临时目录 $dir 创建后回查仍不存在，回退到根目录")
            "/"
        }
    }

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String,
        skipSpaceCheck: Boolean
    ): Result<String> = runCatching {
        // 转存前置空间校验：空间不足直接抛出，不再走后面的转存（避免被误报「转存超时」）；
        // 批量入口已做过整批预算校验时跳过（避免同一批多次查配额）
        if (!skipSpaceCheck) checkSpace(file, cookie)
        val (shareId, uk) = requireShareInfo(session, cookie)
        val result = api.transfer(
            shareId, uk, session.stoken, file.fid, toDirFid, cookie, surl = session.shareId,
        )
        result.fsId
    }

    /** 批量转存 / 批量下载前的整批空间预算校验：只查一次配额，不足时抛出（百度转存占用目标账号空间） */
    override suspend fun ensureBatchSpace(sizes: List<Long>, credential: String): Boolean =
        TransferSpaceGuard.ensureEnoughSpaceForBatch(sizes, "百度") { api.getQuota(credential) }

    /** 转存前置空间校验：空间不足直接抛出，不再走后面的转存与轮询 */
    private suspend fun checkSpace(file: ShareFile, cookie: String) =
        TransferSpaceGuard.ensureEnoughSpace(file.fsize.takeIf { it > 0 }, "百度") { api.getQuota(cookie) }

    /** 个人网盘文件直链（filemetas） */
    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> = runCatching {
        val dlink = api.fileMetasDlink(fid, cookie)
        DownloadLink(fid = fid, filename = "", downloadUrl = dlink, size = 0L)
    }

    /** 百度取直链：转存临时目录（自动创建，失败回退根目录）→ locatedownload 拿 appall 高速链 → 立即删除临时转存（失败不阻断） */
    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String,
        skipSpaceCheck: Boolean
    ): Result<DownloadLink> = runCatching {
        // 下载路径同样要先转存（占用空间）：单文件下载在此校验；批量时由调用方整批校验后跳过
        if (!skipSpaceCheck) checkSpace(file, cookie)
        val (shareId, uk) = requireShareInfo(session, cookie)
        val dirPath = ensureTempDir(cookie).getOrThrow()
        val transferred = transferToTemp(session, file, shareId, uk, dirPath, cookie)
        // 转存接口有时只回 errno=0 不给路径（异步转存尤其如此），
        // 此时按「目录 + 文件名」回查真实路径，给异步落盘留一点时间
        val path = transferred.path.takeIf { it.isNotBlank() }
            ?: awaitTransferred(dirPath, file, cookie)
            ?: throw BaiduApiException("转存成功但未能定位文件（${file.fname}），请稍后重试")
        // locatedownload 按转存后的完整路径取链，返回 appallNN.baidupcs.com CDN 直链
        // （自带 sign/expires，删除转存后仍有效；仅需 BDUSS + 手机 UA 即可满速下载）
        val dlink = api.locateDownload(path, cookie)
        // appall 直链不依赖转存文件存活：取链成功后立即删除临时转存，网盘不留残留
        deleteTransferred(path, cookie)
        DownloadLink(
            fid = transferred.fsId,
            filename = file.fname,
            downloadUrl = dlink,
            size = file.fsize
        )
    }

    /**
     * 免转存取链：分享签名（`/share/tplconfig`）→ `/api/sharedownload` 直接换下载地址。
     *
     * 这条路是网页端「下载」按钮的真实链路，**不往用户网盘写任何东西**，
     * 因此不会碰上「转存被拒（errno=2）」这类跟目标目录、转存权限相关的失败。
     * 失败时由调用方回退到「转存 → locatedownload」。
     */
    override suspend fun getShareDownloadLinkWithoutSave(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        val (shareId, uk) = requireShareInfo(session, cookie)
        val sekey = session.stoken.ifBlank { sekeys[keyOf(cookie, session.shareId)] ?: "" }
        // 签名有两个来源：分享页里那份（浏览器自己用的，与页面 bdstoken、验证态同源）
        // 和 `/share/tplconfig` 换来的那份。前者更可信，两个都备着按序试。
        val page = runCatching { api.sharePage(session.shareId, sekey, cookie) }.getOrNull()
        val signs = buildList {
            page?.toSign()?.let { add(it) }
            api.shareSign(session.shareId, sekey, cookie)?.let { tpl ->
                if (none { it.sign == tpl.sign && it.timestamp == tpl.timestamp }) add(tpl)
            }
        }
        if (signs.isEmpty()) throw BaiduApiException("未取到分享签名，请重新解析该分享后重试")
        // /api/sharedownload 的 path 是「文件所在目录」；条目自身路径存在 pdirFid 里
        val parent = file.pdirFid.substringBeforeLast('/', "").ifBlank { "/" }
        val dlink = api.shareDirectDlink(
            shareId = shareId,
            shareUk = uk,
            fsId = file.fid,
            path = parent,
            signs = signs,
            sekey = sekey,
            cookie = cookie,
            bdstoken = page?.bdstoken.orEmpty(),
            referer = BaiduConstants.sharePageUrl(session.shareId),
        ) ?: throw BaiduApiException("分享直链接口拒绝了请求")
        DownloadLink(
            fid = file.fid,
            filename = file.fname,
            downloadUrl = dlink,
            size = file.fsize,
        )
    }

    /**
     * 转存到应用自管的临时目录。
     *
     * 百度转存接口的 err_msg 不可信：errno=2 常被写成「文件已存在」，实际含义是
     * **目标目录不存在 / 参数不被接受**；真正的同名冲突是 errno=4 或 12+info[0].errno=-30。
     * 参数形状问题（`fsidlist` 带不带引号等）已在 [BaiduApi.transfer] 内部按兼容性重试过，
     * 这里只处理「环境类」问题，按代价从低到高：
     *  1. 直接转存；
     *  2. 目录里已有同名同大小文件 → 直接复用（上次「失败」的转存很可能其实已落盘）；
     *  3. 刷新 bdstoken 再试（token 与 Cookie 不匹配时，列目录/删除/转存会一起失灵）；
     *  4. 分享未验证 → 补分享页下发的 BDCLND / 解码后的 randsk；
     *  5. 确有同名项 → 删掉重转；删不掉就复用；
     *  6. 换一个全新的空目录重转（建完会回查确认，避免对着不存在的目录白转）。
     *
     * 探明可用的目录会记在 [workDirs]（按账号隔离），同一账号后续文件直接用，避免每个文件都重跑一轮。
     */
    private suspend fun transferToTemp(
        session: ShareSession,
        file: ShareFile,
        shareId: String,
        uk: String,
        dirPath: String,
        cookie: String,
    ): BaiduTransferResult {
        val errors = mutableListOf<String>()
        val baseDir = workDirs[cookie] ?: dirPath

        // 转存的两个「身份」参数。errno=2 有一类就是它们不对，所以失败后允许用
        // 分享页重新解析到的值替换（见下面第 2 步）
        var sid = shareId
        var from = uk
        AppLogger.info(
            TAG,
            "转存开始 file=${file.fname} fsid=${file.fid} shareid=${sid.ifBlank { "(空)" }} " +
                "from=${from.ifBlank { "(空)" }} dir=$baseDir sekey=${session.stoken.isNotBlank()}",
        )

        suspend fun attempt(dir: String): BaiduTransferResult? {
            val result = runCatching {
                api.transfer(
                    sid, from, session.stoken, file.fid, dir, cookie, surl = session.shareId,
                )
            }
            result.exceptionOrNull()?.let { errors += (it.message ?: "未知错误") }
            val ok = result.getOrNull()
            // 记住跑通的目录（按账号隔离），后续同账号文件不再重试
            if (ok != null && dir != dirPath) {
                workDirs[cookie] = dir
            }
            return ok
        }

        // 参数形状（fsidlist 写法 / sekey 传法 / BDCLND 注入 / UA / Referer）已在
        // [BaiduApi.transfer] 内部按阶梯试过一轮，这里只处理「换个环境还能成」的少数情况。
        val first = runCatching {
            api.transfer(sid, from, session.stoken, file.fid, baseDir, cookie, surl = session.shareId)
        }
        first.getOrNull()?.let { return it }
        val firstError = first.exceptionOrNull()
        errors += firstError?.message ?: "未知错误"
        val errno = (firstError as? BaiduApiException)?.errno
        val retryable = errno?.let { it == 2 || it == 4 || it == -6 } ?: false
        if (!retryable) throw firstError ?: IllegalStateException("转存失败")

        // 1) 先看目录里是不是已经有同名同大小的文件：百度对「已落盘」的重复请求会回 errno=2，
        //    上一次「失败」的转存很可能其实已经成功，直接复用最省事也不动用户数据
        findTransferred(baseDir, file, cookie)?.let { reused ->
            AppLogger.info(TAG, "目录里已有同名同大小文件，直接复用：$reused")
            return BaiduTransferResult(fsId = "", path = reused)
        }

        // 2) 核对分享参数：分享页解析出的 shareid/uk 与手上的不一致就换掉重试。
        //    百度对「shareid 不对」的响应和「目录不对」一模一样（errno=2 + 文件已存在）。
        if (errno == 2) {
            // force=true：解析阶段可能失败过一次（负缓存），这里值得重抓一次页面
            val fresh = runCatching {
                api.shareMeta(session.shareId, session.stoken, cookie, force = true)
            }.getOrNull()?.takeIf { it.shareId.isNotBlank() && it.uk.isNotBlank() }
            if (fresh != null && (fresh.shareId != sid || fresh.uk != from)) {
                AppLogger.info(
                    TAG,
                    "改用分享页参数重试转存（shareid/from：$sid/$from → ${fresh.shareId}/${fresh.uk}）",
                )
                sid = fresh.shareId
                from = fresh.uk
                shareInfos[keyOf(cookie, session.shareId)] = sid to from
                attempt(baseDir)?.let { return it }
            }
        }

        // 3) 真有同名残留（errno=4）：删掉重转，删不掉就复用
        if (errno == 4) {
            val existing = findTransferred(baseDir, file.copy(fsize = 0), cookie)
            if (existing != null) {
                val removed = api.deleteFile(existing, cookie)
                AppLogger.info(TAG, "转存冲突，删除残留 $existing: $removed")
                if (removed) attempt(baseDir)?.let { return it }
                return BaiduTransferResult(fsId = "", path = existing)
            }
        }

        // 4) bdstoken 与当前 Cookie 不匹配时，列目录/删除/转存会一起失灵，刷新再试
        api.refreshBdstoken(cookie)
        AppLogger.info(TAG, "刷新 bdstoken 后重试转存：$baseDir")
        attempt(baseDir)?.let { return it }

        // 给用户看的话要短、可执行；技术细节（shareid/dir/参数形状/每次的 errno）只进日志，
        // 弹窗里堆一堆内部诊断既吓人又帮不上忙
        AppLogger.warn(
            TAG,
            "转存最终失败 file=${file.fname} shareid=$sid from=$from dir=$baseDir " +
                "stoken=${BaiduConstants.hasStoken(cookie)} errors=${errors.take(3)}",
        )
        throw BaiduApiException(
            "转存被百度拒绝（已尝试换参数形状、刷新 token、核对分享参数与目标目录）。"
        )
    }

    /**
     * 等转存落盘并返回真实路径。
     *
     * 异步转存（channel=chunked&async=1）只回 taskid，文件要过一会儿才出现在目录里；
     * 同步转存偶尔也只回 errno=0 不给路径。这里轮询几次再放弃。
     */
    private suspend fun awaitTransferred(
        dirPath: String,
        file: ShareFile,
        cookie: String,
        attempts: Int = 6,
    ): String? {
        repeat(attempts) { index ->
            findTransferred(dirPath, file, cookie)?.let { return it }
            if (index < attempts - 1) delay(AppConstants.RESOLVE_RETRY_BACKOFF_MS)
        }
        return null
    }

    /** 在 [baseDir] 下开一个全新的空子目录（时间戳命名），失败返回 null */
    private suspend fun freshWorkDir(baseDir: String, cookie: String): String? {
        val dir = "${baseDir.trimEnd('/')}/ktd-${System.currentTimeMillis()}"
        return if (runCatching { api.createDir(dir, cookie) }.getOrDefault(false)) dir else null
    }

    /**
     * 目录里是否已有该文件的转存；返回其完整路径。
     *
     * 用带 size 的列目录接口比对「同名 + 同大小」，避免把旧分享的同名残留当成当前文件；
     * [file] 的 fsize 传 0 表示只按名字找（用于删除残留）。
     */
    private suspend fun findTransferred(dirPath: String, file: ShareFile, cookie: String): String? {
        val listed = runCatching { api.listCloudFiles(dirPath, cookie) }
        listed.exceptionOrNull()?.let {
            // 以前这里静默返回 null，导致「复用/删残留」永远失效却毫无痕迹
            AppLogger.warn(TAG, "列目录失败，跳过复用/清理 dir=$dirPath err=${it.message}")
            return null
        }
        return listed.getOrNull()
            ?.firstOrNull { it.fname == file.fname && (file.fsize <= 0L || it.fsize == file.fsize) }
            ?.fidToken
            ?.takeIf { it.isNotBlank() }
    }

    /** 删除转存文件（失败不阻断）；删完后若所在工作目录已空也一并删掉（只碰应用自建的目录） */
    private suspend fun deleteTransferred(path: String, cookie: String) {
        runCatching { api.deleteFile(path, cookie) }
        val tempDirs = (BaiduConstants.LEGACY_TEMP_DIR_NAMES + BaiduConstants.TEMP_DIR_NAME)
            .map { "/$it/" }
        val parent = path.substringBeforeLast('/', "")
        val ownedWorkDir = tempDirs.any { parent.startsWith(it) } ||
            parent.substringAfterLast('/').startsWith("ktd-")
        if (parent.isNotEmpty() && parent != "/" && ownedWorkDir) {
            runCatching { api.deleteFile(parent, cookie) }
        }
    }

    /**
     * 取 share_id/uk：优先用已缓存的，其次列表接口，最后分享页解析。
     *
     * 顺序是「便宜的先试」：缓存 0 次请求、列表 1 次请求（顺带完成一次目录读取）、
     * 分享页 1 次请求。三者都拿不到才算失败——空着传下去只会换来 errno=2。
     */
    private suspend fun requireShareInfo(session: ShareSession, cookie: String): Pair<String, String> {
        val key = keyOf(cookie, session.shareId)
        shareInfos[key]
            ?.takeIf { it.first.isNotBlank() && it.second.isNotBlank() }
            ?.let { return it }
        val sekey = session.stoken.ifBlank { sekeys[key] ?: "" }
        runCatching { api.listShare(session.shareId, sekey, "/", cookie) }.getOrNull()?.let { result ->
            if (result.shareId.isNotBlank() && result.uk.isNotBlank()) {
                val info = result.shareId to result.uk
                shareInfos[key] = info
                return info
            }
        }
        val meta = runCatching { api.shareMeta(session.shareId, sekey, cookie) }.getOrNull()
            ?: throw BaiduApiException("无法获取分享 ID（shareid），分享可能已失效或需要重新解析")
        AppLogger.info(TAG, "分享元信息取自分享页：shareid=${meta.shareId} uk=${meta.uk}")
        val info = meta.shareId to meta.uk
        shareInfos[key] = info
        return info
    }
}

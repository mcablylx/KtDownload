package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.BaiduApi
import top.mcly99.dl.data.network.C139Api
import top.mcly99.dl.data.network.GuangYaApi
import top.mcly99.dl.data.network.GuangYaConstants
import top.mcly99.dl.data.network.GuangYaDevice
import top.mcly99.dl.data.network.ILanzouApi
import top.mcly99.dl.data.network.ILanzouConstants
import top.mcly99.dl.data.network.LanzouApi
import top.mcly99.dl.data.network.LanzouConstants
import top.mcly99.dl.data.network.Pan115Api
import top.mcly99.dl.data.network.Pan115Constants
import top.mcly99.dl.data.network.Pan123Api
import top.mcly99.dl.data.network.Pan123Constants
import top.mcly99.dl.data.network.QuarkApi
import top.mcly99.dl.data.network.QuarkConstants
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.UCApi
import top.mcly99.dl.data.network.UCConstants
import top.mcly99.dl.data.network.XunleiApi
import top.mcly99.dl.data.network.XunleiDeviceFingerprint
import top.mcly99.dl.data.network.WebCaptchaInit
import top.mcly99.dl.data.network.model.ShareExpire
import top.mcly99.dl.data.network.model.ShareInfo

/**
 * P4「我的网盘」的平台适配层（自研 glue，不是 AGPL 移植）。
 *
 * 上游把 10 个平台拆成 10 个 *CloudViewModel（约 1.5 万行），每个 ViewModel 里
 * 直接调自己的 Api 类。这里只保留「调用序列」这一层差异：一个 [MyDrivePlatformAdapter]
 * 对应一个平台，方法签名统一，返回统一投影 [MyDriveFile]。
 *
 * 能力位为 false 的方法保留默认实现（抛 [MyDriveUnsupportedException]），
 * 是防御性的第二道保险 —— UI 正常情况下根本不会调用它们。
 *
 * 两个刻意不做的事：
 * - **上传 / 回收站**：移植层没有可靠接口，上游 UI 的「回收站可恢复」也是假文案；
 * - **复制粘贴 AGPL UI**：只参考上游各 ViewModel 的调用参数，不复制布局代码。
 */
interface MyDrivePlatformAdapter {
    val platform: SharePlatform
    val capabilities: MyDriveCapabilities
    val rootDirId: String

    suspend fun list(ctx: DriveContext, dirId: String, cursor: String? = null): MyDrivePage =
        unsupported("列目录")

    suspend fun createFolder(ctx: DriveContext, parentId: String, name: String): String? =
        unsupported("新建文件夹")

    suspend fun rename(ctx: DriveContext, file: MyDriveFile, newName: String): Boolean =
        unsupported("重命名")

    suspend fun move(ctx: DriveContext, files: List<MyDriveFile>, toParentId: String): Boolean =
        unsupported("移动")

    suspend fun delete(ctx: DriveContext, files: List<MyDriveFile>): Boolean =
        unsupported("删除")

    /** 目前只支持单选分享；[expiredType] 是 [ShareExpire] 的中性码（只用永久 / 7 天两档） */
    suspend fun createShare(
        ctx: DriveContext,
        files: List<MyDriveFile>,
        passcode: String,
        expiredType: Int,
    ): MyDriveShareResult? = unsupported("创建分享")

    suspend fun quota(ctx: DriveContext): MyDriveQuota? = null

    fun unsupported(action: String): Nothing =
        throw MyDriveUnsupportedException(action, platform)
}

/** ShareInfo（移植层）→ 统一分享结果 */
private fun ShareInfo.toMyDriveShareResult(): MyDriveShareResult = MyDriveShareResult(
    url = shareUrl,
    passcode = passcode,
    title = title,
    warning = warning,
)

private fun fullCapabilities(
    createShare: Boolean = true,
    quota: Boolean = true,
    expiryTiers: List<Int> = DEFAULT_SHARE_TIERS,
    passcode: Boolean = true,
    /**
     * 自己网盘里的文件能否取直链下载。
     *
     * 以前写死 `true`：139 / 123 / 115 / 光鸭 / 蓝奏 / 蓝奏优享 的仓库
     * `getDownloadLink` 恒抛 `UnsupportedOperationException`（那是**分享**链路的接口，
     * 自己网盘的文件走的是另一套），于是「下载」按钮一定失败，UI 却提示「已加入下载」（P1-15）。
     * 现在按平台真实支持声明，做不到就灰按钮 —— 与 [MyDriveStrings.capabilityHint] 的承诺一致。
     */
    download: Boolean = true,
) = MyDriveCapabilities(
    list = true, createFolder = true, rename = true, move = true, delete = true,
    createShare = createShare, quota = quota, download = download, saveShare = true,
    // 不能建分享的平台不显示有效期选择器，避免「有档位但按钮是灰的」这种半残状态
    shareExpiryTiers = if (createShare) expiryTiers else emptyList(),
    sharePasscode = passcode,
)

/** 115 的档位与中性码不同，且多出 3/15 天、没有 30 天，单独声明 */
private val PAN115_SHARE_TIERS: List<Int> = listOf(
    ShareExpire.PAN115_FOREVER,
    ShareExpire.PAN115_ONE_DAY,
    ShareExpire.PAN115_THREE_DAYS,
    ShareExpire.PAN115_SEVEN_DAYS,
    ShareExpire.PAN115_FIFTEEN_DAYS,
)

// ------------------------------------------------------------------ 夸克 / UC

/** 夸克与 UC 的接口几乎对称，只差具体 Api 类，抽一个共用骨架避免两份漂移 */
private abstract class QuarkLikeAdapter : MyDrivePlatformAdapter {
    override val capabilities: MyDriveCapabilities = fullCapabilities()

    abstract suspend fun listAll(dirId: String, credential: String): List<top.mcly99.dl.data.network.model.ShareFile>?
    abstract suspend fun mkdir(name: String, parentId: String, credential: String): String?
    abstract suspend fun deleteOne(id: String, credential: String): Any?
    abstract suspend fun renameOne(id: String, newName: String, credential: String): Boolean
    abstract suspend fun moveOne(id: String, toParentId: String, credential: String): String?
    abstract suspend fun shareOne(
        id: String, title: String, urlType: Int, passcode: String,
        expiredType: Int, credential: String,
    ): String?
    abstract suspend fun shareInfo(shareId: String, credential: String): ShareInfo?
    abstract suspend fun space(credential: String): top.mcly99.dl.data.network.model.QuotaInfo?

    final override suspend fun list(ctx: DriveContext, dirId: String, cursor: String?): MyDrivePage =
        MyDrivePage((listAll(dirId, ctx.credential) ?: emptyList()).map { it.toMyDriveFile() })

    final override suspend fun createFolder(ctx: DriveContext, parentId: String, name: String): String? =
        mkdir(name, parentId, ctx.credential)

    final override suspend fun rename(ctx: DriveContext, file: MyDriveFile, newName: String): Boolean =
        renameOne(file.id, newName, ctx.credential)

    final override suspend fun move(ctx: DriveContext, files: List<MyDriveFile>, toParentId: String): Boolean {
        // 接口一次只收一个 fid；逐项执行，全部成功才算成功
        return files.all { moveOne(it.id, toParentId, ctx.credential) != null }
    }

    final override suspend fun delete(ctx: DriveContext, files: List<MyDriveFile>): Boolean =
        files.all { deleteOne(it.id, ctx.credential) != null }

    final override suspend fun createShare(
        ctx: DriveContext, files: List<MyDriveFile>, passcode: String, expiredType: Int,
    ): MyDriveShareResult? {
        val file = files.firstOrNull() ?: return null
        // 1=无提取码 2=链接+提取码；expired_type 的取值恰好等于中性码本身
        val urlType = if (passcode.isBlank()) 1 else 2
        val shareId = shareOne(file.id, file.name, urlType, passcode, expiredType, ctx.credential)
            ?: return null
        return shareInfo(shareId, ctx.credential)?.toMyDriveShareResult()
    }

    final override suspend fun quota(ctx: DriveContext): MyDriveQuota? =
        space(ctx.credential)?.takeIf { it.total > 0 }?.let { MyDriveQuota(it.used, it.total) }
}

private object QuarkAdapter : QuarkLikeAdapter() {
    private val api = QuarkApi()
    override val platform = SharePlatform.QUARK
    override val rootDirId: String = QuarkConstants.DEFAULT_PDIR_FID
    override suspend fun listAll(dirId: String, credential: String) =
        api.listAllCloudFiles(dirId, credential)
    override suspend fun mkdir(name: String, parentId: String, credential: String) =
        api.createFolder(name, parentId, credential)
    override suspend fun deleteOne(id: String, credential: String) = api.deleteFile(id, credential)
    override suspend fun renameOne(id: String, newName: String, credential: String) =
        api.renameFile(id, newName, credential)
    override suspend fun moveOne(id: String, toParentId: String, credential: String) =
        api.moveFile(id, toParentId, credential)
    override suspend fun shareOne(
        id: String, title: String, urlType: Int, passcode: String,
        expiredType: Int, credential: String,
    ) = api.createShare(listOf(id), title, urlType, passcode, expiredType, credential)
    override suspend fun shareInfo(shareId: String, credential: String) =
        api.getShareInfo(shareId, credential)
    override suspend fun space(credential: String) = api.getQuota(credential)
}

private object UCAdapter : QuarkLikeAdapter() {
    private val api = UCApi()
    override val platform = SharePlatform.UC
    override val rootDirId: String = UCConstants.DEFAULT_PDIR_FID
    override suspend fun listAll(dirId: String, credential: String) =
        api.listAllCloudFiles(dirId, credential)
    override suspend fun mkdir(name: String, parentId: String, credential: String) =
        api.createFolder(name, parentId, credential)
    override suspend fun deleteOne(id: String, credential: String) = api.deleteFile(id, credential)
    override suspend fun renameOne(id: String, newName: String, credential: String) =
        api.renameFile(id, newName, credential)
    override suspend fun moveOne(id: String, toParentId: String, credential: String) =
        api.moveFile(id, toParentId, credential)
    override suspend fun shareOne(
        id: String, title: String, urlType: Int, passcode: String,
        expiredType: Int, credential: String,
    ) = api.createShare(listOf(id), title, urlType, passcode, expiredType, credential)
    override suspend fun shareInfo(shareId: String, credential: String) =
        api.getShareInfo(shareId, credential)
    override suspend fun space(credential: String) = api.getQuota(credential)
}

// ------------------------------------------------------------------ 百度网盘

/**
 * 百度用**完整路径**而不是 fid：列目录返回的 ShareFile.fid 是 fs_id（创建分享要用），
 * fidToken 才是 path（导航 / 重命名 / 移动 / 删除全用它）。这里把 [MyDriveFile.id]
 * 统一成 path，并在 createShare 时从 [MyDriveFile.raw] 取回 fs_id。
 */
private object BaiduAdapter : MyDrivePlatformAdapter {
    private val api = BaiduApi()
    override val platform = SharePlatform.BAIDU
    override val capabilities: MyDriveCapabilities = fullCapabilities()
    override val rootDirId: String = "/"

    private fun pathOf(dirId: String): String = dirId.ifBlank { "/" }
    private fun join(parentId: String, name: String): String {
        val p = pathOf(parentId).trimEnd('/')
        return "$p/$name"
    }

    override suspend fun list(ctx: DriveContext, dirId: String, cursor: String?): MyDrivePage =
        MyDrivePage(api.listCloudFiles(pathOf(dirId), ctx.credential).map { raw ->
            raw.toMyDriveFile().copy(id = raw.fidToken.ifBlank { raw.fid })
        })

    override suspend fun createFolder(ctx: DriveContext, parentId: String, name: String): String? {
        val path = join(parentId, name)
        return if (api.createDir(path, ctx.credential)) path else null
    }

    override suspend fun rename(ctx: DriveContext, file: MyDriveFile, newName: String): Boolean =
        api.renameFile(file.id, newName, ctx.credential)

    override suspend fun move(ctx: DriveContext, files: List<MyDriveFile>, toParentId: String): Boolean =
        api.moveFiles(files.map { it.id }, pathOf(toParentId), ctx.credential)

    override suspend fun delete(ctx: DriveContext, files: List<MyDriveFile>): Boolean =
        api.deleteFiles(files.map { it.id }, ctx.credential)

    override suspend fun createShare(
        ctx: DriveContext, files: List<MyDriveFile>, passcode: String, expiredType: Int,
    ): MyDriveShareResult? {
        val file = files.firstOrNull() ?: return null
        val fsId = file.raw?.fid ?: file.id
        val result = api.createShare(
            listOf(fsId), ShareExpire.baiduPeriod(expiredType), passcode, ctx.credential,
        )
        return MyDriveShareResult(result.link, result.pwd, file.name)
    }

    override suspend fun quota(ctx: DriveContext): MyDriveQuota? =
        api.getQuota(ctx.credential)?.takeIf { it.total > 0 }?.let { MyDriveQuota(it.used, it.total) }
}

// ------------------------------------------------------------------ 139 / 123 / 115

private object C139Adapter : MyDrivePlatformAdapter {
    private val api = C139Api()
    override val platform = SharePlatform.C139
    override val capabilities: MyDriveCapabilities = fullCapabilities(download = false)
    override val rootDirId: String = "0"

    override suspend fun list(ctx: DriveContext, dirId: String, cursor: String?): MyDrivePage {
        val (files, next) = api.listCloudFiles(dirId, ctx.credential, cursor)
        return MyDrivePage(files.map { it.toMyDriveFile() }, next)
    }

    override suspend fun createFolder(ctx: DriveContext, parentId: String, name: String): String? =
        api.createDir(parentId, name, ctx.credential)

    override suspend fun rename(ctx: DriveContext, file: MyDriveFile, newName: String): Boolean =
        api.renameFile(file.id, newName, ctx.credential)

    override suspend fun move(ctx: DriveContext, files: List<MyDriveFile>, toParentId: String): Boolean =
        api.moveFiles(files.map { it.id }, toParentId, ctx.credential) != null

    override suspend fun delete(ctx: DriveContext, files: List<MyDriveFile>): Boolean =
        api.deleteFiles(files.map { it.id }, ctx.credential) != null

    override suspend fun createShare(
        ctx: DriveContext, files: List<MyDriveFile>, passcode: String, expiredType: Int,
    ): MyDriveShareResult? {
        val file = files.firstOrNull() ?: return null
        // 139 的提取码由服务端自动生成；文件与目录分列两个参数
        val coLst = if (file.isDir) emptyList() else listOf(file.id)
        val caLst = if (file.isDir) listOf(file.id) else emptyList()
        return api.createShare(
            coLst, caLst, ShareExpire.daysOrNull(expiredType), file.name, ctx.credential,
        ).toMyDriveShareResult()
    }

    override suspend fun quota(ctx: DriveContext): MyDriveQuota? =
        api.getQuota(ctx.credential)?.takeIf { it.total > 0 }?.let { MyDriveQuota(it.used, it.total) }
}

private object Pan123Adapter : MyDrivePlatformAdapter {
    private val api = Pan123Api()
    override val platform = SharePlatform.PAN123
    override val capabilities: MyDriveCapabilities = fullCapabilities(download = false)
    override val rootDirId: String = "0"

    override suspend fun list(ctx: DriveContext, dirId: String, cursor: String?): MyDrivePage {
        val (files, next) = api.listCloudFiles(dirId, ctx.credential)
        return MyDrivePage(files.map { it.toMyDriveFile() }, next)
    }

    override suspend fun createFolder(ctx: DriveContext, parentId: String, name: String): String? =
        api.createDir(parentId, name, ctx.credential)

    override suspend fun rename(ctx: DriveContext, file: MyDriveFile, newName: String): Boolean {
        api.renameFile(file.id, newName, ctx.credential)
        return true
    }

    override suspend fun move(ctx: DriveContext, files: List<MyDriveFile>, toParentId: String): Boolean {
        api.moveFiles(files.map { it.id }, toParentId, ctx.credential)
        return true
    }

    override suspend fun delete(ctx: DriveContext, files: List<MyDriveFile>): Boolean {
        // 123 的删除接口要整份 ShareFile，不是 id
        val raw = files.mapNotNull { it.raw }
        if (raw.size != files.size) return false
        api.deleteFiles(raw, ctx.credential)
        return true
    }

    override suspend fun createShare(
        ctx: DriveContext, files: List<MyDriveFile>, passcode: String, expiredType: Int,
    ): MyDriveShareResult? {
        val file = files.firstOrNull() ?: return null
        // 123 的 expiration 是绝对 ISO 时间串；永久用 2099 哨兵，其余按天数现算。
        // daysOrNull 对未知码会抛异常（fail-loud），不会静默变成另一种有效期。
        val days = ShareExpire.daysOrNull(expiredType)
        val expiration = if (days == null) Pan123Constants.EXPIRATION_FOREVER else expirationInDays(days)
        return api.createShare(
            listOf(file.id), file.name, expiration, passcode.takeIf { it.isNotBlank() }, ctx.credential,
        ).toMyDriveShareResult()
    }

    override suspend fun quota(ctx: DriveContext): MyDriveQuota? =
        api.getQuota(ctx.credential)?.takeIf { it.total > 0 }?.let { MyDriveQuota(it.used, it.total) }
}

/** 与上游 Pan123CloudViewModel.expiration 同口径：本地时区偏移手写，避免 SimpleDateFormat "XXX" 兼容问题 */
private fun expirationInDays(days: Int): String {
    val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, days) }
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
    val offsetMin = java.util.TimeZone.getDefault().getOffset(cal.timeInMillis) / 60000
    val sign = if (offsetMin >= 0) "+" else "-"
    val abs = kotlin.math.abs(offsetMin)
    return sdf.format(cal.time) + String.format("%s%02d:%02d", sign, abs / 60, abs % 60)
}

private object Pan115Adapter : MyDrivePlatformAdapter {
    private val api = Pan115Api()
    override val platform = SharePlatform.PAN115
    override val capabilities: MyDriveCapabilities = fullCapabilities(
        expiryTiers = PAN115_SHARE_TIERS,
        download = false,
    )
    override val rootDirId: String = Pan115Constants.ROOT_CID

    override suspend fun list(ctx: DriveContext, dirId: String, cursor: String?): MyDrivePage {
        val all = ArrayList<top.mcly99.dl.data.network.model.ShareFile>()
        var offset = cursor?.toIntOrNull() ?: 0
        while (true) {
            val page = api.listFiles(dirId, ctx.credential, offset, Pan115Constants.PAGE_LIMIT)
            all.addAll(page.files)
            if (page.files.isEmpty() || all.size >= page.total) break
            offset += Pan115Constants.PAGE_LIMIT
        }
        return MyDrivePage(all.map { it.toMyDriveFile() })
    }

    override suspend fun createFolder(ctx: DriveContext, parentId: String, name: String): String? =
        api.createDir(parentId, name, ctx.credential)

    override suspend fun rename(ctx: DriveContext, file: MyDriveFile, newName: String): Boolean {
        api.rename(file.id, newName, ctx.credential)
        return true
    }

    override suspend fun move(ctx: DriveContext, files: List<MyDriveFile>, toParentId: String): Boolean {
        api.move(files.map { it.id }, toParentId, ctx.credential)
        return true
    }

    override suspend fun delete(ctx: DriveContext, files: List<MyDriveFile>): Boolean {
        api.delete(files.map { it.id }, files.firstOrNull()?.parentId.orEmpty(), ctx.credential)
        return true
    }

    override suspend fun createShare(
        ctx: DriveContext, files: List<MyDriveFile>, passcode: String, expiredType: Int,
    ): MyDriveShareResult? {
        val file = files.firstOrNull() ?: return null
        // 115 的档位码与中性码不同（多 3/15 天、没有 30 天），而且两边都有「1 天 / 7 天」
        // 但取值不同；这里两种码都接受，统一收敛到 115 自己的码位
        val code = when (expiredType) {
            ShareExpire.FOREVER, ShareExpire.PAN115_FOREVER -> ShareExpire.PAN115_FOREVER
            ShareExpire.ONE_DAY, ShareExpire.PAN115_ONE_DAY -> ShareExpire.PAN115_ONE_DAY
            ShareExpire.PAN115_THREE_DAYS -> ShareExpire.PAN115_THREE_DAYS
            ShareExpire.SEVEN_DAYS, ShareExpire.PAN115_SEVEN_DAYS -> ShareExpire.PAN115_SEVEN_DAYS
            ShareExpire.PAN115_FIFTEEN_DAYS -> ShareExpire.PAN115_FIFTEEN_DAYS
            else -> throw MyDriveUnsupportedException("该有效期档位", platform)
        }
        return api.createShare(
            listOf(file.id), ShareExpire.pan115Duration(code), ctx.credential,
        ).toMyDriveShareResult()
    }

    override suspend fun quota(ctx: DriveContext): MyDriveQuota? =
        api.getQuota(ctx.credential)?.takeIf { it.total > 0 }?.let { MyDriveQuota(it.used, it.total) }
}

// ------------------------------------------------------------------ 迅雷 / 光鸭

/** 迅雷每个接口都要 accessToken + deviceId + captchaToken 三件套；凭证是结构化 JSON */
private object XunleiAdapter : MyDrivePlatformAdapter {
    /**
     * 「我的网盘」这条链路的迅雷请求**全部是网页 token**（登录页走 pan.xunlei.com）。
     *
     * 所以 captcha 必须用网页 9 盐现签：不注入这个刷新器时，[XunleiApi] 会回退 App 通道
     * 的 10 盐签名，服务端判 `client_id not match`，用户看到「验证码与当前登录方式不匹配」——
     * 这正是「退出重登后一直读不了」的前半段原因。
     */
    private val api = XunleiApi().apply {
        webCaptchaRefresher = { deviceId, action ->
            (initWebCaptcha(deviceId, action) as? WebCaptchaInit.Ok)?.captcha
        }
    }
    override val platform = SharePlatform.XUNLEI
    override val capabilities: MyDriveCapabilities = fullCapabilities()

    /**
     * 迅雷个人网盘的根是**空串**，不是 `"0"`：`GET /drive/v1/files?parent_id=` 才表示根目录，
     * 传 `"0"` 会被服务端当成一个不存在的文件夹，直接回「文件(夹)不存在」。
     *
     * 注意别和**分享链路**的根哨兵混为一谈：[CloudDriveService] 给迅雷返回 `"0"`，
     * 那是 `getShare` / `getShareDetail` 里「顶层分享页」的判定值，两套语义不同。
     */
    override val rootDirId: String = ""

    private fun creds(ctx: DriveContext): Triple<String, String, String> {
        val c = XunleiDriveCredential.parse(ctx.credential)?.takeIf { it.isUsable }
            ?: throw IllegalStateException("迅雷网盘凭证无效，请重新登录")
        // device_id 为空时用本地设备指纹兜底，并丢弃与之不匹配的 captcha（详见 requestIdentity）。
        // 空 X-Device-Id 会被服务端判 invalid_argument，用户看到的就是「请求参数错误」。
        val id = c.requestIdentity { XunleiDeviceFingerprint.deviceId() }
        // captcha/init 的 meta 必须带真实 user_id，否则拿到的是降级 token（写操作会被拒）
        api.cacheUserId(c.accessToken, force = true)
        return Triple(id.accessToken, id.deviceId, id.captchaToken)
    }

    override suspend fun list(ctx: DriveContext, dirId: String, cursor: String?): MyDrivePage {
        val (token, device, captcha) = creds(ctx)
        return MyDrivePage((api.getFiles(dirId, token, device, captcha) ?: emptyList())
            .map { it.toMyDriveFile() })
    }

    override suspend fun createFolder(ctx: DriveContext, parentId: String, name: String): String? {
        val (token, device, captcha) = creds(ctx)
        return api.createFolder(name, parentId, token, device, captcha)
    }

    override suspend fun rename(ctx: DriveContext, file: MyDriveFile, newName: String): Boolean {
        val (token, device, captcha) = creds(ctx)
        return api.renameFile(file.id, newName, token, device, captcha)
    }

    override suspend fun move(ctx: DriveContext, files: List<MyDriveFile>, toParentId: String): Boolean {
        val (token, device, captcha) = creds(ctx)
        return api.moveFile(files.map { it.id }, toParentId, token, device, captcha) != null
    }

    override suspend fun delete(ctx: DriveContext, files: List<MyDriveFile>): Boolean {
        val (token, device, captcha) = creds(ctx)
        return api.deleteFiles(files.map { it.id }, token, device, captcha)
    }

    override suspend fun createShare(
        ctx: DriveContext, files: List<MyDriveFile>, passcode: String, expiredType: Int,
    ): MyDriveShareResult? {
        val file = files.firstOrNull() ?: return null
        val (token, device, captcha) = creds(ctx)
        return api.createShare(
            listOf(file.id), file.name, ShareExpire.xunleiDays(expiredType),
            token, device, captcha, passcode,
        )?.toMyDriveShareResult()
    }

    override suspend fun quota(ctx: DriveContext): MyDriveQuota? {
        val (token, device, captcha) = creds(ctx)
        return api.getQuota(token, device, captcha)
            ?.takeIf { it.total > 0 }?.let { MyDriveQuota(it.used, it.total) }
    }
}

/** 光鸭凭证：accessToken|deviceId|deviceSign（或同名 JSON 字段） */
private object GuangYaAdapter : MyDrivePlatformAdapter {
    private val api = GuangYaApi()
    override val platform = SharePlatform.GUANGYA
    override val capabilities: MyDriveCapabilities = fullCapabilities(download = false)
    override val rootDirId: String = GuangYaConstants.ROOT_PARENT_ID

    private fun token(ctx: DriveContext): String =
        CloudCredentialFields.parse(ctx.credential).accessToken.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("光鸭云盘凭证无效，请重新登录")

    override suspend fun list(ctx: DriveContext, dirId: String, cursor: String?): MyDrivePage =
        MyDrivePage(api.listCloudFiles(token(ctx), dirId).map { it.toMyDriveFile() })

    override suspend fun createFolder(ctx: DriveContext, parentId: String, name: String): String? =
        api.createDir(token(ctx), parentId, name)

    override suspend fun rename(ctx: DriveContext, file: MyDriveFile, newName: String): Boolean {
        api.renameFile(token(ctx), file.id, newName)
        return true
    }

    override suspend fun move(ctx: DriveContext, files: List<MyDriveFile>, toParentId: String): Boolean {
        api.moveFiles(token(ctx), files.map { it.id }, toParentId)
        return true
    }

    override suspend fun delete(ctx: DriveContext, files: List<MyDriveFile>): Boolean {
        api.deleteFiles(token(ctx), files.map { it.id })
        return true
    }

    override suspend fun createShare(
        ctx: DriveContext, files: List<MyDriveFile>, passcode: String, expiredType: Int,
    ): MyDriveShareResult? {
        val file = files.firstOrNull() ?: return null
        return api.createShare(
            accessToken = token(ctx),
            fileIds = listOf(file.id),
            title = file.name,
            expireDays = ShareExpire.daysOrNull(expiredType),
            passcode = passcode.takeIf { it.isNotBlank() },
        ).toMyDriveShareResult()
    }

    override suspend fun quota(ctx: DriveContext): MyDriveQuota? {
        val f = CloudCredentialFields.parse(ctx.credential)
        if (f.accessToken.isBlank() || f.deviceId.isBlank() || f.deviceSign.isBlank()) return null
        return api.getQuota(f.accessToken, GuangYaDevice(f.deviceId, f.deviceSign))
            ?.takeIf { it.total > 0 }?.let { MyDriveQuota(it.used, it.total) }
    }
}

// ------------------------------------------------------------------ 蓝奏 / 蓝奏优享

/**
 * 蓝奏云的个人盘接口要 cookie + uid + vei，而账号池只存一份 Cookie；
 * uid/vei 必须先 fetchLoginParams 现取（下游此前完全没有接线）。
 * 这里按 Cookie 的哈希缓存，只缓存 uid/vei，不缓存 Cookie 明文。
 */
private object LanzouAdapter : MyDrivePlatformAdapter {
    private val api = LanzouApi()
    override val platform = SharePlatform.LANZOU
    override val capabilities: MyDriveCapabilities = MyDriveCapabilities(
        list = true, createFolder = true, rename = true, move = true, delete = true,
        createShare = true, quota = false, download = false,
        // 官方链路不支持转存：LanzouResolveRepository.transferFile 直接抛 UnsupportedOperationException
        saveShare = false,
        // 有效期与提取码都由服务端决定：给用户选择器只会让人以为选了就生效
        shareExpiryTiers = emptyList(),
        sharePasscode = false,
    )
    override val rootDirId: String = LanzouConstants.ROOT_FOLDER_ID

    @Volatile
    private var cachedKey: Int = 0

    @Volatile
    private var cachedUid: String = ""

    @Volatile
    private var cachedVei: String = ""

    private suspend fun params(ctx: DriveContext): Pair<String, String> {
        val key = ctx.credential.hashCode()
        if (key == cachedKey && cachedUid.isNotBlank()) return cachedUid to cachedVei
        val p = api.fetchLoginParams(ctx.credential)
            ?: throw IllegalStateException("蓝奏云登录态无效，无法读取个人盘参数，请重新登录")
        cachedKey = key
        cachedUid = p.uid
        cachedVei = p.vei
        return p.uid to p.vei
    }

    private fun numeric(id: String): String = id
        .removePrefix(LanzouConstants.FOLDER_PREFIX)
        .removePrefix(LanzouConstants.FILE_PREFIX)

    override suspend fun list(ctx: DriveContext, dirId: String, cursor: String?): MyDrivePage {
        val (uid, vei) = params(ctx)
        return MyDrivePage(api.listCloudFiles(ctx.credential, uid, vei, dirId)
            .map { it.toMyDriveFile() })
    }

    override suspend fun createFolder(ctx: DriveContext, parentId: String, name: String): String? {
        val (uid, vei) = params(ctx)
        return api.createFolder(ctx.credential, uid, vei, numeric(parentId), name)
    }

    override suspend fun rename(ctx: DriveContext, file: MyDriveFile, newName: String): Boolean {
        val (uid, vei) = params(ctx)
        if (file.isDir) api.renameFolder(ctx.credential, uid, vei, numeric(file.id), newName)
        else api.renameFile(ctx.credential, uid, vei, numeric(file.id), newName)
        return true
    }

    override suspend fun move(ctx: DriveContext, files: List<MyDriveFile>, toParentId: String): Boolean {
        val (uid, vei) = params(ctx)
        files.forEach { api.moveFile(ctx.credential, uid, vei, numeric(toParentId), numeric(it.id)) }
        return true
    }

    override suspend fun delete(ctx: DriveContext, files: List<MyDriveFile>): Boolean {
        val (uid, vei) = params(ctx)
        files.forEach {
            if (it.isDir) api.deleteFolder(ctx.credential, uid, vei, numeric(it.id))
            else api.deleteFile(ctx.credential, uid, vei, numeric(it.id))
        }
        return true
    }

    override suspend fun createShare(
        ctx: DriveContext, files: List<MyDriveFile>, passcode: String, expiredType: Int,
    ): MyDriveShareResult? {
        val file = files.firstOrNull() ?: return null
        val (uid, vei) = params(ctx)
        val info = if (file.isDir) {
            api.createFolderShare(ctx.credential, uid, vei, numeric(file.id))
        } else {
            api.createFileShare(ctx.credential, uid, vei, numeric(file.id))
        }
        // 蓝奏的分享有效期/提取码由服务端决定，UI 上不提供这两项选择
        return info.toMyDriveShareResult()
    }
}

/** 蓝奏优享：没有创建分享接口，其余能力齐全；fid 带 d:/f: 前缀要剥掉 */
private object ILanzouAdapter : MyDrivePlatformAdapter {
    private val api = ILanzouApi()
    override val platform = SharePlatform.ILANZOU
    override val capabilities: MyDriveCapabilities = MyDriveCapabilities(
        list = true, createFolder = true, rename = true, move = true, delete = true,
        createShare = false, quota = true, download = false, saveShare = true,
    )
    override val rootDirId: String = ILanzouConstants.ROOT_FOLDER_ID

    private fun creds(ctx: DriveContext): Pair<String, String> {
        val f = CloudCredentialFields.parse(ctx.credential)
        if (f.appToken.isBlank() || f.uuid.isBlank()) {
            throw IllegalStateException("蓝奏优享凭证格式应为 appToken|uuid，请重新登录")
        }
        return f.appToken to f.uuid
    }

    private fun numeric(id: String): String = id
        .removePrefix(ILanzouConstants.FOLDER_PREFIX)
        .removePrefix(ILanzouConstants.FILE_PREFIX)

    override suspend fun list(ctx: DriveContext, dirId: String, cursor: String?): MyDrivePage {
        val (token, uuid) = creds(ctx)
        return MyDrivePage(api.listFiles(token, uuid, dirId).map { it.toMyDriveFile() })
    }

    override suspend fun createFolder(ctx: DriveContext, parentId: String, name: String): String? {
        val (token, uuid) = creds(ctx)
        return api.createDir(token, uuid, numeric(parentId), name)
    }

    override suspend fun rename(ctx: DriveContext, file: MyDriveFile, newName: String): Boolean {
        val (token, uuid) = creds(ctx)
        if (file.isDir) api.renameFolder(token, uuid, numeric(file.id), newName)
        else api.renameFile(token, uuid, numeric(file.id), newName)
        return true
    }

    override suspend fun move(ctx: DriveContext, files: List<MyDriveFile>, toParentId: String): Boolean {
        val (token, uuid) = creds(ctx)
        api.moveEntries(
            token, uuid,
            folderIds = files.filter { it.isDir }.map { numeric(it.id) },
            fileIds = files.filter { !it.isDir }.map { numeric(it.id) },
            targetId = numeric(toParentId),
        )
        return true
    }

    override suspend fun delete(ctx: DriveContext, files: List<MyDriveFile>): Boolean {
        val (token, uuid) = creds(ctx)
        api.deleteEntries(
            token, uuid,
            folderIds = files.filter { it.isDir }.map { numeric(it.id) },
            fileIds = files.filter { !it.isDir }.map { numeric(it.id) },
        )
        return true
    }

    override suspend fun quota(ctx: DriveContext): MyDriveQuota? {
        val (token, uuid) = creds(ctx)
        return api.getQuota(token, uuid)
            ?.takeIf { it.total > 0 }?.let { MyDriveQuota(it.used, it.total) }
    }
}

/** 10 个平台（GitHub 只读排除，不在表内）；测试可整张替换成假适配器 */
fun defaultMyDriveAdapters(): Map<SharePlatform, MyDrivePlatformAdapter> = mapOf(
    SharePlatform.QUARK to QuarkAdapter,
    SharePlatform.UC to UCAdapter,
    SharePlatform.BAIDU to BaiduAdapter,
    SharePlatform.C139 to C139Adapter,
    SharePlatform.PAN123 to Pan123Adapter,
    SharePlatform.PAN115 to Pan115Adapter,
    SharePlatform.XUNLEI to XunleiAdapter,
    SharePlatform.GUANGYA to GuangYaAdapter,
    SharePlatform.LANZOU to LanzouAdapter,
    SharePlatform.ILANZOU to ILanzouAdapter,
)

package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.model.ShareExpire
import top.mcly99.dl.data.repository.ShareResolveRepository
import top.mcly99.dl.utils.AppLogger

/**
 * P4「我的网盘」的统一调用契约。
 *
 * 与 [CloudDriveApi] 的分工：**解析别人的分享**走 CloudDriveApi；**操作自己网盘里的文件**
 * 走本接口。两条链路都复用移植层的 `*Api.kt` / `*ResolveRepository.kt`，但互不相干，
 * 所以新增这些能力不需要动任何 AGPL 移植文件。
 *
 * 下载与转存刻意放在 [DefaultMyDriveApi] 统一层：
 * - `getDownloadLink(fid, credential)` 是 10 个 Repository 都实现了的公共入口；
 * - `createSession` + `listFiles` + `transferFile` 是「转存到我的网盘」的公共三段式；
 * 交给各平台适配器各写一遍只会制造十份不一致的实现。
 */
interface MyDriveApi {
    fun capabilities(platform: SharePlatform): MyDriveCapabilities
    fun rootDirId(platform: SharePlatform): String

    suspend fun list(ctx: DriveContext, dirId: String, cursor: String? = null): MyDrivePage
    suspend fun createFolder(ctx: DriveContext, parentId: String, name: String): String?
    suspend fun rename(ctx: DriveContext, file: MyDriveFile, newName: String): Boolean
    suspend fun move(ctx: DriveContext, files: List<MyDriveFile>, toParentId: String): Boolean
    suspend fun delete(ctx: DriveContext, files: List<MyDriveFile>): Boolean
    suspend fun createShare(
        ctx: DriveContext,
        files: List<MyDriveFile>,
        passcode: String = "",
        expiredType: Int = ShareExpire.FOREVER,
    ): MyDriveShareResult?
    suspend fun quota(ctx: DriveContext): MyDriveQuota?

    /** 自己网盘里的文件取直链；结果与 P0 的请求头口径一致，可直接交给下载引擎 */
    suspend fun downloadLink(ctx: DriveContext, file: MyDriveFile): CloudLink?

    /** 把一条分享链接里的文件转存到当前目录；部分成功也要如实报数 */
    suspend fun saveShare(
        ctx: DriveContext,
        link: String,
        passcode: String,
        toDirId: String,
    ): Result<MyDriveSaveResult>
}

class DefaultMyDriveApi(
    private val adapters: Map<SharePlatform, MyDrivePlatformAdapter> = defaultMyDriveAdapters(),
    /** 迅雷 access_token 自动刷新后写回账号池的落点，由 AppViewModel 注入 */
    private val onCredentialRefreshed: (SharePlatform, String) -> Unit = { _, _ -> },
    /**
     * 可注入的 Repository 工厂：测试用 fake，生产复用 [CloudDriveService] 已验证过的
     * 构造骨架（迅雷三件套 / 123 与光鸭的 token provider 都在里面）。
     */
    private val shareRepository: (SharePlatform, String) -> ShareResolveRepository = { platform, credential ->
        val service = CloudDriveService()
        // Repository 内部用 lambda 现取 credential，先同步再用；迅雷刷新后的新 token
        // 经 credentialSink 写回账号池，避免「卡片显示已登录但列目录一直 401」。
        service.useCredential(credential)
        service.bindCredentialSink { _, value -> onCredentialRefreshed(platform, value) }
        service.repository(platform)
    },
) : MyDriveApi {

    private fun adapter(platform: SharePlatform): MyDrivePlatformAdapter =
        adapters[platform] ?: throw MyDriveUnsupportedException("我的网盘", platform)

    override fun capabilities(platform: SharePlatform): MyDriveCapabilities =
        adapters[platform]?.capabilities ?: MyDriveCapabilities.NONE

    override fun rootDirId(platform: SharePlatform): String =
        adapters[platform]?.rootDirId.orEmpty()

    override suspend fun list(ctx: DriveContext, dirId: String, cursor: String?): MyDrivePage =
        adapter(ctx.platform).list(ctx, dirId, cursor)

    override suspend fun createFolder(ctx: DriveContext, parentId: String, name: String): String? =
        adapter(ctx.platform).createFolder(ctx, parentId, name)

    override suspend fun rename(ctx: DriveContext, file: MyDriveFile, newName: String): Boolean =
        adapter(ctx.platform).rename(ctx, file, newName)

    override suspend fun move(ctx: DriveContext, files: List<MyDriveFile>, toParentId: String): Boolean =
        adapter(ctx.platform).move(ctx, files, toParentId)

    override suspend fun delete(ctx: DriveContext, files: List<MyDriveFile>): Boolean =
        adapter(ctx.platform).delete(ctx, files)

    override suspend fun createShare(
        ctx: DriveContext,
        files: List<MyDriveFile>,
        passcode: String,
        expiredType: Int,
    ): MyDriveShareResult? = adapter(ctx.platform).createShare(ctx, files, passcode, expiredType)

    override suspend fun quota(ctx: DriveContext): MyDriveQuota? =
        runCatching { adapter(ctx.platform).quota(ctx) }
            .onFailure { e -> AppLogger.warn("MyDriveApi", "读取 ${ctx.platform.name} 空间配额失败: ${e.message}", e) }
            .getOrNull()

    override suspend fun downloadLink(ctx: DriveContext, file: MyDriveFile): CloudLink? {
        if (!capabilities(ctx.platform).download || file.isDir) return null
        val repo = shareRepository(ctx.platform, ctx.credential)
        // 百度列目录时把 id 统一成了 path，但取链接口认的是 fs_id
        val fileId = if (ctx.platform == SharePlatform.BAIDU) {
            file.raw?.fid?.takeIf { it.isNotBlank() } ?: file.id
        } else {
            file.id
        }
        val link = repo.getDownloadLink(fileId, ctx.credential).getOrNull() ?: return null
        val headers = CloudDownloadHeaders.of(
            platform = ctx.platform,
            credential = ctx.credential,
            guestCookie = link.guestCookie,
            downloadUrl = link.downloadUrl,
        )
        return CloudLink(
            url = link.downloadUrl,
            filename = link.filename.ifBlank { file.name },
            // 以列表里看到的文件大小为准：取链接口偶发返回占位值，直接拿来入队
            // 会把 239 MB 的任务显示成 6.83 KB，并且进度/速度一起失真
            size = preferredDownloadSize(file.size, link.size, link.isHls),
            cookie = headers.cookie.orEmpty(),
            referer = headers.referer.orEmpty(),
            userAgent = headers.userAgent,
            origin = headers.origin,
            secChUa = headers.secChUa,
            isHls = link.isHls,
            cleanupDirFid = link.cleanupDirFid,
            platform = ctx.platform,
        )
    }

    override suspend fun saveShare(
        ctx: DriveContext,
        link: String,
        passcode: String,
        toDirId: String,
    ): Result<MyDriveSaveResult> = runCatching {
        if (!capabilities(ctx.platform).saveShare) {
            throw MyDriveUnsupportedException("转存到我的网盘", ctx.platform)
        }
        val repo = shareRepository(ctx.platform, ctx.credential)
        val session = repo.createSession(link, passcode.takeIf { it.isNotBlank() }, ctx.credential)
            .getOrThrow()
        // 分享目录的根 id 与「我的网盘」根 id 是两套语义，沿用解析链路的口径
        val shareRoot = CloudDriveService().rootDirFid(ctx.platform)
        val files = repo.listFiles(session, shareRoot, ctx.credential).getOrThrow()
        val target = toDirId.ifBlank { rootDirId(ctx.platform) }
        var saved = 0
        var failed = 0
        var firstError: String? = null
        for (file in files) {
            repo.transferFile(session, file, target, ctx.credential)
                .onSuccess { saved++ }
                .onFailure { e ->
                    failed++
                    if (firstError == null) firstError = e.message
                }
        }
        // 全部失败才把第一个错误抛给 UI；部分成功照常返回统计
        if (saved == 0 && failed > 0) {
            throw IllegalStateException(firstError ?: "转存失败")
        }
        MyDriveSaveResult(saved, failed, firstError)
    }
}

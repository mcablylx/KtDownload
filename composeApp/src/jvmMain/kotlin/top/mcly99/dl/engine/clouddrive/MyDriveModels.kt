package top.mcly99.dl.engine.clouddrive

import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.data.network.model.ShareExpire
import top.mcly99.dl.data.network.model.ShareFile

/**
 * P4「我的网盘」的自研数据模型。
 *
 * 移植层（data/network 下的各 Api 文件，AGPL-3.0）里每个平台的文件 id 语义都不同：
 * 夸克/UC 用 fid、百度用路径、115 用 cid、蓝奏带 `file_` / `folder_` 前缀。
 * 这一层把它们统一收敛成 [MyDriveFile.id]（String），UI 与 ViewModel 不碰移植层模型。
 * [MyDriveFile.raw] 只供适配器回传平台原生对象（123 云盘的删除接口要整份 ShareFile），
 * 不要拿它做展示。
 */

/** 平台能力位：为 false 的动作，UI 必须隐藏按钮而不是点了再报错 */
data class MyDriveCapabilities(
    val list: Boolean = false,
    val createFolder: Boolean = false,
    val rename: Boolean = false,
    val move: Boolean = false,
    val delete: Boolean = false,
    val createShare: Boolean = false,
    val quota: Boolean = false,
    /** 自己网盘里的文件取直链下载；统一走 ShareResolveRepository.getDownloadLink */
    val download: Boolean = false,
    /** 把别人的分享链接转存到我的网盘；蓝奏云官方链路不支持 */
    val saveShare: Boolean = false,
    /**
     * 创建分享时可选的**有效期码位**（空列表 = 由服务端决定，UI 不显示选择器）。
     *
     * 码位是 [ShareExpire] 的中性码，115 用自己那一段 101..105；适配器负责把码位
     * 翻译成各平台接口的真实语义（天数 / 绝对时间串 / 字符串档位）。
     * **不再由 UI 写死「永久 / 7 天」**：那会让 1 天、30 天、115 的 3/15 天档全都用不了。
     *
     * 默认空列表 = 「不提供选择器」。漏声明时按钮会隐藏，而不是显示一个点了没用的选择器；
     * 支持分享的平台必须在 [fullCapabilities] 里显式给出档位。
     */
    val shareExpiryTiers: List<Int> = emptyList(),
    /** 蓝奏云的提取码由服务端决定，填了也不生效，UI 应隐藏输入框 */
    val sharePasscode: Boolean = true,
) {
    companion object {
        /** 只浏览（GitHub 之外的兜底） */
        val BROWSE_ONLY = MyDriveCapabilities(list = true)

        /** GitHub 是代码仓库，不是个人网盘；从平台列表里直接排除即可 */
        val NONE = MyDriveCapabilities()
    }
}

/** 通用四档：永久 / 1 天 / 7 天 / 30 天（夸克 / UC / 百度 / 139 / 123 / 迅雷 / 光鸭都支持） */
val DEFAULT_SHARE_TIERS: List<Int> = listOf(
    ShareExpire.FOREVER, ShareExpire.ONE_DAY, ShareExpire.SEVEN_DAYS, ShareExpire.THIRTY_DAYS,
)

data class MyDriveFile(
    val id: String,
    val name: String,
    val size: Long = 0L,
    val isDir: Boolean = false,
    val parentId: String = "",
    val modifyTime: String = "",
    /** 适配器内部用；UI 一律只读上面的投影字段 */
    val raw: ShareFile? = null,
)

data class MyDrivePage(
    val files: List<MyDriveFile> = emptyList(),
    /** 分页游标；目前只有 139 / 123 有，其余为 null 表示一次取完 */
    val nextCursor: String? = null,
)

data class MyDriveQuota(val used: Long, val total: Long) {
    val fraction: Float
        get() = if (total <= 0L) 0f else (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/** 创建分享的结果；统一成链接 + 提取码，不再让 UI 认识各平台返回类型 */
data class MyDriveShareResult(
    val url: String,
    val passcode: String,
    val title: String,
    val warning: String? = null,
)

/** 批量转存结果：部分成功也要如实报数，不能只说「成功」 */
data class MyDriveSaveResult(
    val saved: Int,
    val failed: Int,
    val firstError: String? = null,
)

/**
 * 一次操作的凭证上下文。
 *
 * 账号池只存一份 String，但各平台解释为 Cookie / access token / 结构化 JSON /
 * 竖线分隔四种形态；拆字段的事交给 [MyDrivePlatformAdapter]，这里只负责搬运。
 */
data class DriveContext(
    val platform: SharePlatform,
    val credential: String,
    val accountId: String = "",
)

fun ShareFile.toMyDriveFile(): MyDriveFile = MyDriveFile(
    id = fid,
    name = fname,
    size = fsize,
    isDir = isdir,
    parentId = pdirFid,
    modifyTime = modifyTime,
    raw = this,
)

/** 不支持的动作被调用时的统一异常；能力位正确时不应出现 */
class MyDriveUnsupportedException(action: String, platform: SharePlatform) :
    UnsupportedOperationException("$action：${platform.name} 暂不支持该操作")

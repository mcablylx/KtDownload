package top.mcly99.dl.theme

import top.mcly99.dl.data.network.model.ShareExpire

/**
 * P4「我的网盘」文案（自研，双语）。
 *
 * 与 [NetworkStrings] / [MultiAccountStrings] 同理：**刻意不并入 [UiStrings]**。
 * UiStrings 的构造参数已逼近 JVM 方法签名 255 槽上限，再加字段会「编译通过、
 * 运行时 ClassFormatError」（P1 踩过）。本页按 `AppSettings.language` 独立选表。
 */
data class MyDriveStrings(
    // ---- 页面 / 平台选择 ----
    val title: String,
    val subtitle: String,
    val platformSection: String,
    val accountSection: String,
    val notLoggedIn: String,
    val loginHint: String,
    /** 未登录引导区的登录按钮 */
    val loginAction: String,
    /** 参数：账号备注（或默认名）、凭证掩码 */
    val accountLabel: (String, String) -> String,
    val primaryBadge: String,
    val unnamedAccount: String,
    /** 参数：该平台账号数 */
    val accountCount: (Int) -> String,
    // ---- 工具条 ----
    val refresh: String,
    val newFolder: String,
    val rename: String,
    val move: String,
    val delete: String,
    val share: String,
    val download: String,
    val saveShare: String,
    val selectAll: String,
    val clearSelection: String,
    /** 参数：已选条目数 */
    val selectedCount: (Int) -> String,
    val up: String,
    // ---- 列表状态 ----
    val loading: String,
    val emptyDir: String,
    /** 参数：条目数 */
    val itemsCount: (Int) -> String,
    val loadMore: String,
    val loadingMore: String,
    /** 参数：错误信息 */
    val loadFailed: (String) -> String,
    val folderTag: String,
    /** 参数：已用、总量（已格式化） */
    val quotaLabel: (String, String) -> String,
    // ---- 通用按钮 ----
    val cancel: String,
    val confirm: String,
    val nameLabel: String,
    // ---- 新建 / 重命名 ----
    val newFolderTitle: String,
    val newFolderHint: String,
    val renameTitle: String,
    // ---- 删除确认 ----
    val deleteTitle: String,
    /** 参数：待删除条目数 */
    val deleteMessage: (Int) -> String,
    val deleteConfirm: String,
    // ---- 移动 ----
    val moveTitle: String,
    val moveHere: String,
    val moveUp: String,
    val moveEmpty: String,
    // ---- 创建分享 ----
    val shareTitle: String,
    val passcodeLabel: String,
    val passcodeHint: String,
    val expireLabel: String,
    /**
     * 有效期码位 → 展示文案。
     *
     * 各平台档位不同（115 有 3 天 / 15 天、没有 30 天），不能写死两个字段，
     * 否则新增档位时又要在 UI 里加分支。
     */
    val expireOption: (Int) -> String,
    val shareResultTitle: String,
    val shareResultCode: String,
    val copyLink: String,
    // ---- 转存分享 ----
    val saveShareTitle: String,
    val shareLinkLabel: String,
    val shareLinkHint: String,
    val saveShareAction: String,
    /** 参数：成功个数、失败个数 */
    val savedCount: (Int, Int) -> String,
    // ---- 提示 ----
    val noSelection: String,
    val loginRequired: String,
    val operationFailed: (String) -> String,
    val created: (String) -> String,
    val renamed: (String) -> String,
    val moved: (String) -> String,
    val deleted: (Int) -> String,
    val downloaded: (String) -> String,
    /** 参数：选中但取链失败的文件数 */
    val downloadFailed: (Int) -> String,
    /** 参数：成功数、总数 */
    val downloadedPartial: (Int, Int) -> String,
    val saveShareUnsupported: String,
    // ---- 能力说明 ----
    val capabilityHint: String,
)

/** 与 [stringsFor] 回落规则一致：除 `en-US` 外一律中文 */
fun myDriveStrings(language: String): MyDriveStrings =
    if (language.equals("en-US", ignoreCase = true)) enUSMyDrive else zhCNMyDrive

private val zhCNMyDrive = MyDriveStrings(
    title = "我的网盘",
    subtitle = "直接管理自己网盘里的文件，无需打开网页",
    platformSection = "选择网盘",
    accountSection = "当前账号",
    notLoggedIn = "未登录",
    loginHint = "请先到「云盘」页登录该平台，再来这里管理文件",
    loginAction = "登录该平台",
    accountLabel = { name, masked -> "$name · $masked" },
    primaryBadge = "主账号",
    unnamedAccount = "未命名账号",
    accountCount = { "$it 个账号" },
    refresh = "刷新",
    newFolder = "新建文件夹",
    rename = "重命名",
    move = "移动",
    delete = "删除",
    share = "创建分享",
    download = "下载",
    saveShare = "转存分享",
    selectAll = "全选",
    clearSelection = "取消选择",
    selectedCount = { "已选 $it 项" },
    up = "上一级",
    loading = "正在读取目录…",
    emptyDir = "这个文件夹是空的",
    itemsCount = { "共 $it 项" },
    loadMore = "加载更多",
    loadingMore = "正在加载…",
    loadFailed = { "读取失败：$it" },
    folderTag = "文件夹",
    quotaLabel = { used, total -> "已用 $used / $total" },
    cancel = "取消",
    confirm = "确定",
    nameLabel = "名称",
    newFolderTitle = "新建文件夹",
    newFolderHint = "输入文件夹名称",
    renameTitle = "重命名",
    deleteTitle = "确认删除",
    deleteMessage = { "将删除选中的 $it 项。部分平台删除后不可恢复，请确认。" },
    deleteConfirm = "删除",
    moveTitle = "移动到…",
    moveHere = "移动到这里",
    moveUp = "返回上一级目录",
    moveEmpty = "这里没有可选的子文件夹",
    shareTitle = "创建分享链接",
    passcodeLabel = "提取码（留空则不设置）",
    passcodeHint = "4 位以内字符",
    expireLabel = "有效期",
    expireOption = { code -> shareExpiryLabel(code, english = false) },
    shareResultTitle = "分享已创建",
    shareResultCode = "提取码",
    copyLink = "复制链接",
    saveShareTitle = "转存分享到当前目录",
    shareLinkLabel = "分享链接",
    shareLinkHint = "粘贴该平台的分享链接（可带提取码）",
    saveShareAction = "转存",
    savedCount = { ok, failed -> "已转存 $ok 项，失败 $failed 项" },
    noSelection = "请先选择文件",
    loginRequired = "请先登录该平台",
    operationFailed = { "操作失败：$it" },
    created = { "已创建「$it」" },
    renamed = { "已重命名为「$it」" },
    moved = { "已移动到「$it」" },
    deleted = { "已删除 $it 项" },
    downloaded = { "已加入下载「$it」" },
    downloadFailed = { "没有取到下载地址（$it 个文件）：该平台暂不支持直接下载，请用「云盘」页分享后再下载" },
    downloadedPartial = { ok, total -> "已加入 $ok 个，$total 个取链失败" },
    saveShareUnsupported = "该平台不支持转存分享，请使用「云盘」页下载后再上传",
    capabilityHint = "灰色按钮表示该平台没有对应能力（不会点了才报错）",
)

private val enUSMyDrive = MyDriveStrings(
    title = "My Drive",
    subtitle = "Manage files in your own cloud drive without opening a browser",
    platformSection = "Drive",
    accountSection = "Account",
    notLoggedIn = "Not signed in",
    loginHint = "Sign in on the Cloud Drive page first, then come back here",
    loginAction = "Sign in",
    accountLabel = { name, masked -> "$name · $masked" },
    primaryBadge = "Primary",
    unnamedAccount = "Unnamed account",
    accountCount = { "$it accounts" },
    refresh = "Refresh",
    newFolder = "New folder",
    rename = "Rename",
    move = "Move",
    delete = "Delete",
    share = "Share",
    download = "Download",
    saveShare = "Save share",
    selectAll = "Select all",
    clearSelection = "Clear",
    selectedCount = { "$it selected" },
    up = "Up",
    loading = "Loading folder…",
    emptyDir = "This folder is empty",
    itemsCount = { "$it items" },
    loadMore = "Load more",
    loadingMore = "Loading…",
    loadFailed = { "Could not load: $it" },
    folderTag = "Folder",
    quotaLabel = { used, total -> "$used used of $total" },
    cancel = "Cancel",
    confirm = "OK",
    nameLabel = "Name",
    newFolderTitle = "New folder",
    newFolderHint = "Folder name",
    renameTitle = "Rename",
    deleteTitle = "Confirm delete",
    deleteMessage = { "Delete $it selected item(s). Some drives cannot restore deleted files." },
    deleteConfirm = "Delete",
    moveTitle = "Move to…",
    moveHere = "Move here",
    moveUp = "Parent folder",
    moveEmpty = "No subfolders here",
    shareTitle = "Create share link",
    passcodeLabel = "Passcode (leave blank for none)",
    passcodeHint = "Up to 4 characters",
    expireLabel = "Expires",
    expireOption = { code -> shareExpiryLabel(code, english = true) },
    shareResultTitle = "Share created",
    shareResultCode = "Passcode",
    copyLink = "Copy link",
    saveShareTitle = "Save share into this folder",
    shareLinkLabel = "Share link",
    shareLinkHint = "Paste a share link from this drive (passcode optional)",
    saveShareAction = "Save",
    savedCount = { ok, failed -> "$ok saved, $failed failed" },
    noSelection = "Select a file first",
    loginRequired = "Sign in to this drive first",
    operationFailed = { "Failed: $it" },
    created = { "Created \"$it\"" },
    renamed = { "Renamed to \"$it\"" },
    moved = { "Moved into \"$it\"" },
    deleted = { "Deleted $it item(s)" },
    downloaded = { "Added \"$it\" to downloads" },
    downloadFailed = { count ->
        "No download link for $count file(s): this drive does not support direct download. Share it and download from the Cloud Drive page instead."
    },
    downloadedPartial = { ok, total -> "$ok added, $total could not be resolved" },
    saveShareUnsupported = "This drive cannot save shares. Download it on the Cloud Drive page instead.",
    capabilityHint = "Disabled buttons mean the drive has no such capability (no late errors)",
)

/**
 * 有效期码位 → 文案。
 *
 * 115 用自己那一段码位（101..105），中性码只有永久 / 1 天 / 7 天 / 30 天四档；
 * 两边都映射到同一批文案，认不出的码位如实显示数字而不是假装成「永久」。
 */
internal fun shareExpiryLabel(code: Int, english: Boolean): String = when (code) {
    ShareExpire.FOREVER, ShareExpire.PAN115_FOREVER -> if (english) "Never" else "永久有效"
    ShareExpire.ONE_DAY, ShareExpire.PAN115_ONE_DAY -> if (english) "1 day" else "1 天"
    ShareExpire.PAN115_THREE_DAYS -> if (english) "3 days" else "3 天"
    ShareExpire.SEVEN_DAYS, ShareExpire.PAN115_SEVEN_DAYS -> if (english) "7 days" else "7 天"
    ShareExpire.PAN115_FIFTEEN_DAYS -> if (english) "15 days" else "15 天"
    ShareExpire.THIRTY_DAYS -> if (english) "30 days" else "30 天"
    else -> if (english) "Expiry #$code" else "有效期 #$code"
}

package top.mcly99.dl.theme

/**
 * 局域网传输界面补充文案（自研，双语）。
 *
 * 与 [RemoveTaskStrings] / [MyDriveStrings] / [DataToolsStrings] 同一条理由：
 * **不进 [UiStrings]** —— 它的构造参数已逼近 JVM 方法签名 255 槽上限，
 * 再加字段会「编译通过、运行时 ClassFormatError」。
 *
 * 这里装的是 P1-23 / P1-20 补上真实入口之后才需要的几个动作词：
 * 传输历史行上的「取消」「移除记录」、面板上的「打开接收目录」「清空已完成」，
 * 以及端口切换失败但旧服务仍在跑时的那句提示（不能笼统说「启动失败」，
 * 否则用户以为上传已经不可用了）。
 */
data class TransferExtraStrings(
    /** 传输中任务的取消按钮 */
    val cancelTransfer: String,
    /** 已完成 / 失败 / 已取消记录的移除按钮 */
    val removeRecord: String,
    /** 清空全部已结束记录 */
    val clearFinished: String,
    /** 打开接收目录（系统文件管理器） */
    val openReceiveDir: String,
    /** 打开接收目录失败 */
    val openReceiveDirFailed: String,
    /** 参数：仍在监听的端口号 —— 端口切换失败、旧服务继续工作 */
    val portSwitchFailed: (Int) -> String,
    /** 参数：IP —— 设备行上的「复制 IP」（原先复制的是必然 403 的 URL） */
    val copyIp: String,
    /** 参数：IP —— 复制成功提示 */
    val copiedIp: (String) -> String,
    /** 设备未开启 Web 上传时，端口位置显示的占位说明 */
    val webPortUnknown: String,
    /** P1-09：接收服务只在可信局域网内使用，不能暴露到公网 */
    val trustedLanOnly: String,
)

fun transferExtraStrings(language: String): TransferExtraStrings =
    if (language.equals("en-US", ignoreCase = true)) enUSTransferExtra else zhCNTransferExtra

private val zhCNTransferExtra = TransferExtraStrings(
    cancelTransfer = "取消",
    removeRecord = "移除记录",
    clearFinished = "清空已结束",
    openReceiveDir = "打开接收目录",
    openReceiveDirFailed = "无法打开接收目录：文件夹不存在或没有默认文件管理器",
    portSwitchFailed = { port -> "新端口启动失败，仍在 $port 端口运行" },
    copyIp = "复制 IP",
    copiedIp = { ip -> "已复制：$ip" },
    webPortUnknown = "对方的 Web 上传未开启",
    trustedLanOnly = "仅可在可信局域网内使用；请勿把接收地址发到公网",
)

private val enUSTransferExtra = TransferExtraStrings(
    cancelTransfer = "Cancel",
    removeRecord = "Remove record",
    clearFinished = "Clear finished",
    openReceiveDir = "Open receive folder",
    openReceiveDirFailed = "Cannot open the receive folder: it does not exist or no file manager is available",
    portSwitchFailed = { port -> "New port failed to start; still serving on port $port" },
    copyIp = "Copy IP",
    copiedIp = { ip -> "Copied: $ip" },
    webPortUnknown = "Web upload is off on that device",
    trustedLanOnly = "Trusted LAN only; do not share the receive address on the public internet",
)

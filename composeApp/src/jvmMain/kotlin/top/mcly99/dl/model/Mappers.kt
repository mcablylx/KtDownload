package top.mcly99.dl.model

import top.mcly99.dl.utils.AppConstants
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 引擎模型 -> UI 模型的唯一转换入口（P2-2）。
 *
 * 这些函数只做纯数据映射，不依赖 Compose；调用方（AppViewModel 的
 * `derivedStateOf`）负责缓存，避免在每次重组时重复执行。
 */

fun DownloadTask.toUiTask(): UiTask {
    // 总大小可能没取到（服务器 HEAD 403 / chunked 响应，或修复前入队的历史任务）。
    // 已完成的文件用落盘字节兜底：否则 UI 会显示「139 MB / 0 B」+ 进度 0%，
    // 明明下完了却像没下一样。
    val completed = status == DownloadStatus.COMPLETED
    val displaySize = totalSize.takeIf { it > 0 }
        ?: if (completed && downloadedSize > 0) downloadedSize else 0L
    return UiTask(
        id = id,
        name = fileName,
        url = url,
        type = type,
        status = status,
        progress = if (completed) 100 else progressPercent.coerceIn(0, 100),
        size = displaySize,
        downloaded = downloadedSize,
        speed = speed,
        threads = threads,
        createdAtEpochMs = createTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        completedAtEpochMs = completeTime?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
        savePath = savePath.toString(),
        error = errorMessage,
        segmentDone = segmentDone,
        segmentTotal = segmentTotal,
    )
}

fun NetworkDevice.toUiDevice(
    online: Boolean? = null,
    now: Long = System.currentTimeMillis(),
): UiDevice = UiDevice(
    id = id,
    name = name,
    ip = ipAddress,
    port = port,
    // 设备发现协议暂不携带类型；默认按桌面设备展示，后续可在协议中扩展
    type = DeviceType.Desktop,
    online = online ?: (now - lastSeen < AppConstants.DEVICE_OFFLINE_TIMEOUT_MS),
    lastSeenEpochMs = lastSeen,
)

fun TransferTask.toUiTransfer(): UiTransfer = UiTransfer(
    id = id,
    fileName = fileName,
    fileSize = fileSize,
    transferred = transferredSize,
    speed = speed,
    direction = direction,
    deviceId = targetDevice.id,
    deviceName = targetDevice.name,
    status = status,
    progress = (progress * 100).roundToInt().coerceIn(0, 100),
    sizePreparing = sizePreparing,
)

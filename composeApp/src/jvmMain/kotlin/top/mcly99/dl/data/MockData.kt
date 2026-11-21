package top.mcly99.dl.data

import top.mcly99.dl.model.*

/**
 * UI 预览数据（P2-2）。
 *
 * 这里只构造 [UiTask] / [UiDevice] / [UiTransfer]，不再维护第二套“New”模型。
 *
 * 注意：本文件只服务于 @Preview 与默认值，**不要**在这里转发生产逻辑。
 * 设置的读写入口是 [SettingsRepository]（v4-P2-4：原先这里有一层同名的 loadAppSettings /
 * saveAppSettings 转发，导致生产代码从「MockData」里 import 设置 I/O）。
 */

private val gb: Long = 1024L * 1024 * 1024
private val mb: Long = 1024L * 1024

fun initialMockTasks(now: Long = System.currentTimeMillis()): List<UiTask> = listOf(
    UiTask(
        id = "task_1",
        name = "Ubuntu 24.04 LTS Desktop.iso",
        url = "https://releases.ubuntu.com/24.04/ubuntu-24.04-desktop-amd64.iso",
        type = TaskType.Http,
        status = DownloadStatus.DOWNLOADING,
        progress = 67,
        size = (5.2 * gb).toLong(),
        downloaded = (3.484 * gb).toLong(),
        speed = (12.5 * mb).toLong(),
        threads = 8,
        createdAtEpochMs = now - 3_600_000,
        savePath = "/Downloads",
    ),
    UiTask(
        id = "task_2",
        name = "4K.Nature.Documentary.2024.mkv",
        url = "magnet:?xt=urn:btih:example",
        type = TaskType.Bt,
        status = DownloadStatus.DOWNLOADING,
        progress = 23,
        size = (8.7 * gb).toLong(),
        downloaded = (2.001 * gb).toLong(),
        speed = (5.2 * mb).toLong(),
        threads = 4,
        createdAtEpochMs = now - 7_200_000,
        savePath = "/Downloads/Videos",
    ),
    UiTask(
        id = "task_3",
        name = "Online Course - React Advanced.mp4",
        url = "https://example.com/course/playlist.m3u8",
        type = TaskType.M3u8,
        status = DownloadStatus.PAUSED,
        progress = 45,
        size = (2.1 * gb).toLong(),
        downloaded = (0.945 * gb).toLong(),
        speed = 0,
        threads = 4,
        createdAtEpochMs = now - 86_400_000,
        savePath = "/Downloads/Courses",
    ),
    UiTask(
        id = "task_4",
        name = "Software-Installer-v2.5.exe",
        url = "https://example.com/software-v2.5.exe",
        type = TaskType.Http,
        status = DownloadStatus.COMPLETED,
        progress = 100,
        size = 156 * mb,
        downloaded = 156 * mb,
        speed = 0,
        threads = 4,
        createdAtEpochMs = now - 172_800_000,
        completedAtEpochMs = now - 172_000_000,
        savePath = "/Downloads",
    ),
    UiTask(
        id = "task_5",
        name = "Music-Album-2024.zip",
        url = "https://example.com/album.zip",
        type = TaskType.Http,
        status = DownloadStatus.COMPLETED,
        progress = 100,
        size = 320 * mb,
        downloaded = 320 * mb,
        speed = 0,
        threads = 4,
        createdAtEpochMs = now - 259_200_000,
        completedAtEpochMs = now - 258_000_000,
        savePath = "/Downloads/Music",
    ),
    UiTask(
        id = "task_6",
        name = "Game-Mod-Collection.rar",
        url = "https://example.com/mods.rar",
        type = TaskType.Http,
        status = DownloadStatus.QUEUED,
        progress = 0,
        size = (1.2 * gb).toLong(),
        downloaded = 0,
        speed = 0,
        threads = 4,
        createdAtEpochMs = now - 300_000,
        savePath = "/Downloads/Games",
    ),
    UiTask(
        id = "task_7",
        name = "Broken-Link-File.pdf",
        url = "https://example.com/broken.pdf",
        type = TaskType.Http,
        status = DownloadStatus.FAILED,
        progress = 12,
        size = 50 * mb,
        downloaded = 6 * mb,
        speed = 0,
        threads = 4,
        createdAtEpochMs = now - 400_000,
        savePath = "/Downloads",
        error = "连接超时，服务器无响应",
    ),
    UiTask(
        id = "task_8",
        name = "Merging-Package.tar.gz",
        url = "https://example.com/package.tar.gz",
        type = TaskType.Http,
        status = DownloadStatus.MERGING,
        progress = 100,
        size = (2.4 * gb).toLong(),
        downloaded = (2.4 * gb).toLong(),
        speed = 0,
        threads = 8,
        createdAtEpochMs = now - 500_000,
        savePath = "/Downloads",
    ),
)

fun initialMockDevices(now: Long = System.currentTimeMillis()): List<UiDevice> = listOf(
    UiDevice("dev_1", "MacBook Pro", "192.168.1.101", 8080, DeviceType.Desktop, true, now),
    UiDevice("dev_2", "iPhone 15", "192.168.1.102", 8080, DeviceType.Mobile, true, now),
    UiDevice("dev_3", "iPad Air", "192.168.1.103", 8080, DeviceType.Tablet, false, now - 3_600_000),
)

fun initialMockTransfers(): List<UiTransfer> = listOf(
    UiTransfer(
        id = "transfer_1",
        fileName = "Project-Files.zip",
        fileSize = 256 * mb,
        transferred = 128 * mb,
        speed = 45 * mb,
        direction = TransferDirection.Send,
        deviceId = "dev_2",
        deviceName = "iPhone 15",
        status = TransferStatus.TRANSFERRING,
        progress = 50,
    ),
)

fun defaultSettings(): AppSettings = AppSettings()

package top.mcly99.dl.model

import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MappersTest {

    private val createdAt: LocalDateTime = LocalDateTime.of(2026, 10, 23, 12, 0, 0)

    @Test
    fun download_task_maps_to_ui_task_without_status_loss() {
        val task = DownloadTask(
            id = "task-1",
            url = "https://example.com/archive.zip",
            fileName = "archive.zip",
            savePath = Paths.get("/tmp/archive.zip"),
            totalSize = 1_000L,
            downloadedSize = 250L,
            status = DownloadStatus.MERGING,
            speed = 2_048L,
            createTime = createdAt,
            completeTime = null,
            threads = 8,
        )

        val ui = task.toUiTask()

        assertEquals("task-1", ui.id)
        assertEquals("archive.zip", ui.name)
        assertEquals(TaskType.Http, ui.type)
        assertEquals(DownloadStatus.MERGING, ui.status)
        assertEquals(25, ui.progress)
        assertEquals(1_000L, ui.size)
        assertEquals(250L, ui.downloaded)
        assertEquals(2_048L, ui.speed)
        assertEquals(8, ui.threads)
        assertEquals(
            createdAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            ui.createdAtEpochMs,
        )
        assertNull(ui.completedAtEpochMs)
        assertTrue(ui.isActiveTask)
        assertFalse(ui.isFinished)
    }

    @Test
    fun completed_task_exposes_completion_time_and_clamps_progress() {
        val completedAt = createdAt.plusMinutes(5)
        val task = DownloadTask(
            id = "task-2",
            url = "https://example.com/movie.m3u8",
            fileName = "movie.m3u8",
            savePath = Paths.get("/tmp/movie.m3u8"),
            totalSize = 100L,
            downloadedSize = 120L,
            status = DownloadStatus.COMPLETED,
            createTime = createdAt,
            completeTime = completedAt,
            type = TaskType.M3u8,
        )

        val ui = task.toUiTask()

        assertEquals(TaskType.M3u8, ui.type)
        assertEquals(100, ui.progress)
        assertEquals(
            completedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            ui.completedAtEpochMs,
        )
        assertTrue(ui.isFinished)
        assertFalse(ui.isActiveTask)
    }

    @Test
    fun cancelled_task_stays_cancelled_in_ui() {
        val task = DownloadTask(
            id = "task-3",
            url = "https://example.com/file.bin",
            fileName = "file.bin",
            savePath = Paths.get("/tmp/file.bin"),
            status = DownloadStatus.CANCELLED,
            createTime = createdAt,
        )

        val ui = task.toUiTask()

        assertEquals(DownloadStatus.CANCELLED, ui.status)
        assertTrue(ui.isFinished)
    }

    @Test
    fun hls_progress_and_segment_counts_survive_mapping() {
        val task = DownloadTask(
            id = "task-hls",
            url = "https://example.com/movie.m3u8",
            fileName = "movie.mp4",
            savePath = Paths.get("/tmp/movie.mp4"),
            totalSize = 0L,
            downloadedSize = 0L,
            status = DownloadStatus.DOWNLOADING,
            createTime = createdAt,
            type = TaskType.M3u8,
            segmentDone = 3,
            segmentTotal = 12,
        )

        val ui = task.toUiTask()

        assertEquals(TaskType.M3u8, ui.type)
        assertEquals(25, ui.progress)
        assertEquals(3, ui.segmentDone)
        assertEquals(12, ui.segmentTotal)
    }

    /**
     * 总大小未知但已完成（服务器没给 `Content-Length`，或修复前入队的历史任务）：
     * 用落盘字节兜底，否则 UI 会显示「139 MB / 0 B」且进度 0%，看着像没下完。
     */
    @Test
    fun completed_task_without_total_size_falls_back_to_downloaded_bytes() {
        val task = DownloadTask(
            id = "task-unknown-size",
            url = "https://cdn.example.com/Fallout4.part01.exe",
            fileName = "Fallout4.part01.exe",
            savePath = Paths.get("/tmp/Fallout4.part01.exe"),
            totalSize = 0L,
            downloadedSize = 139L * 1024 * 1024,
            status = DownloadStatus.COMPLETED,
            createTime = createdAt,
            completeTime = createdAt.plusMinutes(3),
        )

        val ui = task.toUiTask()

        assertEquals(139L * 1024 * 1024, ui.size, "完成态应用已下载字节兜底总大小")
        assertEquals(100, ui.progress, "完成就是 100%，不该因为大小未知而显示 0%")
    }

    @Test
    fun device_online_flag_can_come_from_view_model_snapshot() {
        val now = 10_000L
        val device = NetworkDevice(
            id = "dev-1",
            name = "PC",
            ipAddress = "192.168.1.2",
            port = 8080,
            lastSeen = now - 1_000L,
        )

        assertTrue(device.toUiDevice(now = now).online)
        assertFalse(device.toUiDevice(online = false, now = now).online)
        assertTrue(device.toUiDevice(online = true, now = now).online)
    }

    @Test
    fun transfer_task_keeps_direction_and_progress() {
        val device = NetworkDevice("dev-1", "Phone", "192.168.1.3", 8080)
        val transfer = TransferTask(
            id = "transfer-1",
            fileName = "photo.jpg",
            fileSize = 1_000L,
            targetDevice = device,
            status = TransferStatus.TRANSFERRING,
            progress = 0.456f,
            speed = 1_024L,
            transferredSize = 456L,
            direction = TransferDirection.Receive,
        )

        val ui = transfer.toUiTransfer()

        assertEquals("transfer-1", ui.id)
        assertEquals("photo.jpg", ui.fileName)
        assertEquals(TransferDirection.Receive, ui.direction)
        assertEquals(TransferStatus.TRANSFERRING, ui.status)
        assertEquals(46, ui.progress)
        assertEquals(456L, ui.transferred)
        assertEquals("dev-1", ui.deviceId)
        assertEquals("Phone", ui.deviceName)
        assertTrue(ui.isActive)
    }
}

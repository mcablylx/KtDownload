package top.mcly99.dl.engine

import top.mcly99.dl.model.DownloadSource
import top.mcly99.dl.model.DownloadStatus
import top.mcly99.dl.model.DownloadTask
import top.mcly99.dl.model.TaskType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Paths
import java.time.LocalDateTime
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TaskStore] 序列化单测。
 *
 * 走 [TaskStore.encode] / [TaskStore.decode] 这对纯函数，而不是 `save` / `load`：
 * 后者写的是 `~/.mclydownload/tasks.properties`，跑一次测试就会覆盖用户真实的任务列表。
 *
 * 中间仍然过一遍 `Properties.store` → 字节 → `Properties.load`，
 * 因为这段 ISO-8859-1 转义才是「中文文件名、带 `;` 的 Referer」真正会出问题的地方。
 */
class TaskStoreTest {

    @Test
    fun cookie_is_never_written_only_a_requirement_flag() {
        // v8-P0-1：Cookie 是会话凭证，不落盘；只留一个「这个任务需要会话」的标记
        val task = task(
            cookie = "SESSDATA=abc%2C123; bili_jct=deadbeef; sid=xyz==",
            referer = "https://page.example.com/watch/1",
        )

        val encoded = TaskStore.encode(listOf(task))

        assertNull(encoded.getProperty("task.0.cookie"))
        assertEquals("1", encoded.getProperty("task.0.cookieRequired"))
        // Referer 不是凭证，仍然保留：HLS 分片要靠它过防盗链
        assertEquals(task.referer, encoded.getProperty("task.0.referer"))

        val restored = roundTrip(listOf(task)).single()

        assertNull(restored.cookie)
        assertTrue(restored.requiresCookie)
        // 恢复成失败而不是暂停：没有凭证的任务点「继续」只会得到一个 403
        assertEquals(DownloadStatus.FAILED, restored.status)
        assertEquals(COOKIE_NOT_PERSISTED_MESSAGE, restored.errorMessage)
    }

    @Test
    fun old_config_with_cookie_key_is_migrated_to_requires_cookie() {
        // 模拟旧版本写下的文件：有 `cookie`、没有 `cookieRequired`
        val encoded = TaskStore.encode(listOf(task(cookie = "a=1")))
        encoded.setProperty("task.0.cookie", "a=1")
        encoded.remove("task.0.cookieRequired")

        assertTrue(TaskStore.hasPersistedCookie(encoded))

        val restored = TaskStore.decode(encoded).single()

        // 凭证本身必须被丢弃，但「需要会话」这个事实要保留，否则升级后会静默 403
        assertNull(restored.cookie)
        assertTrue(restored.requiresCookie)
        assertEquals(DownloadStatus.FAILED, restored.status)
        assertEquals(COOKIE_NOT_PERSISTED_MESSAGE, restored.errorMessage)
    }

    @Test
    fun old_config_without_cookie_keys_reads_back_null() {
        // 更老的用户文件里两个 key 都没有：读回「不需要会话」，与 v6 之前的行为一致
        val encoded = TaskStore.encode(listOf(task(cookie = "a=1")))
        encoded.remove("task.0.cookie")
        encoded.remove("task.0.cookieRequired")
        encoded.remove("task.0.referer")

        val restored = TaskStore.decode(encoded).single()

        assertNull(restored.cookie)
        assertFalse(restored.requiresCookie)
        assertNull(restored.referer)
    }

    @Test
    fun null_cookie_is_not_written_at_all() {
        // 不写 key 而不是写空值：Properties 里没有这个 key 才叫「老配置」，语义更干净
        val encoded = TaskStore.encode(listOf(task(cookie = null, referer = null)))

        assertNull(encoded.getProperty("task.0.cookie"))
        assertNull(encoded.getProperty("task.0.cookieRequired"))
        assertNull(encoded.getProperty("task.0.referer"))
    }

    @Test
    fun full_task_round_trips_field_by_field() {
        val task = DownloadTask(
            id = "id-1",
            url = "https://cdn.example.com/index.m3u8?wsSecret=x&wsTime=1",
            fileName = "第 3 集.mp4",
            savePath = Paths.get("out", "第 3 集.mp4"),
            totalSize = 1_234_567L,
            downloadedSize = 456L,
            status = DownloadStatus.PAUSED,
            threads = 8,
            type = TaskType.M3u8,
            segmentDone = 12,
            segmentTotal = 40,
            createTime = LocalDateTime.of(2026, 10, 28, 12, 30, 45),
            errorMessage = "HTTP 403",
            referer = "https://page.example.com/p/3",
        )

        val restored = roundTrip(listOf(task)).single()

        // 除 speed / completeTime（不持久化）之外全字段相等
        assertEquals(task.copy(speed = 0L, completeTime = null), restored)
    }

    @Test
    fun completed_and_cancelled_tasks_are_dropped() {
        val tasks = listOf(
            task(id = "done").copy(status = DownloadStatus.COMPLETED),
            task(id = "gone").copy(status = DownloadStatus.CANCELLED),
            task(id = "keep").copy(status = DownloadStatus.FAILED),
        )

        val ids = roundTrip(tasks).map { it.id }

        assertEquals(listOf("keep"), ids)
    }

    @Test
    fun downloading_is_restored_as_paused() {
        // 重启后不能静默续跑：原状态 DOWNLOADING 一律恢复成 PAUSED
        val restored = roundTrip(listOf(task().copy(status = DownloadStatus.DOWNLOADING))).single()

        assertEquals(DownloadStatus.PAUSED, restored.status)
    }

    @Test
    fun broken_entry_is_skipped_without_killing_the_rest() {
        // 手改坏一条不能导致整个列表读不出来
        val encoded = TaskStore.encode(listOf(task(id = "a"), task(id = "b")))
        encoded.setProperty("task.count", "2")
        encoded.remove("task.0.url")

        val restored = TaskStore.decode(encoded)

        assertEquals(listOf("b"), restored.map { it.id })
    }

    @Test
    fun count_missing_means_empty_not_crash() {
        assertTrue(TaskStore.decode(Properties()).isEmpty())
    }

    // ---------------------------------------------------------------- 辅助

    @Test
    fun platform_headers_are_persisted_for_cookieless_netdisks() {
        // 139 / 123 / 光鸭 / 蓝奏这类任务不带 Cookie，重启后会照常恢复；
        // 平台专用 UA 与 Origin 跟着丢的话，续传请求会被 CDN 判成网页端。
        val task = task(
            userAgent = "netdisk;12.24.6;piano;android-android;16;JSbridge4.4.0",
            origin = "https://drive.uc.cn",
            secChUa = "\"Chromium\";v=\"100\"",
        )

        val restored = roundTrip(listOf(task)).single()

        assertEquals(task.userAgent, restored.userAgent)
        assertEquals(task.origin, restored.origin)
        assertEquals(task.secChUa, restored.secChUa)
        // 不带 Cookie 的任务不进入「必须重新抓取」的失败态
        assertFalse(restored.requiresCookie)
        assertEquals(DownloadStatus.PAUSED, restored.status)
    }

    @Test
    fun old_config_without_platform_headers_still_loads() {
        // 老文件没有这些 key：读回 null，引擎按原逻辑推断 UA
        val restored = roundTrip(listOf(task(referer = "https://page.example.com/1"))).single()

        assertNull(restored.userAgent)
        assertNull(restored.origin)
        assertNull(restored.secChUa)
    }

    // ---------------------------------------------------------------- C2：多源任务不落盘

    @Test
    fun multi_source_links_are_flagged_but_never_persisted() {
        val task = task(
            altSources = listOf(
                DownloadSource(
                    url = "https://cdn.example.com/a.mp4?account=second",
                    cookie = "SESSDATA=top-secret",
                    userAgent = "netdisk",
                ),
            ),
        )
        val encoded = TaskStore.encode(listOf(task))
        assertEquals("1", encoded.getProperty("task.0.multiSource"))
        // 备选 URL 与其 Cookie 都不能以任何形式出现在文件里
        assertFalse(
            encoded.stringPropertyNames().any { key ->
                val value = encoded.getProperty(key).orEmpty()
                value.contains("top-secret") || value.contains("account=second")
            },
        )

        val restored = roundTrip(listOf(task)).single()
        assertTrue(restored.altSources.isEmpty())
        assertEquals(DownloadStatus.FAILED, restored.status)
        assertEquals(MULTI_SOURCE_NOT_PERSISTED_MESSAGE, restored.errorMessage)
    }

    @Test
    fun single_source_task_is_not_marked_as_multi_source() {
        val encoded = TaskStore.encode(listOf(task()))
        assertNull(encoded.getProperty("task.0.multiSource"))
        assertEquals(DownloadStatus.PAUSED, roundTrip(listOf(task())).single().status)
    }

    // ---------------------------------------------------------------- P0-2：内容指纹

    @Test
    fun content_fingerprint_survives_a_restart() {
        // 不落盘的话，重启后恢复了半截文件的任务在续传时没有任何依据判断远端是否换了文件 ——
        // 而「隔夜续传」恰恰是静默坏文件最容易发生的时刻
        val original = task(etag = "\"686897696a7c876b7e\"", lastModified = "Wed, 21 Oct 2015 07:28:00 GMT")

        val encoded = TaskStore.encode(listOf(original))
        assertEquals(original.etag, encoded.getProperty("task.0.etag"))
        assertEquals(original.lastModified, encoded.getProperty("task.0.lastModified"))

        val restored = roundTrip(listOf(original)).single()
        assertEquals(original.etag, restored.etag)
        assertEquals(original.lastModified, restored.lastModified)
    }

    @Test
    fun task_without_a_fingerprint_stays_without_one() {
        // 老配置文件没有这两个 key：读回 null，续传退化成改动前的行为（不做内容校验）
        val encoded = TaskStore.encode(listOf(task()))
        assertNull(encoded.getProperty("task.0.etag"))

        val restored = roundTrip(listOf(task())).single()
        assertNull(restored.etag)
        assertNull(restored.lastModified)
    }

    private fun task(
        id: String = "id-1",
        cookie: String? = null,
        referer: String? = null,
        userAgent: String? = null,
        origin: String? = null,
        secChUa: String? = null,
        altSources: List<DownloadSource> = emptyList(),
        etag: String? = null,
        lastModified: String? = null,
    ) = DownloadTask(
        id = id,
        url = "https://cdn.example.com/a.mp4",
        fileName = "a.mp4",
        savePath = Paths.get("out", "a.mp4"),
        status = DownloadStatus.PAUSED,
        createTime = LocalDateTime.of(2026, 10, 28, 12, 0, 0),
        referer = referer,
        userAgent = userAgent,
        origin = origin,
        secChUa = secChUa,
        cookie = cookie,
        altSources = altSources,
        etag = etag,
        lastModified = lastModified,
    )

    /** 真写一遍字节再读回来，覆盖 Properties 的转义层 */
    private fun roundTrip(tasks: List<DownloadTask>): List<DownloadTask> {
        val bytes = ByteArrayOutputStream().use { out ->
            TaskStore.encode(tasks).store(out, "KtDownload download tasks")
            out.toByteArray()
        }
        val props = Properties().apply { ByteArrayInputStream(bytes).use { load(it) } }
        return TaskStore.decode(props)
    }
}

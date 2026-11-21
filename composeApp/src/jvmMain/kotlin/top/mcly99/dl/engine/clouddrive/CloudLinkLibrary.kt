package top.mcly99.dl.engine.clouddrive

import org.json.JSONArray
import org.json.JSONObject
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.utils.AppConstants
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * 一条已解析的分享链接 / 收藏项。
 *
 * ktdown 自有模型（D-29）：上游把收藏存进 SQLite 的 `BookmarkEntity` 并配了独立的
 * 收藏页与分类弹窗，本实现只保留「链接 + 标题 + 平台 + 时间」四个必要字段，
 * 用本地 JSON 文件持久化，不引入上游 AGPL 代码，也不引入数据库依赖。
 *
 * @param url 实际用于解析的链接。中文口令解析成功后存的是**换出来的真实链接**，
 *   复用时就不必再请求一次第三方口令服务。
 * @param platform 仅用于展示徽标；老数据没有该字段时为 null。
 */
data class CloudLinkEntry(
    val url: String,
    val title: String,
    val platform: SharePlatform?,
    val savedAt: Long,
)

/**
 * 网盘解析历史与收藏的本地存储。
 *
 * 设计约束：
 * - 分享文案常带提取码，属于用户数据，只写本机，绝不发往任何第三方；
 * - 与凭证存储同一套写入纪律：先写临时文件再原子替换，POSIX 下 `0700` 目录 / `0600` 文件；
 * - 文件损坏、字段缺失、平台枚举变更时**静默降级**为空/跳过坏条目，绝不阻塞解析主流程；
 * - [dir] 与 [clock] 可注入，单测用临时目录和固定时钟，不碰真实用户目录。
 */
class CloudLinkLibrary(
    private val dir: Path = AppConstants.APP_DATA_DIR,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val file: Path get() = dir.resolve(FILE_NAME)

    private var history: List<CloudLinkEntry> = emptyList()
    private var bookmarks: List<CloudLinkEntry> = emptyList()

    init {
        runCatching { load() }
    }

    fun history(): List<CloudLinkEntry> = synchronized(lock) { history.toList() }

    fun bookmarks(): List<CloudLinkEntry> = synchronized(lock) { bookmarks.toList() }

    fun isBookmarked(url: String): Boolean = synchronized(lock) {
        bookmarks.any { sameUrl(it.url, url) }
    }

    /** 解析成功后记一条；同一链接只保留最新时间，历史最多 [HISTORY_LIMIT] 条 */
    fun recordResolved(url: String, title: String, platform: SharePlatform?) {
        synchronized(lock) {
            val entry = newEntry(url, title, platform) ?: return
            history = listOf(entry) + history
                .filterNot { sameUrl(it.url, entry.url) }
                .take(HISTORY_LIMIT - 1)
            persist()
        }
    }

    /** 收藏（已存在则刷新标题 / 时间）；返回是否新增 */
    fun addBookmark(url: String, title: String, platform: SharePlatform?): Boolean {
        synchronized(lock) {
            val entry = newEntry(url, title, platform) ?: return false
            val existed = bookmarks.any { sameUrl(it.url, entry.url) }
            bookmarks = listOf(entry) + bookmarks
                .filterNot { sameUrl(it.url, entry.url) }
                .take(BOOKMARK_LIMIT - 1)
            persist()
            return !existed
        }
    }

    /** 切换当前链接的收藏状态；返回切换后是否已收藏 */
    fun toggleBookmark(url: String, title: String, platform: SharePlatform?): Boolean {
        synchronized(lock) {
            return if (isBookmarked(url)) {
                bookmarks = bookmarks.filterNot { sameUrl(it.url, url) }
                persist()
                false
            } else {
                addBookmark(url, title, platform)
                true
            }
        }
    }

    fun removeHistory(url: String) {
        synchronized(lock) {
            val next = history.filterNot { sameUrl(it.url, url) }
            if (next.size == history.size) return
            history = next
            persist()
        }
    }

    fun removeBookmark(url: String) {
        synchronized(lock) {
            val next = bookmarks.filterNot { sameUrl(it.url, url) }
            if (next.size == bookmarks.size) return
            bookmarks = next
            persist()
        }
    }

    fun clearHistory() {
        synchronized(lock) {
            if (history.isEmpty()) return
            history = emptyList()
            persist()
        }
    }

    fun clearBookmarks() {
        synchronized(lock) {
            if (bookmarks.isEmpty()) return
            bookmarks = emptyList()
            persist()
        }
    }

    // ---------------------------------------------------------------- 序列化

    private fun newEntry(url: String, title: String, platform: SharePlatform?): CloudLinkEntry? {
        val clean = url.trim()
        if (clean.isEmpty()) return null
        return CloudLinkEntry(clean, title.trim().ifBlank { clean }, platform, clock())
    }

    private fun load() {
        if (!Files.isRegularFile(file)) return
        val root = runCatching {
            JSONObject(String(Files.readAllBytes(file), StandardCharsets.UTF_8))
        }.getOrNull() ?: return
        history = decode(root.optJSONArray("history"), HISTORY_LIMIT)
        bookmarks = decode(root.optJSONArray("bookmarks"), BOOKMARK_LIMIT)
    }

    private fun decode(array: JSONArray?, limit: Int): List<CloudLinkEntry> {
        if (array == null) return emptyList()
        // 按 url 去重：老版本 / 手工编辑可能写进重复项，保留时间最新的那条
        val byUrl = LinkedHashMap<String, CloudLinkEntry>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val url = o.optString("url").trim()
            if (url.isEmpty()) continue
            val platform = runCatching {
                SharePlatform.valueOf(o.optString("platform"))
            }.getOrNull()
            val entry = CloudLinkEntry(
                url = url,
                title = o.optString("title").ifBlank { url },
                platform = platform,
                savedAt = o.optLong("savedAt", 0L),
            )
            val old = byUrl[url]
            if (old == null || old.savedAt < entry.savedAt) byUrl[url] = entry
        }
        return byUrl.values.sortedByDescending { it.savedAt }.take(limit)
    }

    private fun encode(entries: List<CloudLinkEntry>): JSONArray = JSONArray().apply {
        entries.forEach {
            put(
                JSONObject()
                    .put("url", it.url)
                    .put("title", it.title)
                    .put("platform", it.platform?.name.orEmpty())
                    .put("savedAt", it.savedAt),
            )
        }
    }

    private fun persist() {
        if (history.isEmpty() && bookmarks.isEmpty()) {
            runCatching { Files.deleteIfExists(file) }
            return
        }
        val root = JSONObject()
            .put("version", 1)
            .put("history", encode(history))
            .put("bookmarks", encode(bookmarks))
        runCatching {
            Files.createDirectories(dir)
            restrict(dir, DIRECTORY_PERMISSIONS)
            val tmp = Files.createTempFile(dir, FILE_NAME, ".tmp")
            try {
                Files.write(tmp, root.toString().toByteArray(StandardCharsets.UTF_8))
                restrict(tmp, FILE_PERMISSIONS)
                try {
                    Files.move(
                        tmp, file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
    }

    /** Windows / 非 POSIX 文件系统没有这些权限位，失败静默忽略 */
    private fun restrict(path: Path, permissions: Set<PosixFilePermission>) {
        runCatching {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, permissions)
            }
        }
    }

    private companion object {
        const val FILE_NAME = "cloud-links.json"
        const val HISTORY_LIMIT = 30
        const val BOOKMARK_LIMIT = 100

        val DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        val FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )

        /** 链接比较忽略大小写与首尾空白，但不忽略查询参数（提取码就挂在后面） */
        fun sameUrl(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)
    }
}

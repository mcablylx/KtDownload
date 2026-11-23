package top.mcly99.dl.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.mcly99.dl.data.network.SharePlatform
import top.mcly99.dl.engine.clouddrive.CloudDriveAccountStore
import top.mcly99.dl.engine.clouddrive.CloudDriveCatalog
import top.mcly99.dl.engine.clouddrive.CloudLink
import top.mcly99.dl.engine.clouddrive.DefaultMyDriveApi
import top.mcly99.dl.engine.clouddrive.DriveContext
import top.mcly99.dl.engine.clouddrive.MyDriveApi
import top.mcly99.dl.engine.clouddrive.MyDriveCapabilities
import top.mcly99.dl.engine.clouddrive.MyDriveFile
import top.mcly99.dl.engine.clouddrive.MyDriveQuota
import top.mcly99.dl.engine.clouddrive.MyDriveShareResult
import top.mcly99.dl.theme.MyDriveStrings
import top.mcly99.dl.theme.myDriveStrings

/** 「我的网盘」页的弹窗状态；输入内容留在 UI 层，ViewModel 只管动作与数据 */
sealed interface MyDriveDialog {
    data object None : MyDriveDialog
    data object CreateFolder : MyDriveDialog
    data class Rename(val file: MyDriveFile) : MyDriveDialog
    data class Delete(val files: List<MyDriveFile>) : MyDriveDialog
    data object Move : MyDriveDialog
    data class Share(val files: List<MyDriveFile>) : MyDriveDialog
    data object SaveShare : MyDriveDialog
    data class ShareResult(val result: MyDriveShareResult) : MyDriveDialog
}

/**
 * P4「我的网盘」状态机（自研，不移植上游 10 个 *CloudViewModel）。
 *
 * 与 [CloudDriveViewModel] 并列：那边解析别人的分享，这边操作自己网盘里的文件。
 * 下载仍统一由 [AppViewModel] 通过 [onDownload] 注入，避免出现第二条入队路径。
 *
 * 账号取自 P3 的账号池，支持切换非主账号；凭证只在内存里搬运，不落盘、不进日志。
 */
class MyDriveViewModel(
    private val api: MyDriveApi = DefaultMyDriveApi(),
    private val accounts: CloudDriveAccountStore = CloudDriveAccountStore.shared,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val stringsProvider: () -> MyDriveStrings = { myDriveStrings("zh-CN") },
) {
    private fun t(): MyDriveStrings = stringsProvider()

    /** GitHub 是代码仓库不是个人网盘，从入口里直接排除 */
    val platforms = CloudDriveCatalog.platforms.filter { it.platform != SharePlatform.GITHUB }

    var platform by mutableStateOf<SharePlatform?>(null)
        private set
    var accountId by mutableStateOf<String?>(null)
        private set
    var files by mutableStateOf(emptyList<MyDriveFile>())
        private set
    /** 下一页游标：只有 139 / 123 会返回，null 表示目录已取完 */
    var nextCursor by mutableStateOf<String?>(null)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    val hasMore: Boolean get() = nextCursor != null
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var quota by mutableStateOf<MyDriveQuota?>(null)
        private set
    var selectedIds by mutableStateOf(emptySet<String>())
        private set
    /** 面包屑：(目录 id, 目录名)；空栈表示根目录 */
    var pathStack by mutableStateOf(emptyList<Pair<String, String>>())
        private set
    var dialog by mutableStateOf<MyDriveDialog>(MyDriveDialog.None)
        private set

    // ---- 移动对话框自己的目录栈，不影响主页面的位置 ----
    var movePathStack by mutableStateOf(emptyList<Pair<String, String>>())
        private set
    var moveFolders by mutableStateOf(emptyList<MyDriveFile>())
        private set

    /**
     * 列目录请求的代次（P0-3）。
     *
     * 每次导航（切平台 / 切账号 / 进目录 / 返回上级 / 操作后刷新）都自增，请求返回后比对，
     * 不一致就整份丢弃。没有这一步的话，慢网下「先点平台 A、再点平台 B」时 A 的响应
     * 会把 A 的文件与游标写进 B 的列表；更糟的是后续删除 / 移动拿的是 B 的凭证
     * （`ctx()` 现取当前平台）× A 的文件 id，各平台 id 语义不同，可能命中别人的文件。
     *
     * 只在主线程（`scope`）里读写，不需要额外同步。
     */
    private var loadGeneration = 0

    /** 移动对话框的目录列表有自己的代次：它与主列表是两条独立的请求链 */
    private var moveGeneration = 0

    /**
     * 作废所有在途的目录请求，返回新代次。
     *
     * `loadingMore` 必须在这里一起复位：它的 finally 只在「代次仍然匹配」时才清标志，
     * 旧请求被作废后那个 finally 会直接跳过，标志留着 true 会让新目录永远翻不了页。
     */
    private fun invalidateLoads(): Int {
        loadGeneration++
        loadingMore = false
        return loadGeneration
    }

    private fun invalidateMoveLoads(): Int {
        moveGeneration++
        return moveGeneration
    }

    /** AppViewModel 注入：与「云盘」页共用同一个下载入队方法 */
    var onDownload: (CloudLink) -> String? = { null }

    val capabilities: MyDriveCapabilities
        get() = api.capabilities(platform ?: SharePlatform.QUARK)

    val currentDirId: String
        get() = pathStack.lastOrNull()?.first ?: platform?.let { api.rootDirId(it) }.orEmpty()

    val moveCurrentDirId: String
        get() = movePathStack.lastOrNull()?.first ?: platform?.let { api.rootDirId(it) }.orEmpty()

    val selectedFiles: List<MyDriveFile>
        get() = files.filter { it.id in selectedIds }

    val accountsForPlatform: List<top.mcly99.dl.engine.clouddrive.CloudAccount>
        get() = platform?.let { accounts.accountsFor(it) }.orEmpty()

    /** 侧栏平台卡片用：与当前选中的平台无关，逐个平台查账号池 */
    fun configuredFor(value: SharePlatform): Boolean = accounts.isConfigured(value)

    fun accountCountFor(value: SharePlatform): Int = accounts.accountCount(value)

    fun accountLabel(index: Int): String {
        val p = platform ?: return ""
        val account = accountsForPlatform.getOrNull(index) ?: return ""
        val name = account.remark.ifBlank { account.nickname.ifBlank { t().unnamedAccount } }
        return t().accountLabel(name, accounts.maskCredential(account))
    }

    val isLoggedIn: Boolean
        get() = platform?.let { credential().isNotBlank() } == true

    val canCreateFolder: Boolean get() = capabilities.createFolder && isLoggedIn
    val canRename: Boolean get() = capabilities.rename && selectedFiles.size == 1 && isLoggedIn
    val canMove: Boolean get() = capabilities.move && selectedFiles.isNotEmpty() && isLoggedIn
    val canDelete: Boolean get() = capabilities.delete && selectedFiles.isNotEmpty() && isLoggedIn
    val canShare: Boolean get() = capabilities.createShare && selectedFiles.size == 1 && isLoggedIn
    val canDownload: Boolean get() = capabilities.download && selectedFiles.any { !it.isDir } && isLoggedIn
    val canSaveShare: Boolean get() = capabilities.saveShare && isLoggedIn

    /** 分享弹窗可选的有效期档位（码位由适配器映射到各平台接口语义） */
    val shareExpiryTiers: List<Int> get() = capabilities.shareExpiryTiers

    /** 蓝奏云的提取码由服务端决定，UI 上直接隐藏输入框而不是填了不生效 */
    val shareSupportsPasscode: Boolean get() = capabilities.sharePasscode

    private fun credential(): String {
        val p = platform ?: return ""
        val id = accountId
        return if (id == null) accounts.credentialFor(p)
        else accounts.accountById(p, id)?.credential.orEmpty()
    }

    private fun ctx(): DriveContext = DriveContext(
        platform = platform ?: SharePlatform.QUARK,
        credential = credential(),
        accountId = accountId.orEmpty(),
    )

    // ---------------------------------------------------------------- 导航

    fun selectPlatform(value: SharePlatform) {
        platform = value
        accountId = null
        pathStack = emptyList()
        files = emptyList()
        nextCursor = null
        selectedIds = emptySet()
        movePathStack = emptyList()
        moveFolders = emptyList()
        quota = null
        error = null
        notice = null
        dialog = MyDriveDialog.None
        refresh()
    }

    fun selectAccount(id: String?) {
        accountId = id
        pathStack = emptyList()
        files = emptyList()
        nextCursor = null
        selectedIds = emptySet()
        refresh()
    }

    fun refresh() {
        if (platform == null) return
        val generation = invalidateLoads()
        val dirId = currentDirId
        scope.launch {
            loadDir(dirId, generation)
            loadQuota(generation)
        }
    }

    /**
     * 账号池被「云盘」页改动后调用（登录 / 登出 / 增删账号 / 切主账号）。
     *
     * 账号池 [CloudDriveAccountStore] 是普通 `MutableList`，不是 Compose state，
     * 所以那边 `accounts.set()` 之后本页的 [isLoggedIn] / [configuredFor] 不会自动重算。
     * 这里递增一个 state 强制读取方重组，并在已选中平台时重新列目录：
     * 登录成功后从「请先登录」的 error 直接变成文件列表，不用用户手动点刷新。
     *
     * 只通知、不写账号池 —— 写入仍由 CloudDriveViewModel 负责，避免出现第二条写路径。
     */
    private var accountRevision by mutableStateOf(0)
    val accountsRevision: Int get() = accountRevision

    fun onAccountsChanged() {
        accountRevision++
        if (platform != null) refresh()
    }

    fun openDir(file: MyDriveFile) {
        if (!file.isDir) return
        pathStack = pathStack + (file.id to file.name)
        selectedIds = emptySet()
        nextCursor = null
        val generation = invalidateLoads()
        val dirId = currentDirId
        scope.launch { loadDir(dirId, generation) }
    }

    fun goUp() {
        if (pathStack.isEmpty()) return
        pathStack = pathStack.dropLast(1)
        selectedIds = emptySet()
        nextCursor = null
        val generation = invalidateLoads()
        val dirId = currentDirId
        scope.launch { loadDir(dirId, generation) }
    }

    /**
     * 取下一页。
     *
     * 139 / 123 的列目录接口是分页的（`MyDrivePage.nextCursor`），早先只取了第一页，
     * 于是这两个平台上「第二页之后的文件」既看不到也选不中 —— 不是显示问题，是能力缺失。
     * 这里按游标续取，并按 id 去重：翻页期间若有新文件插入，同一条目可能出现在两页里。
     */
    fun loadMore() {
        scope.launch {
            // 检查与置位一起放在 launch 内的第一个挂起点之前（P1-6）：
            // 否则连点两次都会在 launch 外看到 loadingMore=false，并发翻页导致重复/错乱。
            val cursor = nextCursor ?: return@launch
            if (loading || loadingMore) return@launch
            loadingMore = true
            val dirId = currentDirId
            // 记下当前代次：翻页期间用户可能切了平台 / 目录，这一页属于旧目录，绝不能拼到新列表上
            val generation = loadGeneration
            try {
                val page = withContext(ioDispatcher) { api.list(ctx(), dirId, cursor) }
                if (generation != loadGeneration) return@launch
                val seen = files.mapTo(HashSet()) { it.id }
                files = files + page.files.filter { seen.add(it.id) }
                nextCursor = page.nextCursor
            } catch (e: Exception) {
                if (generation == loadGeneration) notice = t().loadFailed(e.message ?: e.javaClass.simpleName)
            } finally {
                // 被作废的那一页不要动标志位：作废时 invalidateLoads() 已经复位过，
                // 而新代次的请求可能正把 loadingMore 置为 true
                if (generation == loadGeneration) loadingMore = false
            }
        }
    }

    fun toggleSelect(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun selectAll() {
        // 只选文件不选目录：单行点击目录是「打开」而非选择，全选若把目录算进去，
        // 会让 canDelete/canMove 对目录生效，与单行交互语义不一致且可能误删整目录（P1-E3）。
        selectedIds = files.filter { !it.isDir }.map { it.id }.toSet()
    }

    fun clearSelection() {
        selectedIds = emptySet()
    }

    fun dismissDialog() {
        dialog = MyDriveDialog.None
    }

    // ---------------------------------------------------------------- 动作

    fun openCreateFolder() {
        if (!canCreateFolder) return
        dialog = MyDriveDialog.CreateFolder
    }

    fun submitCreateFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !canCreateFolder) return
        runOperation {
            api.createFolder(ctx(), currentDirId, trimmed)
            val generation = invalidateLoads()
            loadDir(currentDirId, generation)
            loadQuota(generation)
            dialog = MyDriveDialog.None
            notice = t().created(trimmed)
        }
    }

    fun openRename() {
        val file = selectedFiles.singleOrNull() ?: return
        if (!canRename) return
        dialog = MyDriveDialog.Rename(file)
    }

    fun submitRename(file: MyDriveFile, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || !canRename) return
        runOperation {
            if (!api.rename(ctx(), file, trimmed)) fail(t().operationFailed("rename"))
            loadDir(currentDirId, invalidateLoads())
            dialog = MyDriveDialog.None
            notice = t().renamed(trimmed)
        }
    }

    fun openDelete() {
        val selected = selectedFiles
        if (selected.isEmpty() || !canDelete) return
        dialog = MyDriveDialog.Delete(selected)
    }

    /** 删除必须经二次确认；UI 直接调本方法而没有 dialog 时也不会误删 */
    fun confirmDelete() {
        val targets = (dialog as? MyDriveDialog.Delete)?.files ?: return
        if (!canDelete) return
        runOperation {
            if (api.delete(ctx(), targets)) {
                selectedIds = emptySet()
                val generation = invalidateLoads()
                loadDir(currentDirId, generation)
                // 删除释放的是服务端容量，列表刷新不会带来新数值；必须重新取一次，
                // 否则顶部「已用容量」会一直停在删除前的快照（截图里的问题）。
                loadQuota(generation)
                notice = t().deleted(targets.size)
            }
            dialog = MyDriveDialog.None
        }
    }

    fun openMove() {
        val selected = selectedFiles
        if (selected.isEmpty() || !canMove) return
        dialog = MyDriveDialog.Move
        movePathStack = pathStack
        val generation = invalidateMoveLoads()
        scope.launch { loadMoveFolders(generation) }
    }

    fun openMoveFolder(folder: MyDriveFile) {
        movePathStack = movePathStack + (folder.id to folder.name)
        val generation = invalidateMoveLoads()
        scope.launch { loadMoveFolders(generation) }
    }

    fun moveGoUp() {
        if (movePathStack.isEmpty()) return
        movePathStack = movePathStack.dropLast(1)
        val generation = invalidateMoveLoads()
        scope.launch { loadMoveFolders(generation) }
    }

    fun confirmMove() {
        val targets = selectedFiles
        if (targets.isEmpty() || !canMove) return
        val dirName = movePathStack.lastOrNull()?.second ?: t().title
        runOperation {
            api.move(ctx(), targets, moveCurrentDirId)
            selectedIds = emptySet()
            loadDir(currentDirId, invalidateLoads())
            dialog = MyDriveDialog.None
            notice = t().moved(dirName)
        }
    }

    fun openShare() {
        val selected = selectedFiles
        if (selected.size != 1 || !canShare) return
        dialog = MyDriveDialog.Share(selected)
    }

    fun submitShare(files: List<MyDriveFile>, passcode: String, expiredType: Int) {
        if (!canShare || files.size != 1) return
        runOperation {
            val result = api.createShare(ctx(), files, passcode.trim(), expiredType)
                ?: fail(t().operationFailed("share"))
            dialog = MyDriveDialog.ShareResult(result)
        }
    }

    fun downloadSelected() {
        val targets = selectedFiles.filter { !it.isDir }
        if (targets.isEmpty() || !canDownload) return
        runOperation {
            val links = targets.mapNotNull { file ->
                withContext(ioDispatcher) { api.downloadLink(ctx(), file) }
            }
            // 一个链接都没取到就不能报「已加入下载」：用户会去任务列表找一个不存在的任务（P1-15）。
            // 取链失败在 api 层是被 getOrNull 吞掉的，能走到这里说明确实没有可下载的地址。
            if (links.isEmpty()) {
                notice = t().downloadFailed(targets.size)
                return@runOperation
            }
            val queued = links.count { onDownload(it) != null }
            if (queued == 0) {
                notice = t().downloadFailed(targets.size)
                return@runOperation
            }
            notice = if (queued == links.size) {
                t().downloaded(targets.first().name)
            } else {
                t().downloadedPartial(queued, links.size)
            }
        }
    }

    fun openSaveShare() {
        if (!canSaveShare) return
        dialog = MyDriveDialog.SaveShare
    }

    fun submitSaveShare(link: String, passcode: String) {
        if (link.isBlank() || !canSaveShare) return
        runOperation {
            val result = api.saveShare(ctx(), link.trim(), passcode.trim(), currentDirId).getOrThrow()
            val generation = invalidateLoads()
            loadDir(currentDirId, generation)
            // 转存会占用容量，同样要重新取，不能沿用进页面时的旧快照
            loadQuota(generation)
            dialog = MyDriveDialog.None
            notice = t().savedCount(result.saved, result.failed)
        }
    }

    fun clearNotice() {
        notice = null
    }

    fun shutdown() {
        scope.cancel()
    }

    // ---------------------------------------------------------------- 内部

    private suspend fun loadDir(dirId: String, generation: Int) {
        if (platform == null) return
        // 排队期间已经被更新的导航取代：连状态都别碰，否则新目录的 loading / error 会被旧请求改写
        if (generation != loadGeneration) return
        if (credential().isBlank()) {
            files = emptyList()
            loading = false
            error = t().loginRequired
            return
        }
        loading = true
        error = null
        try {
            val page = withContext(ioDispatcher) { api.list(ctx(), dirId) }
            if (generation != loadGeneration) return
            files = page.files
            nextCursor = page.nextCursor
            selectedIds = selectedIds.intersect(files.map { it.id }.toSet())
        } catch (e: Exception) {
            if (generation != loadGeneration) return
            files = emptyList()
            nextCursor = null
            selectedIds = emptySet()
            error = t().loadFailed(e.message ?: e.javaClass.simpleName)
        } finally {
            if (generation == loadGeneration) loading = false
        }
    }

    /**
     * 容量是独立于目录列表的一次网络请求，删除 / 转存后必须重取。
     *
     * 带 [generation] 是因为它和列目录并发：慢网络下「进入页面取到的旧容量」可能晚于
     * 「删除后取到的新容量」返回，不带代次会把已释放的容量又写回去。
     */
    private suspend fun loadQuota(generation: Int) {
        if (generation != loadGeneration) return
        if (!capabilities.quota || !isLoggedIn) {
            quota = null
            return
        }
        val value = runCatching { withContext(ioDispatcher) { api.quota(ctx()) } }.getOrNull()
        if (generation == loadGeneration) quota = value
    }

    private suspend fun loadMoveFolders(generation: Int) {
        if (!isLoggedIn) {
            if (generation == moveGeneration) moveFolders = emptyList()
            return
        }
        val dirId = moveCurrentDirId
        val result = runCatching {
            // 移动对话框只看文件夹，但同样要翻页取全，否则 139 / 123 上
            // 「目标文件夹在第二页」时用户根本没得选
            val collected = LinkedHashMap<String, MyDriveFile>()
            var cursor: String? = null
            var pages = 0
            do {
                val page = withContext(ioDispatcher) { api.list(ctx(), dirId, cursor) }
                // 每翻一页都看一眼代次：最多 20 页就是 20 次请求，用户很可能中途换了目标目录
                if (generation != moveGeneration) return
                page.files.filter { it.isDir }.forEach { collected[it.id] = it }
                cursor = page.nextCursor
                pages++
            } while (cursor != null && pages < MOVE_FOLDER_MAX_PAGES)
            collected.values.toList()
        }
        if (generation != moveGeneration) return
        moveFolders = result.getOrDefault(emptyList())
    }

    /** 所有写操作共用一个外壳：串行、吞异常、给 notice，不把异常抛到 Compose 层 */
    private fun runOperation(block: suspend () -> Unit) {
        scope.launch {
            // 判断与置位必须一起放在 launch 内的第一个挂起点之前：
            // Dispatchers.Main 下 launch 是 post，若判断留在 launch 外，同事件循环内
            // 连点两次都会看到 busy=false，造成两个写操作并发（P1-6）。
            if (busy) return@launch
            busy = true
            try {
                block()
            } catch (e: Exception) {
                notice = t().operationFailed(e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    private fun fail(message: String): Nothing = throw IllegalStateException(message)

    private companion object {
        /**
         * 移动对话框最多翻几页。
         *
         * 每页 50~100 条，20 页足够覆盖正常目录；再往上翻会让「打开移动对话框」
         * 变成几十次请求，用户只会以为界面卡死。
         */
        const val MOVE_FOLDER_MAX_PAGES = 20
    }
}

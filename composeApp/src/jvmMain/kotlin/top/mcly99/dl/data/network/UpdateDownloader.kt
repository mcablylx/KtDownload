package top.mcly99.dl.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 安装包下载器：与用户下载任务体系完全隔离。
 *
 * 为什么不用 DownloadEngine.addTask：那个会把安装包塞进用户任务列表并写进
 * tasks.properties，还要走 8 线程分片 / HEAD 探测 / HLS 合成等重型路径。
 * 更新包只需要「单连接流式下载 → 校验 → 打开」，用 OkHttp 直接写文件即可。
 *
 * 下载到 `~/.mclydownload/updates/` 的临时文件，校验通过后返回最终路径。
 */
class UpdateDownloader(
    private val clientProvider: () -> okhttp3.OkHttpClient = { HttpClients.downloadClient() },
) {

    /**
     * 进度回调。
     * - percent：0..100 的整数百分比（total 未知时为 -1），保留给按百分比显示的调用方
     * - fraction：0f..1f 的细粒度进度，供进度条使用；total 未知时为 -1f
     * - received/total：字节数，total 未知时为 -1
     */
    data class Progress(val percent: Int, val fraction: Float, val received: Long, val total: Long)

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun resetCancel() {
        cancelled = false
    }

    /**
     * 下载安装包。返回下载好的文件；失败抛异常由调用方捕获。
     *
     * @param expectedSha256 可选的 SHA-256（十六进制，大小写不敏感）；非空时校验，不匹配则删除文件并抛异常。
     */
    suspend fun download(
        asset: GitHubAsset,
        onProgress: (Progress) -> Unit,
        expectedSha256: String? = null,
    ): Path = withContext(Dispatchers.IO) {
        cancelled = false
        val dir = AppConstants.APP_DATA_DIR.resolve("updates")
        Files.createDirectories(dir)
        val tmp = dir.resolve("${asset.name}.part")

        try {
            // 与网盘下载、GitHub 仓库浏览共用同一套镜像选择（AUTO 测速 / 用户固定前缀）。
            // 修复：旧实现直接下载 asset.downloadUrl（github.com 直连），国内常只有几 KB/s，
            // 277MB 安装包按 1% 粒度回调要几分钟才动一格，看起来像「进度条卡死」。
            val prefix = GitHubMirror.activePrefix()
            val candidates = buildList {
                if (prefix.isNotBlank()) add(prefix + asset.downloadUrl)
                add(asset.downloadUrl)
            }
            val client = clientProvider()

            // 只在「拿到成功响应前」切换下载源；一旦开始写流就固定在当前源，
            // 避免中途换源导致半截文件重头下载。
            var activeResponse: okhttp3.Response? = null
            var usedUrl = ""
            for (url in candidates) {
                try {
                    val request = Request.Builder().url(url).get().build()
                    val candidate = client.newCall(request).execute()
                    if (candidate.isSuccessful) {
                        activeResponse = candidate
                        usedUrl = url
                        break
                    }
                    AppLogger.warn("Update", "下载源返回 HTTP ${candidate.code}，尝试下一个: ${url.take(100)}")
                    candidate.close()
                } catch (e: Exception) {
                    AppLogger.warn("Update", "下载源连接失败，尝试下一个: ${url.take(100)} (${e.message})")
                }
            }
            val response = activeResponse ?: throw IllegalStateException("所有下载源均不可用，无法下载安装包")
            AppLogger.info("Update", "安装包下载源: $usedUrl")

            response.use { resp ->
                val body = resp.body ?: throw IllegalStateException("下载安装包失败：空响应体")
                val total = if (asset.size > 0) asset.size else body.contentLength()

                body.byteStream().use { input ->
                    Files.newOutputStream(tmp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        var lastReportBytes = -1L
                        while (true) {
                            if (cancelled) throw UpdateCancelled()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            received += n
                            // 按字节节流（每 256KB）而非按 1%：低速网络下进度条也能持续可见地推进
                            if (received - lastReportBytes >= 256L * 1024 || (total > 0 && received >= total)) {
                                lastReportBytes = received
                                if (total > 0) {
                                    val fraction = (received.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                    onProgress(Progress((fraction * 100).toInt(), fraction, received, total))
                                } else {
                                    onProgress(Progress(-1, -1f, received, -1))
                                }
                            }
                        }
                    }
                }
            }

            // 完整性校验：优先 SHA-256，否则至少比对大小，避免拿到半截文件
            if (!expectedSha256.isNullOrBlank()) {
                val actual = sha256(tmp.toFile())
                if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                    Files.deleteIfExists(tmp)
                    throw IllegalStateException("安装包校验失败：SHA-256 不匹配")
                }
            } else if (asset.size > 0 && Files.size(tmp) != asset.size) {
                Files.deleteIfExists(tmp)
                throw IllegalStateException("安装包校验失败：大小不符（期望 ${asset.size}，实际 ${Files.size(tmp)}）")
            }

            val final = dir.resolve(asset.name)
            Files.move(tmp, final, StandardCopyOption.REPLACE_EXISTING)
            AppLogger.info("Update", "安装包下载完成: ${final.fileName} (${Files.size(final)} bytes)")
            final
        } catch (e: UpdateCancelled) {
            Files.deleteIfExists(tmp)
            throw e
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }

    /** 计算文件 SHA-256，返回小写十六进制。 */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** 用户取消下载的信号（不视为错误）。 */
class UpdateCancelled : Exception("update download cancelled")

package top.mcly99.dl.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
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

    /** 当前在飞的请求：cancel() 时一并 cancel，立刻中断阻塞中的 socket 读（P2-2） */
    @Volatile
    private var activeCall: Call? = null

    fun cancel() {
        cancelled = true
        activeCall?.cancel()
    }

    fun resetCancel() {
        cancelled = false
        activeCall = null
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
        // P1-08：asset.name 直接做落盘文件名，先按 pickInstaller 的命名约定校验，
        // 防止 Release 里混入 `../evil.exe` 之类的名字逃出 updates 目录被直接执行。
        val safeName = asset.name.takeIf { INSTALLER_NAME.matches(it) }
            ?: throw IllegalStateException("安装包文件名不符合命名约定: ${asset.name}")
        val tmp = dir.resolve("$safeName.part")

        try {
            // 与网盘下载、GitHub 仓库浏览共用同一套镜像选择（AUTO 测速 / 用户固定前缀）。
            // 修复：旧实现直接下载 asset.downloadUrl（github.com 直连），国内常只有几 KB/s，
            // 277MB 安装包按 1% 粒度回调要几分钟才动一格，看起来像「进度条卡死」。
            val prefix = GitHubMirror.activePrefix()
            val candidates = buildList {
                // 规范化尾部斜杠：用户自定义前缀可能不带 '/'，直接拼会得到
                // https://proxy.comhttps://github.com/...（P2-E7）
                if (prefix.isNotBlank()) {
                    val mirrored = prefix.trimEnd('/') + "/" + asset.downloadUrl
                    if (isSafeHttpsUrl(mirrored)) add(mirrored)
                }
                if (isSafeHttpsUrl(asset.downloadUrl)) add(asset.downloadUrl)
            }
            // P1-08：不接受 http:// 明文下载，也不接受带 userinfo / 无主机的畸形 URL
            if (candidates.isEmpty()) throw IllegalStateException("没有可信的 https 下载源")
            val client = clientProvider()

            // 只在「拿到成功响应前」切换下载源；一旦开始写流就固定在当前源，
            // 避免中途换源导致半截文件重头下载。
            var activeResponse: okhttp3.Response? = null
            var activeCallRef: Call? = null
            var usedUrl = ""
            for (url in candidates) {
                var candidate: okhttp3.Response? = null
                try {
                    val request = Request.Builder().url(url).get().build()
                    val call = client.newCall(request)
                    activeCallRef = call
                    candidate = call.execute()
                    if (!candidate.isSuccessful) {
                        AppLogger.warn("Update", "下载源返回 HTTP ${candidate.code}，尝试下一个: ${url.take(100)}")
                        candidate.close()
                        candidate = null
                        continue
                    }
                    // 镜像常返回 HTTP 200 + HTML 错误/限流页：先按文件头验证是不是安装包，
                    // 不是就换下一个源，避免写满 .part 到最后 size 校验才失败并整包重下（P2-2）。
                    if (!looksLikeInstaller(candidate, asset.name)) {
                        AppLogger.warn("Update", "下载源返回非安装包内容，尝试下一个: ${url.take(100)}")
                        candidate.close()
                        candidate = null
                        continue
                    }
                    activeResponse = candidate
                    activeCallRef = call // 成功后保持句柄，cancel() 可中断后续阻塞读
                    usedUrl = url
                    break
                } catch (e: Exception) {
                    AppLogger.warn("Update", "下载源连接失败，尝试下一个: ${url.take(100)} (${e.message})")
                    candidate?.close()
                }
            }
            activeCall = activeCallRef
            val response = activeResponse ?: throw IllegalStateException("所有下载源均不可用，无法下载安装包")

            // 拿到响应后立刻交给 use 接管：任何在 use 之前抛出的异常（日志、状态读取等）
            // 都会让连接一直挂着不释放，所以打印日志也移进 use 内（P2-E4）
            response.use { resp ->
                AppLogger.info("Update", "安装包下载源: $usedUrl")
                val body = resp.body ?: throw IllegalStateException("下载安装包失败：空响应体")
                val total = if (asset.size > 0) asset.size else body.contentLength()

                body.byteStream().use { input ->
                    Files.newOutputStream(tmp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        var lastReportBytes = -1L
                        while (true) {
                            if (cancelled) throw UpdateCancelled()
                            currentCoroutineContext().ensureActive() // 外部协程取消也能立刻中断
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

            val final = dir.resolve(safeName)
            Files.move(tmp, final, StandardCopyOption.REPLACE_EXISTING)
            AppLogger.info("Update", "安装包下载完成: ${final.fileName} (${Files.size(final)} bytes)")
            final
        } catch (e: UpdateCancelled) {
            Files.deleteIfExists(tmp)
            throw e
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        } finally {
            activeCall = null
        }
    }

    /**
     * 用响应体前几个字节判断是不是安装包，挡掉镜像返回的 HTML 错误/限流页（P2-2）。
     *
     * - .exe/.msi → `MZ`（PE 可执行头）
     * - .zip → `PK`（ZIP 头）
     * - 其它类型不强行判定（交给后续 size/SHA 校验）
     */
    private fun looksLikeInstaller(response: okhttp3.Response, assetName: String): Boolean {
        val body = response.body ?: return false
        val expected = when {
            assetName.endsWith(".exe", ignoreCase = true) || assetName.endsWith(".msi", ignoreCase = true) -> "MZ"
            assetName.endsWith(".zip", ignoreCase = true) -> "PK"
            else -> return true
        }
        return runCatching {
            val peek = body.source().peek()
            val head = ByteArray(expected.length)
            val read = peek.read(head, 0, expected.length)
            read == expected.length && String(head, Charsets.US_ASCII) == expected
        }.getOrDefault(false)
    }

    /** 只接受 https 且无 userinfo / 有主机的 URL（P1-08）。 */
    private fun isSafeHttpsUrl(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        uri.scheme?.equals("https", ignoreCase = true) == true &&
            !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    /**
     * 安装前二次校验：重新读盘计算 digest，覆盖「下载后校验 → 打开安装器」之间
     * 文件被替换的窗口（TOCTOU）。无 digest 一律不通过（P1-08）。
     */
    fun verify(file: Path, asset: GitHubAsset, expectedSha256: String?): Boolean {
        if (expectedSha256.isNullOrBlank()) return false
        if (!Files.isRegularFile(file)) return false
        if (asset.size > 0 && runCatching { Files.size(file) }.getOrDefault(-1L) != asset.size) return false
        return runCatching {
            sha256(file.toFile()).equals(expectedSha256.trim(), ignoreCase = true)
        }.getOrDefault(false)
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

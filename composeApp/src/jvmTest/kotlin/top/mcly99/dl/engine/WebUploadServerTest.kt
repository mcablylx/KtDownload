package top.mcly99.dl.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `WebUploadServer` 的端到端测试（v4-P1-1）。
 *
 * 第四轮走查发现 multipart 解析会丢弃预读的 4096 字节，导致「小文件必然 400、大文件返回成功但零落盘」，
 * 而当时上传链路**没有任何端到端用例**，缺陷因此长期存活。本测试补齐这条链路。
 *
 * 覆盖点：
 * - 边界情况：1 字节、0 字节、> 4096 字节（跨越旧实现丢字节的阈值）
 * - 多段：单请求多文件、非文件字段与文件混排（验证分隔符之后的段头不会被吞）
 * - 编码：中文文件名
 * - 安全：无 token 403、路径穿越仍落在保存目录内、Windows 保留名 400
 * - 限制：文件数超过 `MAX_FILES_PER_REQUEST` 返回 413
 */
class WebUploadServerTest {

    private lateinit var dir: File
    private lateinit var server: WebUploadServer
    private var port: Int = 0

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("webupload-test").toFile()
        server = WebUploadServer(saveDir = dir.absolutePath)
        port = freePort()
        assertTrue(server.start(port), "服务启动失败，端口 $port")
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ 用例

    @Test
    fun uploads_single_byte_file() {
        val body = "X".toByteArray()
        val (code, text) = post(listOf(Part("file", "one.txt", "text/plain", body)))
        assertEquals(200, code, text)
        assertEquals(listOf("one.txt"), savedFiles())
        assertEquals("X", read("one.txt"))
    }

    @Test
    fun uploads_empty_file() {
        val (code, text) = post(listOf(Part("file", "empty.txt", "text/plain", ByteArray(0))))
        assertEquals(200, code, text)
        assertEquals(listOf("empty.txt"), savedFiles())
        assertEquals("", read("empty.txt"))
    }

    @Test
    fun uploads_file_larger_than_old_preamble_window() {
        // 旧实现预读 4096 字节并丢弃，超过这个长度的请求体必然丢段头
        val body = "A".repeat(20_000).toByteArray()
        val (code, text) = post(listOf(Part("file", "big.txt", "text/plain", body)))
        assertEquals(200, code, text)
        assertEquals(listOf("big.txt"), savedFiles())
        assertEquals(20_000L, File(dir, "big.txt").length())
        assertEquals("A".repeat(20_000), read("big.txt"))
    }

    @Test
    fun uploads_multiple_files_in_one_request() {
        val parts = listOf(
            Part("file", "a.txt", "text/plain", "alpha".toByteArray()),
            Part("file", "b.txt", "text/plain", "bravo".toByteArray()),
            Part("file", "c.txt", "text/plain", "charlie".toByteArray()),
        )
        val (code, text) = post(parts)
        assertEquals(200, code, text)
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), savedFiles())
        assertEquals("alpha", read("a.txt"))
        assertEquals("bravo", read("b.txt"))
        assertEquals("charlie", read("c.txt"))
    }

    @Test
    fun skips_non_file_field_and_keeps_file() {
        val parts = listOf(
            Part("comment", null, null, "只是一段说明".toByteArray()),
            Part("file", "kept.bin", "application/octet-stream", ByteArray(64) { it.toByte() }),
        )
        val (code, text) = post(parts)
        assertEquals(200, code, text)
        assertEquals(listOf("kept.bin"), savedFiles())
        assertTrue(File(dir, "kept.bin").readBytes().contentEquals(ByteArray(64) { it.toByte() }))
    }

    @Test
    fun keeps_chinese_file_name() {
        val (code, text) = post(listOf(Part("file", "测试文件.txt", "text/plain", "内容".toByteArray())))
        assertEquals(200, code, text)
        // macOS 的 APFS/HFS+ 会把文件名规范化为 NFD，Linux/Windows 原样保存（NFC）。
        // 「测试文件」都是无分解形式的 CJK 字符，两种规范下字节相同，所以这里本来就通过；
        // 但为了将来换成带组合音的字符（如 "café.txt"、"한국.txt"）时不在 macOS 上假失败，
        // 断言统一按 NFC 比较。
        assertEquals(listOf("测试文件.txt"), savedFiles().map { it.nfc() })
        assertEquals("内容", read("测试文件.txt"))
    }

    @Test
    fun binary_content_is_written_verbatim() {
        // 内容里带 \r\n 与 "--" 片段，确认滑动窗口不会误判分隔符
        val payload = "line1\r\n--not-a-boundary\r\nline3\r\n".toByteArray()
        val (code, text) = post(listOf(Part("file", "tricky.bin", "application/octet-stream", payload)))
        assertEquals(200, code, text)
        assertTrue(File(dir, "tricky.bin").readBytes().contentEquals(payload))
    }

    @Test
    fun rejects_request_without_token() {
        val (code, text) = post(listOf(Part("file", "x.txt", "text/plain", "x".toByteArray())), token = null)
        assertEquals(403, code, text)
        assertTrue(savedFiles().isEmpty())
    }

    @Test
    fun rejects_wrong_token() {
        val (code, text) = post(listOf(Part("file", "x.txt", "text/plain", "x".toByteArray())), token = "deadbeef")
        assertEquals(403, code, text)
        assertTrue(savedFiles().isEmpty())
    }

    @Test
    fun upload_page_requires_token() {
        val unauthorized = get("")
        assertEquals(403, unauthorized.first, unauthorized.second)

        val authorized = get("?token=${server.accessToken}")
        assertEquals(200, authorized.first, authorized.second)
        assertTrue(authorized.second.contains("<html"), "上传页应返回 HTML")
    }

    @Test
    fun path_traversal_stays_inside_save_dir() {
        val (code, text) = post(listOf(Part("file", "../escape.txt", "text/plain", "evil".toByteArray())))
        // 文件名被清洗成最后一段，落在保存目录内
        assertEquals(200, code, text)
        assertEquals(listOf("escape.txt"), savedFiles())
        // 用 File.exists() 而不是 canonicalPath 比较：macOS 上 /var 是指向 /private/var 的符号链接，
        // Files.createTempDirectory 返回 /var/...，canonicalPath 却是 /private/var/...，
        // 混用两种形式做字符串比较会在 macOS 上假失败。exists() 走 stat，会跟随符号链接，跨平台一致。
        assertFalse(File(dir.parentFile, "escape.txt").exists(), "文件不应写到保存目录之外")
    }

    @Test
    fun rejects_windows_reserved_name() {
        val (code, text) = post(listOf(Part("file", "CON.txt", "text/plain", "x".toByteArray())))
        assertEquals(400, code, text)
        assertTrue(text.contains("invalid_file_name"), text)
        assertTrue(savedFiles().isEmpty())
    }

    @Test
    fun rejects_too_many_files() {
        val limit = top.mcly99.dl.utils.AppConstants.MAX_FILES_PER_REQUEST
        val parts = (0..limit).map { Part("file", "f$it.txt", "text/plain", "x".toByteArray()) }
        val (code, text) = post(parts)
        assertEquals(413, code, text)
        assertTrue(text.contains("too_many_files"), text)
        // 失败请求不应留下半成品
        assertTrue(savedFiles().isEmpty())
    }

    // ------------------------------------------------------------------ 走查 P1-20/21/22

    /**
     * P1-20：换端口失败不能把正在工作的服务一起关掉。
     *
     * 原先 `start()` 第一行就是 `stop()`，于是用户在设置里填一个被占用的端口，
     * 结果是「老服务停了、新服务起不来」，上传功能整体消失。
     */
    @Test
    fun switching_to_a_busy_port_keeps_the_old_server_alive() {
        // 用一个真实监听中的 socket 占住端口；不能取完就关，否则存在被别人抢走的竞态
        val occupied = ServerSocket(0)
        val busyPort = occupied.localPort
        try {
            val ok = server.start(busyPort)
            assertFalse(ok, "端口被占用时启动应当失败")
            assertTrue(server.isRunning, "旧服务必须继续运行")
            assertEquals(port, server.port, "端口号应当仍是旧的有效端口")

            // 旧端口仍能正常服务（不只是状态字段没变脏）
            val (code, body) = get("?token=${server.accessToken}")
            assertEquals(200, code, "旧端口应当仍可访问")
            assertTrue(body.contains("<!DOCTYPE html>"), "应当仍是上传页")
        } finally {
            occupied.close()
        }
    }

    /** 同一端口重复 `start` 不该重启：监听中的端口无法被第二个 socket 抢占 */
    @Test
    fun starting_twice_on_the_same_port_is_a_no_op() {
        assertTrue(server.start(port), "同端口重复启动应当报成功")
        assertEquals(port, server.port)
        val (code, _) = get("?token=${server.accessToken}")
        assertEquals(200, code)
    }

    /**
     * P1-21：并发上传同名文件不能互相截断。
     *
     * 旧实现用 `exists()` 做「查重再创建」，两个请求都会看到「文件不存在」，
     * 于是先后 `FileOutputStream` 同一个路径 —— 后写者截断先写者，两边还都回 success。
     */
    @Test
    fun concurrent_uploads_with_the_same_name_never_overwrite_each_other() {
        val payloads = (0 until 4).map { index ->
            // 每个请求内容不同且足够大，一旦互相覆盖就会读出长度/内容不符
            ByteArray(64 * 1024) { (it % 251 + index).toByte() }
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(payloads.size)
        val results = try {
            payloads.map { body ->
                pool.submit<Pair<Int, String>> {
                    post(listOf(Part("file", "same.bin", "application/octet-stream", body)))
                }
            }.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        results.forEach { (code, text) -> assertEquals(200, code, text) }
        val names = savedFiles()
        assertEquals(payloads.size, names.size, "四个同名上传应当落成四个文件，实际：$names")

        // 磁盘上每个文件都必须与某一个请求的内容完全一致，不能出现交错/截断
        val onDisk = names.map { File(dir, it).readBytes() }
        payloads.forEach { expected ->
            assertTrue(
                onDisk.any { it.contentEquals(expected) },
                "有请求的内容在磁盘上找不到完整副本（说明被截断或覆盖）",
            )
        }
    }

    /**
     * P1-22：半死连接（发了一半就再也不发）必须被主动断开。
     *
     * 线程池只有 4 条线程，攒够 4 个这样的连接，之后连 `GET /` 都进不来。
     * 这里把超时压到 300ms，验证看门狗会在秒级把它关掉。
     */
    @Test
    fun a_stalled_upload_connection_is_closed_by_the_idle_watchdog() {
        val quick = WebUploadServer(saveDir = dir.absolutePath, idleTimeoutMs = 300)
        val quickPort = freePort()
        assertTrue(quick.start(quickPort), "服务启动失败，端口 $quickPort")
        try {
            java.net.Socket("127.0.0.1", quickPort).use { socket ->
                socket.soTimeout = 5_000
                val head = "POST /?token=${quick.accessToken} HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:$quickPort\r\n" +
                    "Content-Type: multipart/form-data; boundary=XYZ\r\n" +
                    "Content-Length: 1000000\r\n\r\n" +
                    "--XYZ\r\nContent-Disposition: form-data; name=\"file\"; filename=\"stalled.bin\"\r\n\r\n"
                socket.getOutputStream().apply {
                    write(head.toByteArray())
                    flush()
                }

                val started = System.currentTimeMillis()
                // 不再发送任何字节：期望服务端在看门狗触发后关闭连接，
                // 于是这里读到的要么是 -1（FIN），要么是 SocketException（RST）
                val outcome = runCatching { socket.getInputStream().read() }
                val elapsed = System.currentTimeMillis() - started
                assertTrue(
                    elapsed < 4_000,
                    "连接应在空闲超时后被断开，实际等了 ${elapsed}ms（读结果：$outcome）",
                )
                assertTrue(quick.isRunning, "断开单个空闲连接不应影响服务本身")
            }
            // 半截上传不能留下任何文件。处理器线程收到关闭异常后才删文件，给它一点时间
            awaitNoSavedFiles()
            assertTrue(savedFiles().isEmpty(), "被中断的上传不应当留下文件：${savedFiles()}")
        } finally {
            quick.stop()
        }
    }

    /** 等「处理器线程删掉半成品文件」这一步完成（最多 3 秒） */
    private fun awaitNoSavedFiles() {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && savedFiles().isNotEmpty()) {
            Thread.sleep(50)
        }
    }

    // ------------------------------------------------------------------ 辅助

    private data class Part(
        val name: String,
        val fileName: String?,
        val contentType: String?,
        val body: ByteArray,
    )

    private fun buildMultipart(parts: List<Part>, boundary: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun write(text: String) = out.write(text.toByteArray(Charsets.UTF_8))
        for (part in parts) {
            write("--$boundary\r\n")
            write(
                buildString {
                    append("Content-Disposition: form-data; name=\"")
                    append(part.name)
                    append('"')
                    if (part.fileName != null) {
                        append("; filename=\"")
                        append(part.fileName)
                        append('"')
                    }
                    append("\r\n")
                },
            )
            if (part.contentType != null) write("Content-Type: ${part.contentType}\r\n")
            write("\r\n")
            out.write(part.body)
            write("\r\n")
        }
        write("--$boundary--\r\n")
        return out.toByteArray()
    }

    /**
     * JDK 的 HttpURLConnection 按 host:port 缓存 keep-alive 连接。测试用 `freePort()`
     * 取的临时端口可能被操作系统立刻回收给下一个测试服务器，客户端偶尔会捡到一条
     * 已经关闭的旧连接，报 `Unexpected end of file from server`。这类偶发失败与被测代码
     * 无关，换一条新连接重试即可；重试上限 3 次，不改被测服务。
     */
    private fun <T> withFreshConnectionRetry(block: () -> T): T {
        var last: java.net.SocketException? = null
        repeat(3) { attempt ->
            try {
                return block()
            } catch (e: java.net.SocketException) {
                last = e
                if (attempt == 2) throw e
            }
        }
        throw last ?: IllegalStateException("请求失败")
    }

    private fun post(
        parts: List<Part>,
        token: String? = server.accessToken,
        boundary: String = "----KtDownTestBoundary",
    ): Pair<Int, String> = withFreshConnectionRetry {
        val body = buildMultipart(parts, boundary)
        val query = if (token == null) "" else "?token=$token"
        val conn = (URL("http://127.0.0.1:$port/$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Content-Length", body.size.toString())
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun get(query: String): Pair<Int, String> = withFreshConnectionRetry {
        val conn = (URL("http://127.0.0.1:$port/$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 20_000
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun savedFiles(): List<String> =
        dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    /** Unicode NFC 规范化：macOS 文件系统返回 NFD，比较前统一（见 `keeps_chinese_file_name`） */
    private fun String.nfc(): String =
        java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFC)

    private fun read(name: String): String = File(dir, name).readText(Charsets.UTF_8)

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}

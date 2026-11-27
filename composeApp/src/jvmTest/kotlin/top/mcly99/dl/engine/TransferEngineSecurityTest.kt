package top.mcly99.dl.engine

import top.mcly99.dl.testutil.TestTimeouts
import top.mcly99.dl.testutil.awaitUntil
import top.mcly99.dl.utils.AppConstants
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P1-09：局域网接收服务的认证 / 容量上限 / 目录 confined 回归测试。
 *
 * 现有 `TransferEngineFolderTest` 复用 `sendFiles()`，两端同版本，覆盖不到「旧版发送端」
 * 与「畸形协议」；这里全部用裸 Socket 手工拼字节，并用 `shutdownOutput` + 等对端关闭
 * 作为确定性同步点，不用 sleep 轮询。
 */
class TransferEngineSecurityTest {

    @Test
    fun auth_enabled_rejects_legacy_sender_without_auth() {
        withEngine(token = "secret") { dir, port ->
            sendAndAwaitClose(port, listOf("FILE|a.bin|3"), "abc".toByteArray(Charsets.UTF_8))
            assertFalse(dir.resolve("a.bin").exists(), "未认证连接不得落盘")
            assertTrue(dir.walkTopDown().filter { it.isFile }.toList().isEmpty())
        }
    }

    @Test
    fun auth_enabled_rejects_wrong_token() {
        withEngine(token = "secret") { dir, port ->
            sendAndAwaitClose(
                port,
                listOf("AUTH|wrong", "FILE|a.bin|3"),
                "abc".toByteArray(Charsets.UTF_8),
            )
            assertFalse(dir.resolve("a.bin").exists(), "配对码不符不得落盘")
        }
    }

    @Test
    fun auth_enabled_accepts_valid_handshake() {
        withEngine(token = "secret") { dir, port ->
            sendAndAwaitClose(
                port,
                listOf("AUTH|secret", "FILE|a.bin|3"),
                "abc".toByteArray(Charsets.UTF_8),
            )
            val received = dir.resolve("a.bin")
            assertTrue(received.exists(), "正确 AUTH| 后应正常接收")
            assertEquals("abc", received.readText())
        }
    }

    @Test
    fun oversized_file_is_rejected_before_any_file_is_created() {
        withEngine(token = "secret") { dir, port ->
            val declared = AppConstants.MAX_TRANSFER_FILE_BYTES + 1
            sendAndAwaitClose(port, listOf("AUTH|secret", "FILE|big.bin|$declared"))
            assertFalse(dir.resolve("big.bin").exists(), "超上限声明必须在写盘前拒绝")
            assertTrue(dir.walkTopDown().filter { it.isFile }.toList().isEmpty())
        }
    }

    @Test
    fun path_traversal_is_rejected() {
        withEngine(token = "secret") { dir, port ->
            sendAndAwaitClose(
                port,
                listOf("AUTH|secret", "FILE|../evil.bin|3"),
                "abc".toByteArray(Charsets.UTF_8),
            )
            assertFalse(dir.resolve("evil.bin").exists())
            assertTrue(dir.walkTopDown().filter { it.isFile }.toList().isEmpty())
        }
    }

    @Test
    fun auth_disabled_still_accepts_legacy_sender() {
        // 互操作性回归：不注入 token 时保持旧协议，未接线的场景与旧版本仍可互通
        withEngine(token = "") { dir, port ->
            sendAndAwaitClose(port, listOf("FILE|a.bin|3"), "abc".toByteArray(Charsets.UTF_8))
            assertTrue(dir.resolve("a.bin").exists(), "关闭认证时旧版 FILE| 协议应仍可用")
        }
    }

    private fun withEngine(token: String, block: (File, Int) -> Unit) {
        val dir = Files.createTempDirectory("ktdl-recv-").toFile()
        val port = ServerSocket(0).use { it.localPort }
        val engine = TransferEngine(port).apply {
            receiveDir = dir
            accessToken = token
        }
        try {
            engine.start()
            awaitUntil(TestTimeouts.SHORT, describe = { "接收服务未能启动" }) { engine.fileServerRunning }
            block(dir, port)
        } finally {
            engine.stop()
            dir.deleteRecursively()
        }
    }

    /** 手工发协议行 + 载荷，半关闭输出后等对端处理完并关闭连接（确定性同步点）。 */
    private fun sendAndAwaitClose(port: Int, lines: List<String>, payload: ByteArray = ByteArray(0)) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            lines.forEach { out.write((it + "\n").toByteArray(Charsets.UTF_8)) }
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()
            socket.shutdownOutput()
            try {
                val input = socket.getInputStream()
                while (input.read() != -1) { /* 对端不写数据，只是等 EOF */ }
            } catch (_: IOException) {
                // 对端直接 reset 也说明这一轮已经处理完
            }
        }
    }
}

package top.mcly99.dl.engine

import kotlinx.coroutines.runBlocking
import top.mcly99.dl.model.NetworkDevice
import top.mcly99.dl.testutil.TestTimeouts
import top.mcly99.dl.testutil.awaitUntilSuspend
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferEngineFolderTest {

    @Test
    fun sanitize_relative_path_keeps_nested_dirs_and_rejects_traversal() {
        val engine = TransferEngine()
        assertEquals(listOf("folder", "sub", "a.txt"), engine.sanitizeRelativePath("folder/sub/a.txt"))
        assertEquals(listOf("folder", "a.txt"), engine.sanitizeRelativePath("folder\\a.txt"))
        assertNull(engine.sanitizeRelativePath("../etc/passwd"))
        assertNull(engine.sanitizeRelativePath("folder/../a.txt"))
        assertNull(engine.sanitizeRelativePath("folder/./a.txt"))
        assertNull(engine.sanitizeRelativePath(""))
    }

    @Test
    fun sends_folder_tree_with_subdirs_and_non_ascii_names() = runBlocking {
        // 用临时端口，避免本机正在运行的应用占用 9877
        val port = ServerSocket(0).use { it.localPort }

        val source = Files.createTempDirectory("send-folder").toFile()
        val receive = Files.createTempDirectory("recv-folder").toFile()
        try {
            File(source, "a.txt").writeText("hello")
            File(source, "sub").mkdirs()
            File(source, "sub/b.txt").writeText("world")
            File(source, "sub/中文.txt").writeText("你好")

            val received = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
            val receiver = TransferEngine(port).apply {
                receiveDir = receive
                onFileReceived = { file, ip -> received += file.name to ip }
                start()
            }
            val sender = TransferEngine(port)
            try {
                awaitUntilSuspend(TestTimeouts.SHORT, describe = { "接收服务未能启动" }) {
                    receiver.fileServerRunning
                }
                assertTrue(receiver.fileServerRunning, "接收服务未能启动")

                val files = sender.collectFolderFiles(source)
                assertEquals(3, files.size)

                val target = NetworkDevice(id = "test", name = "test", ipAddress = "127.0.0.1", port = 0)
                assertTrue(sender.sendFiles(files, target, taskId = "test-send") { _, _, _ -> })

                val expected = listOf(
                    File(receive, "${source.name}/a.txt") to "hello",
                    File(receive, "${source.name}/sub/b.txt") to "world",
                    File(receive, "${source.name}/sub/中文.txt") to "你好",
                )
                awaitUntilSuspend(TestTimeouts.MEDIUM, describe = {
                    "文件未按目录结构送达: ${expected.filter { (f, text) -> !f.isFile || f.readText() != text }.map { it.first.path }}"
                }) {
                    expected.all { (f, text) -> f.isFile && f.readText() == text }
                }
                expected.forEach { (file, text) ->
                    assertTrue(file.isFile, "缺少文件: ${file.path}")
                    assertEquals(text, file.readText())
                }

                // P1-1：接收回调带上发送方 IP，ViewModel 依赖它创建接收任务
                awaitUntilSuspend(TestTimeouts.SHORT, describe = { "接收回调只收到 ${received.size}/3 条" }) {
                    received.size >= 3
                }
                assertEquals(
                    setOf("a.txt", "b.txt", "中文.txt"),
                    received.map { it.first }.toSet(),
                )
                assertTrue(received.all { it.second == "127.0.0.1" }, "回调 IP 应为发送方地址: $received")
            } finally {
                receiver.stop()
                sender.stop()
            }
        } finally {
            source.deleteRecursively()
            receive.deleteRecursively()
        }
    }
}

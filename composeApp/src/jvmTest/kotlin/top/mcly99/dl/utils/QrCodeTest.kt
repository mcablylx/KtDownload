package top.mcly99.dl.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QrCodeTest {
    @Test
    fun encodes_typical_upload_url() {
        val text = "http://192.168.1.10:8080/?token=ab12cd34ef56"
        val m = assertNotNull(QrCode.encode(text), "二维码编码不应返回 null")
        // 版本 3 对应 29x29
        assertTrue(m.size in listOf(21, 25, 29, 33, 37, 41, 45, 49, 53, 57), "尺寸应为 17 + 4*version")
        assertEquals(m.size, m[0].size)
        // 三个定位图形的左上角必须为深色，且 7x7 外圈深色
        assertTrue(m[0][0], "左上定位图形角应为深色")
        assertTrue(m[0][m.size - 1], "右上定位图形角应为深色")
        assertTrue(m[m.size - 1][0], "左下定位图形角应为深色")
        // 定位图形内部第 1 行第 1 列应为浅色
        assertTrue(!m[1][1], "定位图形内圈应为浅色")
    }

    @Test
    fun rejects_overlong_content() {
        val tooLong = "x".repeat(5000)
        assertEquals(null, QrCode.encode(tooLong), "超出版本上限应返回 null 而不是抛异常")
    }

    /**
     * 回归：完整矩阵与「已用独立解码器（jsQR）确认可扫描」的基准一致。
     *
     * 覆盖版本 1 / 4 / 8 / 9 / 10，重点锁定：
     * - 格式信息（含掩码编号）落点；
     * - v8+ 的分块顺序（短块在前、长块在后）与交错写入；
     * - 版本信息区域只出现在 v7+。
     *
     * 若将来有意调整掩码罚分等策略，请先用独立解码器重新验证后再更新
     * `src/jvmTest/resources/qr-golden.txt`。
     */
    @Test
    fun matches_independently_verified_matrices() {
        val cases = loadGolden()
        assertTrue(cases.isNotEmpty(), "基准数据不应为空")
        cases.forEach { (text, expectedRows) ->
            val actual = assertNotNull(QrCode.encode(text), "编码失败: $text")
            val size = actual.size
            assertEquals(expectedRows.size, size, "版本/尺寸不一致: $text")
            expectedRows.forEachIndexed { y, hex ->
                val value = hex.toLong(16)
                val totalBits = hex.length * 4
                for (x in 0 until size) {
                    val expected = ((value ushr (totalBits - 1 - x)) and 1L) == 1L
                    assertEquals(expected, actual[y][x], "模块不一致 ($y,$x): $text")
                }
            }
        }
    }

    /** 解析 `TEXT|内容` + 每行十六进制（每 4 个模块压缩为 1 位）的基准文件 */
    private fun loadGolden(): List<Pair<String, List<String>>> {
        val stream = QrCodeTest::class.java.getResourceAsStream("/qr-golden.txt")
            ?: error("缺少测试资源 qr-golden.txt")
        val cases = mutableListOf<Pair<String, List<String>>>()
        var text: String? = null
        var rows = mutableListOf<String>()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                when {
                    line.isEmpty() -> {
                        val current = text
                        if (current != null) {
                            cases.add(current to rows.toList())
                            text = null
                            rows = mutableListOf()
                        }
                    }
                    line.startsWith("TEXT|") -> text = line.removePrefix("TEXT|")
                    else -> rows.add(line)
                }
            }
        }
        text?.let { cases.add(it to rows.toList()) }
        return cases
    }
}

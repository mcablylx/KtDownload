package top.mcly99.dl.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [TransferExtraStrings] 的自检。
 *
 * 这张表是 P1-23 补入口时新建的：传输历史上的「取消 / 移除记录」、面板上的
 * 「打开接收目录 / 清空已结束」，以及端口回滚提示。文案错了不会崩，只会让用户
 * 在两个相似动作之间猜 —— 所以至少守住「不为空」和「两个动作不同名」。
 */
class TransferExtraStringsTest {

    private val zh = transferExtraStrings("zh-CN")
    private val en = transferExtraStrings("en-US")

    @Test
    fun both_languages_are_complete() {
        listOf(zh, en).forEach { table ->
            assertTrue(table.cancelTransfer.isNotBlank())
            assertTrue(table.removeRecord.isNotBlank())
            assertTrue(table.clearFinished.isNotBlank())
            assertTrue(table.openReceiveDir.isNotBlank())
            assertTrue(table.openReceiveDirFailed.isNotBlank())
            assertTrue(table.copyIp.isNotBlank())
            assertTrue(table.webPortUnknown.isNotBlank())
            assertTrue(table.trustedLanOnly.isNotBlank())
            assertTrue(table.portSwitchFailed(8080).isNotBlank())
            assertTrue(table.copiedIp("192.168.1.2").isNotBlank())
        }
    }

    @Test
    fun cancel_and_remove_are_different_actions() {
        // 「取消」是停掉正在跑的传输，「移除记录」只是从列表里删一行；
        // 混用会让用户以为删掉记录就等于停掉传输
        assertNotEquals(zh.cancelTransfer, zh.removeRecord)
        assertNotEquals(en.cancelTransfer, en.removeRecord)
        assertNotEquals(zh.clearFinished, zh.removeRecord)
    }

    @Test
    fun port_switch_message_names_the_port_still_in_use() {
        // 端口回滚时用户最需要知道的是「现在到底还能不能用、在哪个端口」，
        // 提示里必须带上仍在监听的端口号
        assertTrue(zh.portSwitchFailed(9090).contains("9090"), zh.portSwitchFailed(9090))
        assertTrue(en.portSwitchFailed(9090).contains("9090"), en.portSwitchFailed(9090))
    }

    @Test
    fun chinese_and_english_tables_differ() {
        // 防止复制粘贴时把 en 表忘了改（历史上出现过整表英文照抄中文的情况）
        assertNotEquals(zh.cancelTransfer, en.cancelTransfer)
        assertNotEquals(zh.openReceiveDir, en.openReceiveDir)
        assertEquals("Cancel", en.cancelTransfer)
    }

    @Test
    fun trusted_lan_notice_mentions_lan_in_both_languages() {
        // P1-09：这条标注是安全边界说明，不能只有中文或只有英文
        assertTrue(zh.trustedLanOnly.contains("局域网"), zh.trustedLanOnly)
        assertTrue(en.trustedLanOnly.contains("LAN", ignoreCase = true), en.trustedLanOnly)
    }
}

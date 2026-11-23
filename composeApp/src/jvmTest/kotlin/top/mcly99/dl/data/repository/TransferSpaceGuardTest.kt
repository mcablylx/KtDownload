package top.mcly99.dl.data.repository

import top.mcly99.dl.data.network.model.QuotaInfo
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 转存空间校验回归测试（P1-3）。
 *
 * 配额查询失败（鉴权过期/网络错误）曾被 getOrNull 压成 null 后当成「已完成校验」
 * 返回 true，让调用方跳过逐项兜底，空间不足仍会转存。这里断言：查询失败返回 false
 * （保留逐项兜底），配额充足/不足的正常判定不受影响。
 */
class TransferSpaceGuardTest {

    @Test
    fun batch_query_failure_returns_false_to_keep_item_fallback() = runBlocking {
        val result = TransferSpaceGuard.ensureEnoughSpaceForBatch(listOf(100L, 200L), "测试盘") {
            throw IllegalStateException("鉴权过期")
        }

        assertFalse(result, "查询失败不能冒充「已完成校验」返回 true")
    }

    @Test
    fun batch_null_quota_returns_false() = runBlocking {
        val result = TransferSpaceGuard.ensureEnoughSpaceForBatch(listOf(100L), "测试盘") { null }

        assertFalse(result)
    }

    @Test
    fun batch_enough_space_returns_true() = runBlocking {
        val result = TransferSpaceGuard.ensureEnoughSpaceForBatch(listOf(100L, 200L), "测试盘") {
            QuotaInfo(used = 0L, total = 1000L, usedInTrash = 0L)
        }

        assertTrue(result)
    }

    @Test
    fun single_file_insufficient_space_throws() = runBlocking {
        val thrown = runCatching {
            TransferSpaceGuard.ensureEnoughSpace(5000L, "测试盘") {
                QuotaInfo(used = 0L, total = 1000L, usedInTrash = 0L)
            }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertTrue(thrown.message!!.contains("空间不足"))
    }

    @Test
    fun single_file_query_failure_is_tolerated() = runBlocking {
        // 单文件校验的容错原则不变：查询失败放行，不阻塞既有流程，只记日志
        val thrown = runCatching {
            TransferSpaceGuard.ensureEnoughSpace(5000L, "测试盘") {
                throw IllegalStateException("网络错误")
            }
        }.exceptionOrNull()

        assertTrue(thrown == null)
    }
}

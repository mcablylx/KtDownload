package top.mcly99.dl.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ByteRateSampler] 单测。
 *
 * 时间戳全部注入，不依赖真实时钟：速度计算最容易写错的地方就是「窗口未满时的最旧样本在哪」，
 * 用固定时间戳才能把每个分支都走到（v6-P1-1）。
 */
class ByteRateSamplerTest {

    @Test
    fun first_sample_reports_zero_speed() {
        val sampler = ByteRateSampler()
        assertEquals(0L, sampler.sample(0, now = 1_000))
    }

    @Test
    fun second_sample_uses_elapsed_time_and_byte_delta() {
        val sampler = ByteRateSampler(minIntervalMs = 300)
        sampler.sample(0, now = 1_000)
        // 1 秒下了 1000 字节 → 1000 B/s
        assertEquals(1_000L, sampler.sample(1_000, now = 2_000))
        // 再过 0.5 秒又下 1000 字节；窗口里最旧样本仍是 0/1000，累计 2000/1500ms ≈ 1333 B/s
        assertEquals(1_333L, sampler.sample(2_000, now = 2_500))
    }

    @Test
    fun samples_inside_the_min_interval_reuse_the_last_value() {
        val sampler = ByteRateSampler(minIntervalMs = 300)
        sampler.sample(0, now = 1_000)
        sampler.sample(1_000, now = 2_000)
        // 只过了 100ms：不重新计算，直接返回上一次的速度
        assertEquals(1_000L, sampler.sample(1_500, now = 2_100))
    }

    @Test
    fun stalled_download_decays_to_zero_within_one_window() {
        val sampler = ByteRateSampler(minIntervalMs = 300)
        sampler.sample(0, now = 1_000)
        assertEquals(1_000L, sampler.sample(1_000, now = 2_000))

        // 卡住后不会瞬间归零：滑动窗口里还留着「之前在涨」的样本，速度平滑衰减 ——
        // 这是刻意的取舍（瞬时的 0 会让网络抖动看起来像断流）
        val during = sampler.sample(1_000, now = 3_000)
        assertTrue(during in 1..999, "窗口内还有旧样本时应衰减而不是归零，实际 $during")

        // 再卡满一个窗口（5 个采样点）之后必须归零，否则「卡住了」会一直显示在跑
        var now = 3_000L
        var speed = during
        repeat(5) {
            now += 1_000
            speed = sampler.sample(1_000, now = now)
        }
        assertEquals(0L, speed)
    }

    @Test
    fun reset_drops_the_window() {
        val sampler = ByteRateSampler(minIntervalMs = 300)
        sampler.sample(0, now = 1_000)
        sampler.sample(1_000, now = 2_000)
        sampler.reset()
        assertEquals(0L, sampler.sample(5_000, now = 3_000))
    }

    @Test
    fun window_slides_after_it_is_full() {
        val sampler = ByteRateSampler(window = 2, minIntervalMs = 1)
        // 窗口长度 2：第三个样本进来时最旧的那个被挤掉，速率只看最近两点
        sampler.sample(0, now = 0)
        sampler.sample(0, now = 1_000)
        sampler.sample(0, now = 2_000)
        val speed = sampler.sample(4_000, now = 3_000)
        assertTrue(speed > 0, "窗口滑动后应当用最近的增量算出正速度，实际 $speed")
    }
}

package com.ztc1997.timeless

import com.ztc1997.timeless.core.FakeTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 覆盖 P1 引入的 seed→假数字缓存：命中缓存不能改变原有确定性不变量。 */
class FakeTimeCacheTest {

    @Test
    fun `interleaved seeds stay deterministic and adjacent minutes differ`() {
        for (seed in 1L..2_000L) {
            val prev = FakeTime.mask("23:14", seed - 1)
            val cur = FakeTime.mask("23:14", seed)
            assertNotEquals("seed=$seed", prev, cur)
            assertEquals(prev, FakeTime.mask("23:14", seed - 1))
            assertEquals(cur, FakeTime.mask("23:14", seed))
        }
    }

    @Test
    fun `digit and mask stay consistent across cache eviction`() {
        for (seed in 1L..2_000L) {
            val digit = FakeTime.digit(seed, 7)
            val masked = FakeTime.mask("7", seed)
            assertEquals(('0' + digit).toString(), masked)
            // 插入相邻分钟的调用，把单槽缓存换掉，再回来读同一 seed。
            FakeTime.digit(seed - 1, 3)
            FakeTime.mask("7", seed - 1)
            assertEquals(masked, FakeTime.mask("7", seed))
        }
    }
}

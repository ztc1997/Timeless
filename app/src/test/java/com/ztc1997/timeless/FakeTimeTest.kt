package com.ztc1997.timeless

import com.ztc1997.timeless.core.FakeTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTimeTest {

    @Test
    fun `same minute is deterministic`() {
        val seed = 29_763L
        assertEquals(FakeTime.mask("23:14", seed), FakeTime.mask("23:14", seed))
        assertEquals(FakeTime.digit(seed, 2), FakeTime.digit(seed, 2))
    }

    @Test
    fun `digits always land in 6 to 9`() {
        for (seed in 0L until 5_000L) {
            for (d in 0..9) {
                val v = FakeTime.digit(seed, d)
                assertTrue("seed=$seed d=$d v=$v", v in 6..9)
            }
        }
    }

    @Test
    fun `all four digits are identical`() {
        // "18:24" → "77:77"：四位全同是这套方案的可辨识签名（电子表自检态）。
        for (seed in 0L until 5_000L) {
            val masked = FakeTime.mask("18:24", seed)
            val digits = masked.filter { it in '0'..'9' }
            assertEquals(4, digits.length)
            assertEquals(1, digits.toSet().size)
        }
    }

    @Test
    fun `digit ignores the original value`() {
        // 逐位写入时 hook 只拿得到"这一位原本是几"，取假值时不能看它 ——
        // 否则同一分钟内不同位置会算出不同数字，"四位全同"就散了。
        for (seed in 0L until 1_000L) {
            val v = FakeTime.digit(seed, 0)
            for (d in 1..9) assertEquals(v, FakeTime.digit(seed, d))
        }
    }

    @Test
    fun `non digits are preserved`() {
        val masked = FakeTime.mask("23:14", 42L)
        assertEquals(5, masked.length)
        assertEquals(':', masked[2])
        assertEquals(" PM", FakeTime.mask("23:14 PM", 42L).drop(5))
    }

    @Test
    fun `whole string mask equals per digit writes`() {
        // 状态栏整串 "23:14" 与插件时钟逐位写入必须一致：散列只依赖 (分钟, 数字值)。
        val seed = 777L
        val whole = FakeTime.mask("23:14", seed)
        val parts = FakeTime.mask("2", seed) + FakeTime.mask("3", seed) + ":" +
            FakeTime.mask("1", seed) + FakeTime.mask("4", seed)
        assertEquals(whole, parts)
    }

    @Test
    fun `hour and minute are both out of range`() {
        // 双重越界：小时 66..99（真实 0..23）、分钟 66..99（真实 0..59）。
        for (seed in 0L until 2_000L) {
            val masked = FakeTime.mask("23:59", seed)
            val hour = masked.substring(0, 2).toInt()
            val minute = masked.substring(3, 5).toInt()
            assertTrue("seed=$seed hour=$hour", hour in 66..99)
            assertTrue("seed=$seed minute=$minute", minute in 66..99)
        }
    }

    @Test
    fun `every mask changes from the previous minute`() {
        // 硬保证：整串与逐位段（小时 "23" / 分钟 "14" / 单个数字）在相邻两分钟
        // 必然不同，所以屏幕上不会出现"这一分钟没动"。
        val samples = listOf("23:14", "23", "14", "7", "09:09", "11:11 PM", "23:59")
        for (text in samples) {
            for (seed in 1L until 5_000L) {
                assertNotEquals(
                    "text=$text seed=$seed",
                    FakeTime.mask(text, seed - 1),
                    FakeTime.mask(text, seed),
                )
            }
        }
    }

    @Test
    fun `digits are roughly uniform`() {
        // 4 个取值应各占约 1/4；偏得离谱说明散列退化。
        val counts = IntArray(10)
        var total = 0
        for (seed in 0L until 20_000L) {
            counts[FakeTime.digit(seed, 7) - 6]++
            total++
        }
        for (c in 0 until 4) {
            val ratio = counts[c].toDouble() / total
            assertTrue("c=$c ratio=$ratio", ratio in 0.22..0.28)
        }
    }

    @Test
    fun `fake output does not correlate with real minute`() {
        // 真实分钟序号对 10 取模，与假数字不应有单调/周期性关系：
        // 每个余数分组里 4 个取值的分布都接近均匀。
        val buckets = Array(10) { IntArray(4) }
        for (seed in 0L until 20_000L) {
            buckets[(seed % 10).toInt()][FakeTime.digit(seed, 3) - 6]++
        }
        for (b in 0 until 10) {
            val sum = buckets[b].sum()
            for (c in 0 until 4) {
                val ratio = buckets[b][c].toDouble() / sum
                assertTrue("bucket=$b c=$c ratio=$ratio", ratio in 0.20..0.30)
            }
        }
    }

    @Test
    fun `12h and 24h surfaces stay consistent for shared digits`() {
        val seed = 4_242L
        assertEquals(
            FakeTime.mask("3:07", seed).takeLast(3),
            FakeTime.mask("15:07", seed).takeLast(3),
        )
    }
}

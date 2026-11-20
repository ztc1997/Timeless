package com.ztc1997.timeless

import com.ztc1997.timeless.core.Placeholder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholderTest {

    @Test
    fun `same minute is deterministic`() {
        val seed = 29763L
        assertEquals(Placeholder.mask("23:14", seed), Placeholder.mask("23:14", seed))
        assertEquals(Placeholder.glyph(seed, 2), Placeholder.glyph(seed, 2))
    }

    @Test
    fun `non digits are preserved digits are masked`() {
        val masked = Placeholder.mask("23:14", 42L)
        assertEquals(5, masked.length)
        assertEquals(':', masked[2])
        assertTrue(masked[0] in arrayOf(Placeholder.MOON, Placeholder.STAR))
        assertTrue(masked[1] in arrayOf(Placeholder.MOON, Placeholder.STAR))
        assertTrue(masked[3] in arrayOf(Placeholder.MOON, Placeholder.STAR))
        assertTrue(masked[4] in arrayOf(Placeholder.MOON, Placeholder.STAR))
    }

    @Test
    fun `whole string mask equals per digit writes`() {
        // 状态栏整串 "23:14" 与插件时钟逐位写入（2/3/1/4 各一次，插件每位是
        // 独立 TextView）必须得到相同图案 —— 散列只依赖 (分钟, 数字值)。
        val seed = 777L
        val whole = Placeholder.mask("23:14", seed)
        val parts = Placeholder.mask("2", seed) + Placeholder.mask("3", seed) + ":" +
            Placeholder.mask("1", seed) + Placeholder.mask("4", seed)
        assertEquals(whole, parts)
    }

    @Test
    fun `12h and 24h surfaces stay consistent for shared digits`() {
        // 12 小时制下状态栏可能显示 "3:07"，24 小时制锁屏显示 "15:07"；
        // 分钟部分数字相同 → 图案必须一致（散列不含小时制差异）。
        val seed = 4242L
        assertEquals(
            Placeholder.mask("3:07", seed).takeLast(3),
            Placeholder.mask("15:07", seed).takeLast(3),
        )
    }

    @Test
    fun `different minutes produce different patterns at least sometimes`() {
        val chars = "23:14".map { c ->
            (0..9).map { m ->
                Placeholder.glyph(m.toLong(), c - '0')
            }
        }
        // 任一数字位在 10 个不同分钟里应同时出现过月与星（散列充分）
        assertTrue(chars.all { run -> run.toSet().size == 2 })
    }
}

package com.ztc1997.timeless

import com.ztc1997.timeless.hook.StarMoonSpan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 星月 span 的纯逻辑回归测试。
 *
 * 说明（2026-11-20）：span 的 ReplacementSpan 挂接与 Paint.getTextBounds 依赖
 * 真实 Android framework（纯 JVM stub 下是空实现），无法在 JVM 单测里验证；
 * 尺寸口径由真机逐帧像素对照验证（AOD 真实数字墨迹高 463，span 实时 cap=468，
 * 两者吻合；写入时快照 cap 会因字体未加载/场景复用而错，已回退）。
 * 这里只覆盖不依赖 framework 的直通判定逻辑。
 */
class StarMoonSpanTest {

    @Test
    fun `no glyph chars yields the original string unchanged`() {
        val orig = "12:34"
        val out = StarMoonSpan.spanned(orig, orig)
        assertEquals(orig, out)
    }
}

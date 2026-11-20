package com.ztc1997.timeless

import com.ztc1997.timeless.core.ConfigKeys
import com.ztc1997.timeless.core.ConfigSnapshot
import com.ztc1997.timeless.core.SleepWindow
import com.ztc1997.timeless.hook.MaskDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MaskDecision 装配逻辑单测。
 *
 * 刻意取 linkDnd=false / followSystem=false 的配置：这样 DndState.isActive() 被
 * 短路、SystemSleepSource.window(false) 首行返回 null，整条路径不碰 Android
 * runtime，可在纯 JVM 单测里验证「配置快照确实透传给了 core 判定」。勿扰与系统
 * 作息的实际读取依赖真机，留给集成验证；纯判定本身由 SleepWindowTest 覆盖。
 */
class MaskDecisionTest {

    private fun cfg(
        enabled: Boolean = true,
        override: Int = ConfigKeys.OVERRIDE_AUTO,
        schedule: Boolean = false,
        start: String = "23:00",
        end: String = "07:00",
    ) = ConfigSnapshot(
        enabled = enabled,
        manualOverride = override,
        scheduleEnabled = schedule,
        windowStart = start,
        windowEnd = end,
        linkDnd = false,
        followSystem = false,
    )

    @Test
    fun `总开关关闭时不屏蔽`() {
        assertFalse(MaskDecision.shouldMask { cfg(enabled = false, schedule = true) })
    }

    @Test
    fun `强制开启时无视时段屏蔽`() {
        assertTrue(MaskDecision.shouldMask { cfg(override = ConfigKeys.OVERRIDE_ON) })
    }

    @Test
    fun `强制关闭时即使命中时段也不屏蔽`() {
        assertFalse(
            MaskDecision.shouldMask {
                cfg(override = ConfigKeys.OVERRIDE_OFF, schedule = true)
            },
        )
    }

    @Test
    fun `固定时段命中由core判定`() {
        // 用真实当前时间，按时段是否命中决定期望值，避免测试依赖运行时钟的绝对点
        val now = System.currentTimeMillis()
        val expected = SleepWindow.isInWindow("23:00", "07:00", now)
        assertEquals(expected, MaskDecision.shouldMask { cfg(schedule = true) })
    }
}

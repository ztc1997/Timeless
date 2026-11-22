package com.ztc1997.timeless

import com.ztc1997.timeless.core.ConfigKeys
import com.ztc1997.timeless.core.ConfigSnapshot
import com.ztc1997.timeless.core.MaskReason
import com.ztc1997.timeless.core.SleepWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** SleepWindow 纯逻辑单测：时段边界、跨午夜、覆盖优先级。 */
class SleepWindowTest {

    private fun at(hour: Int, minute: Int): Long {
        val base = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return base.timeInMillis
    }

    @Test
    fun `跨午夜窗口 23点到7点`() {
        assertTrue(SleepWindow.isInWindow("23:00", "07:00", at(23, 0)))
        assertTrue(SleepWindow.isInWindow("23:00", "07:00", at(2, 30)))
        assertTrue(SleepWindow.isInWindow("23:00", "07:00", at(6, 59)))
        assertFalse(SleepWindow.isInWindow("23:00", "07:00", at(7, 0)))
        assertFalse(SleepWindow.isInWindow("23:00", "07:00", at(22, 59)))
    }

    @Test
    fun `同一天窗口 1点到3点`() {
        assertFalse(SleepWindow.isInWindow("01:00", "03:00", at(0, 59)))
        assertTrue(SleepWindow.isInWindow("01:00", "03:00", at(1, 0)))
        assertTrue(SleepWindow.isInWindow("01:00", "03:00", at(2, 0)))
        assertFalse(SleepWindow.isInWindow("01:00", "03:00", at(3, 0)))
    }

    @Test
    fun `总开关关闭时不屏蔽`() {
        val cfg = ConfigSnapshot(enabled = false, windowStart = "23:00", windowEnd = "07:00")
        assertFalse(SleepWindow.shouldMask(cfg, at(2, 0), dndActive = true))
    }

    @Test
    fun `强制显示优先于时段和勿扰`() {
        val cfg = ConfigSnapshot(
            manualOverride = ConfigKeys.OVERRIDE_OFF,
            scheduleEnabled = true,
            windowStart = "23:00", windowEnd = "07:00",
            linkDnd = true,
        )
        assertFalse(SleepWindow.shouldMask(cfg, at(2, 0), dndActive = true))
    }

    @Test
    fun `强制屏蔽优先于关时段`() {
        val cfg = ConfigSnapshot(
            manualOverride = ConfigKeys.OVERRIDE_ON,
            scheduleEnabled = false,
        )
        assertTrue(SleepWindow.shouldMask(cfg, at(12, 0), dndActive = false))
    }

    @Test
    fun `勿扰联动`() {
        val cfg = ConfigSnapshot(
            scheduleEnabled = false,
            linkDnd = true,
            windowStart = "23:00", windowEnd = "07:00",
        )
        assertTrue(SleepWindow.shouldMask(cfg, at(12, 0), dndActive = true))
        assertFalse(SleepWindow.shouldMask(cfg, at(12, 0), dndActive = false))
    }

    @Test
    fun `非法时段解析失败返回null`() {
        assertTrue(SleepWindow.parseHhMm("25:00") == null)
        assertTrue(SleepWindow.parseHhMm("12:60") == null)
        assertTrue(SleepWindow.parseHhMm("bad") == null)
        assertTrue(SleepWindow.parseHhMm("23:00") == 23 * 60)
    }

    @Test
    fun `跟随系统时用系统窗口`() {
        val cfg = ConfigSnapshot(
            scheduleEnabled = true,
            windowStart = "23:00", windowEnd = "07:00",
            followSystem = true,
        )
        val system = "01:00" to "05:00"
        // 02:00 只在系统窗口内
        assertTrue(SleepWindow.shouldMask(cfg, at(2, 0), dndActive = false, systemWindow = system))
        // 06:00 只在手动窗口内（系统窗口已结束）
        assertFalse(SleepWindow.shouldMask(cfg, at(6, 0), dndActive = false, systemWindow = system))
    }

    @Test
    fun `跟随系统但取不到系统值时退回手动窗口`() {
        val cfg = ConfigSnapshot(
            scheduleEnabled = true,
            windowStart = "23:00", windowEnd = "07:00",
            followSystem = true,
        )
        assertTrue(SleepWindow.shouldMask(cfg, at(23, 30), dndActive = false, systemWindow = null))
        assertFalse(SleepWindow.shouldMask(cfg, at(12, 0), dndActive = false, systemWindow = null))
    }

    @Test
    fun `关闭跟随时忽略系统窗口`() {
        val cfg = ConfigSnapshot(
            scheduleEnabled = true,
            windowStart = "12:00", windowEnd = "13:00",
            followSystem = false,
        )
        val system = "01:00" to "05:00"
        // 02:00 落在系统窗口里，但手动窗口是 12:00-13:00，且未跟随 → 不屏蔽
        assertFalse(SleepWindow.shouldMask(cfg, at(2, 0), dndActive = false, systemWindow = system))
    }

    @Test
    fun `判定原因按强制 勿扰 时段的优先级返回`() {
        val cfg = ConfigSnapshot(
            scheduleEnabled = true,
            linkDnd = true,
            windowStart = "23:00", windowEnd = "07:00",
        )
        assertEquals(
            MaskReason.FORCED,
            SleepWindow.decide(
                cfg.copy(manualOverride = ConfigKeys.OVERRIDE_ON),
                at(2, 0), dndActive = true,
            ),
        )
        assertEquals(MaskReason.DND, SleepWindow.decide(cfg, at(12, 0), dndActive = true))
        assertEquals(MaskReason.TIME, SleepWindow.decide(cfg, at(2, 0), dndActive = false))
        assertEquals(null, SleepWindow.decide(cfg, at(12, 0), dndActive = false))
    }

    @Test
    fun `临时禁用期间不屏蔽 到期后自动恢复`() {
        val now = at(2, 0)
        val cfg = ConfigSnapshot(
            scheduleEnabled = true,
            windowStart = "23:00", windowEnd = "07:00",
            tempDisableUntilSec = (now / 1000).toInt() + 60,
        )
        assertEquals(null, SleepWindow.decide(cfg, now, dndActive = false))
        // 一分钟后到期，原时段判定继续生效
        assertEquals(
            MaskReason.TIME,
            SleepWindow.decide(cfg, now + 60_000, dndActive = false),
        )
    }

    @Test
    fun `临时禁用优先于强制和勿扰`() {
        val now = at(2, 0)
        val cfg = ConfigSnapshot(
            manualOverride = ConfigKeys.OVERRIDE_ON,
            linkDnd = true,
            tempDisableUntilSec = (now / 1000).toInt() + 60,
        )
        assertEquals(null, SleepWindow.decide(cfg, now, dndActive = true))
    }

    @Test
    fun `下一次窗口结束时刻取今天或明天`() {
        val cfg = ConfigSnapshot(windowStart = "23:00", windowEnd = "07:00")
        val todayEnd = SleepWindow.nextWindowEndMillis(cfg, null, at(2, 0))!!
        assertEquals(at(7, 0), todayEnd)

        val tomorrowEnd = SleepWindow.nextWindowEndMillis(cfg, null, at(12, 0))!!
        assertEquals(at(7, 0) + 24 * 60 * 60 * 1000, tomorrowEnd)

        val system = "01:00" to "05:00"
        assertEquals(
            at(5, 0),
            SleepWindow.nextWindowEndMillis(cfg.copy(followSystem = true), system, at(2, 0))!!,
        )
    }
}

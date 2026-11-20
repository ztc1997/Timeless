package com.ztc1997.timeless

import com.ztc1997.timeless.core.SystemSleepTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 系统作息换算：小时小数 → HH:mm。 */
class SystemSleepTimeTest {

    @Test
    fun `小时小数换算成分钟`() {
        // 真机取到的值：入睡 23.9 → 23:54，起床 6.83 → 06:50
        assertEquals("23:54", SystemSleepTime.toHhMm("23.9"))
        assertEquals("06:50", SystemSleepTime.toHhMm("6.83"))
        assertEquals("07:00", SystemSleepTime.toHhMm("7"))
        assertEquals("00:00", SystemSleepTime.toHhMm("0"))
        assertEquals("12:30", SystemSleepTime.toHhMm("12.5"))
    }

    @Test
    fun `越界与非法值返回 null`() {
        assertNull(SystemSleepTime.toHhMm(null))
        assertNull(SystemSleepTime.toHhMm(""))
        assertNull(SystemSleepTime.toHhMm("abc"))
        assertNull(SystemSleepTime.toHhMm("-1"))
        assertNull(SystemSleepTime.toHhMm("25"))
    }

    @Test
    fun `24 点附近按 00 点算`() {
        assertEquals("00:00", SystemSleepTime.toHhMm("24"))
        assertEquals("23:59", SystemSleepTime.toHhMm("23.99"))
    }

    @Test
    fun `编码解码`() {
        val raw = SystemSleepTime.encode("23:54", "06:50")
        assertEquals("23:54|06:50", raw)
        assertEquals("23:54" to "06:50", SystemSleepTime.decode(raw))
        assertNull(SystemSleepTime.decode(null))
        assertNull(SystemSleepTime.decode("garbage"))
        assertNull(SystemSleepTime.decode("23:54|99:99"))
    }
}

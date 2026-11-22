package com.ztc1997.timeless

import android.content.SharedPreferences
import com.ztc1997.timeless.core.ConfigKeys
import com.ztc1997.timeless.core.ConfigSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ConfigSnapshot.from() 字段映射单测：默认值、全字段、缺失回退、null 字符串。 */
class ConfigSnapshotTest {

    /** JVM 单测用的最小 SharedPreferences：只读 get*, 写入类方法不支持。 */
    private class FakePrefs(private val map: Map<String, Any?>) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? =
            (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    @Test
    fun `空配置回落到全部默认值`() {
        val snap = ConfigSnapshot.from(FakePrefs(emptyMap()))
        assertEquals(ConfigSnapshot.DEFAULT, snap)
        assertEquals(ConfigKeys.DEFAULT_LOG_LEVEL, snap.logLevel)
        assertEquals(ConfigKeys.DEFAULT_WINDOW_START, snap.windowStart)
        assertEquals(0, snap.sleepRequest)
    }

    @Test
    fun `全字段按各自默认值读取`() {
        val snap = ConfigSnapshot.from(
            FakePrefs(
                mapOf(
                    ConfigKeys.ENABLED to false,
                    ConfigKeys.SCHEDULE_ENABLED to false,
                    ConfigKeys.WINDOW_START to "22:30",
                    ConfigKeys.WINDOW_END to "06:15",
                    ConfigKeys.LINK_DND to true,
                    ConfigKeys.MANUAL_OVERRIDE to ConfigKeys.OVERRIDE_ON,
                    ConfigKeys.LOG_LEVEL to 3,
                    ConfigKeys.FOLLOW_SYSTEM to true,
                    ConfigKeys.SYSTEM_SLEEP_REQUEST to 42,
                    ConfigKeys.TEMP_DISABLE_UNTIL_SEC to 1_900_000_000,
                ),
            ),
        )
        assertFalse(snap.enabled)
        assertFalse(snap.scheduleEnabled)
        assertEquals("22:30", snap.windowStart)
        assertEquals("06:15", snap.windowEnd)
        assertTrue(snap.linkDnd)
        assertEquals(ConfigKeys.OVERRIDE_ON, snap.manualOverride)
        assertEquals(3, snap.logLevel)
        assertTrue(snap.followSystem)
        assertEquals(42, snap.sleepRequest)
        assertEquals(1_900_000_000, snap.tempDisableUntilSec)
    }

    @Test
    fun `类型不符与null字符串回落到默认值`() {
        val snap = ConfigSnapshot.from(
            FakePrefs(
                mapOf(
                    ConfigKeys.ENABLED to "not-a-boolean",
                    ConfigKeys.WINDOW_START to null,
                    ConfigKeys.LOG_LEVEL to "3",
                ),
            ),
        )
        // 类型不符：getXxx 的 as? 取不到，返回调用方传入的默认值
        assertEquals(ConfigKeys.DEFAULT_ENABLED, snap.enabled)
        assertEquals(ConfigKeys.DEFAULT_WINDOW_START, snap.windowStart)
        assertEquals(ConfigKeys.DEFAULT_LOG_LEVEL, snap.logLevel)
    }
}

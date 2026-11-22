package com.ztc1997.timeless.core

import android.content.SharedPreferences

/**
 * 配置快照。一次读取把所有配置项收进一个不可变对象，目标进程里的 hook 回调
 * 只读这一份，避免每次时钟刷新都跨进程读 SharedPreferences。
 *
 * 不引用 Xposed API 类型，模块 App 进程与目标进程都能安全加载。
 */
data class ConfigSnapshot(
    val enabled: Boolean = ConfigKeys.DEFAULT_ENABLED,
    val scheduleEnabled: Boolean = ConfigKeys.DEFAULT_SCHEDULE_ENABLED,
    val windowStart: String = ConfigKeys.DEFAULT_WINDOW_START,
    val windowEnd: String = ConfigKeys.DEFAULT_WINDOW_END,
    val linkDnd: Boolean = ConfigKeys.DEFAULT_LINK_DND,
    val manualOverride: Int = ConfigKeys.OVERRIDE_AUTO,
    val logLevel: Int = ConfigKeys.DEFAULT_LOG_LEVEL,
    /** 跟随系统自动学习的入睡/起床时间；此时 [windowStart]/[windowEnd] 仅作手动备用值。 */
    val followSystem: Boolean = ConfigKeys.DEFAULT_FOLLOW_SYSTEM,
    /**
     * 设置页发来的"立即刷新系统作息"请求序号，0 表示没有请求。
     *
     * 放进快照是为了让 hook 侧的配置变更回调能顺带收到它 —— 快照变了才会回调。
     */
    val sleepRequest: Int = 0,
    /**
     * 流体云「临时禁用」到期时刻（Unix 秒）；当前时间小于它时一律不屏蔽。
     * 到点后无需写回配置，判定自然恢复原有规则。
     */
    val tempDisableUntilSec: Int = 0,
) {
    companion object {
        val DEFAULT = ConfigSnapshot()

        /**
         * 从配置通道读取一份完整快照。模块 App 侧（remote prefs）与目标进程侧
         * （框架托管的 remote prefs）共用这一份字段映射，避免两处各读一遍、加了
         * 新字段漏改其中一处。
         *
         * 只依赖 Android SDK 的 [SharedPreferences]，两侧进程都持有；不碰 Xposed API。
         */
        fun from(p: SharedPreferences): ConfigSnapshot = ConfigSnapshot(
            enabled = p.getBoolean(ConfigKeys.ENABLED, ConfigKeys.DEFAULT_ENABLED),
            scheduleEnabled = p.getBoolean(
                ConfigKeys.SCHEDULE_ENABLED, ConfigKeys.DEFAULT_SCHEDULE_ENABLED,
            ),
            windowStart = p.getString(ConfigKeys.WINDOW_START, ConfigKeys.DEFAULT_WINDOW_START)
                ?: ConfigKeys.DEFAULT_WINDOW_START,
            windowEnd = p.getString(ConfigKeys.WINDOW_END, ConfigKeys.DEFAULT_WINDOW_END)
                ?: ConfigKeys.DEFAULT_WINDOW_END,
            linkDnd = p.getBoolean(ConfigKeys.LINK_DND, ConfigKeys.DEFAULT_LINK_DND),
            manualOverride = p.getInt(ConfigKeys.MANUAL_OVERRIDE, ConfigKeys.OVERRIDE_AUTO),
            logLevel = p.getInt(ConfigKeys.LOG_LEVEL, ConfigKeys.DEFAULT_LOG_LEVEL),
            followSystem = p.getBoolean(ConfigKeys.FOLLOW_SYSTEM, ConfigKeys.DEFAULT_FOLLOW_SYSTEM),
            sleepRequest = p.getInt(ConfigKeys.SYSTEM_SLEEP_REQUEST, 0),
            tempDisableUntilSec = p.getInt(ConfigKeys.TEMP_DISABLE_UNTIL_SEC, 0),
        )
    }
}

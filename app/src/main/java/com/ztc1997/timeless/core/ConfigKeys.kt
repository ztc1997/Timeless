package com.ztc1997.timeless.core

/**
 * 配置键约定。模块 App 进程与目标进程共用这份常量表，
 * 不引用任何 io.github.libxposed.api 类型（App 进程里没有这些类）。
 */
object ConfigKeys {
    /** remote preferences 分组名，两侧必须一致 */
    const val GROUP = "timeless_config"

    const val ENABLED = "enabled"
    const val SCHEDULE_ENABLED = "schedule_enabled"
    const val WINDOW_START = "window_start"
    const val WINDOW_END = "window_end"
    const val LINK_DND = "link_dnd"
    const val MANUAL_OVERRIDE = "manual_override"
    const val LOG_LEVEL = "log_level"

    /**
     * 流体云「临时禁用」的到期时刻（Unix 秒）。9999…0 表示没有临时禁用。
     * 用 Int 秒而不是 Long 毫秒：remote preferences 的两侧写入通道都支持 Int，
     * 到 2038 年才溢出，足够用。
     */
    const val TEMP_DISABLE_UNTIL_SEC = "temp_disable_until_sec"

    /** 跟随系统自动学习的作息（开启后上方两个时间只作为只读展示）。 */
    const val FOLLOW_SYSTEM = "follow_system"

    /**
     * 设置页请求 hook 侧「立刻去查一次系统作息」。
     *
     * 值是一个请求序号（毫秒时间戳），hook 侧查到后把同一个序号写回
     * [SystemSleepTime.ACK_KEY]，设置页据此结束"读取中"状态。
     * 它不是配置项，不进 [ALL_KEYS]（不镜像本地、不登记待补写）。
     */
    const val SYSTEM_SLEEP_REQUEST = "system_sleep_request"

    // ---- 默认值 ----
    const val DEFAULT_ENABLED = true
    const val DEFAULT_SCHEDULE_ENABLED = true
    const val DEFAULT_WINDOW_START = "23:00"
    const val DEFAULT_WINDOW_END = "07:00"
    const val DEFAULT_LINK_DND = false
    const val DEFAULT_LOG_LEVEL = 2
    const val DEFAULT_FOLLOW_SYSTEM = false

    /** 手动覆盖：自动（按时段/勿扰判定） */
    const val OVERRIDE_AUTO = -1
    /** 手动覆盖：强制关闭（醒来要看时间） */
    const val OVERRIDE_OFF = 0
    /** 手动覆盖：强制开启（立刻屏蔽） */
    const val OVERRIDE_ON = 1

    val ALL_KEYS = setOf(
        ENABLED, SCHEDULE_ENABLED, WINDOW_START, WINDOW_END,
        LINK_DND, MANUAL_OVERRIDE, LOG_LEVEL, FOLLOW_SYSTEM, TEMP_DISABLE_UNTIL_SEC,
    )
}

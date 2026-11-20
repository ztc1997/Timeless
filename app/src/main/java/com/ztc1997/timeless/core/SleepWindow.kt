package com.ztc1997.timeless.core

import java.util.Calendar

/**
 * 睡眠时段判定：纯逻辑，不依赖 Android/Xposed，便于单元测试。
 *
 * 判定优先级（从高到低）：
 *  1. 总开关 [ConfigSnapshot.enabled] 关 → 不屏蔽
 *  2. 手动覆盖 [ConfigSnapshot.manualOverride]：
 *     - [ConfigKeys.OVERRIDE_OFF] 强制关 → 不屏蔽（醒来要看时间）
 *     - [ConfigKeys.OVERRIDE_ON] 强制开 → 屏蔽
 *  3. 联动勿扰 [ConfigSnapshot.linkDnd] 且当前勿扰开启 → 屏蔽
 *  4. 固定时段 [ConfigSnapshot.scheduleEnabled] 且当前在窗口内 → 屏蔽
 *  5. 其余 → 不屏蔽
 *
 * 时段支持跨午夜（例如 23:00 → 07:00）。
 *
 * 开启 [ConfigSnapshot.followSystem] 时，窗口由系统自动学习的作息提供
 * （[systemWindow]）；取不到系统值就退回手动窗口，避免"开了跟随却完全不屏蔽"。
 */
object SleepWindow {

    /**
     * @param nowMillis 当前时间毫秒
     * @param dndActive 当前勿扰/睡眠模式是否开启
     * @param systemWindow 系统自动学习的 (入睡, 起床)；仅 followSystem 生效
     */
    fun shouldMask(
        config: ConfigSnapshot,
        nowMillis: Long,
        dndActive: Boolean,
        systemWindow: Pair<String, String>? = null,
    ): Boolean {
        if (!config.enabled) return false

        when (config.manualOverride) {
            ConfigKeys.OVERRIDE_OFF -> return false
            ConfigKeys.OVERRIDE_ON -> return true
        }

        if (config.linkDnd && dndActive) return true

        if (config.scheduleEnabled && isInEffectiveWindow(config, systemWindow, nowMillis)) {
            return true
        }

        return false
    }

    /** 当前生效的 (开始, 结束)：跟随系统且已取到系统值 → 用系统；否则用手动。 */
    fun effectiveWindow(
        config: ConfigSnapshot,
        systemWindow: Pair<String, String>?,
    ): Pair<String, String> =
        if (config.followSystem && systemWindow != null) systemWindow
        else config.windowStart to config.windowEnd

    private fun isInEffectiveWindow(
        config: ConfigSnapshot,
        systemWindow: Pair<String, String>?,
        nowMillis: Long,
    ): Boolean {
        val (start, end) = effectiveWindow(config, systemWindow)
        return isInWindow(start, end, nowMillis)
    }

    /** 判断 nowMillis 是否落在 [start, end) 窗口内，支持跨午夜。 */
    fun isInWindow(start: String, end: String, nowMillis: Long): Boolean {
        val startMin = parseHhMm(start) ?: return false
        val endMin = parseHhMm(end) ?: return false
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        return if (startMin <= endMin) {
            // 同一天内：start <= now < end
            nowMin in startMin until endMin
        } else {
            // 跨午夜：now >= start 或 now < end
            nowMin >= startMin || nowMin < endMin
        }
    }

    /** 解析 "HH:mm"，失败返回 null。 */
    fun parseHhMm(text: String): Int? {
        val parts = text.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }
}

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
    /**
     * 当前是否应屏蔽，以及**为什么**屏蔽。
     *
     * 流体云要在启动时说明原因，所以判定结果必须带原因；优先级与本函数一致：
     * 手动强制 > 勿扰联动 > 时段。任何新增触发条件都要在这里登记原因，
     * 不能只改 [shouldMask] 而让通知层猜。
     */
    fun decide(
        config: ConfigSnapshot,
        nowMillis: Long,
        dndActive: Boolean,
        systemWindow: Pair<String, String>? = null,
    ): MaskReason? {
        if (!config.enabled) return null
        // 流体云上点了「临时禁用」：到点之前一律不屏蔽，且原因也无从谈起。
        if (nowMillis / 1000 < config.tempDisableUntilSec) return null

        when (config.manualOverride) {
            ConfigKeys.OVERRIDE_OFF -> return null
            ConfigKeys.OVERRIDE_ON -> return MaskReason.FORCED
        }

        if (config.linkDnd && dndActive) return MaskReason.DND

        if (config.scheduleEnabled && isInEffectiveWindow(config, systemWindow, nowMillis)) {
            return MaskReason.TIME
        }

        return null
    }

    fun shouldMask(
        config: ConfigSnapshot,
        nowMillis: Long,
        dndActive: Boolean,
        systemWindow: Pair<String, String>? = null,
    ): Boolean = decide(config, nowMillis, dndActive, systemWindow) != null

    /**
     * 当前生效窗口的下一次结束时刻（本地时区），给「临时禁用」计算自动恢复时间。
     *
     * 只需要结束时刻本身：无论窗口是否跨午夜，下一次结束就是今天/明天的那个时刻。
     * 解析失败返回 null，调用方退回固定时长。
     */
    fun nextWindowEndMillis(
        config: ConfigSnapshot,
        systemWindow: Pair<String, String>?,
        nowMillis: Long,
    ): Long? {
        val endMin = parseHhMm(effectiveWindow(config, systemWindow).second) ?: return null
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        cal.apply {
            set(Calendar.HOUR_OF_DAY, endMin / 60)
            set(Calendar.MINUTE, endMin % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // 同一分钟也算下一次（秒数已归零，此时刻不晚于 now）
            add(Calendar.DATE, if (endMin > nowMin) 0 else 1)
        }
        return cal.timeInMillis
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

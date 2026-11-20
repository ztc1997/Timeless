package com.ztc1997.timeless.core

/**
 * 系统「个人作息」时间的表示与换算。
 *
 * ColorOS 的作息时间（场景服务 com.coloros.sceneservice 学习出来的入睡/起床时刻）
 * 以「小时的小数」存：23.9 → 23:54，6.83 → 06:50。本文件只做纯字符串/数值换算，
 * 不碰 Android API，供 hook 进程与模块 App 两侧共用。
 */
object SystemSleepTime {

    /** 系统作息缓存的落地位置：Settings.Secure 里的一个自定义键，值形如 "23:54|06:50"。 */
    const val SECURE_KEY = "timeless_system_sleep"

    /**
     * 回执：hook 侧完成了哪一次刷新请求（值 = 设置页发来的请求序号）。
     *
     * 设置页点开「跟随系统」时把序号写进配置，hook 进程查到作息后把同一序号
     * 写到这里，设置页轮询这个键就知道"这次请求已经办完"，可以停止转圈。
     */
    const val ACK_KEY = "timeless_system_sleep_ack"

    /** 分隔符：入睡|起床 */
    private const val SEPARATOR = "|"

    /**
     * 把 "23.9" 这种小时表示换算成 "23:54"。
     *
     * 四舍五入到分钟（6.83 → 409.8 分 → 06:50），越界或非法返回 null。
     * 24.0 视作 00:00（系统偶尔会把 23.98 这类值四舍五入到整 24 点）。
     */
    fun toHhMm(raw: String?): String? {
        val v = raw?.trim()?.toDoubleOrNull() ?: return null
        if (v.isNaN() || v < 0.0 || v >= 24.5) return null
        var total = Math.round(v * 60.0).toInt()
        total = ((total % 1440) + 1440) % 1440
        return formatHhMm(total / 60, total % 60)
    }

    fun formatHhMm(hour: Int, minute: Int): String =
        hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')

    /** 入睡 + 起床 → "23:54|06:50"，供写入 Settings.Secure。 */
    fun encode(start: String, end: String): String = "$start$SEPARATOR$end"

    /** 解析 "23:54|06:50"；任一段不合法返回 null。 */
    fun decode(raw: String?): Pair<String, String>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split(SEPARATOR)
        if (parts.size != 2) return null
        val start = parts[0].trim()
        val end = parts[1].trim()
        if (SleepWindow.parseHhMm(start) == null || SleepWindow.parseHhMm(end) == null) return null
        return start to end
    }
}

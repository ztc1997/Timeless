package com.ztc1997.timeless.core

/**
 * [LEGACY] 2026-11-20 假时间方案（`77:77`）启用后，主链路不再使用星月字符或 span。
 * 本类仅作为旧占位回退保留，勿在新 hook 中引用。
 *
 * 星月占位符：时间数字的每一位替换为月亮 ☾ 或四芒星 ✦，按分钟确定性变化。
 *
 * 设计要点（2026-11-17 定稿，同日修订）：
 * - 纯函数：每个字形的取值由 (当前分钟序号, 数字值) 经 splitmix64 散列决定。
 *   同一分钟内每次 hook 回调结果相同（不闪烁），跨分钟整组刷新（给用户
 *   "没有卡死"的活性信号）。
 * - **散列不包含段内下标**（修订）：插件时钟（KeyguardPersonalityClocks）是
 *   逐位写入的——每一位都是独立的单字符 tryUpdateText，无法知道自己在
 *   "HH:mm" 里的全局位置；状态栏则是整串一次替换。若散列含段内下标，
 *   两边图案必然不同（真机实测）。改为只依赖数字值后，整串 / 逐位 /
 *   SET_TEXT / 12 与 24 小时制全部天然一致。代价：同分钟内重复数字
 *   （如 "11:11"）整串同图案，可接受。
 * - systemui 与 launcher 两个进程独立计算同一分钟得到相同图案，无需跨进程同步；
 *   hook 回调每帧调用本函数也无状态、无锁。
 * - 各拦截面统一按"数字段"调用 [mask]：
 *     状态栏 getSmallTime 整串   "23:14" → "☾✦:✦☾"（冒号等非数字原样保留）
 *     插件时钟逐位写入          "2" → "☾"（每次写入就是一个数字）
 *     SET_TEXT 时/分字段        用系统当前时间构造 "%02d" 数字段
 * - 字形渲染（2026-11-17 二次修订）：状态栏/下拉菜单直接用纯字符占位（二者同源
 *   getSmallTime，宿主样式化会把 span 重建为纯 String，索性与下拉菜单观感一致）；
 *   插件时钟与锁屏 SET_TEXT 路径曾走 hook 层 StarMoonSpan 自绘（现已停用）。本类产出的
 *   ☾/✦ 字符同时是绘制标记与兜底（span 丢失时退回字体回退渲染）。
 * - 已删除旧的自定义横线占位符（ConfigKeys.PLACEHOLDER），星月为唯一占位样式。
 *
 * 不依赖任何 Xposed / Android 类型（core 层约定）。
 */
object Placeholder {

    /** 月亮：U+263E LAST QUARTER MOON */
    const val MOON = '☾'

    /** 星星：U+2726 BLACK FOUR-POINTED STAR（四芒星，替代旧五角星 ★） */
    const val STAR = '✦'

    /** 当前分钟序号。 */
    fun minuteSeed(nowMs: Long): Long = nowMs / 60_000L

    /**
     * 单个数字 → 星/月。确定性：同一 (分钟, 数字) 恒定，跨拦截面一致。
     * splitmix64 finalizer；常量为其无符号 64 位形式的有符号十进制
     * （0x9E3779B97F4A7C15 等超出 Kotlin Long 字面量范围，不能直接写）。
     */
    fun glyph(minuteSeed: Long, digit: Int): Char {
        var z = minuteSeed * -7046029254386353131L +
            digit * 2246822507L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293053741671L
        z = z xor (z ushr 31)
        return if (z and 1L == 0L) MOON else STAR
    }

    /**
     * 把文本中的每个连续数字段逐位替换为星/月，其余字符（冒号、AM/PM、日期后缀等）
     * 原样保留。text == null 时返回单个月亮兜底。
     * 由于散列只看数字值，"23:14" 整串与 "2"+"3"+":"+"1"+"4" 逐位结果完全一致。
     */
    fun mask(text: CharSequence?, minuteSeed: Long): String {
        if (text == null) return MOON.toString()
        val sb = StringBuilder(text.length)
        for (i in text.indices) {
            val c = text[i]
            sb.append(if (c in '0'..'9') glyph(minuteSeed, c - '0') else c)
        }
        return sb.toString()
    }
}

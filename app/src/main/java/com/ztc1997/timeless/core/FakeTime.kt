package com.ztc1997.timeless.core

/**
 * 假时间占位：睡眠时段把时钟换成**四位全同的不可能钟点**（`77:77`）。
 *
 * 设计要点（2026-11-20 定稿，方案推导见 `FAKE_CLOCK_PLAN.md`）：
 *
 * - **四位用同一个数字，取值 6..9**。`77:77`、`88:88` 是所有电子表通电时的
 *   全亮自检画面，用户不需要任何图例就知道"这不是坏点，是被接管了"。同时
 *   真实钟点里小时 ≤23、分钟 ≤59，四位全落在 6..9 时**必然不可能**。
 *
 * - **取值只由分钟序号决定，与原始数字无关**。这一点让三条互不相干的链路自动
 *   对齐：状态栏是整串替换（"18:24"）、插件时钟是逐位写入（"18" 和 "24" 各一次）、
 *   主题位图是按位取图（1→7、8→7…）。如果取值依赖原始数字，逐位写入时就必须
 *   知道自己是小时还是分钟，而 hook 拿不到这个信息；现在四位都取同一值，
 *   谁先谁后、整串还是逐位，结果都一样。
 *
 * - **每分钟整组跳一次**。散列只吃分钟序号：同一分钟内恒定（不闪烁），
 *   跨分钟必变（活性信号），且相邻两分钟不会是同一个数字。因为形态只有
 *   6/7/8/9 四种，"整块一起换"本身就很显眼，不会有人以为时钟卡住了。
 *
 * - **无法反推真实时间**。散列不吃时分本身；取 `ushr 1` 丢掉最低位，避免与
 *   `Placeholder.glyph` 用到的奇偶位相关。整块同时变化还顺带抹掉了
 *   「分钟进位时只有某一位变」这个侧信道。
 *
 * - 纯函数、无状态、无锁；systemui 与 launcher 两个进程独立计算得到同一结果。
 *   不依赖任何 Xposed / Android 类型（core 层约定）。
 */
object FakeTime {

    /** 假数字下界。真实时间里十位不可能取到 6 以上。 */
    const val MIN_DIGIT = 6

    /** 假数字个数：6、7、8、9。 */
    const val DIGIT_SPAN = 4

    /**
     * 「与上一分钟不同」的回溯深度上限。取值只有 4 种，撞值率 1/4，
     * 链长服从几何分布；4^-64 已不可能发生，正常路径只回溯一层。
     */
    private const val MAX_LOOKBACK = 64

    /** 当前分钟序号。与 [Placeholder.minuteSeed] 同定义，两者可共用。 */
    fun minuteSeed(nowMs: Long): Long = nowMs / 60_000L

    /**
     * 该分钟使用的假数字（6..9）。所有位共用这一个值。
     *
     * [original] 仅为调用方兼容保留：逐位链路本来就只拿得到"某一位是多少"，
     * 这里刻意**不看**它 —— 看了反而会让不同位置的数字算出不同结果。
     */
    @Suppress("UNUSED_PARAMETER")
    fun digit(seed: Long, original: Int): Int = effectiveDigit(seed)

    /** 只缓存最近一次求出的 seed→假数字；逐位回调的 seed 天然按分钟推进，命中率很高。 */
    private data class DigitCache(val seed: Long, val digit: Int)

    @Volatile
    private var cache: DigitCache? = null

    /**
     * 该分钟真正使用的假数字：raw 值与上一分钟撞车时顺移一位。
     *
     * 相邻两分钟必不同这一性质只取决于假数字本身，不必再对整串文本做递归 mask；
     * 这里对 6..9 这四个整数做有界回溯，避免高频 TextView 回调里反复构造字符串。
     */
    private fun effectiveDigit(seed: Long, depth: Int = 0): Int {
        cache?.let { if (it.seed == seed) return it.digit }
        var cur = raw(seed)
        if (seed > 0L && depth < MAX_LOOKBACK && cur == effectiveDigit(seed - 1, depth + 1)) {
            cur = next(cur)
        }
        cache = DigitCache(seed, cur)
        return cur
    }

    private fun raw(seed: Long): Int {
        var z = seed + -7046029254386353131L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293053741671L
        z = z xor (z ushr 31)
        return MIN_DIGIT + ((z ushr 1) % DIGIT_SPAN).toInt()
    }

    /**
     * 把文本里的每个数字字符换成同一个假数字，其余字符（冒号、AM/PM 等）原样保留。
     * `"18:24"` → `"77:77"`。
     *
     * **保证相邻两分钟的返回值不同**：4 个取值撞车率 1/4，真机上表现为"偶尔有
     * 一分钟没动"。撞了就把整串数字一起顺移到下一个值（保持四位全同）。
     *
     * 空输入返回两位兜底数字（位图链路要求至少两位）。
     */
    fun mask(text: CharSequence?, seed: Long): String {
        if (text.isNullOrEmpty()) return "$MIN_DIGIT$MIN_DIGIT"
        val v = effectiveDigit(seed)
        val sb = StringBuilder(text.length)
        for (i in text.indices) {
            val c = text[i]
            sb.append(if (c in '0'..'9') ('0' + v) else c)
        }
        return sb.toString()
    }

    /** 6→7→8→9→6，撞车时整组顺移一位。 */
    private fun next(v: Int): Int = MIN_DIGIT + (v - MIN_DIGIT + 1) % DIGIT_SPAN

    /** 固定的两位假数字，用于只知道"这是 1~2 位数字段"、但拿不到原值的兜底场合。 */
    fun twoDigits(seed: Long): String = mask("00", seed)
}

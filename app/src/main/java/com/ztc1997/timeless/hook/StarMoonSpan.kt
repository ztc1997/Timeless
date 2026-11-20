package com.ztc1997.timeless.hook

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import com.ztc1997.timeless.core.Placeholder
import kotlin.math.max

/**
 * [LEGACY] 2026-11-20 起主链路改用假时间，宿主用自身字形排版；本 span 仅留作回退。
 *
 * 星月自绘 span（方案 2，2026-11-17；同日二次修订；2026-11-19 抽出字形）。
 *
 * 背景：时钟专用字体只覆盖数字/冒号，☾/✦ 走字体回退后（a）垂直位置随各面
 * 时钟字体的度量上下漂移——桌面偏下、AOD/锁屏偏上；（b）☾ 与 ✦ 是两个独立
 * 设计的 Unicode 字形，同一串里高度不一致。字体层面无解，改为自绘：
 *
 * - [getSize] 不修改行度量（fm 保持 paint 原值），布局行高与真实数字完全一致；
 * - 字形高度 = 该 paint 下数字 "0" 的 ink 高（cap band），与本地真实数字等高；
 * - **单元格宽度 = 被替换数字自身的 advance**（二次修订核心）：每个 span 携带
 *   原数字字符，宽度取 paint.measureText(原数字)。此前统一用 "0" 的宽度，
 *   遇到窄数字（如 "1"）整串比真实时间宽，锁屏→桌面过场动画按真实度量收敛，
 *   动画结束落点一比即"跳一下"（真机实测）。宽度逐位继承后，占位串与真实
 *   数字串度量完全一致，任何对真实数字无缝的动画对占位符同样无缝；
 * - 形状（星/月的路径、比例、压扁规则）见 [StarMoonGlyph]——那是唯一实现，
 *   引擎链路的锁屏/AOD 时钟也调它，保证同一台机器上星月长得一样。
 *
 * span 逐字符挂在 ☾/✦ 上；若宿主链路（如 RedOne 样式化）把文本重建为纯
 * String 丢掉 span，字符仍退回字体回退渲染——功能不丢，只是回到旧观感。
 *
 * 适用范围（2026-11-17 修订）：仅插件时钟（PersonalityClocksFeature）与锁屏
 * SET_TEXT 路径（ClockMaskFeature#writeMask）。状态栏/下拉菜单（getSmallTime
 * RETURN_VALUE）已改为纯字符占位，不再挂本 span。
 *
 * 2026-11-20 防回归记录：曾尝试两种"动画收尾跳变"修法，均已回退——
 *  1) 写入时快照 cap/cellW：AOD 写入时字体未加载完，且锁屏 span 会残留到 AOD
 *     复用 view，快照把 1034 画进 468 格子 → AOD 星月巨大被裁；
 *  2) openRtTextPaint(false) 取"目标字号"覆盖 paint 度量：锁屏各位是独立
 *     TextView、paint 状态不同，单一全局目标字号被各位竞争覆盖 → 每位星月
 *     大小不一致。
 * 结论：span 始终实时读当前 paint 度量，星月与真实数字同高同宽，任何场景
 * 大小都正确；跳变必须在绘制/动画链路解决，不要在 span 度量里补偿。
 */
class StarMoonSpan(
    private val digit: Char = '0',
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        // 保持行度量与主字体一致（不放大行高、不挪基线上下文）
        if (fm != null) {
            val f = paint.fontMetrics
            fm.top = f.top.toInt()
            fm.ascent = f.ascent.toInt()
            fm.descent = f.descent.toInt()
            fm.bottom = f.bottom.toInt()
            fm.leading = f.leading.toInt()
        }
        return max(cellWidth(paint).toInt(), 1)
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        baseline: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val star = text[start] == Placeholder.STAR
        val w = cellWidth(paint)
        val capH = StarMoonGlyph.capHeight(paint)
        if (capH <= 0f) return
        diagDraw(paint, capH) // 临时诊断，收口删除
        val center = StarMoonGlyph.capCenterOffset(paint)
        StarMoonGlyph.draw(
            canvas = canvas,
            paint = paint,
            cx = x + w / 2f,
            cy = baseline + center,
            capH = capH,
            cellW = w,
            star = star,
        )
    }

    /**
     * 单元格宽度：被替换数字自身的 advance（宽度度量与真实数字逐位一致），
     * 这样占位串总宽 == 真实时间串总宽，过场动画落点不跳。digit 缺省 '0'。
     */
    private fun cellWidth(paint: Paint): Float {
        val w = paint.measureText(digit.toString())
        return if (w > 0f) w else paint.textSize * 0.55f
    }

    companion object {
        /** 临时诊断（收口删除）：span 实际绘制时的 paint 字号变化（按 paint 去重）。 */
        private val lastSeenPaintTs =
            java.util.Collections.synchronizedMap(mutableMapOf<Int, Float>())

        private fun diagDraw(paint: Paint, capH: Float) {
            val key = System.identityHashCode(paint)
            val ts = paint.textSize
            val prev = lastSeenPaintTs[key]
            if (prev == null || kotlin.math.abs(prev - ts) > 0.5f) {
                lastSeenPaintTs[key] = ts
                com.ztc1997.timeless.core.GLog.i(
                    "DRAW p=${Integer.toHexString(key)} ts=${ts.toInt()} cap=${capH.toInt()}",
                )
            }
        }

        /**
         * 把 [Placeholder.mask] 产出的星月串包成 SpannableString，逐字符挂
         * [StarMoonSpan]。[original] 是被替换的原始文本（与 masked 等长，
         * mask 逐位替换保持长度）：星月位继承原数字的 advance 宽度；
         * original 缺失/长度不符/该位不是数字时退回 '0' 宽度。
         * 没有星月字符时原样返回（零开销直通）。
         */
        fun spanned(masked: String, original: CharSequence? = null): CharSequence {
            if (masked.indexOf(Placeholder.MOON) < 0 && masked.indexOf(Placeholder.STAR) < 0) {
                return masked
            }
            val ss = SpannableString(masked)
            for (i in masked.indices) {
                if (masked[i] == Placeholder.MOON || masked[i] == Placeholder.STAR) {
                    val d = original.getOrNull(i)
                    val digit = if (d != null && d in '0'..'9') d else '0'
                    ss.setSpan(
                        StarMoonSpan(digit),
                        i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            return ss
        }

        private fun CharSequence?.getOrNull(i: Int): Char? =
            if (this != null && i in indices) this[i] else null
    }
}

package com.ztc1997.timeless.hook

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.ztc1997.timeless.core.Placeholder
import kotlin.math.max
import kotlin.math.min

/**
 * [LEGACY] 假时间方案启用后不再进入主绘制链路，仅为旧 StarMoonSpan/Drawable 回退保留。
 *
 * 星月字形的唯一绘制实现。
 *
 * 为什么抽出来（2026-11-19）：星月有两个完全不同的落点——
 *  - **文本链路**（桌面/锁屏 SET_TEXT/状态栏）：宿主用字体排版，靠
 *    [StarMoonSpan] 的 ReplacementSpan 回调在文字位置上画；
 *  - **引擎链路**（第三方主题的锁屏/AOD）：VariUIEngine / EGView 用位图或
 *    `canvas.drawText` 自己画数字，没有文本可替换，只能在绘制方法里按原
 *    数字的位置和尺寸直接画。
 *
 * 两条链路的形状必须一模一样，否则同一台手机上桌面和锁屏是两种星月。
 * 所以形状只有这一份实现，[StarMoonSpan] 与 [EngineClockPainter] 都调它。
 *
 * 坐标约定：字形以 (cx, cy) 为中心，capH 是「数字墨迹高度」（= 直径），
 * cellW 是该位数字的宽度（窄位如 "1" 会把星横向压扁，不越出格子）。
 */
object StarMoonGlyph {

    /** 文本链路的月牙宽度：占单元格宽的比例（保守，避免与相邻数字重叠）。 */
    const val MOON_WIDTH_TEXT = 0.40f

    /**
     * 位图链路的月牙宽度：位图格子本身就是这一个数字的包围盒，可以放宽到
     * [MOON_WIDTH_BITMAP]，否则月牙在大字号下会细长得像括号（2026-11-19 用户
     * 反馈「桌面上的月亮太长了」）。
     */
    const val MOON_WIDTH_BITMAP = 0.62f

    /**
     * 位图链路整体收缩比例：字形尖端如果正好压在格子边缘，会被 TextView 的
     * compound drawable 裁掉（用户反馈「上下被切掉了一部分」），留一点余量。
     */
    const val BITMAP_SHRINK = 0.94f

    private val digitBounds = Rect()
    private val path = Path()
    private val cutPath = Path()
    private val outerRect = RectF()
    private val cutRect = RectF()

    /** 数字 → 该分钟下应为星还是月。与 [Placeholder.mask] 的散列同源。 */
    fun isStar(minuteSeed: Long, digit: Int): Boolean =
        Placeholder.glyph(minuteSeed, digit) == Placeholder.STAR

    /**
     * 以 (cx, cy) 为中心画一个字形。
     *
     * @param capH 字形高度（数字墨迹高），星月直径同此值
     * @param cellW 单元格宽度（原数字的 advance/位图宽）
     */
    fun draw(
        canvas: Canvas,
        paint: Paint,
        cx: Float,
        cy: Float,
        capH: Float,
        cellW: Float,
        star: Boolean,
        moonWidth: Float = MOON_WIDTH_TEXT,
    ) {
        if (!(capH > 0f) || !(cellW > 0f)) return
        val style = paint.style
        paint.style = Paint.Style.FILL
        path.reset()
        if (star) {
            drawStar(path, cx, cy, capH, cellW)
        } else {
            drawMoon(path, cutPath, outerRect, cutRect, cx, cy, capH, cellW, moonWidth)
        }
        canvas.drawPath(path, paint)
        paint.style = style
    }

    /**
     * 把字形画进一个矩形格子（引擎的数字位图格子：left/top/right/bottom）。
     * 格子就是原数字的包围盒，因此星月与原数字同高同宽同位。
     */
    fun drawInBox(
        canvas: Canvas,
        paint: Paint,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        star: Boolean,
        moonWidth: Float = MOON_WIDTH_TEXT,
    ) {
        val w = right - left
        val h = bottom - top
        if (!(w > 0f) || !(h > 0f)) return
        draw(canvas, paint, left + w / 2f, top + h / 2f, h, w, star, moonWidth)
    }

    /**
     * 取「数字墨迹高」：该 paint 下 "0" 的 ink 高，也就是真实数字的高度。
     * 文本链路（span）与 AOD 的 drawText 链路都用它，保证与真实数字等高。
     *
     * 注意（2026-11-20）：不要对这里的 ink 高做「与 textSize 比值」的归一化。
     * 「灵感接续」主题的数字是特殊字体，textSize=339 时 getTextBounds("0")=1034
     * 是**真实数字墨迹高**（真机截图里数字就撑满这么高）。归一化会把星月改小，
     * 而冒号是主题自己渲染的、大小不变，导致星月与冒号比例失调。
     */
    fun capHeight(paint: Paint): Float {
        paint.getTextBounds("0", 0, 1, digitBounds)
        return (digitBounds.bottom - digitBounds.top).toFloat()
    }

    /** 数字墨迹带的垂直中心相对基线的偏移（负值向上）。 */
    fun capCenterOffset(paint: Paint): Float {
        paint.getTextBounds("0", 0, 1, digitBounds)
        return (digitBounds.top + digitBounds.bottom) / 2f
    }

    /**
     * 实芯四芒星（凹边曲线，同应用图标风格）。
     * 高 = 数字 ink 高；宽超出窄单元格时按 cell 等比压缩横向（如 "1" 位）。
     * 臂根用双控制点贴近中心的 cubic 贝塞尔收束，凹得深才不会画成胖菱形
     * （真机 0.22r quad 版实测过肥，已改）。
     */
    private fun drawStar(path: Path, cx: Float, cy: Float, capH: Float, cellW: Float) {
        val r = capH * 0.50f
        val maxHalfW = cellW * 0.5f * 0.92f
        val sx = if (r > maxHalfW) maxHalfW / r else 1f
        val ri = r * 0.10f
        path.moveTo(cx, cy - r)
        path.cubicTo(cx + ri * sx, cy - ri, cx + ri * sx, cy - ri, cx + r * sx, cy)
        path.cubicTo(cx + ri * sx, cy + ri, cx + ri * sx, cy + ri, cx, cy + r)
        path.cubicTo(cx - ri * sx, cy + ri, cx - ri * sx, cy + ri, cx - r * sx, cy)
        path.cubicTo(cx - ri * sx, cy - ri, cx - ri * sx, cy - ri, cx, cy - r)
        path.close()
    }

    /**
     * 实芯月牙（开口朝右，近似 ☾）：大椭圆挖掉右上偏移的同比例椭圆。
     * 水平半径受单元格宽度约束（宽 < 高），避免月牙比数字列宽。
     * 挖除圆偏移 0.55rx、同半径 → 月牙厚度 ≈ 0.45rx，尖角收干净
     * （真机 0.62 偏移 + 0.92 半径版实测过厚过钝，已改）。
     */
    private fun drawMoon(
        path: Path,
        cutPath: Path,
        outerRect: RectF,
        cutRect: RectF,
        cx: Float,
        cy: Float,
        capH: Float,
        cellW: Float,
        moonWidth: Float,
    ) {
        val ry = capH * 0.50f
        val rx = min(ry, cellW * moonWidth)
        outerRect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        path.addOval(outerRect, Path.Direction.CW)
        // 挖除圆心向右上偏移，留下左侧月牙
        val cutCx = cx + rx * 0.55f
        val cutCy = cy - ry * 0.12f
        cutRect.set(cutCx - rx, cutCy - ry, cutCx + rx, cutCy + ry)
        cutPath.addOval(cutRect, Path.Direction.CW)
        path.op(cutPath, Path.Op.DIFFERENCE)
        cutPath.reset()
        // 挖除后月牙 ink 全在单元格左半边（外椭圆以 cx 居中、挖除圆右偏），
        // 视觉中心偏左；取实际包围盒水平平移，让 ink 几何中心回到 cx。
        path.computeBounds(outerRect, true)
        path.offset(cx - (outerRect.left + outerRect.right) / 2f, 0f)
    }

    /** 供诊断/日志用：格子尺寸的取整，避免日志里出现一长串小数。 */
    fun describe(capH: Float, cellW: Float): String =
        "capH=${max(0f, capH).toInt()} cellW=${max(0f, cellW).toInt()}"

    /**
     * 量出位图的「墨迹」占比：`[left, top, right, bottom]`，取值 0..1（相对位图宽高）。
     *
     * 为什么要量（2026-11-19 用户反馈「桌面月亮太长、上下被切」）：主题数字位图
     * 是 175×215 的格子，真实数字墨迹往往只占其中一部分（上下有留白）。若直接拿
     * 格子当字形高度，星月就会被拉长顶到格子边缘，尖端还被裁掉。按真实墨迹定位后，
     * 星月与主题数字同高、同位置、同留白。
     *
     * 全透明（量不出墨迹）时返回 null，调用方按整体格子处理。
     */
    fun inkFraction(bmp: android.graphics.Bitmap): FloatArray? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val step = max(1, max(w, h) / 64)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if ((bmp.getPixel(x, y) ushr 24) and 0xFF > 16) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += step
            }
            y += step
        }
        if (maxX < 0 || maxY < 0) return null
        return floatArrayOf(
            minX.toFloat() / w,
            minY.toFloat() / h,
            (maxX + 1).toFloat() / w,
            (maxY + 1).toFloat() / h,
        )
    }
}

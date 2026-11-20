package com.ztc1997.timeless.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.min

/**
 * [LEGACY] 假时间方案改在取图源头换数字，本度量与 StarMoonDrawable 仅留作回退。
 *
 * 主题数字位图 → 星月的公共度量。
 *
 * 桌面与锁屏的数字位图都是「格子比墨迹大」：175×215 的格子里真实数字往往只占
 * 中间一段（上下留白）。直接拿格子当字形高度会得到一枚被拉长、还顶到格子边缘
 * 被裁掉的星月（2026-11-19 用户真机反馈「月亮太长、上下被切」）。所以先量出
 * 原图的墨迹包围盒（占比 0..1），再把星月画在这个包围盒里。
 */
object DigitInk {

    /** 最近一次量到的墨迹高度占比（相对格子高），冒号补画时用来对齐数字。 */
    @Volatile
    var heightFraction: Float = 1f

    /** 最近一次取样到的数字颜色（冒号沿用，主题是黑/白/金都跟得住）。 */
    @Volatile
    var color: Int = 0

    /** 最近一次见到的数字位图原生高度：冒号没有自己的尺寸信息，靠它定大小。 */
    @Volatile
    var referenceHeight: Int = 0

    fun update(height: Float, ink: Int) {
        if (height > 0f) heightFraction = height.coerceIn(0.2f, 1f)
        if (ink != 0) color = ink
    }

    /** 位图的代表色：按 alpha 加权抽样，避免把透明背景算成黑色。 */
    fun averageColor(bmp: Bitmap): Int? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val step = max(1, max(w, h) / 32)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = bmp.getPixel(x, y)
                if ((p ushr 24) and 0xFF >= 48) {
                    r += Color.red(p)
                    g += Color.green(p)
                    b += Color.blue(p)
                    n++
                }
                x += step
            }
            y += step
        }
        if (n == 0L) return null
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** 把一个 drawable 离屏画一遍，返回 (代表色, 墨迹占比)，都取不到时返回 null。 */
    fun measure(d: Drawable): Pair<Int, FloatArray?>? {
        (d as? BitmapDrawable)?.bitmap?.let { bmp ->
            val color = averageColor(bmp)
            val ink = StarMoonGlyph.inkFraction(bmp)
            if (color != null || ink != null) return (color ?: 0) to ink
        }
        return runCatching {
            val size = 64
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val saved = Rect(d.bounds)
            d.bounds = Rect(0, 0, size, size)
            d.draw(Canvas(bmp))
            d.bounds = saved
            val color = averageColor(bmp)
            val ink = StarMoonGlyph.inkFraction(bmp)
            bmp.recycle()
            if (color == null && ink == null) null else (color ?: 0) to ink
        }.getOrNull()
    }
}

/**
 * 主题「图片数字」→ 星月占位。
 *
 * 全局/第三方主题下，桌面（以及部分锁屏样式）的时间不是文字，而是主题资源里的
 * 0-9 数字位图（`Utils.getHostClockNum(context, digit)` 取图，再
 * `TextView.setCompoundDrawables` 贴到每个数字位）。文本钩子在这条链路上完全
 * 落空，之前只能把数字位 alpha=0 —— 结果是「桌面时钟直接消失」，与状态栏/锁屏的
 * 星月占位不一致（2026-11-19 换机后用户实测）。
 *
 * 所以在取图源头包一层：intrinsic 尺寸原样沿用（宿主的字号缩放、字宽改写全部照旧，
 * 布局不会动），绘制时在原数字**墨迹的包围盒**里画星月 —— 与主题数字同高、同位置、
 * 同留白。颜色从原位图按 alpha 加权取样；星/月的选择用与桌面文本链路同一份散列
 * （分钟种子 + 数字值），因此同一分钟桌面与锁屏图案一致。
 * [LEGACY] 主链路已不在取图源头包星月 drawable，类仅留作回退。
 */
class StarMoonDrawable(
    private val origin: Drawable,
    private val star: Boolean,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 宿主可能按淡入淡出调 alpha（setAlpha），转成 paint 的整体透明度。 */
    private var alphaScale = 255

    private val metrics by lazy { DigitInk.measure(origin) }

    init {
        if (origin.intrinsicHeight > 0) DigitInk.referenceHeight = origin.intrinsicHeight
    }

    private val ink: Int get() = metrics?.first?.takeIf { it != 0 } ?: Color.WHITE

    private val inkBox: FloatArray? get() = metrics?.second

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty || alphaScale <= 0) return
        val f = inkBox
        var l = b.left + b.width() * (f?.get(0) ?: 0f)
        var t = b.top + b.height() * (f?.get(1) ?: 0f)
        var r = b.left + b.width() * (f?.get(2) ?: 1f)
        var bottom = b.top + b.height() * (f?.get(3) ?: 1f)
        // 尖端留一点余量，避免被 compound drawable 的边界裁掉。
        val shrinkX = (r - l) * (1f - StarMoonGlyph.BITMAP_SHRINK) / 2f
        val shrinkY = (bottom - t) * (1f - StarMoonGlyph.BITMAP_SHRINK) / 2f
        l += shrinkX
        r -= shrinkX
        t += shrinkY
        bottom -= shrinkY
        val color = ink
        // 记下来给「主题没提供分隔符」时补画的冒号用。
        DigitInk.update(if (f != null) f[3] - f[1] else 1f, color)
        paint.color = color
        paint.alpha = alphaScale
        StarMoonGlyph.drawInBox(
            canvas = canvas,
            paint = paint,
            left = l,
            top = t,
            right = r,
            bottom = bottom,
            star = star,
            moonWidth = StarMoonGlyph.MOON_WIDTH_BITMAP,
        )
    }

    /** 尺寸完全交给原 drawable：宿主用它算字宽与行高。 */
    override fun getIntrinsicWidth(): Int = origin.intrinsicWidth

    override fun getIntrinsicHeight(): Int = origin.intrinsicHeight

    override fun setAlpha(alpha: Int) {
        alphaScale = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 时间分隔符（冒号）。
 *
 * 桌面那套数字位图模板**没有**给启动器提供分隔符图：`Utils.getHostClockSymbol()`
 * 在 launcher 侧返回 null，`applyDigitDrawable` 因此直接 return，冒号格子里什么
 * 都不画（2026-11-19 用户反馈「桌面上的冒号没了」）。
 *
 * 这里自己补两个圆点：高度沿用刚量到的主题数字位图高度（[DigitInk.referenceHeight]），
 * 圆点半径再乘数字墨迹占比，保证与星月看起来同高；颜色沿用数字取样的颜色。
 */
class ColonDrawable : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var alphaScale = 255

    private val cellHeight: Int get() = DigitInk.referenceHeight.takeIf { it > 0 } ?: 96

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty || alphaScale <= 0) return
        val h = b.height().toFloat()
        // 数字墨迹高（同一个刷新里会先量完时/分数字，这里是它们的占比）。
        val inkH = h * DigitInk.heightFraction
        val r = max(2f, inkH * 0.085f)
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val gap = inkH * 0.17f
        paint.color = DigitInk.color.takeIf { it != 0 } ?: Color.WHITE
        paint.alpha = alphaScale
        canvas.drawCircle(cx, cy - gap, r, paint)
        canvas.drawCircle(cx, cy + gap, r, paint)
    }

    /** 宽度按冒号习惯收窄（约为高度的 0.3）。 */
    override fun getIntrinsicWidth(): Int = max(2, (cellHeight * 0.30f).toInt())

    override fun getIntrinsicHeight(): Int = cellHeight

    override fun setAlpha(alpha: Int) {
        alphaScale = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

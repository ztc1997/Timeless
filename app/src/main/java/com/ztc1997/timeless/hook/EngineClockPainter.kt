package com.ztc1997.timeless.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.ztc1997.timeless.core.GLog
import com.ztc1997.timeless.core.Placeholder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * [LEGACY] 2026-11-20 引擎时钟改由 EngineClockInjector 喂假数字，由宿主自绘；
 * 本绘制器不再被调用，仅留作回退参考，勿在新链路引用。
 *
 * 第三方主题锁屏 / AOD 时钟的「星月占位」绘制。
 *
 * 背景（2026-11-19 真机取证）：这两处的时钟不是文本控件，宿主也完全不认
 * visibility / alpha（引擎自己遍历子元素直接 `view.draw(canvas)`），唯一能
 * 干预的入口就是绘制方法本身。只把绘制跳过会留下空白，与桌面/状态栏的星月
 * 不一致，所以在同一个 canvas、同一批坐标上把星月画回去：
 *
 * - **锁屏** `com.zplus.engine.lk_view.TimeView#draw`：数字是 0-9 位图数组
 *   `e[0..9]` + 冒号 `e[10]`，布局是「时位1、时位2、冒号、分位1、分位2」，
 *   逐位横排，位间间隔 `g`。位图宽高就是数字格子的宽高，照原样一格格画星月。
 * - **AOD** `com.oplus.egview.widget.TimeView#onDraw`：`drawVerticalTime` 把
 *   `mTextLines[0]="HH"`、`[1]="mm"` 两行用 `mTextPaint` 画在中间，
 *   行距 = `fontSpacing + mExtraLineGap`，首行基线 = `-fontMetrics.top`。
 *   按同样的两行、同样的基线、同样的字宽画星月；横向单行模式读 `mText`
 *   从 paddingLeft 起画。
 *
 * 字形本身来自 [StarMoonGlyph]——与桌面/状态栏是同一份实现，形状完全一致。
 * 锁屏的颜色从主题数字位图里取样（对面可能是白、金、渐变），AOD 直接用
 * `mTextPaint` 的颜色/渐变，因此星月会跟着主题走而不是硬编码白色。
 */
object EngineClockPainter {

    enum class Kind { ZPLUS_TIME, EG_TIME, ZPLUS_IMAGE_NUMBER, ZPLUS_IMAGE_ELEMENT }

    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 主题数字位图 → 取样得到的颜色。位图随视图存活，用弱引用键不会留垃圾。 */
    private val colorCache = java.util.WeakHashMap<Any, Int>()

    /** 画一次星月；返回 false 表示这块时钟不归我们管（字段缺失等），调用方按空白处理。 */
    fun draw(kind: Kind, canvas: Canvas, view: View): Boolean = runCatching {
        val seed = Placeholder.minuteSeed(System.currentTimeMillis())
        when (kind) {
            Kind.ZPLUS_TIME -> drawZplusTime(canvas, view, seed)
            Kind.EG_TIME -> drawEgTime(canvas, view, seed)
            Kind.ZPLUS_IMAGE_NUMBER -> drawImageNumber(canvas, view, seed)
            Kind.ZPLUS_IMAGE_ELEMENT -> drawImageElement(canvas, view, seed)
        }
    }.getOrElse {
        GLog.w("引擎星月绘制失败：$kind", it)
        false
    }

    // ------------------------------------------- 锁屏（模板里的逐位数字图片）

    /** 主题模板的时间数字图片名：`time1/time_2.png`（真机实测，2026-11-19）。 */
    private val timeDigitPattern = Regex("""(?i).*time[^/]*/(?:.*?_)?(\d)\.(?:png|webp|jpg)$""")

    /** 从图片元素名里取时间数字：命中返回 0..9，否则 null（日期/装饰一律放行）。 */
    internal fun timeDigitOf(src: String?): Int? {
        if (src.isNullOrEmpty()) return null
        if (src.contains("date", ignoreCase = true)) return null
        val m = timeDigitPattern.matchEntire(src) ?: return null
        return m.groupValues.getOrNull(1)?.toIntOrNull()
    }

    /**
     * 通用图片元素 `com.zplus.engine.lk_view.ImageElementView` 画的时间数字位。
     *
     * 换机后（PJD110 / ColorOS 17.0.0.100）锁屏那串时间是这套模板用**逐位 PNG**
     * 拼出来的：每个数字位就是一个 `ImageElementView`，`e` 字段是当前图片名
     * （`time1/time_2.png`），由模板表达式按分钟换图。既不是文本（`tryUpdateText`
     * 收不到），也不是 `TimeView`/`ImageNumber`（这两个钩子挂上了但一次没回调）。
     *
     * 处理：只拦名字里带 `time` 的数字图片，在同一个格子里改画星月；其余图片
     * （日期、卡通装饰、快捷按钮）照原样走 ——— 用户要求日期不屏蔽。
     */
    private fun drawImageElement(canvas: Canvas, view: View, seed: Long): Boolean {
        val src = Reflect.findField(view.javaClass, "e")?.get(view) as? String ?: return false
        val digit = timeDigitOf(src) ?: return false
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 0f || h <= 0f) return false
        val bmp = Reflect.findField(view.javaClass, "g")?.get(view) as? Bitmap
        val hostPaint = Reflect.findField(view.javaClass, "a")?.get(view) as? Paint
        val sampled = bmp?.let { averageColor(it) } ?: 0
        glyphPaint.color = if (sampled != 0) sampled else (hostPaint?.color ?: Color.WHITE)
        glyphPaint.alpha = hostPaint?.alpha ?: 255
        glyphPaint.shader = null
        // 与原数字同高同位置：按位图墨迹占比收进真实数字的包围盒（避免顶格裁切）。
        val f = bmp?.let { StarMoonGlyph.inkFraction(it) }
        var l = w * (f?.get(0) ?: 0f)
        var t = h * (f?.get(1) ?: 0f)
        var r = w * (f?.get(2) ?: 1f)
        var b2 = h * (f?.get(3) ?: 1f)
        val shrinkX = (r - l) * (1f - StarMoonGlyph.BITMAP_SHRINK) / 2f
        val shrinkY = (b2 - t) * (1f - StarMoonGlyph.BITMAP_SHRINK) / 2f
        l += shrinkX
        r -= shrinkX
        t += shrinkY
        b2 -= shrinkY
        StarMoonGlyph.drawInBox(
            canvas = canvas,
            paint = glyphPaint,
            left = l,
            top = t,
            right = r,
            bottom = b2,
            star = StarMoonGlyph.isStar(seed, digit),
            moonWidth = StarMoonGlyph.MOON_WIDTH_BITMAP,
        )
        return true
    }

    // ------------------------------------------------- 锁屏（ImageNumber 数字位图）

    /**
     * 主题模板里的「数字」元素 `com.zplus.engine.lk_view.ImageNumber`。
     *
     * 换机后锁屏那串时间（以及日期里的数字）就是它画的：元素从主题资源加载
     * `src + "_" + 数字 + 后缀` 十张位图放进 `W[0..9]`，`draw(Canvas)` 里把
     * `number` 表达式求值成整数字符串，逐位 `canvas.drawBitmap` 横排。
     * 因此既没有文本可替换，也不是 `TimeView`（那个类这套模板根本不用）。
     *
     * 这里复刻它的横排逻辑：位数、每位的位图宽高、累加起点全都照原样，只把
     * 位图换成星月；颜色按位图 alpha 加权取样（主题数字是黑/白/金都跟得住），
     * 透明度沿用原有的 paint（宿主用它做淡入淡出）。
     *
     * @return false 表示这位元素的数字取不到（表达式求值失败等），调用方应放行原绘制。
     */
    private fun drawImageNumber(canvas: Canvas, view: View, seed: Long): Boolean {
        val cls = view.javaClass
        // needDraw() 为假时原方法什么都不画，这里也什么都不画。
        val need = runCatching { cls.getMethod("needDraw").invoke(view) as? Boolean }.getOrNull()
        if (need == false) return true
        // S == null 表示这是张静态图（没有 src/number），不是时间，放行。
        if (Reflect.findField(cls, "S")?.get(view) as? String == null) return false
        val digitBitmaps = Reflect.findField(cls, "W")?.get(view) as? Array<*> ?: return false
        val hostPaint = Reflect.findField(cls, "a0")?.get(view) as? Paint
        val text = numberText(view, cls) ?: return false
        if (text.isEmpty()) return false

        var x = 0f
        for (ch in text) {
            if (ch !in '0'..'9') return false
            val entry = digitBitmaps.getOrNull(ch - '0') ?: return false
            val bmp = bitmapOf(entry) ?: return false
            val w = bmp.width.toFloat()
            val h = bmp.height.toFloat()
            if (w <= 0f || h <= 0f) return false
            val color = colorOf(entry)
            glyphPaint.color = if (color != 0) color else Color.WHITE
            glyphPaint.alpha = hostPaint?.alpha ?: 255
            glyphPaint.shader = null
            StarMoonGlyph.drawInBox(
                canvas = canvas,
                paint = glyphPaint,
                left = x,
                top = 0f,
                right = x + w,
                bottom = h,
                star = StarMoonGlyph.isStar(seed, ch - '0'),
            )
            x += w
        }
        return true
    }

    /** 复刻 ImageNumber 里 `number` 取值：优先变量 `V.l()`，否则表达式 `U.o()` 取绝对值。 */
    private fun numberText(view: View, cls: Class<*>): String? {
        val variable = Reflect.findField(cls, "V")?.get(view)
        if (variable != null) {
            val n = runCatching { variable.javaClass.getMethod("l").invoke(variable) }.getOrNull()
            if (n is Number) return n.toInt().toString()
        }
        val expr = Reflect.findField(cls, "U")?.get(view) ?: return null
        val f = runCatching { expr.javaClass.getMethod("o").invoke(expr) }.getOrNull() as? Number
            ?: return null
        return kotlin.math.abs(f.toFloat()).toInt().toString()
    }

    // -------------------------------------------------------------- 锁屏（位图数字）

    private fun drawZplusTime(canvas: Canvas, view: View, seed: Long): Boolean {
        val cls = view.javaClass
        val digits = Reflect.findField(cls, "e")?.get(view) as? Array<*> ?: return false
        if (digits.size < 11) return false
        val space = Reflect.findField(cls, "g")?.getFloat(view) ?: 0f
        val hourOnly = Reflect.findField(cls, "h")?.getBoolean(view) ?: false
        val minuteOnly = Reflect.findField(cls, "i")?.getBoolean(view) ?: false
        val hourText = callString(view, "getHourText") ?: return false
        val minuteText = callString(view, "getMinuteText") ?: return false

        // 与宿主一致：裁剪态下整体右移 getXValue()（resident_city 城市时钟用）。
        var x = if (callBoolean(view, "hasClip") == true) (callFloat(view, "getXValue") ?: 0f) else 0f
        val showHour = !minuteOnly
        val showMinute = !hourOnly
        if (showHour) {
            x = drawZplusDigit(canvas, digits, hourText, 0, x, space, seed)
            x = drawZplusDigit(canvas, digits, hourText, 1, x, space, seed)
        }
        if (showHour && showMinute) {
            // 冒号不是数字：按原位图照画（用户要求保留），只把宽度让出来。
            val colon = digits[10]
            if (colon != null) {
                val w = dimenOf(colon, "getWidth")
                val bmp = bitmapOf(colon)
                if (bmp != null) {
                    canvas.drawBitmap(bmp, x, 0f, bitmapPaint(view))
                }
                x += w + space
            }
        }
        if (showMinute) {
            x = drawZplusDigit(canvas, digits, minuteText, 0, x, space, seed)
            drawZplusDigit(canvas, digits, minuteText, 1, x, space, seed)
        }
        return true
    }

    /** 画一位数字的星月，返回下一位的起始 x（与原位图布局一致）。 */
    private fun drawZplusDigit(
        canvas: Canvas,
        digits: Array<*>,
        text: String,
        index: Int,
        x: Float,
        space: Float,
        seed: Long,
    ): Float {
        val ch = if (index < text.length) text[index] else '0'
        if (ch !in '0'..'9') return x
        val digit = digits.getOrNull(ch - '0') ?: return x
        val w = dimenOf(digit, "getWidth")
        val h = dimenOf(digit, "getHeight")
        val color = colorOf(digit)
        glyphPaint.color = if (color != 0) color else Color.WHITE
        glyphPaint.shader = null
        StarMoonGlyph.drawInBox(
            canvas = canvas,
            paint = glyphPaint,
            left = x,
            top = 0f,
            right = x + w,
            bottom = h.toFloat(),
            star = StarMoonGlyph.isStar(seed, ch - '0'),
        )
        return x + w + space
    }

    /** 主题数字位图的代表色：按 alpha 加权抽样，避免把透明背景算成黑色。 */
    private fun colorOf(digit: Any): Int {
        colorCache[digit]?.let { return it }
        val bmp = bitmapOf(digit) ?: return 0
        val color = averageColor(bmp)
        if (color != 0) colorCache[digit] = color
        return color
    }

    /** `IEngineBitmap#c()`：位图本体。 */
    private fun bitmapOf(digit: Any): Bitmap? = runCatching {
        digit.javaClass.getMethod("c").invoke(digit) as? Bitmap
    }.getOrNull()

    /**
     * 画位图用的 paint：取视图自己的 `f` 字段，`onSetAlpha` 把淡出动画的 alpha
     * 写在这里，借用它冒号才能跟主题一起淡入淡出。
     */
    private fun bitmapPaint(view: View): Paint =
        (Reflect.findField(view.javaClass, "f")?.get(view) as? Paint)
            ?: fallbackBitmapPaint

    private val fallbackBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun averageColor(bmp: Bitmap): Int {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return 0
        val step = max(1, sqrt(w.toDouble() * h / 1500.0).toInt())
        return runCatching {
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
            if (n == 0L) 0 else Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }.getOrDefault(0)
    }

    private fun dimenOf(target: Any, method: String): Int =
        runCatching { target.javaClass.getMethod(method).invoke(target) as? Int }.getOrNull() ?: 0

    /** 调用视图上的无参方法（`getHourText` / `hasClip` 等私有或包内方法）。 */
    private fun call(view: View, name: String): Any? {
        var c: Class<*>? = view.javaClass
        while (c != null && c != Any::class.java) {
            val m = runCatching { c!!.getDeclaredMethod(name) }.getOrNull()
            if (m != null) {
                runCatching { m.isAccessible = true }
                return runCatching { m.invoke(view) }.getOrNull()
            }
            c = c.superclass
        }
        return null
    }

    private fun callString(view: View, name: String): String? = call(view, name) as? String

    private fun callBoolean(view: View, name: String): Boolean? = call(view, name) as? Boolean

    private fun callFloat(view: View, name: String): Float? = (call(view, name) as? Number)?.toFloat()

    // ------------------------------------------------------------------ AOD（drawText）

    private fun drawEgTime(canvas: Canvas, view: View, seed: Long): Boolean {
        val cls = view.javaClass
        val paint = Reflect.findField(cls, "mTextPaint")?.get(view) as? Paint ?: return false
        val lines = Reflect.findField(cls, "mTextLines")?.get(view) as? Array<*> ?: return false
        val type = Reflect.findField(cls, "mDefaultTimeType")?.get(view) as? String
        val capH = StarMoonGlyph.capHeight(paint)
        if (capH <= 0f) return false
        val fm = paint.fontMetrics
        val baseline0 = -fm.top

        val first = lines.getOrNull(0) as? String
        val second = lines.getOrNull(1) as? String
        val vertical = type == "vertical" ||
            (!first.isNullOrEmpty() && !second.isNullOrEmpty())
        if (vertical && !first.isNullOrEmpty() && !second.isNullOrEmpty()) {
            // 两行左对齐到同一个宽度块（宿主 drawVerticalTime 用 fMax 居中整块）。
            val block = max(paint.measureText(first), paint.measureText(second))
            val left = view.measuredWidth / 2f - block / 2f
            val step = paint.fontSpacing +
                (Reflect.findField(cls, "mExtraLineGap")?.getFloat(view) ?: 0f)
            drawGlyphRun(canvas, paint, first, left, baseline0, capH, seed)
            drawGlyphRun(canvas, paint, second, left, baseline0 + step, capH, seed)
            return true
        }

        // 横向单行："HH:mm"，从 paddingLeft 起。
        val text = Reflect.findField(cls, "mText")?.get(view) as? String ?: return false
        if (text.isEmpty()) return false
        val left = (Reflect.findField(cls, "mPaddingLeft")?.getInt(view) ?: 0).toFloat()
        drawGlyphRun(canvas, paint, text, left, baseline0, capH, seed)
        return true
    }

    /** 逐字符画：数字换星月，冒号等原样用宿主 paint 画（占位宽度与真实字符一致）。 */
    private fun drawGlyphRun(
        canvas: Canvas,
        paint: Paint,
        text: String,
        startX: Float,
        baseline: Float,
        capH: Float,
        seed: Long,
    ) {
        var x = startX
        val cy = baseline + StarMoonGlyph.capCenterOffset(paint)
        for (ch in text) {
            val w = paint.measureText(ch.toString())
            if (ch in '0'..'9') {
                StarMoonGlyph.draw(
                    canvas = canvas,
                    paint = paint,
                    cx = x + w / 2f,
                    cy = cy,
                    capH = capH,
                    cellW = w,
                    star = StarMoonGlyph.isStar(seed, ch - '0'),
                )
            } else {
                canvas.drawText(ch.toString(), x, baseline, paint)
            }
            x += w
        }
    }

    /** 供 HostClockHider 判断：这个类名走哪条绘制逻辑。 */
    fun kindFor(className: String): Kind? = when (className) {
        ZPLUS_CLASS -> Kind.ZPLUS_TIME
        EG_CLASS -> Kind.EG_TIME
        ZPLUS_IMAGE_NUMBER_CLASS -> Kind.ZPLUS_IMAGE_NUMBER
        ZPLUS_IMAGE_ELEMENT_CLASS -> Kind.ZPLUS_IMAGE_ELEMENT
        else -> null
    }

    const val ZPLUS_CLASS = "com.zplus.engine.lk_view.TimeView"
    const val EG_CLASS = "com.oplus.egview.widget.TimeView"

    /** 主题模板里的数字元素（锁屏时间/日期里的数字位图）。 */
    const val ZPLUS_IMAGE_NUMBER_CLASS = "com.zplus.engine.lk_view.ImageNumber"

    /** 主题模板的通用图片元素（逐位 PNG 拼时间，换机后锁屏实测走这里）。 */
    const val ZPLUS_IMAGE_ELEMENT_CLASS = "com.zplus.engine.lk_view.ImageElementView"
}

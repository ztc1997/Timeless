package com.ztc1997.nocturne.hook

import android.view.View
import com.ztc1997.nocturne.core.ConfigSnapshot
import com.ztc1997.nocturne.core.FakeTime
import com.ztc1997.nocturne.core.GLog
import io.github.libxposed.api.XposedInterface

/**
 * 引擎时钟的「喂假数字」注入（2026-11-20，取代 [EngineClockPainter] 的自绘）。
 *
 * 之前之所以只能自绘，是因为把入口定在了 `draw(Canvas)` 上。实际上引擎在画之前
 * **会先问时间是多少**，那才是真正该下手的地方 —— 把答案换掉，宿主就会：
 *
 *  - 按假数字去 `e[0..9]` 里取主题自己的位图；
 *  - 按假数字的位图宽高重算 `setSize` 与逐位横排位置（`TimeView#b()`）；
 *  - 颜色/渐变/透明度全部沿用主题配置。
 *
 * 结果就是「主题自己的字形 + 不可能出现的钟点」，既不需要测墨迹包围盒，也不会有
 * 顶格裁切，比自绘稳得多。三个入口：
 *
 * 1. `com.zplus.engine.lk_view.TimeView#getHourText/getMinuteText`
 *    → 返回两位假数字字符串。`b()` 与 `draw()` 都读这两个方法，换掉即可。
 * 2. `com.oplus.egview.widget.TimeView#calcTimeNumber(boolean)`
 *    → AOD 的两行文本 `mTextLines[0]="HH"`、`[1]="mm"`，proceed 后按假数字改写。
 * 3. `com.zplus.engine.lk_view.ImageElementView#draw(Canvas)`
 *    → 逐位 PNG 模板：每个数字位是一张 `time_2.png` 这样的图片，没有文本可换。
 *    这里临时把图片名字段 `e` 的末位数字换成假数字，让宿主自己去加载那张图，
 *    draw 完成后立刻恢复原值（否则解除睡眠时会停留在假图）。
 */
class EngineClockInjector(
    private val xposed: XposedInterface,
    private val config: () -> ConfigSnapshot,
    private val bag: HookBag,
) {

    private fun shouldMask(): Boolean = MaskDecision.shouldMask(config)

    private fun seed(): Long = FakeTime.minuteSeed(System.currentTimeMillis())

    /** 已经挂过的 (类, 方法) 组合，避免自检节拍重复挂钩。 */
    private val hooked = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun install(loader: ClassLoader): Int {
        var n = 0
        n += hookZplusTimeText(loader)
        n += hookEgTimeText(loader)
        n += hookImageElement(loader)
        n += hookVariablesByName(loader)
        return n
    }

    // ------------------------------------------------ 0. 引擎时间变量（真正的源头）

    /**
     * 引擎的时间变量名。`DateTimeManager`（`variUIEngineProguard.y6.f`）每分钟把
     * 真实时分写进这几项，模板里的元素都从这里读。
     */
    private val timeVariables = setOf(
        "minute", "minute_resident",
        "hour", "hour12", "hour24",
        "hour12_resident", "hour24_resident",
        "system.time.min1", "system.time.min2",
        "system.time.hour1", "system.time.hour2",
    )

    /** 变量管理器：`variUIEngineProguard.c7.j`（`w1.c`，字段 c 是 public final）。 */
    private val VARIABLE_MANAGER = "variUIEngineProguard.c7.j"

    private data class CachedMask(val seed: Long, val len: Int, val text: String)

    /** 每分钟、每个变量只算一次（`k()` 在表达式求值里调用很密，别每帧重算）。 */
    private val maskCache = java.util.concurrent.ConcurrentHashMap<String, CachedMask>()

    private fun maskedVariable(name: String, real: String): String? {
        if (real.isEmpty()) return null
        val s = seed()
        val hit = maskCache[name]
        if (hit != null && hit.seed == s && hit.len == real.length) return hit.text
        val masked = FakeTime.mask(real, s)
        maskCache[name] = CachedMask(s, real.length, masked)
        return masked
    }

    /**
     * `VariableManager#k(String)`：按名字取变量值（字符串通道）。
     *
     * 为什么非要拦这一层：`TimeView#draw()` 里**分钟十位**并不走 `getMinuteText()`，
     * 而是直接 `mEngineUtil.c.k("minute")` 取原始变量（反编译 `TimeView.java` 第
     * 172 行），个位才用 `getMinuteText()`。只挂 getter 时真机表现就是
     * 「分钟十位是真实时间、个位是假数字」——2026-11-20 用户报的「有一位没被替换」。
     *
     * 拦在变量读取处的好处：宿主的排版（`TimeView#b()` 同样用 `k("minute")` 量宽）
     * 与绘制读到的是同一个假值，宽度、字形、颜色全由主题自己算，口径天然一致。
     */
    private fun hookVariablesByName(loader: ClassLoader): Int {
        val cls = Reflect.loadClass(VARIABLE_MANAGER, loader)
        if (cls == null) {
            // 混淆类名换版就到这里，别静默——下一步会尝试从活 TimeView 反查兜底。
            GLog.w("引擎时间变量：找不到 $VARIABLE_MANAGER（混淆改名？），等待动态解析兜底")
            return 0
        }
        return hookTimeVariables(cls)
    }

    /** 变量层钩子数量：>0 时不再尝试动态解析（避免解析到无关类刷警告）。 */
    private var variableHooks = 0

    private fun hookTimeVariables(cls: Class<*>): Int {
        if (!hooked.add("${cls.name}#k")) return 0
        val m = Reflect.methodsNamed(cls, "k").firstOrNull {
            it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java &&
                it.returnType == String::class.java
        } ?: run {
            GLog.w("引擎时间变量：${cls.name} 缺少 k(String):String（混淆改名？）")
            return 0
        }
        return runCatching {
            bag.add(
                xposed.hook(m)
                    .setId("nocturne:engine:variables:k")
                    .intercept { chain ->
                        val real = chain.proceed()
                        val name = chain.getArg(0) as? String
                        if (name == null || name !in timeVariables || !shouldMask()) {
                            return@intercept real
                        }
                        val text = real as? String ?: return@intercept real
                        maskedVariable(name, text) ?: real
                    },
            )
            GLog.i("挂钩成功：引擎时间变量 ${cls.name}#k(String)（${timeVariables.size} 个名字）")
            variableHooks++
            1
        }.getOrElse { GLog.w("挂钩引擎时间变量失败", it); 0 }
    }

    /**
     * 兜底解析：混淆类名换版后 `variUIEngineProguard.c7.j` 可能对不上，此时从活的
     * `TimeView` 实例反查 `mEngineUtil.c`（`c` 是 public final 字段）拿管理器类。
     * 只尝试一次，成功后不再介入。
     */
    private val varResolveTried = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun resolveVariablesFrom(view: View) {
        // 静态名字已经挂上就不用兜底了：活视图上反查出来的对象不一定是变量管理器
        // （真机验证过会落到 variUIEngineProguard.y6.j2 这种无关类上）。
        if (variableHooks > 0) return
        if (!varResolveTried.compareAndSet(false, true)) return
        runCatching {
            val engine = Reflect.findField(view.javaClass, "mEngineUtil")?.get(view) ?: return
            val manager = runCatching { engine.javaClass.getField("c").get(engine) }
                .getOrElse { Reflect.findField(engine.javaClass, "c")?.get(engine) ?: return }
            if (manager == null) return
            val n = hookTimeVariables(manager.javaClass)
            if (n > 0) GLog.i("引擎时间变量动态解析：${manager.javaClass.name}（$n 个钩子）")
            else GLog.i("引擎时间变量动态解析未命中（${manager.javaClass.name}）")
        }.onFailure { GLog.w("引擎时间变量动态解析失败", it) }
    }

    // --------------------------------------------- 1. 锁屏 TimeView（位图数字）

    /**
     * `TimeView#getHourText()` / `getMinuteText()` 都是 private、无参、返回 String，
     * 内部会从引擎变量表取 `hour24`/`hour12`/`minute` 再补零。直接覆盖返回值，
     * 两位数字刚好满足 `draw()` 里 `charAt(0)/charAt(1)` 的假设。
     */
    private fun hookZplusTimeText(loader: ClassLoader): Int {
        val cls = Reflect.loadClass("com.zplus.engine.lk_view.TimeView", loader)
        if (cls == null) {
            GLog.w("引擎时钟：找不到 com.zplus.engine.lk_view.TimeView，跳过")
            return 0
        }
        var n = 0
        for (name in arrayOf("getHourText", "getMinuteText")) {
            if (!hooked.add("${cls.name}#$name")) continue
            // 取不到方法时要留痕：这两个 getter 名是可读的，换版后若被改名，
            // 静默 continue 会让「整串时间没被替换」无从定位（排查成本极高）。
            val m = runCatching { cls.getDeclaredMethod(name) }.getOrNull()
            if (m == null) {
                GLog.w("引擎时钟缺少 ${cls.simpleName}#$name（换版改名？），此入口跳过")
                continue
            }
            runCatching {
                bag.add(
                    xposed.hook(m)
                        .setId("nocturne:engine:${cls.simpleName}:$name")
                        .intercept { chain ->
                            val real = chain.proceed()
                            // 首次回调时顺手尝试动态解析变量管理器（混淆类名换版兜底）。
                            (chain.thisObject as? View)?.let { resolveVariablesFrom(it) }
                            if (!shouldMask()) return@intercept real
                            FakeTime.mask(real as? CharSequence, seed())
                        },
                )
                GLog.i("挂钩成功：引擎时钟 ${cls.simpleName}#$name（返回假数字）")
                n++
            }.onFailure { GLog.w("挂钩引擎时钟失败：${cls.name}#$name", it) }
        }
        return n
    }

    // ------------------------------------------------------- 2. AOD（两行文本）

    /**
     * `calcTimeNumber(boolean)` 把 `HH`/`mm` 写进 `mTextLines[0..1]`，`onDraw` 再逐行
     * `drawText`。proceed 之后就地改字段：不改绘制、不改 paint，字距行高全由宿主算。
     */
    private fun hookEgTimeText(loader: ClassLoader): Int {
        val cls = Reflect.loadClass("com.oplus.egview.widget.TimeView", loader)
        if (cls == null) {
            GLog.w("AOD 时钟：找不到 com.oplus.egview.widget.TimeView，跳过")
            return 0
        }
        if (!hooked.add("${cls.name}#calcTimeNumber")) return 0
        val field = Reflect.findField(cls, "mTextLines") ?: run {
            GLog.w("AOD 时钟缺少 mTextLines 字段，跳过假时间注入")
            return 0
        }
        val methods = Reflect.methodsNamed(cls, "calcTimeNumber")
        if (methods.isEmpty()) {
            GLog.w("AOD 时钟缺少 calcTimeNumber，跳过假时间注入")
            return 0
        }
        var n = 0
        for (m in methods) {
            runCatching {
                bag.add(
                    xposed.hook(m)
                        .setId("nocturne:engine:eg:calcTimeNumber")
                        .intercept { chain ->
                            val result = chain.proceed()
                            if (shouldMask()) {
                                val self = chain.thisObject
                                @Suppress("UNCHECKED_CAST")
                                val lines = runCatching { field.get(self) as? Array<String> }
                                    .getOrNull()
                                if (lines != null && lines.size >= 2) {
                                    val s = seed()
                                    lines[0] = FakeTime.mask(lines[0], s)
                                    lines[1] = FakeTime.mask(lines[1], s)
                                } else if (lines != null) {
                                    // 单行模式（横向）：只有一行，也照样换。
                                    val s = seed()
                                    for (i in lines.indices) lines[i] = FakeTime.mask(lines[i], s)
                                }
                            }
                            result
                        },
                )
                GLog.i("挂钩成功：AOD 时钟 calcTimeNumber（改写 mTextLines）")
                n++
            }.onFailure { GLog.w("挂钩 AOD 时钟失败", it) }
        }
        return n
    }

    // --------------------------------------------------- 3. 逐位 PNG（换图片名）

    /** 图片名末尾的数字 + 扩展名：`time_2.png` → 换掉那个 2。 */
    private val digitSuffix = Regex("""(\d)(\.(?:png|webp|jpg|jpeg))$""", RegexOption.IGNORE_CASE)

    private fun replaceTrailingDigit(src: String, digit: Int): String =
        digitSuffix.replace(src) { m -> "$digit${m.groupValues[2]}" }

    /**
     * 逐位 PNG：`e` 是当前图片名，模板表达式按分钟决定显示哪张。
     *
     * draw 期间临时改成假数字的图片名，宿主读 `e` 去取图、贴到同一个格子里；
     * draw 结束立刻恢复，避免解除睡眠后停留在假图。取不到图片名或不是时间图
     * （日期、装饰）一律原样放行。
     */
    private fun hookImageElement(loader: ClassLoader): Int {
        val cls = Reflect.loadClass("com.zplus.engine.lk_view.ImageElementView", loader)
        if (cls == null) {
            GLog.w("逐位图片时钟：找不到 ImageElementView，跳过")
            return 0
        }
        // 字段名 `e` 是混淆名（就是当前图片名 time_2.png）。换版后被改名时这里
        // 曾是完全静默的失败点 —— 画面不动、日志干净，只能靠肉眼发现。
        val field = Reflect.findField(cls, "e")
        if (field == null) {
            GLog.w("逐位图片时钟：ImageElementView 缺少图片名字段 e（混淆改名？），跳过")
            return 0
        }
        val draw = runCatching {
            cls.getDeclaredMethod("draw", android.graphics.Canvas::class.java)
        }.getOrNull()
        if (draw == null) {
            GLog.w("逐位图片时钟：ImageElementView 缺少 draw(Canvas)，跳过")
            return 0
        }
        if (!hooked.add("${cls.name}#draw")) return 0
        return runCatching {
            bag.add(
                xposed.hook(draw)
                    .setId("nocturne:engine:imageelement:draw")
                    .intercept { chain ->
                        val self = chain.thisObject as? View
                        if (!shouldMask() || self == null) return@intercept chain.proceed()
                        val src = runCatching { field.get(self) as? String }.getOrNull()
                            ?: return@intercept chain.proceed()
                        val digit = EngineClockPainter.timeDigitOf(src)
                            ?: return@intercept chain.proceed()
                        val fake = FakeTime.digit(seed(), digit)
                        if (fake == digit) return@intercept chain.proceed()
                        val swapped = replaceTrailingDigit(src, fake)
                        if (swapped == src) return@intercept chain.proceed()
                        runCatching { field.set(self, swapped) }
                        try {
                            chain.proceed()
                        } finally {
                            runCatching { field.set(self, src) }
                        }
                    },
            )
            GLog.i("挂钩成功：逐位图片时钟 ImageElementView#draw（换图片名）")
            1
        }.getOrElse { GLog.w("挂钩逐位图片时钟失败", it); 0 }
    }
}

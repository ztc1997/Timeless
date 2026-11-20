package com.ztc1997.timeless.hook

import android.content.Context
import android.view.View
import android.widget.TextView
import com.ztc1997.timeless.core.ConfigSnapshot
import com.ztc1997.timeless.core.FakeTime
import com.ztc1997.timeless.core.GLog
import io.github.libxposed.api.XposedInterface

/**
 * KeyguardPersonalityClocks 插件时钟拦截（真机反编译见 HOOK_PLAN.md §2.6）。
 *
 * 桌面卡片时钟与锁屏数字时钟的实际渲染方是插件
 * `com.oplus.keyguard.personality.clocks`（KeyguardPersonalityClocks.apk）：
 * DigitalTimeView / ClockTimeView 等把时间数字写入全部收敛到
 * `CustomizedTextView#tryUpdateText(CharSequence)`（正常路径与翻页动画路径都调它）。
 * 之前的"写控件文本"式 hook 会被时钟自身的分钟级刷新覆盖（竞态），因此必须在
 * 这个写入源头上把参数换掉，写入内容从一开始就是占位符。
 *
 * 插件类通过 createPackageContext 加载进宿主进程（launcher / systemui），
 * 所以要用插件的 classLoader 找类，而不是宿主的。
 *
 * 拦截条件刻意收窄：写入内容是 1~2 位纯数字（时间数字位）才替换为假数字
 * （core/FakeTime.kt，按分钟确定性变化，且恒落在 6~9 —— 真实钟点不可能出现）。
 * 冒号（":"）、AM/PM、日期（"11月17日"）、天气（"30°"）都不是纯数字，自动放行。
 * 未命中睡眠时段时不做任何替换，原样 proceed。
 *
 * 2026-11-19 第三方/全局主题适配：Launcher 进程在全局主题下
 * `ClockTimeView#refreshTimeText` 会把 `isImageDigitMode` 置 true，时间数字改由
 * `applyDigitDrawable` 贴主题图片数字，同时 `tryUpdateText` 只收到 null —— 上面的
 * 文本替换路径完全落空（真机现象：第三方主题桌面失效）。因此改为在取图源头
 * `Utils.getHostClockNum` 把真实数字换成假数字，宿主仍取主题自己的位图排版；
 * `applyDigitDrawable` 上只保留 debug 日志，行为透明。
 * 该分支只在 Launcher 生效（SystemUI 里 isImageDigitMode 恒为 false）。
 */
class PersonalityClocksFeature(
    private val xposed: XposedInterface,
    private val config: () -> ConfigSnapshot,
    private val bag: HookBag,
) {

    /** 写入内容是否为时间数字位：1~2 位纯数字（如 "1"、"14"、"08"）。 */
    private fun isTimeDigitArg(arg: CharSequence?): Boolean {
        if (arg == null) return false
        val n = arg.length
        if (n < 1 || n > 2) return false
        for (i in 0 until n) {
            if (!Character.isDigit(arg[i])) return false
        }
        return true
    }

    private fun shouldMask(): Boolean = MaskDecision.shouldMask(config)

    /** 已经挂过 tryUpdateText 的 Class，避免同一 Class 被多路重复挂钩。 */
    private val hookedClasses = java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())

    /**
     * 在给定 Class 上挂 tryUpdateText 钩子（幂等：同一 Class 只挂一次）。
     * @param source 日志里标注的安装来源
     */
    private fun hookClass(cls: Class<*>, source: String) {
        if (!hookedClasses.add(cls)) return
        val method = runCatching {
            cls.getDeclaredMethod("tryUpdateText", CharSequence::class.java)
        }.getOrNull()
        if (method == null) {
            GLog.w("tryUpdateText 不存在于 ${cls.name}（来源 $source）")
            return
        }
        runCatching { method.isAccessible = true }
        runCatching {
            bag.add(
                xposed.hook(method)
                    .setId("timeless:personality:$source")
                    .intercept { chain ->
                        if (!shouldMask()) {
                            chain.proceed()
                        } else {
                            val arg = runCatching { chain.getArg(0) as? CharSequence }.getOrNull()
                            if (isTimeDigitArg(arg)) {
                                // 写入内容本身就是一个数字段：换成假数字（确定性按分钟变化），
                                // 同分钟内结果恒定，去重与 RedOne 刷新等副作用照常保留。
                                // 假数字与真实数字同为 1~2 位，字体度量不变，不需要 span。
                                val masked = FakeTime.mask(
                                    arg,
                                    FakeTime.minuteSeed(System.currentTimeMillis()),
                                )
                                if (GLog.isDebug) GLog.d("屏蔽时间数字：$arg → \"$masked\"（$source）")
                                chain.proceed(arrayOf<Any?>(masked))
                            } else {
                                // 非纯数字放行（冒号/AM/PM/日期/天气）；debug 下记录
                                // 数字类以外的写入，用于排查唤醒瞬间是否走了别的入口。
                                if (GLog.isDebug && arg != null && arg.length <= 24) {
                                    GLog.d("放行写入：\"$arg\"（$source）")
                                }
                                chain.proceed()
                            }
                        }
                    },
            )
            GLog.i("挂钩成功：插件时钟 tryUpdateText（来源 $source → ${cls.name}，classLoader=${cls.classLoader}）")
        }.onFailure { GLog.w("挂钩插件时钟失败（来源 $source）", it) }
    }

    /** 已经挂过 applyDigitDrawable 的 Class，避免同一 Class 被多路重复挂钩。 */
    private val hookedImageDigitClasses =
        java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())

    /** 已经挂过 ThemeClockHelper.setTextImg 的 Class。 */
    private val hookedThemeImgClasses =
        java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())

    /**
     * 第三方/全局主题的「图片数字」写入源头（锁屏 + AOD）。
     *
     * 真机反编译：`com.oplus.keyguard.clock.base.ui.view.ClockTimeView` 在
     * `ThemeClockHelper.isClockImg()` 为 true 时，逐个数字位调用
     * `ThemeClockHelper.setTextImg(textView, "20", scale)`，而该方法实现是
     *
     *     textView.setText(null);
     *     textView.setCompoundDrawables(左位图, null, 右位图, null);
     *
     * —— 先把文本清空、再贴两张数字图片。所以我们挂在 `tryUpdateText` 上的星月
     * 文本会被紧接着清掉，屏幕上留下的是主题自带的真实数字图片。这就是
     * 「桌面已生效、锁屏/AOD 仍显示真实时间」的根因（桌面走 digital 族，锁屏与
     * AOD 走 base 族的图片数字路径）。
     *
     * 处理方式：命中睡眠时不执行原方法，改为把星月写进文本并清空四周 drawable，
     * 视觉上与桌面卡片的时间替换保持一致；未命中则原样放行，图片数字自然恢复。
     */
    private fun hookThemeClockImg(cls: Class<*>, source: String): Int {
        if (!hookedThemeImgClasses.add(cls)) return 0
        val methods = Reflect.methodsNamed(cls, "setTextImg")
        if (methods.isEmpty()) {
            GLog.w("setTextImg 不存在于 ${cls.name}（来源 $source）")
            return 0
        }
        var count = 0
        for (method in methods) {
            runCatching {
                bag.add(
                    xposed.hook(method)
                        .setId("timeless:personality:themeimg:$source")
                        .intercept { chain ->
                            val tvForLog = runCatching { chain.getArg(0) as? TextView }.getOrNull()
                            if (GLog.isDebug) {
                                GLog.d(
                                    "setTextImg 回调 digits=\"${runCatching { chain.getArg(1) }.getOrNull()}\"" +
                                        " mask=${shouldMask()} view=${tvForLog?.javaClass?.name}",
                                )
                            }
                            if (!shouldMask()) {
                                chain.proceed()
                            } else {
                                val tv = runCatching { chain.getArg(0) as? TextView }.getOrNull()
                                val digits = runCatching {
                                    chain.getArg(1) as? String
                                }.getOrNull()
                                if (tv == null || digits.isNullOrEmpty()) {
                                    chain.proceed()
                                } else {
                                    val masked = FakeTime.mask(
                                        digits,
                                        FakeTime.minuteSeed(System.currentTimeMillis()),
                                    )
                                    if (GLog.isDebug) {
                                        GLog.d("屏蔽主题图片数字：$digits → \"$masked\"（$source）")
                                    }
                                    // 换参数而不是自绘：原方法会按 digits 取 0-9 主题位图
                                    // 逐位贴成 compound drawable，喂假数字就能画出主题
                                    // 自己的字形，观感与主题完全一致。
                                    val args = chain.args.toMutableList()
                                    args[1] = masked
                                    chain.proceed(args.toTypedArray())
                                }
                            }
                        },
                )
                GLog.i("挂钩成功：主题图片数字 setTextImg（来源 $source → ${cls.name}）")
                count++
            }.onFailure { GLog.w("挂钩主题图片数字失败（来源 $source）", it) }
        }
        return count
    }

    /** 已经挂过 getHostClockNum 的 Class。 */
    private val hookedNumClasses =
        java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())

    /**
     * 主题数字位图的取图源头（全局主题的桌面/锁屏样式「图片数字」）。
     *
     * `Utils.getHostClockNum(context, digit)` 每次返回一张 0-9 数字位图，宿主随后
     * 把它缩放、贴成数字位的 compound drawable。**直接改第 2 个参数**：把真实数字
     * 换成假数字，宿主就会去取假数字的主题位图 —— 画出来是主题自己的字形，
     * 观感与主题原生完全一致，不需要任何自绘（这是本方案相比星月自绘最大的收益）。
     *
     * 逐位调用天然对应"每一位"：宿主按 时十/时个/分十/分个 依次取图，
     * 每一位的假值只由分钟种子决定（[FakeTime.digit] 刻意忽略原数字），
     * 与整串 mask 的结果一致。
     *
     * 只在睡眠时段替换；平时原样返回，主题位图不受影响。
     */
    private fun hookClockNum(cls: Class<*>, source: String): Int {
        if (!hookedNumClasses.add(cls)) return 0
        var count = 0
        for (method in Reflect.methodsNamed(cls, "getHostClockNum")) {
            if (method.parameterTypes.size != 2) continue
            if (method.returnType != android.graphics.drawable.Drawable::class.java) continue
            runCatching {
                bag.add(
                    xposed.hook(method)
                        .setId("timeless:personality:num:$source")
                        .intercept { chain ->
                            val digit = chain.getArg(1) as? Int
                            if (!shouldMask() || digit == null || digit !in 0..9) {
                                return@intercept chain.proceed()
                            }
                            val fake = FakeTime.digit(
                                FakeTime.minuteSeed(System.currentTimeMillis()),
                                digit,
                            )
                            val args = chain.args.toMutableList()
                            args[1] = fake
                            chain.proceed(args.toTypedArray())
                        },
                )
                GLog.i("挂钩成功：主题数字取图 getHostClockNum（来源 $source → ${cls.name}）")
                count++
            }.onFailure { GLog.w("挂钩主题数字取图失败（$source）", it) }
        }
        if (count == 0) GLog.w("getHostClockNum 不可挂：${cls.name}（来源 $source）")
        return count
    }

    /** 已经挂过 getHostClockSymbol 的 Class。 */
    private val hookedSymbolClasses =
        java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())

    /** 已经打过「补画冒号」日志的次数（避免每分钟刷屏）。 */
    private val colonLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 时间分隔符的取图源头（`Utils.getHostClockSymbol(context)`）。
     *
     * 真机实测：桌面那套模板在 launcher 侧取不到分隔符图，返回 null，
     * `applyDigitDrawable` 见到 null drawable 直接 return —— 数字换成假数字位图之后
     * 冒号位置就是空的（用户 2026-11-19 反馈「桌面上的冒号没了」）。
     * 所以在源头兜底：主题没给就返回自己画的冒号（见 [ColonDrawable]），
     * 主题给了就原样用它的。
     */
    private fun hookClockSymbol(cls: Class<*>, source: String): Int {
        if (!hookedSymbolClasses.add(cls)) return 0
        var count = 0
        for (method in Reflect.methodsNamed(cls, "getHostClockSymbol")) {
            if (method.parameterTypes.size != 1) continue
            if (method.returnType != android.graphics.drawable.Drawable::class.java) continue
            runCatching {
                bag.add(
                    xposed.hook(method)
                        .setId("timeless:personality:symbol:$source")
                        .intercept { chain ->
                            val result = chain.proceed()
                            if (!shouldMask()) return@intercept result
                            // 主题在 launcher 侧即使给得出分隔符图，画出来也是空的
                            // （真机 22:23 验证：hook 到的是非 null，屏幕上却没有冒号），
                            // 所以睡眠时段统一用自己补的冒号，与状态栏的「:」观感一致。
                            if (colonLogged.compareAndSet(false, true)) {
                                GLog.i(
                                    "补画冒号：原分隔符图=${if (result == null) "null" else "有（未采用）"}" +
                                        "（来源 $source）",
                                )
                            }
                            ColonDrawable()
                        },
                )
                GLog.i("挂钩成功：主题分隔符取图 getHostClockSymbol（来源 $source → ${cls.name}）")
                count++
            }.onFailure { GLog.w("挂钩主题分隔符取图失败（$source）", it) }
        }
        return count
    }

    /**
     * 全局主题下贴图片数字的写入源头。
     *
     * 2026-11-20：改参数化方案后这里不再需要任何动作 —— 数字位图已在取图源头
     * （[hookClockNum]）换成假数字对应的主题位图，[applyDigitDrawable] 只是把
     * 拿到的图贴上去，贴出来自然是假时间。保留钩子只为在 debug 日志里确认这条
     * 链路还在走（排查"桌面为什么没变"时有用），行为上完全透明。
     */
    private fun hookImageDigit(cls: Class<*>, source: String): Int {
        if (!hookedImageDigitClasses.add(cls)) return 0
        val methods = Reflect.methodsNamed(cls, "applyDigitDrawable")
        if (methods.isEmpty()) {
            GLog.w("applyDigitDrawable 不存在于 ${cls.name}（来源 $source）")
            return 0
        }
        var count = 0
        for (method in methods) {
            runCatching {
                bag.add(
                    xposed.hook(method)
                        .setId("timeless:personality:imgdigit:$source")
                        .intercept { chain ->
                            if (GLog.isDebug && shouldMask()) {
                                GLog.d("图片数字位贴图（取图源头已换假数字，$source）")
                            }
                            chain.proceed()
                        },
                )
                GLog.i("挂钩成功：图片数字 applyDigitDrawable（来源 $source → ${cls.name}）")
                count++
            }.onFailure { GLog.w("挂钩图片数字失败（来源 $source）", it) }
        }
        return count
    }

    /**
     * 快速路径：用宿主 Context 的 createPackageContext 找插件 classLoader 挂钩，
     * 拿到的 classLoader 与宿主实际持有的是同一实例。
     * @param appContext 宿主 Application Context（用于 createPackageContext）
     * @return 成功挂上的钩子数
     */
    fun install(appContext: Context?): Int {
        val ctx = appContext ?: run {
            GLog.w("宿主 Context 未就绪，插件时钟拦截延迟到下次触发")
            return 0
        }
        // 临时诊断（收口删除）：字号/RT 通道/文本拷贝追踪，仅 debug 构建。
        if (com.ztc1997.timeless.BuildConfig.DEBUG && TextSizeDiag.isEnabled()) {
            runCatching { TextSizeDiag(xposed, bag).install(ctx) }
                .onFailure { GLog.w("字号诊断安装失败", it) }
        }
        var hooked = 0
        for (pkg in PLUGIN_PACKAGES) {
            val loader = runCatching {
                ctx.createPackageContext(
                    pkg,
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
                ).classLoader
            }.getOrNull()
            if (loader == null) {
                if (GLog.isDebug) GLog.d("插件不可用：$pkg")
                continue
            }
            // ① 文本数字写入源头（普通模式 / 灵感接续）。
            for (className in TARGET_CLASS_NAMES) {
                val tvCls = runCatching { loader.loadClass(className) }.getOrNull()
                if (tvCls == null) {
                    if (GLog.isDebug) GLog.d("类不存在：$pkg → $className")
                    continue
                }
                hookClass(tvCls, "fast-path:$pkg")
                if (hookedClasses.contains(tvCls)) hooked++
            }
            // ② 图片数字写入源头（全局/第三方主题，仅 Launcher 生效）。
            for (className in IMAGE_DIGIT_CLASS_NAMES) {
                val imgCls = runCatching { loader.loadClass(className) }.getOrNull()
                if (imgCls == null) {
                    if (GLog.isDebug) GLog.d("图片数字类不存在：$pkg → $className")
                    continue
                }
                hooked += hookImageDigit(imgCls, "fast-path:$pkg")
            }
            // ③ 图片数字的取图源头：换成假数字位图（沿用主题字形，桌面不消失）。
            for (className in NUM_CLASS_NAMES) {
                val numCls = runCatching { loader.loadClass(className) }.getOrNull()
                if (numCls == null) {
                    if (GLog.isDebug) GLog.d("数字取图类不存在：$pkg → $className")
                    continue
                }
                hooked += hookClockNum(numCls, "fast-path:$pkg")
            }
            // ④ 分隔符取图源头：主题没给就补冒号。
            for (className in NUM_CLASS_NAMES) {
                val symCls = runCatching { loader.loadClass(className) }.getOrNull() ?: continue
                hooked += hookClockSymbol(symCls, "fast-path:$pkg")
            }
            // ③ text 族 / gallery 族的「刷新后再改写」路线已删除：它们按类名逐个
            // 补钩，一旦主题换了子类覆写就漏（真机踩到：DefaultSingleClockView
            // 覆写 refreshClockText，基类出口改的是离屏控件 time_text，屏幕上
            // 真正显示的 time_text_ext 被子类后写覆盖）。改由
            // KeyguardTextWriteMask 在 TextView#setText 这一最终写入点统一兜底。
            // ⑤ 第三方/全局主题图片数字（锁屏 + AOD）：ThemeClockHelper.setTextImg。
            for (className in THEME_IMG_CLASS_NAMES) {
                val cls = runCatching { loader.loadClass(className) }.getOrNull()
                if (cls == null) {
                    if (GLog.isDebug) GLog.d("主题图片数字类不存在：$pkg → $className")
                    continue
                }
                hooked += hookThemeClockImg(cls, "fast-path:$pkg")
            }
        }
        if (hooked > 0) GLog.i("插件时钟拦截已挂 $hooked 个钩子（快速路径）")
        return hooked
    }

    companion object {
        /**
         * tryUpdateText 的全部实现（真机反编译确认）：
         * - common.widget.CustomizedTextView：公共基类（声明处）
         * - base.widget.CustomizedTextView：中间基类，**final**，只会被非覆写子类用到
         * - digital.widget.MyCustomizedTextView：时间数字控件的**虚分派终点**！
         *   覆写了 tryUpdateText 并直接 setText。只挂基类钩子时，所有时间数字
         *   写入走这个覆写、完全绕过基类钩子 —— 桌面恢复真实时间、锁屏不生效
         *   的根因。必须排在第一位。
         * digital.ui.view.DigitalTimeView#tryUpdateText 只是转发层（转发到
         * MyCustomizedTextView），挂终类即可覆盖。
         */
        val TARGET_CLASS_NAMES = arrayOf(
            "com.oplus.keyguard.clock.digital.widget.MyCustomizedTextView",
            "com.oplus.keyguard.clock.common.widget.CustomizedTextView",
            "com.oplus.keyguard.clock.base.widget.CustomizedTextView",
        )

        /**
         * 全局/第三方主题下贴图片数字的写入类（真机反编译：ClockTimeView.java）。
         * 只有 Launcher 进程的 refreshTimeText 会把 isImageDigitMode 置 true 并走
         * applyDigitDrawable；SystemUI 里该字段恒 false，仍走上面的文本路径。
         * 版本号后缀 `_release` 是 JADX 对内联合成方法名的标记，运行时方法名就是
         * applyDigitDrawable，类全名不含后缀。
         */
        val IMAGE_DIGIT_CLASS_NAMES = arrayOf(
            "com.oplus.keyguard.clock.digital.ui.view.ClockTimeView",
        )

        /**
         * 图片数字的取图源头（真机反编译：digital/util/Utils.getHostClockNum）。
         * 全局主题下每个数字位都从这里取 0-9 位图，是唯一能把「位图」换成
         * 「星月」的地方；只包 applyDigitDrawable 只能拿到已贴好的 drawable，
         * 不知道这一位是几（星/月选择要按数字值散列，才能与状态栏一致）。
         */
        val NUM_CLASS_NAMES = arrayOf(
            "com.oplus.keyguard.clock.digital.util.Utils",
        )

        /**
         * 第三方/全局主题的「图片数字」写入工具类（锁屏 + AOD）。
         * `setTextImg` 会先 `setText(null)` 再贴数字位图，绕开一切文本钩子。
         */
        val THEME_IMG_CLASS_NAMES = arrayOf(
            "com.oplus.keyguard.clock.base.util.ThemeClockHelper",
        )

        /**
         * 插件宿主包。CustomizedTextView 实际位于 KeyguardPersonalityClocks.apk；
         * 同时尝试 clock.base（独立 APK），避免版本演进后类搬家。
         */
        val PLUGIN_PACKAGES = arrayOf(
            "com.oplus.keyguard.personality.clocks",
            "com.oplus.keyguard.clock.base",
        )
    }
}

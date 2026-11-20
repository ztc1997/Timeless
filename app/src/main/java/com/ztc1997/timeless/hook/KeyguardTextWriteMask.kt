package com.ztc1997.timeless.hook

import android.text.TextUtils
import android.widget.TextView
import com.ztc1997.timeless.core.ConfigSnapshot
import com.ztc1997.timeless.core.FakeTime
import com.ztc1997.timeless.core.GLog
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * 锁屏 / AOD / 桌面时钟文本的**最终写入点**兜底（2026-11-20 换第三方主题后新增）。
 *
 * ## 为什么需要它
 *
 * 之前是按「写入源头」逐个挂钩：digital 族的 `tryUpdateText`、text 族的
 * `applyTextClockTimeAndExtra`、gallery 族的 `refreshClockText`。换主题后真机取证：
 *
 * ```
 * 18:34:02.991  setText("18:34")  ← BaseSingleClockView.refreshClockText
 * 18:34:02.994  setText("66:66")  ← 我们的钩子（改的是 getTimeTextView()）
 * 界面实际显示：18:34
 * ```
 *
 * 根因是**虚分派**：`DefaultSingleClockView` 覆写了 `refreshClockText`，
 * 基类方法只是它 `super.` 调用的一半；基类写 `time_text`（一个离屏控件），
 * 子类紧接着再写 `time_text_ext`（屏幕上真正显示的那个）。我们在基类出口改的是
 * 前者，被子类后写覆盖 —— 屏幕照旧真实时间。而按类名逐个补钩又会随主题/
 * 版本不断新增子类，永远追不上。
 *
 * ## 做法
 *
 * 所有时钟控件最终都要落到 `TextView.setText`（含 `tryUpdateText` 内部那次）。
 * 在这里按**实例所属包名**筛选，一次覆盖全部族与全部子类：
 * `com.oplus.keyguard.*`（即 KeyguardPersonalityClocks 插件里的时钟控件）。
 *
 * 只改形如 `HH:mm` 的整串时间，以及**资源 id 名含 time** 的 1~2 位纯数字段；
 * 日期（`11/20`）、星期（`周五`）、农历（`十月十二`）、天气（`30°`）都不匹配，
 * 原样放行。
 *
 * 幂等性：`FakeTime.mask` 对已是假时间（四位全同）的输入返回同一串，因此本钩子
 * 不会与自身或其他重写路径互相触发（比较相同即直接 proceed），无递归风险。
 */
class KeyguardTextWriteMask(
    private val xposed: XposedInterface,
    private val config: () -> ConfigSnapshot,
    private val bag: HookBag,
) {

    private fun shouldMask(): Boolean = MaskDecision.shouldMask(config)

    private fun seed(): Long = FakeTime.minuteSeed(System.currentTimeMillis())

    /** 整串时间：`18:34`、`9:05`（允许前后空白）。 */
    private val fullTime = Regex("""^\s*\d{1,2}:\d{2}\s*$""")

    /** 纯数字段：`18`、`5`（逐位写入的时钟控件）。 */
    private val digitSegment = Regex("""^\d{1,2}$""")

    /** 当前写入的是不是「时间」类文本。 */
    private fun looksLikeTime(tv: TextView, text: CharSequence): Boolean {
        val s = text.toString()
        if (fullTime.matches(s)) return true
        // 1~2 位纯数字只有在控件本身就是时间控件时才替换，避免误伤
        // 电量百分比、通知角标之类的数字。
        if (!digitSegment.matches(s)) return false
        val name = idName(tv) ?: return false
        return name.contains("time", true) || name.contains("clock", true)
    }

    /** 控件 id 的资源名；解析不到返回 null（不去猜）。 */
    private fun idName(tv: TextView): String? {
        val id = tv.id
        if (id == 0 || id == -1) return null
        return runCatching {
            tv.resources.getResourceEntryName(id)
        }.getOrNull()
    }

    /** 类名是否属于锁屏时钟插件（含其全部子类），结果按 Class 缓存。 */
    private val keyguardClasses = ConcurrentHashMap<Class<*>, Boolean>()

    private fun isKeyguardClockView(tv: TextView): Boolean {
        val cls = tv.javaClass
        keyguardClasses[cls]?.let { return it }
        val name = cls.name
        // com.oplus.keyguard.clock.*（personality 插件）、com.oplus.keyguard.style.*
        // 都在范围内；systemui 自身的时钟另有专门钩子，这里放过避免重复。
        val hit = name.startsWith("com.oplus.keyguard.")
        keyguardClasses[cls] = hit
        return hit
    }

    private val installed = INSTALLED

    fun install(): Int {
        if (!installed.compareAndSet(false, true)) return 0
        var n = 0
        for (m in Reflect.methodsNamed(TextView::class.java, "setText")) {
            // 只挂接受文本的两个重载：setText(int) 是资源 id，与时间无关。
            if (m.parameterTypes.isEmpty()) continue
            if (m.parameterTypes[0] != CharSequence::class.java) continue
            runCatching {
                bag.add(
                    xposed.hook(m)
                        .setId("timeless:keyguard:textwrite:${m.parameterTypes.size}")
                        .intercept { chain ->
                            val text = runCatching { chain.getArg(0) as? CharSequence }.getOrNull()
                            if (text == null || !shouldMask()) {
                                return@intercept chain.proceed()
                            }
                            val tv = chain.thisObject as? TextView
                                ?: return@intercept chain.proceed()
                            if (!isKeyguardClockView(tv) || !looksLikeTime(tv, text)) {
                                return@intercept chain.proceed()
                            }
                            val masked = FakeTime.mask(text, seed())
                            if (TextUtils.equals(text, masked)) {
                                return@intercept chain.proceed()
                            }
                            if (GLog.isDebug) {
                                GLog.d(
                                    "屏蔽时钟文本：\"$text\" → \"$masked\"" +
                                        "（${tv.javaClass.name}#${idName(tv)}）",
                                )
                            }
                            val args = chain.args.toMutableList()
                            args[0] = masked
                            chain.proceed(args.toTypedArray())
                        },
                )
                n++
            }.onFailure { GLog.w("挂钩时钟文本写入失败：$m", it) }
        }
        if (n > 0) GLog.i("挂钩成功：锁屏时钟文本写入兜底 TextView#setText（$n 个重载）")
        return n
    }

    private companion object {
        /**
         * 进程级一次性门闩。
         *
         * `onPackageReady` 在一个进程里会对多个包各回调一次（真机日志：systemui
         * 内同一个钩子被装了 4 遍），每次都会 new 一个本类实例。门闩必须放在
         * companion 里，否则每个实例各挂一遍 —— 重复拦截不仅浪费，还会让
         * `chain.proceed` 层层嵌套、栈越来越深。
         */
        val INSTALLED = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}

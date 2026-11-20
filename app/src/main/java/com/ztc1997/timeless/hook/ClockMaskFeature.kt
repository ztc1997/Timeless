package com.ztc1997.timeless.hook

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.ztc1997.timeless.core.ConfigSnapshot
import com.ztc1997.timeless.core.FakeTime
import com.ztc1997.timeless.core.GLog
import io.github.libxposed.api.XposedInterface

/**
 * 时间屏蔽判定 + 占位符替换。
 *
 * hook 点全部来自 ColorOS 17 真机反编译（见 HOOK_PLAN.md）。分三种替换方式：
 *  1. RETURN_VALUE：方法返回值就是时间文本。必须先 proceed 执行原方法完成其内部
 *     副作用（getSmallTime 会懒初始化 SimpleDateFormat 字段），再用占位符覆盖返回值；
 *     绝不跳过 proceed，否则 SystemUI 崩溃（真机实测）。这样即便 OplusClockExImpl
 *     用 post 异步 setText，读到的也是占位符，不会被真实时间覆盖。
 *  2. SET_TEXT：proceed 之后把 View 上指定字段的 TextView 写成占位符
 *     （锁屏三种时钟）。小时/分钟/冒号分别取占位符的对应片段。
 *  3. HIDE_VIEWS：AOD 图片数字 / Canvas 表盘无文本可替换，每次刷新按判定设 View alpha
 *     （命中 0f / 解除 1f），靠分钟级刷新自动恢复。
 *
 * shouldMask() 每次都读最新 [config]，不缓存；屏幕/时段/勿扰变化即时生效。
 */
class ClockMaskFeature(
    private val xposed: XposedInterface,
    private val classLoader: ClassLoader,
    private val config: () -> ConfigSnapshot,
    private val bag: HookBag,
    /** 任一时钟钩子首次回调时触发（用于懒安装需要宿主 Context 就绪的后续钩子）。 */
    private val onClockActive: (() -> Unit)? = null,
) {

    /** 安装所有命中候选类的钩子，返回成功挂上的钩子数。 */
    fun install(): Int {
        var hooked = 0
        for (target in ClockTargets.ALL) {
            hooked += installTarget(target)
        }
        // 桌面灵感时钟不再走这里： PersonalityClocksFeature 在写入源头
        // （MyCustomizedTextView#tryUpdateText）替换参数，覆盖初始化与后续
        // 全部刷新；旧 desktop 钩子靠遍历 View 树写文本，与源头替换交替
        // 覆盖会导致桌面在两种占位样式间跳动（真机实测），故移除。
        if (hooked == 0) {
            GLog.w("未命中任何已知时钟类，本进程不生效（请核对机型与反编译候选）")
        } else {
            GLog.i("时钟屏蔽已挂 $hooked 个钩子")
        }
        return hooked
    }

    private fun installTarget(target: ClockTargets.Target): Int {
        val cls = Reflect.loadClass(target.className, classLoader) ?: run {
            if (GLog.isDebug) GLog.d("类不存在：${target.className}")
            return 0
        }
        val methods = Reflect.methodsNamed(cls, target.methodName)
        if (methods.isEmpty()) {
            if (GLog.isDebug) GLog.d("方法不存在：${target.className}#${target.methodName}")
            return 0
        }
        var count = 0
        for (m in methods) {
            runCatching {
                bag.add(
                    xposed.hook(m)
                        .setId("timeless:clock:${target.id}:${m.name}")
                        .intercept { chain ->
                            if (onClockActive != null) onClockActive()
                            val shouldMask = shouldMask()
                            if (target.mode == ClockTargets.HookMode.RETURN_VALUE) {
                                // 关键：无论命中与否都必须先 proceed 执行原方法。
                                // Clock.getSmallTime() 内部懒初始化 mClockFormat /
                                // mContentDescriptionFormat 两个 SimpleDateFormat 字段；
                                // 若命中时跳过原方法，updateClock() 紧接着调用
                                // mContentDescriptionFormat.format(...) 会 NPE，导致 SystemUI
                                // 反复崩溃、LSPosed 进入安全模式（真机实测教训）。
                                // 正确做法：让原方法跑完完成初始化，再决定是否用占位符覆盖返回值。
                                val real = chain.proceed()
                                if (!shouldMask) {
                                    real
                                } else {
                                    // 原返回值整串换成假时间（冒号、AM/PM 等非数字保留）。
                                    // 纯函数 + 分钟种子，无状态不闪烁；失败兜底两位假数字。
                                    // 2026-11-20：不再包 StarMoonSpan —— 假数字的字宽与
                                    // 真实数字不同（8 比 1 宽），继承原宽度会把字形挤变形，
                                    // 直接返回纯文本让宿主自己排版。
                                    runCatching {
                                        FakeTime.mask(
                                            real as? CharSequence,
                                            FakeTime.minuteSeed(System.currentTimeMillis()),
                                        )
                                    }.getOrElse { FakeTime.twoDigits(FakeTime.minuteSeed(System.currentTimeMillis())) }
                                }
                            } else {
                                val result = chain.proceed()
                                if (target.mode == ClockTargets.HookMode.HIDE_VIEWS) {
                                    // AOD：无论命中与否都要写 alpha —— 命中透明、解除恢复。
                                    // 分钟级刷新保证状态切换后下一次刷新即回到正确显示。
                                    if (GLog.isDebug) {
                                        GLog.d("AOD 刷新 ${target.id} mask=$shouldMask view=${chain.thisObject?.javaClass?.name}")
                                    }
                                    runCatching { applyAlpha(chain.thisObject, target, shouldMask) }
                                        .onFailure { GLog.w("隐藏 AOD 时钟失败：${target.id}", it) }
                                } else if (shouldMask) {
                                    if (GLog.isDebug) GLog.d("时钟回调 ${target.id} mask=true")
                                    runCatching { applyFields(chain.thisObject, target) }
                                        .onFailure { GLog.w("替换时钟文本失败：${target.id}", it) }
                                }
                                result
                            }
                        }
                )
                GLog.i("挂钩成功：${target.id} → ${target.className}#${m.name}")
                count++
            }.onFailure { GLog.w("挂钩失败：${target.className}#${target.methodName}", it) }
        }
        return count
    }

    /** 按 target 的字段列表写占位符；必要时先取 this$0 外层。 */
    private fun applyFields(clockObject: Any?, target: ClockTargets.Target) {
        var obj = clockObject ?: return
        if (target.id in ClockTargets.OUTER_FIELD_TARGETS) {
            val outer = Reflect.findField(obj.javaClass, "this\$0")
                ?.let { runCatching { it.get(obj) }.getOrNull() }
            if (outer == null) {
                if (GLog.isDebug) GLog.d("runnable 上未取到 this\$0：${target.id}")
                return
            }
            obj = outer
        }

        if (target.textFields.isEmpty()) {
            // clockObject 本身就是 TextView（如 RedTextClock.onTimeChanged）。
            (obj as? TextView)?.let {
                writeMask(it, target.maskHint ?: hintFromClass(it))
            }
            return
        }
        for (fieldName in target.textFields) {
            val field = Reflect.findField(obj.javaClass, fieldName) ?: continue
            val tv = runCatching { field.get(obj) }.getOrNull() as? TextView ?: continue
            writeMask(tv, fieldName)
        }
    }

    /**
     * AOD：按当前判定设置 View alpha。命中睡眠 -> 0f（透明隐藏），否则 -> 1f（恢复）。
     * [textFields] 非空时作用于这些字段指向的 View（如 DefaultClockView 的横/纵容器），
     * 为空则作用于 clockObject 自身（如 DialClockView）。不做状态记忆：靠分钟级刷新自愈。
     */
    private fun applyAlpha(clockObject: Any?, target: ClockTargets.Target, hide: Boolean) {
        val obj = clockObject ?: return
        val alpha = if (hide) 0f else 1f
        if (target.textFields.isEmpty()) {
            (obj as? View)?.let { setAlphaIfChanged(it, alpha) }
            return
        }
        for (fieldName in target.textFields) {
            val field = Reflect.findField(obj.javaClass, fieldName) ?: continue
            val view = runCatching { field.get(obj) }.getOrNull() as? View ?: continue
            setAlphaIfChanged(view, alpha)
        }
    }

    /** 避免同值重复设置触发不必要的重绘。 */
    private fun setAlphaIfChanged(view: View, alpha: Float) {
        if (view.alpha != alpha) view.alpha = alpha
    }

    /**
     * 按字段语义写假时间：hour/minute 字段各自是一段数字，用系统当前时分作为
     * 散列输入构造**长度相同**的假数字段；colon 写原样冒号；无法识别的字段写整串。
     *
     * 假的两位数字与原值同为两位，所以 TextView 的换行、居中、基线都不变；
     * 同分钟内结果恒定（确定性散列），跨分钟自动整组变化。
     */
    private fun writeMask(tv: TextView, hint: String) {
        val seed = FakeTime.minuteSeed(System.currentTimeMillis())
        // 假时间只依赖分钟种子，不需要真实时分：用等长数字段保持 TextView 排版不变。
        val masked = when {
            hint.contains("hour", true) -> FakeTime.twoDigits(seed)
            hint.contains("minute", true) || hint.contains("min", true) -> FakeTime.twoDigits(seed)
            hint.contains("colon", true) -> ":"
            else -> FakeTime.mask("00:00", seed)
        }
        if (tv.text?.toString() != masked) tv.text = masked
    }

    private fun hintFromClass(view: TextView): String = view.javaClass.simpleName

    /** 当前是否应屏蔽。判定统一走 [MaskDecision]。 */
    private fun shouldMask(): Boolean = MaskDecision.shouldMask(config)
}

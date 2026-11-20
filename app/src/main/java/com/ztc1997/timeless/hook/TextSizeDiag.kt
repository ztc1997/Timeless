package com.ztc1997.timeless.hook

import android.content.Context
import android.widget.TextView
import com.ztc1997.timeless.core.GLog
import io.github.libxposed.api.XposedInterface

/**
 * 临时诊断（2026-11-20，收口删除）：动画收尾跳变的字号追踪。
 *
 * 静态分析结论（反编译 out-personality）：
 *  - 时间字号唯一合法入口是 CustomizedTextView.updateTextSize → setTextSize(0, f8)；
 *  - 转场动画期间视图由 OPlus 框架 RT text paint 通道绘制（真实数字不跳），
 *    动画结束回调 releaseFontLayoutFixAndCloseRtTypeface 只同步 typeface/color，
 *    不设 textSize；主 paint 的字号追上是另一条独立调用；
 *  - 上一次诊断没抓到跳变瞬间的字号调用（疑似 hook 挂错类）。
 *
 * 本诊断全量捕获 MyCustomizedTextView 上的每一次真实字号变化：
 *  - TSZ：android.widget.TextView.setTextSize(unit, size)（唯一最终入口，带调用栈）
 *  - UTS：ClockTimeView/SingleClockView.updateTextSize（聚合入口）
 *  - RTP：openRtTextPaint(true/false)（RT 通道开关）
 *  - SYN：SingleClockView.syncClockContentFrom（跨场景文本拷贝）
 *  - DRAW：StarMoonSpan 实际绘制时的 paint 字号（见 StarMoonSpan）
 */
class TextSizeDiag(
    private val xposed: XposedInterface,
    private val bag: HookBag,
) {

    companion object {
        /**
         * 显式开关：默认关闭。排查字号/动画跳变时改为 true 并装 debug 包，
         * 排查完改回 false，避免诊断 hook 常驻影响时钟回调。
         */
        private const val ENABLED = false

        fun isEnabled(): Boolean = ENABLED
    }
    /** 已挂过的 Class，幂等（同一 Class 只挂一次）。 */
    private val hooked = java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())

    private fun fmtStack(): String {
        val st = Throwable().stackTrace
        // 跳过 hook 框架自身帧，只留宿主时钟链路
        val frames = st.drop(3)
            .filter {
                val cn = it.className
                cn.contains("oplus.keyguard.clock") || cn.contains("oplus.keyguard.personality")
            }
            .take(6)
            .joinToString("<-") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
        return if (frames.isEmpty()) "(无时钟链路帧)" else frames
    }

    private fun hookMethod(
        cls: Class<*>,
        name: String,
        paramTypes: Array<Class<*>>,
        id: String,
        onCall: (chain: XposedInterface.Chain) -> Unit,
    ) {
        if (!hooked.add(cls)) return
        val method = runCatching { cls.getDeclaredMethod(name, *paramTypes) }.getOrNull()
        if (method == null) {
            GLog.w("DIAG 类 ${cls.name} 无方法 $name，跳过")
            return
        }
        runCatching { method.isAccessible = true }
        runCatching {
            bag.add(
                xposed.hook(method)
                    .setId("timeless:diag:$id")
                    .intercept { chain ->
                        runCatching { onCall(chain) }
                        chain.proceed()
                    },
            )
            GLog.i("DIAG 挂钩成功：$id → ${cls.name}.$name")
        }.onFailure { GLog.w("DIAG 挂钩失败：$id", it) }
    }

    private fun viewId(v: Any?): String {
        if (v !is TextView) return "?"
        val text = runCatching { v.text?.toString() }.getOrNull() ?: ""
        return "h=${Integer.toHexString(System.identityHashCode(v))}" +
            " tag=${runCatching { v.tag?.toString() }.getOrNull()}" +
            " text=\"$text\"" +
            " ts=${runCatching { v.textSize.toInt() }.getOrNull()}"
    }

    fun install(appContext: Context?) {
        val ctx = appContext ?: return
        val loader = runCatching {
            ctx.createPackageContext(
                "com.oplus.keyguard.personality.clocks",
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            ).classLoader
        }.getOrNull()
        if (loader == null) {
            GLog.w("DIAG 插件包不可用，字号诊断未安装")
            return
        }

        val myTvCls = runCatching {
            loader.loadClass("com.oplus.keyguard.clock.digital.widget.MyCustomizedTextView")
        }.getOrNull()
        val commonTvCls = runCatching {
            loader.loadClass("com.oplus.keyguard.clock.common.widget.CustomizedTextView")
        }.getOrNull()
        val clockTimeViewCls = runCatching {
            loader.loadClass("com.oplus.keyguard.clock.digital.ui.view.ClockTimeView")
        }.getOrNull()
        val singleClockViewCls = runCatching {
            loader.loadClass("com.oplus.keyguard.clock.digital.ui.view.SingleClockView")
        }.getOrNull()

        // ① TSZ：框架 TextView.setTextSize(unit, size) —— 时钟文本字号唯一最终入口。
        // 只在 MyCustomizedTextView 实例上打日志（instanceof 过滤，开销可忽略）。
        runCatching {
            val m = TextView::class.java.getDeclaredMethod(
                "setTextSize",
                Int::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!,
            )
            bag.add(
                xposed.hook(m)
                    .setId("timeless:diag:tsz")
                    .intercept { chain ->
                        val self = chain.thisObject
                        if (myTvCls != null && myTvCls.isInstance(self)) {
                            val unit = chain.getArg(0)
                            val size = (chain.getArg(1) as? Number)?.toFloat()
                            GLog.i(
                                "TSZ unit=$unit size=${size?.toInt()} " +
                                    "${viewId(self as? TextView)} ${fmtStack()}",
                            )
                        }
                        chain.proceed()
                    },
            )
            GLog.i("DIAG 挂钩成功：tsz → android.widget.TextView.setTextSize")
        }.onFailure { GLog.w("DIAG 挂钩 TextView.setTextSize 失败", it) }

        // ② RTP：RT text paint 通道开关（common CustomizedTextView）。
        if (commonTvCls != null) {
            hookMethod(commonTvCls, "openRtTextPaint", arrayOf(Boolean::class.javaPrimitiveType!!), "rtp") { chain ->
                val on = chain.getArg(0) as? Boolean
                GLog.i("RTP on=$on ${viewId(chain.thisObject as? TextView)}")
            }
        }

        // ③ UTS：聚合字号入口（ClockTimeView 与 SingleClockView）。
        if (clockTimeViewCls != null) {
            hookMethod(clockTimeViewCls, "updateTextSize", arrayOf(Float::class.javaPrimitiveType!!), "uts-clock") { chain ->
                val f = (chain.getArg(0) as? Number)?.toFloat()
                GLog.i("UTS-Clock size=${f?.toInt()} ${viewId(chain.thisObject as? TextView)}")
            }
        }
        if (singleClockViewCls != null) {
            hookMethod(singleClockViewCls, "updateTextSize", arrayOf(Float::class.javaPrimitiveType!!), "uts-single") { chain ->
                val f = (chain.getArg(0) as? Number)?.toFloat()
                GLog.i("UTS-Single size=${f?.toInt()}")
            }
            // ④ SYN：跨场景文本拷贝（星月 span 随文本走）。
            hookMethod(
                singleClockViewCls,
                "syncClockContentFrom",
                arrayOf(singleClockViewCls),
                "syn",
            ) { chain ->
                val src = chain.getArg(0)
                GLog.i(
                    "SYN dst=${Integer.toHexString(System.identityHashCode(chain.thisObject))}" +
                        " src=${if (src == null) "null" else Integer.toHexString(System.identityHashCode(src))}",
                )
            }
        }
    }
}

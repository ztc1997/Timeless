package com.ztc1997.timeless.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import com.ztc1997.timeless.core.ConfigSnapshot
import com.ztc1997.timeless.core.GLog
import io.github.libxposed.api.XposedInterface

/**
 * 第三方主题下锁屏 / AOD 时钟的假时间注入接管（旧版本为星月占位）。
 *
 * 屏幕上那串真实时间到底由谁画（真机逐层取证，2026-11-19，结论推翻过三次）：
 *  - 不是 personality 插件的 digital / text / gallery 族：`tryUpdateText` 只改到
 *    一批 `isShown=false` 的离屏控件，`applyTextClockTimeAndExtra`、
 *    `refreshClockText` 在锁屏上一次都没触发；
 *  - 不是 `CustomOplusKeyguardStyleClock`（id keyguard_style_clock）：那个实例
 *    alpha 置 0 后画面毫无变化，`keyguard_style_clock_enabled` 实测也是 0；
 *  - 而是 **VariUIEngine 自绘**：uiautomator dump 里时钟就是普通
 *    `android.widget.FrameLayout`（1272×2800 屏上 y=304..501、x=328..945），
 *    挂在 `com.android.systemui:id/keyguard_plugin_container` 下，没有 text、
 *    没有 id。容器实例就是 `KeyguardEngineContainer`，内部是
 *    `com.zplus.engine.lk_view.TimeView`（0-9 位图逐位 drawBitmap）；
 *  - AOD 同一套模板体系，类换成 `com.oplus.egview.widget.TimeView`
 *    （`mTextLines` 两行「时 / 分」用 canvas.drawText）。
 *
 * **为什么只能拦绘制**：这套引擎自己遍历子元素直接 `view.draw(canvas)`，不经
 * `ViewGroup.dispatchDraw`，所以 `visibility=INVISIBLE` 和 `alpha=0` 全部无效
 * （各自真机实测一次，画面照旧）。唯一入口是绘制方法本身，见
 * [EngineClockPainter]：不 proceed，改在原数字的格子上画星月。
 *
 * **2026-11-19 晚修复（官方主题「灵感接续」整块空白）**：早期那两组「状态写
 * 入口」钩子（`CustomOplusKeyguardStyleClock#setAlpha` → 0、`getInnerAlpha` →
 * 0、`AodBlackLayout#setVisibility` → INVISIBLE，外加自检节拍重放）对第三方主题
 * 无效，但在**官方主题**下这两个视图就是屏幕上那块时钟/息屏容器的真身：一旦被
 * 抹掉，时间**连同日期**一起消失，屏上只剩电量与通知图标（真机截图确认）。
 * 它们只隐藏、不画，与「星月占位」的目标也相悖，故整组删除。现在本类只做
 * 引擎时钟的绘制接管（[hookEngineClocks]）与容器定位诊断。
 */
class HostClockHider(
    private val xposed: XposedInterface,
    private val classLoader: ClassLoader,
    private val config: () -> ConfigSnapshot,
    private val bag: HookBag,
) {

    private fun shouldMask(): Boolean = MaskDecision.shouldMask(config)

    /** 受控的时钟视图（诊断用，仅记录宿主最近的请求，不改变行为）。 */
    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var ticking = false

    fun install(): Int {
        var hooked = 0
        GLog.i("宿主时钟：只做引擎绘制接管（整块 alpha/visibility 隐藏路线已移除）")
        // 引擎钩子只在 SystemUI（锁屏 + AOD）里需要：桌面走 personality 插件的
        // 数字位图，壁纸进程走 ColorfulEngine 的 ClockFrameRender，在那边挂既多余、
        // 又会与本地链路互相打架（见 ensureEngineHook 的说明）。
        // 2026-11-20 修：这条守卫原先只写在 ensureEngineHook 里，install() 的直挂
        // 路径绕过了它 —— 真机日志里 launcher / colorfulengine 也挂上了 TimeView
        // 系列钩子。现在收口到 isSystemUiProcess()，两条路径共用同一判定。
        if (!isSystemUiProcess()) {
            engineHooked = true
            GLog.i("非 SystemUI 进程（${processName()}），跳过引擎时钟钩子")
            return 0
        }
        // UIEngine 锁屏时钟：走的不是文本也不是 View 属性，只能拦 draw。
        val engineLoader = runCatching {
            DndState.currentAppContext()?.createPackageContext(
                "com.oplus.uiengine",
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )?.classLoader
        }.getOrNull()
        GLog.i("引擎 classLoader：${engineLoader?.javaClass?.name ?: "null（回退宿主 CL）"}")
        hooked += hookEngineClocks(engineLoader ?: classLoader)
        // 锁屏容器靠 id 定位，装完立刻试一次，之后交给自检节拍。
        ensureTicking()
        handler.post { runCatching { applyContainer() } }
        // Application 通常再过几百毫秒才 attach，稍后补一次引擎钩子。
        handler.postDelayed(
            { runCatching { ensureEngineHook(DndState.currentAppContext()) } },
            2_000L,
        )
        return hooked
    }

    /** 停止自检节拍。热重载只卸载 HookHandle，不会取消 Handler 上的延迟任务。 */
    fun close() {
        ticking = false
        handler.removeCallbacksAndMessages(null)
        pluginContainer = null
        GLog.i("宿主时钟自检节拍已停止")
    }

    // ---------------------------------------------------------------- 锁屏

    /**
     * 屏幕上真正显示时间/日期的容器。
     *
     * 真机取证（2026-11-19）：第三方主题的锁屏时钟既不是 personality 的文本控件，
     * 也不是 `CustomOplusKeyguardStyleClock`（那个实例 alpha 置 0 后画面毫无变化），
     * 而是 **VariUIEngine 用普通 FrameLayout + Canvas 自绘**的：uiautomator dump 里
     * 时钟就是 `android.widget.FrameLayout`（1272×2800 屏上 y=304..501、x=328..945），
     * 日期是紧邻的另一个 FrameLayout（y=555..618），两者都挂在
     * `com.android.systemui:id/keyguard_plugin_container` 下，没有 text、没有 id。
     *
     * 所以不再逐级找时钟控件，直接按资源 id 定位这个容器并整块变透明。id 用
     * `getIdentifier` 现场解析，避免依赖 R 常量；窗口根通过 `WindowManagerGlobal`
     * 反射枚举（SystemUI 没有 Activity，拿不到 decorView）。
     */
    private var pluginContainer: View? = null
    private var locateLogged = false

    private fun windowRoots(): List<View> {
        val byMethod = runCatching {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmg.getMethod("getInstance").invoke(null)
            val m = wmg.getDeclaredMethod("getRootViews")
            m.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (m.invoke(inst) as? List<View>).orEmpty()
        }.getOrDefault(emptyList())
        if (byMethod.isNotEmpty()) return byMethod
        // 兜底：直接读私有字段 mViews（不同版本方法名有差异）。
        return runCatching {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmg.getMethod("getInstance").invoke(null)
            val f = wmg.getDeclaredField("mViews")
            f.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (f.get(inst) as? List<View>).orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun locatePluginContainer(): View? {
        // 已经拿到有内容的实例就直接复用。
        pluginContainer?.let {
            val n = (it as? android.view.ViewGroup)?.childCount ?: 0
            if (it.isAttachedToWindow && n > 0) return it
        }
        var best: View? = null
        var bestScore = -1
        for (root in windowRoots()) {
            val id = runCatching {
                root.context.resources.getIdentifier(
                    "keyguard_plugin_container",
                    "id",
                    "com.android.systemui",
                )
            }.getOrDefault(0)
            if (id == 0) continue
            val v = runCatching { root.findViewById<View>(id) }.getOrNull() ?: continue
            val n = (v as? android.view.ViewGroup)?.childCount ?: 0
            if (!locateLogged) {
                GLog.i(
                    "候选容器 ${v.javaClass.name} childCount=$n" +
                        " shown=${v.isShown} attached=${v.isAttachedToWindow}",
                )
            }
            // 一定要挑「已附着且可见」的那份：Theme 预览会另外造一份 0×0 的
            // 离屏容器，按子节点数取最大会选中它（真机踩过：dump 出来的整棵树
            // rect 全是 0,0，看不到屏幕上真正在画的那个实例）。
            val score = (if (v.isAttachedToWindow) 2 else 0) + (if (v.isShown) 1 else 0)
            if (score > bestScore || (score == bestScore && n > 0)) {
                bestScore = score
                best = v
            }
        }
        locateLogged = true
        if (best != null && bestScore >= 0) {
            pluginContainer = best
            GLog.i(
                "采用锁屏容器：${best.javaClass.name} 子节点数=" +
                    "${(best as? android.view.ViewGroup)?.childCount} attached=${best.isAttachedToWindow}",
            )
        }
        return pluginContainer
    }

    /**
     * 引擎时钟的假数字注入。
     *
     * 2026-11-20：不再做绘制接管。引擎在画之前会先问"现在几点"
     * （`TimeView#getHourText/getMinuteText`、AOD 的 `calcTimeNumber`、
     * 逐位 PNG 模板的图片名字段），把答案换成假数字即可 —— 宿主会用**主题自己的**
     * 字形、位图宽高和颜色重新排版，观感与主题原生完全一致，也不需要测墨迹包围盒
     * 或复刻基线。详见 [EngineClockInjector]。
     */
    private fun hookEngineClocks(engineLoader: ClassLoader): Int {
        return EngineClockInjector(xposed, config, bag).install(engineLoader)
    }

    private var engineHooked = false
    private var engineDiagLogged = false

    /** 当前进程名；取不到时为 "unknown"（只用于日志）。 */
    private fun processName(): String =
        runCatching { android.app.Application.getProcessName() }.getOrNull() ?: "unknown"

    /**
     * 是否 SystemUI 进程。
     *
     * 取不到进程名时按「是」处理：引擎类在该进程不存在时 install 自己会返回 0，
     * 代价只是几条 warn；而误判成「不是」会让锁屏/AOD 整条链路失效，且没有任何症状
     * 提示。所以判定只在**明确读到了非 systemui 进程名**时才否定。
     */
    private fun isSystemUiProcess(): Boolean {
        val proc = runCatching { android.app.Application.getProcessName() }.getOrNull()
        return proc == null || proc.contains("systemui")
    }

    /** `ActivityThread.getSystemContext()`：还没有 Application 时的兜底 Context。 */
    private fun systemContext(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getDeclaredMethod("currentActivityThread")
            .apply { isAccessible = true }
            .invoke(null)
        val m = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
        m.invoke(thread) as? Context
    }.getOrNull()

    /**
     * 补装引擎时钟钩子。
     *
     * `install()` 跑在宿主 Application 刚创建时，`currentAppContext()` 还是 null
     * （实测 `引擎 classLoader：null`），而引擎类在 VariUIEngine 自己的
     * ClassLoader 里，宿主 CL 找不到。所以由 [ticker] 反复尝试，直到能拿到
     * Context 并 `createPackageContext` 成功。
     */
    private fun ensureEngineHook(ctx: Context?) {
        if (engineHooked) return
        // 引擎时钟只在 SystemUI（锁屏 + AOD）里拦。桌面那套走 personality 插件，
        // 已经有假数字；这里如果一并拦掉，桌面时钟会变成空白而不是假时间。
        if (!isSystemUiProcess()) {
            engineHooked = true
            GLog.i("非 SystemUI 进程（${processName()}），跳过引擎时钟钩子")
            return
        }
        val real = ctx ?: systemContext()
        if (real == null) {
            if (!engineDiagLogged) {
                engineDiagLogged = true
                GLog.w("引擎钩子等待 Context（currentAppContext / getSystemContext 均为空）")
            }
            return
        }
        val loader = runCatching {
            real.createPackageContext(
                "com.oplus.uiengine",
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            ).classLoader
        }.getOrNull()
        if (loader == null) {
            if (!engineDiagLogged) {
                engineDiagLogged = true
                GLog.w("UIEngine 上下文不可用，继续重试")
            }
            return
        }
        engineHooked = true
        GLog.i("UIEngine classLoader 就绪，挂引擎时钟钩子 ${hookEngineClocks(loader)} 个")
    }

    private fun applyContainer() {
        val c = locatePluginContainer() ?: return
        runCatching { ensureEngineHook(c.context) }
    }

    // ------------------------------------------------------------- 状态自检

    private fun ensureTicking() {
        if (ticking) return
        ticking = true
        handler.postDelayed(ticker, RECHECK_MS)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) return
            // 引擎钩子不依赖锁屏容器：AOD 场景下 keyguard_plugin_container 根本
            // 不存在，如果只在 locate 成功时补装，AOD 就会整段漏拦（真机踩过）。
            runCatching { ensureEngineHook(DndState.currentAppContext()) }
            applyContainer()
            if (ticking) handler.postDelayed(this, RECHECK_MS)
        }
    }

    companion object {
        /** 引擎钩子补装的自检间隔。 */
        private const val RECHECK_MS = 20_000L
    }
}

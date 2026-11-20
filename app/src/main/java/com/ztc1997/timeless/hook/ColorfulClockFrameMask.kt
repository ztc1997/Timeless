package com.ztc1997.timeless.hook

import com.ztc1997.timeless.core.ConfigSnapshot
import com.ztc1997.timeless.core.FakeTime
import com.ztc1997.timeless.core.GLog
import io.github.libxposed.api.XposedInterface

/**
 * 壁纸引擎时钟（ColorfulEngine「景深时钟 / 时钟画框」）的假时间注入。
 *
 * ## 背景
 *
 * 换第三方主题后锁屏上出现一串占满屏幕的巨大数字，uiautomator 整棵树里
 * **找不到任何对应控件**（数字区域 y≈600..1400 是空的），说明它不在 View 体系里。
 * 真机取证定位到：
 *
 *  - SurfaceFlinger 里有 `ColorfulWallpaperService` 图层；
 *  - 进程 `com.heytap.colorfulengine` 存在且带 `GLGraffiti` 渲染线程；
 *  - APK 里有 `wallpaper/clockframe/` 包（ClockFrameRender、
 *    `loadCoverTexture`、`playNumberChangeAnimator`）—— 数字是拿壁纸纹理当
 *    填充画出来的，所以数字内部透出的是「另一层壁纸」，这正是景深效果。
 *
 * 结论：这类主题的锁屏/AOD 时间是**壁纸进程自己用 GL 画的**，systemui 里怎么钩
 * 都碰不到。必须把本模块的作用域加上 com.heytap.colorfulengine。
 *
 * ## 钩点
 *
 * 反编译 `ClockFrameRender`：
 *
 * ```java
 * public final String K(Context context, long j10) {           // 1586 行
 *     return new SimpleDateFormat(is24h ? "HHmm" : "hhmm", Locale.US).format(new Date(j10));
 * }
 * public void a(boolean z10, long j10) {                      // updateClockTime
 *     String K = K(context, j10);                             // ① 取 "HHmm"
 *     int I = I(K);                                           // ② 变了几个数字
 *     String H = H(I, K);                                     // ③ x0([各数字值]) + 动画名
 *     ... engine.i0(H) / h0(H, ...) / X(I, H)                 // ④ 上传并播放翻转动画
 * }
 * ```
 *
 * 全 APK 只有这一处格式化时间，`x0(int[])` 会把**每个数字位**作为 shader uniform
 * 上传，宿主自己按数字取主题的数字纹理。所以只改 ① 的返回值即可：
 *
 *  - 返回值变成「四位全同」的假时间（如 "8888"）：四个数字位全部取同一个假数字
 *    纹理，用的仍是**主题自己的字形与景深效果**；
 *  - `I(K)` 与上一分钟必然不同 → 走「fourNumberChange」整串翻转动画，
 *    观感与主题原生换分钟完全一致，且每分钟必变（满足"活着"的要求）；
 *  - 不碰 x0 / 纹理名 / GL 状态，不会有取不到纹理导致空白的风险 —— 6~9 的
 *    数字纹理任何时钟主题都必须提供（0~9 齐全）。
 *
 * 未命中睡眠时段时原样返回真实时间，壁纸恢复原生表现。
 */
class ColorfulClockFrameMask(
    private val xposed: XposedInterface,
    private val config: () -> ConfigSnapshot,
    private val bag: HookBag,
) {

    private fun shouldMask(): Boolean = MaskDecision.shouldMask(config)

    private val installed = INSTALLED

    /** @return 成功挂上的钩子数（非壁纸进程里恒为 0：类不存在）。 */
    fun install(loader: ClassLoader): Int {
        val cls = Reflect.loadClass(RENDER_CLASS, loader) ?: return 0
        if (!installed.compareAndSet(false, true)) return 0
        val m = runCatching {
            cls.getDeclaredMethod(
                "K",
                android.content.Context::class.java,
                java.lang.Long.TYPE,
            )
        }.getOrNull() ?: run {
            GLog.w("壁纸时钟：找不到 ClockFrameRender#K(Context, long)，跳过")
            return 0
        }
        return runCatching {
            bag.add(
                xposed.hook(m)
                    .setId("timeless:colorful:clockframe")
                    .intercept { chain ->
                        val real = chain.proceed()
                        if (!shouldMask()) return@intercept real
                        val fake = FakeTime.mask(
                            real as? CharSequence,
                            FakeTime.minuteSeed(System.currentTimeMillis()),
                        )
                        if (GLog.isDebug) GLog.d("壁纸时钟：\"$real\" → \"$fake\"")
                        fake
                    },
            )
            GLog.i("挂钩成功：壁纸引擎时钟 ClockFrameRender#K（景深时钟返回假时间）")
            1
        }.getOrElse {
            GLog.w("挂钩壁纸引擎时钟失败", it)
            0
        }
    }

    private companion object {
        const val RENDER_CLASS =
            "com.heytap.colorfulengine.wallpaper.clockframe.render.impl.ClockFrameRender"

        /** 进程级一次性门闩（同 KeyguardTextWriteMask：onPackageReady 会多次回调）。 */
        val INSTALLED = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}

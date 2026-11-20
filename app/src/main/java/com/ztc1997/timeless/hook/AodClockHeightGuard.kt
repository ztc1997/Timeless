package com.ztc1997.timeless.hook

import com.ztc1997.timeless.core.ConfigSnapshot
import com.ztc1997.timeless.core.GLog
import io.github.libxposed.api.XposedInterface

/**
 * AOD 电池重叠修复（2026-11-20）。
 *
 * 现象：换用「灵感接续」主题后，屏蔽态 AOD 上星月与电池百分比图标重叠。
 *
 * 真机取证（控制防烧屏变量、日期 y 归一化、四组 hook 隔离、Settings 读写对比）：
 *  - 重叠不是星月尺寸问题（星月与真实数字同高、与冒号比例正常），
 *    而是电池被整体上移约 300px；
 *  - 电池位置由系统 Settings `oplus_keyguardstyle_aod_clock_height` 决定：
 *    正常态=601、屏蔽态=300，差值正好等于电池上移量。手动写回 601 后屏蔽态
 *    电池立刻回位、星月/冒号全部正常且刷新不复发；
 *  - 写方是主题防烧屏动画器（反编译 p0.q.G → Settings.System.putInt），入参来自
 *    digital 时钟 `getClockTimeTextLayoutRect().bottom`；屏蔽态下该矩形被算小，
 *    于是写 300，AodClockLayout 据此把电池上移。
 *  - 五循环对照：正常态稳定 601；屏蔽态第一次循环即变 300。确系屏蔽触发。
 *
 * 修复：屏蔽态拦截对这个 key 的「异常变小」写入，保持正确基准高度；只在明显压缩
 * （新值 < 基准 * [SHRINK_RATIO]）时拦截，避免干扰主题正常防烧屏位移。基准取本进程
 * 内见到的最大值（正常态写入的值恒大于异常压缩值）。
 */
class AodClockHeightGuard(
    private val xposed: XposedInterface,
    private val config: () -> ConfigSnapshot,
    private val bag: HookBag,
) {
    private fun shouldMask(): Boolean = MaskDecision.shouldMask(config)

    companion object {
        private const val KEY = "oplus_keyguardstyle_aod_clock_height"
        /** 新值小于基准的该比例即视为异常压缩。601→300 ≈ 0.50。 */
        private const val SHRINK_RATIO = 0.75f
    }

    /** 本进程内见过的最大基准值（正常态写入）。 */
    @Volatile
    private var baseline: Int = 0

    fun install(classLoader: ClassLoader) {
        // 注入时读一次当前值作基准种子：用户通常在非屏蔽时段安装，此时是正确高度
        // （正常态≈601）。若此时已被污染成小值，则基准只能等正常态写入时再校正。
        runCatching {
            val ctx = DndState.currentAppContext()
            if (ctx != null) {
                @Suppress("DEPRECATION")
                val cur = android.provider.Settings.System.getInt(
                    ctx.contentResolver, KEY, 0,
                )
                if (cur > 0) baseline = cur
                GLog.i("AOD 时钟高度保护：初始基准=$baseline")
            }
        }
        for (clsName in arrayOf(
            "android.provider.Settings\$System",
            "android.provider.Settings",
        )) {
            val cls = runCatching { classLoader.loadClass(clsName) }.getOrNull() ?: continue
            for (m in Reflect.methodsNamed(cls, "putInt")) {
                val pts = m.parameterTypes
                // putInt(ContentResolver, String, int) / (..., int, int)
                if (pts.size < 3) continue
                if (pts[1] != String::class.java) continue
                if (pts[2] != Integer.TYPE) continue
                runCatching {
                    bag.add(
                        xposed.hook(m)
                            .setId("timeless:aod-clock-height-guard:${clsName}:${pts.size}")
                            .intercept { chain ->
                                val key = runCatching { chain.getArg(1) as? String }.getOrNull()
                                if (key != KEY) {
                                    chain.proceed()
                                } else {
                                    val value = runCatching {
                                        (chain.getArg(2) as? Number)?.toInt()
                                    }.getOrNull()
                                    if (value == null) {
                                        chain.proceed()
                                    } else if (!shouldMask()) {
                                        // 正常态：放行并记录正确基准
                                        if (value > baseline) baseline = value
                                        chain.proceed()
                                    } else if (baseline <= 0) {
                                        // 屏蔽态首次命中：尚无基准，用当前值建立后放行
                                        baseline = value
                                        chain.proceed()
                                    } else if (value < baseline * SHRINK_RATIO) {
                                        // 异常压缩：拦截，保持电池位置正确的基准高度
                                        GLog.i("AOD 时钟高度保护：拦截异常压缩 $baseline → $value（屏蔽态）")
                                        null
                                    } else {
                                        chain.proceed()
                                    }
                                }
                            },
                    )
                    GLog.i("AOD 时钟高度保护：挂钩 ${clsName}.${m.name}(${pts.size} 参)")
                }.onFailure { GLog.w("AOD 时钟高度保护挂钩失败：${clsName}.${m.name}", it) }
            }
        }
    }
}

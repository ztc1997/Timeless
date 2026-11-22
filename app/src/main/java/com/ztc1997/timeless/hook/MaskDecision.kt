package com.ztc1997.timeless.hook

import com.ztc1997.timeless.core.ConfigSnapshot
import com.ztc1997.timeless.core.MaskReason
import com.ztc1997.timeless.core.SleepWindow

/**
 * 睡眠屏蔽判定的唯一装配入口。
 *
 * 三个时钟 feature（状态栏/AOD、插件时钟、主题引擎时钟）过去各自持有一份
 * 逻辑完全相同的 shouldMask()：读配置快照 → 按需查勿扰 → 按需查系统作息 →
 * 交给 [SleepWindow.shouldMask] 纯判定。集中到这里，避免以后改判定时漏改某处
 * 导致各时钟行为不一致（真机上这种不一致表现为"状态栏是星月、锁屏还是真实时间"）。
 *
 * 这里只做"装配 hook 层依赖"：DndState / SystemSleepSource 都是本包 object，
 * 无需注入；纯判定在 core 层的 [SleepWindow.shouldMask]，那边有单测覆盖。
 */
object MaskDecision {

    /**
     * 当前屏蔽原因；null 表示不屏蔽。流体云与所有时钟 feature 共用这一份判定，
     * 保证「通知说按时段启动」和「时钟确实被替换」永远一致。
     *
     * @param config 返回最新配置快照的 getter（每次回调都读，勿缓存）
     */
    fun evaluate(config: () -> ConfigSnapshot): MaskReason? {
        // 叠加本进程的「临时禁用」状态：按钮广播在各 hook 进程内闭环写入，
        // 不经过会被冻结的模块 App。进程不落盘，宿主重启后自然失效。
        return decide(config(), applyTempDisable = true)
    }

    /**
     * 不含「临时禁用」的原始屏蔽原因。
     *
     * 流体云用它区分两种 reason=null：临时禁用导致的暂停（底层仍在屏蔽窗口，
     * 通知要保留并显示有效期）vs 真正解除屏蔽（通知该撤销）。时钟判定仍走
     * [evaluate]，临时禁用时照样显示真实时间。
     */
    fun evaluateRaw(config: () -> ConfigSnapshot): MaskReason? =
        decide(config(), applyTempDisable = false)

    private fun decide(snapshot: ConfigSnapshot, applyTempDisable: Boolean): MaskReason? {
        // 临时禁用只影响时钟判定；流体云读原始原因时要把它从快照里剥掉。
        val cfg = if (applyTempDisable) {
            TempDisableLocal.merge(snapshot)
        } else {
            snapshot.copy(tempDisableUntilSec = 0)
        }
        return SleepWindow.decide(
            cfg,
            System.currentTimeMillis(),
            if (cfg.linkDnd) DndState.isActive() else false,
            SystemSleepSource.window(cfg.followSystem),
        )
    }

    /** 当前是否应屏蔽。保留原签名，供各时钟 feature 使用。 */
    fun shouldMask(config: () -> ConfigSnapshot): Boolean = evaluate(config) != null
}

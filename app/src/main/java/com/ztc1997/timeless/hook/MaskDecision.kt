package com.ztc1997.timeless.hook

import com.ztc1997.timeless.core.ConfigSnapshot
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
     * @param config 返回最新配置快照的 getter（每次回调都读，勿缓存）
     */
    fun shouldMask(config: () -> ConfigSnapshot): Boolean {
        val cfg = config()
        return SleepWindow.shouldMask(
            cfg,
            System.currentTimeMillis(),
            if (cfg.linkDnd) DndState.isActive() else false,
            SystemSleepSource.window(cfg.followSystem),
        )
    }
}

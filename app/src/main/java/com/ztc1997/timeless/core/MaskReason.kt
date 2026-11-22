package com.ztc1997.timeless.core

/**
 * 屏蔽启动原因。优先级与 [SleepWindow.decide] 完全一致，流体云照此展示文案。
 *
 * [FORCED] 来自手动覆盖「强制屏蔽」；[DND] 来自免打扰联动；[TIME] 来自固定/系统作息时段。
 */
enum class MaskReason {
    FORCED,
    DND,
    TIME,
}

package com.ztc1997.timeless.core

/**
 * hook 进程与模块 App 之间关于流体云的约定。
 *
 * 不依赖 Android / Xposed 类型：SystemUI 侧用它构造 PendingIntent，
 * 模块 App 侧的 BroadcastReceiver 用它校验动作。
 */
object FluidCloudContract {
    /** 「临时禁用」按钮的唯一动作。PendingIntent 显式限定到模块 App 包名。 */
    const val ACTION_TEMP_DISABLE = "com.ztc1997.timeless.TEMP_DISABLE"

    /** 临时禁用到期时刻（Unix 秒）的 Intent extra。 */
    const val EXTRA_UNTIL_SEC = "timeless_temp_disable_until_sec"

    /** 触发原因（MaskReason.name）的 Intent extra，供日志核对。 */
    const val EXTRA_REASON = "timeless_mask_reason"
}

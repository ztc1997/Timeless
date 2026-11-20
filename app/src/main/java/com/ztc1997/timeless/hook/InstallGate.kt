package com.ztc1997.timeless.hook

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程级一次性安装门闩。
 *
 * 同一进程可能对多个 scope 包回调 `onPackageReady`，钩子必须只装一遍；
 * 热重载会创建新的 module generation（新实例），因此本对象天然按 generation 重置。
 * [reset] 仅供同 generation 内显式重装（如 onHotReloaded 兜底）使用。
 */
class InstallGate {
    private val installed = AtomicBoolean(false)

    /** @return true 表示本 generation 首次调用，调用方应执行安装。 */
    fun tryInstall(): Boolean = installed.compareAndSet(false, true)

    /** 允许同 generation 重新安装；新 generation 无需调用。 */
    fun reset() {
        installed.set(false)
    }
}

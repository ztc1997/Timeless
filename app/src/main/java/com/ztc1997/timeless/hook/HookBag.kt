package com.ztc1997.timeless.hook

import com.ztc1997.timeless.core.GLog
import io.github.libxposed.api.XposedInterface

/**
 * 一次安装产生的钩子句柄集合。
 *
 * 现代 API 里 xposed.hook(...) 返回 HookHandle，卸载钩子必须持有句柄 ——
 * 这是相比传统 XposedBridge 最大的行为差异之一。集中到 bag 里，
 * 热重载 / 重装配置时一次性卸载，避免旧钩子残留叠加。
 */
class HookBag {
    private val handles = java.util.Collections.synchronizedList(
        mutableListOf<XposedInterface.HookHandle>(),
    )

    fun add(handle: XposedInterface.HookHandle) {
        handles.add(handle)
    }

    /** 幂等卸载（HookHandle.unhook 本身幂等） */
    fun unhookAll() {
        val snapshot = synchronized(handles) { handles.toList() }
        handles.clear()
        for (h in snapshot) {
            runCatching { h.unhook() }
                .onFailure { GLog.w("卸载钩子失败: ${h.executable}", it) }
        }
    }
}

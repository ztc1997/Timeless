package com.ztc1997.timeless.hook

import android.content.Context
import android.provider.Settings
import java.lang.reflect.Method

/** 勿扰 / 睡眠模式检测。仅在目标进程使用。 */
object DndState {

    /**
     * Application Context 与反射 Method 的缓存。
     *
     * currentApplication() 在宿主 Application attach 之前返回 null（onPackageReady
     * 阶段常发生），所以只缓存非 null 的 Context —— null 一旦缓存，插件时钟的
     * "Application 创建后"延迟安装就永远拿不到 Context。Method 与进程绑定，
     * 首次拿到即可长期复用。
     */
    @Volatile
    private var cachedContext: Context? = null

    @Volatile
    private var currentApplicationMethod: Method? = null

    /** 当前勿扰模式是否开启（zen_mode != 0）。 */
    fun isActive(): Boolean = runCatching {
        val ctx = currentAppContext() ?: return false
        Settings.Global.getInt(ctx.contentResolver, "zen_mode", 0) != 0
    }.getOrDefault(false)

    /** 通过 ActivityThread 取当前进程 Application Context（带缓存）。 */
    fun currentAppContext(): Context? {
        cachedContext?.let { return it }
        val ctx = runCatching {
            val m = currentApplicationMethod ?: Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .also { it.isAccessible = true }
                .also { currentApplicationMethod = it }
            m.invoke(null) as? Context
        }.getOrNull()
        if (ctx != null) cachedContext = ctx
        return ctx
    }

    /**
     * 清空缓存，仅用于测试隔离；生产代码在 Application attach 前的 null 不会被缓存。
     */
    internal fun resetForTest() {
        cachedContext = null
        currentApplicationMethod = null
    }
}

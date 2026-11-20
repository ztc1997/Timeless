package com.ztc1997.timeless.appconfig

import com.ztc1997.timeless.core.GLog
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 与 LSPosed 框架服务的连接（模块 App 进程）。
 *
 * 现代 API 里，模块 App 通过框架注入的 binder 与 LSPosed 通信：
 * 框架的守护进程 call() 到模块 APK 里合并进来的 XposedProvider，
 * onBinderReceived 后这里就能拿到 [XposedService]，进而读写 remote preferences。
 *
 * XposedProvider 由 io.github.libxposed:service 的清单自动合并
 * （authority = <applicationId>.XposedService），无需自己声明。
 */
object FrameworkBridge {

    fun interface Listener {
        /** service 为 null 表示未连接 / 已断开 */
        fun onServiceChanged(service: XposedService?)
    }

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var registered = false

    private val listeners = mutableListOf<Listener>()

    /** listeners 会被主线程（注册）与 binder 线程（绑定/断开）同时碰，统一加锁拷贝。 */
    private val lock = Any()

    /** 幂等；在 Application.onCreate 里调用。 */
    fun init() {
        if (registered) return
        registered = true
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(svc: XposedService) {
                    val desc = runCatching {
                        "${svc.frameworkName} ${svc.frameworkVersion} (api ${svc.apiVersion})"
                    }.getOrDefault("(信息不可读)")
                    GLog.i("已连接框架服务：$desc")
                    service = svc
                    notifyListeners(svc)
                }

                override fun onServiceDied(svc: XposedService) {
                    GLog.w("框架服务断开")
                    if (service === svc) {
                        service = null
                        notifyListeners(null)
                    }
                }
            })
        } catch (t: Throwable) {
            GLog.e("注册框架服务监听失败", t)
        }
    }

    fun addListener(l: Listener) {
        synchronized(lock) { listeners.add(l) }
        // 注册即同步当前状态：连接可能早于注册（provider 的 binder 先到）
        l.onServiceChanged(service)
    }

    private fun notifyListeners(svc: XposedService?) {
        // 锁只保护列表拷贝；回调里会跨进程读配置，不能持锁调用
        val snapshot = synchronized(lock) { listeners.toList() }
        for (l in snapshot) {
            runCatching { l.onServiceChanged(svc) }
                .onFailure { GLog.w("服务状态回调异常", it) }
        }
    }
}

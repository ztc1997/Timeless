package com.ztc1997.timeless.appconfig

import android.app.Application
import com.ztc1997.timeless.core.GLog

/** 模块 App 进程入口：接上框架服务与配置仓库。 */
class TimelessApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FrameworkBridge.init()
        ModulePreferences.init(this)
        GLog.i("无时 App 启动，配置后端=${ModulePreferences.backend()}")
    }
}

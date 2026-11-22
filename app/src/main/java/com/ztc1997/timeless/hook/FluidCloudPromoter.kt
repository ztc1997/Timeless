package com.ztc1997.timeless.hook

import android.app.Notification
import com.ztc1997.timeless.core.GLog
import io.github.libxposed.api.XposedInterface

/**
 * 在 system_server 进程给「无时」流体云通知补上 FLAG_PROMOTED_ONGOING。
 *
 * 背景（2026-11-22 逆向 NMS + 对照 com.lele.llpower 得出）：
 * ColorOS 流体云的 AOD 胶囊只渲染「真正被系统提升的 ongoing 通知」。系统判定在
 * NotificationManagerService.fixNotificationWithChannel()：
 *
 *   if (isPromotable(notification, channel)) {
 *       canPostPromoted = permissionManager.checkPermissionForDataDelivery(
 *           "android.permission.POST_PROMOTED_NOTIFICATIONS", ...)
 *       if (canPostPromoted) notification.flags |= FLAG_PROMOTED_ONGOING; // 0x40000
 *   }
 *
 * 我们被迫从 SystemUI 进程发通知（只有它能走 Seedling 原生胶囊链路），而
 * SystemUI 的 Manifest 没声明 POST_PROMOTED_NOTIFICATIONS，该权限又不可动态授予
 * （pm grant 报 not changeable；appops 设 allow 也无效），于是自家通知永远拿不到
 * PROMOTED_ONGOING，进不了 AOD 通道。对照 com.lele.llpower 声明并授予了该权限，
 * 所以它的流体云能在 AOD 显示。
 *
 * 解决：在 system_server hook 上述方法，原逻辑跑完后，只对自家通知
 * （pkg=SystemUI 且带无时专属 extra）补这个 flag。其余通知一律放行，绝不碰系统
 * 其它流体云。需要在 LSPosed 作用域里勾选「系统框架 / system_server」才生效。
 */
class FluidCloudPromoter(
    private val xposed: XposedInterface,
    private val bag: HookBag,
) {
    companion object {
        private const val NMS_CLASS = "com.android.server.notification.NotificationManagerService"
        private const val METHOD = "fixNotificationWithChannel"
        private const val SYSTEM_UI_PKG = "com.android.systemui"
        private const val FLAG_PROMOTED_ONGOING = 0x00040000

        /** 发帖时塞的专属标记，用来精确识别自家通知，不误伤系统流体云。 */
        const val EXTRA_TIMEL_FLUID = "timeless_fluid_cloud"
    }

    /**
     * @param hintClassLoader 框架给的 classLoader；在 system_server 里它往往只覆盖
     *   framework.jar，加载不到 services.jar 里的 NMS，故内部再探测多个来源。
     */
    fun install(hintClassLoader: ClassLoader?) {
        val cls = resolveNmsClass(hintClassLoader)
        if (cls == null) {
            GLog.w("NMS 类未找到，流体云 promoted 放行跳过")
            return
        }
        val methods = Reflect.methodsNamed(cls, METHOD)
            .filter { it.parameterTypes.size == 4 }
        if (methods.isEmpty()) {
            GLog.w("$METHOD 方法未找到，流体云 promoted 放行跳过")
            return
        }
        for (m in methods) {
            runCatching {
                bag.add(
                    xposed.hook(m)
                        .setId("timeless:fluid-cloud:promoter")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val notification = chain.getArg(0) as? Notification
                            val pkg = chain.getArg(3) as? String
                            if (notification != null && pkg == SYSTEM_UI_PKG && isOurNotification(notification)) {
                                if (notification.flags and FLAG_PROMOTED_ONGOING == 0) {
                                    notification.flags = notification.flags or FLAG_PROMOTED_ONGOING
                                    GLog.i("流体云 promoted 放行：已补 FLAG_PROMOTED_ONGOING")
                                }
                            }
                            result
                        },
                )
                GLog.i("流体云 promoted 放行 hook 已安装：${m}")
            }.onFailure { GLog.w("挂流体云 promoted 放行钩子失败", it) }
        }
    }

    /**
     * system_server 里加载 NMS（services.jar）的 classLoader 不是框架回调给的
     * param.classLoader——实测后者是 BootClassLoader，只覆盖 framework.jar，加载不到
     * services.jar。最可靠的办法：ServiceManager 返回的 notification 服务就是 NMS
     * 的本地 Binder 实例，它的 classLoader 一定是加载 services.jar 的那个
     * （PathClassLoader，含 services.jar / oplus-services.jar）。
     */
    private fun resolveNmsClass(hint: ClassLoader?): Class<*>? {
        val loaders = LinkedHashSet<ClassLoader>()
        for (svc in arrayOf("notification", "activity", "package", "window")) {
            runCatching {
                val sm = Class.forName("android.os.ServiceManager", false, hint)
                val binder = sm.getMethod("getService", String::class.java).invoke(null, svc)
                binder?.javaClass?.classLoader?.let { loaders.add(it) }
            }
        }
        for (cl in loaders) {
            val found = runCatching { Class.forName(NMS_CLASS, false, cl) }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    private fun isOurNotification(n: Notification): Boolean =
        n.extras?.getBoolean(FluidCloudPromoter.EXTRA_TIMEL_FLUID, false) == true
}

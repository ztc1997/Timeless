package com.ztc1997.timeless.hook

import com.ztc1997.timeless.core.GLog
import io.github.libxposed.api.XposedInterface

/**
 * 让「无时已启动」通知被系统识别为真正的 ColorOS 流体云胶囊。
 *
 * 为什么需要 hook：SystemUI 的 [OplusLiveAlertFilters] 会 AND 多个子 filter，
 * 其中插件 filter（onLiveNotificationPost）只认识白名单 app/服务。我们发的通知
 * 会拿到 flag=0，于是被降级成普通通知——即使塞了 `oplusLiveAlertOptions` 也没用，
 * 因为系统会在 entryToLiveAlert 里覆盖这个 extra。
 *
 * 拦截总 filter，只对自家通知直接返回 7（bit0=状态栏挖孔胶囊、bit1=锁屏胶囊、
 * bit2=AOD），其余通知原样放行。后续完全走系统原生的
 * entryToLiveAlert → Seedling 插件 → 黑色胶囊渲染链路，锁屏/AOD 与挖孔位置
 * 都由系统自己负责，不需要我们画 View。
 */
class FluidCloudFilter(
    private val xposed: XposedInterface,
    private val bag: HookBag,
) {
    companion object {
        private const val FILTER_CLASS =
            "com.oplus.systemui.statusbar.notification.livealert.data.repository.OplusLiveAlertFilters"
        private const val STATUS_BAR_PKG = "com.android.systemui"

        /** 状态栏 + 锁屏 + AOD 全 host。 */
        private const val FLAG_ALL_HOSTS = 7
    }

    fun install(classLoader: ClassLoader) {
        val cls = runCatching { classLoader.loadClass(FILTER_CLASS) }.getOrNull()
        if (cls == null) {
            GLog.w("流体云 filter 类不存在，跳过（当前系统可能不支持流体云）")
            return
        }
        val method = cls.declaredMethods.firstOrNull {
            it.name == "filterEntry" && it.parameterTypes.size == 1
        }
        if (method == null) {
            GLog.w("流体云 filterEntry 方法不存在，跳过")
            return
        }
        bag.add(
            xposed.hook(method)
                .setId("timeless:fluid-cloud:filter")
                .intercept { chain ->
                    if (isOurNotification(chain.getArg(0))) {
                        GLog.i("流体云 filter 放行：自家通知 flag=$FLAG_ALL_HOSTS")
                        FLAG_ALL_HOSTS
                    } else {
                        chain.proceed()
                    }
                },
        )
        GLog.i("流体云 filter hook 已安装：${cls.name}.${method.name}")
    }

    /** 不引用 SystemUI 的类，纯反射读取 NotificationEntry.getSbn()。 */
    private fun isOurNotification(entry: Any?): Boolean {
        if (entry == null) return false
        val sbn = runCatching {
            entry.javaClass.getMethod("getSbn").invoke(entry)
        }.getOrNull() ?: return false
        val packageName = runCatching {
            sbn.javaClass.getMethod("getPackageName").invoke(sbn) as? String
        }.getOrNull()
        val id = runCatching {
            (sbn.javaClass.getMethod("getId").invoke(sbn) as? Number)?.toInt()
        }.getOrNull()
        return packageName == STATUS_BAR_PKG && id == FluidCloudNotifier.NOTIFICATION_ID
    }
}

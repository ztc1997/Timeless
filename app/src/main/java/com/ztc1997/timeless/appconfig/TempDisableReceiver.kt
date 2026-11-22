package com.ztc1997.timeless.appconfig

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ztc1997.timeless.core.ConfigKeys
import com.ztc1997.timeless.core.FluidCloudContract
import com.ztc1997.timeless.core.GLog

/**
 * 流体云「临时禁用」按钮的接收器。
 *
 * 通知由 SystemUI 进程发出（那里才有流体云需要的特殊权限），但 hook 进程
 * 对 remote preferences 是只读的，所以写配置这一步必须回到模块 App —
 * 这里不需要任何通知权限，只走原有的配置通道。
 *
 * 写入的是到期时刻而不是布尔开关：到点后 hook 侧判定自然恢复，
 * 不需要谁再来清一次状态，也不会因为进程没起来而永久禁用。
 */
class TempDisableReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FluidCloudContract.ACTION_TEMP_DISABLE) return
        val until = intent.getIntExtra(FluidCloudContract.EXTRA_UNTIL_SEC, 0)
        val reason = intent.getStringExtra(FluidCloudContract.EXTRA_REASON).orEmpty()

        // 进程可能刚被 PendingIntent 拉起，先接上框架服务再写，
        // 否则会落到本地暂存、要等连接上才补写（延迟不可控）。
        FrameworkBridge.init()
        // until <= 0 当作「提前恢复」：清掉临时禁用，下一次判定立刻按原规则走。
        val ok = ModulePreferences.edit {
            putInt(ConfigKeys.TEMP_DISABLE_UNTIL_SEC, until.coerceAtLeast(0))
        }
        GLog.i(
            "流体云临时禁用：reason=$reason until=$until " +
                "backend=${ModulePreferences.backend()} 写入${if (ok) "成功" else "失败"}",
        )
    }
}

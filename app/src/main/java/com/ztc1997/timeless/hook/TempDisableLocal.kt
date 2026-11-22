package com.ztc1997.timeless.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.ztc1997.timeless.core.ConfigSnapshot
import com.ztc1997.timeless.core.FluidCloudContract
import com.ztc1997.timeless.core.GLog

/**
 * 流体云「临时禁用」的进程内闭环状态。
 *
 * 为什么不用 App 配置：通知由 SystemUI 进程发出，按钮落点若在模块 App，
 * ColorOS 会冻结后台 App，广播只停在 Enqueued；改启动 Activity 又要解冻、
 * 弹界面。这里改为——按钮广播由 SystemUI 发出，每个 hook 进程（SystemUI /
 * launcher / 引擎）动态注册同一个 action 的 receiver，各自在本进程内存里
 * 记住到期时刻，[MaskDecision] 判定时叠加进去。全程不碰 App 进程、不弹界面。
 *
 * 代价：状态不落盘，宿主进程重启后丢失（临时禁用本来就只在本次睡眠窗口有效）。
 * 兼容：App 配置里历史写入的 tempDisableUntilSec 仍生效，[merge] 取更晚的到期时刻。
 */
object TempDisableLocal {

    /** 本进程记住的到期时刻（Unix 秒）；0 表示未临时禁用。 */
    @Volatile
    private var untilSec: Int = 0

    private var receiver: BroadcastReceiver? = null
    private var onReceived: ((Int) -> Unit)? = null

    /** 当前本进程生效的临时禁用到期时刻（Unix 秒）；0 表示未禁用。供通知显示有效期。 */
    fun currentUntilSec(): Int = untilSec

    /** 把本进程临时禁用叠加到配置快照（取更晚的到期时刻）。 */
    fun merge(config: ConfigSnapshot): ConfigSnapshot =
        if (untilSec > config.tempDisableUntilSec) {
            config.copy(tempDisableUntilSec = untilSec)
        } else {
            config
        }

    /**
     * 注册接收器；可重复调用（幂等）。[onReceived] 只在 SystemUI 进程需要 ——
     * 收到临时禁用/恢复屏蔽广播后主动刷新流体云通知，不必等下一次时钟绘制回调。
     * 回调参数是新的到期时刻（0 表示刚恢复屏蔽）。
     */
    fun install(ctx: Context, onReceived: ((Int) -> Unit)? = null) {
        if (receiver != null) return
        this.onReceived = onReceived
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    FluidCloudContract.ACTION_TEMP_DISABLE_LOCAL -> {
                        val until = intent.getIntExtra(FluidCloudContract.EXTRA_UNTIL_SEC, 0)
                        val reason = intent.getStringExtra(FluidCloudContract.EXTRA_REASON).orEmpty()
                        untilSec = until.coerceAtLeast(0)
                        GLog.i("流体云临时禁用（本地闭环）：reason=$reason until=$untilSec")
                    }
                    FluidCloudContract.ACTION_RESTORE_LOCAL -> {
                        untilSec = 0
                        GLog.i("流体云恢复屏蔽（本地闭环）：清除临时禁用")
                    }
                    else -> return
                }
                onReceived?.invoke(untilSec)
            }
        }
        runCatching {
            // 广播由 SystemUI 进程发出，跨 uid，必须 EXPORTED；targetSdk 37 也要求显式声明。
            val filter = IntentFilter().apply {
                addAction(FluidCloudContract.ACTION_TEMP_DISABLE_LOCAL)
                addAction(FluidCloudContract.ACTION_RESTORE_LOCAL)
            }
            ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            receiver = r
            GLog.i("本地临时禁用/恢复接收器已注册")
        }.onFailure { GLog.w("注册本地临时禁用接收器失败", it) }
    }

    /** 热重载时注销，避免与新一代 receiver 并存。 */
    fun close() {
        val r = receiver ?: return
        receiver = null
        onReceived = null
        runCatching { DndState.currentAppContext()?.unregisterReceiver(r) }
            .onFailure { GLog.w("注销本地临时禁用接收器失败", it) }
    }
}

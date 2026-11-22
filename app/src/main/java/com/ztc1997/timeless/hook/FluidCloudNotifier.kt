package com.ztc1997.timeless.hook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.ztc1997.timeless.BuildConfig
import com.ztc1997.timeless.core.ConfigSnapshot
import com.ztc1997.timeless.core.FluidCloudContract
import com.ztc1997.timeless.core.GLog
import com.ztc1997.timeless.core.MaskReason
import com.ztc1997.timeless.core.SleepWindow

/**
 * 流体云提醒：只在 SystemUI 进程安装使用。
 *
 * 为什么从 SystemUI 发：真正的 ColorOS 流体云（挖孔周围的黑色胶囊、锁屏/AOD 常驻）
 * 由 SystemUI 内的 Seedling 插件渲染，帖子来自 SystemUI 才走得了这条原生链路。
 * 模块 App 只是普通三方应用，发出来只会是普通通知。
 *
 * 光发通知还不够：SystemUI 的 OplusLiveAlertFilters 会拦截通知，插件 filter 不认识
 * 我们就会降级成普通通知。FluidCloudFilter 负责把自家通知的 flag 改成 7，
 * 剩下的 options 由系统 entryToLiveAlert 自己生成（手动塞 extra 会被覆盖）。
 *
 * 状态机刻意只认「屏蔽原因变化」：
 * - null → 有原因：发帖
 * - 有原因 → 另一个原因：更新同一条通知（期间原因不会变，但覆盖配置变更的边界）
 * - 有原因 → null：撤销（解除屏蔽 / 临时禁用生效）
 * 多次回调幂等，避免状态栏每秒刷新都重发通知。
 */
object FluidCloudNotifier {

    internal const val NOTIFICATION_ID = 0x54494D45 // TIM E，固定 ID 便于覆盖/撤销
    private const val CHANNEL_ID = "live_alert"

    private val lock = Any()
    private var active = false
    private var lastReason: MaskReason? = null

    /**
     * 由时钟回调驱动：每次判定完是否屏蔽都喂进来（SystemUI 进程外不要调用）。
     * [config] 必须是只读当前快照的 getter，用来算临时禁用的到期时刻。
     */
    fun onDecision(reason: MaskReason?, config: () -> ConfigSnapshot) {
        synchronized(lock) {
            if (reason == null) {
                if (active) {
                    DndState.currentAppContext()?.let(::cancel)
                    active = false
                    lastReason = null
                }
                return
            }
            if (active && reason == lastReason) return

            val ctx = DndState.currentAppContext()
            if (ctx == null) {
                GLog.w("流体云未发出：SystemUI Context 尚未就绪")
                return
            }
            post(ctx, reason, config())
            active = true
            lastReason = reason
        }
    }

    /** 热重载 / 卸载钩子时撤销，避免旧 generation 的通知留在状态栏。 */
    fun dismiss() {
        synchronized(lock) {
            if (!active) return
            DndState.currentAppContext()?.let(::cancel)
            active = false
            lastReason = null
        }
    }

    private fun post(ctx: Context, reason: MaskReason, cfg: ConfigSnapshot) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm == null) {
            GLog.w("流体云未发出：拿不到 NotificationManager")
            return
        }
        ensureChannel(nm)

        val title = "无时已启动"
        val detail = "启动原因：${reason.label()}"
        // chip 最多显示 7 个字符，超长会被截断成只剩图标
        val short = "无时·${reason.shortLabel()}"

        val untilSec = tempDisableUntilSec(reason, cfg)
        val intent = Intent(FluidCloudContract.ACTION_TEMP_DISABLE)
            .setPackage(BuildConfig.APPLICATION_ID)
            .putExtra(FluidCloudContract.EXTRA_UNTIL_SEC, untilSec)
            .putExtra(FluidCloudContract.EXTRA_REASON, reason.name)
        val actionPending = PendingIntent.getBroadcast(
            ctx,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(Icon.createWithResource(ctx, android.R.drawable.ic_menu_recent_history))
            .setContentTitle(title)
            .setContentText(detail)
            .setShortCriticalText(short)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setRequestPromotedOngoing(true)
            .addAction(
                Notification.Action.Builder(null, "临时禁用", actionPending).build(),
            )
            .build()

        runCatching { nm.notify(NOTIFICATION_ID, notification) }
            .onSuccess { GLog.i("流体云已发出：reason=${reason.name} tempDisableUntil=$untilSec") }
            .onFailure { GLog.w("流体云发出失败", it) }
    }

    private fun cancel(ctx: Context) {
        runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIFICATION_ID)
        }.onFailure { GLog.w("撤销流体云失败", it) }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "无时提醒",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }.onFailure { GLog.w("创建流体云通知渠道失败", it) }
    }

    /**
     * 临时禁用多久：按用户选的「本次睡眠窗口结束」算，取不到窗口（例如由勿扰或
     * 强制开启触发、没开固定时段）时兜底 1 小时。
     */
    private fun tempDisableUntilSec(reason: MaskReason, cfg: ConfigSnapshot): Int {
        val now = System.currentTimeMillis()
        val windowEnd = if (cfg.scheduleEnabled || cfg.followSystem) {
            SleepWindow.nextWindowEndMillis(cfg, SystemSleepSource.window(cfg.followSystem), now)
        } else {
            null
        }
        val millis = windowEnd ?: (now + 60 * 60 * 1000L)
        // 留 1 分钟余量，避免到点的那一分钟就立刻又屏蔽一次
        return (millis / 1000).toInt() + 60
    }

    private fun MaskReason.label(): String = when (this) {
        MaskReason.FORCED -> "强制屏蔽"
        MaskReason.DND -> "免打扰模式"
        MaskReason.TIME -> "睡眠时段"
    }

    private fun MaskReason.shortLabel(): String = when (this) {
        MaskReason.FORCED -> "强制"
        MaskReason.DND -> "勿扰"
        MaskReason.TIME -> "时段"
    }
}

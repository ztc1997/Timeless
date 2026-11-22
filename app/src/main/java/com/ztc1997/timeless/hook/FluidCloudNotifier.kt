package com.ztc1997.timeless.hook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.ztc1997.timeless.core.ConfigSnapshot
import com.ztc1997.timeless.core.FluidCloudContract
import com.ztc1997.timeless.core.GLog
import com.ztc1997.timeless.core.MaskReason
import com.ztc1997.timeless.core.SleepWindow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 * 通知有三态，靠「不含临时禁用的原始原因」+ 本地临时禁用状态区分：
 * - 原始原因非空 + 未临时禁用 → 屏蔽中：「无时已启动·原因」+【临时禁用】
 * - 原始原因非空 + 临时禁用中 → 暂停中：保留通知，显示「临时禁用至 HH:mm」+【恢复屏蔽】
 * - 原始原因为 null → 真解除：撤销
 * 时钟判定仍把临时禁用算进去（临时禁用时显示真实时间），只有流体云把它区分开。
 * 多次回调幂等，避免状态栏每秒刷新都重发通知。
 */
object FluidCloudNotifier {

    /** 通知 UI 状态；用于去重，避免同一状态每秒重发。 */
    private data class UiState(val mode: Mode, val reason: MaskReason?, val untilSec: Int) {
        enum class Mode { ACTIVE, PAUSED, GONE }
    }

    internal const val NOTIFICATION_ID = 0x54494D45 // TIM E，固定 ID 便于覆盖/撤销
    // 不用系统流体云的 live_alert 渠道：那条渠道是 HIGH + 系统默认提示音，
    // 而系统渠道属于 com.android.systemui，改它的音频属性会连系统流体云一起静音。
    // importance 从 LOW 改成 DEFAULT 后必须换 ID：Android 不允许修改已存在渠道的
    // importance，同 ID 重建无效（实测 naturalImportance 仍为 2）。v2 让系统真正
    // 新建 DEFAULT 渠道，通知才会被提升为 PROMOTED_ONGOING 并进入 AOD 渲染通道。
    private const val CHANNEL_ID = "timeless_live_alert_v2"

    private val lock = Any()
    private var active = false
    private var lastState: UiState? = null

    /**
     * 临时禁用到期兜底：熄屏/AOD 期间没有时钟绘制，光靠 onDecision 回调无法在
     * 到期那一刻把 PAUSED 通知切回 ACTIVE。进入暂停态时挂一个到点任务，到点后
     * 用上次记住的 reason 直接把通知切回屏蔽态（此时时钟判定也随 untilSec 过期
     * 而恢复屏蔽，二者一致）。热重载/状态切换时统一取消。
     *
     * Handler 不在 HookHandle 管理范围内，热重载必须显式清理（见 dismiss）。
     */
    private val expireHandler = Handler(Looper.getMainLooper())
    private val expireRunnable = Runnable {
        synchronized(lock) {
            val paused = lastState
            if (paused == null || paused.mode != UiState.Mode.PAUSED) return@Runnable
            val ctx = DndState.currentAppContext()
            if (ctx == null) return@Runnable
            val activeState = UiState(UiState.Mode.ACTIVE, paused.reason, 0)
            post(ctx, activeState, ConfigSnapshot.DEFAULT)
            active = true
            lastState = activeState
            GLog.i("流体云临时禁用到期，兜底切回屏蔽态")
        }
    }

    /**
     * 由时钟回调驱动：每次判定完是否屏蔽都喂进来（SystemUI 进程外不要调用）。
     *
     * 这里不看传入的 reason（那是叠加临时禁用后的结果，无法区分「暂停」与「解除」），
     * 而是自己读原始原因 + 本地临时禁用状态算出三态。[config] 是只读快照 getter。
     */
    fun onDecision(reason: MaskReason?, config: () -> ConfigSnapshot) {
        val rawReason = MaskDecision.evaluateRaw(config)
        val cfg = config()
        val untilSec = TempDisableLocal.currentUntilSec()
        val nowSec = System.currentTimeMillis() / 1000
        val paused = rawReason != null && untilSec > nowSec
        val target = when {
            rawReason == null -> UiState(UiState.Mode.GONE, null, 0)
            paused -> UiState(UiState.Mode.PAUSED, rawReason, untilSec)
            else -> UiState(UiState.Mode.ACTIVE, rawReason, 0)
        }
        synchronized(lock) {
            if (target == lastState) return
            apply(target, cfg)
        }
    }

    /** 由 TempDisableLocal 的本地广播回调触发：立即刷新通知，不等时钟绘制。 */
    fun refreshNow(config: () -> ConfigSnapshot) = onDecision(null, config)

    private fun apply(target: UiState, cfg: ConfigSnapshot) {
        // 离开暂停态就撤掉到期兜底任务；进入暂停态则在下方重新挂。
        expireHandler.removeCallbacks(expireRunnable)
        if (target.mode == UiState.Mode.GONE) {
            if (active) {
                GLog.i("流体云撤销：原始原因为 null（真解除屏蔽）")
                DndState.currentAppContext()?.let(::cancel)
                active = false
            }
            lastState = target
            return
        }
        val ctx = DndState.currentAppContext()
        if (ctx == null) {
            GLog.w("流体云未发出：SystemUI Context 尚未就绪")
            return
        }
        post(ctx, target, cfg)
        active = true
        lastState = target
        if (target.mode == UiState.Mode.PAUSED) {
            val delayMs = (target.untilSec.toLong() - System.currentTimeMillis() / 1000) * 1000L
            expireHandler.postDelayed(expireRunnable, delayMs.coerceAtLeast(0))
        }
    }

    /** 热重载 / 卸载钩子时撤销，避免旧 generation 的通知与到期任务残留。 */
    fun dismiss() {
        synchronized(lock) {
            expireHandler.removeCallbacksAndMessages(null)
            if (!active) return
            DndState.currentAppContext()?.let(::cancel)
            active = false
            lastState = null
        }
    }

    private fun post(ctx: Context, state: UiState, cfg: ConfigSnapshot) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm == null) {
            GLog.w("流体云未发出：拿不到 NotificationManager")
            return
        }
        ensureChannel(nm)

        val reason = state.reason ?: return
        val paused = state.mode == UiState.Mode.PAUSED
        val title: String
        val detail: String
        // chip 最多显示 7 个字符，超长会被截断成只剩图标
        val short: String
        val actionLabel: String
        val actionIntent: Intent
        if (paused) {
            // 临时禁用中：保留流体云，告知有效期，点按钮立即恢复屏蔽。
            val untilText = formatUntil(state.untilSec)
            title = "无时已临时禁用"
            detail = "已暂停屏蔽，至 $untilText 自动恢复 · 点此立即恢复屏蔽"
            short = "无时·已停"
            actionLabel = "恢复屏蔽"
            actionIntent = Intent(FluidCloudContract.ACTION_RESTORE_LOCAL)
        } else {
            // 屏蔽中：说明原因，给临时禁用按钮。
            title = "无时已启动"
            detail = "启动原因：${reason.label()}"
            short = "无时·${reason.shortLabel()}"
            actionLabel = "临时禁用"
            val untilSec = tempDisableUntilSec(reason, cfg)
            actionIntent = Intent(FluidCloudContract.ACTION_TEMP_DISABLE_LOCAL)
                .putExtra(FluidCloudContract.EXTRA_UNTIL_SEC, untilSec)
                .putExtra(FluidCloudContract.EXTRA_REASON, reason.name)
        }
        // 按钮广播在系统进程内闭环：SystemUI 发出、各 hook 进程的 TempDisableLocal
        // 动态 receiver 接收，不经过会被冻结的模块 App。IMMUTABLE + UPDATE_CURRENT
        // 保证覆盖旧 Intent 的到期时刻。用 action 名做 requestCode，避免
        // 「临时禁用」与「恢复屏蔽」两个 PendingIntent 互相覆盖。
        val actionPending = PendingIntent.getBroadcast(
            ctx,
            actionIntent.action.hashCode(),
            actionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = Notification.Builder(ctx, CHANNEL_ID)
            .addExtras(Bundle().apply {
                putInt("immersiveCardType", 2)
                // 给 system_server 的 FluidCloudPromoter 精确识别自家通知用，
                // 避免误伤系统其它流体云。
                putBoolean(FluidCloudPromoter.EXTRA_TIMEL_FLUID, true)
            })
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
                Notification.Action.Builder(null, actionLabel, actionPending).build(),
            )
            .build()

        runCatching { nm.notify(NOTIFICATION_ID, notification) }
            .onSuccess {
                GLog.i("流体云已发出：mode=${state.mode} reason=${reason.name} until=${state.untilSec}")
            }
            .onFailure { GLog.w("流体云发出失败", it) }
    }

    /** 把 Unix 秒格式化成本地 HH:mm，给「临时禁用至」文案用。 */
    private fun formatUntil(untilSec: Int): String =
        runCatching {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(untilSec * 1000L))
        }.getOrDefault("稍后")

    private fun cancel(ctx: Context) {
        runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIFICATION_ID)
        }.onFailure { GLog.w("撤销流体云失败", it) }
    }

    private fun ensureChannel(nm: NotificationManager) {
        // 渠道属性只在创建/显式更新时生效，所以每次发帖都覆盖写一次（幂等）。
        // importance 必须是 DEFAULT(3) 而不是 LOW(2)：对照 com.lele.llpower 实测
        // requestPromotedOngoing=true 在 LOW 渠道下不会把通知提升成 PROMOTED_ONGOING，
        // 只有 DEFAULT 及以上系统才认，进而才走 Seedling 的 AOD 渲染通道。
        // 静音不靠降 importance，靠下面的 setSound(null)，DEFAULT 一样无声。
        val channel = NotificationChannel(
            CHANNEL_ID,
            "无时提醒",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(true)
            description = "无时启动提醒（静默）"
        }
        runCatching { nm.createNotificationChannel(channel) }
            .onFailure { GLog.w("创建流体云通知渠道失败", it) }
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

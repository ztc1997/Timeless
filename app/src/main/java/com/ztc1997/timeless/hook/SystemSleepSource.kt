package com.ztc1997.timeless.hook

import android.net.Uri
import android.provider.Settings
import com.ztc1997.timeless.core.ConfigSnapshot
import com.ztc1997.timeless.core.GLog
import com.ztc1997.timeless.core.SystemSleepTime
import java.util.concurrent.Executors

/**
 * 系统自动学习的入睡/起床时间（ColorOS 场景服务）。
 *
 * 数据来源：`content://com.coloros.sceneservice.scenesprovider/user_profile`
 * 的 `tag=0` 行（智能/自动学习那一条），`start_sleep_time` / `end_sleep_time`
 * 存的是「小时的小数」，例如 23.9 → 23:54、6.83 → 06:50。同一份数据就是
 * 设置 → 个人信息里「入睡时间 / 起床时间（自动学习）」显示的值。
 *
 * 为什么在 hook 进程里查：
 *  - 这是普通 ContentProvider 查询，不是 root 读私有文件；目标进程
 *    （SystemUI / launcher）本身持有 `oppo.permission.OPPO_COMPONENT_SAFE`，
 *    有权读取，无需 su、无需额外 scope。
 *  - 判定发生在时钟刷新回调里（每分钟级），所以结果必须缓存：本类做
 *    5 分钟软过期 + 60 秒最小重试间隔，查询开销不落在每次刷新上。
 *
 * 顺带把结果写进 Settings.Secure（[SystemSleepTime.SECURE_KEY]），模块 App
 * 没有该 provider 的读权限，靠这个键在设置页把系统时间展示出来。
 */
object SystemSleepSource {

    private const val AUTHORITY = "com.coloros.sceneservice.scenesprovider"
    private const val PATH = "user_profile"
    private const val COL_TAG = "tag"
    private const val COL_START = "start_sleep_time"
    private const val COL_END = "end_sleep_time"

    /** 智能来源的 tag：表里 '0' 是自动学习，'1'/'4' 是手动。 */
    private const val TAG_SMART = "0"

    /** 缓存软过期：作息一天只变几次，5 分钟足够跟手。 */
    private const val REFRESH_MS = 5 * 60_000L

    /** 失败后的最小重试间隔，避免 provider 不可用时每分钟都打一次。 */
    private const val RETRY_MS = 60_000L

    // lazy：跟随系统关闭时 window() 第一行就返回，不必解析 Uri。既省一次启动开销，
    // 也让该分支能在无 Android runtime 的 JVM 单测里跑（Uri.parse 是 android stub）。
    private val uri: Uri by lazy { Uri.parse("content://$AUTHORITY/$PATH") }

    @Volatile
    private var cached: Pair<String, String>? = null

    @Volatile
    private var lastAttempt = 0L

    /** 上次落盘的内容，只用来避免重复打日志。 */
    @Volatile
    private var reported: String? = null

    /** 上一次配置里的「跟随系统」开关与请求序号，用来判断这次配置变化要不要立刻查。 */
    @Volatile
    private var following = false

    @Volatile
    private var lastToken = Int.MIN_VALUE

    /** 只打印一次失败，避免日志刷屏。 */
    @Volatile
    private var failureLogged = false

    /**
     * Settings.Secure 是 binder 同步写，不能在时钟刷新回调线程上做（分钟级、
     * 逐位数字写入时尤其频繁），否则会把回调卡在跨进程写盘上。所有落盘统一
     * 丢到这一条单线程队列：既串行（report 与 ack 保序），又不阻塞调用方。
     */
    private val writer by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "timeless-sleep-write").apply { isDaemon = true }
        }
    }

    /**
     * 取系统作息窗口。
     *
     * @param enabled 配置里的「跟随系统」开关；关闭时直接返回 null（不查询）
     * @return (入睡, 起床)，未取到返回 null
     */
    fun window(enabled: Boolean): Pair<String, String>? {
        if (!enabled) return null
        val now = System.currentTimeMillis()
        val current = cached
        if (current != null && now - lastAttempt < REFRESH_MS) return current
        if (now - lastAttempt < RETRY_MS) return current

        synchronized(this) {
            val nowInLock = System.currentTimeMillis()
            if (nowInLock - lastAttempt < RETRY_MS) return cached
            lastAttempt = nowInLock
            val fresh = query()
            if (fresh != null) {
                cached = fresh
                report(fresh)
            } else if (!failureLogged) {
                failureLogged = true
                GLog.w("读取系统作息失败，跟随系统时段暂不可用（沿用上次值或手动时段）")
            }
            return cached
        }
    }

    /**
     * 配置变化时调用：跟随系统打开、或设置页发来新的刷新请求时，立刻查一次。
     *
     * 配置回调在框架的 binder 线程上跑，provider 查询可能阻塞，所以丢到 [writer]
     * 单线程队列，查完由 [report] 落 Settings.Secure，设置页就能立刻读到
     * （[SystemSleepTime.ACK_KEY] 回执）。
     */
    fun onConfigChanged(cfg: ConfigSnapshot) = synchronized(this) {
        val token = cfg.sleepRequest
        val needFetch = cfg.followSystem && (!following || token != lastToken)
        following = cfg.followSystem
        lastToken = token
        if (!needFetch) return@synchronized
        // 复用 report/ack 的单线程队列：配置变更可能连续推送，逐个排队比每条 new Thread
        // 更稳，也保证 fetchNow → report → ack 的顺序不被并发打乱。
        writer.execute { runCatching { fetchNow(token) } }
    }

    /** 立即查询一次（忽略软过期），查完写缓存 + Settings.Secure 回执。 */
    fun fetchNow(token: Int = 0): Pair<String, String>? = synchronized(this) {
        lastAttempt = System.currentTimeMillis()
        val fresh = query()
        if (fresh != null) {
            cached = fresh
            failureLogged = false
            report(fresh)
        } else if (!failureLogged) {
            failureLogged = true
            GLog.w("立即刷新系统作息失败，沿用上次值")
        }
        if (fresh != null) ack(token)
        cached
    }

    /** 告诉设置页「第 [token] 次刷新请求已办完」；token 为 0 表示不是被请求触发的。 */
    private fun ack(token: Int) {
        if (token == 0) return
        // 与 report 共用单线程队列，保证「先落 SECURE_KEY，再写 ACK」的顺序：
        // 设置页轮询到 ACK 时读到的必定是本次的新值，而非上一次的旧值。
        writer.execute {
            runCatching {
                val ctx = DndState.currentAppContext() ?: return@execute
                Settings.Secure.putInt(ctx.contentResolver, SystemSleepTime.ACK_KEY, token)
            }.onFailure { GLog.w("回写系统作息回执失败", it) }
        }
    }

    private fun query(): Pair<String, String>? = try {
        val ctx = DndState.currentAppContext()
        if (ctx == null) {
            null
        } else {
            val cursor = ctx.contentResolver.query(
                uri,
                arrayOf(COL_TAG, COL_START, COL_END),
                null,
                null,
                null,
            )
            cursor?.use { c ->
                val tagIdx = c.getColumnIndex(COL_TAG)
                val startIdx = c.getColumnIndex(COL_START)
                val endIdx = c.getColumnIndex(COL_END)
                if (startIdx < 0 || endIdx < 0) {
                    null
                } else {
                    var result: Pair<String, String>? = null
                    while (c.moveToNext()) {
                        val tag = if (tagIdx >= 0) c.getString(tagIdx) else TAG_SMART
                        if (tag != TAG_SMART) continue
                        val start = SystemSleepTime.toHhMm(c.getString(startIdx))
                        val end = SystemSleepTime.toHhMm(c.getString(endIdx))
                        if (start != null && end != null) {
                            result = start to end
                            break
                        }
                    }
                    result
                }
            }
        }
    } catch (t: Throwable) {
        if (!failureLogged) {
            failureLogged = true
            GLog.w("查询系统作息异常", t)
        }
        null
    }

    /**
     * 把结果落一份到 Settings.Secure，供模块 App 展示（写不了就算了，不影响屏蔽）。
     *
     * 每次都写、不按内容去重：这份镜像可能被外部清掉（清数据、系统重置、用户手动删），
     * 只在内容变化时写会留下"hook 认为写过、界面上却是空"的空洞。
     *
     * 写盘投递到 [writer] 后台线程：本方法由时钟刷新回调（[window]）与配置回调
     * （[fetchNow]）调用，binder 同步写不能占着回调线程。
     */
    private fun report(window: Pair<String, String>) {
        val payload = SystemSleepTime.encode(window.first, window.second)
        writer.execute {
            runCatching {
                val ctx = DndState.currentAppContext() ?: return@execute
                Settings.Secure.putString(ctx.contentResolver, SystemSleepTime.SECURE_KEY, payload)
                if (payload != reported) GLog.i("系统作息 = ${window.first} → ${window.second}")
                reported = payload
            }.onFailure { GLog.w("回写系统作息到 Settings.Secure 失败", it) }
        }
    }
}

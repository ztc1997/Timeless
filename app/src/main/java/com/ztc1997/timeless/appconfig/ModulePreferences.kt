package com.ztc1997.timeless.appconfig

import android.content.Context
import android.content.SharedPreferences
import com.ztc1997.timeless.core.ConfigKeys
import com.ztc1997.timeless.core.ConfigSnapshot
import com.ztc1997.timeless.core.PendingWrite

/**
 * 模块 App 侧配置仓库。两个后端：
 *  - REMOTE：XposedService.getRemotePreferences(group)，框架托管，写进去
 *    目标进程立刻能看到并收到推送。正常状态的唯一真实来源。
 *  - LOCAL：模块自己的 SharedPreferences，两个用途：
 *      1) 镜像：写远端成功的键顺手抄一份，框架还没连上时先用它顶上。
 *         框架注入 binder 是异步的（daemon 反向调用 XposedProvider），
 *         release 包启动快、首帧常常赶在绑定之前，只能从空的本地文件读到
 *         默认值——这就是"强杀后设置页永远是默认参数"的来源。
 *      2) 暂存：框架未连接时的写入，记下键名，连接后补写进远端。
 *         否则那次写入会被永久丢在本地，远端旧值继续生效。
 *
 * 本类不引用任何 io.github.libxposed.api 类型（只引用 service，后者会打包），
 * 可安全在模块 App 进程加载。
 */
object ModulePreferences {

    enum class Backend { REMOTE, LOCAL, NONE }

    /** 本地暂存待补写到远端的键（StringSet，只存在于本地文件；不写进远端分组） */
    private const val PENDING_KEY = "__timeless_pending"

    private var local: SharedPreferences? = null
    @Volatile private var remote: SharedPreferences? = null
    @Volatile private var initialized = false
    private val listeners = mutableListOf<() -> Unit>()

    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        notifyChanged()
    }

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val ctx = context.applicationContext
        local = ctx.getSharedPreferences(ConfigKeys.GROUP, Context.MODE_PRIVATE)
        FrameworkBridge.addListener { svc ->
            val rp = svc?.let { runCatching { it.getRemotePreferences(ConfigKeys.GROUP) }.getOrNull() }
            if (rp !== remote) {
                remote?.let { runCatching { it.unregisterOnSharedPreferenceChangeListener(changeListener) } }
                rp?.registerOnSharedPreferenceChangeListener(changeListener)
                remote = rp
            }
            if (rp != null) flushPending(rp)
            notifyChanged()
        }
        initialized = true
    }

    /**
     * 把框架未连接时暂存在本地的键补写进远端。
     *
     * 这类键是用户在界面上刚改完保存的，属于最新意图，**覆盖**远端旧值；
     * 而单纯的历史镜像值不在此列（只在 [mirrorToLocal] 里躺着，不会被回灌）。
     */
    private fun flushPending(rp: SharedPreferences) {
        val lp = local ?: return
        val pending = runCatching { lp.getStringSet(PENDING_KEY, emptySet()) }.getOrNull().orEmpty()
        if (pending.isEmpty()) return

        val stored = runCatching { lp.all }.getOrNull().orEmpty()
        val toWrite = PendingWrite.plan(pending, stored)
        if (toWrite.isEmpty()) {
            clearPending(lp)
            return
        }
        val ok = runCatching {
            val e = rp.edit()
            for ((key, value) in toWrite) {
                when (value) {
                    is Boolean -> e.putBoolean(key, value)
                    is Int -> e.putInt(key, value)
                    is String -> e.putString(key, value)
                }
            }
            e.commit()
        }.getOrDefault(false)
        // 写失败（例如服务刚好断开）就留住 pending，下次连接再补
        if (ok) clearPending(lp)
    }

    private fun markPending(keys: Collection<String>) {
        val lp = local ?: return
        val valid = keys.filter { it in ConfigKeys.ALL_KEYS }
        if (valid.isEmpty()) return
        runCatching {
            val existing = lp.getStringSet(PENDING_KEY, emptySet()).orEmpty()
            lp.edit().putStringSet(PENDING_KEY, existing + valid).commit()
        }
    }

    private fun clearPending(lp: SharedPreferences) {
        runCatching { lp.edit().remove(PENDING_KEY).commit() }
    }

    /** 写远端成功后抄一份到本地：冷启动框架未就绪时界面先用它顶，不闪默认值。 */
    private fun mirrorToLocal(ops: Map<String, Any?>) {
        val lp = local ?: return
        val cache = ops.filterKeys { it in ConfigKeys.ALL_KEYS }
        if (cache.isEmpty()) return
        runCatching {
            val e = lp.edit()
            for ((key, value) in cache) {
                when (value) {
                    null -> e.remove(key)
                    is Boolean -> e.putBoolean(key, value)
                    is Int -> e.putInt(key, value)
                    is String -> e.putString(key, value)
                }
            }
            e.commit()
        }
    }

    private fun active(): SharedPreferences? = remote ?: local

    fun backend(): Backend = when {
        remote != null -> Backend.REMOTE
        local != null -> Backend.LOCAL
        else -> Backend.NONE
    }

    /** 当前生效配置的一份快照；后端未就绪时给默认值。 */
    fun load(): ConfigSnapshot {
        val p = active() ?: return ConfigSnapshot.DEFAULT
        return runCatching { ConfigSnapshot.from(p) }.getOrDefault(ConfigSnapshot.DEFAULT)
    }

    /** 写入配置（远端优先），返回是否成功。 */
    fun edit(block: EditorScope.() -> Unit): Boolean {
        val target = active() ?: return false
        return runCatching {
            val scope = EditorScope(target.edit())
            scope.block()
            val ok = scope.commit()
            if (ok) {
                if (target === remote) mirrorToLocal(scope.ops) else markPending(scope.ops.keys)
            }
            ok
        }.getOrDefault(false)
    }

    fun observe(l: () -> Unit) {
        synchronized(listeners) { listeners.add(l) }
    }

    fun unobserve(l: () -> Unit) {
        synchronized(listeners) { listeners.remove(l) }
    }

    /** 可能来自 binder 线程（框架推送配置），调用方自行切主线程。 */
    private fun notifyChanged() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        for (l in snapshot) {
            runCatching { l() }
        }
    }

    /**
     * 写入作用域：转发给真实的 Editor，同时记下写了哪些键——
     * 远端成功要镜像到本地，本地暂存要登记待补写。
     */
    class EditorScope internal constructor(private val delegate: SharedPreferences.Editor) {

        internal val ops = LinkedHashMap<String, Any?>()

        fun putBoolean(key: String, value: Boolean) {
            delegate.putBoolean(key, value)
            ops[key] = value
        }

        fun putInt(key: String, value: Int) {
            delegate.putInt(key, value)
            ops[key] = value
        }

        fun putString(key: String, value: String?) {
            delegate.putString(key, value)
            ops[key] = value
        }

        fun remove(key: String) {
            delegate.remove(key)
            ops[key] = null
        }

        internal fun commit(): Boolean = delegate.commit()
    }
}

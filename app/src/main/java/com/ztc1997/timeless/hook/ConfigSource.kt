package com.ztc1997.timeless.hook

import android.content.SharedPreferences
import com.ztc1997.timeless.core.ConfigKeys
import com.ztc1997.timeless.core.ConfigSnapshot
import com.ztc1997.timeless.core.GLog
import io.github.libxposed.api.XposedInterface

/**
 * 目标进程侧配置读取。
 *
 * 现代 API 的 [XposedInterface.getRemotePreferences] 由框架托管，跨进程可靠；
 * 返回的 SharedPreferences 在目标进程里只读，但支持变更监听 —— 配置一改，
 * 框架主动推到本进程，无需轮询。
 *
 * 框架在 Binder 线程触发变更回调，[onChanged] 的实现方需自行注意线程。
 */
class ConfigSource(private val xposed: XposedInterface) {

    @Volatile
    var snapshot: ConfigSnapshot = ConfigSnapshot.DEFAULT
        private set

    /** 配置变化回调，框架在 Binder 线程触发 */
    @Volatile
    var onChanged: ((ConfigSnapshot) -> Unit)? = null

    private var prefs: SharedPreferences? = null

    private val changeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            val fresh = read()
            if (fresh == snapshot) return@OnSharedPreferenceChangeListener
            snapshot = fresh
            GLog.i("配置已更新（触发键=$key）")
            runCatching { onChanged?.invoke(fresh) }
                .onFailure { GLog.w("配置变化回调异常", it) }
        }

    /** 打开通道并读取首帧配置。可重复调用（热重载后重新打开）。 */
    fun open() {
        close()
        val caps = runCatching { xposed.frameworkProperties }.getOrDefault(0L)
        if (caps and XposedInterface.PROP_CAP_REMOTE == 0L) {
            GLog.w("框架未声明 remote preferences 能力，使用默认配置")
            snapshot = ConfigSnapshot.DEFAULT
            return
        }
        try {
            val p = xposed.getRemotePreferences(ConfigKeys.GROUP)
            prefs = p
            p.registerOnSharedPreferenceChangeListener(changeListener)
            snapshot = read()
            GLog.i("配置通道 = remote preferences，已读取首帧")
        } catch (t: Throwable) {
            GLog.w("remote preferences 不可用，使用默认配置", t)
            snapshot = ConfigSnapshot.DEFAULT
        }
    }

    fun close() {
        val p = prefs
        prefs = null
        if (p != null) {
            runCatching { p.unregisterOnSharedPreferenceChangeListener(changeListener) }
        }
    }

    private fun read(): ConfigSnapshot {
        val p = prefs ?: return snapshot
        return try {
            ConfigSnapshot.from(p)
        } catch (t: Throwable) {
            GLog.w("读取配置失败，沿用当前快照", t)
            snapshot
        }
    }
}

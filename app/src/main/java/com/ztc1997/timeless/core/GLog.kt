package com.ztc1997.timeless.core

import android.util.Log

/**
 * 统一日志出口。刻意不引用任何 io.github.libxposed.api 类型：
 * 那些类只在目标进程由框架提供，模块 App 进程里没有。框架接口通过
 * 纯 Java 函数式接口 [Sink] 注入。
 */
object GLog {

    const val TAG = "Timeless"

    fun interface Sink {
        fun log(priority: Int, tag: String, msg: String, tr: Throwable?)
    }

    @Volatile
    private var sink: Sink? = null

    /** 日志等级阈值，跟随配置：0=关 1=错误 2=信息 3=调试 */
    @Volatile
    var level: Int = ConfigKeys.DEFAULT_LOG_LEVEL

    /** 由目标进程入口注入框架日志出口 */
    fun install(s: Sink) {
        sink = s
    }

    private fun enabled(priority: Int): Boolean = when (level) {
        0 -> false
        1 -> priority >= Log.ERROR
        2 -> priority >= Log.INFO
        else -> true
    }

    val isDebug: Boolean get() = enabled(Log.DEBUG)

    private fun emit(priority: Int, msg: String, tr: Throwable?) {
        if (!enabled(priority)) return
        val s = sink
        if (s != null) {
            runCatching { s.log(priority, TAG, msg, tr) }
        } else {
            if (tr != null) {
                Log.println(priority, TAG, "$msg\n${Log.getStackTraceString(tr)}")
            } else {
                Log.println(priority, TAG, msg)
            }
        }
    }

    fun d(msg: String) = emit(Log.DEBUG, msg, null)
    fun i(msg: String) = emit(Log.INFO, msg, null)
    fun w(msg: String, tr: Throwable? = null) = emit(Log.WARN, msg, tr)
    fun e(msg: String, tr: Throwable? = null) = emit(Log.ERROR, msg, tr)
}

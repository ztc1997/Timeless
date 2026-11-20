package com.ztc1997.timeless.core

/**
 * 框架未连接时"暂存本地"的那批键，等框架连上后要补写进远端。
 *
 * 关键区别：本地文件里躺着两类值——
 *  1) **暂存**（[pending] 里的键）：用户在界面上改完保存的，是最新意图，
 *     必须覆盖远端旧值；
 *  2) **镜像**（写入远端成功后抄下来的副本）：只是为了让冷启动第一帧
 *     有值可显示，绝不能回灌远端，否则框架那边清空过配置会被旧值复活。
 *
 * 纯函数，便于单测；不引用 Android / Xposed 类型。
 */
object PendingWrite {

    /** 支持写进 remote preferences 的值类型之外的一律忽略。 */
    fun plan(pending: Set<String>, local: Map<String, Any?>): Map<String, Any> {
        val result = LinkedHashMap<String, Any>()
        for (key in pending) {
            if (key !in ConfigKeys.ALL_KEYS) continue
            val value = local[key] ?: continue
            if (value is Boolean || value is Int || value is String) result[key] = value
        }
        return result
    }
}

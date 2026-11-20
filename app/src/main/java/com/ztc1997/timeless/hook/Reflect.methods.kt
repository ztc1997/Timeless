package com.ztc1997.timeless.hook

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** 轻量反射工具，仅依赖 JDK，模块 App 与目标进程都能用。 */
object Reflect {

    /**
     * 反射结果缓存。字段查找（findField）沿继承链逐级 getDeclaredField，在
     * 分钟级的时钟回调里每次都做一遍是不必要的开销；方法枚举同理，只在安装期
     * 调用但一并缓存。Key 用 Class 引用而非类名字符串，避免同名类在不同
     * ClassLoader（插件/宿主）下串味。
     */
    private data class Key(val cls: Class<*>, val name: String)

    /** 用包装类承载可空的查找结果——ConcurrentHashMap 的 value 不接受 null。 */
    private class FieldResult(val field: Field?)
    private class MethodsResult(val methods: List<Method>)

    private val fieldCache = ConcurrentHashMap<Key, FieldResult>()
    private val methodsCache = ConcurrentHashMap<Key, MethodsResult>()

    /**
     * 清空缓存。仅在宿主 ClassLoader 被整体替换、旧 Class 引用需释放时调用；
     * 常态热重载走的是新 generation，其静态字段是全新的，无需手动清。
     */
    fun clearCache() {
        fieldCache.clear()
        methodsCache.clear()
    }

    /** 用宿主 classLoader 加载类，失败返回 null。 */
    fun loadClass(name: String, cl: ClassLoader): Class<*>? =
        runCatching { Class.forName(name, false, cl) }.getOrNull()

    /**
     * 取某个类上所有可挂的同名方法。
     * 现代 API 的 hook(Executable) 需要具体的 Method，没有 hookAllMethods 批量写法，
     * 所以自己枚举。抽象方法过滤掉（LSPlant 会拒绝）。结果按 (Class, name) 缓存。
     */
    fun methodsNamed(cls: Class<*>, name: String): List<Method> =
        methodsCache.getOrPut(Key(cls, name)) {
            MethodsResult(
                cls.declaredMethods
                    .filter { it.name == name && !Modifier.isAbstract(it.modifiers) }
                    .onEach { runCatching { it.isAccessible = true } },
            )
        }.methods

    /** 沿继承链查找字段并设为可访问；未找到的结果（null）也缓存，避免反复扫描。 */
    fun findField(start: Class<*>, name: String): Field? =
        fieldCache.getOrPut(Key(start, name)) { FieldResult(findFieldUncached(start, name)) }.field

    private fun findFieldUncached(start: Class<*>, name: String): Field? {
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            val found = runCatching { c!!.getDeclaredField(name) }.getOrNull()
            if (found != null) {
                runCatching { found.isAccessible = true }
                return found
            }
            c = c.superclass
        }
        return null
    }
}

package com.ztc1997.timeless

import com.ztc1997.timeless.hook.Reflect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Reflect 缓存语义单测：沿继承链找字段、null 结果可缓存、同名方法枚举过滤抽象方法。 */
class ReflectTest {

    @Before
    fun setUp() = Reflect.clearCache()

    private open class Base(val baseField: String = "base")
    private class Child : Base() {
        val childField: String = "child"
        fun target() = "one"
        fun target(i: Int) = "two"
    }

    private abstract class AbstractTarget {
        abstract fun abstractOne()
        fun concreteOne() = "concrete"
    }

    @Test
    fun `findField 沿继承链找到父类字段`() {
        val field = Reflect.findField(Child::class.java, "baseField")
        assertEquals(String::class.java, field?.type)
        val child = Child()
        assertEquals("base", field?.get(child))
    }

    @Test
    fun `findField 同一 class 与名字返回缓存的同一实例`() {
        val first = Reflect.findField(Child::class.java, "childField")
        val second = Reflect.findField(Child::class.java, "childField")
        assertSame(first, second)
    }

    @Test
    fun `findField 找不到的结果被缓存且始终为null`() {
        assertNull(Reflect.findField(Child::class.java, "noSuchField"))
        assertNull(Reflect.findField(Child::class.java, "noSuchField"))
    }

    @Test
    fun `methodsNamed 枚举重载并过滤抽象方法`() {
        val overloads = Reflect.methodsNamed(Child::class.java, "target")
        assertEquals(2, overloads.size)
        assertTrue(overloads.any { it.parameterTypes.isEmpty() })
        assertTrue(overloads.any { it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) })

        val concrete = Reflect.methodsNamed(AbstractTarget::class.java, "abstractOne")
        assertTrue(concrete.isEmpty())
        assertEquals(1, Reflect.methodsNamed(AbstractTarget::class.java, "concreteOne").size)
    }

    @Test
    fun `clearCache 后重新反射返回新的Field实例`() {
        val first = Reflect.findField(Child::class.java, "childField")
        Reflect.clearCache()
        val afterClear = Reflect.findField(Child::class.java, "childField")
        // getDeclaredField 每次返回新拷贝，未命中缓存时对象身份不同
        assertTrue(first !== afterClear)
    }
}

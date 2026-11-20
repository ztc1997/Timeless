package com.ztc1997.timeless

import com.ztc1997.timeless.hook.InstallGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InstallGateTest {

    @Test
    fun `only first install succeeds`() {
        val gate = InstallGate()
        assertTrue(gate.tryInstall())
        assertFalse(gate.tryInstall())
        assertFalse(gate.tryInstall())
    }

    @Test
    fun `reset allows one more install`() {
        val gate = InstallGate()
        assertTrue(gate.tryInstall())
        assertFalse(gate.tryInstall())
        gate.reset()
        assertTrue(gate.tryInstall())
        assertFalse(gate.tryInstall())
    }

    @Test
    fun `concurrent calls install exactly once`() {
        val gate = InstallGate()
        val winners = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(64)
        repeat(64) {
            pool.execute {
                start.await()
                if (gate.tryInstall()) winners.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(1, winners.get())
    }
}

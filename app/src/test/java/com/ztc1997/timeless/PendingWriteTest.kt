package com.ztc1997.timeless

import com.ztc1997.timeless.core.ConfigKeys
import com.ztc1997.timeless.core.PendingWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingWriteTest {

    @Test
    fun `pending keys are planned with their local values`() {
        val plan = PendingWrite.plan(
            pending = setOf(ConfigKeys.ENABLED, ConfigKeys.WINDOW_START),
            local = mapOf(
                ConfigKeys.ENABLED to false,
                ConfigKeys.WINDOW_START to "01:30",
                // 镜像值（用户没改过）：不得回灌远端
                ConfigKeys.WINDOW_END to "06:00",
            ),
        )
        assertEquals(
            mapOf(ConfigKeys.ENABLED to false, ConfigKeys.WINDOW_START to "01:30"),
            plan,
        )
    }

    @Test
    fun `pending keys missing from local are skipped`() {
        val plan = PendingWrite.plan(
            pending = setOf(ConfigKeys.MANUAL_OVERRIDE),
            local = mapOf(ConfigKeys.ENABLED to true),
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `internal keys never reach remote preferences`() {
        val plan = PendingWrite.plan(
            pending = setOf("__timeless_pending", ConfigKeys.LINK_DND),
            local = mapOf("__timeless_pending" to setOf("x"), ConfigKeys.LINK_DND to true),
        )
        assertEquals(mapOf(ConfigKeys.LINK_DND to true), plan)
    }

    @Test
    fun `unsupported value types are dropped`() {
        val plan = PendingWrite.plan(
            pending = setOf(ConfigKeys.LOG_LEVEL),
            local = mapOf(ConfigKeys.LOG_LEVEL to 3L),
        )
        assertTrue(plan.isEmpty())
    }
}

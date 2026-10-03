package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorDemandTest {
    @Test
    fun `sampling stays on until every consumer releases it`() {
        val demand = MonitorDemand()
        demand.set(MonitorClient.UI, true)
        demand.set(MonitorClient.SESSION, true)
        assertTrue(demand.shouldRun())
        demand.set(MonitorClient.UI, false)
        assertTrue(demand.shouldRun())
        demand.set(MonitorClient.SESSION, false)
        assertFalse(demand.shouldRun())
    }

    @Test
    fun `duplicate set and release are idempotent`() {
        val demand = MonitorDemand()
        demand.set(MonitorClient.OVERLAY, true)
        demand.set(MonitorClient.OVERLAY, true)
        assertEquals(setOf(MonitorClient.OVERLAY), demand.clients())
        demand.set(MonitorClient.OVERLAY, false)
        demand.set(MonitorClient.OVERLAY, false)
        assertFalse(demand.shouldRun())
    }
}

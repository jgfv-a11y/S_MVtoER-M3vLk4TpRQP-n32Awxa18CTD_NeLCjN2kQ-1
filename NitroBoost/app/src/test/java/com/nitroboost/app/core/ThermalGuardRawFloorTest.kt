package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.1: the raw-temperature hard floor. The opt-in thermal override makes
 * the OS report THERMAL_STATUS_NONE, blinding status-only guards; the floor
 * maps thermistor readings directly so the heat limit cannot be disabled.
 */
class ThermalGuardRawFloorTest {

    @Test
    fun `mapping is monotone with the documented thresholds`() {
        assertEquals(ThermalGuard.STATUS_NOMINAL, ThermalGuard.rawStatusFor(null))
        assertEquals(ThermalGuard.STATUS_NOMINAL, ThermalGuard.rawStatusFor(0.0))
        assertEquals(ThermalGuard.STATUS_NOMINAL, ThermalGuard.rawStatusFor(40.0))
        assertEquals(ThermalGuard.STATUS_MODERATE, ThermalGuard.rawStatusFor(44.0))
        assertEquals(ThermalGuard.STATUS_SEVERE, ThermalGuard.rawStatusFor(48.0))
        assertEquals(ThermalGuard.STATUS_CRITICAL, ThermalGuard.rawStatusFor(52.0))
        assertEquals(ThermalGuard.STATUS_CRITICAL, ThermalGuard.rawStatusFor(70.0))
    }

    @Test
    fun `override scenario - raw floor drops the thermal module at severe heat`() {
        // With the override active the OS status is 0; only the raw floor
        // can trigger a de-escalation.
        val osStatus = ThermalGuard.STATUS_NOMINAL // blind by the override
        val raw = ThermalGuard.rawStatusFor(48.5)
        val eff = maxOf(osStatus, raw)
        val drop = ThermalGuard.modulesToDrop(eff)
        assertTrue("severe raw heat must drop the THERMAL module", drop.contains(Module.THERMAL))
    }

    @Test
    fun `moderate raw heat drops GPU but keeps the thermal override`() {
        val drop = ThermalGuard.modulesToDrop(ThermalGuard.rawStatusFor(44.5))
        assertEquals(setOf(Module.GPU), drop)
    }

    @Test
    fun `critical raw heat drops cpu gpu and thermal`() {
        val drop = ThermalGuard.modulesToDrop(ThermalGuard.rawStatusFor(55.0))
        assertEquals(setOf(Module.CPU, Module.GPU, Module.THERMAL), drop)
    }
}

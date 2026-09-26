package com.nitroboost.app.core

import com.nitroboost.app.core.adaptive.ThermalTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalTrendTest {

    @Test fun `no data means zero slope and unchanged status`() {
        val t = ThermalTrend()
        assertEquals(0.0, t.slopePerMin(), 1e-9)
        assertEquals(0, t.effectiveStatus(0))
        t.record(1000, null) // null readings ignored
        assertEquals(0.0, t.slopePerMin(), 1e-9)
    }

    @Test fun `rising slope escalates one tier while still mild`() {
        val t = ThermalTrend()
        // 40°C at t=0, +0.5°C every 30s => 1.0°C/min... make it 1.5°C/min
        t.record(0, 40.0)
        t.record(30_000, 40.75)
        t.record(60_000, 41.5)
        t.record(90_000, 42.25)
        t.record(120_000, 43.0)
        assertTrue(t.slopePerMin() >= ThermalTrend.EARLY_WARN_SLOPE)
        // LIGHT (1) with sharp rise -> effective MODERATE (2)
        assertEquals(2, t.effectiveStatus(1))
        // MODERATE (2) with sharp rise -> effective SEVERE (3)
        assertEquals(3, t.effectiveStatus(2))
        // SEVERE (3) and above: trust the OS, no further escalation
        assertEquals(3, t.effectiveStatus(3))
    }

    @Test fun `flat or cooling slope never escalates`() {
        val t = ThermalTrend()
        t.record(0, 45.0)
        t.record(120_000, 45.0)
        assertEquals(1, t.effectiveStatus(1))

        val c = ThermalTrend()
        c.record(0, 45.0)
        c.record(120_000, 43.0)
        assertEquals(1, c.effectiveStatus(1))
        assertTrue(c.cooling(2))
    }

    @Test fun `window trims old samples`() {
        val t = ThermalTrend(windowMs = 60_000)
        t.record(0, 40.0)
        t.record(100_000, 41.0)
        t.record(140_000, 41.5)
        t.record(200_000, 42.0)
        // only 140k/200k remain inside the 60s window:
        // (42.0 - 41.5) over 1 min = 0.5°C/min
        assertEquals(0.5, t.slopePerMin(), 0.01)
    }

    @Test fun `clear resets the buffer`() {
        val t = ThermalTrend()
        t.record(0, 40.0)
        t.record(120_000, 43.0)
        t.clear()
        assertEquals(0.0, t.slopePerMin(), 1e-9)
        assertFalse(t.cooling(2)) // no data + hot status: not cooling
        assertTrue(t.cooling(0))  // low status counts as cool
    }
}

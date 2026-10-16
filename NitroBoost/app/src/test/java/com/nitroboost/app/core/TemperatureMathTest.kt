package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TemperatureMathTest {

    @Test
    fun `normalizes millidegrees used by standard thermal zones`() {
        assertEquals(44.5, TemperatureMath.normalizeThermalZoneCelsius("44500\n")!!, 0.0)
        assertEquals(-5.25, TemperatureMath.normalizeThermalZoneCelsius("-5250")!!, 0.0)
    }

    @Test
    fun `preserves direct Celsius values used by OEM kernels`() {
        assertEquals(44.0, TemperatureMath.normalizeThermalZoneCelsius("44")!!, 0.0)
        assertEquals(44.5, TemperatureMath.normalizeThermalZoneCelsius(" 44.5 ")!!, 0.0)
    }

    @Test
    fun `rejects unavailable non finite and implausible readings`() {
        assertNull(TemperatureMath.normalizeThermalZoneCelsius(""))
        assertNull(TemperatureMath.normalizeThermalZoneCelsius("not-a-temperature"))
        assertNull(TemperatureMath.normalizeThermalZoneCelsius("NaN"))
        assertNull(TemperatureMath.normalizeThermalZoneCelsius("-273000"))
        assertNull(TemperatureMath.normalizeThermalZoneCelsius("151000"))
    }

    @Test
    fun `keeps documented physical bounds`() {
        assertEquals(-40.0, TemperatureMath.normalizeThermalZoneCelsius("-40")!!, 0.0)
        assertEquals(150.0, TemperatureMath.normalizeThermalZoneCelsius("150")!!, 0.0)
    }
}

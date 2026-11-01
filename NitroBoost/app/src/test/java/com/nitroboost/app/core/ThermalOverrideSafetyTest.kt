package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.ThermalOverrideTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThermalOverrideSafetyTest {
    private fun journal(): Journal {
        val file = File.createTempFile("nitro-thermal", ".json")
        file.delete()
        file.deleteOnExit()
        return Journal(file)
    }

    @Test
    fun `legacy thermal task is rejected without sending a command`() {
        val executor = FakeExecutor().apply {
            privileged = true
            thermalSafetyAvailable = true
        }
        val result = ThermalOverrideTask().apply(
            BoostContext(testProfile(Module.THERMAL, thermalOverride = true), executor, journal())
        )

        assertEquals(TaskStatus.Skipped, result.status)
        assertTrue(result.detail.contains("safety-policy"))
        assertTrue(executor.shellLog.isEmpty())
    }

    @Test
    fun `capability registry exposes rejection rather than unsupported`() {
        val executor = FakeExecutor().apply { privileged = true }
        val status = CapabilityRegistry().status(
            ThermalOverrideTask(),
            BoostContext(testProfile(Module.THERMAL), executor, journal()),
            nowMs = 100L
        )
        assertEquals(CapabilityState.REJECTED, status.state)
        assertTrue(status.reason.contains("thermal"))
    }

    @Test
    fun `legacy profile field is normalized away`() {
        val safe = ProfileValidation.validatedCopy(
            testProfile(Module.THERMAL, thermalOverride = true)
        )
        assertFalse(safe!!.thermalOverride)
    }
}

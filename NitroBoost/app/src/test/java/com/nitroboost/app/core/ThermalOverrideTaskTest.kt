package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.ThermalOverrideTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The thermal override shim must stay fail-closed forever: Android exposes
 * no safe threshold API, and the old `override-status 0` command disabled
 * the OS thermal governor. This test fails if the task ever starts
 * emitting that command again.
 */
class ThermalOverrideTaskTest {

    @Test
    fun `override is never supported and never touches the system`() {
        val ex = FakeExecutor()
        val f = File.createTempFile("nitro_thermal_shim", ".json")
        f.delete()
        f.deleteOnExit()
        val ctx = BoostContext(
            testProfile(Module.THERMAL, thermalOverride = true), ex, Journal(f)
        )
        val task = ThermalOverrideTask()

        assertFalse(task.isSupported(ctx))

        val result = task.apply(ctx)

        assertEquals(TaskStatus.Skipped, result.status)
        assertTrue(result.entries.isEmpty())
        assertTrue("no shell command may be issued", ex.shellLog.isEmpty())
        assertTrue("no write may be issued", ex.written.isEmpty())
    }
}

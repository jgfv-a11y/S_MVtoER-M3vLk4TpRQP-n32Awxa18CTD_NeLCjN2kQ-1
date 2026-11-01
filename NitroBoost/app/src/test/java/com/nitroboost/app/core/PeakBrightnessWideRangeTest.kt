package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.PeakBrightnessTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Peak brightness must never DIM the panel: panels with wider integer
 * brightness ranges (1..2047/4095) previously dropped to the hardcoded 255.
 */
class PeakBrightnessWideRangeTest {

    private fun journal(): Journal {
        val f = File.createTempFile("nitro_peak_wide", ".json")
        f.delete()
        f.deleteOnExit()
        return Journal(f)
    }

    @Test
    fun `wide-range panel keeps its higher current value instead of dimming to 255`() {
        val ex = FakeExecutor()
        ex.privileged = true
        ex.sys["screen_brightness"] = "2047" // OEM range 1..4095
        ex.sys["screen_brightness_mode"] = "1"
        val j = journal()
        val task = PeakBrightnessTask()
        val ctx = BoostContext(testProfile(Module.DISPLAY), ex, j)

        val r = task.apply(ctx)
        assertEquals(TaskStatus.Applied, r.status)
        // Brightness must not go DOWN: max(current, 255).
        assertEquals("2047", ex.sysSettingGet("screen_brightness"))
        assertEquals("0", ex.sysSettingGet("screen_brightness_mode")) // manual mode still applied
        j.add(r.entries)

        val b = j.entries.first { it.key == "screen_brightness" }
        assertEquals("2047", b.oldValue)
        assertEquals("2047", b.newValue)

        // Restore path still works with the wide-range value.
        assertTrue(Journal.restore(b, ex))
        assertEquals("2047", ex.sysSettingGet("screen_brightness"))
    }

    @Test
    fun `classic range panel still reaches 255`() {
        val ex = FakeExecutor()
        ex.privileged = true
        ex.sys["screen_brightness"] = "100"
        ex.sys["screen_brightness_mode"] = "1"
        val j = journal()
        val task = PeakBrightnessTask()
        val ctx = BoostContext(testProfile(Module.DISPLAY), ex, j)

        val r = task.apply(ctx)
        assertEquals(TaskStatus.Applied, r.status)
        assertEquals("255", ex.sysSettingGet("screen_brightness"))
    }

    @Test
    fun `malformed stored brightness falls back to the classic max`() {
        val ex = FakeExecutor()
        ex.privileged = true
        ex.sys["screen_brightness"] = "not-a-number"
        ex.sys["screen_brightness_mode"] = "1"
        val j = journal()
        val task = PeakBrightnessTask()
        val ctx = BoostContext(testProfile(Module.DISPLAY), ex, j)

        val r = task.apply(ctx)
        assertEquals(TaskStatus.Applied, r.status)
        assertEquals("255", ex.sysSettingGet("screen_brightness"))
    }
}

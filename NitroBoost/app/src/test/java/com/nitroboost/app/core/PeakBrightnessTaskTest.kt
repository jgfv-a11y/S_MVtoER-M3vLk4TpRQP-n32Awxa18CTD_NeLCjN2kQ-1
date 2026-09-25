package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.PeakBrightnessTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PeakBrightnessTaskTest {

    private fun journal(): Journal {
        val f = File.createTempFile("nitro_peak", ".json")
        f.deleteOnExit()
        return Journal(f)
    }

    @Test
    fun `locks max brightness, journals both keys, restores both`() {
        val ex = FakeExecutor()
        ex.privileged = true
        ex.sys["screen_brightness"] = "128"
        ex.sys["screen_brightness_mode"] = "1" // auto-brightness on
        val j = journal()
        val task = PeakBrightnessTask()
        val ctx = BoostContext(testProfile(Module.DISPLAY), ex, j)

        assertTrue(task.isSupported(ctx))
        assertFalse(task.isApplied(ctx))

        val r = task.apply(ctx)
        assertEquals(TaskStatus.Applied, r.status)
        assertEquals(2, r.entries.size)
        j.add(r.entries)

        val b = j.entries.first { it.key == "screen_brightness" }
        assertEquals(JournalEntry.Kind.SYS_SETTING, b.kind)
        assertEquals("128", b.oldValue)
        assertEquals("255", b.newValue)
        val m = j.entries.first { it.key == "screen_brightness_mode" }
        assertEquals("1", m.oldValue)
        assertEquals("0", m.newValue) // manual mode

        // Idempotent while journaled
        assertTrue(task.isApplied(ctx))
        assertEquals(TaskStatus.NoChange, task.apply(ctx).status)

        // Restore brings back the user's original brightness + auto mode
        assertTrue(Journal.restore(b, ex))
        assertTrue(Journal.restore(m, ex))
        assertEquals("128", ex.sysSettingGet("screen_brightness"))
        assertEquals("1", ex.sysSettingGet("screen_brightness_mode"))
    }

    @Test
    fun `skipped when brightness is not writable at all`() {
        val ex = FakeExecutor()
        ex.privileged = true
        ex.failSys = true // simulates missing WRITE_SETTINGS + no Shizuku
        val j = journal()
        val task = PeakBrightnessTask()
        val ctx = BoostContext(testProfile(Module.DISPLAY), ex, j)

        val r = task.apply(ctx)
        assertEquals(TaskStatus.Skipped, r.status)
        assertTrue(r.detail.contains("brightness not writable"))
        assertTrue(j.isEmpty())
    }
}

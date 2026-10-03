package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.DisplayTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DisplayTaskTest {
    private fun journal(): Journal {
        val file = File.createTempFile("nitro_display", ".json")
        file.deleteOnExit()
        return Journal(file)
    }

    @Test
    fun `zero refresh rate preserves the adaptive policy`() {
        val executor = FakeExecutor().apply {
            sys[DisplayTask.KEY_MIN_REFRESH_RATE] = "60.0"
            sys[DisplayTask.KEY_PEAK_REFRESH_RATE] = "120.0"
        }
        val task = DisplayTask()
        val ctx = BoostContext(testProfile(Module.DISPLAY), executor, journal())

        assertEquals(TaskStatus.NoChange, task.apply(ctx).status)
        assertEquals("60.0", executor.sysSettingGet(DisplayTask.KEY_MIN_REFRESH_RATE))
        assertFalse(task.isApplied(ctx))
    }

    @Test
    fun `explicit supported rate journals the exact old float and restores it`() {
        val executor = FakeExecutor().apply {
            sys[DisplayTask.KEY_MIN_REFRESH_RATE] = "60.0"
            sys[DisplayTask.KEY_PEAK_REFRESH_RATE] = "144.0"
        }
        val task = DisplayTask()
        val journal = journal()
        val profile = testProfile(Module.DISPLAY).apply { refreshRate = 120 }
        val ctx = BoostContext(profile, executor, journal)

        val result = task.apply(ctx)
        assertEquals(TaskStatus.Applied, result.status)
        assertEquals("120", executor.sysSettingGet(DisplayTask.KEY_MIN_REFRESH_RATE))
        journal.add(result.entries)
        val entry = journal.entries.single()
        assertEquals("60.0", entry.oldValue)
        assertTrue(Journal.restore(entry, executor))
        assertEquals("60.0", executor.sysSettingGet(DisplayTask.KEY_MIN_REFRESH_RATE))
    }

    @Test
    fun `restores an unset refresh setting by deleting the temporary override`() {
        val executor = FakeExecutor().apply {
            sys[DisplayTask.KEY_PEAK_REFRESH_RATE] = "144.0"
        }
        val task = DisplayTask()
        val journal = journal()
        val profile = testProfile(Module.DISPLAY).apply { refreshRate = 120 }
        val result = task.apply(BoostContext(profile, executor, journal))

        assertEquals(TaskStatus.Applied, result.status)
        assertEquals("120", executor.sysSettingGet(DisplayTask.KEY_MIN_REFRESH_RATE))
        journal.add(result.entries)
        assertTrue(Journal.restore(journal.entries.single(), executor))
        assertFalse(executor.sys.containsKey(DisplayTask.KEY_MIN_REFRESH_RATE))
    }

    @Test
    fun `rate above reported panel peak is rejected`() {
        val executor = FakeExecutor().apply {
            sys[DisplayTask.KEY_MIN_REFRESH_RATE] = "60.0"
            sys[DisplayTask.KEY_PEAK_REFRESH_RATE] = "144.0"
        }
        val task = DisplayTask()
        val profile = testProfile(Module.DISPLAY).apply { refreshRate = 240 }
        val result = task.apply(BoostContext(profile, executor, journal()))

        assertEquals(TaskStatus.Skipped, result.status)
        assertTrue(result.detail.contains("exceeds panel peak"))
        assertEquals("60.0", executor.sysSettingGet(DisplayTask.KEY_MIN_REFRESH_RATE))
    }

    @Test
    fun `density reset restores absence of an override not physical density`() {
        val executor = FakeExecutor().apply { physicalDensity = 420 }
        val task = DisplayTask()
        val journal = journal()
        val profile = testProfile(Module.DISPLAY).apply { dpi = 480 }
        val result = task.apply(BoostContext(profile, executor, journal))
        assertEquals(TaskStatus.Applied, result.status)
        assertEquals(480, executor.densityOverride)
        journal.add(result.entries)

        val entry = journal.entries.single()
        assertNull(entry.oldValue)
        assertEquals("wm density reset", entry.revertCmd)
        assertTrue(Journal.restore(entry, executor))
        assertNull(executor.densityOverride)
        assertEquals(420, executor.physicalDensity)
    }

    @Test
    fun `invalid profile values and missing privilege are skipped`() {
        val task = DisplayTask()
        val invalid = testProfile(Module.DISPLAY).apply { dpi = 1 }
        assertEquals(
            TaskStatus.Skipped,
            task.apply(BoostContext(invalid, FakeExecutor(), journal())).status
        )
        val unprivileged = FakeExecutor().apply { privileged = false }
        assertEquals(
            TaskStatus.Skipped,
            task.apply(BoostContext(testProfile(Module.DISPLAY), unprivileged, journal())).status
        )
    }
}

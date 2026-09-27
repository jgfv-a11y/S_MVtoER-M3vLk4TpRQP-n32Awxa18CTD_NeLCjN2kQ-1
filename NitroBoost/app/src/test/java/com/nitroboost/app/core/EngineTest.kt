package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.AllTasks
import com.nitroboost.app.core.tasks.DndTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineTest {

    private fun tempJournal(): Journal {
        val f = File.createTempFile("nitro_test", ".json")
        f.deleteOnExit()
        return Journal(f)
    }

    @Test
    fun `boost applies dnd and journals it`() {
        val ex = FakeExecutor()
        val j = tempJournal()
        val profile = testProfile(Module.DND)
        val ctx = BoostContext(profile, ex, j)
        val engine = BoostEngine(listOf(DndTask()))

        val report = engine.boost(ctx)

        assertEquals(1, report.appliedCount)
        assertEquals(0, report.failedCount)
        assertEquals(DndFilters.PRIORITY, ex.dndFilter)
        assertEquals(1, j.entries.size)
        assertEquals("dnd", j.entries[0].taskId)
        assertEquals(DndFilters.ALL.toString(), j.entries[0].oldValue)
    }

    @Test
    fun `boost is idempotent - second run is NoChange with no new entries`() {
        val ex = FakeExecutor()
        val j = tempJournal()
        val profile = testProfile(Module.DND)
        val engine = BoostEngine(listOf(DndTask()))

        engine.boost(BoostContext(profile, ex, j))
        val before = j.entries.size
        val report = engine.boost(BoostContext(profile, ex, j))

        assertEquals(0, report.appliedCount)
        assertEquals(1, report.noChangeCount)
        assertEquals(before, j.entries.size)
    }

    @Test
    fun `restoreAll reverts dnd to original filter`() {
        val ex = FakeExecutor()
        ex.dndFilter = DndFilters.ALARMS
        val j = tempJournal()
        val profile = testProfile(Module.DND)
        val engine = BoostEngine(listOf(DndTask()))

        engine.boost(BoostContext(profile, ex, j))
        assertEquals(DndFilters.PRIORITY, ex.dndFilter)

        engine.restoreAll(BoostContext(profile, ex, j))

        assertEquals(DndFilters.ALARMS, ex.dndFilter)
        assertTrue(j.isEmpty())
    }

    @Test
    fun `disabled modules are skipped`() {
        val ex = FakeExecutor()
        val j = tempJournal()
        val profile = testProfile(Module.DND, dnd = false)
        val engine = BoostEngine(listOf(DndTask()))

        val report = engine.boost(BoostContext(profile, ex, j))

        assertEquals(1, report.skippedCount)
        assertEquals(0, ex.written.size)
        assertTrue(j.isEmpty())
    }

    @Test
    fun `failing task does not abort the rest and is not journaled`() {
        val ex = FakeExecutor()
        ex.failDnd = true
        val j = tempJournal()
        val profile = testProfile(Module.DND)
        val engine = BoostEngine(listOf(DndTask()))

        val report = engine.boost(BoostContext(profile, ex, j))

        assertEquals(1, report.failedCount)
        assertEquals(0, j.entries.size)
    }

    @Test
    fun `journal survives reload and restore still works after app restart`() {
        val ex = FakeExecutor()
        ex.dndFilter = DndFilters.NONE
        val f = File.createTempFile("nitro_test", ".json")
        f.deleteOnExit()
        val profile = testProfile(Module.DND)
        val engine = BoostEngine(listOf(DndTask()))

        engine.boost(BoostContext(profile, ex, Journal(f)))
        assertEquals(DndFilters.PRIORITY, ex.dndFilter)

        // Simulate process death: fresh Journal over the same file
        val reloaded = Journal(f)
        engine.restoreAll(BoostContext(profile, ex, reloaded))

        assertEquals(DndFilters.NONE, ex.dndFilter)
        assertTrue(reloaded.isEmpty())
    }

    @Test
    fun `thermal deescalate drops only the requested modules`() {
        val ex = FakeExecutor()
        ex.dndFilter = DndFilters.ALARMS
        // fake a governor file so cpu task can apply
        val govPath = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"
        ex.sysfs[govPath] = "schedutil"
        val j = tempJournal()
        val profile = testProfile(Module.CPU, Module.DND)
        val engine = BoostEngine(listOf(DndTask(), com.nitroboost.app.core.tasks.GovernorTask()))

        engine.boost(BoostContext(profile, ex, j))
        assertEquals(2, j.entries.size)

        // severe heat: drop GPU+THERMAL (none applied here) then critical: drop CPU
        val critical = ThermalGuard.modulesToDrop(ThermalGuard.STATUS_CRITICAL)
        assertTrue(critical.contains(Module.CPU))
        val dropped = engine.deescalate(BoostContext(profile, ex, j), critical)

        assertEquals(1, dropped)
        assertEquals("schedutil", ex.readSys(govPath))
        // DND must survive de-escalation
        assertEquals(DndFilters.PRIORITY, ex.dndFilter)
        assertEquals(1, j.entries.size)
    }

    @Test
    fun `full roster never crashes on an empty device`() {
        val ex = FakeExecutor()
        ex.privileged = true
        val j = tempJournal()
        val profile = testProfile()
        val ctx = BoostContext(profile, ex, j)

        val report = BoostEngine(AllTasks.tasks).boost(ctx)

        // Whatever the fake device supports, the engine must finish cleanly.
        assertTrue(report.results.isNotEmpty())
    }

    @Test
    fun `states reflect applied journal entries`() {
        val ex = FakeExecutor()
        val j = tempJournal()
        val profile = testProfile(Module.DND)
        val engine = BoostEngine(listOf(DndTask()))
        engine.boost(BoostContext(profile, ex, j))

        val states = engine.states(BoostContext(profile, ex, j))
        assertEquals(1, states.size)
        assertTrue(states[0].applied)
    }
}

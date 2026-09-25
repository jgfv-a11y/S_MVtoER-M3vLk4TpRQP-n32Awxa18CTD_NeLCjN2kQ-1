package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.AllTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.5: the boost-level valve. Level 1 = basics (no privileges),
 * 2 = standard, 3 = aggressive. The engine must skip any task whose
 * required level exceeds the user selection, and never journal it.
 */
class BoostLevelTest {

    private fun tempJournal(): Journal {
        val f = File.createTempFile("nitro_level", ".json")
        f.deleteOnExit()
        return Journal(f)
    }

    /** Minimal task with a configurable level. */
    private class StubTask(
        override val id: String,
        override val boostLevel: Int
    ) : BoostTask {
        override val titleAr = id
        override val titleEn = id
        override val descAr = ""
        override val descEn = ""
        override val module = Module.CPU
        override val requiresPrivilege = false
        override fun isSupported(ctx: BoostContext): Boolean = true
        override fun isApplied(ctx: BoostContext): Boolean = false
        override fun apply(ctx: BoostContext): TaskResult =
            TaskResult(id, TaskStatus.Applied, "stub level $boostLevel")
    }

    @Test
    fun `roster level map is as designed`() {
        val t = AllTasks.byId
        // Level 1 — safe basics, no privilege
        assertEquals(1, t["dnd"]!!.boostLevel)
        assertEquals(1, t["animations"]!!.boostLevel)
        assertEquals(1, t["game_mode"]!!.boostLevel)
        assertEquals(1, t["data_saver"]!!.boostLevel)
        assertEquals(1, t["peak_brightness"]!!.boostLevel)
        // Level 3 — aggressive, opt-in only
        assertEquals(3, t["walt_tuning"]!!.boostLevel)
        assertEquals(3, t["touch_boost"]!!.boostLevel)
        assertEquals(3, t["ram_trim"]!!.boostLevel)
        assertEquals(3, t["ram_kill"]!!.boostLevel)
        assertEquals(3, t["thermal_override"]!!.boostLevel)
        // Level 2 — the standard default
        assertEquals(2, t["cpu_online"]!!.boostLevel)
        assertEquals(2, t["io_scheduler"]!!.boostLevel)
        assertEquals(2, t["device_idle"]!!.boostLevel)
        assertEquals(2, t["game_perf_mode"]!!.boostLevel)
        assertEquals(2, t["cpu_governor"]!!.boostLevel)
    }

    @Test
    fun `level 2 skips level-3 tasks and never journals them`() {
        val engine = BoostEngine(
            listOf(StubTask("stub1", 1), StubTask("stub2", 2), StubTask("stub3", 3))
        )
        val ex = FakeExecutor()
        val j = tempJournal()
        val report = engine.boost(BoostContext(testProfile(Module.CPU), ex, j), maxLevel = 2)

        assertTrue(report.results["stub1"]!!.status.success)
        assertTrue(report.results["stub2"]!!.status.success)
        assertEquals(TaskStatus.Skipped, report.results["stub3"]!!.status)
        assertEquals("boost level too low (needs 3)", report.results["stub3"]!!.detail)
        assertTrue(j.isEmpty())
        assertEquals(0, ex.written.size)
    }

    @Test
    fun `level 3 applies everything while level 1 only the basics`() {
        val tasks = listOf(StubTask("s1", 1), StubTask("s2", 2), StubTask("s3", 3))

        val j3 = tempJournal()
        val r3 = BoostEngine(tasks).boost(
            BoostContext(testProfile(Module.CPU), FakeExecutor(), j3), maxLevel = 3
        )
        assertEquals(3, r3.appliedCount)
        assertEquals(0, r3.skippedCount)

        val j1 = tempJournal()
        val r1 = BoostEngine(tasks).boost(
            BoostContext(testProfile(Module.CPU), FakeExecutor(), j1), maxLevel = 1
        )
        assertEquals(1, r1.appliedCount)
        assertEquals(2, r1.skippedCount)
        assertTrue(j1.isEmpty())
    }

    @Test
    fun `maxLevel defaults to 3 for backward compatibility`() {
        val j = tempJournal()
        val report = BoostEngine(listOf(StubTask("s3", 3)))
            .boost(BoostContext(testProfile(Module.CPU), FakeExecutor(), j))
        assertTrue(report.results["s3"]!!.status.success)
    }
}

package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.GpuTask
import com.nitroboost.app.core.tasks.TouchBoostTask
import com.nitroboost.app.core.tasks.WaltSchedulerTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GpuWaltTouchTasksTest {

    private fun journal(): Journal {
        val f = File.createTempFile("nitro_tasks", ".json")
        f.delete()
        f.deleteOnExit()
        return Journal(f)
    }

    @Test
    fun `gpu task boosts only readable nodes and journals them`() {
        val ex = FakeExecutor()
        ex.sysfs["/sys/class/kgsl/kgsl-3d0/force_clk_on"] = "0"
        val ctx = BoostContext(testProfile(Module.GPU), ex, journal())
        val task = GpuTask()

        assertTrue(task.isSupported(ctx))
        assertFalse(task.isApplied(ctx))

        val result = task.apply(ctx)

        assertEquals(TaskStatus.Applied, result.status)
        assertEquals("1", ex.readSys("/sys/class/kgsl/kgsl-3d0/force_clk_on"))
        assertEquals(1, result.entries.size)
        assertEquals("0", result.entries[0].oldValue)
        assertTrue(task.isApplied(ctx))
        assertEquals(TaskStatus.NoChange, task.apply(ctx).status)
    }

    @Test
    fun `gpu task is skipped when no gpu nodes exist`() {
        val ex = FakeExecutor()
        val ctx = BoostContext(testProfile(Module.GPU), ex, journal())

        val result = GpuTask().apply(ctx)

        assertEquals(TaskStatus.Skipped, result.status)
        assertTrue(ex.written.isEmpty())
    }

    @Test
    fun `walt task tunes readable sysctls only`() {
        val ex = FakeExecutor()
        ex.sysfs["/proc/sys/kernel/sched_latency_ns"] = "6000000"
        ex.sysfs["/proc/sys/kernel/sched_child_runs_first"] = "0"
        val ctx = BoostContext(testProfile(Module.TWEAKS), ex, journal())
        val task = WaltSchedulerTask()

        val result = task.apply(ctx)

        assertEquals(TaskStatus.Applied, result.status)
        assertEquals("1000000", ex.readSys("/proc/sys/kernel/sched_latency_ns"))
        assertEquals("1", ex.readSys("/proc/sys/kernel/sched_child_runs_first"))
        assertEquals(2, result.entries.size)
        assertEquals(TaskStatus.NoChange, task.apply(ctx).status)
    }

    @Test
    fun `touch boost raises a low input boost and leaves a high one alone`() {
        val ex = FakeExecutor()
        ex.sysfs["/sys/module/cpu_boost/parameters/input_boost_ms"] = "40"
        val ctx = BoostContext(testProfile(Module.TWEAKS), ex, journal())
        val task = TouchBoostTask()

        val result = task.apply(ctx)

        assertEquals(TaskStatus.Applied, result.status)
        assertEquals("200", ex.readSys("/sys/module/cpu_boost/parameters/input_boost_ms"))
        assertEquals("40", result.entries.single().oldValue)
        assertEquals(TaskStatus.NoChange, task.apply(ctx).status)
    }

    @Test
    fun `touch boost is skipped without a cpu_boost interface`() {
        val ex = FakeExecutor()
        val ctx = BoostContext(testProfile(Module.TWEAKS), ex, journal())

        assertEquals(TaskStatus.Skipped, TouchBoostTask().apply(ctx).status)
    }
}

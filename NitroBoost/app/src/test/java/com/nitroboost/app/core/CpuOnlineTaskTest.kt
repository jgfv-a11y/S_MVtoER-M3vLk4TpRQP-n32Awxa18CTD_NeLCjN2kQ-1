package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.CpuOnlineTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CpuOnlineTaskTest {

    private fun journal(): Journal {
        val f = File.createTempFile("nitro_cpu", ".json")
        f.deleteOnExit()
        return Journal(f)
    }

    private fun executorWithCores(vararg states: Pair<Int, Int>): FakeExecutor {
        val ex = FakeExecutor()
        ex.privileged = true
        for ((n, online) in states) {
            ex.sysfs["/sys/devices/system/cpu/cpu$n/online"] = online.toString()
        }
        return ex
    }

    @Test
    fun `brings offline cores online and journals each`() {
        val ex = executorWithCores(0 to 1, 1 to 0, 2 to 0)
        val j = journal()
        val task = CpuOnlineTask()
        val ctx = BoostContext(testProfile(Module.CPU), ex, j)

        assertTrue(task.isSupported(ctx))
        assertFalse(task.isApplied(ctx))

        val r = task.apply(ctx)
        assertEquals(TaskStatus.Applied, r.status)
        assertEquals(2, r.entries.size)
        j.add(r.entries)

        // All online now -> idempotent
        assertTrue(task.isApplied(ctx))
        assertEquals(TaskStatus.NoChange, task.apply(ctx).status)

        // Restore re-parks the cores
        for (e in r.entries) assertTrue(Journal.restore(e, ex))
        assertEquals("0", ex.readSys("/sys/devices/system/cpu/cpu1/online"))
        assertEquals("0", ex.readSys("/sys/devices/system/cpu/cpu2/online"))
    }

    @Test
    fun `already-online device is a clean NoChange`() {
        val ex = executorWithCores(0 to 1, 1 to 1)
        val j = journal()
        val task = CpuOnlineTask()
        val ctx = BoostContext(testProfile(Module.CPU), ex, j)

        assertTrue(task.isApplied(ctx))
        assertEquals(TaskStatus.NoChange, task.apply(ctx).status)
        assertTrue(j.isEmpty())
    }

    @Test
    fun `skipped on kernels without a hotplug interface`() {
        val ex = FakeExecutor()
        ex.privileged = true
        // No /sys/.../cpuN/online nodes at all
        val j = journal()
        val task = CpuOnlineTask()
        val ctx = BoostContext(testProfile(Module.CPU), ex, j)

        assertFalse(task.isSupported(ctx))
        assertEquals(TaskStatus.Skipped, task.apply(ctx).status)
        assertTrue(j.isEmpty())
    }

    @Test
    fun `skipped without privilege`() {
        val ex = executorWithCores(0 to 0)
        ex.privileged = false
        val j = journal()
        val task = CpuOnlineTask()
        val ctx = BoostContext(testProfile(Module.CPU), ex, j)

        assertFalse(task.isSupported(ctx))
        assertEquals(TaskStatus.Skipped, task.apply(ctx).status)
    }
}

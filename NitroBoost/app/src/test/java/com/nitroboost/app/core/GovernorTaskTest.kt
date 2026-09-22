package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.GovernorTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.3.2: GovernorTask became a parameterized variant so the adaptive engine
 * can A/B test "performance" vs "schedutil" the same way it sweeps
 * downscale levels.
 */
class GovernorTaskTest {

    private val govPath = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"

    private fun journal(): Journal {
        val f = File.createTempFile("nitro_gov", ".json")
        f.deleteOnExit()
        return Journal(f)
    }

    @Test
    fun `default variant is performance and journals the write`() {
        val ex = FakeExecutor()
        ex.sysfs[govPath] = "schedutil"
        val j = journal()
        val task = GovernorTask()
        val ctx = BoostContext(testProfile(Module.CPU), ex, j)

        assertTrue(task.isSupported(ctx))
        val r = task.apply(ctx)
        assertEquals(TaskStatus.Applied, r.status)
        j.add(r.entries)
        assertTrue(ex.written.any { "sysfs:$govPath" == it })
        assertEquals("performance", ex.sysfs[govPath])
        assertTrue(task.isApplied(ctx))
        assertTrue(task.titleEn.contains("performance"))
    }

    @Test
    fun `schedutil variant targets schedutil with its own identity`() {
        val ex = FakeExecutor()
        ex.sysfs[govPath] = "performance"
        val j = journal()
        val task = GovernorTask(governor = "schedutil")
        val ctx = BoostContext(testProfile(Module.CPU), ex, j)

        assertTrue(task.titleEn.contains("schedutil"))
        val r = task.apply(ctx)
        assertEquals(TaskStatus.Applied, r.status)
        j.add(r.entries)
        assertEquals("schedutil", ex.sysfs[govPath])
        assertTrue(task.isApplied(ctx))

        // The default task sees a schedutil system as NOT applied (its target
        // is performance) — the two variants really are different candidates.
        assertEquals(false, GovernorTask().isApplied(ctx))
    }

    @Test
    fun `read-only rom write is skipped not failed`() {
        val ex = FakeExecutor()
        ex.sysfs[govPath] = "schedutil"
        // ROM that exposes the node but the kernel refuses echo-writes
        // (read-only cpufreq on some vendor kernels).
        val ro = object : SystemExecutor by ex {
            override fun shell(cmd: String): ShellResult =
                if (cmd.startsWith("echo")) ShellResult(false, 1, "", "read-only file system")
                else ex.shell(cmd)
        }
        val j = journal()
        val task = GovernorTask()
        val ctx = BoostContext(testProfile(Module.CPU), ro, j)

        val r = task.apply(ctx)
        assertEquals(TaskStatus.Skipped, r.status)
        assertTrue(j.isEmpty())
    }

    @Test
    fun `second apply is a no-op after success`() {
        val ex = FakeExecutor()
        ex.sysfs[govPath] = "schedutil"
        val j = journal()
        val task = GovernorTask(governor = "schedutil")
        val ctx = BoostContext(testProfile(Module.CPU), ex, j)

        assertEquals(TaskStatus.Applied, task.apply(ctx).status)
        assertEquals(TaskStatus.NoChange, task.apply(ctx).status)
    }
}

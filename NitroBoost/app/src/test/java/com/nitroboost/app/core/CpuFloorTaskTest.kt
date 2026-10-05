package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.CpuFloorTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CpuFloorTaskTest {
    private val policyPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"

    private fun journal(): Journal {
        val file = File.createTempFile("nitro_cpu_floor", ".json")
        file.delete()
        file.deleteOnExit()
        return Journal(file)
    }

    private fun executor(output: String): Pair<FakeExecutor, SystemExecutor> {
        val fake = FakeExecutor().apply {
            privileged = true
            sysfs[policyPath] = "300"
        }
        val wrapped = object : SystemExecutor by fake {
            override fun shell(cmd: String): ShellResult =
                if (cmd.startsWith("for d in /sys/devices/system/cpu/cpufreq/policy*"))
                    ShellResult(true, 0, output, "")
                else fake.shell(cmd)
        }
        return fake to wrapped
    }

    @Test
    fun `writes bounded floor through validated sysfs path and journals restore`() {
        val (fake, executor) = executor("/sys/devices/system/cpu/cpufreq/policy0 1000 300")
        val task = CpuFloorTask()
        val journal = journal()
        val result = task.apply(BoostContext(testProfile(Module.CPU), executor, journal))

        assertEquals(TaskStatus.Applied, result.status)
        assertEquals("650", fake.readSys(policyPath))
        assertEquals(policyPath, result.entries.single().key)
        assertTrue(Journal.restore(result.entries.single(), executor))
        assertEquals("300", fake.readSys(policyPath))
    }

    @Test
    fun `rejects shell-like policy path from device output`() {
        val (fake, executor) = executor("/sys/devices/system/cpu/cpufreq/policy0;reboot 1000 300")
        val result = CpuFloorTask().apply(BoostContext(testProfile(Module.CPU), executor, journal()))

        assertEquals(TaskStatus.Skipped, result.status)
        assertTrue(fake.written.isEmpty())
    }
}

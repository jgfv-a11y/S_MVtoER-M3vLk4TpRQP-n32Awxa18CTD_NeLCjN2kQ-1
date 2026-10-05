package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.RamKillTask
import com.nitroboost.app.core.tasks.RamTrimTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RamSafetyTest {
    private fun journal(): Journal {
        val file = File.createTempFile("nitro_ram", ".json")
        file.delete()
        file.deleteOnExit()
        return Journal(file)
    }

    @Test
    fun `force stop filters invalid packages and never kills selected game`() {
        val executor = FakeExecutor()
        val task = RamKillTask().apply {
            killableProvider = {
                listOf("com.test.game", "com.safe.app", "com.bad;reboot", "com.safe.app")
            }
        }
        val result = task.apply(BoostContext(testProfile(Module.RAM), executor, journal()))

        assertEquals(TaskStatus.Applied, result.status)
        assertEquals(listOf("am force-stop \"com.safe.app\""), executor.shellLog)
    }

    @Test
    fun `RAM cleanup tasks do not invoke privileged commands without access`() {
        val executor = FakeExecutor().apply { privileged = false }
        val killer = RamKillTask().apply { killableProvider = { listOf("com.safe.app") } }
        val ctx = BoostContext(testProfile(Module.RAM), executor, journal())

        assertEquals(TaskStatus.Skipped, killer.apply(ctx).status)
        assertEquals(TaskStatus.Skipped, RamTrimTask().apply(ctx).status)
        assertTrue(executor.shellLog.isEmpty())
    }
}

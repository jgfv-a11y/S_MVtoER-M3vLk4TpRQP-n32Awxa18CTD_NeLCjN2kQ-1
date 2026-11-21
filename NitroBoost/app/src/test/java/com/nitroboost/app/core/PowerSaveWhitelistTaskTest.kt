package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.PowerSaveWhitelistTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PowerSaveWhitelistTaskTest {

    private fun journal(): Journal {
        val f = File.createTempFile("nitro_doze", ".json")
        f.delete()
        f.deleteOnExit()
        return Journal(f)
    }

    @Test
    fun `apply whitelists the game and the journal entry removes it again`() {
        val ex = FakeExecutor()
        val j = journal()
        val ctx = BoostContext(testProfile(Module.POWER), ex, j)
        val task = PowerSaveWhitelistTask()

        assertFalse(task.isApplied(ctx))

        val result = task.apply(ctx)

        assertEquals(TaskStatus.Applied, result.status)
        assertTrue(ex.dozeWhitelist.contains("com.test.game"))
        assertTrue(task.isApplied(ctx))
        assertEquals(TaskStatus.NoChange, task.apply(ctx).status)

        val entry = result.entries.single()
        assertTrue(Journal.restore(entry, ex))
        assertFalse(ex.dozeWhitelist.contains("com.test.game"))
    }

    @Test
    fun `similar package names do not count as whitelisted`() {
        val ex = FakeExecutor()
        ex.dozeWhitelist.add("com.test.gamex")
        val ctx = BoostContext(testProfile(Module.POWER), ex, journal())

        assertFalse(PowerSaveWhitelistTask().isApplied(ctx))
    }
}

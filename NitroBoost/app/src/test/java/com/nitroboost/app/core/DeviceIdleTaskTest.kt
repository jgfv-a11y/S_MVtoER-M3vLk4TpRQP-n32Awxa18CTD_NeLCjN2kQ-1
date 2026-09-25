package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.DeviceIdleTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeviceIdleTaskTest {

    private fun journal(): Journal {
        val f = File.createTempFile("nitro_doze", ".json")
        f.deleteOnExit()
        return Journal(f)
    }

    @Test
    fun `whitelists the game and journals the remove command`() {
        val ex = FakeExecutor()
        ex.privileged = true
        val j = journal()
        val task = DeviceIdleTask()
        val ctx = BoostContext(testProfile(Module.POWER), ex, j)

        assertTrue(task.isSupported(ctx))
        assertFalse(task.isApplied(ctx))

        val r = task.apply(ctx)
        assertEquals(TaskStatus.Applied, r.status)
        j.add(r.entries)

        val e = j.entries.first()
        assertEquals(JournalEntry.Kind.CMD, e.kind)
        assertEquals("device_idle:com.test.game", e.key)
        assertEquals("cmd deviceidle whitelist-remove com.test.game 2>/dev/null", e.revertCmd)
        assertTrue(ex.shellLog.any { it == "cmd deviceidle whitelist com.test.game 2>&1" })

        // Idempotent: already journaled
        assertTrue(task.isApplied(ctx))
        assertEquals(TaskStatus.NoChange, task.apply(ctx).status)

        // Restore runs the whitelist-remove command
        assertTrue(Journal.restore(e, ex))
        assertTrue(ex.written.any { it.startsWith("deviceidle:rm:") })
    }

    @Test
    fun `skipped without privilege`() {
        val ex = FakeExecutor()
        ex.privileged = false
        val j = journal()
        val task = DeviceIdleTask()
        val ctx = BoostContext(testProfile(Module.POWER), ex, j)

        assertFalse(task.isSupported(ctx))
        assertEquals(TaskStatus.Skipped, task.apply(ctx).status)
        assertTrue(j.isEmpty())
    }

    @Test
    fun `skipped when the profile has no package`() {
        val ex = FakeExecutor()
        ex.privileged = true
        val j = journal()
        val task = DeviceIdleTask()
        val profile = com.nitroboost.app.core.AppProfile(
            packageName = "",
            name = "No Pkg",
            enabledModules = mutableSetOf(Module.POWER)
        )
        val ctx = BoostContext(profile, ex, j)

        assertFalse(task.isSupported(ctx))
        val r = task.apply(ctx)
        assertEquals(TaskStatus.Skipped, r.status)
    }
}

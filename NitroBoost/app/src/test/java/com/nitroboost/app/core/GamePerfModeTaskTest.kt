package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.GamePerfModeTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GamePerfModeTaskTest {

    private fun journal(): Journal {
        val f = File.createTempFile("nitro_mode", ".json")
        f.deleteOnExit()
        return Journal(f)
    }

    @Test
    fun `sets performance mode on android 12+ and journals balanced revert`() {
        val ex = FakeExecutor()
        ex.privileged = true
        val j = journal()
        val task = GamePerfModeTask(sdk = 34)
        val ctx = BoostContext(testProfile(Module.GPU), ex, j)

        assertTrue(task.isSupported(ctx))
        assertFalse(task.isApplied(ctx))

        val r = task.apply(ctx)
        assertEquals(TaskStatus.Applied, r.status)
        j.add(r.entries)

        val e = j.entries.first()
        assertEquals(JournalEntry.Kind.CMD, e.kind)
        assertEquals("game_mode_api:com.test.game", e.key)
        // AOSP shell mapping: 1 = standard, 2 = performance, 3 = battery.
        assertEquals("2", e.newValue)
        // Revert goes back to standard (mode 1) — NOT `cmd game reset`,
        // which would also wipe the downscale override.
        assertEquals("cmd game set --mode 1 com.test.game 2>/dev/null", e.revertCmd)
        assertTrue(ex.shellLog.any { it == "cmd game set --mode 2 com.test.game 2>&1" })

        // Idempotent: already journaled
        assertTrue(task.isApplied(ctx))
        assertEquals(TaskStatus.NoChange, task.apply(ctx).status)

        // Restore runs the standard-mode command
        assertTrue(Journal.restore(e, ex))
        assertTrue(ex.written.any { it.startsWith("game-mode:cmd game set --mode 1") })
    }

    @Test
    fun `skipped on android 11 even with privilege`() {
        val ex = FakeExecutor()
        ex.privileged = true
        val j = journal()
        val task = GamePerfModeTask(sdk = 30)
        val ctx = BoostContext(testProfile(Module.GPU), ex, j)

        assertFalse(task.isSupported(ctx))
        assertEquals(TaskStatus.Skipped, task.apply(ctx).status)
        assertTrue(j.isEmpty())
    }

    @Test
    fun `skipped without privilege`() {
        val ex = FakeExecutor()
        ex.privileged = false
        val j = journal()
        val task = GamePerfModeTask(sdk = 34)
        val ctx = BoostContext(testProfile(Module.GPU), ex, j)

        assertFalse(task.isSupported(ctx))
        assertEquals(TaskStatus.Skipped, task.apply(ctx).status)
    }
}

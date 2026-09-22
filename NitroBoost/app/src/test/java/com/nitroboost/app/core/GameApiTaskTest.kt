package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.GameApiTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GameApiTaskTest {

    private fun journal(): Journal {
        val f = File.createTempFile("nitro_gameapi", ".json")
        f.deleteOnExit()
        return Journal(f)
    }

    @Test
    fun `apply on android 12+ journals downscale with reset revert`() {
        val ex = FakeExecutor()
        ex.privileged = true
        val j = journal()
        val profile = testProfile(Module.GPU)
        val task = GameApiTask(sdk = 34)
        val ctx = BoostContext(profile, ex, j)

        assertTrue(task.isSupported(ctx))
        assertFalse(task.isApplied(ctx))

        val r = task.apply(ctx)
        assertEquals(TaskStatus.Applied, r.status)
        // The engine persists task results to the journal; mirror that here.
        j.add(r.entries)
        val entry = j.entries.first { it.taskId == task.id }
        assertEquals("game_api:com.test.game", entry.key)
        assertEquals("cmd game reset com.test.game 2>/dev/null", entry.revertCmd)
        assertTrue(task.isApplied(ctx))

        // Second run is a no-op (already applied)
        assertEquals(TaskStatus.NoChange, task.apply(ctx).status)

        // restoreAll path: the revert command is executed through the journal
        val restored = Journal.restore(entry, ex)
        assertTrue(restored)
        assertTrue(ex.written.any { it.startsWith("game-api-reset:") })
    }

    @Test
    fun `skipped on android 11 even with privilege`() {
        val ex = FakeExecutor()
        ex.privileged = true
        val j = journal()
        val task = GameApiTask(sdk = 30)
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
        val task = GameApiTask(sdk = 34)
        val ctx = BoostContext(testProfile(Module.GPU), ex, j)

        assertFalse(task.isSupported(ctx))
        assertEquals(TaskStatus.Skipped, task.apply(ctx).status)
    }
}

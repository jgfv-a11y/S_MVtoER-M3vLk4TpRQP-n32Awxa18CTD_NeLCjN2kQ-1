package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.IoSchedulerTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IoSchedulerTaskTest {

    private fun journal(): Journal {
        val f = File.createTempFile("nitro_io", ".json")
        f.deleteOnExit()
        return Journal(f)
    }

    @Test
    fun `switches mmcblk0 to none and journals the previous scheduler`() {
        val ex = FakeExecutor()
        ex.privileged = true
        ex.sysfs["/sys/block/mmcblk0/queue/scheduler"] = "[mq-deadline] kyber none"
        val j = journal()
        val task = IoSchedulerTask()
        val ctx = BoostContext(testProfile(Module.TWEAKS), ex, j)

        assertTrue(task.isSupported(ctx))
        assertFalse(task.isApplied(ctx))

        val r = task.apply(ctx)
        assertEquals(TaskStatus.Applied, r.status)
        val e = r.entries[0]
        assertEquals(JournalEntry.Kind.SYSFS, e.kind)
        assertEquals("/sys/block/mmcblk0/queue/scheduler", e.key)
        assertEquals("mq-deadline", e.oldValue)
        assertEquals("none", e.newValue)
        j.add(r.entries)

        assertTrue(task.isApplied(ctx))
        assertEquals(TaskStatus.NoChange, task.apply(ctx).status)

        // Restore brings the previous scheduler back
        assertTrue(Journal.restore(e, ex))
        assertEquals("mq-deadline", ex.readSys("/sys/block/mmcblk0/queue/scheduler"))
    }

    @Test
    fun `single-option scheduler node is not meaningful - skipped`() {
        val ex = FakeExecutor()
        ex.privileged = true
        ex.sysfs["/sys/block/mmcblk0/queue/scheduler"] = "[none]"
        val j = journal()
        val task = IoSchedulerTask()
        val ctx = BoostContext(testProfile(Module.TWEAKS), ex, j)

        assertFalse(task.isSupported(ctx))
        assertEquals(TaskStatus.Skipped, task.apply(ctx).status)
    }

    @Test
    fun `skipped when no block device is visible`() {
        val ex = FakeExecutor()
        ex.privileged = true
        val j = journal()
        val task = IoSchedulerTask()
        val ctx = BoostContext(testProfile(Module.TWEAKS), ex, j)

        assertFalse(task.isSupported(ctx))
        assertEquals(TaskStatus.Skipped, task.apply(ctx).status)
        assertTrue(j.isEmpty())
    }

    @Test
    fun `skipped without privilege`() {
        val ex = FakeExecutor()
        ex.privileged = false
        ex.sysfs["/sys/block/mmcblk0/queue/scheduler"] = "[mq-deadline] none"
        val j = journal()
        val task = IoSchedulerTask()
        val ctx = BoostContext(testProfile(Module.TWEAKS), ex, j)

        assertFalse(task.isSupported(ctx))
        assertEquals(TaskStatus.Skipped, task.apply(ctx).status)
    }
}

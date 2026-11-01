package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.IoSchedulerTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression coverage for the production journal path of io_scheduler.
 *
 * Bug being guarded against: JournaledSystemExecutor.writeSys records the raw
 * kernel display list ("[mq-deadline] kyber none") as the entry's oldValue.
 * That string can never be written back (ShellInput.isSysValue rejects spaces
 * and brackets), which made every io_scheduler change permanently unrestorable
 * and left restoreAll failing forever. The fix pre-seeds the journal with the
 * ACTIVE scheduler token; Journal.add keeps the first entry per
 * taskId+kind+key, so the accurate entry wins. These tests exercise the same
 * engine path production uses (applyWithJournal), not the raw executor the
 * older task-only tests used (which masked the bug).
 */
class IoSchedulerJournalPathTest {

    private fun journal(): Journal {
        val f = File.createTempFile("nitro_io_engine", ".json")
        f.delete()
        f.deleteOnExit()
        return Journal(f)
    }

    @Test
    fun `engine journaled apply records the active token and restores cleanly`() {
        val ex = FakeExecutor()
        ex.privileged = true
        ex.sysfs["/sys/block/sda/queue/scheduler"] = "[mq-deadline] kyber none"
        val j = journal()
        val task = IoSchedulerTask()
        val engine = BoostEngine(listOf(task))
        val ctx = BoostContext(testProfile(Module.TWEAKS), ex, j)

        val result = engine.applyWithJournal(task.id, ctx) { taskCtx -> task.apply(taskCtx) }
        assertEquals(TaskStatus.Applied, result.status)
        assertEquals("none", ex.readSys("/sys/block/sda/queue/scheduler"))

        // The durable journal must hold the ACTIVE token, never the raw list.
        val entry = j.snapshot().single { it.key == "/sys/block/sda/queue/scheduler" }
        assertEquals("mq-deadline", entry.oldValue)
        assertEquals("none", entry.newValue)

        // A whole-journal restore (session stop path) must fully succeed.
        val report = engine.restoreAll(ctx)
        assertEquals(0, report.failedCount)
        assertTrue("journal must be empty after restoreAll", j.isEmpty())
        assertEquals("mq-deadline", ex.readSys("/sys/block/sda/queue/scheduler")?.let {
            Regex("\\[([^\\]]+)\\]").find(it)?.groupValues?.get(1) ?: it.trim()
        })
    }

    @Test
    fun `failed write does not leave the pre-seeded entry behind`() {
        val ex = FakeExecutor()
        ex.privileged = true
        // Kernel offering more than one option but the write never sticks:
        // FakeExecutor.writeSys always succeeds, so simulate a locked node by
        // making the active token re-read as unchanged after the write.
        ex.sysfs["/sys/block/sda/queue/scheduler"] = "[bfq] none"
        val j = journal()
        val task = IoSchedulerTask()
        val ctx = BoostContext(testProfile(Module.TWEAKS), ex, j)

        val result = engine_writeThatFails(ex, j, task, ctx)
        assertEquals(TaskStatus.Skipped, result.status)
        assertTrue("no journal residue after a failed write", j.isEmpty())
    }

    /** Applies through the engine with an executor whose writes silently no-op. */
    private fun engine_writeThatFails(
        ex: FakeExecutor,
        j: Journal,
        task: IoSchedulerTask,
        ctx: BoostContext
    ): TaskResult {
        val locked = object : SystemExecutor by ex {
            override fun writeSys(path: String, value: String): Boolean = false
        }
        val lockedCtx = BoostContext(ctx.profile, locked, j)
        val engine = BoostEngine(listOf(task))
        return engine.applyWithJournal(task.id, lockedCtx) { taskCtx -> task.apply(taskCtx) }
    }
}

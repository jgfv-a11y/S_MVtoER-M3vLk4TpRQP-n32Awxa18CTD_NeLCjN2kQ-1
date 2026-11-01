package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CapabilityRegistryTest {
    private fun journal(): Journal {
        val file = File.createTempFile("nitro-capability", ".json")
        file.delete()
        file.deleteOnExit()
        return Journal(file)
    }

    private class ProbeTask(private val counter: () -> Unit) : BoostTask {
        override val id = "probe_task"
        override val titleAr = "probe"
        override val titleEn = "probe"
        override val descAr = ""
        override val descEn = ""
        override val module = Module.CPU
        override val requiresPrivilege = false
        override fun isSupported(ctx: BoostContext): Boolean {
            counter()
            return true
        }
        override fun isApplied(ctx: BoostContext): Boolean = false
        override fun apply(ctx: BoostContext): TaskResult =
            TaskResult(id, TaskStatus.NoChange)
    }

    @Test
    fun `permission is distinct from unsupported`() {
        val executor = FakeExecutor().apply { privileged = false }
        val task = com.nitroboost.app.core.tasks.AllTasks.byId["cpu_governor"]!!
        val status = CapabilityRegistry().status(
            task,
            BoostContext(testProfile(Module.CPU), executor, journal()),
            nowMs = 1_000L
        )
        assertEquals(CapabilityState.PERMISSION_REQUIRED, status.state)
    }

    @Test
    fun `probe result is cached only for the short observation window`() {
        var probes = 0
        val task = ProbeTask { probes++ }
        val registry = CapabilityRegistry(ttlMs = 2_000L)
        val ctx = BoostContext(testProfile(Module.CPU), FakeExecutor(), journal())

        registry.status(task, ctx, nowMs = 1_000L)
        registry.status(task, ctx, nowMs = 2_500L)
        assertEquals(1, probes)

        registry.status(task, ctx, nowMs = 3_100L)
        assertEquals(2, probes)
    }

    @Test
    fun `journal ownership is reported as applied`() {
        val journal = journal()
        assertTrue(journal.add(listOf(
            JournalEntry("probe_task", JournalEntry.Kind.SYS_SETTING, "demo", "0", "1")
        )))
        val status = CapabilityRegistry().status(
            ProbeTask {},
            BoostContext(testProfile(Module.CPU), FakeExecutor(), journal),
            nowMs = 2_000L
        )
        assertEquals(CapabilityState.APPLIED, status.state)
    }
}

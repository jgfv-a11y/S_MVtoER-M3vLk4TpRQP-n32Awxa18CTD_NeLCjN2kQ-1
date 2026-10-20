package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SystemRendererControllerTest {
    private fun newJournal(): Journal {
        val dir = kotlin.io.path.createTempDirectory("nitro_renderer_").toFile()
        dir.deleteOnExit()
        return Journal(File(dir, "renderer_journal.json"))
    }

    @Test
    fun `Vulkan change is write-ahead recorded and verified`() {
        val raw = FakeExecutor()
        val journal = newJournal()
        var observedWriteAhead = false
        val observed = object : SystemExecutor by raw {
            override fun systemPropertyPut(name: String, value: String): Boolean {
                observedWriteAhead = journal.file.exists() &&
                    journal.containsTaskKey(SystemRendererController.TASK_ID, name)
                return raw.systemPropertyPut(name, value)
            }
        }

        val result = SystemRendererController.setRenderer(
            observed, journal, SystemRendererController.Target.VULKAN,
            androidApi = 35, hasVulkanHardwareFeature = true
        )

        assertEquals(SystemRendererController.Code.APPLIED, result.code)
        assertEquals(RendererSwitchStateMachine.Phase.COMPLETE, result.phase)
        assertTrue(observedWriteAhead)
        assertEquals("skiavk", raw.systemProperties[SystemRendererController.PROPERTY])
        val entry = journal.snapshot().single()
        assertEquals(JournalEntry.Kind.SYSTEM_PROPERTY, entry.kind)
        assertEquals("", entry.oldValue)
        assertEquals("skiavk", entry.newValue)
    }

    @Test
    fun `restore returns to the original unset property after multiple switches`() {
        val raw = FakeExecutor()
        val journal = newJournal()
        val first = SystemRendererController.setRenderer(
            raw, journal, SystemRendererController.Target.VULKAN, 35, true
        )
        val second = SystemRendererController.setRenderer(
            raw, journal, SystemRendererController.Target.OPENGL, 35, true
        )
        assertEquals(SystemRendererController.Code.APPLIED, first.code)
        assertEquals(SystemRendererController.Code.APPLIED, second.code)
        assertEquals(1, journal.snapshot().size)
        assertEquals("", journal.snapshot().single().oldValue)
        assertEquals("opengl", raw.systemProperties[SystemRendererController.PROPERTY])

        val restored = SystemRendererController.restoreOriginal(raw, journal)

        assertEquals(SystemRendererController.Code.RESTORED, restored.code)
        assertEquals("", raw.systemProperties[SystemRendererController.PROPERTY])
        assertTrue(journal.isEmpty())
    }

    @Test
    fun `failed write leaves property unchanged and removes unneeded rollback entry`() {
        val raw = FakeExecutor().apply { failSystemPropertyWrite = true }
        val journal = newJournal()

        val result = SystemRendererController.setRenderer(
            raw, journal, SystemRendererController.Target.VULKAN, 35, true
        )

        assertEquals(SystemRendererController.Code.APPLY_FAILED, result.code)
        assertEquals("", raw.systemPropertyGet(SystemRendererController.PROPERTY))
        assertTrue(journal.isEmpty())
        assertFalse(result.success)
    }

    @Test
    fun `Vulkan is rejected when API or hardware support is missing`() {
        val raw = FakeExecutor()
        val journal = newJournal()

        val oldApi = SystemRendererController.setRenderer(
            raw, journal, SystemRendererController.Target.VULKAN, 28, true
        )
        val missingFeature = SystemRendererController.setRenderer(
            raw, journal, SystemRendererController.Target.VULKAN, 35, false
        )

        assertEquals(SystemRendererController.Code.UNSUPPORTED_ANDROID, oldApi.code)
        assertEquals(SystemRendererController.Code.VULKAN_UNAVAILABLE, missingFeature.code)
        assertEquals("", raw.systemPropertyGet(SystemRendererController.PROPERTY))
        assertTrue(journal.isEmpty())
    }

    @Test
    fun `unknown existing values and missing privilege fail closed`() {
        val raw = FakeExecutor().apply {
            systemProperties[SystemRendererController.PROPERTY] = "vendor-private-renderer"
        }
        val journal = newJournal()
        val unknown = SystemRendererController.setRenderer(
            raw, journal, SystemRendererController.Target.VULKAN, 35, true
        )
        assertEquals(SystemRendererController.Code.UNKNOWN_PROPERTY_VALUE, unknown.code)
        assertEquals("vendor-private-renderer", raw.systemPropertyGet(SystemRendererController.PROPERTY))

        raw.privileged = false
        val noPrivilege = SystemRendererController.setRenderer(
            raw, journal, SystemRendererController.Target.OPENGL, 35, true
        )
        assertEquals(SystemRendererController.Code.PRIVILEGE_REQUIRED, noPrivilege.code)
        assertTrue(journal.isEmpty())
    }

    @Test
    fun `already selected OpenGL alias does not create a journal entry`() {
        val raw = FakeExecutor().apply {
            systemProperties[SystemRendererController.PROPERTY] = "skiagl"
        }
        val journal = newJournal()

        val result = SystemRendererController.setRenderer(
            raw, journal, SystemRendererController.Target.OPENGL, 35, true
        )

        assertEquals(SystemRendererController.Code.NO_CHANGE, result.code)
        assertTrue(journal.isEmpty())
        assertEquals("skiagl", raw.systemPropertyGet(SystemRendererController.PROPERTY))
    }

    @Test
    fun `blocked rollback journal prevents renderer mutation`() {
        val dir = kotlin.io.path.createTempDirectory("nitro_renderer_corrupt_").toFile()
        dir.deleteOnExit()
        val file = File(dir, "renderer_journal.json").apply { writeText("not-json") }
        val journal = Journal(file)
        val raw = FakeExecutor()

        assertTrue(journal.recoveryBlocked)
        val result = SystemRendererController.setRenderer(
            raw, journal, SystemRendererController.Target.VULKAN, 35, true
        )

        assertEquals(SystemRendererController.Code.JOURNAL_BLOCKED, result.code)
        assertEquals("", raw.systemPropertyGet(SystemRendererController.PROPERTY))
        assertTrue(raw.written.isEmpty())
    }

    @Test
    fun `journal restore refuses a forged system-property entry`() {
        val raw = FakeExecutor().apply {
            systemProperties[SystemRendererController.PROPERTY] = "skiavk"
        }
        val forged = JournalEntry(
            taskId = "untrusted",
            kind = JournalEntry.Kind.SYSTEM_PROPERTY,
            key = SystemRendererController.PROPERTY,
            oldValue = "opengl",
            newValue = "skiavk"
        )

        assertFalse(Journal.restore(forged, raw))
        assertEquals("skiavk", raw.systemPropertyGet(SystemRendererController.PROPERTY))
    }
}

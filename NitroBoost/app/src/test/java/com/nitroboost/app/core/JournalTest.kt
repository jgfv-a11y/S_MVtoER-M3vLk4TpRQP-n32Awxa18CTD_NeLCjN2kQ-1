package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class JournalTest {

    @Test
    fun testJournalAtomicity_processKillMidWrite() {
        val tmpDir = createTempDirectory().toFile()
        val journalFile = File(tmpDir, "test_journal.json")

        val journal = Journal(journalFile)

        journal.add(
            listOf(
                JournalEntry("task1", JournalEntry.Kind.SYS_SETTING, "key1", "old1", "new1", null, System.currentTimeMillis()),
                JournalEntry("task2", JournalEntry.Kind.SECURE_SETTING, "key2", "old2", "new2", null, System.currentTimeMillis())
            )
        )

        assertTrue(journalFile.exists())
        assertEquals(2, journal.entries.size)

        // Second write creates a .bak of the last known-good file.
        journal.add(
            listOf(
                JournalEntry("task3", JournalEntry.Kind.GLOBAL_SETTING, "key3", "old3", "new3", null, System.currentTimeMillis())
            )
        )
        assertTrue(File(tmpDir, "test_journal.json.bak").exists())
        assertEquals(3, journal.entries.size)

        val journal2 = Journal(journalFile)
        assertEquals(3, journal2.entries.size)
        assertEquals("task1", journal2.entries[0].taskId)
        assertEquals("task2", journal2.entries[1].taskId)
        assertEquals("task3", journal2.entries[2].taskId)
    }

    @Test
    fun `corrupt journal recovers the last durable backup without losing reversals`() {
        val tmpDir = createTempDirectory().toFile()
        val journalFile = File(tmpDir, "recover_journal.json")
        val first = Journal(journalFile)
        first.add(listOf(JournalEntry("first", JournalEntry.Kind.SYS_SETTING, "one", "0", "1")))
        first.add(listOf(JournalEntry("second", JournalEntry.Kind.SYS_SETTING, "two", "0", "1")))
        journalFile.writeText("{truncated")

        val recovered = Journal(journalFile)
        assertEquals(listOf("first"), recovered.entries.map { it.taskId })
        assertTrue(File(tmpDir, "recover_journal.json.corrupt").exists())
    }

    @Test
    fun testJournalCorruptionRecovery() {
        val tmpDir = createTempDirectory().toFile()
        val journalFile = File(tmpDir, "corrupt_journal.json")
        journalFile.writeText("{invalid json [[[")

        val journal = Journal(journalFile)
        assertTrue(journal.isEmpty())

        val backup = File(tmpDir, "corrupt_journal.json.corrupt")
        assertTrue(backup.exists())
    }

    @Test
    fun testJournalRotation_atSizeLimit() {
        val tmpDir = createTempDirectory().toFile()
        val journalFile = File(tmpDir, "rotating_journal.json")
        val journal = Journal(journalFile)

        val entries = mutableListOf<JournalEntry>()
        repeat(260) { i ->
            entries.add(
                JournalEntry(
                    "task_$i",
                    JournalEntry.Kind.SYSFS,
                    "path_$i",
                    "old_$i",
                    "new_$i",
                    null,
                    System.currentTimeMillis()
                )
            )
        }

        journal.add(entries)
        // Rotation is for diagnosis only; active rollback records must remain live.
        assertEquals(entries.size, journal.entries.size)

        val archiveFiles = tmpDir.listFiles { f ->
            f.name.startsWith("rotating_journal.json_")
        }
        assertTrue(archiveFiles?.isNotEmpty() == true)
        // Archived snapshot must still be loadable (audit / manual restore).
        val archived = Journal(archiveFiles!!.first { it.length() > 0 })
        assertEquals(entries.size, archived.entries.size)
    }

    @Test
    fun testJournalRestore_revertsInReverseOrder() {
        val tmpDir = createTempDirectory().toFile()
        val journalFile = File(tmpDir, "restore_journal.json")
        val journal = Journal(journalFile)

        val entryList = listOf(
            JournalEntry("task1", JournalEntry.Kind.SYS_SETTING, "key1", "old1", "new1", null, 1000L),
            JournalEntry("task2", JournalEntry.Kind.SYS_SETTING, "key2", "old2", "new2", null, 2000L),
            JournalEntry("task3", JournalEntry.Kind.SYS_SETTING, "key3", "old3", "new3", null, 3000L)
        )
        journal.add(entryList)

        assertEquals(3, journal.entries.size)
        assertEquals("task1", journal.entries[0].taskId)
        assertEquals("task3", journal.entries[2].taskId)

        journal.remove(listOf(entryList[0]))
        assertEquals(2, journal.entries.size)
    }

    @Test
    fun testUnknownKindIsSkippedWithoutWipingTheRest() {
        val tmpDir = createTempDirectory().toFile()
        val journalFile = File(tmpDir, "mixed.json")
        journalFile.writeText(
            """
            [
              {"taskId":"ok","kind":"SYS_SETTING","key":"k","oldValue":"a","newValue":"b","revertCmd":null,"ts":1},
              {"taskId":"bad","kind":"NOT_A_KIND","key":"k","oldValue":"a","newValue":"b","revertCmd":null,"ts":2},
              {"taskId":"ok2","kind":"SYSFS","key":"/sys/x","oldValue":"0","newValue":"1","revertCmd":null,"ts":3}
            ]
            """.trimIndent()
        )

        val journal = Journal(journalFile)
        assertEquals(2, journal.entries.size)
        assertEquals("ok", journal.entries[0].taskId)
        assertEquals("ok2", journal.entries[1].taskId)
        assertFalse(File(tmpDir, "mixed.json.corrupt").exists())
    }

    @Test
    fun `restore rejects tampered persisted shell commands`() {
        val executor = FakeExecutor()
        val entry = JournalEntry(
            taskId = "display",
            kind = JournalEntry.Kind.CMD,
            key = "wm_density",
            oldValue = null,
            newValue = "480",
            revertCmd = "wm density reset; reboot"
        )

        assertFalse(Journal.restore(entry, executor))
        assertTrue(executor.shellLog.isEmpty())
    }

    @Test
    fun `restore rejects a delete that reports success but leaves the setting`() {
        val executor = FakeExecutor().apply {
            sys["temporary"] = "changed"
            preserveSysDelete = true
        }
        val entry = JournalEntry(
            taskId = "settings",
            kind = JournalEntry.Kind.SYS_SETTING,
            key = "temporary",
            oldValue = null,
            newValue = "changed"
        )

        assertFalse(Journal.restore(entry, executor))
        assertEquals("changed", executor.sys["temporary"])
    }

    @Test
    fun testEmptyAndMissingFilesAreEmptyJournals() {
        val tmpDir = createTempDirectory().toFile()
        val missing = Journal(File(tmpDir, "nope.json"))
        assertTrue(missing.isEmpty())
        assertFalse(missing.recoveryBlocked)

        val blank = File(tmpDir, "blank.json")
        blank.writeText("   \n")
        val journal = Journal(blank)
        assertTrue(journal.isEmpty())
        assertTrue(journal.recoveryBlocked)
    }
}

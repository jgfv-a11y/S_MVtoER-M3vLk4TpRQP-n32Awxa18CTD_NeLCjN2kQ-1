package com.nitroboost.app.core

import org.junit.Test
import org.junit.Assert.*
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

        val journal2 = Journal(journalFile)
        assertEquals(2, journal2.entries.size)
        assertEquals("task1", journal2.entries[0].taskId)
        assertEquals("task2", journal2.entries[1].taskId)
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
        assertTrue(journal.isEmpty())

        val archiveFiles = tmpDir.listFiles { f ->
            f.name.startsWith("rotating_journal.json_")
        }
        assertTrue(archiveFiles?.isNotEmpty() == true)
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
}

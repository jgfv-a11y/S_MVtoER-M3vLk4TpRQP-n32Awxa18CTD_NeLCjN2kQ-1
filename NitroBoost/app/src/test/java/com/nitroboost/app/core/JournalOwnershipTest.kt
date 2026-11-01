package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ownership rule for Journal.restore (v1.23.0): the journal only undoes the
 * change THIS app made. When a readable current value is neither the journaled
 * newValue (our change, still live) nor the original oldValue, the state was
 * changed by the user, the system, or another app — restore must NOT clobber
 * it, and must report the undo obligation as complete.
 */
class JournalOwnershipTest {

    private fun journal(): Journal {
        val f = File.createTempFile("nitro_own", ".json")
        f.delete()
        f.deleteOnExit()
        return Journal(f)
    }

    // ---------------- sysfs ----------------

    @Test
    fun `sysfs restore skips writing over a third-party value`() {
        val ex = FakeExecutor()
        val j = journal()
        val entry = JournalEntry(
            taskId = "cpu_governor",
            kind = JournalEntry.Kind.SYSFS,
            key = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor",
            oldValue = "schedutil",
            newValue = "performance"
        )
        j.add(listOf(entry))

        // We applied "performance"; afterwards the user (or another app) wrote
        // "powersave". Restoring must respect that owner, not write schedutil.
        ex.sysfs[entry.key] = "powersave"
        assertTrue(Journal.restore(entry, ex))
        assertEquals("powersave", ex.readSys(entry.key))
    }

    @Test
    fun `sysfs restore undoes our value when it is still live`() {
        val ex = FakeExecutor()
        val entry = JournalEntry(
            taskId = "cpu_governor",
            kind = JournalEntry.Kind.SYSFS,
            key = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor",
            oldValue = "schedutil",
            newValue = "performance"
        )
        ex.sysfs[entry.key] = "performance"
        assertTrue(Journal.restore(entry, ex))
        assertEquals("schedutil", ex.readSys(entry.key))
    }

    @Test
    fun `sysfs restore is a no-op success when already back at the original`() {
        val ex = FakeExecutor()
        val entry = JournalEntry(
            taskId = "cpu_governor",
            kind = JournalEntry.Kind.SYSFS,
            key = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor",
            oldValue = "schedutil",
            newValue = "performance"
        )
        ex.sysfs[entry.key] = "schedutil"
        assertTrue(Journal.restore(entry, ex))
        assertEquals("schedutil", ex.readSys(entry.key))
    }

    @Test
    fun `sysfs restore keeps best-effort behaviour when the node is unreadable`() {
        val ex = FakeExecutor()
        val entry = JournalEntry(
            taskId = "cpu_governor",
            kind = JournalEntry.Kind.SYSFS,
            key = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor",
            oldValue = "schedutil",
            newValue = "performance"
        )
        // Value never existed on this executor (readSys returns null), and the
        // write fails: the historical best-effort path must still be attempted
        // and must report failure so the entry stays available for retry.
        val failingWrite = object : SystemExecutor by ex {
            override fun writeSys(path: String, value: String): Boolean = false
        }
        assertFalse(Journal.restore(entry, failingWrite))
    }

    // ---------------- settings ----------------

    @Test
    fun `secure setting restore skips writing over a third-party value`() {
        val ex = FakeExecutor()
        val entry = JournalEntry(
            taskId = "game_mode",
            kind = JournalEntry.Kind.SECURE_SETTING,
            key = "game_mode",
            oldValue = "1",
            newValue = "4"
        )
        ex.secure[entry.key] = "5" // changed externally during the session
        assertTrue(Journal.restore(entry, ex))
        assertEquals("5", ex.secureSettingGet(entry.key))
    }

    @Test
    fun `system setting restore undoes our value when still live`() {
        val ex = FakeExecutor()
        val entry = JournalEntry(
            taskId = "peak_brightness",
            kind = JournalEntry.Kind.SYS_SETTING,
            key = "screen_brightness",
            oldValue = "128",
            newValue = "255"
        )
        ex.sys[entry.key] = "255"
        assertTrue(Journal.restore(entry, ex))
        assertEquals("128", ex.sysSettingGet(entry.key))
    }

    @Test
    fun `global setting externally deleted is treated as undone`() {
        val ex = FakeExecutor()
        val entry = JournalEntry(
            taskId = "network",
            kind = JournalEntry.Kind.GLOBAL_SETTING,
            key = "some_global_key",
            oldValue = "2",
            newValue = "0"
        )
        ex.global[entry.key] = "0"
        // External actor deletes our key entirely; restoring must not re-create it.
        ex.global.remove(entry.key)
        assertTrue(Journal.restore(entry, ex))
        assertEquals(null, ex.globalSettingGet(entry.key))
    }

    @Test
    fun `setting restore with pre-session absent value deletes only our live value`() {
        val ex = FakeExecutor()
        val entry = JournalEntry(
            taskId = "settings",
            kind = JournalEntry.Kind.SYS_SETTING,
            key = "temporary",
            oldValue = null,
            newValue = "changed"
        )
        ex.sys["temporary"] = "changed"
        assertTrue(Journal.restore(entry, ex))
        assertEquals(null, ex.sysSettingGet("temporary"))
    }

    // ---------------- DND ----------------

    @Test
    fun `dnd restore skips when the user changed the filter during the session`() {
        val ex = FakeExecutor()
        val entry = JournalEntry(
            taskId = "dnd",
            kind = JournalEntry.Kind.DND,
            key = "interruption_filter",
            oldValue = DndFilters.ALL.toString(),
            newValue = DndFilters.NONE.toString()
        )
        ex.dndFilter = DndFilters.PRIORITY // user picked priority during the session
        assertTrue(Journal.restore(entry, ex))
        assertEquals(DndFilters.PRIORITY, ex.dndFilterGet())
    }

    @Test
    fun `dnd restore undoes our filter when still live`() {
        val ex = FakeExecutor()
        val entry = JournalEntry(
            taskId = "dnd",
            kind = JournalEntry.Kind.DND,
            key = "interruption_filter",
            oldValue = DndFilters.ALL.toString(),
            newValue = DndFilters.NONE.toString()
        )
        ex.dndFilter = DndFilters.NONE
        assertTrue(Journal.restore(entry, ex))
        assertEquals(DndFilters.ALL, ex.dndFilterGet())
    }
}

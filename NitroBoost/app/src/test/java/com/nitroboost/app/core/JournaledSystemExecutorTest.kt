package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JournaledSystemExecutorTest {

    private fun missingJournalFile(name: String): File {
        val directory = kotlin.io.path.createTempDirectory("nitro_wal_").toFile()
        directory.deleteOnExit()
        return File(directory, name)
    }

    @Test
    fun `settings shell write is durably journaled before delegate mutation`() {
        val raw = FakeExecutor().apply { secure["game_mode"] = "0" }
        val journal = Journal(missingJournalFile("settings.json"))
        var sawWriteAheadEntry = false
        val observed = object : SystemExecutor by raw {
            override fun shell(cmd: String): ShellResult {
                if (cmd == "settings put secure game_mode 2") {
                    sawWriteAheadEntry = journal.file.exists() &&
                        journal.containsTaskKey("game_mode", "game_mode")
                }
                return raw.shell(cmd)
            }
        }
        val executor = BoostContext(testProfile(Module.CPU), observed, journal)
            .forTask("game_mode").executor

        val result = executor.shell("settings put secure game_mode 2")

        assertTrue(result.ok)
        assertTrue("write-ahead record must exist when the platform write begins", sawWriteAheadEntry)
        assertEquals("2", raw.secure["game_mode"])
        assertEquals("0", journal.snapshot().single().oldValue)
    }

    @Test
    fun `setting write fails closed when write-ahead persistence fails`() {
        val blocker = File.createTempFile("nitro_wal_blocker", ".tmp")
        val journal = Journal(File(blocker, "journal.json"))
        val raw = FakeExecutor().apply { sys["min_refresh_rate"] = "60" }
        val executor = BoostContext(testProfile(Module.DISPLAY), raw, journal)
            .forTask("display").executor

        try {
            assertFalse(executor.sysSettingPut("min_refresh_rate", "90"))
            assertEquals("60", raw.sys["min_refresh_rate"])
            assertTrue(journal.containsTaskKey("display", "min_refresh_rate"))
            assertTrue(raw.written.isEmpty())
        } finally {
            blocker.delete()
        }
    }

    @Test
    fun `typed mutation records original value before changing sysfs`() {
        val path = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"
        val raw = FakeExecutor().apply { sysfs[path] = "schedutil" }
        val journal = Journal(missingJournalFile("sysfs.json"))
        var sawWriteAheadEntry = false
        val observed = object : SystemExecutor by raw {
            override fun writeSys(path: String, value: String): Boolean {
                sawWriteAheadEntry = journal.containsTaskKey("cpu_governor", path)
                return raw.writeSys(path, value)
            }
        }
        val executor = BoostContext(testProfile(Module.CPU), observed, journal)
            .forTask("cpu_governor").executor

        assertTrue(executor.writeSys(path, "performance"))

        assertTrue("journal must be durable before sysfs write", sawWriteAheadEntry)
        assertEquals("performance", raw.readSys(path))
        assertEquals("schedutil", journal.snapshot().single().oldValue)
    }
}

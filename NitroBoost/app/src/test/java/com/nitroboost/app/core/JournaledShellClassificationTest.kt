package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JournaledShellClassificationTest {

    private fun journal(): Journal {
        val f = File.createTempFile("nitro_classify", ".json")
        f.delete()
        f.deleteOnExit()
        return Journal(f)
    }

    private fun executorFor(taskId: String, raw: FakeExecutor): SystemExecutor =
        BoostContext(testProfile(Module.TWEAKS), raw, journal()).forTask(taskId).executor

    @Test
    fun `read-only fallthrough reaches the delegate`() {
        val raw = FakeExecutor()
        val executor = executorFor("display", raw)

        val result = executor.shell("wm density")

        assertTrue(result.ok)
        assertEquals(listOf("wm density"), raw.shellLog)
    }

    @Test
    fun `unknown shell command fails closed and never reaches the delegate`() {
        val raw = FakeExecutor()
        val executor = executorFor("display", raw)

        val result = executor.shell("reboot")

        assertFalse(result.ok)
        assertEquals("unclassified_shell_command", result.stderr)
        assertTrue("delegate must not see unclassified commands", raw.shellLog.isEmpty())
    }

    @Test
    fun `unhandled mutation fails closed even when the policy knows the shape`() {
        val raw = FakeExecutor()
        val executor = executorFor("display", raw)

        // Sysfs writes must go through the typed writeSys journal path, not
        // a raw echo fallthrough.
        val result = executor.shell(
            "echo \"1\" > \"/sys/class/kgsl/kgsl-3d0/force_clk_on\" 2>/dev/null"
        )

        assertFalse(result.ok)
        assertEquals("unhandled_shell_mutation", result.stderr)
        assertTrue(raw.shellLog.isEmpty())
    }

    @Test
    fun `one-way trim is admitted only for its owning task`() {
        val raw = FakeExecutor()
        val owner = executorFor("ram_trim", raw)
        assertTrue(owner.shell("pm trim-caches 9223372036854775807").ok)

        val raw2 = FakeExecutor()
        val other = executorFor("display", raw2)
        val denied = other.shell("pm trim-caches 9223372036854775807")
        assertFalse(denied.ok)
        assertEquals("unowned_one_way_command", denied.stderr)
        assertTrue(raw2.shellLog.isEmpty())
    }

    @Test
    fun `one-way force-stop is admitted only for ram_kill with a valid package`() {
        val raw = FakeExecutor()
        val owner = executorFor("ram_kill", raw)
        assertTrue(owner.shell("am force-stop \"com.test.game\"").ok)

        val raw2 = FakeExecutor()
        val other = executorFor("ram_trim", raw2)
        assertFalse(other.shell("am force-stop \"com.test.game\"").ok)
        assertTrue(raw2.shellLog.isEmpty())

        val raw3 = FakeExecutor()
        val badPkg = executorFor("ram_kill", raw3)
        assertFalse(badPkg.shell("am force-stop \"com.test;reboot\"").ok)
        assertTrue(raw3.shellLog.isEmpty())
    }
}

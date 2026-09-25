package com.nitroboost.app.core

import com.nitroboost.app.core.tasks.GovernorTask
import com.nitroboost.app.platform.RootShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.4.0: privileged tasks that cannot be verified yet (no Shizuku/root)
 * must report PENDING ("waiting for Shizuku"), not "not supported" — the
 * hardware may well support them, we just cannot probe it yet.
 */
class PendingStateTest {

    private val govPath = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"

    private fun ctx(privileged: Boolean, withGovernorFile: Boolean): BoostContext {
        val ex = FakeExecutor()
        ex.privileged = privileged
        if (withGovernorFile) ex.sysfs[govPath] = "schedutil"
        val f = File.createTempFile("nitro_pending", ".json")
        f.deleteOnExit()
        return BoostContext(testProfile(Module.CPU), ex, Journal(f))
    }

    @Test
    fun `without privilege the governor task is pending not unsupported`() {
        val engine = BoostEngine(listOf(GovernorTask()))
        val state = engine.states(ctx(privileged = false, withGovernorFile = false))[0]

        assertFalse(state.supported)
        assertTrue(state.pending)
        assertTrue(state.requiresPrivilege)
    }

    @Test
    fun `with privilege and the sysfs present it is supported and not pending`() {
        val engine = BoostEngine(listOf(GovernorTask()))
        val state = engine.states(ctx(privileged = true, withGovernorFile = true))[0]

        assertTrue(state.supported)
        assertFalse(state.pending)
    }

    @Test
    fun `non-privileged tasks never become pending`() {
        // DndTask is a normal-privilege task: unsupported (never) => plain
        // unsupported, pending must stay false.
        val ex = FakeExecutor()
        ex.privileged = true
        val f = File.createTempFile("nitro_pending2", ".json")
        f.deleteOnExit()
        val ctx = BoostContext(testProfile(Module.DND, dnd = false), ex, Journal(f))
        val engine = BoostEngine(listOf(com.nitroboost.app.core.tasks.DndTask()))
        val state = engine.states(ctx)[0]
        assertFalse(state.pending)
    }
}

/** Root probe parsing is pure — test it directly. */
class RootProbeParseTest {

    @Test
    fun `uid zero proves root`() {
        assertTrue(RootShell.parseProbeOutput("uid=0(root) gid=0(root) groups=0(root)"))
    }

    @Test fun `empty or failed output is no root`() {
        assertFalse(RootShell.parseProbeOutput(""))
        assertFalse(RootShell.parseProbeOutput("in: invalid user"))
        assertFalse(RootShell.parseProbeOutput("uid=1000(app) gid=1000(app)"))
    }
}

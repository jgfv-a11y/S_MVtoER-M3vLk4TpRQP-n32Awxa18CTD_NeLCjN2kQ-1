package com.nitroboost.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract between the two hand-maintained lists that must never drift:
 * the revertCmd strings tasks produce, and Journal's exact-match restore
 * allowlist (Journal.safeRevertCommand). If a task's revert command stops
 * being restorable, this test fails instead of a user's journal silently
 * accumulating entries that can never be undone.
 */
class RevertCommandContractTest {

    private val pkg = "com.test.game"

    @Test
    fun `every task-produced revert command is restorable`() {
        val ex = FakeExecutor()
        ex.physicalDensity = 420
        ex.densityOverride = 320

        val entries = listOf(
            JournalEntry(
                "game_api_downscale", JournalEntry.Kind.CMD, "game_api:$pkg",
                "none", "--downscale 0.8", "cmd game reset $pkg 2>/dev/null"
            ),
            JournalEntry(
                "game_perf_mode", JournalEntry.Kind.CMD, "game_mode_api:$pkg",
                "1", "2", "cmd game set --mode 1 $pkg 2>/dev/null"
            ),
            JournalEntry(
                "device_idle", JournalEntry.Kind.CMD, "device_idle:$pkg",
                null, "whitelisted", "cmd deviceidle whitelist-remove $pkg 2>/dev/null"
            ),
            JournalEntry(
                "doze_whitelist", JournalEntry.Kind.CMD, "doze_whitelist:$pkg",
                "absent", "present", "cmd deviceidle whitelist -$pkg 2>/dev/null"
            ),
            JournalEntry(
                "display", JournalEntry.Kind.CMD, "wm_density",
                null, "320", "wm density reset"
            ),
            JournalEntry(
                "thermal_override", JournalEntry.Kind.THERMAL_OVERRIDE, "thermal_override",
                "-1", "0", "cmd thermalservice override-status -1"
            )
        )

        for (entry in entries) {
            assertTrue(
                "revert for ${entry.taskId} must be accepted: ${entry.revertCmd}",
                Journal.restore(entry, ex)
            )
        }
    }

    @Test
    fun `tampered or extended revert commands stay unrestorable`() {
        val ex = FakeExecutor()
        val tampered = listOf(
            JournalEntry(
                "game_api_downscale", JournalEntry.Kind.CMD, "game_api:$pkg",
                "none", "--downscale 0.8", "cmd game reset $pkg 2>/dev/null; reboot"
            ),
            JournalEntry(
                "doze_whitelist", JournalEntry.Kind.CMD, "doze_whitelist:$pkg",
                "absent", "present", "cmd deviceidle whitelist -com.evil.app 2>/dev/null"
            ),
            JournalEntry(
                "thermal_override", JournalEntry.Kind.THERMAL_OVERRIDE, "thermal_override",
                "-1", "0", "cmd thermalservice override-status 0"
            )
        )
        for (entry in tampered) {
            assertFalse(
                "tampered revert must be refused: ${entry.revertCmd}",
                Journal.restore(entry, ex)
            )
        }
        assertTrue("refused reverts must not execute anything", ex.written.isEmpty())
    }
}

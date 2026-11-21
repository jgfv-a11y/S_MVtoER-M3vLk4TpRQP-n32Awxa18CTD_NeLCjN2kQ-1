package com.nitroboost.app.core

import com.nitroboost.app.core.PrivilegedShellPolicy.Disposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedShellPolicyTest {

    @Test
    fun `read-only commands used by tasks are classified read-only`() {
        val readOnly = listOf(
            "wm density",
            "settings get system min_refresh_rate",
            "settings get secure game_mode",
            "settings get global low_power",
            "cmd deviceidle help 2>&1",
            "cmd game help 2>&1",
            "cmd deviceidle whitelist",
            "dumpsys deviceidle whitelist",
            "dumpsys gfxinfo \"com.test.game\" framestats 2>/dev/null",
            "ps -A -o PID,RSS,NAME",
            "cat \"/sys/class/thermal/thermal_zone0/temp\" 2>/dev/null",
            "for d in /sys/devices/system/cpu/cpufreq/policy*; do " +
                "[ -d \"\$d\" ] && echo \"\$d \$(cat \$d/cpuinfo_max_freq 2>/dev/null) " +
                "\$(cat \$d/scaling_min_freq 2>/dev/null)\"; done"
        )
        for (cmd in readOnly) {
            assertEquals(cmd, Disposition.READ_ONLY, PrivilegedShellPolicy.classify(cmd))
            assertTrue(cmd, PrivilegedShellPolicy.isAllowed(cmd))
        }
    }

    @Test
    fun `journaled mutations and restores are classified mutating`() {
        val mutating = listOf(
            "settings put secure game_mode 4",
            "settings put global low_power 0",
            "settings delete system window_animation_scale",
            "wm density 320",
            "wm density reset",
            "cmd game set --downscale 0.8 com.test.game 2>&1",
            "cmd game set --mode 2 com.test.game 2>&1",
            "cmd game set --mode 1 com.test.game 2>/dev/null",
            "cmd game reset com.test.game 2>/dev/null",
            "cmd deviceidle whitelist com.test.game 2>&1",
            "cmd deviceidle whitelist +com.test.game 2>/dev/null",
            "cmd deviceidle whitelist -com.test.game 2>/dev/null",
            "cmd deviceidle whitelist-remove com.test.game 2>/dev/null",
            "cmd thermalservice override-status -1",
            "echo \"performance\" > \"/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor\" 2>/dev/null"
        )
        for (cmd in mutating) {
            assertEquals(cmd, Disposition.MUTATING, PrivilegedShellPolicy.classify(cmd))
        }
    }

    @Test
    fun `one-way commands are classified one-way`() {
        assertEquals(
            Disposition.ONE_WAY,
            PrivilegedShellPolicy.classify("pm trim-caches 9223372036854775807")
        )
        assertEquals(
            Disposition.ONE_WAY,
            PrivilegedShellPolicy.classify("am force-stop \"com.test.game\"")
        )
        assertEquals("com.test.game", PrivilegedShellPolicy.forceStopPackage("am force-stop \"com.test.game\""))
    }

    @Test
    fun `unknown and injected commands are rejected`() {
        val rejected = listOf(
            "",
            "reboot",
            "settings put system x 1; reboot",
            "settings get system min_refresh_rate; cat /etc/passwd",
            "am force-stop \"com.test.game\"; reboot",
            "am force-stop com.test.game",
            "am force-stop \"com.test;reboot\"",
            "cmd game set --mode 3 com.test.game 2>&1",
            "cmd game set --downscale 0.5 com.test.game 2>&1",
            "echo \"1\" > \"/etc/passwd\" 2>/dev/null",
            "echo \"\$(reboot)\" > \"/sys/kernel/test\" 2>/dev/null",
            "cat \"/etc/passwd\" 2>/dev/null",
            "dumpsys gfxinfo \"com.test;rm\" framestats 2>/dev/null",
            "settings put system min_refresh_rate 90 extra",
            "wm density 320; reboot",
            "cmd thermalservice override-status 1"
        )
        for (cmd in rejected) {
            assertEquals(cmd, Disposition.UNKNOWN, PrivilegedShellPolicy.classify(cmd))
            assertFalse(cmd, PrivilegedShellPolicy.isAllowed(cmd))
        }
    }
}

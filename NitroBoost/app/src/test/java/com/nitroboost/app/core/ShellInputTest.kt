package com.nitroboost.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellInputTest {
    @Test
    fun `accepts ordinary package names and rejects shell syntax`() {
        assertTrue(ShellInput.isPackageName("com.example.game_2"))
        assertFalse(ShellInput.isPackageName("com.example;reboot"))
        assertFalse(ShellInput.isPackageName("com.example$(id)"))
        assertFalse(ShellInput.isPackageName("../com.example"))
    }

    @Test
    fun `sys paths stay within tuning trees without traversal`() {
        assertTrue(ShellInput.isSysPath("/sys/devices/system/cpu/cpu0/online"))
        assertTrue(ShellInput.isSysPath("/proc/sys/kernel/sched_latency_ns"))
        assertFalse(ShellInput.isSysPath("/etc/passwd"))
        assertFalse(ShellInput.isSysPath("/sys/../etc/passwd"))
        assertFalse(ShellInput.isSysPath("/sys/class/x;reboot"))
    }

    @Test
    fun `shell values reject substitutions and whitespace`() {
        assertTrue(ShellInput.isSysValue("schedutil"))
        assertFalse(ShellInput.isSysValue("$(reboot)"))
        assertFalse(ShellInput.isSysValue("value;reboot"))
        assertFalse(ShellInput.isSettingKey("system;reboot"))
        assertFalse(ShellInput.isSettingValue("1; reboot"))
    }
}

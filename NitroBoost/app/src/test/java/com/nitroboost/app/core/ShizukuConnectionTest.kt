package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuConnectionTest {

    private fun resolve(
        installed: Boolean = true,
        binderAlive: Boolean = true,
        permissionGranted: Boolean = true,
        bound: Boolean = true,
        everReady: Boolean = false
    ) = ShizukuConnectionResolver.resolve(installed, binderAlive, permissionGranted, bound, everReady)

    @Test
    fun `not installed is disconnected`() {
        assertEquals(ShizukuConnection.DISCONNECTED, resolve(installed = false))
    }

    @Test
    fun `binder dead without prior session is disconnected`() {
        assertEquals(ShizukuConnection.DISCONNECTED, resolve(binderAlive = false, everReady = false))
    }

    @Test
    fun `binder dead after a ready session is session expired`() {
        assertEquals(
            ShizukuConnection.SESSION_EXPIRED,
            resolve(binderAlive = false, everReady = true)
        )
    }

    @Test
    fun `binder alive without permission is connected no permission`() {
        assertEquals(
            ShizukuConnection.CONNECTED_NO_PERMISSION,
            resolve(permissionGranted = false, bound = false)
        )
    }

    @Test
    fun `binder alive with permission but unbound is not granted`() {
        // granted permission but the user service bind failed: NOT "connected granted"
        assertEquals(
            ShizukuConnection.CONNECTED_NO_PERMISSION,
            resolve(permissionGranted = true, bound = false)
        )
    }

    @Test
    fun `fully ready maps to connected granted`() {
        assertEquals(
            ShizukuConnection.CONNECTED_GRANTED,
            resolve(everReady = true)
        )
    }

    @Test
    fun `session expiry wins over missing app on next poll`() {
        // Shizuku uninstalled after a session: still reported as expired, not plain disconnected,
        // so the UI can say "session ended" rather than "never connected".
        assertEquals(
            ShizukuConnection.SESSION_EXPIRED,
            resolve(installed = false, everReady = true)
        )
    }

    @Test
    fun `command result only settles from running via real exit code`() {
        assertEquals(CommandStatus.SUCCEEDED, ShizukuConnectionResolver.commandResult(CommandStatus.RUNNING, 0, null))
        assertEquals(CommandStatus.FAILED, ShizukuConnectionResolver.commandResult(CommandStatus.RUNNING, 1, "denied"))
        // a result arriving while not running is ignored (never fake a success)
        assertEquals(CommandStatus.IDLE, ShizukuConnectionResolver.commandResult(CommandStatus.IDLE, 0, null))
        assertEquals(CommandStatus.SUCCEEDED, ShizukuConnectionResolver.commandResult(CommandStatus.SUCCEEDED, 1, null))
    }

    @Test
    fun `idle state default is disconnected and idle`() {
        assertEquals(ShizukuConnection.DISCONNECTED, ShizukuUiState.IDLE.connection)
        assertEquals(CommandStatus.IDLE, ShizukuUiState.IDLE.command)
    }
}

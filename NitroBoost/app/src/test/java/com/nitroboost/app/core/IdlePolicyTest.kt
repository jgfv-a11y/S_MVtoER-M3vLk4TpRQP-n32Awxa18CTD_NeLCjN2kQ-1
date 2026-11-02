package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdlePolicyTest {

    @Test
    fun `monitor runs at 1s during a session`() {
        assertEquals(IdlePolicy.MONITOR_ACTIVE_MS, IdlePolicy.monitorIntervalMs(uiVisible = true, sessionActive = true, overlayVisible = false))
    }

    @Test
    fun `monitor runs at 1s when only the overlay is visible`() {
        assertEquals(IdlePolicy.MONITOR_ACTIVE_MS, IdlePolicy.monitorIntervalMs(uiVisible = false, sessionActive = false, overlayVisible = true))
    }

    @Test
    fun `monitor slows to 5s when only the UI is visible and nothing is active`() {
        assertEquals(IdlePolicy.MONITOR_IDLE_MS, IdlePolicy.monitorIntervalMs(uiVisible = true, sessionActive = false, overlayVisible = false))
    }

    @Test
    fun `monitor stops entirely when nothing consumes samples`() {
        assertNull(IdlePolicy.monitorIntervalMs(uiVisible = false, sessionActive = false, overlayVisible = false))
    }

    @Test
    fun `network probe is blocked at idle`() {
        assertFalse(IdlePolicy.networkProbeAllowed(sessionActive = false, overlayVisible = false))
        assertTrue(IdlePolicy.networkProbeAllowed(sessionActive = true, overlayVisible = false))
        assertTrue(IdlePolicy.networkProbeAllowed(sessionActive = false, overlayVisible = true))
    }

    @Test
    fun `shizuku poller is fast while not ready or card visible and slow when ready and hidden`() {
        assertEquals(IdlePolicy.SHIZUKU_POLL_ACTIVE_MS, IdlePolicy.shizukuPollDelayMs(connectionReady = false, cardVisible = false))
        assertEquals(IdlePolicy.SHIZUKU_POLL_ACTIVE_MS, IdlePolicy.shizukuPollDelayMs(connectionReady = true, cardVisible = true))
        assertEquals(IdlePolicy.SHIZUKU_POLL_IDLE_MS, IdlePolicy.shizukuPollDelayMs(connectionReady = true, cardVisible = false))
    }

    @Test
    fun `usable check does not require a shell command`() {
        assertTrue(IdlePolicy.usableWithoutCommand(binderAlive = true, bound = true))
        assertFalse(IdlePolicy.usableWithoutCommand(binderAlive = true, bound = false))
        assertFalse(IdlePolicy.usableWithoutCommand(binderAlive = false, bound = true))
    }
}

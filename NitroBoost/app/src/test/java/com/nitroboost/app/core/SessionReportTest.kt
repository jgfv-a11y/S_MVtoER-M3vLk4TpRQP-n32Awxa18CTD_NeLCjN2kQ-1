package com.nitroboost.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionReportTest {

    @Test
    fun `summarize computes averages peaks and delta`() {
        val rep = SessionReportBuilder.summarize(
            startedAt = 1_000L,
            endedAt = 61_000L,
            fpsSamples = listOf(50, 60, 70),
            tempSamples = listOf(35, 42, 38),
            pingSamples = listOf(24, 12, 30),
            ramMbSamples = listOf(2048, 2300, 2100),
            applied = 12,
            failed = 1,
            previousAvgFps = 55
        )
        assertEquals(60, rep.avgFps)
        assertEquals(50, rep.minFps)
        assertEquals(42, rep.peakTempC)
        assertEquals(12, rep.minPingMs)
        assertEquals(2300, rep.peakRamMb)
        assertEquals(50, rep.durationSec)
        assertEquals(5, rep.deltaFps)
        assertEquals(12, rep.applied)
        assertEquals(1, rep.failed)
        assertEquals(55, rep.previousAvgFps)
    }

    @Test
    fun `empty samples produce nulls and no delta`() {
        val rep = SessionReportBuilder.summarize(
            0L, 5_000L, emptyList(), emptyList(), emptyList(), emptyList(),
            applied = 0, failed = 0, previousAvgFps = null
        )
        assertNull(rep.avgFps)
        assertNull(rep.minFps)
        assertNull(rep.peakTempC)
        assertNull(rep.minPingMs)
        assertNull(rep.deltaFps)
        assertEquals(0, rep.peakRamMb)
        assertEquals(5, rep.durationSec)
    }

    @Test
    fun `delta can be negative when the boost hurt`() {
        val rep = SessionReportBuilder.summarize(
            0L, 10_000L,
            fpsSamples = listOf(30, 32),
            tempSamples = emptyList(),
            pingSamples = emptyList(),
            ramMbSamples = emptyList(),
            applied = 0, failed = 0,
            previousAvgFps = 60
        )
        assertEquals(-29, rep.deltaFps)
    }

    @Test
    fun `duration never negative on clock skew`() {
        val rep = SessionReportBuilder.summarize(
            100L, 50L, emptyList(), emptyList(), emptyList(), emptyList(),
            applied = 0, failed = 0, previousAvgFps = null
        )
        assertEquals(0, rep.durationSec)
    }
}
